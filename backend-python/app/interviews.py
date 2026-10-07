"""Mock interview routes: session creation, answers, AI follow-ups and reports."""

from __future__ import annotations

import uuid
from datetime import timedelta
from typing import Annotated

from fastapi import APIRouter, Depends, Header

from . import ai, security
from .core import (
    complete_activity,
    create_activity,
    owned_activity,
    require_consent,
)
from .db import all_rows, one, transaction
from .errors import ApiError
from .jobs import enqueue_job, mark_job
from .progress import activity_view
from .shared import dumps, loads, now

User = Annotated[str, Depends(security.current_user)]
router = APIRouter()


def interview_view(connection, user_id: str, activity_id: str):
    activity = owned_activity(connection, user_id, activity_id)
    if activity["module"] != "INTERVIEW":
        raise ApiError(404, "NOT_FOUND", "The interview was not found.")
    session = one(
        connection,
        "SELECT * FROM interview_session WHERE activity_id=%s",
        (activity_id,),
    )
    remaining = session["remaining_seconds"]
    if session["current_started"] and activity["state"] == "ACTIVE":
        remaining = max(
            0, remaining - int((now() - session["current_started"]).total_seconds())
        )
    turns = all_rows(
        connection,
        "SELECT sequence,prompt,topic,kind,answer FROM interview_turn "
        "WHERE activity_id=%s ORDER BY sequence",
        (activity_id,),
    )
    return {
        "activity": activity_view(activity),
        "turns": [
            {
                "sequence": row["sequence"],
                "question": row["prompt"],
                "topic": row["topic"],
                "kind": row["kind"],
                "answer": row["answer"],
            }
            for row in turns
        ],
        "sequence": session["current_sequence"],
        "remainingSeconds": remaining,
        "wallDeadline": session["wall_deadline"],
    }


@router.post("/api/v1/interviews")
def create_interview(
    body: dict,
    user_id: User,
    idempotency_key: Annotated[str, Header(alias="Idempotency-Key")],
):
    role, skills = body.get("roleId"), body.get("skills") or []
    kind, difficulty, mode, minutes = (
        body.get("type"),
        body.get("difficulty"),
        body.get("answerMode", "TEXT"),
        body.get("minutes"),
    )
    if (
        kind not in ("TECHNICAL", "HR", "MIXED")
        or difficulty not in ("EASY", "MEDIUM", "HARD")
        or mode != "TEXT"
        or minutes not in (10, 20, 30)
        or not skills
    ):
        raise ApiError(422, "INVALID_INPUT", "Check interview settings.")
    context = {
        "roleId": role,
        "skills": skills,
        "type": kind,
        "difficulty": difficulty,
        "answerMode": mode,
        "minutes": minutes,
    }
    with transaction() as connection:
        require_consent(connection, user_id)
        role_row = one(
            connection,
            "SELECT skills FROM role_catalog WHERE id=%s",
            (role,),
            required=False,
        )
        if not role_row or not set(skills).issubset(set(loads(role_row["skills"], []))):
            raise ApiError(422, "INVALID_INPUT", "Choose skills for the selected role.")
        activity = create_activity(
            connection, user_id, "INTERVIEW", idempotency_key, context
        )
        exists = one(
            connection,
            "SELECT activity_id FROM interview_session WHERE activity_id=%s",
            (activity["id"],),
            required=False,
        )
        if not exists:
            seeds = all_rows(
                connection,
                "SELECT * FROM interview_seed WHERE state='PUBLISHED' AND (role_id=%s OR role_id='shared') "
                "AND (%s='MIXED' OR kind=%s) ORDER BY id",
                (role, kind, kind),
            )
            seeds = [
                seed
                for seed in seeds
                if seed["kind"] == "HR" or seed["topic"] in skills
            ]
            if not seeds:
                raise ApiError(
                    422,
                    "CONTENT_UNAVAILABLE",
                    "No reviewed interview prompts match these settings.",
                )
            first = next(
                (
                    seed
                    for seed in seeds
                    if kind != "MIXED" or seed["kind"] == "TECHNICAL"
                ),
                seeds[0],
            )
            maximum = {10: 8, 20: 12, 30: 16}[minutes]
            connection.execute(
                "INSERT INTO interview_session(activity_id,remaining_seconds,max_questions,wall_deadline,current_started) "
                "VALUES(%s,%s,%s,%s,%s)",
                (
                    activity["id"],
                    minutes * 60,
                    maximum,
                    now() + timedelta(minutes=minutes * 3),
                    now(),
                ),
            )
            connection.execute(
                "INSERT INTO interview_turn(id,activity_id,sequence,topic,kind,prompt,reference_answer,followup_depth) "
                "VALUES(%s,%s,0,%s,%s,%s,%s,0)",
                (
                    str(uuid.uuid4()),
                    activity["id"],
                    first["topic"],
                    first["kind"],
                    first["prompt"],
                    first["reference_answer"],
                ),
            )
        return interview_view(connection, user_id, activity["id"])


@router.get("/api/v1/interviews/{activity_id}")
def get_interview(activity_id: str, user_id: User):
    with transaction() as connection:
        return interview_view(connection, user_id, activity_id)


@router.post("/api/v1/interviews/{activity_id}/answers")
def answer_interview(activity_id: str, body: dict, user_id: User):
    sequence, answer = body.get("sequence"), (body.get("text") or "").strip()
    submission = body.get("submissionKey")
    if (
        not isinstance(sequence, int)
        or not answer
        or len(answer) > 8000
        or not submission
        or len(submission) > 100
        or "mediaId" in body
    ):
        raise ApiError(
            422, "INVALID_INPUT", "An answer and submission key are required."
        )
    with transaction() as connection:
        activity = owned_activity(connection, user_id, activity_id, True)
        turn = one(
            connection,
            "SELECT * FROM interview_turn WHERE activity_id=%s AND sequence=%s FOR UPDATE",
            (activity_id, sequence),
        )
        if turn["answer"] is not None:
            if turn["answer"] != answer or turn["submission_key"] != submission:
                raise ApiError(
                    409,
                    "ANSWER_EXISTS",
                    "This question already has an accepted answer.",
                )
        else:
            session = one(
                connection,
                "SELECT * FROM interview_session WHERE activity_id=%s FOR UPDATE",
                (activity_id,),
            )
            if activity["state"] != "ACTIVE" or sequence != session["current_sequence"]:
                raise ApiError(
                    409,
                    "INTERVIEW_STATE",
                    "Wait for the next question or reload this interview.",
                )
            elapsed = int((now() - session["current_started"]).total_seconds())
            remaining = session["remaining_seconds"] - max(0, elapsed)
            if remaining <= 0 or now() >= session["wall_deadline"]:
                raise ApiError(
                    409,
                    "INTERVIEW_EXPIRED",
                    "Interview time has expired. Finish for your report.",
                )
            connection.execute(
                "UPDATE interview_turn SET answer=%s,submission_key=%s WHERE id=%s",
                (answer, submission, turn["id"]),
            )
            connection.execute(
                "UPDATE interview_session SET remaining_seconds=%s,current_started=NULL WHERE activity_id=%s",
                (remaining, activity_id),
            )
            connection.execute(
                "UPDATE activity SET state='PROCESSING' WHERE id=%s", (activity_id,)
            )
        job = enqueue_job(
            connection,
            user_id,
            "INTERVIEW_ANSWER",
            activity_id,
            {"activityId": activity_id, "sequence": sequence},
        )
        if job["state"] in ("FAILED", "QUEUED"):
            if job["state"] == "FAILED" and job["attempts"] >= 5:
                raise ApiError(
                    409,
                    "RETRY_LIMIT",
                    "The retry limit was reached. Contact the administrator.",
                )
            connection.execute(
                "UPDATE job SET state='QUEUED',error_code=NULL WHERE id=%s",
                (job["id"],),
            )
        return {"activityId": activity_id, "jobId": job["id"]}


def process_interview_answer(
    user_id: str, activity_id: str, sequence: int, job_id: str
):
    try:
        with transaction() as connection:
            connection.execute("UPDATE job SET state='RUNNING' WHERE id=%s", (job_id,))
            activity = owned_activity(connection, user_id, activity_id, True)
            turn = one(
                connection,
                "SELECT * FROM interview_turn WHERE activity_id=%s AND sequence=%s",
                (activity_id, sequence),
            )
            session = one(
                connection,
                "SELECT * FROM interview_session WHERE activity_id=%s",
                (activity_id,),
            )
        if activity["state"] == "COMPLETED" or session["current_sequence"] > sequence:
            mark_job(job_id, "COMPLETED")
            return
        evaluation = (
            loads(turn["evaluation"])
            if turn["evaluation"]
            else ai.evaluate(
                turn["kind"], turn["prompt"], turn["reference_answer"], turn["answer"]
            )
        )
        with transaction() as connection:
            owned_activity(connection, user_id, activity_id, True)
            connection.execute(
                "UPDATE interview_turn SET evaluation=%s WHERE id=%s",
                (dumps(evaluation), turn["id"]),
            )
        # Commit grading before generating a follow-up; generation failure must not
        # discard valid assessment evidence or keep a DB lock during model inference.
        generated = None
        if (
            activity["state"] != "FINISHING"
            and evaluation["scorable"]
            and turn["followup_depth"] < 2
        ):
            try:
                generated = ai.follow_up(
                    turn["kind"],
                    turn["topic"],
                    turn["prompt"],
                    turn["answer"],
                    turn["reference_answer"],
                    turn["followup_depth"] + 1,
                )
            except Exception:
                pass  # Fall back to the next published seed.
        with transaction() as connection:
            activity = owned_activity(connection, user_id, activity_id, True)
            session = one(
                connection,
                "SELECT * FROM interview_session WHERE activity_id=%s",
                (activity_id,),
            )
            if (
                activity["state"] == "COMPLETED"
                or session["current_sequence"] > sequence
            ):
                pass
            elif (
                activity["state"] == "FINISHING"
                or sequence + 1 >= session["max_questions"]
                or now() >= session["wall_deadline"]
            ):
                build_interview_report(connection, activity)
            else:
                depth = turn["followup_depth"]
                next_turn = None
                if generated:
                    next_turn = (
                        turn["topic"],
                        turn["kind"],
                        generated,
                        turn["reference_answer"],
                        depth + 1,
                    )
                else:
                    context = loads(activity["context"], {})
                    seen = {
                        row["prompt"]
                        for row in all_rows(
                            connection,
                            "SELECT prompt FROM interview_turn WHERE activity_id=%s",
                            (activity_id,),
                        )
                    }
                    seeds = all_rows(
                        connection,
                        "SELECT * FROM interview_seed WHERE state='PUBLISHED' AND (role_id=%s OR role_id='shared') "
                        "AND (%s='MIXED' OR kind=%s) ORDER BY id",
                        (context["roleId"], context["type"], context["type"]),
                    )
                    available = [
                        seed
                        for seed in seeds
                        if seed["prompt"] not in seen
                        and (seed["kind"] == "HR" or seed["topic"] in context["skills"])
                    ]
                    if available:
                        seed = next(
                            (
                                item
                                for item in available
                                if context["type"] != "MIXED"
                                or item["kind"] != turn["kind"]
                            ),
                            available[0],
                        )
                        next_turn = (
                            seed["topic"],
                            seed["kind"],
                            seed["prompt"],
                            seed["reference_answer"],
                            0,
                        )
                if next_turn is None:
                    build_interview_report(connection, activity)
                else:
                    connection.execute(
                        "INSERT INTO interview_turn(id,activity_id,sequence,topic,kind,prompt,reference_answer,followup_depth) "
                        "VALUES(%s,%s,%s,%s,%s,%s,%s,%s)",
                        (str(uuid.uuid4()), activity_id, sequence + 1, *next_turn),
                    )
                    connection.execute(
                        "UPDATE interview_session SET current_sequence=%s,current_started=%s WHERE activity_id=%s",
                        (sequence + 1, now(), activity_id),
                    )
                    connection.execute(
                        "UPDATE activity SET state='ACTIVE' WHERE id=%s", (activity_id,)
                    )
        mark_job(job_id, "COMPLETED")
    except Exception as error:
        mark_job(job_id, "FAILED", type(error).__name__)


def build_interview_report(connection, activity: dict):
    turns = all_rows(
        connection,
        "SELECT * FROM interview_turn WHERE activity_id=%s AND answer IS NOT NULL ORDER BY sequence",
        (activity["id"],),
    )
    if any(not row["evaluation"] for row in turns):
        raise RuntimeError("Answers are still being evaluated")
    details, scores, strengths, improvements, by_topic = [], [], [], [], {}
    for row in turns:
        evaluation = loads(row["evaluation"])
        details.append(
            {
                "question": row["prompt"],
                "answer": row["answer"],
                "evaluation": evaluation,
            }
        )
        strengths.extend(evaluation["strengths"])
        improvements.extend(evaluation["improvements"])
        if evaluation["scorable"]:
            value = ai.score(row["kind"], evaluation["dimensions"])
            scores.append(value)
            by_topic.setdefault(row["topic"], []).append(value)
    score = round(sum(scores) / len(scores), 2) if scores else None
    report = {
        "module": "INTERVIEW",
        "score": score,
        "strengths": list(dict.fromkeys(strengths))[:3],
        "improvements": list(dict.fromkeys(improvements))[:3],
        "answers": details,
        "rubricVersion": ai.RUBRIC_VERSION,
        "scoringVersion": ai.SCORING_VERSION,
        "scorableAnswers": len(scores),
        "totalAnswers": len(turns),
    }
    competencies = {
        topic: sum(values) / len(values) for topic, values in by_topic.items()
    }
    complete_activity(connection, activity, report, score, competencies)


@router.post("/api/v1/interviews/{activity_id}/finish")
def finish_interview(activity_id: str, user_id: User):
    with transaction() as connection:
        activity = owned_activity(connection, user_id, activity_id, True)
        if activity["module"] != "INTERVIEW":
            raise ApiError(404, "NOT_FOUND", "The interview was not found.")
        if activity["state"] == "COMPLETED":
            return {"activityId": activity_id, "state": "COMPLETED"}
        pending = one(
            connection,
            "SELECT sequence FROM interview_turn WHERE activity_id=%s AND answer IS NOT NULL "
            "AND evaluation IS NULL ORDER BY sequence LIMIT 1",
            (activity_id,),
            required=False,
        )
        if pending:
            job = enqueue_job(
                connection,
                user_id,
                "INTERVIEW_ANSWER",
                activity_id,
                {"activityId": activity_id, "sequence": pending["sequence"]},
            )
            connection.execute(
                "UPDATE activity SET state='FINISHING' WHERE id=%s", (activity_id,)
            )
            if job["state"] == "FAILED":
                if job["attempts"] >= 5:
                    raise ApiError(
                        409,
                        "RETRY_LIMIT",
                        "The retry limit was reached. Contact the administrator.",
                    )
                connection.execute(
                    "UPDATE job SET state='QUEUED',error_code=NULL WHERE id=%s",
                    (job["id"],),
                )
            return {"activityId": activity_id, "jobId": job["id"]}
        build_interview_report(connection, activity)
        return {"activityId": activity_id, "state": "COMPLETED"}


@router.get("/api/v1/interviews/{activity_id}/report")
def interview_report(activity_id: str, user_id: User):
    with transaction() as connection:
        activity = owned_activity(connection, user_id, activity_id)
        return (
            loads(activity["report"])
            if activity["report"]
            else {"state": activity["state"]}
        )
