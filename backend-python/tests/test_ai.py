import copy
import io
import json
import math

import pytest

from app import ai


def evaluation(kind="TECHNICAL", answer="An array allows indexed access.", rating=3):
    return {
        "dimensions": {key: rating for key in ai.WEIGHTS[kind]},
        "evidence": [answer],
        "strengths": ["Clear explanation."],
        "improvements": ["Add an example."],
        "improvedAnswer": answer,
        "scorable": True,
    }


@pytest.mark.parametrize("kind", list(ai.WEIGHTS))
@pytest.mark.parametrize("rating,expected", [(0, 0), (2, 50), (4, 100)])
def test_score_scale(kind, rating, expected):
    assert ai.score(kind, {key: rating for key in ai.WEIGHTS[kind]}) == expected


def test_weighted_total_and_english_weights():
    assert (
        ai.score(
            "TECHNICAL",
            {"correctness": 4, "reasoning": 2, "clarity": 3, "relevance": 4},
        )
        == 80
    )
    assert ai.score("ENGLISH", {"grammar": 2, "clarity": 4, "relevance": 4}) == 85


@pytest.mark.parametrize(
    "invalid", [True, "4", None, -1, 4.1, math.nan, math.inf, [], {}]
)
def test_rejects_invalid_dimension_values(invalid):
    dimensions = evaluation()["dimensions"]
    dimensions["correctness"] = invalid
    with pytest.raises(ai.EvaluationError):
        ai.score("TECHNICAL", dimensions)


@pytest.mark.parametrize(
    "change",
    [
        {"scorable": "true"},
        {"evidence": "An array allows indexed access."},
        {"evidence": ["A fact absent from this answer."]},
        {"evidence": []},
        {"strengths": [42]},
        {"improvements": ["x"] * 5},
        {"improvedAnswer": {}},
        {"improvedAnswer": "x" * 6001},
        {"totalScore": 100},
        {"dimensions": {}},
    ],
)
def test_rejects_malformed_model_output(monkeypatch, change):
    result = evaluation()
    result.update(change)
    monkeypatch.setattr(ai, "_call", lambda *args: copy.deepcopy(result))
    with pytest.raises(ai.EvaluationError):
        ai.evaluate(
            "TECHNICAL",
            "Explain indexed access",
            "Array indexing is constant time",
            "An array allows indexed access.",
        )


def test_unscorable_output_has_no_numeric_score(monkeypatch):
    result = evaluation()
    result.update(scorable=False, evidence=[])
    monkeypatch.setattr(ai, "_call", lambda *args: result)
    assert (
        ai.evaluate("TECHNICAL", "Question", "Reference", "Unrelated response")["score"]
        is None
    )


@pytest.mark.parametrize(
    "kind,expected_relevance",
    [("TECHNICAL", 4.0), ("HR", pytest.approx(4 / 3))],
)
def test_scorable_interview_answer_gets_ten_point_relevance_floor(
    monkeypatch, kind, expected_relevance
):
    result = evaluation(kind, answer="A relevant answer.", rating=0)
    monkeypatch.setattr(ai, "_call", lambda *args: copy.deepcopy(result))

    scored = ai.evaluate(kind, "Question", "Reference", "A relevant answer.")

    assert scored["score"] == 10
    assert scored["dimensions"]["relevance"] == pytest.approx(expected_relevance)
    assert scored["scoringVersion"] == "weighted-v2"
    for dimension in scored["dimensions"]:
        if dimension != "relevance":
            assert scored["dimensions"][dimension] == 0


def test_unscorable_answer_does_not_receive_interview_floor(monkeypatch):
    result = evaluation("TECHNICAL", answer="Off-topic response.", rating=0)
    result.update(scorable=False, evidence=[])
    monkeypatch.setattr(ai, "_call", lambda *args: copy.deepcopy(result))

    scored = ai.evaluate(
        "TECHNICAL", "Question", "Reference", "Off-topic response."
    )

    assert scored["score"] is None
    assert scored["dimensions"]["relevance"] == 0


def test_scoring_passes_reviewed_criteria_and_preserves_evidence(monkeypatch):
    captured = {}

    def provider(system, data, schema):
        captured.update(data)
        return evaluation("SHORT_ANSWER", data["answer"])

    monkeypatch.setattr(ai, "_call", provider)
    result = ai.evaluate(
        "SHORT_ANSWER",
        "Question",
        "Verified reference",
        "Student answer",
        ["Describe isolation"],
    )
    assert captured["criteria"] == ["Describe isolation"]
    assert result["score"] == 75
    assert result["rubricVersion"] == ai.RUBRIC_VERSION
    assert result["scoringVersion"] == ai.SCORING_VERSION
    assert result["evidence"] == ["Student answer"]


@pytest.mark.parametrize(
    "envelope",
    [
        {"done": False, "message": {"content": "{}"}},
        {"done": True, "done_reason": "length", "message": {"content": "{}"}},
        {"done": True, "message": {"content": "not json"}},
        {"done": True, "message": {"content": "[]"}},
        {"done": True},
    ],
)
def test_rejects_incomplete_or_malformed_provider_envelope(monkeypatch, envelope):
    monkeypatch.setattr(
        ai.urllib.request,
        "urlopen",
        lambda *args, **kwargs: io.BytesIO(json.dumps(envelope).encode()),
    )
    with pytest.raises(ai.EvaluationError):
        ai._call("system", {}, {})


def test_evidence_schema_only_allows_submitted_spans(monkeypatch):
    answer = "I built a library app. I implemented book search."

    def provider(system, data, schema):
        allowed = schema["properties"]["evidence"]["items"]["enum"]
        assert allowed == ["I built a library app.", "I implemented book search."]
        result = evaluation("HR", answer)
        result["evidence"] = allowed
        return result

    monkeypatch.setattr(ai, "_call", provider)
    assert (
        ai.evaluate("HR", "Describe a project", "Use supplied facts", answer)["score"]
        == 75
    )
