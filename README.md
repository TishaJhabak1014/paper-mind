# PaperMind

PaperMind is an AI-assisted research discovery app. Search Semantic Scholar, explore citation and supporting/opposing opinion graphs, summarize abstracts with Gemini, and export summaries to Google Drive.

## Stack

- Java 17, Spring Boot, Spring Data JPA, PostgreSQL
- React 18, Vite, Material UI
- Gemini API for query analysis and summaries
- Semantic Scholar Graph API for paper metadata and citations
- Google Drive API for user-authorized document export

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
