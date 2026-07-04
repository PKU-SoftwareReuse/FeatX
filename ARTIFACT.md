# Artifact Evaluation Guide

## Paper

FeatX: Editing Software by Editing Features for Repository-Level Code Evolution

## Purpose

This artifact contains the FeatX tool and the commit dataset used in the paper.
FeatX is a feature-oriented repository-level code evolution system. It extracts a
hierarchical feature structure from an existing Java repository, maps features to
code entities, and uses an LLM-backed evolution agent to turn feature edits into
code patches.

## Artifact Contents

- `Frontend/`: React web interface.
- `Backend/`: Spring Boot backend.
- `RepoSummary/`: Python feature extraction and repository summarization module.
- `AE_ABSTRACT.md`: artifact abstract text for the ASE AE submission.
- `datasets/commits/dataset.json`: Commit dataset used for the replay study.
- `datasets/README.md`: data inventory and validation commands.
- `datasets/mysql/featx_seed.sql`: data-only MySQL seed for the NBlog
  case-study feature map.
- `datasets/repos/12/`: NBlog repository snapshot matching `project_info.id =
  12` in the MySQL seed.
- `docker-compose.yml` and `docker/`: Docker Compose deployment for MySQL,
  backend, RepoSummary dependencies, and frontend.
- `LICENSE`: MIT license.
- `REQUIREMENTS`: Software and hardware requirements.
- `STATUS`: Current validation status and known limitations.

## Target Badges

- Functional: the artifact is documented and includes executable components plus
  quick checks for the dataset, frontend, Python module, and backend.
- Available: claim this badge only after archiving this repository/artifact on a
  long-term repository with a DOI, such as Zenodo, Figshare, or Software
  Heritage.
- Reusable: the Docker Compose deployment has been smoke-tested locally. Full
  LLM-backed workflows still require reviewer-provided API credentials.

## Docker Deployment

This is the recommended path for ASE Artifact Evaluation.
The Compose configuration builds and starts the MySQL, backend, and frontend
containers.

Requirements for this path are Docker with Compose v2, internet access for the
first build, and sufficient local disk space for the generated images and
dependency caches. No GPU is required. Configuration for this path is in
`.env.example` and `docker-compose.yml`; MySQL runs as a Docker service and does
not need to be installed on the host.

```bash
cp .env.example .env
docker compose build
docker compose up -d
```

The first build can take several minutes because it downloads Maven, npm,
Python, PyTorch CPU, and sentence-transformers dependencies. The backend image
is large because it includes the Java backend and the RepoSummary Python stack.

Smoke checks:

```bash
docker compose ps
curl -i http://localhost:8080/connect/test
curl -i http://localhost:3000/
curl -i http://localhost:3000/api/connect/test
```

Expected result:

- `docker compose ps` shows `mysql`, `backend`, and `frontend` running.
- The backend check returns HTTP 200.
- The frontend check returns the FeatX HTML page.
- The `/api/connect/test` check through Nginx returns HTTP 200.

If the default host ports are occupied, override them:

```bash
BACKEND_PORT=28080 FRONTEND_PORT=23000 docker compose up -d
curl -i http://localhost:28080/connect/test
curl -i http://localhost:23000/api/connect/test
```

LLM-backed paths require credentials in `.env`:

```env
LLM_API_URL=https://api.deepseek.com/chat/completions
LLM_API_KEY=<reviewer-api-key>
LLM_API_MODEL=deepseek-v4-pro

OPENAI_BASE_URL=https://api.deepseek.com
OPENAI_API_KEY=<reviewer-api-key>
OPENAI_API_MODEL=deepseek-v4-pro
```

Stop the deployment:

```bash
docker compose down
```

Remove persistent MySQL and repository-cache volumes:

```bash
docker compose down -v
```

The MySQL image imports `datasets/mysql/featx_seed.sql` only when the MySQL data
volume is empty. This seed contains the NBlog case-study data: 1 repository, 31
modules, 75 features, 713 code-map entries, and 2885 graph edges.
The backend image similarly includes the matching source snapshot at
`/workspace/repos/12`, initialized from `datasets/repos/12` when the
`featx-repos` volume is empty.

## Quick Checks

These checks do not require an LLM API key and are intended to finish quickly on
a standard Linux machine after dependencies are available.

### 1. Check the dataset

```bash
jq '[.dataset[] | .issues | length] | add' datasets/commits/dataset.json
jq -r '.dataset[] | "\(.project_name)\t\(.issues | length)"' datasets/commits/dataset.json
```

Expected output:

```text
38
FlappyBird  2
PlayEdu     15
NBlog       21
```

The 38 issues correspond to the 38 real-world feature-editing commits discussed
in the paper.

### 2. Build the frontend

```bash
cd Frontend
npm install
npm run build
```

Expected result: `npm run build` finishes successfully and creates
`Frontend/build/`. Warnings from `diff2html` source maps and ESLint do not block
the production build.

### 3. Syntax-check the Python module

```bash
cd RepoSummary
python3 -m compileall -q src
```

Expected result: the command exits with status 0.

### 4. Build the backend

```bash
cd Backend
./mvnw -DskipTests package
```

Expected result: Maven builds the Spring Boot backend. The first run requires
network access to download Java dependencies and can take several minutes.

## Manual Local Deployment

This alternative path is for running the components outside Docker Compose. It
is not recommended for artifact evaluation because the Docker Compose path is
the tested package. Manual deployment requires MySQL 8, Java 17, Node.js 20,
Python 3.10, and an LLM API compatible with OpenAI-style chat completions. See
`README.md` for detailed configuration fields.

At minimum:

1. Create a MySQL database and initialize it with
   `Backend/src/main/java/cn/edu/pku/lixutian/dao/update-schema.sql`.
2. Create `Backend/src/main/resources/application.properties` from
   `Backend/src/main/resources/example.properties`.
3. Create `RepoSummary/.env` with the same database and repository-cache path.
4. Configure LLM API endpoint, key, and model in both places.
5. Start the backend and frontend.

The top-level `.env` file is for Docker Compose. Manual deployment should set
the equivalent values in the backend and RepoSummary configuration files.

The web UI is expected at `http://localhost:3000/`, with the backend listening on
`http://127.0.0.1:8080`.

## Demonstration

The paper demonstration video is available at:

https://youtu.be/OZqKZ4Ii-yM

An online demo is available at:

https://lixutian.github.io/FeatX

## Notes for Reviewers

The LLM-backed feature extraction and code evolution paths require external API
credentials and may incur provider-side cost. The quick checks above are meant
to verify the packaged artifact structure without using paid LLM calls.
