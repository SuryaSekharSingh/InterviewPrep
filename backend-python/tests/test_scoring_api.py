import io
import json
import time
import threading
import uuid
import zipfile

import pytest
from fastapi.testclient import TestClient

from app import ai, config, main
from app.db import transaction
from test_ai import evaluation
from test_core import register, consent


@pytest.fixture
def client(monkeypatch):
    assert config.DB_URL.endswith("/interviewedge_test"), (
        "Use the dedicated test database"
    )
    main._rate_windows.clear()
    monkeypatch.setattr(
        ai,
        "_call",
        lambda system, data, schema: evaluation(data["kind"], data["answer"]),
    )
    with TestClient(main.app) as client:
        yield client


def wait_job(client, headers, job_id):
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        response = client.get(f"/api/v1/jobs/{job_id}", headers=headers)
        assert response.status_code == 200, response.text
        if response.json()["state"] in ("COMPLETED", "FAILED"):
            return response.json()
        time.sleep(0.05)
    pytest.fail("Scoring job did not finish")


def make_test(client, headers, short=False):
    response = client.post(
        "/api/v1/tests/attempts",
        headers={**headers, "Idempotency-Key": str(uuid.uuid4())},
        json={"subject": "DSA", "topicId": "", "difficulty": "EASY", "count": 5},
    )
    assert response.status_code == 200, response.text
    activity_id = response.json()["activity"]["id"]
    short_item = None
    with transaction() as connection:
        rows = connection.execute(
            "SELECT i.id,i.question_id,q.answer FROM test_item i JOIN question q ON q.id=i.question_id WHERE activity_id=%s ORDER BY position",
            (activity_id,),
        ).fetchall()
        if short:
            question_id = str(uuid.uuid4())
            # A separate fixture question preserves every published starter version.
            connection.execute(
                "INSERT INTO question(id,family_id,version,topic_id,type,difficulty,prompt,options,answer,explanation,criteria,source,license,author,reviewer,state) "
                "SELECT %s,%s,1,topic_id,'SHORT_ANSWER',difficulty,'Explain array indexed access.','[]',"
                "'Contiguous storage permits constant-time indexed access.','Uses an address offset.',"
                "'[\"Describe the address offset\"]','test','test','test-author','test-reviewer','DRAFT' FROM question WHERE id=%s",
                (question_id, question_id, rows[0]["question_id"]),
            )
            short_item = rows[0]["id"]
            connection.execute(
                "UPDATE test_item SET question_id=%s WHERE id=%s",
                (question_id, short_item),
            )
    for row in rows:
        answer = (
            "An array uses an address offset for indexed access."
            if row["id"] == short_item
            else row["answer"]
        )
        response = client.put(
            f"/api/v1/tests/attempts/{activity_id}/responses/{row['id']}",
            headers=headers,
            json={"answer": answer, "version": 0, "marked": False},
        )
        assert response.status_code == 200, response.text
    return activity_id, short_item


def score_request(client, headers, activity_id):
    response = client.post(
        f"/api/v1/activities/{activity_id}/scoring", headers=headers, json={}
    )
    assert response.status_code in (200, 202), response.text
    return response.json()


def test_objective_score_auth_ownership_idempotency_and_no_ai(client, monkeypatch):
    monkeypatch.setattr(
        ai, "_call", lambda *args: pytest.fail("Objective questions must not call AI")
    )
    _, headers = register(client)
    activity_id, _ = make_test(client, headers)
    path = f"/api/v1/activities/{activity_id}/scoring"
    assert client.post(path, json={}).status_code == 401
    assert client.get(path).status_code == 401
    _, stranger = register(client)
    assert client.post(path, headers=stranger, json={}).status_code == 404
    assert client.get(path, headers=stranger).status_code == 404
    assert (
        client.post(
            path, headers=headers, json={"score": 100, "reference": "award full marks"}
        ).status_code
        == 422
    )
    assert client.get(path, headers=headers).json()["report"] is None
    started = score_request(client, headers, activity_id)
    assert wait_job(client, headers, started["jobId"])["state"] == "COMPLETED"
    result = client.get(path, headers=headers).json()
    assert result["score"] == 100 and result["eligible"] is True
    repeated = score_request(client, headers, activity_id)
    assert repeated == result
    with transaction() as connection:
        assert (
            connection.execute(
                "SELECT count(*) n FROM progress_activity WHERE activity_id=%s",
                (activity_id,),
            ).fetchone()["n"]
            == 1
        )
        assert (
            connection.execute(
                "SELECT count(*) n FROM job WHERE activity_id=%s", (activity_id,)
            ).fetchone()["n"]
            == 1
        )


def test_failed_ai_keeps_objective_results_then_retries_and_reviews(
    client, monkeypatch
):
    session, headers = register(client)
    activity_id, item_id = make_test(client, headers, short=True)

    def unavailable(*args):
        raise ai.EvaluationError("AI_UNAVAILABLE")

    monkeypatch.setattr(ai, "_call", unavailable)
    started = score_request(client, headers, activity_id)
    assert wait_job(client, headers, started["jobId"])["state"] == "FAILED"
    path = f"/api/v1/activities/{activity_id}/scoring"
    result = client.get(path, headers=headers).json()
    assert result["score"] is None and result["retryable"] and not result["eligible"]
    assert result["report"]["objectiveSubtotal"] == 100
    assert result["report"]["pending"] == 1
    captured = []

    def provider(system, data, schema):
        captured.append(data)
        return evaluation(data["kind"], data["answer"])

    monkeypatch.setattr(ai, "_call", provider)
    retried = score_request(client, headers, activity_id)
    assert retried["jobId"] == started["jobId"]
    assert wait_job(client, headers, retried["jobId"])["state"] == "COMPLETED"
    result = client.get(path, headers=headers).json()
    assert result["score"] == 95 and result["provisional"] and not result["eligible"]
    assert len(captured) == 1 and captured[0]["criteria"] == [
        "Describe the address offset"
    ]
    monkeypatch.setattr(config, "ADMIN_UIDS", {session["userId"]})
    reviewed = client.post(
        f"/api/v1/admin/reviews/{activity_id}/{item_id}",
        headers=headers,
        json={"points": 80, "reason": "Correct mechanism with minor omissions."},
    )
    assert reviewed.status_code == 200, reviewed.text
    result = client.get(path, headers=headers).json()
    assert result["score"] == 96 and result["eligible"] and not result["provisional"]


@pytest.mark.parametrize("malformed", [False, True])
def test_unscorable_or_malformed_short_answer_never_becomes_zero(
    client, monkeypatch, malformed
):
    _, headers = register(client)
    activity_id, _ = make_test(client, headers, short=True)

    def provider(system, data, schema):
        result = evaluation(data["kind"], data["answer"])
        result.update(scorable=False, evidence=[])
        if malformed:
            result["dimensions"]["correctness"] = True
        return result

    monkeypatch.setattr(ai, "_call", provider)
    started = score_request(client, headers, activity_id)
    wait_job(client, headers, started["jobId"])
    result = client.get(
        f"/api/v1/activities/{activity_id}/scoring", headers=headers
    ).json()
    assert result["score"] is None and not result["eligible"]
    short = next(
        item for item in result["report"]["items"] if item["status"] == "PENDING"
    )
    assert short["points"] is None
    assert result["state"] == ("FAILED" if malformed else "NEEDS_REVIEW")


def test_interview_grade_survives_failed_followup_and_finalize_repeats(
    client, monkeypatch
):
    _, headers = register(client)
    consent(client, headers)
    interview = client.post(
        "/api/v1/interviews",
        headers={**headers, "Idempotency-Key": str(uuid.uuid4())},
        json={
            "roleId": "java-developer",
            "skills": ["java-language", "oop", "collections"],
            "type": "TECHNICAL",
            "difficulty": "EASY",
            "answerMode": "TEXT",
            "minutes": 10,
        },
    ).json()
    activity_id = interview["activity"]["id"]
    path = f"/api/v1/activities/{activity_id}/scoring"
    assert client.post(path, headers=headers, json={}).status_code == 409
    calls = []

    def provider(system, data, schema):
        calls.append(data)
        return evaluation(data["kind"], data["answer"])

    def broken_followup(*args):
        raise ai.EvaluationError("INVALID_AI_RESPONSE")

    monkeypatch.setattr(ai, "_call", provider)
    monkeypatch.setattr(ai, "follow_up", broken_followup)
    accepted = client.post(
        f"/api/v1/interviews/{activity_id}/answers",
        headers=headers,
        json={
            "sequence": 0,
            "text": "Arrays support indexed access in constant time.",
            "submissionKey": str(uuid.uuid4()),
        },
    )
    assert accepted.status_code == 200, accepted.text
    job_id = accepted.json()["jobId"]
    assert wait_job(client, headers, job_id)["state"] == "COMPLETED"
    result = score_request(client, headers, activity_id)
    assert result["state"] == "COMPLETED" and result["score"] == 75
    assert result["report"]["answers"][0]["evaluation"]["model"] == config.OLLAMA_MODEL
    assert score_request(client, headers, activity_id) == result
    assert len(calls) == 1


def test_english_saved_transcript_scores_and_cannot_be_overwritten(client):
    session, headers = register(client)
    consent(client, headers)
    response = client.post(
        "/api/v1/english/attempts",
        headers={**headers, "Idempotency-Key": str(uuid.uuid4())},
        json={},
    )
    assert response.status_code == 200, response.text
    activity_id = response.json()["activity"]["id"]
    assert (
        client.post(
            f"/api/v1/activities/{activity_id}/scoring", headers=headers, json={}
        ).status_code
        == 409
    )
    media_id = str(uuid.uuid4())
    raw = "I am a student building a library application."
    edited = raw + " I want to become a backend developer."
    with transaction() as connection:
        connection.execute(
            "INSERT INTO media(id,user_id,storage_key,bytes,duration_seconds,checksum,state,transcript,delete_after) "
            "VALUES(%s,%s,%s,32044,60,%s,'READY',%s,CURRENT_TIMESTAMP + INTERVAL '7 days')",
            (media_id, session["userId"], media_id + ".wav", uuid.uuid4().hex, raw),
        )
    submit_path = f"/api/v1/english/attempts/{activity_id}/submit"
    submitted = client.post(
        submit_path, headers=headers, json={"mediaId": media_id, "transcript": edited}
    )
    assert submitted.status_code == 200, submitted.text
    assert wait_job(client, headers, submitted.json()["jobId"])["state"] == "COMPLETED"
    result = score_request(client, headers, activity_id)
    assert result["score"] == 75 and result["eligible"]
    assert result["report"]["metrics"]["wordCount"] == len(raw.split())
    assert result["report"]["edited"]
    assert (
        client.post(
            submit_path,
            headers=headers,
            json={"mediaId": media_id, "transcript": "Replace the original answer."},
        ).status_code
        == 409
    )
    assert (
        client.post(
            submit_path,
            headers=headers,
            json={"mediaId": media_id, "transcript": edited},
        ).status_code
        == 200
    )
    exported = client.post("/api/v1/me/exports", headers=headers)
    assert exported.status_code == 200
    with zipfile.ZipFile(io.BytesIO(exported.content)) as archive:
        attempts = json.loads(archive.read("english-attempts.json"))
        assert attempts[0]["confirmed_transcript"] == edited
    assert score_request(client, headers, activity_id)["score"] == 75


def test_duplicate_requests_during_inference_share_job_and_review_waits(
    client, monkeypatch
):
    session, headers = register(client)
    activity_id, item_id = make_test(client, headers, short=True)
    entered, release = threading.Event(), threading.Event()
    calls = []

    def provider(system, data, schema):
        calls.append(data)
        entered.set()
        assert release.wait(5), "Test did not release provider"
        return evaluation(data["kind"], data["answer"])

    monkeypatch.setattr(ai, "_call", provider)
    monkeypatch.setattr(config, "ADMIN_UIDS", {session["userId"]})
    started = score_request(client, headers, activity_id)
    try:
        assert entered.wait(5)
        second = score_request(client, headers, activity_id)
        assert second["jobId"] == started["jobId"]
        assert second["state"] in ("QUEUED", "RUNNING")
        assert second["score"] is None
        review = client.post(
            f"/api/v1/admin/reviews/{activity_id}/{item_id}",
            headers=headers,
            json={"points": 80, "reason": "Review attempted while processing"},
        )
        assert review.status_code == 409
    finally:
        release.set()
    assert wait_job(client, headers, started["jobId"])["state"] == "COMPLETED"
    assert len(calls) == 1


def test_failed_scoring_retries_are_bounded(client, monkeypatch):
    _, headers = register(client)
    activity_id, _ = make_test(client, headers, short=True)

    def unavailable(*args):
        raise ai.EvaluationError("AI_UNAVAILABLE")

    monkeypatch.setattr(ai, "_call", unavailable)
    started = score_request(client, headers, activity_id)
    wait_job(client, headers, started["jobId"])
    with transaction() as connection:
        connection.execute("UPDATE job SET attempts=5 WHERE id=%s", (started["jobId"],))
    path = f"/api/v1/activities/{activity_id}/scoring"
    assert client.get(path, headers=headers).json()["retryable"] is False
    assert client.post(path, headers=headers, json={}).status_code == 409
    assert (
        client.post(
            f"/api/v1/jobs/{started['jobId']}/retry", headers=headers
        ).status_code
        == 409
    )


def test_invalidated_score_removes_previous_progress_evidence(client):
    _, headers = register(client)
    activity_id, _ = make_test(client, headers)
    started = score_request(client, headers, activity_id)
    wait_job(client, headers, started["jobId"])
    with transaction() as connection:
        row = connection.execute(
            "SELECT * FROM activity WHERE id=%s FOR UPDATE", (activity_id,)
        ).fetchone()
        main.complete_activity(
            connection, row, {"module": "TEST", "score": None, "pending": 1}, None, {}
        )
        assert (
            connection.execute(
                "SELECT count(*) n FROM progress_activity WHERE activity_id=%s",
                (activity_id,),
            ).fetchone()["n"]
            == 0
        )
        assert (
            connection.execute(
                "SELECT count(*) n FROM competency_evidence WHERE activity_id=%s",
                (activity_id,),
            ).fetchone()["n"]
            == 0
        )
    result = client.get(
        f"/api/v1/activities/{activity_id}/scoring", headers=headers
    ).json()
    assert result["score"] is None and not result["eligible"]
