# 当前架构

```text
浏览器 → React :3010 → /api → FastAPI :13010 → HTTP → RepoSummary :18091
                                  ↓                         ↓
                         data/projects/<id>.json     共享目录/<id>/summary.json
```

## 职责

- 主后端准备上传文件或 Git 工作区，分配项目 ID、初始化导入起点并提交总结任务。
- RepoSummary 从共享目录读取项目，报告阶段和进度，将摘要写入 summary.json。
- 主后端在任务完成后读取共享 JSON，验证项目和任务 ID；不从 HTTP 下载结果。
- 前端保留原页面和组件，显示真实的 Epic / Feature 层级，按文件组织代码元素节点。
- 界面只有中文；代码元素名按原文显示，不做源码分析或依赖边推断。

## 存储

- `data/projects/<id>.json`：项目元信息、Git 信息、任务状态，不重复存放完整摘要。
- `data/uploads/<id>.zip`：上传归档。
- `SHARED_PROJECT_ROOT/<id>/source/`：实际项目文件和 Git 仓库。
- `SHARED_PROJECT_ROOT/<id>/summary.json`：唯一正式摘要，使用原子替换写入。

使用文件存储和进程内锁，只运行一个后端实例，不使用数据库或消息队列。

## 主接口

统一前缀 `/api`，接口文档由 `/docs` 提供。

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | /health | 主后端健康检查 |
| GET | /integrations/reposummary | 总结服务健康检查 |
| GET / POST | /projects | 项目列表 / Git 导入 |
| POST | /projects/git/check | 验证仓库及链接中的分支或标签 |
| POST | /projects/upload | 文件夹上传 |
| GET / DELETE | /projects/{id} | 项目详情 / 删除项目 |
| GET | /projects/{id}/archive | 凭证下载上传归档 |
| POST | /projects/{id}/summary | 提交首次或再次总结 |
| GET | /projects/{id}/summary/status | 查询服务返回的阶段和进度 |
| GET | /projects/{id}/summary | 读取共享摘要文件 |

功能编辑使用项目内固定版 Codex，后台线程运行 `codex exec --json`，通过 SSE 转发执行输出。
- `POST /projects/{id}/edits`：提交 add / modify / delete，记录 Git 起点并组织 Prompt，直接在项目工作区执行。
- `GET /agent/models`：读取模型配置及副本安装/认证配置状态。
- `GET /projects/{id}/agent/runs/{runId}`、`.../events`：任务快照、支持事件 ID 续传的 SSE。
- `GET /projects/{id}/agent/progress`：执行状态，不虚构百分比。
- `GET /projects/{id}/agent/runs/{runId}/graph`：未提交修改文件节点。
- `GET / PUT /projects/{id}/diff`：读取、修改项目工作区文件内容。
- `POST /projects/{id}/git/operations`：文件暂存、取消暂存、恢复、整体提交或放弃回退。

`data/agent-runs/` 保存任务起点、Prompt、JSONL 日志和普通文本结果（不再创建候选工作区）；`data/codex-home/` 保存原生 `config.toml`、可选的 `<名称>.config.toml` 和独立登录状态。模型下拉框通过 Codex 的 `model/list` 读取，`config.toml` 指定默认模型；不再维护 `.env` 模型列表，也不要求每个模型创建一个 profile。
正式源码和 summary.json 只在用户确认后更新；Git 仅作本地版本管理，不推送远程。
错误返回 `{error:{code,message}}`；参数校验失败返回 422。

## 运行

`dev.sh` 从根目录 `.env` 读取服务配置并管理三个用户级 systemd 服务。
前端代理 `/api`、`/docs` 和 `/openapi.json` 到主后端，开启热更新；主后端开启代码重载。
当前不使用静态构建目录。仅适用于可信环境中的单实例开发，不提供账号和权限管理。
