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

本仓库包含以下工作的代码与数据：

- <a href="https://arxiv.org/abs/2606.31206">[ASE 2026 工具与数据集轨道] FeatX: Editing Software by Editing Features for Repository-Level Code Evolution</a>
- <a href="https://arxiv.org/abs/2510.11039">RepoSummary: Feature-Oriented Summarization and Documentation Generation for Code Repositories</a>

## 👋 项目概述

FeatX 是一种面向特性的 LLM 辅助编程交互界面。  
给定一个**已有的软件代码仓库**作为修改对象，FeatX 首先对仓库中的功能（features）进行总结，并构建一个全面的上下文 **CodeMap**。  
在此基础上，FeatX 利用大语言模型生成满足新功能需求的代码，突出显示由此产生的代码变更以供用户确认，最终输出**修改后的代码仓库**。

<img src="/docs/asserts/FeatX_approach.png">
<img src="/docs/asserts/FeatX_Pannels.png">

## 🚀 系统部署

本节提供两种部署方式。ASE 工件评估和普通本地试用推荐使用 Docker Compose；手工部署仅适合开发或定制环境，不推荐作为评审路径。

### 1. Docker Compose 部署（推荐）

要求：

*   Docker 与 Compose v2
*   首次构建需要联网下载依赖
*   推荐使用 x86_64 Linux CPU 环境；不需要 GPU
*   推荐 8-16 GiB 内存和至少 20 GB 可用磁盘空间

此路径的配置由 `.env.example` 和 `docker-compose.yml` 管理。执行以下命令启动 FeatX：

```bash
cp .env.example .env
docker compose build
docker compose up -d
```

Docker Compose 会启动 MySQL、Spring Boot 后端（包含 RepoSummary Python 环境）以及 Nginx 前端。MySQL 在容器内运行，不需要在宿主机上安装 MySQL。

启动后可执行以下检查：

```bash
docker compose ps
curl -i http://localhost:8080/connect/test
curl -i http://localhost:3000/
curl -i http://localhost:3000/api/connect/test
```

然后在浏览器访问 [http://localhost:3000/](http://localhost:3000/)。

如果 `8080` 或 `3000` 端口已被占用，可以覆盖宿主机端口：

```bash
BACKEND_PORT=28080 FRONTEND_PORT=23000 docker compose up -d
```

Docker 包中已经包含 NBlog 种子数据和对应源代码快照：

*   `datasets/mysql/featx_seed.sql` 初始化 MySQL 特性映射数据。
*   `datasets/repos/12` 初始化后端容器中的 `/workspace/repos/12`。

种子数据查看和 smoke checks 不需要 LLM API Key。完整的 LLM 特性抽取与代码演化流程需要在 `.env` 中配置凭据：

```env
LLM_API_URL=https://api.deepseek.com/chat/completions
LLM_API_KEY=<reviewer-api-key>
LLM_API_MODEL=deepseek-v4-pro

OPENAI_BASE_URL=https://api.deepseek.com
OPENAI_API_KEY=<reviewer-api-key>
OPENAI_API_MODEL=deepseek-v4-pro
```

停止服务：

```bash
docker compose down
```

如果需要重新初始化种子数据，再删除持久化卷：

```bash
docker compose down -v
```

### 2. 手工部署（不推荐用于工件评估）

手工部署是在 Docker Compose 之外分别运行各组件。只有在需要定制运行环境时才建议使用。

要求：

*   Java JDK 17，以及 `Backend/mvnw`
*   Node.js 20.x 和 npm 10.x
*   Python 3.10，以及 `RepoSummary/requirements.txt` 中的依赖
*   MySQL 8，并使用 `Backend/src/main/java/cn/edu/pku/lixutian/dao/update-schema.sql` 初始化 schema
*   用于完整流程的 OpenAI-compatible LLM API
*   生产环境前端可使用 Nginx 或其他静态文件服务器

配置文件：

*   `Backend/src/main/resources/application.properties` 配置仓库缓存路径、MySQL 和 Java 后端 LLM 参数，也可以参考 `Backend/src/main/resources/example.properties`。
*   `RepoSummary/.env` 配置相同的仓库缓存路径、MySQL 连接和 Python 侧 LLM 参数。
*   后端与 RepoSummary 的数据库和仓库缓存目录必须指向同一套环境。
*   顶层 `.env` 只用于 Docker Compose。手工部署时请在上述组件配置文件中设置等价参数。

构建并启动后端：

```bash
cd Backend
./mvnw -DskipTests package
java -jar target/*.jar
```

构建前端：

```bash
cd Frontend
npm install
npm run build
```

将 `Frontend/build/` 交给 Nginx 或其他静态文件服务器，并把前端 API 请求代理到 `http://localhost:8080/`。

## 💽 使用说明

完成部署后，在浏览器中访问：

👉 [http://localhost:3000/](http://localhost:3000/)

即可通过 Web 界面使用 FeatX。

我们提供了一段系统演示视频，对特性查看、特性编辑以及代码精简（debloating）等完整流程进行了逐步讲解：

👉 [https://youtu.be/YyCwPy8hf48](https://youtu.be/YyCwPy8hf48)

如果你不希望在本地部署系统，也可以使用在线演示版本：

👉 [https://lixutian.github.io/FeatX](https://lixutian.github.io/FeatX)

在线 Demo 展示了 FeatX 的核心交互范式，适合快速体验与评估。

## ✍️ 许可证

本项目采用 **MIT License** 开源协议，详见 [LICENSE](/LICENSE) 文件。
