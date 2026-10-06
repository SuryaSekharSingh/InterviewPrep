"""Public scoring contracts, independent of the AI provider and Android UI."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class ScoringRequest(BaseModel):
    # Answers and rubrics belong to persisted activities, never this request.
    model_config = ConfigDict(extra="forbid")


class ScoringStatus(BaseModel):
    activityId: str
    module: Literal["TEST", "INTERVIEW", "ENGLISH"]
    state: Literal[
        "NOT_REQUESTED", "QUEUED", "RUNNING", "FAILED", "COMPLETED", "NEEDS_REVIEW"
    ]
    jobId: str | None = None
    score: float | None = Field(default=None, ge=0, le=100)
    eligible: bool
    provisional: bool = False
    retryable: bool = False
    errorCode: str | None = None
    report: dict | None = None
