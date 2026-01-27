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

- [Under Review] FeatX: A Feature-Oriented Interface for LLM-Assisted Programming
- <a href="https://arxiv.org/abs/2510.11039">RepoSummary: Feature-Oriented Summarization and Documentation Generation for Code Repositories</a>

## 👋 Overview

FeatX is a feature-oriented interface for LLM-assisted programming. 
Given *an existing software repository* to be modified, 
FeatX first summarizes the features of the repository, and then constructs a comprehensive contextual CodeMap. 
Based on the context, FeatX leverages an LLM to generate code for new feature requirements, highlights the resulting code changes for confirmation, and finally produces *a modified repository*.

<img src="/docs/asserts/FeatX.jpg">

## 🚀 Set Up

This section describes how to deploy the FeatX system, including server-side environment preparation, backend and frontend initialization, and service exposure. The setup assumes a Linux (Ubuntu 22.04) or Windows 11 environment and a basic familiarity with command-line operations.

### 1\. System Requirements

Ensure the following dependencies are installed before deployment:

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

## 💽 Usage

After completing the deployment, open a web browser and navigate to:

👉 [http://localhost:3000/](http://localhost:3000/)

You can then access and interact with FeatX through the web-based interface.

A step-by-step walkthrough of the system, including feature inspection, feature editing, and debloating workflows, is provided in our video demonstration:  

👉 [https://youtu.be/YyCwPy8hf48](https://youtu.be/YyCwPy8hf48)

If you prefer not to deploy the system locally, an online demo is also available at:  

👉 [https://lixutian.github.io/FeatX](https://lixutian.github.io/FeatX)

The online demo showcases the core interaction paradigm of FeatX and can be used for quick exploration and evaluation.

## ✍️ License

This project is licensed under the MIT License. See the [LICENSE](/LICENSE) file for details.