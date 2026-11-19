# PaperMind

PaperMind is an AI-assisted research discovery app. Search Semantic Scholar, explore citation and supporting/opposing opinion graphs, summarize abstracts with Gemini, and export summaries to Google Drive.

## Stack

- Java 17, Spring Boot, Spring Data JPA, PostgreSQL
- React 18, Vite, Material UI
- Gemini API for query analysis and summaries
- Semantic Scholar Graph API for paper metadata and citations
- Google Drive API for user-authorized document export

## Product scope and roadmap

This section describes planned releases; it is not a claim that the features below are already implemented. PaperMind remains focused on research discovery and evidence exploration. DriveAtlas is a separate project and is not part of this roadmap.

### Current feature baseline

- Search Semantic Scholar using an AI-refined research query.
- Browse papers in list and citation/opinion graph views.
- Classify papers as supporting or opposing a query.
- Generate an AI summary from a returned abstract.
- Export summaries to Google Drive with user authorization.
- Persist application data in PostgreSQL and run the services with Docker Compose.

### Summary and report behaviors

PaperMind has two search/graph styles, but they do not have the same evidence. Keep three distinct actions instead of presenting every output as a generic “summary”:

1. **Paper summary:** a short, plain-language summary of one paper's abstract. It is not a full-paper summary and does not compare papers.
2. **Evidence synthesis:** a comparative report across a selected group of search-result papers, grounded in the content available for those papers.
3. **Citation-context report:** an analysis of what a citing paper says about a focal/cited paper. A citation edge alone is not evidence that the citing work supports, opposes, or extends the cited work.

The scope options below refer to the search-result papers selected as the report's focal set. They do not mean “all papers that exist” or “all citations in the literature.” Where supporting/opposing labels are available, they filter this focal set. They must not be interpreted as the stance of a citing paper unless its citation passage is actually analyzed.

| Search style | Action and scope | Applicable? | Goal and evidence rule |
|---|---|---|---|
| Citation | Paper summary, for any individual result or displayed citing paper with an abstract | Yes, when an abstract is available | Explain that one abstract in accessible language. Include the paper's authors, title, year when known, and a formatted reference/link to the actual paper page. Mark missing metadata; never invent it. |
| Citation | Evidence synthesis — All | Yes | Compare the available search-result papers against the user's research question. Include the sampled citation graph only as relationship metadata unless citation passages are available. |
| Citation | Evidence synthesis — Supporting / Opposing | Conditional | Offer these scopes only when the search has support/opposition classifications. Use them to filter the search-result papers, and disclose that classification is model-generated and may be uncertain. Do not label their citing papers as supporting/opposing based on the edge. |
| Citation | Citation-context report — All | Yes only when citation-context evidence is available; otherwise show a disabled action with an explanation | For each selected focal paper, describe how available citing works explicitly discuss it: for example, use, extension, replication, qualification, or criticism. Require citation passages or authorized full text that contains the passage; a graph edge, title, abstract pair, or citation count is insufficient to assert the relationship. |
| Citation | Citation-context report — Supporting / Opposing | Conditional on both classifications and citation-context evidence | Apply the selected stance filter to focal search-result papers, then analyze their available citation passages. The stance describes the focal paper's relation to the user's query, not the citing paper's view of it. |
| Opinion | Paper summary, for any individual result with an abstract | Yes | Same single-paper behavior as citation search; opinion grouping does not change the summary's evidence or imply endorsement. |
| Opinion | Evidence synthesis — All | Yes | Give a balanced overview of how the retrieved papers relate to the user's question, comparing the supporting and opposing groups, noting disagreements, mixed/unclear evidence, coverage gaps, and limitations. Do not reduce the literature to a vote count. |
| Opinion | Evidence synthesis — Supporting | Yes when the group is non-empty | Summarize the claims and evidence in the papers classified as supporting the user's query; explain shared themes and important differences. Label the group as model-classified, not verified consensus. |
| Opinion | Evidence synthesis — Opposing | Yes when the group is non-empty | Summarize counterarguments and evidence in the papers classified as opposing the user's query; distinguish direct contradiction from different populations, methods, outcomes, or assumptions where the sources support that distinction. |
| Opinion | Citation-context report — All / Supporting / Opposing | Not in the current opinion-search data flow | Do not offer this action just because opinion papers have IDs. It becomes available only after citation relationships and source passages are deliberately fetched and linked to those papers. Then the scope has the same focal-paper meaning as in citation search. |

For any report, show the selected paper count and source list before generation. In the result, separate source-reported findings from model-written synthesis, attach paper-level references to claims, identify whether evidence came from an abstract or a passage/full text, and show uncertainty or insufficient evidence. If only abstracts are available, describe the result as an **abstract-based synthesis** and avoid claims about detailed methods, results, or citation intent that the abstracts do not support.

### UI placement and user flow

Keep the existing per-paper action beside that paper. Rename it to **Summarize abstract** so users know exactly what the model receives. Show the generated paragraph with the available author/title/year, an academic-style reference, and a link to the paper landing page; this remains a one-paper action in both search styles and both list/graph views.

Do not add three permanent top-level buttons for All, Supporting, and Opposing. Use one persistent **Create evidence report** action in the results toolbar, above the list/graph content and near the current search/filter controls. It opens a small report setup panel:

1. Choose report type: **Evidence synthesis** or, only when source passages exist, **Citation-context report**.
2. Choose scope: **All**, **Supporting**, **Opposing**, or optionally **Selected papers**. Only show scopes that exist for the current search; disable empty groups with a clear reason.
3. Preview the exact papers and evidence types included; let the user remove papers before generation.
4. Generate the report and show its source-linked output in a side panel or dedicated report view. Offer **Export to Google Docs** only after generation and preview.

Add contextual shortcuts without creating separate implementations:

- In **citation graph** mode, place **Analyze citation context** in the selected focal paper's existing details popup beside the citing-paper list. Run it for that focal paper and eligible citing passages. Until a passage source is integrated, show a concise “Citation passages are not available for this paper” explanation rather than implying the graph edge is enough.
- In **opinion graph** mode, place **Synthesize this group** in the opinion-cluster popup, preset to that cluster's Supporting or Opposing scope. The same action can appear in the Supporting/Opposing list-section heading as a shortcut.
- In **list view**, keep the same results-toolbar report action and scope selector. Do not remove report capability just because the user switched from graph to list.

Treat **search style** (citation/opinion) as the data/evidence mode and **view style** (graph/list) as presentation only. Switching list/graph must preserve the search ID, filters, selected papers, and any report draft; it must not change the report's meaning or available evidence. The report setup should explain why a scope or report type is unavailable for that search.

### Backend and AI orchestration

- Keep report preparation deterministic in Spring services: load the search snapshot, resolve scope from server-side results/classifications, enforce that selected paper IDs belong to that search, and build a source manifest containing paper IDs, metadata, evidence type, and authorized source links.
- Use a report type such as `EVIDENCE_SYNTHESIS` or `CITATION_CONTEXT`, plus a scope such as `ALL`, `SUPPORTING`, `OPPOSING`, or `SELECTED`. The display mode (list/graph) is not part of this decision.
- Invoke a provider through a small Java interface and return a typed report DTO. Ask the model for claim text and source IDs, then validate server-side that every cited ID is in the manifest and that required fields are present. Reject or retry malformed/unsupported output; do not accept model-invented references.
- **Spring AI is the recommended first framework to evaluate** for this Spring Boot project. Use its model/client and structured-output capabilities behind the provider interface. LangChain4j is a reasonable alternative, but do not add both frameworks or a multi-agent workflow for these bounded summarization/report tasks.
- Keep prompts/templates and model profiles task-specific: single abstract summary, abstract-based evidence synthesis, and passage-grounded citation-context analysis have different evidence constraints. A model router may select a configured model by task, but it should not decide which papers are in scope.
- No agent/tool-calling loop is needed for the initial report path: the application already knows the search results and scope. Add retrieval (and potentially RAG/pgvector) only when PaperMind has a usable corpus of paper passages to search. Citation-context reporting also needs a source adapter that actually supplies citation passages; LangChain cannot create missing evidence.
- Start synchronously for small reports. Move larger reports to the planned RabbitMQ job workflow without changing report semantics or the frontend's selected scope.

### Release 1 — Reliable AI and referenced paper summaries

- Distinguish “abstract not supplied by the metadata source” from “the paper has no abstract.” Offer a paper summary only when usable abstract text is available; keep the existing abstract-based summary feature.
- Enrich each single-paper summary with available author name(s), title, publication year, and an academic-style reference linked to the actual paper landing page (and an open-access PDF link when supplied).
- Use a consistent citation format initially (for example, APA-like), but include only fields present in metadata. Never fabricate authors, venue, date, DOI, page numbers, or a full-text URL. Label incomplete references and use a stable source URL such as the paper landing page when available.
- Make the evidence boundary visible: “Summary of the available abstract,” not “summary of the paper.” Keep the generated prose separate from the bibliographic reference.
- Add contract tests for the summary and search APIs, plus deterministic tests using mocked model/provider responses.
- Create a small, versioned evaluation set for summary usefulness, query classification, source coverage, and failure cases. Keep evaluation criteria measurable and avoid brittle exact-text assertions.
- Record model, prompt version, outcome, latency, and token/cost information where available; do not log API keys or unnecessary paper/user content.
- Preserve visible failure states and retry behavior for provider errors, invalid output, and rate limits.

### Release 2 — Scoped evidence synthesis from current search data

- Add a shared report flow for both graph styles with distinct actions for **Paper summary**, **Evidence synthesis**, and (only when eligible) **Citation-context report**.
- Let users select All, Supporting, or Opposing where that scope exists, preview the exact included papers, remove individual papers, and see an explanation for empty or unavailable scopes.
- For opinion search, implement All, Supporting, and Opposing abstract-based evidence syntheses as described in the matrix above.
- For citation search, implement All abstract-based evidence synthesis; expose Supporting/Opposing filters only if the corresponding query classifications are present. Clearly state that graph links are not proof of citation stance.
- Return structured reports with a scope, input-evidence type, per-paper source references, claims/findings, cross-paper themes, disagreements, limitations, and explicit evidence gaps. Each generated claim must refer to one or more included source IDs; do not invent citations.
- Label abstract-only outputs as such and avoid presenting them as full-text or citation-context analyses.
- Add source-coverage and scope-filter tests: every cited paper must be in the selected input set, and no paper outside that set may silently influence the report.

### Release 3 — Citation-context evidence, when obtainable

- Add a citation-context report only after the backend can retrieve, retain, and associate usable citation passages (or authorized full-text passages) with both the focal paper and citing paper.
- Build a source adapter around a provider or licensed/open corpus that actually supplies citation contexts. Do not assume the current sampled citation graph or metadata API returns the text needed.
- For each relationship, show the passage, source paper, location/link where available, and a cautious relation label such as uses, extends, compares, supports, or challenges. Preserve “unclear/other” and allow the model to abstain.
- Treat All as all selected focal search-result papers with eligible contexts; Supporting/Opposing filter those focal papers only when query classifications exist. The relation label is derived from the citing passage, not inherited from the opinion label.
- If no eligible passage exists, explain why the action is unavailable or report that context could not be retrieved; never replace the missing evidence with a guess from titles, abstracts, or graph direction.
- Add evaluations for passage retrieval, relation-label quality, abstention, and source attribution. Use only material the user and application are authorized to access.

### Release 4 — Persistence, cache, model routing, and background jobs

- Persist summaries and reports with a fingerprint of their input papers/content, scope, task, prompt version, model, and creation time.
- Add Redis as a cache-aside optimization with TTL and explicit invalidation/versioning. PostgreSQL remains the durable source of truth.
- Introduce a small Strategy-based model client/router so task profiles can choose a model and an explicit fallback policy. Keep provider-specific request/response details out of application services.
- Prefer Spring AI as the first Spring-native abstraction to evaluate when adding another provider; migrate incrementally and keep direct integration if it remains simpler. Do not add Spring AI and LangChain4j together without a specific need.
- Make model selection configurable by task (for example, short summaries versus evidence synthesis), with cost/latency/quality trade-offs documented and tested.
- Move long-running search, classification, and synthesis workflows to RabbitMQ-backed workers where asynchronous execution improves the user experience.
- Return a durable job ID and expose pending, running, completed, and failed states to the frontend. Persist job state/results in PostgreSQL; implement idempotent processing, bounded retries, and a dead-letter path for repeated failures.
- Keep synchronous APIs for short operations; add operational visibility for queue depth, retries, job duration, and provider failures without recording secrets.

### Release 5 — Graph UX and reviewed document export

- Add a dismissible fullscreen graph view while preserving node interactions. Treat a detached browser window as a later enhancement because it needs shareable/persisted graph state.
- Add Google Docs export only after the report is source-linked and previewable. Show the selected scope, report, and bibliography before export; preserve links to source papers and distinguish generated synthesis from source claims.
- Ask for Google authorization only when the user chooses export. Do not export automatically when a report finishes.
- Keep the single-paper abstract summary exportable as a referenced item, distinct from multi-paper evidence reports.

### Release 6 — Accounts, history, and notifications

- Preserve guest use while allowing sign-in to save searches, graph state, and generated summaries to an account.
- Enforce account ownership for history and saved data; provide deletion and retention controls.
- Add a history sidebar with search, rename, revisit, and delete actions.
- Show in-app job completion and error notifications. Offer browser notifications and sound only as explicit opt-ins, with accessible non-audio status as the default.

### Architecture and technology direction

- Keep the browser client in React and migrate JavaScript to TypeScript incrementally, not as a prerequisite for backend work.
- Keep Spring Boot as the API and worker platform. Spring AI is compatible with this stack; provider abstraction, structured model output, and vector-store integration do not require changing the backend language.
- Keep PostgreSQL as the system of record. Add pgvector only if PaperMind begins retrieving from a meaningful corpus of paper text; abstract summarization alone does not require RAG.
- Add Redis for measured/reproducible caching use cases and RabbitMQ for durable asynchronous jobs. Run both locally through optional Docker Compose profiles.
- Keep external services behind interfaces and configuration. Use standard containers, environment-based configuration, health checks, and database migrations so deployment is not tied to one cloud provider.
- Candidate deployment shapes include a container platform such as Google Cloud Run, AWS App Runner/ECS, or Azure Container Apps, plus a separately managed PostgreSQL service. Free tiers and quotas change; verify current limits, persistence, cold-start behavior, and AI-provider costs before choosing.

### Out of scope for these releases

- Folding DriveAtlas file organization or cross-file editing into PaperMind.
- Claiming support for full-paper synthesis when only metadata/abstracts are available.
- Adding Kafka, MongoDB, PyTorch, or multiple orchestration frameworks solely to increase the technology list.
- Automatically overwriting source papers or exporting generated conclusions without user review.

## Run locally with Docker

Prerequisites: Docker Desktop with Compose, and a Google AI Studio key if you want AI search and summaries.

1. Copy `.env.example` to `.env` in the repository root.
2. Set a strong local `DB_PASSWORD` and add your `GOOGLE_AI_API_KEY`.
3. Run `docker compose up --build`.
4. Open [http://localhost:3000](http://localhost:3000).

Compose starts PostgreSQL, the Spring API, and the frontend. The Compose profile uses Hibernate `update` for a disposable local database. Production defaults to `validate`; deploy against an initialized database and use schema migrations for production changes.

To run the frontend outside Docker, copy `frontend/.env.example` to `frontend/.env`, fill in the Google Drive values if needed, then run `npm ci` and `npm start` in `frontend/`. Vite serves at `http://localhost:5173` by default; add that exact origin to `CORS_ALLOWED_ORIGINS` in the root `.env` (keep `http://localhost:3000` too if you also use the Compose frontend). Add `http://localhost:5173` to the Google OAuth authorized JavaScript origins and the API key's website referrers when testing Google Drive locally. Run the backend with `./gradlew bootRun` after setting the database and Gemini environment variables in your shell.

## API credentials

### Gemini (server-side secret)

Create a key in [Google AI Studio](https://aistudio.google.com/app/apikey). Set it as `GOOGLE_AI_API_KEY` in the root `.env` for Docker Compose, or as a protected environment/Secret Manager value in deployment. **Do not put this key in source files, frontend variables, or Git.** The backend sends it in the `x-goog-api-key` request header.

The model is selected with `GOOGLE_AI_MODEL`; `GOOGLE_AI_FALLBACK_MODEL` is tried when Gemini returns a temporary 429 or 503. The fallback defaults to `gemini-3.5-flash-lite`. Choose models currently available to your account.

### Google Drive (browser credentials)

Set up Google Drive access in [Google Cloud Console](https://console.cloud.google.com/):

1. Select or create the Google Cloud project for PaperMind. Go to **APIs & Services > Library**, search for **Google Drive API**, and click **Enable**.
2. Open **Google Auth platform > Branding** and set the app name and support email. Open **Google Auth platform > Audience** and choose **External** for users outside your Workspace organization, or **Internal** for users in your organization only.
3. If the app's publishing status is **Testing**, go to **Audience > Test users > Add users** and add every Google account that needs to sign in during development. Save the list. Accounts not listed receive `403: access_denied` and cannot authorize the app. After adding an account, refresh PaperMind and choose that same account in Google's sign-in prompt. Testing mode is appropriate for development; publishing to all users may require Google's verification, depending on the scopes.
4. Open **Google Auth platform > Data Access** and add the `https://www.googleapis.com/auth/drive.file` scope used by the Drive export feature.
5. Open **Google Auth platform > Clients > Create client**. Choose **Web application**. Under **Authorized JavaScript origins**, add `http://localhost:3000` for Docker Compose. If running Vite directly with `npm start`, also add `http://localhost:5173`. For deployment, add the exact frontend origin, such as `https://papermind.example.com`. An origin includes scheme, hostname, and optional port, but no path or trailing slash. This app uses a browser token flow and does not need an authorized redirect URI.
6. Create the client and copy its **Client ID**. Drive upload uses the signed-in user's OAuth access token; this frontend does not need a Google API key.

Set this in `frontend/.env` for local npm development, or as a Vite build variable for the frontend image:

- `VITE_GOOGLE_CLIENT_ID`: OAuth client ID. It is public by design; restrict it with authorized JavaScript origins.

For local npm development, copy `frontend/.env.example` to `frontend/.env` and set `VITE_GOOGLE_CLIENT_ID` there. Restart Vite after changing it. For Docker Compose, set `VITE_GOOGLE_CLIENT_ID` in the repository-root `.env` because Compose builds the frontend image from that file. Rebuild the frontend after changing the Client ID with `docker compose up --build -d frontend`. Vite variables are compiled into browser code, so the Client ID is public and must be protected with authorized origins. Never put server-side secrets in frontend environment files. Do not commit either `.env` file.

### Database and deployment settings

The backend reads `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `PORT`, `DDL_AUTO`, and `CORS_ALLOWED_ORIGINS` from the environment. `CORS_ALLOWED_ORIGINS` is a comma-separated list of exact frontend origins. Do not use `DDL_AUTO=create` in a persistent environment.

## Development commands

From the repository root:

```sh
./gradlew build
```

From `frontend/`:

```sh
npm ci
npm start
npm run build
npm test
```

## CI/CD

GitHub Actions builds the backend against a temporary PostgreSQL service, audits frontend dependencies, and builds/tests the frontend. Dependabot checks Gradle, npm, and GitHub Actions dependencies weekly.

The manual `Deploy to Cloud Run` workflow publishes the API and frontend containers to Artifact Registry, deploys both Cloud Run services, and updates API CORS to the deployed frontend origin. Before running it, configure:

- GitHub Actions variables: `GCP_PROJECT_ID`, `GCP_REGION`, `GCP_ARTIFACT_REPOSITORY`, `GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_SERVICE_ACCOUNT`, `CLOUD_RUN_SERVICE_ACCOUNT`, `DB_URL`, `DB_USERNAME`, `GOOGLE_AI_MODEL`, and `VITE_GOOGLE_CLIENT_ID`.
- Google Secret Manager secrets: `papermind-google-ai-api-key` and `papermind-db-password`. Grant the Cloud Run runtime service account access to these secrets.
- Google Cloud permissions: Workload Identity Federation for the GitHub deploy service account, Artifact Registry writer access, and Cloud Run deploy permissions. Create the Artifact Registry Docker repository first.
- A PostgreSQL database already initialized with the PaperMind schema and reachable at `DB_URL`. Production uses Hibernate `validate`; it will not create or modify tables.

The workflow makes both services public. Add the deployed frontend URL to the OAuth client's authorized JavaScript origins, and review Cloud Run quotas and request-cost controls before accepting public traffic.

## Security note

Credentials were previously committed in application properties and may remain in Git history. Revoke and replace those credentials in Google AI Studio and rotate the database password. Removing a value from the latest file does not erase historical commits; if this repository is shared, follow your hosting providerâ€™s guidance for cleaning sensitive history.
