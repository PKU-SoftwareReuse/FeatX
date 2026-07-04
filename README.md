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

## 👋 Overview

FeatX is a feature-oriented interface for LLM-assisted programming. 
Given *an existing software repository* to be modified, 
FeatX first summarizes the features of the repository, and then constructs a comprehensive contextual CodeMap. 
Based on the context, FeatX leverages an LLM to generate code for new feature requirements, highlights the resulting code changes for confirmation, and finally produces *a modified repository*.

<img src="/docs/asserts/FeatX_approach.png">
<img src="/docs/asserts/FeatX_Pannels.png">

## 🚀 Set Up

This section describes how to deploy the FeatX system, including server-side environment preparation, backend and frontend initialization, and service exposure. The setup assumes a Linux (Ubuntu 22.04) or Windows 11 environment and a basic familiarity with command-line operations.

### 0\. Docker Compose Quick Start

The recommended Artifact Evaluation path is Docker Compose. It starts MySQL,
the Spring Boot backend, the RepoSummary Python environment, and an Nginx-served
React frontend.

```bash
cp .env.example .env
docker compose build
docker compose up -d
```

The first build downloads Java, Node, Python, PyTorch CPU, and NLP dependencies,
so it can take several minutes and requires multiple GB of disk space.

After startup, verify the deployment:

```bash
docker compose ps
curl -i http://localhost:8080/connect/test
curl -i http://localhost:3000/
curl -i http://localhost:3000/api/connect/test
```

Open the UI at [http://localhost:3000/](http://localhost:3000/).

If ports `8080` or `3000` are already in use, override only the host ports:

```bash
BACKEND_PORT=28080 FRONTEND_PORT=23000 docker compose up -d
```

Then use [http://localhost:23000/](http://localhost:23000/) and
`http://localhost:28080/connect/test`.

LLM-backed feature extraction and code evolution require API credentials. Put
them in `.env` before running the full workflow:

```env
LLM_API_URL=https://api.deepseek.com/chat/completions
LLM_API_KEY=<reviewer-api-key>
LLM_API_MODEL=deepseek-v4-pro

OPENAI_BASE_URL=https://api.deepseek.com
OPENAI_API_KEY=<reviewer-api-key>
OPENAI_API_MODEL=deepseek-v4-pro
```

Stop the stack with:

```bash
docker compose down
```

Remove the MySQL and repository-cache volumes only when you want a fresh state:

```bash
docker compose down -v
```

### 0.1 Migrating Existing MySQL Data

If you already have FeatX data in another MySQL instance, migrate only the
artifact tables used by the tool:

```bash
cp .env.migration.example .env.migration
# Fill SOURCE_MYSQL_* in .env.migration.
scripts/migrate_mysql_data.sh inspect
scripts/migrate_mysql_data.sh import
```

The script migrates `project_info`, `modules`, `features`, `code_map`, and
`graph_edge`. It first compares source and target columns, backs up the current
Docker MySQL tables into `migration_artifacts/`, then imports the source data.
Do not include `.env.migration` or `migration_artifacts/` in the artifact
archive.

If your machine uses a Docker wrapper or context, set `DOCKER_BIN` in
`.env.migration` or on the command line, for example:

```bash
DOCKER_BIN="docker --context default" scripts/migrate_mysql_data.sh inspect
```

The artifact Docker image also includes a curated seed dump at
`datasets/mysql/featx_seed.sql`. On a fresh MySQL volume, Docker imports this
data automatically and provides the NBlog case-study feature map used for quick
inspection in the UI. Existing volumes are not overwritten; run
`docker compose down -v` before startup if you need to reinitialize from the
seed.

The matching NBlog repository snapshot is included at `datasets/repos/12` and is
copied into the backend image at `/workspace/repos/12`. The numeric directory
matches `project_info.id = 12` in the seeded MySQL data.

### 1\. System Requirements

Ensure the following dependencies are installed before deployment:

For the Docker Compose path:

*   **Docker** with Docker Compose v2
*   **Internet access** for first-time dependency and image downloads
*   The Compose configuration starts the MySQL, backend, and frontend services.

For manual deployment:

*   **Operating System**: Ubuntu 22.04 or Windows 11
*   **Java**: JDK 17 (for the Spring Boot backend)
*   **Maven**: 3.9.9 (dependency and build management)
*   **Python**: 3.10 (for the feature summarization module)
*   **Database**: MySQL 8
*   **Node.js**: v20.18.2 (for the React frontend)
*   **npm**: 10.8.2 (frontend dependency management)
*   **Nginx**: 1.18.0 (reverse proxy and static file serving)

### 2\. Backend Service Setup

#### 2.1 Configuration

Create an `application.properties` file for the Java backend (based on the template in the repository) and customize the following fields:

```properties
# FeatX Configuration
ltm.repo_path=<absolute path to an empty directory used as repository cache>

# MySQL Configuration
spring.datasource.url=jdbc:mysql://<host>:<port>/<database>
spring.datasource.username=<username>
spring.datasource.password=<password>

# LLM Configuration
llm.api.url=https://<llm-provider-api>/chat/completions
llm.api.key=<api-key>
llm.api.model=<model-name>
```

In parallel, configure the Python-based feature summarization module by creating a `.env` file in its directory:

```env
# Must be consistent with application.properties
LOTM_REPO_PATH=<same cache directory as above>
DB_HOST=<host>
DB_PORT=<port>
DB_NAME=<database>
DB_USER=<username>
DB_PASSWORD=<password>

# LLM settings (can differ from backend agent)
OPENAI_BASE_URL=https://<llm-provider-api>
OPENAI_API_KEY=<api-key>
OPENAI_API_MODEL=<model-name>
```

> **Note:** All non-LLM configurations must remain consistent between the Java backend and Python module to ensure correct orchestration.

#### 2.2 Build and Deployment

1.  Compile the backend (skip if using a prebuilt JAR):
    ```bash
    mvn clean package
    ```
2.  Inject the customized `application.properties` into the generated JAR  
    (`BOOT-INF/classes/application.properties`).
3.  Initialize the database:
    ```sql
    CREATE DATABASE lotm;
    USE lotm;
    -- Execute schema definitions from /Backend/src/main/java/cn/edu/pku/lixutian/dao/update-schema.sql
    ```
    Create and authorize a dedicated database user:
    ```sql
    CREATE USER '<username>'@'%' IDENTIFIED BY '<password>';
    GRANT SELECT, INSERT, UPDATE, DELETE ON lotm.* TO '<username>'@'%';
    ```
4.  (Optional) Upload the backend JAR and Python module to the server, ensuring they reside in the same parent directory.
5.  Set up the Python environment:
    ```bash
    conda create -n RepoSummary python=3.10
    conda activate RepoSummary
    pip install -r requirements.txt
    ```
6.  Start the backend service:
    ```bash
    nohup java -jar LoTM-0.0.1-SNAPSHOT.jar > FeatX.log 2>&1 &
    ```
    Monitor logs:
    ```bash
    tail -f FeatX.log
    ```

### 3\. Frontend Service Setup

1.  Build the React frontend:
    ```bash
    npm run build
    ```
2.  (Optional) Deploy the generated `build/` directory to the server.
3.  Configure Nginx as a reverse proxy and static file server:

```nginx
server {
    listen 3000;
    server_name yourdomain.com;

    root /path/to/frontend/build;
    index index.html;

    location /api/ {
        proxy_pass http://localhost:8080/;
    }

    location / {
        try_files $uri /index.html;
    }
}
```

Restart Nginx after configuration. The FeatX frontend will then be accessible via port `3000`, with API requests transparently forwarded to the backend service.

Once all components are running, FeatX is ready for use.

## 🧪 Artifact Evaluation

For ASE 2026 Artifact Evaluation, see:

- [ARTIFACT.md](ARTIFACT.md): artifact contents, badge strategy, and quick checks
- [REQUIREMENTS](REQUIREMENTS): hardware, software, and service requirements
- [STATUS](STATUS): verified checks and known limitations

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

This project is licensed under the MIT License. See the [LICENSE](/LICENSE) file for details.
