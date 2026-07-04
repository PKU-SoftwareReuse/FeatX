# FeatX ASE 2026 工件说明

本仓库是以下论文的 ASE 2026 工件包：

**FeatX: Editing Software by Editing Features for Repository-Level Code Evolution**

相关链接：

- 论文预印本：<https://arxiv.org/abs/2606.31206>
- Zenodo 归档工件：以发布后的 Zenodo 记录页面为准。
- 在线演示：<https://lixutian.github.io/FeatX>
- GitHub 工件分支：<https://github.com/PKU-SoftwareReuse/FeatX/tree/demo-artifact>
- 演示视频：<https://youtu.be/OZqKZ4Ii-yM>

FeatX 是一个面向特性的 LLM 辅助仓库演化工具。给定一个已有 Java
仓库，FeatX 会抽取层次化的特性结构，维护特性到代码的映射，允许开发者
直接编辑自然语言特性描述，并通过 LLM 驱动的演化代理生成仓库级代码修改。

本工件包含源码、种子数据、Docker Compose 部署文件、可选的预构建容器镜像
以及面向审稿人的验证命令。ASE 要求的主文件包括：

- `Artifact_README.md`：英文主说明，包含 Getting Started 与逐步复现说明。
- `REQUIREMENTS.txt`：体系结构、硬件、软件与外部服务要求。
- `STATUS.txt`：申请的徽章、理由、已验证项目与限制。
- `LICENSE.txt`：使用和分发许可。
- `AE_Abstract.pdf`：ASE AE 提交所需的两页工件摘要。

## 推荐审稿路径

推荐使用仓库顶层的 Docker Compose 路径。该路径会启动 MySQL、Spring Boot
后端、RepoSummary Python 环境和 React 前端。启动、smoke test、NBlog
种子数据检查以及非 LLM 的界面浏览不需要 API key。

### 1. 创建 `.env`

在仓库根目录，也就是 `docker-compose.yml` 所在目录执行：

```bash
cp .env.example .env
```

如果只进行快速检查，`LLM_API_KEY` 和 `OPENAI_API_KEY` 可以留空。只有在审稿人
运行完整的 LLM 特性抽取或代码演化流程时，才需要在本地 `.env` 中填入提交系统
提供的 reviewer-only API key。真实 key 不应提交到仓库或打入归档包。

### 2. 启动容器化工件

如果工件一并提供预构建镜像包，先加载镜像，再启动服务：

```bash
docker load -i /path/to/FeatX_ASE26_docker_images_20260703.tar.gz
docker compose up -d
```

如果没有预构建镜像包，可从源码构建：

```bash
docker compose build
docker compose up -d
```

默认端口为后端 `8080`、前端 `3000`。如果端口被占用，可在 `.env` 中修改：

```env
BACKEND_PORT=28080
FRONTEND_PORT=23000
```

### 3. Smoke Test

```bash
docker compose ps
curl -i http://localhost:8080/connect/test
curl -i http://localhost:3000/
curl -i http://localhost:3000/api/connect/test
```

期望结果是三个服务均在运行，后端连接测试返回 HTTP 200，前端页面可访问，并且
`/api/connect/test` 能通过前端代理访问后端。

## 数据与可检查内容

工件支持以下审稿活动：

- 构建并运行 FeatX Web 工具。
- 检查 React 前端、Spring Boot 后端、RepoSummary 模块、MySQL schema 和
  Docker 部署文件。
- 检查 `datasets/commits/dataset.json` 中的 38 个特性编辑 commit issue。
- 检查已预计算的 NBlog 特性映射和匹配源码快照：
  - `datasets/mysql/featx_seed.sql`
  - `datasets/repos/12`

快速数据检查命令：

```bash
jq '[.dataset[] | .issues | length] | add' datasets/commits/dataset.json
jq -r '.dataset[] | "\(.project_name)\t\(.issues | length)"' datasets/commits/dataset.json
```

期望输出：

```text
38
FlappyBird  2
PlayEdu     15
NBlog       21
```

## NBlog 小例子

启动后访问：

```text
http://localhost:3000/
```

不需要 LLM key 的检查路径如下：

1. 在欢迎页打开 seeded `NBlog` 项目。
2. 在 `Feature Panel` 中展开 `Blog content management and retrieval`。
3. 选择描述以 `As a blog user, I want to retrieve a list of blog posts by title and category ID` 开头的特性。
4. 检查 `CodeMap Panel` 是否展示相关类和方法节点。
5. 点击 CodeMap 中的类节点，在 `Diff Panel` 中检查类级代码上下文。

如果配置了 reviewer-only LLM key，也可以运行演示视频中的小型编辑场景：在
NBlog 的博客内容 epic 下添加一个 “Good Morning” 一键发博客特性，观察
`Agent Panel` 的三阶段推理、`CodeMap Panel` 中标红的受影响文件，以及
`Diff Panel` 中的行级 diff。只有在确实希望把生成补丁应用到挂载的 NBlog
快照时，才点击 `Confirm Apply the Diff`。

## 不完全复现的内容

快速检查不会重新运行论文中所有 LLM 驱动实验。完整的特性抽取和代码演化依赖
外部 OpenAI-compatible LLM 服务，可能产生服务端成本，并且会受模型版本和服务
状态影响。论文中的受控用户研究结果由论文报告，本工件包不重新执行该研究。

## 许可证

本工件使用 MIT License 分发。完整条款见仓库根目录的 `LICENSE.txt`。
