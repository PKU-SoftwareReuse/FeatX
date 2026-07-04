# FeatX Artifact Abstract

## Paper

FeatX: Editing Software by Editing Features for Repository-Level Code Evolution

## Artifact Type and Requested Badges

This artifact supports the ASE 2026 Tool and Dataset Track paper. We request
the Functional and Reusable badges. The Available badge should be requested only
after the artifact snapshot is archived on a long-term repository with a DOI,
such as Zenodo, Figshare, or Software Heritage.

## Artifact Contents

The artifact includes the FeatX web tool, backend, feature summarization module,
Docker Compose deployment, commit replay dataset, and a precomputed NBlog
case-study seed:

- `Frontend/`: React user interface.
- `Backend/`: Spring Boot backend.
- `RepoSummary/`: Python feature extraction and repository summarization module.
- `datasets/commits/dataset.json`: 38 feature-editing commits from FlappyBird,
  PlayEdu, and NBlog.
- `datasets/mysql/featx_seed.sql`: data-only MySQL seed for the NBlog feature
  map.
- `datasets/repos/12`: NBlog source snapshot matching `project_info.id = 12`.
- `docker-compose.yml` and `docker/`: executable Docker Compose packaging.
- `README.md`, `ARTIFACT.md`, `REQUIREMENTS`, `STATUS`, and `LICENSE`.

## Hardware and Software Requirements

The recommended path requires Docker with Docker Compose v2 and internet access
for first-time image/dependency downloads. The Compose configuration starts the
MySQL, backend, and frontend services. A machine with at least 8 GB RAM and 15
GB free disk space is recommended. In this path, configuration is supplied by
`.env.example`/`.env` and `docker-compose.yml`; MySQL runs inside Docker.

Manual deployment is not recommended for artifact evaluation, but it is possible
with host-installed MySQL 8, Java 17, Node.js 20, Python 3.10, and the same LLM
configuration. Manual deployment uses the backend and RepoSummary configuration
files instead of the top-level Docker `.env`.

Full LLM-backed workflows require an OpenAI-compatible chat-completions API.
The artifact is configured for DeepSeek-compatible endpoints by default, but API
credentials are intentionally not embedded in the public package.

## Setup

```bash
cp .env.example .env
docker compose build
docker compose up -d
```

If ports 8080 or 3000 are occupied:

```bash
BACKEND_PORT=28080 FRONTEND_PORT=23000 docker compose up -d
```

The MySQL seed and NBlog repository snapshot are imported only when the Docker
volumes are empty. To reinitialize:

```bash
docker compose down -v
docker compose up -d --build
```

## Smoke Checks

```bash
docker compose ps
curl -i http://localhost:8080/connect/test
curl -i http://localhost:3000/
curl -i http://localhost:3000/api/connect/test
```

Expected result: MySQL, backend, and frontend are running; backend and frontend
return HTTP 200; the frontend proxy to `/api/connect/test` also returns HTTP
200.

Seeded data can be checked with:

```bash
docker compose exec -e MYSQL_PWD=featx mysql mysql -h127.0.0.1 -ufeatx lotm \
  -e "SELECT COUNT(*) FROM project_info;"
```

## External Services and Credentials

LLM-backed feature extraction and code evolution call an external
OpenAI-compatible API and may incur provider-side cost. Reviewers can still run
the Docker smoke checks and inspect the seeded NBlog feature map without any API
key. If full LLM workflows are evaluated, provide a reviewer-only API key with
limited quota and revoke it after the review period.

Example `.env` values:

```env
LLM_API_URL=https://api.deepseek.com/chat/completions
LLM_API_MODEL=deepseek-v4-pro
OPENAI_BASE_URL=https://api.deepseek.com
OPENAI_API_MODEL=deepseek-v4-pro
```

Set `LLM_API_KEY` and `OPENAI_API_KEY` locally; do not commit them.

## Known Limitations

The first Docker build can take several minutes because it downloads Maven, npm,
Python, PyTorch CPU, and NLP dependencies. Full feature extraction and code
evolution depend on network access to the configured LLM provider, model
availability, and reviewer-provided credentials.
