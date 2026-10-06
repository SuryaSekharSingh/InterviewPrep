"""Content administration routes and reviewed-question validation."""

from __future__ import annotations

import uuid
from typing import Annotated

from fastapi import APIRouter, Depends

from . import config, security
from .db import all_rows, one, transaction
from .errors import ApiError
from .shared import dumps, loads

User = Annotated[str, Depends(security.current_user)]


def admin_user(user_id: User) -> str:
    if user_id not in config.ADMIN_UIDS:
        raise ApiError(403, "FORBIDDEN", "Administrator access is required.")
    return user_id


Admin = Annotated[str, Depends(admin_user)]


router = APIRouter()


def question_view(row: dict) -> dict:
    return {
        "id": row["id"],
        "familyId": row["family_id"],
        "version": row["version"],
        "topicId": row["topic_id"],
        "type": row["type"],
        "difficulty": row["difficulty"],
        "prompt": row["prompt"],
        "options": loads(row["options"], []),
        "answer": row["answer"],
        "explanation": row["explanation"],
        "criteria": loads(row["criteria"], []),
        "source": row["source"],
        "license": row["license"],
        "author": row["author"],
        "reviewer": row["reviewer"],
        "state": row["state"],
    }


def validate_question(connection, body: dict):
    required = (
        "topicId",
        "type",
        "difficulty",
        "prompt",
        "options",
        "answer",
        "explanation",
        "criteria",
        "source",
        "license",
    )
    if any(key not in body or body[key] is None for key in required):
        raise ApiError(
            422,
            "INVALID_INPUT",
            "Question, answer, explanation, source and licence are required.",
        )
    if body["type"] not in ("MCQ", "CODE_OUTPUT", "SHORT_ANSWER") or body[
        "difficulty"
    ] not in ("EASY", "MEDIUM", "HARD"):
        raise ApiError(
            422, "INVALID_INPUT", "Unsupported question format or difficulty."
        )
    if not one(
        connection,
        "SELECT 1 ok FROM topic WHERE id=%s",
        (body["topicId"],),
        required=False,
    ):
        raise ApiError(422, "INVALID_INPUT", "Unknown topic.")
    if (
        not str(body["prompt"]).strip()
        or len(body["prompt"]) > 10_000
        or not str(body["answer"]).strip()
        or not str(body["explanation"]).strip()
        or not str(body["source"]).strip()
        or not str(body["license"]).strip()
    ):
        raise ApiError(422, "INVALID_INPUT", "Question fields cannot be blank.")
    options, criteria = body["options"], body["criteria"]
    if (
        not isinstance(options, list)
        or not isinstance(criteria, list)
        or any(not str(value).strip() for value in options + criteria)
    ):
        raise ApiError(
            422, "INVALID_INPUT", "Options and criteria must be non-blank lists."
        )
    if body["type"] != "SHORT_ANSWER" and (
        not 2 <= len(options) <= 6
        or len(set(options)) != len(options)
        or body["answer"] not in options
    ):
        raise ApiError(
            422,
            "INVALID_INPUT",
            "Objective questions need distinct options containing the answer.",
        )
    if body["type"] == "SHORT_ANSWER" and not criteria:
        raise ApiError(422, "INVALID_INPUT", "Short answers require criteria.")


def create_question(connection, actor: str, body: dict):
    validate_question(connection, body)
    identifier = str(uuid.uuid4())
    family = body.get("familyId") or identifier
    if family != identifier:
        first = one(
            connection,
            "SELECT id FROM question WHERE family_id=%s AND version=1 FOR UPDATE",
            (family,),
            required=False,
        )
        if not first:
            raise ApiError(422, "INVALID_INPUT", "Unknown question family.")
    version = one(
        connection,
        "SELECT COALESCE(MAX(version),0)+1 next FROM question WHERE family_id=%s",
        (family,),
    )["next"]
    connection.execute(
        "INSERT INTO question(id,family_id,version,topic_id,type,difficulty,prompt,options,answer,explanation,criteria,source,license,author,state) "
        "VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,'DRAFT')",
        (
            identifier,
            family,
            version,
            body["topicId"],
            body["type"],
            body["difficulty"],
            body["prompt"],
            dumps(body["options"]),
            body["answer"],
            body["explanation"],
            dumps(body["criteria"]),
            body["source"],
            body["license"],
            actor,
        ),
    )
    return question_view(
        one(connection, "SELECT * FROM question WHERE id=%s", (identifier,))
    )


@router.get("/api/v1/admin/questions")
def admin_questions(_: Admin):
    with transaction() as connection:
        return [
            question_view(row)
            for row in all_rows(
                connection,
                "SELECT * FROM question ORDER BY topic_id,difficulty,version",
            )
        ]


@router.post("/api/v1/admin/questions")
def admin_create_question(body: dict, actor: Admin):
    with transaction() as connection:
        return create_question(connection, actor, body)


@router.post("/api/v1/admin/questions/import")
def admin_import_questions(body: list[dict], actor: Admin):
    if not 1 <= len(body) <= 300:
        raise ApiError(422, "INVALID_INPUT", "Import between 1 and 300 questions.")
    with transaction() as connection:
        for item in body:
            validate_question(connection, item)
        return [create_question(connection, actor, item) for item in body]


@router.post("/api/v1/admin/questions/{question_id}/publish")
def admin_publish_question(question_id: str, actor: Admin):
    with transaction() as connection:
        row = one(
            connection, "SELECT * FROM question WHERE id=%s FOR UPDATE", (question_id,)
        )
        validate_question(connection, question_view(row))
        if row["author"] == actor:
            raise ApiError(
                422,
                "REVIEW_REQUIRED",
                "A different reviewer must approve this question.",
            )
        if row["state"] != "DRAFT":
            raise ApiError(409, "STATE_CONFLICT", "Only a draft can be published.")
        connection.execute(
            "UPDATE question SET state='RETIRED' WHERE family_id=%s AND state='PUBLISHED'",
            (row["family_id"],),
        )
        connection.execute(
            "UPDATE question SET state='PUBLISHED',reviewer=%s WHERE id=%s",
            (actor, question_id),
        )
        return question_view(
            one(connection, "SELECT * FROM question WHERE id=%s", (question_id,))
        )


@router.post("/api/v1/admin/questions/{question_id}/retire")
def admin_retire_question(question_id: str, _: Admin):
    with transaction() as connection:
        if (
            connection.execute(
                "UPDATE question SET state='RETIRED' WHERE id=%s", (question_id,)
            ).rowcount
            != 1
        ):
            raise ApiError(404, "NOT_FOUND", "Question was not found.")
    return {"retired": True}


@router.get("/api/v1/admin/seeds")
def admin_seeds(_: Admin):
    with transaction() as connection:
        return [
            {
                "id": row["id"],
                "roleId": row["role_id"],
                "kind": row["kind"],
                "topic": row["topic"],
                "prompt": row["prompt"],
                "referenceAnswer": row["reference_answer"],
                "state": row["state"],
            }
            for row in all_rows(connection, "SELECT * FROM interview_seed ORDER BY id")
        ]


@router.post("/api/v1/admin/seeds/{seed_id}/publish")
def admin_publish_seed(seed_id: str, actor: Admin):
    with transaction() as connection:
        if (
            connection.execute(
                "UPDATE interview_seed SET state='PUBLISHED',reviewer=%s WHERE id=%s AND state='DRAFT'",
                (actor, seed_id),
            ).rowcount
            != 1
        ):
            raise ApiError(409, "STATE_CONFLICT", "Seed is not a draft.")
    return {"published": True}


@router.get("/api/v1/admin/reviews")
def admin_reviews(_: Admin):
    with transaction() as connection:
        return all_rows(
            connection,
            "SELECT i.id AS item_id,i.activity_id,i.response,i.feedback,q.prompt,q.answer,q.criteria "
            "FROM test_item i JOIN question q ON q.id=i.question_id WHERE i.grading_status IN ('PROVISIONAL','PENDING') "
            "ORDER BY i.activity_id",
        )
