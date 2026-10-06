"""Progress read models and recommendation endpoints.

Activity finalization remains in main.py because it owns assessment transactions.
"""

from __future__ import annotations

from datetime import timedelta
from typing import Annotated

from fastapi import APIRouter, Depends

from . import security
from .db import all_rows, one, transaction
from .errors import ApiError
from .shared import loads, now

User = Annotated[str, Depends(security.current_user)]
router = APIRouter()


def activity_view(row: dict) -> dict:
    return {
        "id": row["id"],
        "userId": row["user_id"],
        "module": row["module"],
        "state": row["state"],
        "context": loads(row["context"], {}),
        "report": loads(row.get("report")),
        "score": row.get("score"),
        "eligible": row["eligible"],
        "version": row["version"],
        "createdAt": row["created_at"],
        "completedAt": row.get("completed_at"),
    }


def profile_row(connection, user_id: str) -> dict:
    row = one(connection, "SELECT * FROM profile WHERE user_id=%s", (user_id,))
    return {
        "displayName": row["display_name"],
        "roleId": row["role_id"],
        "weeklyGoal": row["weekly_goal"],
        "education": row["education"],
        "skills": loads(row["skills"], []),
        "timezone": row["timezone"],
        "consentVersion": row["consent_version"],
    }


def progress_summary(connection, user_id: str) -> dict:
    rows = all_rows(
        connection,
        "SELECT * FROM progress_activity WHERE user_id=%s ORDER BY completed_at DESC,activity_id",
        (user_id,),
    )
    seen, scores, evidence = set(), {}, {}
    for row in rows:
        if row["retry_group"] in seen:
            continue
        seen.add(row["retry_group"])
        module = row["module"]
        evidence[module] = evidence.get(module, 0) + 1
        if len(scores.setdefault(module, [])) < 5:
            scores[module].append(float(row["score"]))
    module_scores = {
        key: round(sum(values) / len(values), 1) for key, values in scores.items()
    }
    overall = None
    if all(key in module_scores for key in ("INTERVIEW", "TEST", "ENGLISH")):
        overall = round(
            module_scores["INTERVIEW"] * 0.4
            + module_scores["TEST"] * 0.4
            + module_scores["ENGLISH"] * 0.2,
            1,
        )
    profile = profile_row(connection, user_id)
    monday = (now() - timedelta(days=now().weekday())).date()
    weekly = sum(1 for row in rows if row["completed_at"].date() >= monday)
    label = (
        "Build your baseline"
        if overall is None
        else (
            "Practice score"
            if evidence and all(value >= 3 for value in evidence.values())
            else "Early estimate"
        )
    )
    return {
        "overallScore": overall,
        "moduleScores": module_scores,
        "evidenceCounts": evidence,
        "scoreLabel": label,
        "weeklyCompleted": weekly,
        "weeklyGoal": profile["weeklyGoal"],
        "scoringVersion": "v1",
        "eligibleActivities": len(rows),
        "weights": {"INTERVIEW": 40, "TEST": 40, "ENGLISH": 20},
    }


def competencies(connection, user_id: str) -> list[dict]:
    rows = all_rows(
        connection,
        "SELECT e.competency,e.score,p.completed_at,p.retry_group FROM competency_evidence e "
        "JOIN progress_activity p ON p.activity_id=e.activity_id WHERE p.user_id=%s "
        "ORDER BY p.completed_at DESC",
        (user_id,),
    )
    seen, grouped, dates = set(), {}, {}
    for row in rows:
        marker = (row["competency"], row["retry_group"])
        if marker in seen:
            continue
        seen.add(marker)
        values = grouped.setdefault(row["competency"], [])
        if len(values) < 5:
            values.append(float(row["score"]))
        dates.setdefault(row["competency"], row["completed_at"])
    return [
        {
            "id": key,
            "score": round(sum(values) / len(values), 1),
            "evidenceCount": len(values),
            "lastAssessedAt": dates[key],
            "needsRefresh": dates[key] < now() - timedelta(days=30),
        }
        for key, values in sorted(grouped.items())
    ]


def recommendations(connection, user_id: str) -> list[dict]:
    summary = progress_summary(connection, user_id)
    dismissed = {
        row["rule_key"]
        for row in all_rows(
            connection,
            "SELECT rule_key FROM recommendation_dismissal WHERE user_id=%s AND until_at>CURRENT_TIMESTAMP",
            (user_id,),
        )
    }
    result = []
    for module in ("TEST", "INTERVIEW", "ENGLISH"):
        key = f"baseline:{module}"
        if module not in summary["moduleScores"] and key not in dismissed:
            result.append(
                {
                    "id": key,
                    "module": module,
                    "topicId": "",
                    "title": f"Build your {module.lower()} baseline",
                    "reason": "Complete one activity to establish an initial score.",
                    "difficulty": "EASY",
                    "ruleVersion": "v1",
                }
            )
    for item in sorted(
        competencies(connection, user_id), key=lambda value: value["score"]
    ):
        key = "topic:" + item["id"]
        if key in dismissed:
            continue
        identifier = item["id"]
        module = (
            "ENGLISH"
            if identifier.startswith("english-")
            else (
                "TEST"
                if identifier.startswith(("dsa-", "dbms-", "os-"))
                else "INTERVIEW"
            )
        )
        result.append(
            {
                "id": key,
                "module": module,
                "topicId": identifier,
                "title": "Practise " + identifier.replace("-", " "),
                "reason": "Recent evidence suggests this is your best next practice topic.",
                "difficulty": "EASY" if item["score"] < 60 else "MEDIUM",
                "ruleVersion": "v1",
            }
        )
    return result[:3]


@router.get("/api/v1/dashboard")
def dashboard(user_id: User):
    with transaction() as connection:
        return {
            **progress_summary(connection, user_id),
            "displayName": profile_row(connection, user_id)["displayName"],
            "recommendations": recommendations(connection, user_id),
        }


@router.get("/api/v1/progress")
def progress(user_id: User, days: int = 30):
    if not 1 <= days <= 365:
        raise ApiError(422, "INVALID_INPUT", "Choose a period from 1 to 365 days.")
    with transaction() as connection:
        timeline = [
            {"at": row["created_at"], "summary": loads(row["summary"], {})}
            for row in all_rows(
                connection,
                "SELECT summary,created_at FROM progress_snapshot WHERE user_id=%s AND created_at>=%s ORDER BY created_at",
                (user_id, now() - timedelta(days=days)),
            )
        ]
        return {"summary": progress_summary(connection, user_id), "timeline": timeline}


@router.get("/api/v1/competencies")
def get_competencies(user_id: User):
    with transaction() as connection:
        return competencies(connection, user_id)


@router.get("/api/v1/activities")
def activities(user_id: User, module: str = ""):
    with transaction() as connection:
        rows = all_rows(
            connection,
            "SELECT * FROM activity WHERE user_id=%s ORDER BY created_at DESC",
            (user_id,),
        )
        return [
            {
                "id": row["id"],
                "module": row["module"],
                "state": row["state"],
                "score": row["score"],
                "createdAt": row["created_at"],
                "completedAt": row["completed_at"],
            }
            for row in rows
            if not module or row["module"] == module
        ]


@router.get("/api/v1/recommendations")
def get_recommendations(user_id: User):
    with transaction() as connection:
        return recommendations(connection, user_id)


@router.patch("/api/v1/recommendations/{rule_id:path}")
def dismiss_recommendation(rule_id: str, user_id: User):
    with transaction() as connection:
        if rule_id not in {item["id"] for item in recommendations(connection, user_id)}:
            raise ApiError(422, "INVALID_INPUT", "Unknown recommendation.")
        connection.execute(
            "INSERT INTO recommendation_dismissal(user_id,rule_key,until_at) VALUES(%s,%s,%s) "
            "ON CONFLICT(user_id,rule_key) DO UPDATE SET until_at=EXCLUDED.until_at",
            (user_id, rule_id, now() + timedelta(days=3)),
        )
    return {"dismissed": True}
