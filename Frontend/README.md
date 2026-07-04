# FeatX Frontend

This directory contains the React frontend for the FeatX artifact. During
artifact evaluation, reviewers should normally use the top-level Docker Compose
deployment instead of running the frontend directly. Docker Compose builds this
frontend and serves the production bundle through Nginx.

## Artifact Evaluation Path

From the repository root:

```bash
cp .env.example .env
docker compose up -d
```

The frontend is then available at:

```text
http://localhost:3000/
```

If `FRONTEND_PORT` is changed in `.env`, use the configured port instead.

## Manual Development Commands

Manual commands are provided for development and component-level checks:

```bash
npm install
npm run build
```

Expected result: `npm run build` creates `Frontend/build/`. Source-map or
linting warnings do not prevent the production build when the command exits
successfully.

The frontend reads `REACT_APP_API_BASE_URL` at build time. In the Docker
artifact, this value is set to `/api` so that Nginx proxies frontend requests to
the backend service.
