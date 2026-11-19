#!/usr/bin/env python3
"""PaperMind AI workflow evaluator. Offline mode is deterministic and makes no requests."""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


HERE = Path(__file__).resolve().parent
DATASET_PATH = HERE / "dataset.json"
DEFAULT_OUTPUT = HERE / "results" / "offline-latest.json"
HARNESS_VERSION = "1.0.0"
PAPER_LIMIT = 20


def load_dataset(path: Path = DATASET_PATH) -> dict[str, Any]:
    dataset = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(dataset.get("examples"), list) or not dataset["examples"]:
        raise ValueError("Evaluation dataset must contain at least one example.")
    return dataset


def _reviewed_relevance(example: dict[str, Any]) -> dict[str, int]:
    grades: dict[str, int] = {}
    for candidate in example.get("candidates", []):
        judgment = candidate.get("judgment", {})
        grade = judgment.get("relevanceGrade")
        if judgment.get("status") != "REVIEWED" or not isinstance(grade, int) or isinstance(grade, bool):
            return {}
        if grade < 0:
            raise ValueError(f"Negative relevance grade for {candidate.get('paperId')}.")
        grades[candidate["paperId"]] = grade
    return grades


def relevance_metrics(ranked_ids: list[str], grades: dict[str, int], k: int = 5) -> dict[str, float | None]:
    """Compute P@k, recall@k and graded nDCG@k only when the full query is judged."""
    if not grades:
        return {"precisionAtK": None, "recallAtK": None, "ndcgAtK": None}
    top = ranked_ids[:k]
    relevant_total = sum(grade > 0 for grade in grades.values())
    precision = sum(grades.get(paper_id, 0) > 0 for paper_id in top) / k
    recall = (sum(grades.get(paper_id, 0) > 0 for paper_id in top) / relevant_total
              if relevant_total else 0.0)
    dcg = sum((2 ** grades.get(paper_id, 0) - 1) / math.log2(rank + 2)
              for rank, paper_id in enumerate(top))
    ideal = sorted(grades.values(), reverse=True)[:k]
    idcg = sum((2 ** grade - 1) / math.log2(rank + 2) for rank, grade in enumerate(ideal))
    ndcg = dcg / idcg if idcg else 0.0
    return {"precisionAtK": precision, "recallAtK": recall, "ndcgAtK": ndcg}


def macro_f1(expected: list[str], predicted: list[str], labels: list[str]) -> float | None:
    if not expected or len(expected) != len(predicted):
        return None
    scores = []
    for label in labels:
        tp = sum(actual == label and guess == label for actual, guess in zip(expected, predicted))
        fp = sum(actual != label and guess == label for actual, guess in zip(expected, predicted))
        fn = sum(actual == label and guess != label for actual, guess in zip(expected, predicted))
        denominator = 2 * tp + fp + fn
        scores.append((2 * tp / denominator) if denominator else 0.0)
    return sum(scores) / len(scores) if scores else None


def _fixture_contexts(example: dict[str, Any]) -> list[dict[str, str]]:
    focal_id = example["focalPaper"]["paperId"]
    return [{
        "contextId": f"{focal_id}:{candidate['citingPaperId']}:0",
        "citingPaperId": candidate["citingPaperId"],
    } for candidate in example.get("citationCandidates", [])]


def validate_report_ids(report: dict[str, Any], allowed_source_ids: set[str],
                        allowed_context_ids: set[str]) -> dict[str, Any]:
    referenced_sources: set[str] = set()
    for claim in report.get("claims", []):
        referenced_sources.update(str(value) for value in claim.get("sourcePaperIds", []))
    invalid_sources = sorted(referenced_sources - allowed_source_ids)

    referenced_contexts = {str(item.get("contextId", "")) for item in report.get("citationAnalyses", [])}
    referenced_contexts.discard("")
    invalid_contexts = sorted(referenced_contexts - allowed_context_ids)
    return {
        "valid": not invalid_sources and not invalid_contexts,
        "invalidSourceIds": invalid_sources,
        "invalidContextIds": invalid_contexts,
        "validatedSourceCount": len(referenced_sources),
        "validatedContextCount": len(referenced_contexts),
    }


def validate_sentence_references(text: str, allowed_ids: set[str], kind: str) -> dict[str, Any]:
    marker_pattern = rf"\[\[{kind}:([^\]]+)\]\]"
    markers = re.findall(marker_pattern, text)
    unknown = sorted(set(markers) - allowed_ids)

    protected = re.sub(
        r"(?i)\b(?:e\.g|i\.e|et\s+al|etc|vs|cf|approx|figs?|eqs?|refs?|no|dr|prof|st)\.",
        lambda match: match.group().replace(".", "\ue000"), text)
    protected = re.sub(r"\b(?:[A-Za-z]\.){2,}",
                       lambda match: match.group().replace(".", "\ue000"), protected)
    protected = re.sub(r"\b[A-Z]\.(?=\s+[A-Z][a-z])", lambda match: match.group().replace(".", "\ue000"), protected)
    protected = re.sub(r"(?<=\d)\.(?=\d)", "\ue000", protected)

    sentences: list[str] = []
    start = 0
    index = 0
    while index < len(protected):
        if protected.startswith("[[", index):
            marker_end = protected.find("]]", index + 2)
            index = len(protected) if marker_end < 0 else marker_end + 2
            continue
        char = protected[index]
        if char == "\ue000" or char not in ".!?":
            index += 1
            continue
        after = index + 1
        while after < len(protected) and protected[after] in '\"\'\u201d\u2019)]':
            after += 1
        cursor = after
        while cursor < len(protected) and protected[cursor].isspace():
            cursor += 1
        last_marker_end = cursor
        while protected.startswith("[[", cursor):
            marker_end = protected.find("]]", cursor + 2)
            if marker_end < 0:
                break
            last_marker_end = marker_end + 2
            cursor = last_marker_end
            while cursor < len(protected) and protected[cursor].isspace():
                cursor += 1
        has_boundary_space = cursor > after
        if cursor == len(protected) or has_boundary_space:
            sentence = protected[start:last_marker_end].replace("\ue000", ".").strip()
            if sentence:
                sentences.append(sentence)
            start = cursor
            index = cursor
        else:
            index += 1
    remainder = protected[start:].replace("\ue000", ".").strip()
    if remainder:
        sentences.append(remainder)

    cited = sum(bool(re.search(rf"{marker_pattern}(?:\s*{marker_pattern})*$", part.strip()))
                for part in sentences)
    malformed = "[[" in re.sub(r"\[\[(?:SOURCE|CONTEXT):[^\]]+\]\]", "", text) or "]]" in re.sub(
        r"\[\[(?:SOURCE|CONTEXT):[^\]]+\]\]", "", text)
    return {"valid": bool(sentences) and cited == len(sentences) and not unknown and not malformed,
            "sentenceCount": len(sentences), "sentencesWithTerminalReferences": cited,
            "unknownIds": unknown, "malformedMarkers": malformed}


def validate_source_coverage(source_coverage: list[dict[str, Any]], allowed_ids: set[str],
                             claimed_ids: set[str]) -> dict[str, Any]:
    seen: set[str] = set()
    valid = True
    for item in source_coverage:
        paper_id = str(item.get("paperId", ""))
        status = str(item.get("status", "")).upper()
        rationale = str(item.get("rationale", "")).strip()
        if (paper_id not in allowed_ids or paper_id in seen or not rationale
                or status not in {"USED", "OMITTED"}
                or (status == "USED" and paper_id not in claimed_ids)
                or (status == "OMITTED" and paper_id in claimed_ids)):
            valid = False
        seen.add(paper_id)
    return {"valid": valid and seen == allowed_ids,
            "selectedSourceCount": len(allowed_ids), "accountedSourceCount": len(seen),
            "omittedIds": sorted(str(item.get("paperId")) for item in source_coverage
                                 if str(item.get("status", "")).upper() == "OMITTED")}


def _offline_example(example: dict[str, Any]) -> dict[str, Any]:
    """Build fixture-only outputs to exercise evaluator contracts, not model quality."""
    mode = example["mode"]
    candidates = example.get("candidates", [])
    if mode == "CITATION":
        focal = example["focalPaper"]
        search_ids = [focal["paperId"]]
        paper_records = [focal]
        contexts = _fixture_contexts(example)
        source_ids = {focal["paperId"]}
    else:
        search_ids = [candidate["paperId"] for candidate in candidates]
        paper_records = candidates
        contexts = []
        source_ids = {candidate["paperId"] for candidate in candidates
                      if str(candidate.get("abstract", "")).strip()}

    unique_ids = set(search_ids)
    duplicate_count = len(search_ids) - len(unique_ids)
    summary_checks = []
    for paper in paper_records:
        abstract = str(paper.get("abstract", "")).strip()
        summary_checks.append({
            "paperId": paper["paperId"],
            "abstractAvailable": bool(abstract),
            "fixtureSummaryNonEmpty": bool(abstract),
            "fixtureOnly": True,
        })
    # Deterministic negative case: missing/blank evidence must not create a summary.
    missing_abstract_check = {
        "paperId": "fixture:missing-abstract-contract-case",
        "abstractAvailable": False,
        "summaryGenerated": False,
        "expectedStatus": 422,
        "fixtureOnly": True,
    }

    fixture_report = {
        "claims": [{"sourcePaperIds": sorted(source_ids)}],
        "citationAnalyses": [{"contextId": item["contextId"]} for item in contexts],
    }
    source_validation = validate_report_ids(fixture_report, source_ids,
                                            {item["contextId"] for item in contexts})

    candidate_ids = [candidate["paperId"] for candidate in candidates]
    grades = _reviewed_relevance(example)
    search_metrics = relevance_metrics(candidate_ids, grades)
    return {
        "exampleId": example["id"],
        "mode": mode,
        "offlineFixtureOnly": True,
        "selection": {
            "candidateCount": len(candidate_ids) if mode != "CITATION" else len(contexts),
            "selectedCount": len(search_ids),
            "selectedIds": search_ids,
            "duplicateCount": duplicate_count,
            "paperLimit": PAPER_LIMIT,
            "withinPaperLimit": len(search_ids) <= PAPER_LIMIT,
        },
        "searchMetrics": search_metrics,
        "summaryChecks": summary_checks + [missing_abstract_check],
        "reportSourceValidation": source_validation,
        "classificationQuality": {
            "reviewedJudgmentCount": sum(
                candidate.get("judgment", {}).get("status") == "REVIEWED"
                and candidate.get("judgment", {}).get("stance") is not None
                for candidate in candidates
            ),
            "macroF1": None,
            "reason": "No human-reviewed stance labels are present in this dataset version.",
        },
        "citationRelationQuality": {
            "reviewedJudgmentCount": sum(
                item.get("judgment", {}).get("status") == "REVIEWED"
                and item.get("judgment", {}).get("relation") is not None
                for item in example.get("citationCandidates", [])
            ),
            "macroF1": None,
            "reason": "No human-reviewed citation-relation labels are present in this dataset version.",
        },
    }


def _decode_response(payload: bytes) -> Any:
    value: Any = json.loads(payload.decode("utf-8"))
    # The search controller returns a JSON string body in some Spring configurations.
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except json.JSONDecodeError:
            pass
    return value


def _request(base_url: str, path: str, payload: dict[str, Any]) -> Any:
    data = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        base_url.rstrip("/") + path, data=data,
        headers={"Content-Type": "application/json", "Accept": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            return _decode_response(response.read())
    except urllib.error.HTTPError as error:
        # Preserve status only; provider error bodies can contain sensitive diagnostics.
        raise RuntimeError(f"HTTP {error.code} for {path}") from None
    except (urllib.error.URLError, TimeoutError) as error:
        raise RuntimeError(f"Request failed for {path}: {type(error).__name__}") from None


def _flatten_classifications(data: dict[str, Any]) -> dict[str, str]:
    labels: dict[str, str] = {}
    for group in ("supporting", "opposing"):
        for paper_ids in (data.get(group) or {}).values():
            if isinstance(paper_ids, list):
                for paper_id in paper_ids:
                    labels[str(paper_id)] = group.upper()
    return labels


def _live_example(base_url: str, example: dict[str, Any], max_summaries: int,
                  max_report_papers: int) -> dict[str, Any]:
    request_count = 0

    def request(path: str, payload: dict[str, Any]) -> Any:
        nonlocal request_count
        request_count += 1
        return _request(base_url, path, payload)

    search = request("/api/data?progressive=true", {
        "searchPrompt": example["researchQuestion"], "graphViewType": example["mode"],
    })
    if not isinstance(search, dict):
        raise RuntimeError("Search endpoint did not return an object.")
    papers = search.get("Papers", search.get("papers", []))
    if not isinstance(papers, list):
        raise RuntimeError("Search endpoint did not return a paper list.")
    paper_ids = [str(paper.get("paperId", "")) for paper in papers if paper.get("paperId")]
    graph_id = search.get("graphId")
    if graph_id is None:
        raise RuntimeError("Progressive search response did not include graphId.")

    classification: dict[str, str] = {}
    classification_error = None
    if example["mode"] == "OPINION":
        try:
            classification = _flatten_classifications(request("/api/data/opinions", {
                "graphId": graph_id, "prompt": example["researchQuestion"], "papers": papers,
            }))
        except RuntimeError as error:
            classification_error = str(error)

    summaries = []
    for paper in [paper for paper in papers if str(paper.get("abstract", "")).strip()][:max_summaries]:
        paper_id = str(paper["paperId"])
        try:
            summary = request("/api/generate-summary", {"paperId": paper_id})
            summaries.append({
                "paperId": paper_id,
                "status": "ok" if isinstance(summary, dict) and str(summary.get("summary", "")).strip() else "invalid_response",
                "responsePaperIdMatches": isinstance(summary, dict) and summary.get("paperId") == paper_id,
                "basis": summary.get("basis") if isinstance(summary, dict) else None,
            })
        except RuntimeError as error:
            summaries.append({"paperId": paper_id, "status": "error", "error": str(error)})

    report_type = "CITATION_CONTEXT" if example["mode"] == "CITATION" else "EVIDENCE_SYNTHESIS"
    report_result: dict[str, Any] = {"status": "skipped"}
    try:
        preview = request("/api/reports/preview", {
            "graphId": graph_id, "reportType": report_type, "scope": "ALL", "selectedPaperIds": None,
        })
        candidates = preview.get("candidates", []) if isinstance(preview, dict) else []
        if report_type == "CITATION_CONTEXT":
            eligible = [item for item in candidates if item.get("citationPassages")]
        else:
            eligible = [item for item in candidates if item.get("hasAbstract")]
        included_ids = [item["paperId"] for item in eligible[:max_report_papers]]
        if not included_ids:
            report_result = {"status": "skipped_no_eligible_sources"}
        else:
            report = request("/api/reports/generate", {
                "graphId": graph_id, "reportType": report_type, "scope": "ALL",
                "includedPaperIds": included_ids,
            })
            allowed_source_ids = {str(item.get("paperId")) for item in report.get("sources", [])}
            allowed_context_ids = {
                str(passage.get("contextId"))
                for item in report.get("sources", [])
                for passage in item.get("citationPassages", [])
            }
            validation = validate_report_ids(report, allowed_source_ids, allowed_context_ids)
            report_result = {
                "status": "ok" if validation["valid"] else "invalid_source_references",
                "reportType": report_type,
                "sourceValidation": validation,
                "selectedIdsReturned": sorted(allowed_source_ids),
            }
    except RuntimeError as error:
        report_result = {"status": "error", "error": str(error)}

    selected_set = set(paper_ids)
    unknown_classification_ids = sorted(set(classification) - selected_set)
    grades = _reviewed_relevance(example)
    return {
        "exampleId": example["id"],
        "mode": example["mode"],
        "live": True,
        "httpRequests": request_count,
        "graphId": graph_id,
        "selection": {
            "candidateCount": len(papers), "selectedCount": len(paper_ids), "selectedIds": paper_ids,
            "duplicateCount": len(paper_ids) - len(selected_set),
            "paperLimit": PAPER_LIMIT, "withinPaperLimit": len(paper_ids) <= PAPER_LIMIT,
        },
        "searchMetrics": relevance_metrics(paper_ids, grades),
        "classification": {
            "assignedCount": len(classification), "unknownPaperIds": unknown_classification_ids,
            "error": classification_error,
        },
        "summaryChecks": summaries,
        "report": report_result,
        "qualityMetricsNote": "The committed examples use fixture-local IDs; live Semantic Scholar results cannot be scored against them. Metrics remain null until the dataset is populated with reviewed judgments for returned IDs.",
    }


def run(mode: str = "offline", base_url: str = "http://localhost:8080",
        max_summaries: int = 1, max_report_papers: int = 3) -> dict[str, Any]:
    dataset = load_dataset()
    if mode == "offline":
        examples = [_offline_example(example) for example in dataset["examples"]]
        network_requests = 0
    elif mode == "live":
        examples = []
        failed_search_requests = 0
        for example in dataset["examples"]:
            try:
                examples.append(_live_example(base_url, example, max_summaries, max_report_papers))
            except RuntimeError as error:
                failed_search_requests += 1
                examples.append({"exampleId": example["id"], "mode": example["mode"],
                                 "live": True, "httpRequests": 1,
                                 "status": "error", "error": str(error)})
        network_requests = failed_search_requests + sum(
            example.get("httpRequests", 0) for example in examples)
    else:
        raise ValueError("mode must be 'offline' or 'live'.")

    return {
        "harnessVersion": HARNESS_VERSION,
        "mode": mode,
        "dataset": dataset["dataset"],
        "datasetVersion": dataset["version"],
        "networkRequests": network_requests,
        "qualityMetrics": {
            "precisionAt5": None, "recallAt5": None, "ndcgAt5": None,
            "stanceMacroF1": None, "citationRelationMacroF1": None,
            "reason": "The dataset has no human-reviewed relevance, stance, or citation-relation judgments.",
        },
        "summaryReportContractChecks": _offline_contract_checks(),
        "examples": examples,
        "limitations": [
            "Offline outputs are deterministic fixture simulations used to exercise the harness; they are not PaperMind model outputs or quality scores.",
            "Fixture-local IDs cannot be matched to live Semantic Scholar results.",
        ],
    }


def _offline_contract_checks() -> dict[str, Any]:
    """Deterministic request/response contract cases; these do not invoke a model."""
    valid_request = {"paperId": "fixture:summary-success"}
    valid_response = {"paperId": valid_request["paperId"], "summary": "Fixture summary.",
                      "basis": "ABSTRACT"}
    valid_json = json.dumps(valid_request)
    parsed_request = json.loads(valid_json)
    successful_shape = (parsed_request.get("paperId") == valid_response["paperId"]
                        and bool(valid_response.get("summary", "").strip())
                        and valid_response.get("basis") == "ABSTRACT")
    source_ids = {"fixture:source-a", "fixture:source-b"}
    partial_text = "Source A supports one point. [[SOURCE:fixture:source-a]] Source B has another point."
    legacy_reference_check = validate_report_ids(
        {"claims": [{"sourcePaperIds": ["fixture:source-a"]}]}, source_ids, set())
    granular_valid_text = ("Source A supports the method. [[SOURCE:fixture:source-a]] "
                           "Source B reports the outcome. [[SOURCE:fixture:source-b]]")
    source_coverage = [
        {"paperId": "fixture:source-a", "status": "USED", "rationale": "Supports the method."},
        {"paperId": "fixture:source-b", "status": "OMITTED", "rationale": "Does not address the question."},
    ]
    return {
        "fixtureOnly": True,
        "summary": {
            "validJsonRequest": {"passed": parsed_request == valid_request},
            "missingAbstract": {"expectedStatus": 422, "providerCalled": False},
            "blankAbstract": {"expectedStatus": 422, "providerCalled": False},
            "providerError": {"expectedStatus": 502},
            "malformedProviderOutput": {"expectedStatus": 502},
            "emptyProviderResponse": {"expectedStatus": 502},
            "successfulResponse": {"expectedStatus": 200, "validResponseShape": successful_shape},
        },
        "reportSourceIds": {
            "knownSourceAndContextAccepted": validate_report_ids(
                {"claims": [{"sourcePaperIds": ["fixture:source"]}],
                 "citationAnalyses": [{"contextId": "fixture:context"}]},
                {"fixture:source"}, {"fixture:context"}),
            "unknownSourceRejected": validate_report_ids(
                {"claims": [{"sourcePaperIds": ["fixture:invented"]}]},
                {"fixture:source"}, set()),
        },
        "reportQualityGates": {
            "legacyBaseline": {
                "unknownSourceMarkerCheck": "previously enforced",
                "perSentenceCitationCoverage": "not checked",
                "selectedSourceAccounting": "not checked",
                "legacyAcceptsUncitedSentence": bool(legacy_reference_check["valid"]
                    and "[[SOURCE:fixture:source-a]]" in partial_text),
            },
            "after": {
                "granularSentenceCitationCoverage": validate_sentence_references(
                    granular_valid_text, source_ids, "SOURCE"),
                "uncitedSentenceRejected": not validate_sentence_references(
                    partial_text, source_ids, "SOURCE")["valid"],
                "selectedSourceAccounting": validate_source_coverage(
                    source_coverage, source_ids, {"fixture:source-a"}),
                "unaccountedSourceRejected": not validate_source_coverage(
                    source_coverage[:1], source_ids, {"fixture:source-a"})["valid"],
            },
        },
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("offline", "live"), default="offline",
                        help="offline is deterministic and never contacts the app; live is opt-in and calls the local app/providers.")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--max-summaries", type=int, default=1)
    parser.add_argument("--max-report-papers", type=int, default=3)
    parser.add_argument("--output", type=Path, default=None)
    args = parser.parse_args(argv)
    if args.max_summaries < 0 or args.max_report_papers < 1:
        parser.error("--max-summaries must be >= 0 and --max-report-papers must be >= 1")
    result = run(args.mode, args.base_url, args.max_summaries, args.max_report_papers)
    output = args.output or (DEFAULT_OUTPUT if args.mode == "offline" else
                             HERE / "results" / f"live-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({"mode": result["mode"], "datasetVersion": result["datasetVersion"],
                      "networkRequests": result["networkRequests"], "output": str(output)}, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"Evaluation failed: {error}", file=sys.stderr)
        raise SystemExit(2)
