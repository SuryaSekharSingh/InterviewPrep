import uuid

from app import ai
from test_core import consent, register
from test_scoring_api import wait_job

pytest_plugins = ("test_scoring_api",)


def test_text_answer_followup_and_report(client, monkeypatch):
    monkeypatch.setattr(
        ai, "follow_up", lambda *args: "Why is array indexed access constant time?"
    )
    _, headers = register(client)
    consent(client, headers)
    settings = {
        "roleId": "java-developer",
        "skills": ["java-language", "oop", "collections"],
        "type": "TECHNICAL",
        "difficulty": "EASY",
        "answerMode": "TEXT",
        "minutes": 10,
    }
    rejected = client.post(
        "/api/v1/interviews",
        headers={**headers, "Idempotency-Key": str(uuid.uuid4())},
        json={**settings, "answerMode": "VOICE"},
    )
    assert rejected.status_code == 422
    created = client.post(
        "/api/v1/interviews",
        headers={**headers, "Idempotency-Key": str(uuid.uuid4())},
        json=settings,
    )
    assert created.status_code == 200, created.text
    activity_id = created.json()["activity"]["id"]
    assert created.json()["sequence"] == 0

    first_body = {
        "sequence": 0,
        "text": "An array supports indexed access.",
        "submissionKey": str(uuid.uuid4()),
    }
    rejected_media = client.post(
        f"/api/v1/interviews/{activity_id}/answers",
        headers=headers,
        json={**first_body, "mediaId": str(uuid.uuid4())},
    )
    assert rejected_media.status_code == 422
    assert client.post("/api/v1/media", headers=headers).status_code == 404

    first = client.post(
        f"/api/v1/interviews/{activity_id}/answers", headers=headers, json=first_body
    )
    assert first.status_code == 200, first.text
    assert wait_job(client, headers, first.json()["jobId"])["state"] == "COMPLETED"
    next_question = client.get(f"/api/v1/interviews/{activity_id}", headers=headers)
    assert next_question.status_code == 200, next_question.text
    assert next_question.json()["activity"]["state"] == "ACTIVE"
    assert next_question.json()["sequence"] == 1
    assert next_question.json()["turns"][1]["question"] == (
        "Why is array indexed access constant time?"
    )
    duplicate = client.post(
        f"/api/v1/interviews/{activity_id}/answers", headers=headers, json=first_body
    )
    assert duplicate.status_code == 200
    changed = client.post(
        f"/api/v1/interviews/{activity_id}/answers",
        headers=headers,
        json={**first_body, "text": "A different answer"},
    )
    assert changed.status_code == 409

    second = client.post(
        f"/api/v1/interviews/{activity_id}/answers",
        headers=headers,
        json={
            "sequence": 1,
            "text": "The address is computed from the base and index without a search.",
            "submissionKey": str(uuid.uuid4()),
        },
    )
    assert second.status_code == 200, second.text
    assert wait_job(client, headers, second.json()["jobId"])["state"] == "COMPLETED"
    finished = client.post(
        f"/api/v1/interviews/{activity_id}/finish", headers=headers, json={}
    )
    assert finished.status_code == 200, finished.text
    report = client.get(f"/api/v1/interviews/{activity_id}/report", headers=headers)
    assert report.status_code == 200, report.text
    assert report.json()["module"] == "INTERVIEW"
    assert report.json()["totalAnswers"] == 2
    assert report.json()["score"] is not None
