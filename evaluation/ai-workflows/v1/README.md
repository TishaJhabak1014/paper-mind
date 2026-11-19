# PaperMind AI workflow evaluation, v1

## Dataset

`dataset.json` is a small, versioned smoke-evaluation set for the current opinion and citation search modes. It contains original synthetic research questions, abstracts, and citation passages. The fixture text is committed under CC0-1.0 and contains no copied paper text. Fixture-local IDs are stable within this dataset and must not be sent to Semantic Scholar as real paper IDs.

All records are explicitly `UNREVIEWED`. The synthetic examples intentionally include clearly on-topic, mixed-evidence, off-topic, supportive-looking, challenging-looking, and non-substantive citation passages so reviewers can exercise the annotation fields. Those descriptions are not gold relevance, stance, or citation-relation judgments. Do not compute ranking or classification quality metrics until a reviewer records the judgment, evidence note, reviewer, date, and provenance for each annotation. Use relevance grades consistently (for example, 0 irrelevant, 1 partially relevant, 2 directly relevant) and stance/relation labels only when the source content supports them; use `UNCERTAIN` when the passage or abstract is insufficient.

## Provenance and limits

- Text provenance: synthetic original text written for this fixture.
- Label provenance: none yet; all judgments are null and unreviewed.
- Intended modes: keyword-driven paper search with opinion classification, and citation-context exploration.
- This is a smoke set of two questions, not a representative sample of disciplines, publication years, languages, or query difficulty.
- Synthetic examples can reveal wiring and obvious behavior failures, but cannot establish real-world search relevance, abstract-summary factuality, stance accuracy, or citation interpretation quality.

## Baseline

`baseline-results.json` records the baseline attempt against commit `c18e974`, before any changes to prompts or ranking logic. It distinguishes source-audited behavior from runtime metrics. The local execution environment did not permit a backend run (no Java 17 toolchain, denied reads of cached Gradle jars, and an unavailable Docker daemon); no live Semantic Scholar or Gemini request was made. Since the labels are unreviewed, relevance and classification metrics are intentionally null rather than invented.

The Prompt 3 pre-change offline output is preserved as `results/baseline-prompt3-before.json`; the post-change output is `results/offline-latest.json`. These artifacts show unchanged null search/classification quality scores because no labels were reviewed. The pre-change report evaluator checked source IDs but did not measure per-sentence citation coverage or selected-source accounting. The post-change harness adds deterministic contract cases for those requirements; they validate the checks, not model quality. Keep dataset versions immutable; add examples in a new version and record the model, API versions, cache state, and timestamp for every live run.

## Repeatable evaluator (v1)

The evaluator uses only Python's standard library; it does not add an application dependency. From the repository root:

```powershell
python evaluation/ai-workflows/v1/test_run_eval.py
python evaluation/ai-workflows/v1/run_eval.py --mode offline
```

Offline mode is the default, makes zero HTTP requests, and writes the deterministic result to `evaluation/ai-workflows/v1/results/offline-latest.json`. It reports fixture selection counts/IDs, computes precision@5, recall@5, and graded nDCG@5 only when all candidate relevance labels for an example are reviewed, and reports stance/relation macro-F1 only after reviewed labels and predictions are available. With the current dataset, those quality scores are null. The fixture-only contract checks cover valid JSON summary requests and response shape, absent/blank abstract behavior, provider failure, malformed/empty model output, successful response shape, report source/context ID acceptance and rejection, sentence-level citations, and accounting for every selected abstract as used or omitted with a rationale. They exercise evaluator expectations; they do not claim to call or pass the live backend.

The evidence-synthesis workflow now asks for claim-specific source IDs and a per-paper `sourceCoverage` disposition. The backend rejects unknown/duplicate/unaccounted papers, requires a rationale for each disposition, requires `USED` sources to appear in a claim, and requires a verified source marker at the end of each synthesis sentence. The report UI displays that source coverage. These stricter checks can reject otherwise readable provider output when it does not follow the JSON contract; the user can retry. Coverage-gap and limitation wording is constrained to supplied abstracts or the known bounds of the sampled citation passages. This does not verify that a model's rationale is factually correct; that requires reviewed labels and evidence.

## Prompt 3 experiment record

- Same dataset version: `1.0.0`; no annotation changes and no live provider calls.
- Search relevance, opinion stance, and citation-relation metrics: null before and after because every label remains `UNREVIEWED`. No search selection or ranking changes were made.
- Before the change, the backend validated that cited IDs existed but accepted a synthesis containing an uncited sentence and did not require every selected abstract to be used or explicitly omitted. That representative contract case is recorded in `reportQualityGates.legacyBaseline`.
- After the change, deterministic contract checks report that the sentence-level citation case passes, an uncited sentence is rejected, all selected sources can be accounted as used/omitted with rationale, and an unaccounted source is rejected. These are contract-gate results, not evidence that generated reports are more accurate or useful.
- Frontend tests and production build passed. Backend tests could not run to completion: Gradle 8.14.3 under a process-scoped JDK 17 reached `compileJava`, then failed reading the cached `spring-aspects-7.0.10.jar` with Windows `AccessDeniedException`. The user's global Java 21 setting was not changed.
- Trade-off: the source-coverage contract adds a required JSON field and can turn incomplete provider output into a 502 response. This prevents silent source omissions, but the fallback model/provider may also fail the stricter contract. No summary prompt or search/selection behavior was changed.

Live mode is opt-in and requires a running local PaperMind backend configured with working providers. It sends the dataset's synthetic questions to the app and may trigger Semantic Scholar and Gemini calls, so it can consume provider quota:

```powershell
python evaluation/ai-workflows/v1/run_eval.py --mode live --base-url http://localhost:8080
```

Live output is written under `results/live-<UTC timestamp>.json`. It records returned paper IDs, counts, endpoint statuses, summary response shape, and report source-ID validity. Since the committed dataset uses fixture-local IDs and has no human-reviewed labels, live relevance/classification quality remains unscored. Use `--max-summaries 0` to skip summary calls, or lower `--max-report-papers` to constrain report generation. Keep live mode off unless provider use is intended.
