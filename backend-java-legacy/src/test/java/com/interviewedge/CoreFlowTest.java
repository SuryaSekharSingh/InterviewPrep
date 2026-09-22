package com.interviewedge;

import static org.junit.jupiter.api.Assertions.*;

import com.interviewedge.assessment.*;
import com.interviewedge.common.*;
import com.interviewedge.content.*;
import com.interviewedge.identity.*;
import com.interviewedge.progress.*;
import com.interviewedge.tests.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=${EDGE_TEST_DB_URL:jdbc:h2:mem:edge;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
      "spring.datasource.driver-class-name=${EDGE_TEST_DB_DRIVER:org.h2.Driver}",
      "spring.datasource.username=${EDGE_TEST_DB_USER:sa}",
      "spring.datasource.password=${EDGE_TEST_DB_PASSWORD:}",
      "edge.jobs-enabled=false",
      "edge.media-root=target/test-media"
    })
class CoreFlowTest {
  @Autowired LocalAccounts accounts;
  @Autowired IdentityVerifier identities;
  @Autowired Profiles profiles;
  @Autowired ContentCatalog content;
  @Autowired TestService tests;
  @Autowired JdbcTemplate db;
  @Autowired Activities activities;
  @Autowired AssessmentEvents events;
  @Autowired ProgressService progress;
  @Autowired com.interviewedge.jobs.Jobs jobs;
  @Autowired com.interviewedge.media.MediaService media;
  @Autowired com.interviewedge.interviews.InterviewService interviews;
  @Autowired AccountService accountControls;

  @Value("${local.server.port}")
  int port;

  String username() {
    return "student_" + UUID.randomUUID().toString().substring(0, 8);
  }

  LocalAccounts.Session register() {
    return accounts.register(username(), "correct horse battery staple");
  }

  @Test
  void recoveryCodesAreSingleUseAndRevokeOldSessions() {
    String name = username();
    var first = accounts.register(name, "a secure initial password");
    assertEquals(first.userId(), identities.verify(first.token()).uid());
    assertEquals(8, first.recoveryCodes().size());
    var recovered = accounts.recover(name, first.recoveryCodes().get(0), "a replacement password");
    assertThrows(ApiException.class, () -> identities.verify(first.token()));
    assertEquals(first.userId(), identities.verify(recovered.token()).uid());
    assertThrows(
        ApiException.class,
        () -> accounts.recover(name, first.recoveryCodes().get(0), "another secure password"));
    assertThrows(ApiException.class, () -> accounts.login(name, "a secure initial password"));
  }

  @Test
  void logoutRevokesTheSession() {
    var session = register();
    accounts.logout(session.token());
    assertThrows(ApiException.class, () -> identities.verify(session.token()));
  }

  @Test
  void changingPasswordRevokesEveryOldSessionAndRecoveryCode() {
    String name = username();
    var first = accounts.register(name, "original long password");
    var second = accounts.login(name.toUpperCase(Locale.ROOT), "original long password");
    assertThrows(
        ApiException.class,
        () ->
            accounts.changePassword(
                first.userId(), "incorrect password", "replacement long password"));
    var changed =
        accounts.changePassword(
            first.userId(), "original long password", "replacement long password");
    assertThrows(ApiException.class, () -> identities.verify(first.token()));
    assertThrows(ApiException.class, () -> identities.verify(second.token()));
    assertEquals(first.userId(), identities.verify(changed.token()).uid());
    assertThrows(
        ApiException.class,
        () -> accounts.recover(name, first.recoveryCodes().get(1), "another long password"));
    assertEquals(first.userId(), accounts.login(name, "replacement long password").userId());
  }

  @Test
  void sessionsAndRecoveryCodesAreStoredAsHashesAndExpiredSessionsAreRejected() {
    var session = register();
    String tokenHash =
        db.queryForObject(
            "SELECT token_hash FROM login_session WHERE user_id=?", String.class, session.userId());
    assertEquals(Activities.hash(session.token()), tokenHash);
    assertNotEquals(session.token(), tokenHash);
    var hashes =
        db.queryForList(
            "SELECT code_hash FROM recovery_code WHERE user_id=?", String.class, session.userId());
    assertTrue(
        session.recoveryCodes().stream().allMatch(code -> hashes.contains(Activities.hash(code))));
    assertTrue(session.recoveryCodes().stream().noneMatch(hashes::contains));
    db.update(
        "UPDATE login_session SET expires_at=? WHERE user_id=?",
        java.time.OffsetDateTime.now().minusSeconds(1),
        session.userId());
    assertThrows(ApiException.class, () -> identities.verify(session.token()));
  }

  @Test
  void validationDoesNotCreatePartialAccounts() {
    String name = username();
    assertThrows(ApiException.class, () -> accounts.register(name, "short"));
    assertThrows(ApiException.class, () -> accounts.register(name, "a".repeat(73)));
    assertThrows(ApiException.class, () -> accounts.register(name, "界".repeat(25)));
    assertTrue(
        db.queryForList("SELECT user_id FROM local_account WHERE username=?", name).isEmpty());
    var session = accounts.register(name, "valid initial password");
    assertThrows(
        ApiException.class,
        () -> accounts.register(name.toUpperCase(Locale.ROOT), "valid initial password"));
    assertThrows(
        ApiException.class,
        () -> accounts.recover(name, "wrong-code", "replacement long password"));
    assertEquals(session.userId(), identities.verify(session.token()).uid());
    assertEquals(
        8,
        db.queryForObject(
            "SELECT COUNT(*) FROM recovery_code WHERE user_id=?", Integer.class, session.userId()));
  }

  @Test
  void reauthenticationRequiresThePasswordAndIssuesAFreshSession() {
    var session = register();
    assertThrows(
        ApiException.class, () -> accounts.reauthenticate(session.userId(), "wrong password"));
    var fresh = accounts.reauthenticate(session.userId(), "correct horse battery staple");
    assertNotEquals(session.token(), fresh.token());
    assertTrue(fresh.recoveryCodes().isEmpty());
    assertEquals(session.userId(), identities.verify(fresh.token()).uid());
  }

  @Test
  void freshInstallationContainsTheAgreedCatalogue() {
    assertTrue(content.roleExists("java-developer"));
    assertTrue(content.roleExists("backend-developer"));
    assertTrue(content.roleExists("software-engineer"));
    for (String subject : List.of("DSA", "DBMS", "OS"))
      assertEquals(
          5,
          db.queryForObject(
              "SELECT COUNT(*) FROM topic WHERE subject=? AND id NOT LIKE 'test-%'",
              Integer.class, subject));
  }

  @Test
  void mediaNeedsConsentIsOwnerScopedAndCannotBeResurrectedAfterDeletion() {
    var session = register();
    var other = register();
    byte[] wave = new DomainRulesTest().wave();
    java.nio.ByteBuffer pcm =
        java.nio.ByteBuffer.wrap(wave).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    for (int i = 44; i < wave.length; i += 2) pcm.putShort(i, (short) 1000);
    assertThrows(ApiException.class, () -> media.upload(session.userId(), wave));
    profiles.update(session.userId(), Map.of("consentVersion", "privacy-v1"));
    var uploaded = media.upload(session.userId(), wave);
    var recording = (com.interviewedge.media.MediaService.Media) uploaded.get("media");
    assertEquals(
        recording.id(),
        ((com.interviewedge.media.MediaService.Media)
                media.upload(session.userId(), wave).get("media"))
            .id());
    assertThrows(ApiException.class, () -> media.owned(other.userId(), recording.id()));
    media.transcribe(
        session.userId(),
        recording.id(),
        path -> {
          media.delete(session.userId(), recording.id());
          return "A delayed transcript.";
        });
    assertThrows(ApiException.class, () -> media.owned(session.userId(), recording.id()));
    assertEquals(
        "DELETED",
        db.queryForObject("SELECT state FROM media WHERE id=?", String.class, recording.id()));
  }

  @Test
  void exportIncludesAllActivitiesButNoOtherUserOrAccountSecrets() throws Exception {
    var session = register();
    var other = register();
    profiles.update(session.userId(), Map.of("displayName", "Export owner"));
    profiles.update(other.userId(), Map.of("displayName", "PRIVATE_OTHER_USER_MARKER"));
    for (int i = 0; i < 205; i++)
      activities.create(session.userId(), "TEST", "export-fixture-" + i, Map.of("fixture", i));
    var output = new java.io.ByteArrayOutputStream();
    accountControls.export(session.userId(), output);
    Map<String, String> entries = new HashMap<>();
    try (var zip =
        new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(output.toByteArray()))) {
      java.util.zip.ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null)
        entries.put(
            entry.getName(),
            new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
    assertEquals(
        205,
        Json.read("{\"items\":" + entries.get("activities.json") + "}").get("items")
                instanceof List<?> list
            ? list.size()
            : -1);
    assertTrue(entries.containsKey("test-responses.json"));
    assertTrue(entries.containsKey("english-transcripts.json"));
    String all = entries.values().toString();
    assertTrue(all.contains("Export owner"));
    assertFalse(all.contains("PRIVATE_OTHER_USER_MARKER"));
    assertFalse(all.contains(session.token()));
    assertFalse(all.contains(session.recoveryCodes().get(0)));
    assertFalse(all.contains("password_hash"));
  }

  @Test
  void deletionBlocksAccessImmediatelyAndCleanupIsIdempotent() {
    String name = username();
    var session = accounts.register(name, "long password for deletion");
    var activity =
        activities.create(session.userId(), "TEST", UUID.randomUUID().toString(), Map.of());
    accountControls.delete(session.userId());
    assertThrows(ApiException.class, () -> profiles.ensure(session.userId()));
    assertThrows(ApiException.class, () -> accounts.login(name, "long password for deletion"));
    accountControls.handle(session.userId(), Map.of());
    accountControls.handle(session.userId(), Map.of());
    assertThrows(ApiException.class, () -> identities.verify(session.token()));
    assertThrows(ApiException.class, () -> activities.get(activity.id()));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM recovery_code WHERE user_id=?", Integer.class, session.userId()));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM job WHERE user_id=?", Integer.class, session.userId()));
  }

  @Test
  void retriesRemainOneContributionEvenWhenEventsArriveOutOfOrder() {
    var session = register();
    String group = UUID.randomUUID().toString();
    var first =
        activities.create(
            session.userId(), "ENGLISH", UUID.randomUUID().toString(), Map.of("retryGroup", group));
    var second =
        activities.create(
            session.userId(),
            "ENGLISH",
            UUID.randomUUID().toString(),
            Map.of("retryGroup", group, "previousId", first.id()));
    activities.complete(
        first.id(), Map.of("testFixture", true), 40.0, true, Map.of("english-clarity", 40.0));
    activities.complete(
        second.id(), Map.of("testFixture", true), 80.0, true, Map.of("english-clarity", 80.0));
    var pending =
        events.pending().stream()
            .filter(e -> session.userId().equals(e.data().get("userId")))
            .toList();
    assertEquals(2, pending.size());
    pending.stream()
        .filter(e -> second.id().equals(e.data().get("activityId")))
        .forEach(
            e -> {
              var data = new LinkedHashMap<>(e.data());
              data.put("completedAt", "2026-01-02T00:00:00Z");
              progress.project(new AssessmentEvents.Event(e.id(), data));
            });
    pending.stream()
        .filter(e -> first.id().equals(e.data().get("activityId")))
        .forEach(
            e -> {
              var data = new LinkedHashMap<>(e.data());
              data.put("completedAt", "2026-01-01T00:00:00Z");
              progress.project(new AssessmentEvents.Event(e.id(), data));
            });
    var summary = progress.summary(session.userId());
    assertEquals(1, ((Map<?, ?>) summary.get("evidenceCounts")).get("ENGLISH"));
    assertEquals(80.0, ((Map<?, ?>) summary.get("moduleScores")).get("ENGLISH"));
    assertEquals(1, progress.competencies(session.userId()).get(0).get("evidenceCount"));
  }

  @Test
  void activityJobsRemainRecoverableAndPrivateAfterNavigation() {
    var session = register();
    var other = register();
    var activity =
        activities.create(session.userId(), "INTERVIEW", UUID.randomUUID().toString(), Map.of());
    String job =
        jobs.enqueue(
            session.userId(),
            "INTERVIEW_REPORT",
            "fixture:" + activity.id(),
            Map.of("activityId", activity.id()));
    assertEquals(1, jobs.forActivity(session.userId(), activity.id()).size());
    assertTrue(jobs.forActivity(other.userId(), activity.id()).isEmpty());
    assertThrows(ApiException.class, () -> jobs.retry(other.userId(), job));
    assertThrows(ApiException.class, () -> jobs.retry(session.userId(), job));
    db.update("UPDATE job SET state='FAILED',attempts=3 WHERE id=?", job);
    jobs.retry(session.userId(), job);
    assertEquals("QUEUED", jobs.owned(session.userId(), job).get("state"));
  }

  @Test
  void interviewFailurePreservesTheAnswerAndRetryDoesNotDuplicateIt() {
    var session = register();
    profiles.update(session.userId(), Map.of("consentVersion", "privacy-v1"));
    db.update(
        "INSERT INTO interview_seed(id,role_id,kind,topic,prompt,reference_answer,state,reviewer)"
            + " VALUES(?,'java-developer','TECHNICAL','java-language','Explain Java"
            + " primitives.','Primitive values are not objects.','PUBLISHED','fixture-reviewer')",
        UUID.randomUUID().toString());
    var setup =
        new com.interviewedge.interviews.InterviewService.Setup(
            "java-developer", List.of("java-language"), "TECHNICAL", "EASY", "TEXT", 10);
    var view = interviews.create(session.userId(), UUID.randomUUID().toString(), setup);
    String id = ((Activities.Activity) view.get("activity")).id();
    var input =
        new com.interviewedge.interviews.InterviewService.Answer(
            0, "Primitive values are not objects.", null, UUID.randomUUID().toString());
    Object job = interviews.answer(session.userId(), id, input).get("jobId");
    assertEquals(job, interviews.answer(session.userId(), id, input).get("jobId"));
    assertThrows(
        IllegalStateException.class,
        () ->
            interviews.process(
                session.userId(),
                id,
                0,
                r -> {
                  throw new IllegalStateException("Provider unavailable");
                },
                (kind, topic, q, a, ref, depth) ->
                    new com.interviewedge.interviews.InterviewGenerator.FollowUp(
                        "Can you give an example?")));
    assertEquals("PROCESSING", activities.get(id).state());
    assertNull(activities.get(id).score());
    assertEquals(
        input.text(),
        db.queryForObject(
            "SELECT answer FROM interview_turn WHERE activity_id=? AND sequence=0",
            String.class,
            id));
    AnswerEvaluator evaluator =
        r ->
            new AnswerEvaluator.Evaluation(
                Map.of("correctness", 3.0, "reasoning", 3.0, "clarity", 3.0, "relevance", 3.0),
                List.of(r.answer()),
                List.of("Correct statement"),
                List.of("Add an example"),
                r.answer(),
                true,
                "test-double",
                "rubric-v1");
    interviews.process(
        session.userId(),
        id,
        0,
        evaluator,
        (kind, topic, q, a, ref, depth) ->
            new com.interviewedge.interviews.InterviewGenerator.FollowUp(
                "Can you give an example?"));
    interviews.finish(session.userId(), id);
    interviews.report(session.userId(), id);
    interviews.report(session.userId(), id);
    assertEquals(75.0, activities.get(id).score());
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM assessment_outbox WHERE activity_id=?", Integer.class, id));
  }

  @Test
  void passwordsAreNotStoredInPlaintext() {
    String name = username();
    var session = accounts.register(name, "a long secret password");
    String hash =
        db.queryForObject(
            "SELECT password_hash FROM local_account WHERE user_id=?",
            String.class,
            session.userId());
    assertTrue(hash.startsWith("$2"));
    assertFalse(hash.contains("secret"));
  }

  @Test
  void testScoringIsDeterministicPrivateAndIdempotent() {
    var session = register();
    var other = register();
    String topic = "test-" + UUID.randomUUID().toString().substring(0, 8);
    db.update("INSERT INTO topic(id,subject,name) VALUES(?,'DSA','Test topic')", topic);
    for (int i = 0; i < 5; i++) {
      var draft =
          content.draft(
              "author",
              new ContentCatalog.Question(
                  null,
                  null,
                  0,
                  topic,
                  "MCQ",
                  "EASY",
                  "What is 2 + " + i + "?",
                  List.of("correct", "incorrect"),
                  "correct",
                  "Arithmetic fixture",
                  List.of(),
                  "Original test fixture",
                  "CC0",
                  "author",
                  null,
                  "DRAFT"));
      assertThrows(ApiException.class, () -> content.publish("author", draft.id()));
      content.publish("reviewer", draft.id());
    }
    String key = UUID.randomUUID().toString();
    var setup = new TestService.Setup("DSA", topic, "EASY", 5);
    var view = tests.create(session.userId(), key, setup);
    var activity = (Activities.Activity) view.get("activity");
    assertEquals(
        activity.id(),
        ((Activities.Activity) tests.create(session.userId(), key, setup).get("activity")).id());
    assertThrows(
        ApiException.class,
        () -> tests.create(session.userId(), key, new TestService.Setup("OS", "", "EASY", 5)));
    assertThrows(ApiException.class, () -> tests.view(other.userId(), activity.id()));
    var items = (List<Map<String, Object>>) view.get("items");
    assertEquals(5, items.size());
    for (var item : items) {
      assertFalse(item.containsKey("answer"));
      assertFalse(item.containsKey("explanation"));
      var response = new TestService.Response("correct", false, 0);
      assertEquals(
          tests.save(session.userId(), activity.id(), (String) item.get("itemId"), response),
          tests.save(session.userId(), activity.id(), (String) item.get("itemId"), response));
      assertThrows(
          ApiException.class,
          () ->
              tests.save(
                  session.userId(),
                  activity.id(),
                  (String) item.get("itemId"),
                  new TestService.Response("incorrect", false, 0)));
    }
    tests.submit(session.userId(), activity.id());
    tests.grade(
        session.userId(),
        activity.id(),
        r -> {
          throw new AssertionError("MCQs must not call AI");
        });
    assertEquals(100.0, activities.get(activity.id()).score());
    for (var event : events.pending()) {
      progress.project(event);
      progress.project(event);
    }
    assertEquals(
        5,
        db.queryForObject(
            "SELECT COUNT(*) FROM test_item WHERE activity_id=?", Integer.class, activity.id()));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM progress_activity WHERE activity_id=?",
            Integer.class,
            activity.id()));
    assertNull(progress.summary(session.userId()).get("overallScore"));
    assertThrows(
        ApiException.class,
        () ->
            tests.save(
                session.userId(),
                activity.id(),
                (String) items.get(0).get("itemId"),
                new TestService.Response("incorrect", false, 1)));
  }

  @Test
  void concurrentContentRevisionsAndPublicationsRemainConsistent() throws Exception {
    String topic = "test-" + UUID.randomUUID().toString().substring(0, 8);
    db.update("INSERT INTO topic(id,subject,name) VALUES(?,'DSA','Concurrency fixture')", topic);
    var first = content.draft("author", contentFixture(topic, null));
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var start = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<ContentCatalog.Question> revise =
          () -> {
            start.await();
            return content.draft("author", contentFixture(topic, first.familyId()));
          };
      var left = pool.submit(revise);
      var right = pool.submit(revise);
      start.countDown();
      var second = left.get(10, java.util.concurrent.TimeUnit.SECONDS);
      var third = right.get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(Set.of(2, 3), Set.of(second.version(), third.version()));
      var publishStart = new java.util.concurrent.CountDownLatch(1);
      var publishLeft =
          pool.submit(
              () -> {
                publishStart.await();
                return content.publish("reviewer", second.id());
              });
      var publishRight =
          pool.submit(
              () -> {
                publishStart.await();
                return content.publish("reviewer", third.id());
              });
      publishStart.countDown();
      publishLeft.get(10, java.util.concurrent.TimeUnit.SECONDS);
      publishRight.get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(
          1,
          db.queryForObject(
              "SELECT COUNT(*) FROM question WHERE family_id=? AND state='PUBLISHED'",
              Integer.class,
              first.familyId()));
      assertEquals(first.prompt(), content.question(first.id()).prompt());
    } finally {
      pool.shutdownNow();
    }
    assertThrows(
        ApiException.class,
        () -> content.draft("author", contentFixture(topic, UUID.randomUUID().toString())));
  }

  private ContentCatalog.Question contentFixture(String topic, String family) {
    return new ContentCatalog.Question(
        null,
        family,
        0,
        topic,
        "MCQ",
        "EASY",
        "Concurrency test fixture?",
        List.of("correct", "incorrect"),
        "correct",
        "Fixture explanation",
        List.of(),
        "Test fixture",
        "CC0",
        "author",
        null,
        "DRAFT");
  }

  @Test
  void httpEndpointsRequireAuthenticationAndAdminRole() throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    String base = "http://127.0.0.1:" + port;
    assertEquals(
        200,
        client
            .send(
                HttpRequest.newBuilder(URI.create(base + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertEquals(
        401,
        client
            .send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/me")).GET().build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    var session = register();
    assertEquals(
        403,
        client
            .send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/admin/questions"))
                    .header("Authorization", "Bearer " + session.token())
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
  }

  @Test
  void httpRegistrationLoginAndLogoutMatchMobileContract() throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    String base = "http://127.0.0.1:" + port + "/api/v1";
    String name = username();
    String payload = Json.write(Map.of("username", name, "password", "a secure mobile password"));
    var registered =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, registered.statusCode());
    var body = Json.read(registered.body());
    assertEquals(8, ((List<?>) body.get("recoveryCodes")).size());
    String token = (String) body.get("token");
    assertEquals(43, token.length());
    assertEquals(
        200,
        client
            .send(
                HttpRequest.newBuilder(URI.create(base + "/me"))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    var logout =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/auth/logout"))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, logout.statusCode());
    assertEquals(
        401,
        client
            .send(
                HttpRequest.newBuilder(URI.create(base + "/me"))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    var login =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, login.statusCode());
    assertNotEquals(token, Json.read(login.body()).get("token"));
  }
}
