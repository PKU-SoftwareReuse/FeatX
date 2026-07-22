<p align="center">
    <img src="/docs/asserts/featx_logo.png" style="height: 10em" alt="FeatX" />
</p>

<p align="center">
  <a href="/README.md">English</a> |
  <a href="/docs/other_languages/README_CN.md">中文简体</a>
</p>
<p align="center">
    <a href="https://www.java.com/">
        <img alt="Java" src="https://img.shields.io/badge/Java-17+-ED8B00?logo=java&logoColor=white">
    </a>
    <a href="https://spring.io/projects/spring-boot">
        <img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-3.x-6DB33F?logo=springboot&logoColor=white">
    </a>
    <a href="https://react.dev/">
        <img alt="React" src="https://img.shields.io/badge/React-18+-61DAFB?logo=react&logoColor=black">
    </a>
    <a href="https://copyright.princeton.edu/policy">
        <img alt="License" src="https://img.shields.io/badge/License-MIT-blue">
    </a>
</p>

---

Code and data for the following works:

- <a href="https://arxiv.org/abs/2606.31206">[ASE 2026 Tools and Datasets] FeatX: Editing Software by Editing Features for Repository-Level Code Evolution</a>
- <a href="https://arxiv.org/abs/2510.11039">RepoSummary: Feature-Oriented Summarization and Documentation Generation for Code Repositories</a>

Archived ASE 2026 artifact: [https://doi.org/10.5281/zenodo.21190519](https://doi.org/10.5281/zenodo.21190519)

## 👋 Overview

FeatX is a feature-oriented interface for LLM-assisted programming. 
Given *an existing software repository* to be modified, 
FeatX first summarizes the features of the repository, and then constructs a comprehensive contextual CodeMap. 
Based on the context, FeatX leverages an LLM to generate code for new feature requirements, highlights the resulting code changes for confirmation, and finally produces *a modified repository*.

<img src="/docs/asserts/FeatX_approach.png">
<img src="/docs/asserts/FeatX_Pannels.png">

## 🚀 Set Up

This section gives two deployment paths. Docker Compose is the recommended path
for artifact evaluation and normal local use. Manual deployment is available for
development or customized environments, but is not recommended for artifact
evaluation.

### 1. Docker Compose Deployment (Recommended)

Requirements:

*   Docker with Compose v2
*   Internet access for the first build
*   A CPU-only x86_64 Linux machine is recommended; no GPU is required
*   8-16 GiB RAM and at least 20 GB free disk space are recommended

Configuration for this path is in `.env.example` and `docker-compose.yml`.
Copy `.env.example` to `.env` before starting the stack. The MySQL service runs
inside Docker Compose, so a host-installed MySQL server is not required.

Start FeatX:

```bash
cp .env.example .env
docker compose build
docker compose up -d
```

The Compose configuration builds and starts three services: MySQL, the Spring
Boot backend with the RepoSummary Python environment, and an Nginx-served React
frontend. The first build downloads Java, Node, Python, PyTorch CPU, and NLP
dependencies, so it can take several minutes.

After startup, verify the deployment:

```bash
docker compose ps
curl -i http://localhost:8080/connect/test
curl -i http://localhost:3000/
curl -i http://localhost:3000/api/connect/test
```

Open the UI at [http://localhost:3000/](http://localhost:3000/).

If ports `8080` or `3000` are already in use, override the host ports:

```bash
BACKEND_PORT=28080 FRONTEND_PORT=23000 docker compose up -d
```

Then use [http://localhost:23000/](http://localhost:23000/) and
`http://localhost:28080/connect/test`.

The Docker package includes seeded NBlog data and its matching source snapshot:

*   `datasets/mysql/featx_seed.sql` initializes the MySQL feature map.
*   `datasets/repos/12` initializes `/workspace/repos/12` in the backend
    container.

The seeded demo and smoke checks do not require an LLM API key. Full
LLM-backed feature extraction and code evolution require credentials in `.env`:

```env
LLM_API_URL=https://api.deepseek.com
LLM_API_KEY=<reviewer-api-key>

OPENAI_BASE_URL=https://api.deepseek.com
OPENAI_API_KEY=<reviewer-api-key>
OPENAI_API_MODEL=deepseek-v4-pro
```

Stop the stack:

```bash
docker compose down
```

Remove the persistent MySQL and repository-cache volumes only when you want to
reinitialize the seeded demo:

```bash
docker compose down -v
```

### 2. Manual Deployment (Not Recommended for Artifact Evaluation)

Manual deployment runs each component outside Docker Compose. Use it only when
you need to customize the runtime environment.

Requirements:

*   Java JDK 17 and Maven via `Backend/mvnw`
*   Node.js 20.x and npm 10.x
*   Python 3.10 and dependencies from `RepoSummary/requirements.txt`
*   MySQL 8 initialized with
    `Backend/src/main/java/cn/edu/pku/lixutian/dao/update-schema.sql`; the
    backend then applies `Backend/src/main/resources/db/migration` automatically
*   An OpenAI-compatible LLM API endpoint for full workflows
*   Nginx or another static server if serving the production frontend build

Configuration files:

*   `Backend/src/main/resources/application.properties` configures repository
    cache path, MySQL, and Java backend LLM settings. You can also start from
    `Backend/src/main/resources/example.properties`.
*   `RepoSummary/.env` configures the same repository cache path, MySQL
    connection, and Python-side LLM settings.
*   The backend and RepoSummary database/repository-cache settings must point to
    the same MySQL database and repository directory.
*   The top-level `.env` file is for Docker Compose. For manual deployment, set
    the equivalent values in the component-specific files above.

Build and start the backend:

```bash
cd Backend
./mvnw -DskipTests package
java -jar target/*.jar
```

Build the frontend:

```bash
cd Frontend
npm install
npm run build
```

Serve `Frontend/build/` with Nginx or another static file server, and proxy
frontend API requests to the backend at `http://localhost:8080/`.

## 🧪 Artifact Evaluation

For ASE 2026 Artifact Evaluation, see:

- [Artifact_README.md](Artifact_README.md): artifact contents, badge strategy, and quick checks
- [REQUIREMENTS.txt](REQUIREMENTS.txt): hardware, software, and service requirements
- [STATUS.txt](STATUS.txt): verified checks and known limitations

The artifact supports the following paper claims and review checks:

*   Tool availability: reviewers can build and run the FeatX web tool with
    Docker Compose, then open the UI at `http://localhost:3000/`.
*   Data availability: reviewers can inspect the 38 feature-editing commits in
    `datasets/commits/dataset.json`.
*   Case-study inspection: reviewers can inspect the seeded NBlog feature map
    and matching source snapshot through the Docker deployment.
*   Component buildability: reviewers can build the frontend and backend and
    syntax-check the Python module using the commands in `Artifact_README.md`.

The packaged quick checks do not fully rerun every LLM-backed experiment from
the paper. Full feature extraction and code evolution require a configured
OpenAI-compatible API key and may incur provider-side cost.

### Agent run logs and token usage

FeatX records every code-evolution Agent prompt, raw model response, call
metadata, and the run-level token summary. Docker Compose writes these files to
`agent-logs/<runId>/` on the host. Set `AGENT_LOGS_ENABLED=false` in `.env` to
disable file logging. Manual deployments default to `Backend/logs/agent-runs`
when the backend is started from the `Backend` directory; this can be changed
with `AGENT_LOG_DIR`.

`GET /llm/run?runId=<runId>` includes `tokenUsage` and `agentLogPath`.
`tokenUsage` reports the number of LLM calls and input, cached input, uncached
input, output, reasoning output, and total tokens. Providers do not always
return usage for every streaming call, so compare `reportedCalls` with `calls`;
the summary is complete when they are equal.

## 💽 Usage

After completing the deployment, open a web browser and navigate to:

👉 [http://localhost:3000/](http://localhost:3000/)

You can then access and interact with FeatX through the web-based interface.

A step-by-step walkthrough of the system, including feature inspection, feature editing, and debloating workflows, is provided in our video demonstration:  

👉 [https://youtu.be/OZqKZ4Ii-yM](https://youtu.be/OZqKZ4Ii-yM)

If you prefer not to deploy the system locally, an online demo is also available at:  

👉 [https://lixutian.github.io/FeatX](https://lixutian.github.io/FeatX)

The online demo showcases the core interaction paradigm of FeatX and can be used for quick exploration and evaluation.

## ✍️ License

This project is licensed under the MIT License. See the [LICENSE.txt](LICENSE.txt) file for details.
