# AI summaries and research reports

## Abstract summaries

The per-paper **Summarize abstract** action sends only a Semantic Scholar paper ID. The backend loads the paper and its stored abstract, then uses the configured Gemini model through LangChain4j. The result is explicitly based on the abstract and does not claim to summarize the full paper. If the record has only a Semantic Scholar TLDR, the UI displays that supplied TLDR instead of generating a summary.

## Evidence synthesis

The shared **Create evidence report** action is available from list and graph result views. The setup dialog previews the server-stored papers in the selected scope and permits removing individual papers. Browser-provided abstract and classification text are not used as evidence. Evidence synthesis is abstract-based; missing abstracts are excluded and reported in the coverage note. Supporting and opposing labels are saved model classifications, not verified consensus.

## Citation-context reports

The citation details popup offers **Analyze citation context** only if citation records loaded into the graph include actual Semantic Scholar context passages. The backend requests the documented Graph API `contexts`, `intents`, and `isInfluential` citation fields and validates model references against the retrieved passage manifest. Coverage is sampled: up to the first five focal search-result papers and up to five citing papers per focal paper. A citation edge alone is never treated as evidence of a paper's stance or intent.

## API

- `POST /api/reports/preview` accepts graph ID, report type, scope, and optionally selected paper IDs.
- `POST /api/reports/generate` accepts graph ID, report type, scope, and the IDs the user kept from the preview.
- Model output is checked against the server-built source manifest. Unknown paper or citation-passage references cause an explicit error.
