# FeatX Artifact Abstract

## Paper title

FeatX: Editing Software by Editing Features for Repository-Level Code Evolution

## Link to the accepted paper

Preprint: https://arxiv.org/abs/2606.31206

## Purpose

This artifact supports the ASE 2026 Tool and Dataset Track paper. It contains
the FeatX web tool, backend, feature summarization module, Docker Compose
deployment, commit replay dataset, and a precomputed NBlog case-study seed.

The artifact is intended to let reviewers:

- run the FeatX web system locally through Docker Compose;
- inspect the NBlog feature map and matching source snapshot used by the tool;
- validate the included 38 feature-editing commits from FlappyBird, PlayEdu,
  and NBlog;
- check that the frontend, backend, Python module, and dataset are packaged in
  an executable and inspectable form.

The packaged smoke checks do not fully rerun every LLM-backed experiment in the
paper. Full feature extraction and code evolution require an external
OpenAI-compatible API and may incur provider-side cost.

## Badge

We request the Functional, Reusable, and Available badges.

- Functional: the artifact includes documented build, deployment, and smoke
  checks for the dataset, frontend, backend, and Python module.
- Reusable: the Docker Compose deployment, source code, seed data, and
  component-level documentation are included.
- Available: the artifact snapshot is archived on Zenodo with DOI
  `10.5281/zenodo.21187017`.

## Technology skills assumed by the reviewer evaluating the artifact and hardware requirements

The recommended path assumes basic Linux shell usage and Docker Compose v2.
Reviewers should be able to run shell commands, start Docker containers, inspect
HTTP endpoints with `curl`, and open a browser at `http://localhost:3000/`.

Manual deployment is not recommended for artifact evaluation. It requires Java
17, Node.js 20, Python 3.10, MySQL 8, and component-specific configuration.

## Provenance

The artifact includes:

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

The NBlog seed was exported from a validated FeatX Docker MySQL instance. The
seed contains only the application tables used by FeatX and does not include API
credentials, MySQL users, logs, or unrelated operational tables. API keys are
intentionally not embedded in the public artifact.

## Instructions

Recommended setup:

```bash
cp .env.example .env
docker compose build
docker compose up -d
```

If ports 8080 or 3000 are occupied:

```bash
BACKEND_PORT=28080 FRONTEND_PORT=23000 docker compose up -d
```

Smoke checks:

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

Full LLM-backed workflows require credentials in `.env`:

```env
LLM_API_URL=https://api.deepseek.com/chat/completions
LLM_API_MODEL=deepseek-v4-pro
OPENAI_BASE_URL=https://api.deepseek.com
OPENAI_API_MODEL=deepseek-v4-pro
```

Set `LLM_API_KEY` and `OPENAI_API_KEY` locally or provide them through
reviewer-only submission notes; do not commit them.
