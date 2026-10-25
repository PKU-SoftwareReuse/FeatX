# FeatX

React 前端、Python 主后端和独立 RepoSummary 服务，通过 HTTP 和共享项目目录协作。

## 启动

```bash
./dev.sh up
./dev.sh restart
./dev.sh down
./dev.sh ps
./dev.sh logs
```

- 页面入口：`http://服务器IP:3010`，前端热更新。
- API 文档：`http://服务器IP:3010/docs`。
- 主后端：`127.0.0.1:13010`，代码自动重载。
- RepoSummary：`127.0.0.1:18091`。

三个进程由当前用户的 systemd 管理，关闭终端不会停止；没有配置开机自启。
这是无鉴权的开发服务，不作为公网生产环境使用。

首次安装依赖：
```bash
python3 -m venv Backend/.venv
Backend/.venv/bin/pip install -r Backend/requirements.txt
(cd Frontend && npm ci)
npm ci --prefix tools/codex
```

## 配置

主服务配置：

| 配置 | 作用 | 生效方式 |
|---|---|---|
| REPOSUMMARY_HTTP_URL | 外部总结服务的 HTTP 根地址；留空连接内置服务 `http://127.0.0.1:18091`，填写后连接失败不会回退 | `./dev.sh restart` |
| SHARED_PROJECT_ROOT | 双方共享的项目根目录 | `./dev.sh restart` |
| GITHUB_PROXY_URL | GitHub 代理地址；空值或不配置则直连 | 下次 Git 请求 |
| GIT_CLONE_TIMEOUT_SECONDS | Git 下载和版本切换的超时秒数 | 下次 Git 请求 |

`dev.sh` 读取 `.env` 中的服务配置，shell 环境变量可覆盖它；不会执行 `.env` 内容。

主后端启动时会立即读取 `data/codex-home/config.toml` 中当前服务商的 `/models`，之后每 15 分钟同步一次到 `model_catalog_json` 指定的目录文件。使用现有认证，不调用模型、不改默认模型；失败或返回空列表时保留旧目录。工作台每 15 分钟及重新聚焦时读取列表，保留仍有效的当前选择；执行任务期间暂缓页面刷新。扫描状态随 `GET /api/agent/models` 的 `modelSync` 返回。
`.env.example` 为配置样例。不要提交私有 `.env` 或运行数据。

## 项目导入

- 文件夹上传保留文件与目录结构，最多 2000 个文件、100 MiB。
- 没有 `.git` 时初始化 `main`；有完整有效的 `.git` 时保留历史、分支和远程配置。
- 上传后追加“导入项目起点”提交，遵循 `.gitignore`，不推送远程。
- Git 导入支持仓库、GitHub 分支和标签链接；Commit ID 单独选填。
- GitHub 有代理配置时走代理，代理失败时报错，不静默直连。
- 导入成功后自动调用 RepoSummary；项目设置中可重新总结。

## 智能体

项目固定使用 `tools/codex/` 下的 **0.164.0**，不调用全局 Codex，也不自动升级。
先登录这份副本：

```bash
./tools/codex/codex login --device-auth
./tools/codex/codex login status
```

默认登录目录为 `data/codex-home/`，不会复制或修改个人的 `~/.codex/`。
Codex 设置统一放在 `data/codex-home/config.toml`，不再从 FeatX 的 `.env` 读取模型、服务商、认证或代理配置。
模型、推理强度、服务商和权限直接使用 Codex 原生字段；认证使用 Codex 登录或服务商原生凭据机制。
标准进程环境变量仍由 Codex 自行处理，不增加 FeatX 自定义变量。

工作台从 Codex App Server 的 `model/list` 读取模型下拉框，不再要求每个模型创建一个配置文件。
`config.toml` 的 `model` 只控制默认模型；页面选择其他模型时仅对本次任务传递 `--model`，不改写配置。
模型目录短期缓存，配置或登录状态变化后重新读取；获取失败会提示原因，不编造模型列表。
已有的原生 `<名称>.config.toml` profile 仍兼容，但不是多模型选择的前提。

当前按要求设置 `sandbox_mode = "danger-full-access"` 和 `approval_policy = "never"`，
Codex 无沙箱限制、不询问审批。FeatX 的 Git 审阅与确认流程仍保留，但此权限不再提供工作区外的文件隔离。
任务的应用级超时固定为 30 分钟。每个项目同时只能有一个执行中或待确认任务。
`data/agent-runs/<runId>/` 保存任务起点、上下文 Prompt、JSONL 事件与普通文本结果；新任务不再复制候选仓库。
关闭页面不终止任务，重开工作台可恢复输出。主后端重启会中断执行，不会静默重跑。
智能体直接修改 `source/` 工作区；确认后创建本地提交，不推送远程。逐文件“选择保留”仅暂存；部分提交只保留已选文件，其余修改回退，本次任务结束。也可以选择“全部保留并提交”。放弃或执行失败时回退本次修改，保留原有忽略文件；执行期间不要并行手工编辑同一项目。
启动前要求工作区干净。若 Git 提交历史或摘要被外部修改，停止自动提交/回退并提示处理，不覆盖已有提交。主后端重启后，如任务被中断，先放弃清理再发起新任务。

## 目录

```text
Backend/      主后端：项目、上传、Git、任务、共享文件和接口
Frontend/     中文界面、组件和 API 适配层
PyBackend/    RepoSummary 服务
tools/codex/  固定版本 Codex、副本启动器与安装锁文件
 data/        项目管理记录及上传归档，不提交 Git
 docs/        架构和 RepoSummary 接口约定
 dev.sh       本机服务管理入口
```

共享目录位于 `SHARED_PROJECT_ROOT/<projectId>/`：
- `source/`：项目文件及 Git 仓库。
- `summary.json`：唯一的正式功能摘要。

主系统读取摘要展示 Epic / Feature；中央图谱按文件显示相关代码元素，暂不绘制边。
功能新增、修改和删除由智能体直接在项目工作区执行，输出通过 SSE 展示；最终回复为普通中文/Markdown，不要求或解析结果 JSON。确认提交时按用户输入保存功能项描述；修改保留已有代码关联，新增功能项的关联先为空，等待 RepoSummary 后续更新。审阅图谱展示实际 Git 变更文件，不编造元素关联。
删除项目会删除其本地管理记录、上传归档和共享项目目录，不删除远端 Git 仓库。

详见 [架构说明](docs/ARCHITECTURE.md) 和 [RepoSummary 接口约定](docs/REPOSUMMARY_API.md)。
