package com.interviewedge.content;

import com.interviewedge.common.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContentCatalog {
  public record Question(
      String id,
      String familyId,
      int version,
      String topicId,
      String type,
      String difficulty,
      String prompt,
      List<String> options,
      String answer,
      String explanation,
      List<String> criteria,
      String source,
      String license,
      String author,
      String reviewer,
      String state) {
    public Map<String, Object> publicView() {
      return Map.of(
          "id",
          id,
          "topicId",
          topicId,
          "type",
          type,
          "difficulty",
          difficulty,
          "prompt",
          prompt,
          "options",
          options);
    }
  }

  public record Seed(
      String id,
      String roleId,
      String kind,
      String topic,
      String prompt,
      String referenceAnswer,
      String state) {}

  private final JdbcTemplate db;

  public ContentCatalog(JdbcTemplate db) {
    this.db = db;
  }

  private Question map(java.sql.ResultSet r, int row) throws java.sql.SQLException {
    return new Question(
        r.getString("id"),
        r.getString("family_id"),
        r.getInt("version"),
        r.getString("topic_id"),
        r.getString("type"),
        r.getString("difficulty"),
        r.getString("prompt"),
        Arrays.asList(Json.read(r.getString("options"), String[].class)),
        r.getString("answer"),
        r.getString("explanation"),
        Arrays.asList(Json.read(r.getString("criteria"), String[].class)),
        r.getString("source"),
        r.getString("license"),
        r.getString("author"),
        r.getString("reviewer"),
        r.getString("state"));
  }

  public Question question(String id) {
    return db.query("SELECT * FROM question WHERE id=?", this::map, id).stream()
        .findFirst()
        .orElseThrow(ApiException::missing);
  }

  public Map<String, Object> catalog() {
    var roles =
        db.query(
            "SELECT * FROM role_catalog ORDER BY id",
            (r, n) ->
                Map.of(
                    "id",
                    r.getString("id"),
                    "name",
                    r.getString("name"),
                    "skills",
                    Arrays.asList(Json.read(r.getString("skills"), String[].class))));
    var topics =
        db.query(
            "SELECT * FROM topic ORDER BY subject,id",
            (r, n) ->
                Map.of(
                    "id",
                    r.getString("id"),
                    "subject",
                    r.getString("subject"),
                    "name",
                    r.getString("name")));
    return Map.of(
        "roles",
        roles,
        "topics",
        topics,
        "subjects",
        List.of("DSA", "DBMS", "OS"),
        "difficulties",
        List.of("EASY", "MEDIUM", "HARD"),
        "englishPrompt",
        Map.of(
            "version",
            "intro-v1",
            "text",
            "Introduce yourself, describe one project and your contribution, and explain your"
                + " career goal."));
  }

  public boolean roleExists(String id) {
    return !db.queryForList("SELECT id FROM role_catalog WHERE id=?", id).isEmpty();
  }

  public List<String> roleSkills(String id) {
    String raw =
        db
            .query("SELECT skills FROM role_catalog WHERE id=?", (r, n) -> r.getString(1), id)
            .stream()
            .findFirst()
            .orElseThrow(() -> ApiException.bad("Unknown role."));
    return Arrays.asList(Json.read(raw, String[].class));
  }

  public List<Question> candidates(String subject, String topic, String difficulty) {
    return db.query(
        "SELECT q.* FROM question q JOIN topic t ON q.topic_id=t.id WHERE q.state='PUBLISHED' AND"
            + " t.subject=? AND q.difficulty=? AND (?='' OR q.topic_id=?) ORDER BY q.id",
        this::map,
        subject,
        difficulty,
        topic,
        topic);
  }

  public List<Question> all() {
    return db.query("SELECT * FROM question ORDER BY topic_id,difficulty,version", this::map);
  }

  @Transactional
  public List<Question> importDrafts(String actor, List<Question> questions) {
    if (questions == null || questions.isEmpty() || questions.size() > 300)
      throw ApiException.bad("Import between 1 and 300 questions.");
    questions.forEach(this::validate);
    return questions.stream().map(q -> draft(actor, q)).toList();
  }

  @Transactional
  public Question draft(String actor, Question input) {
    validate(input);
    String id = UUID.randomUUID().toString();
    String family = input.familyId() == null || input.familyId().isBlank() ? id : input.familyId();
    if (!family.equals(id)) lockFamily(family);
    int version =
        db.queryForObject(
            "SELECT COALESCE(MAX(version),0)+1 FROM question WHERE family_id=?",
            Integer.class,
            family);
    db.update(
        "INSERT INTO"
            + " question(id,family_id,version,topic_id,type,difficulty,prompt,options,answer,explanation,criteria,source,license,author,state)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT')",
        id,
        family,
        version,
        input.topicId(),
        input.type(),
        input.difficulty(),
        input.prompt(),
        Json.write(input.options()),
        input.answer(),
        input.explanation(),
        Json.write(input.criteria()),
        input.source(),
        input.license(),
        actor);
    audit(actor, "QUESTION_DRAFT", id);
    return question(id);
  }

  private void validate(Question q) {
    if (q == null
        || q.prompt() == null
        || q.prompt().isBlank()
        || q.prompt().length() > 10000
        || q.explanation() == null
        || q.explanation().isBlank()
        || q.source() == null
        || q.source().isBlank()
        || q.license() == null
        || q.license().isBlank()
        || q.answer() == null
        || q.answer().isBlank()
        || q.options() == null
        || q.criteria() == null
        || q.type() == null
        || q.difficulty() == null
        || q.topicId() == null)
      throw ApiException.bad("Question, answer, explanation, source and licence are required.");
    if (!List.of("MCQ", "CODE_OUTPUT", "SHORT_ANSWER").contains(q.type())
        || !List.of("EASY", "MEDIUM", "HARD").contains(q.difficulty()))
      throw ApiException.bad("Unsupported question format or difficulty.");
    if (db.queryForList("SELECT id FROM topic WHERE id=?", q.topicId()).isEmpty())
      throw ApiException.bad("Unknown topic.");
    if (q.options().stream().anyMatch(v -> v == null || v.isBlank())
        || q.criteria().stream().anyMatch(v -> v == null || v.isBlank()))
      throw ApiException.bad("Options and criteria cannot contain blank values.");
    if (!q.type().equals("SHORT_ANSWER")
        && (q.options().size() < 2
            || q.options().size() > 6
            || new HashSet<>(q.options()).size() != q.options().size()
            || !q.options().contains(q.answer())))
      throw ApiException.bad("Objective questions need distinct options containing the answer.");
    if (q.type().equals("SHORT_ANSWER") && q.criteria().isEmpty())
      throw ApiException.bad("Short answers require criteria.");
  }

  @Transactional
  public Question publish(String actor, String id) {
    lockFamily(question(id).familyId());
    Question q = question(id);
    validate(q);
    if (actor.equals(q.author()))
      throw ApiException.bad("A different reviewer must approve this question.");
    if (!q.state().equals("DRAFT")) throw ApiException.conflict("Only a draft can be published.");
    db.update(
        "UPDATE question SET state='RETIRED' WHERE family_id=? AND state='PUBLISHED'",
        q.familyId());
    db.update("UPDATE question SET state='PUBLISHED',reviewer=? WHERE id=?", actor, id);
    audit(actor, "QUESTION_PUBLISH", id);
    return question(id);
  }

  @Transactional
  public void retire(String actor, String id) {
    lockFamily(question(id).familyId());
    db.update("UPDATE question SET state='RETIRED' WHERE id=?", id);
    audit(actor, "QUESTION_RETIRE", id);
  }

  private void lockFamily(String family) {
    // The immutable first version provides one stable lock for all revisions and publication.
    if (db.queryForList(
            "SELECT id FROM question WHERE family_id=? AND version=1 FOR UPDATE", family)
        .isEmpty())
      throw ApiException.bad("Unknown question family. Omit familyId for a new question.");
  }

  public List<Seed> seeds(String role, String kind) {
    return db.query(
        "SELECT * FROM interview_seed WHERE state='PUBLISHED' AND (role_id=? OR role_id='shared')"
            + " AND (?='MIXED' OR kind=?) ORDER BY id",
        (r, n) ->
            new Seed(
                r.getString("id"),
                r.getString("role_id"),
                r.getString("kind"),
                r.getString("topic"),
                r.getString("prompt"),
                r.getString("reference_answer"),
                r.getString("state")),
        role,
        kind,
        kind);
  }

  public List<Map<String, Object>> seedDrafts() {
    return db.query(
        "SELECT * FROM interview_seed ORDER BY id",
        (r, n) ->
            Map.of(
                "id",
                r.getString("id"),
                "roleId",
                r.getString("role_id"),
                "kind",
                r.getString("kind"),
                "topic",
                r.getString("topic"),
                "prompt",
                r.getString("prompt"),
                "referenceAnswer",
                r.getString("reference_answer"),
                "state",
                r.getString("state")));
  }

  public void publishSeed(String actor, String id) {
    if (db.update(
            "UPDATE interview_seed SET state='PUBLISHED',reviewer=? WHERE id=? AND state='DRAFT'",
            actor,
            id)
        != 1) throw ApiException.conflict("Seed is not a draft.");
    audit(actor, "SEED_PUBLISH", id);
  }

  private void audit(String actor, String action, String target) {
    db.update(
        "INSERT INTO audit_event(id,actor,action,target) VALUES(?,?,?,?)",
        UUID.randomUUID().toString(),
        actor,
        action,
        target);
  }
}
