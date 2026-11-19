"""Standard-library contract tests for run_eval.py."""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("run_eval.py")
SPEC = importlib.util.spec_from_file_location("run_eval", SCRIPT)
evaluator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(evaluator)


class EvaluationHarnessTests(unittest.TestCase):
    def test_relevance_metrics_are_only_available_for_reviewed_judgments(self):
        self.assertEqual(
            evaluator.relevance_metrics(["relevant", "irrelevant"], {"relevant": 2, "irrelevant": 0}, k=2),
            {"precisionAtK": 0.5, "recallAtK": 1.0, "ndcgAtK": 1.0},
        )
        self.assertTrue(all(value is None for value in
                            evaluator.relevance_metrics(["a"], {}, k=5).values()))

    def test_report_source_and_context_ids_are_checked_against_allowed_ids(self):
        report = {"claims": [{"sourcePaperIds": ["p1", "unknown"]}],
                  "citationAnalyses": [{"contextId": "c1"}, {"contextId": "c2"}]}
        result = evaluator.validate_report_ids(report, {"p1"}, {"c1"})
        self.assertFalse(result["valid"])
        self.assertEqual(result["invalidSourceIds"], ["unknown"])
        self.assertEqual(result["invalidContextIds"], ["c2"])

    def test_report_quality_gates_require_sentence_citations_and_source_accounting(self):
        text = "First claim. [[SOURCE:p1]] Second claim. [[SOURCE:p2]]"
        self.assertTrue(evaluator.validate_sentence_references(text, {"p1", "p2"}, "SOURCE")["valid"])
        self.assertFalse(evaluator.validate_sentence_references(
            "First claim. [[SOURCE:p1]] Second claim.", {"p1", "p2"}, "SOURCE")["valid"])
        coverage = [
            {"paperId": "p1", "status": "USED", "rationale": "Supports the first claim."},
            {"paperId": "p2", "status": "OMITTED", "rationale": "Outside the question."},
        ]
        self.assertTrue(evaluator.validate_source_coverage(coverage, {"p1", "p2"}, {"p1"})["valid"])
        self.assertFalse(evaluator.validate_source_coverage(coverage[:1], {"p1", "p2"}, {"p1"})["valid"])

    def test_abbreviations_and_dotted_initials_do_not_split_a_cited_sentence(self):
        text = ("Prior work by Smith et al. describes U.S. results, e.g. a 2.5-fold effect, "
                "i.e. the main finding. [[SOURCE:p1]]")
        result = evaluator.validate_sentence_references(text, {"p1"}, "SOURCE")
        self.assertTrue(result["valid"], result)
        self.assertEqual(result["sentenceCount"], 1)

        two_sentences = "First result. [[SOURCE:p1]] Second result. [[SOURCE:p2]]"
        result = evaluator.validate_sentence_references(two_sentences, {"p1", "p2"}, "SOURCE")
        self.assertTrue(result["valid"], result)
        self.assertEqual(result["sentenceCount"], 2)

    def test_offline_mode_makes_no_requests_and_missing_abstract_has_no_summary(self):
        with patch.object(evaluator.urllib.request, "urlopen",
                          side_effect=AssertionError("offline mode attempted network access")):
            result = evaluator.run("offline")
        self.assertEqual(result["networkRequests"], 0)
        self.assertEqual(len(result["examples"]), 2)
        contracts = result["summaryReportContractChecks"]
        self.assertTrue(contracts["summary"]["validJsonRequest"]["passed"])
        self.assertTrue(contracts["summary"]["successfulResponse"]["validResponseShape"])
        self.assertFalse(contracts["summary"]["missingAbstract"]["providerCalled"])
        self.assertFalse(contracts["reportSourceIds"]["unknownSourceRejected"]["valid"])
        for example in result["examples"]:
            missing = next(check for check in example["summaryChecks"]
                           if check["paperId"] == "fixture:missing-abstract-contract-case")
            self.assertFalse(missing["summaryGenerated"])
            self.assertEqual(missing["expectedStatus"], 422)
            self.assertTrue(example["reportSourceValidation"]["valid"])

    def test_offline_cli_output_is_deterministic(self):
        expected = json.dumps(evaluator.run("offline"), indent=2, ensure_ascii=False) + "\n"
        with tempfile.TemporaryDirectory() as directory:
            first = Path(directory) / "first.json"
            second = Path(directory) / "second.json"
            self.assertEqual(evaluator.main(["--mode", "offline", "--output", str(first)]), 0)
            self.assertEqual(evaluator.main(["--mode", "offline", "--output", str(second)]), 0)
            self.assertEqual(first.read_text(encoding="utf-8"), expected)
            self.assertEqual(second.read_bytes(), first.read_bytes())


if __name__ == "__main__":
    unittest.main()
