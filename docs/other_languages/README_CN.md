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

本节介绍 FeatX 系统的部署流程，包括服务器端环境准备、后端与前端的初始化以及服务暴露方式。部署过程假设使用 Linux（Ubuntu 22.04）或 Windows 11 操作系统，并具备基本的命令行操作经验。

### 1\. 系统环境要求

在部署前，请确保已安装以下依赖环境：

*   **操作系统**：Ubuntu 22.04 或 Windows 11
*   **Java**：JDK 17（用于 Spring Boot 后端）
*   **Maven**：3.9.9（依赖管理与构建工具）
*   **Python**：3.10（用于特性总结模块）
*   **数据库**：MySQL 8
*   **Node.js**：v20.18.2（用于 React 前端）
*   **npm**：10.8.2（前端依赖管理）
*   **Nginx**：1.18.0（反向代理与静态资源服务）

### 2\. 后端服务部署

#### 2.1 配置说明

为 Java 后端创建 `application.properties` 配置文件（可基于仓库中提供的模板），并根据实际环境修改以下配置项：

```properties
# FeatX 配置
ltm.repo_path=<用于仓库缓存的空目录的绝对路径>

# MySQL 配置
spring.datasource.url=jdbc:mysql://<host>:<port>/<database>
spring.datasource.username=<username>
spring.datasource.password=<password>

# LLM 配置
llm.api.url=https://<llm-provider-api>/chat/completions
llm.api.key=<api-key>
llm.api.model=<model-name>
```

同时，在 Python 实现的特性总结模块目录下创建 `.env` 文件，配置如下内容：

```env
# 必须与 application.properties 保持一致
LOTM_REPO_PATH=<与后端相同的仓库缓存目录>
DB_HOST=<host>
DB_PORT=<port>
DB_NAME=<database>
DB_USER=<username>
DB_PASSWORD=<password>

# LLM 配置（可与后端 agent 使用不同模型）
OPENAI_BASE_URL=https://<llm-provider-api>
OPENAI_API_KEY=<api-key>
OPENAI_API_MODEL=<model-name>
```

> **注意：** 除 LLM 相关配置外，Java 后端与 Python 模块中的所有其他配置必须保持一致，以确保系统能够正确协同工作。

#### 2.2 构建与启动

1.  编译后端代码（如使用预编译 JAR 可跳过此步）：
    ```bash
    mvn clean package
    ```
2.  将定制好的 `application.properties` 注入生成的 JAR 文件中  
    （路径为 `BOOT-INF/classes/application.properties`）。
3.  初始化数据库：
    ```sql
    CREATE DATABASE lotm;
    USE lotm;
    -- 执行 /Backend/src/main/java/cn/edu/pku/lixutian/dao/update-schema.sql 中的建表语句
    ```
    创建并授权专用数据库用户：
    ```sql
    CREATE USER '<username>'@'%' IDENTIFIED BY '<password>';
    GRANT SELECT, INSERT, UPDATE, DELETE ON lotm.* TO '<username>'@'%';
    ```
4.  （可选）将后端 JAR 与 Python 模块上传至服务器，确保二者位于同一父目录下。
5.  配置 Python 运行环境：
    ```bash
    conda create -n RepoSummary python=3.10
    conda activate RepoSummary
    pip install -r requirements.txt
    ```
6.  启动后端服务：
    ```bash
    nohup java -jar LoTM-0.0.1-SNAPSHOT.jar > FeatX.log 2>&1 &
    ```
    查看运行日志：
    ```bash
    tail -f FeatX.log
    ```

### 3\. 前端服务部署

1.  构建 React 前端项目：
    ```bash
    npm run build
    ```
2.  （可选）将生成的 `build/` 目录部署到服务器。
3.  使用 Nginx 配置反向代理与静态文件服务：

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

配置完成后重启 Nginx。此时，FeatX 前端将通过 `3000` 端口访问，所有 API 请求会被透明地转发至后端服务。

当所有组件均正常运行后，FeatX 即可投入使用。

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
