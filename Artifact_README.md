# FeatX ASE 2026 Artifact

This repository contains the ASE 2026 artifact package for:

**FeatX: Editing Software by Editing Features for Repository-Level Code Evolution**

Artifact access points:

- Paper preprint: <https://arxiv.org/abs/2606.31206>
- Archived artifact package: available from the published Zenodo record.
- Online demo: <https://lixutian.github.io/FeatX>
- GitHub artifact branch:
  <https://github.com/PKU-SoftwareReuse/FeatX/tree/demo-artifact>
- Demonstration video: <https://youtu.be/OZqKZ4Ii-yM>

FeatX is a feature-oriented interface for LLM-assisted repository evolution.
Given an existing Java repository, it extracts a hierarchical feature model,
maintains feature-to-code mappings, lets developers edit feature descriptions,
and invokes an LLM-backed evolution agent to generate repository-level code
patches.

The artifact package contains source code, seed data, a Docker Compose
deployment, prebuilt container images when supplied through the archive, and
validation commands for reviewers. The required ASE files are:

- `Artifact_README.md`: this file, with Getting Started and step-by-step instructions.
- `REQUIREMENTS.txt`: architecture, hardware, software, and service requirements.
- `STATUS.txt`: requested badges, justification, validation status, and limitations.
- `LICENSE.txt`: terms of use and distribution rights.
- `AE_Abstract.pdf`: two-page artifact abstract for the ASE AE submission.

All required reviewer-facing files are included in the artifact package. The
executable artifact is provided through Docker Compose and builds the following
images:

- `featx-mysql:ase26`
- `featx-backend:ase26`
- `featx-frontend:ase26`

## Part 1: Getting Started

This guide installs and smoke-tests the artifact. The reviewer-facing execution
path is Docker Compose. It starts MySQL, the Spring Boot backend, the
RepoSummary Python environment, and the React frontend. The seeded NBlog demo
and the smoke checks do not require an LLM API key.

Expected time: under 30 minutes after Docker is installed when the prebuilt
image archive is used. Building images from source can take longer on slow
networks because it downloads Maven, npm, Python, PyTorch CPU, and
sentence-transformers dependencies.

### 1. Requirements

Use an x86_64 Linux machine with Docker and Compose v2. No GPU is required.
Recommended resources are 16 GiB RAM and 20 GiB free disk space. See
`REQUIREMENTS.txt` for the full list.

### 2. Configure

Create `.env` in the artifact repository root, i.e., the same directory as
`docker-compose.yml`:

```bash
cp .env.example .env
```

Docker Compose automatically reads this root-level `.env` file when reviewers
run `docker compose ...` from the artifact directory. For startup, smoke checks,
seeded-data inspection, and the non-LLM NBlog example, reviewers can leave the
LLM fields empty:

```env
LLM_API_URL=https://api.deepseek.com
LLM_API_KEY=

OPENAI_BASE_URL=https://api.deepseek.com
OPENAI_API_KEY=
OPENAI_API_MODEL=deepseek-v4-pro
SENTENCE_TRANSFORMER_MODEL=sentence-transformers/all-mpnet-base-v2

MYSQL_ROOT_PASSWORD=featx_root
MYSQL_DATABASE=lotm
MYSQL_USER=featx
MYSQL_PASSWORD=featx

BACKEND_PORT=8080
FRONTEND_PORT=3000
```

Add reviewer-only LLM credentials only when exercising full feature extraction
or code evolution workflows. The API keys should be pasted into the local
`.env` file, or provided through the artifact submission system, and should not
be committed or archived with the artifact.

### 3. Build and start the containerized artifact

If the prebuilt Docker image archive is supplied with the artifact, use it
together with this source package: create `.env` as described above, stay in the
directory containing `docker-compose.yml`, load the images, and then start the
stack:

```bash
docker load -i /path/to/FeatX_ASE26_docker_images_20260703.tar.gz
docker compose up -d
```

This path avoids rebuilding the images and does not download build-time
dependencies. If the prebuilt image archive is not available, build the images
locally:

```bash
docker compose build
docker compose up -d
```

Expected services:

- `mysql`: MySQL 8 initialized with `datasets/mysql/featx_seed.sql`.
- `javabackend`: Spring Boot Java backend and Java-side analysis.
- `pybackend`: Python analysis service containing RepoSummary and FocusGraph.
- `frontend`: Nginx-served React UI with `/api` proxied to the backend.

If host ports `8080` or `3000` are already in use, start with alternate ports:

```bash
BACKEND_PORT=28080 FRONTEND_PORT=23000 docker compose up -d
```

Then replace `8080` with `28080` and `3000` with `23000` in the checks below.

### 4. Smoke test

```bash
docker compose ps
curl -i http://localhost:8080/connect/test
curl -i http://localhost:3000/
curl -i http://localhost:3000/api/connect/test
```

Expected result:

- `docker compose ps` shows `mysql`, `javabackend`, `pybackend`, and `frontend` running.
- `curl -i http://localhost:8080/connect/test` returns HTTP 200.
- `curl -i http://localhost:3000/` returns the FeatX frontend HTML page.
- `curl -i http://localhost:3000/api/connect/test` returns HTTP 200 through the
  frontend proxy.

Open the tool at <http://localhost:3000/>.

### 5. Stop or reset the artifact

```bash
docker compose down
```

To remove the persistent MySQL and repository-cache volumes and re-import the
seed data on the next start:

```bash
docker compose down -v
```

## Part 2: Step-by-Step Instructions

This section explains how the artifact supports the paper claims, how to inspect
the data and tool, and which claims are not fully reproduced by the packaged
quick checks.

### Supported paper claims

The artifact supports the following claims and review activities:

- **Tool availability.** Reviewers can build and run the FeatX web tool with
  Docker Compose, then inspect the UI at `http://localhost:3000/`.
- **Feature-oriented system structure.** Reviewers can inspect the React
  frontend, Spring Boot backend, RepoSummary feature extraction module, MySQL
  schema, and seeded feature map.
- **Dataset availability.** Reviewers can inspect the 38 feature-editing commit
  issues used by the replay study in `datasets/commits/dataset.json`.
- **Case-study inspection.** The Docker deployment includes a precomputed NBlog
  feature map and the matching source snapshot in `datasets/repos/12`.
- **Component buildability.** Reviewers can build or syntax-check the frontend,
  backend, Python module, and Docker services using the commands below.

### Claims not fully reproduced by the quick checks

The packaged smoke checks do not fully rerun every LLM-backed experiment from
the paper. Full feature extraction and code evolution require an external
OpenAI-compatible LLM API key, may incur provider-side cost, and can vary across
model versions. If reviewers evaluate those paths, credentials should be
provided through the submission system or a local `.env` file, and must not be
committed to the repository or archived with the artifact.

The controlled user study results are documented in the paper but are not
re-executed by the artifact package.

### Artifact layout

- `Frontend/`: React user interface.
- `JavaBackend/`: Spring Boot backend and Java-side evolution service.
- `PyBackend/`: Python analysis service containing RepoSummary, FocusGraph, and Python tooling.
- `datasets/commits/dataset.json`: 38 feature-editing commits from FlappyBird,
  PlayEdu, and NBlog.
- `datasets/README.md`: data inventory, schema notes, and validation commands.
- `datasets/mysql/featx_seed.sql`: data-only MySQL seed for the NBlog feature
  map.
- `datasets/repos/12/`: NBlog source snapshot matching `project_info.id = 12`.
- `docker-compose.yml` and `docker/`: Docker Compose deployment files.

### 1. Inspect the commit dataset

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

The 38 issues correspond to the real-world feature-editing commits used in the
paper's replay study.

### 2. Inspect the seeded NBlog case study

After starting Docker Compose, check the imported seed:

```bash
docker compose exec -e MYSQL_PWD=featx mysql mysql -h127.0.0.1 -ufeatx lotm \
  -e "SELECT COUNT(*) AS projects FROM project_info;"

docker compose exec -e MYSQL_PWD=featx mysql mysql -h127.0.0.1 -ufeatx lotm \
  -e "SELECT COUNT(*) AS modules FROM modules; SELECT COUNT(*) AS features FROM features; SELECT COUNT(*) AS code_map_entries FROM code_map; SELECT COUNT(*) AS graph_edges FROM graph_edge;"
```

Expected counts for the packaged seed:

```text
project_info: 1
modules: 31
features: 75
code_map: 713
graph_edge: 2885
```

The matching repository snapshot is mounted in the backend container at
`/workspace/repos/12` and is included in the artifact at `datasets/repos/12`.

### 3. Use the web tool

Start the Docker Compose stack and open:

```text
http://localhost:3000/
```

Run the following small example to inspect the four-panel workflow described in
the paper's Usage section. This path uses the seeded NBlog data and does not
require an LLM key.

1. On the welcome page, locate the seeded `NBlog` project card and click
   `Open`.
2. In the `Feature Panel`, expand `Blog content management and retrieval`.
3. Select the feature whose description starts with:
   `As a blog user, I want to retrieve a list of blog posts by title and category ID`.
4. Check that the `CodeMap Panel` renders related class and method nodes for the
   selected feature.
5. Click a class node in the CodeMap. The `Diff Panel` shows the class-level
   code context used for review.

The same non-LLM path can be checked from the backend API:

```bash
curl -s http://localhost:8080/project/getList \
  | jq '.[] | select(.projectName=="NBlog") | {id, projectName, loc, noc, nom, nof, summaryFlag}'

curl -s -X POST http://localhost:8080/project/select \
  -H 'Content-Type: application/json' \
  -d '{"repoId":12}'

curl -s http://localhost:8080/feature/get \
  | jq '.[] | select(.moduleDesc=="Blog content management and retrieval") | {moduleId, moduleDesc, features: (.featureList | length)}'

curl -s 'http://localhost:8080/graph/feature/maxGraph?featureId=40' \
  | jq '{nodes: (.nodes | length), edges: (.edges | length)}'
```

Expected result: the project query returns `NBlog`, the selected module reports
`features: 26`, and the graph query returns nonzero `nodes` and `edges`. If
alternate ports were used above, replace `8080` with the configured backend
port.

The following reviewer activities do not require an LLM key:

- browse the FeatX frontend;
- inspect the seeded NBlog project data;
- inspect CodeMap views, code context, diffs, and source snapshots included with
  the artifact;
- verify that the frontend can reach the backend through `/api/connect/test`.

Full LLM-backed feature extraction and code evolution require local credentials:

```env
LLM_API_URL=https://api.deepseek.com
LLM_API_KEY=<reviewer-api-key>

OPENAI_BASE_URL=https://api.deepseek.com
OPENAI_API_KEY=<reviewer-api-key>
OPENAI_API_MODEL=deepseek-v4-pro
```

Set these values in `.env` before starting the stack. Use rate-limited keys
provided through reviewer-only submission notes.

With credentials configured, reviewers can run the same small editing scenario
used in the demonstration video:

1. Open the seeded `NBlog` project.
2. In the `Feature Panel`, expand the first blog-content epic. In this packaged
   seed it is `Blog content management and retrieval`.
3. Click the add icon next to that epic and replace the placeholder text with:

   ```text
   As a blog content creator, I want to generate and post a "Good Morning" blog using only a simple button, so that I can get convenience.
   ```

4. Click `Submit`. The middle panel switches to `Agent Panel` and streams the
   three-stage Evolution Agent reasoning.
5. When generation finishes, switch back to `CodeMap Panel` if needed. Modified
   files are highlighted in red.
6. Click a highlighted class node. The `Diff Panel` shows the class-wise
   line-level changes.
7. Click `Confirm Apply the Diff` only when the generated patch should be
   applied to the mounted NBlog snapshot. After confirmation, the page refreshes
   and the new feature appears in the `Feature Panel`.

The refinement and cleanup paths follow the same interaction pattern. To
refine the generated feature, click its edit icon and submit:

```text
As a blog content creator, I want to generate and post a "Good Morning, My n-th Blog!" blog using only a simple button, where n is the number of blogs the user has already posted + 1, so that I can record my post number conveniently.
```

To remove the generated feature and restore the repository toward the original
snapshot, click its red delete/debloat icon, inspect the highlighted affected
entities, and confirm the diff.

### 4. Build or check individual components

Frontend:

```bash
cd Frontend
npm install
npm run build
```

Expected result: `npm run build` succeeds and creates `Frontend/build/`.
Warnings from source maps or ESLint do not block the production build.

Python module:

```bash
cd PyBackend
python3 -m compileall -q src
```

Expected result: the command exits with status 0.

Backend:

```bash
cd JavaBackend
./mvnw -DskipTests package
```

Expected result: Maven builds the Spring Boot backend. The first run downloads
Java dependencies and may take several minutes.

Docker configuration:

```bash
docker compose config
docker image ls 'featx-*'
```

Expected result: Compose configuration is valid and the three ASE images are
present after `docker compose build`.

### 5. Run reduced and full scopes

Reduced scope, intended for fast review:

```bash
cp .env.example .env
docker compose build
docker compose up -d
docker compose ps
curl -i http://localhost:8080/connect/test
curl -i http://localhost:3000/api/connect/test
jq '[.dataset[] | .issues | length] | add' datasets/commits/dataset.json
```

Full scope, intended only when reviewer credentials and time are available:

1. Add the LLM endpoint, model, and keys to `.env`.
2. Start the Docker Compose stack.
3. Use the FeatX UI to run feature extraction or feature editing on a supported
   Java repository.
4. Compare the generated localization and diffs with the paper's described
   workflow and the included dataset.

### 6. Manual local deployment, optional

Manual deployment is not recommended for artifact evaluation because Docker
Compose is the tested package. Use this path only for development or customized
environments. It requires MySQL 8, Java 17, Node.js 20, Python 3.10, and an
OpenAI-compatible LLM API for full workflows.

At minimum:

1. Create a MySQL database and initialize it with
   `JavaBackend/src/main/java/com/mycode/dao/update-schema.sql`. Versioned
   migrations under `JavaBackend/src/main/resources/db/migration` run automatically
   when the backend starts.
2. Create `JavaBackend/src/main/resources/application.properties` from
   `JavaBackend/src/main/resources/example.properties`.
3. Create `PyBackend/.env` with the same database and repository-cache path.
4. Configure the LLM API endpoint and key for the backend, and configure the
   endpoint, key, and model for RepoSummary.
5. Start the backend at `http://127.0.0.1:8080` and serve the frontend at
   `http://localhost:3000/`.

The top-level `.env` file is for Docker Compose. Manual deployment should use
the component-specific configuration files described above.

### 7. Package the artifact

To create a distributable archive from the current repository:

```bash
scripts/package_artifact.sh
```

The script excludes `.git`, local `.env`, build outputs, IDE metadata, and
runtime caches from the archive.

## License

This artifact is distributed under the MIT License. See `LICENSE.txt` for the
complete terms.
