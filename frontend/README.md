# PaperMind frontend

This React app uses Vite. From this directory:

```sh
npm ci
npm start
npm run build
npm test
```

Copy `.env.example` to `.env` for local development. `VITE_API_BASE_URL` points to the Spring API. Google Drive uses `VITE_GOOGLE_CLIENT_ID` and the Google Identity Services token flow; no browser API key is needed. Vite variables are compiled into browser code. Never put Gemini or database secrets in this file.
