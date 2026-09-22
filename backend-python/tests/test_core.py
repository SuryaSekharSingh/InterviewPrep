from __future__ import annotations

import json
import time
import uuid

from fastapi.testclient import TestClient

from app.db import transaction
from app.main import app


def credentials():
    return {
        "username": "py_test_" + uuid.uuid4().hex[:10],
        "password": "correct horse battery staple",
    }


def register(client: TestClient):
    response = client.post("/api/v1/auth/register", json=credentials())
    assert response.status_code == 200, response.text
    session = response.json()
    assert len(session["recoveryCodes"]) == 8
    return session, {"Authorization": "Bearer " + session["token"]}


def consent(client: TestClient, headers: dict):
    response = client.patch(
        "/api/v1/me",
        headers=headers,
        json={
            "displayName": "Python Test",
            "roleId": "java-developer",
            "weeklyGoal": 5,
            "education": "",
            "skills": ["java-language", "oop", "collections"],
            "timezone": "Asia/Kolkata",
            "retentionDays": 7,
            "consentVersion": "privacy-v1",
        },
    )
    assert response.status_code == 200, response.text


def wait_for_report(client: TestClient, path: str, headers: dict, timeout=5):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        response = client.get(path, headers=headers)
        assert response.status_code == 200, response.text
        result = response.json()
        if result.get("module"):
            return result
        time.sleep(0.05)
    raise AssertionError(f"Report did not finish within {timeout} seconds: {path}")


def test_accounts_content_tests_and_interviews():
    with TestClient(app) as client:
        session, headers = register(client)
        consent(client, headers)
        catalog = client.get("/api/v1/catalog", headers=headers).json()
        assert len(catalog["roles"]) == 3
        for subject in ("DSA", "DBMS", "OS"):
            for difficulty in ("EASY", "MEDIUM", "HARD"):
                response = client.post(
                    "/api/v1/tests/attempts",
                    headers={**headers, "Idempotency-Key": str(uuid.uuid4())},
                    json={
                        "subject": subject,
                        "topicId": "",
                        "difficulty": difficulty,
                        "count": 5,
                    },
                )
                assert response.status_code == 200, response.text
                assert len(response.json()["items"]) == 5
                assert all("answer" not in item for item in response.json()["items"])
        scored = response.json()
        for item in scored["items"]:
            saved = client.put(
                f"/api/v1/tests/attempts/{scored['activity']['id']}/responses/{item['itemId']}",
                headers=headers,
                json={"answer": item["options"][0], "marked": False, "version": 0},
            )
            assert saved.status_code == 200, saved.text
        submitted = client.post(
            f"/api/v1/tests/attempts/{scored['activity']['id']}/submit",
            headers=headers,
            json={},
        )
        assert submitted.status_code == 200, submitted.text
        report = wait_for_report(
            client,
            f"/api/v1/tests/attempts/{scored['activity']['id']}/result",
            headers,
        )
        assert report["module"] == "TEST"
        assert report["score"] is not None
        progress = client.get("/api/v1/progress", headers=headers).json()
        assert progress["timeline"]
        role_skills = {role["id"]: role["skills"] for role in catalog["roles"]}
        for role in role_skills:
            for kind in ("TECHNICAL", "HR", "MIXED"):
                response = client.post(
                    "/api/v1/interviews",
                    headers={**headers, "Idempotency-Key": str(uuid.uuid4())},
                    json={
                        "roleId": role,
                        "skills": role_skills[role],
                        "type": kind,
                        "difficulty": "MEDIUM",
                        "answerMode": "TEXT",
                        "minutes": 10,
                    },
                )
                assert response.status_code == 200, response.text
                assert len(response.json()["turns"]) == 1
        other, other_headers = register(client)
        activity_id = response.json()["activity"]["id"]
        assert (
            client.get(
                f"/api/v1/interviews/{activity_id}", headers=other_headers
            ).status_code
            == 404
        )
        assert client.get("/api/v1/dashboard", headers=headers).status_code == 200


def test_recovery_rotates_session_and_codes():
    with TestClient(app) as client:
        account = credentials()
        created = client.post("/api/v1/auth/register", json=account).json()
        recovered = client.post(
            "/api/v1/auth/recover",
            json={
                "username": account["username"],
                "recoveryCode": created["recoveryCodes"][0],
                "newPassword": "a completely different password",
            },
        )
        assert recovered.status_code == 200, recovered.text
        assert len(recovered.json()["recoveryCodes"]) == 8
        assert (
            client.get(
                "/api/v1/me", headers={"Authorization": "Bearer " + created["token"]}
            ).status_code
            == 401
        )


def test_interrupted_job_resumes_after_backend_restart():
    with TestClient(app) as client:
        _, headers = register(client)
        consent(client, headers)
        created = client.post(
            "/api/v1/tests/attempts",
            headers={**headers, "Idempotency-Key": str(uuid.uuid4())},
            json={
                "subject": "DBMS",
                "topicId": "",
                "difficulty": "EASY",
                "count": 5,
            },
        ).json()
        activity_id = created["activity"]["id"]
        for item in created["items"]:
            response = client.put(
                f"/api/v1/tests/attempts/{activity_id}/responses/{item['itemId']}",
                headers=headers,
                json={"answer": item["options"][0], "marked": False, "version": 0},
            )
            assert response.status_code == 200, response.text

    job_id = str(uuid.uuid4())
    with transaction() as connection:
        user_id = connection.execute(
            "SELECT user_id FROM activity WHERE id=%s", (activity_id,)
        ).fetchone()["user_id"]
        connection.execute(
            "UPDATE activity SET state='PROCESSING' WHERE id=%s", (activity_id,)
        )
        connection.execute(
            "INSERT INTO job(id,user_id,kind,payload,dedupe_key,state,activity_id) "
            "VALUES(%s,%s,'GRADE_TEST',%s,%s,'RUNNING',%s)",
            (
                job_id,
                user_id,
                json.dumps({"activityId": activity_id}),
                "restart-test:" + job_id,
                activity_id,
            ),
        )

    with TestClient(app) as client:
        report = wait_for_report(
            client,
            f"/api/v1/tests/attempts/{activity_id}/result",
            headers,
        )
        assert report["module"] == "TEST"
        job = client.get(f"/api/v1/jobs/{job_id}", headers=headers).json()
        assert job["state"] == "COMPLETED"
