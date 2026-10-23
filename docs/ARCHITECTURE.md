# FeatX 新框架

## 数据流

```text
React → /api/v1 → FastAPI → HTTP RepoSummary
                      ↓
               data/projects/<id>.json
```

一个项目文件保存元信息、最近一次远端任务和该任务的结果。文件使用同目录临时文件、
fsync、os.replace 原子替换；进程内 RLock 防止单进程并发写冲突。只运行一个 Uvicorn worker。
不依赖数据库，不自动迁移旧数据。网络请求也在受锁保护的操作内完成，面向小规模本地 demo，
不是高并发服务器；存储或任务队列扩展留待后续。

## 模块

- projects：登记、列表、详情、删除登记；项目 ID 显式传入，不存在全局“当前项目”。
- analyses：提交远端任务、查询状态、保存完成结果。没有后台 worker、计划任务或隐式重试。
- graphs：读取原始图、特性和完整结果，不执行任何分析。过滤只在前端发生。
- reposummary：唯一外部分析客户端，只依赖 HTTP 契约。
- operations：编辑、Agent、Diff、Git 未实现入口，始终 501。
- store：JSON 文件存储；models：语言无关结果结构及引用完整性校验。

## 主 API

API 根为 `/api/v1`，可交互说明由 `/docs` 提供，OpenAPI 位于 `/openapi.json`。

| 方法 | 路径 | 行为 |
|---|---|---|
| GET | /health | 主后端在线，不要求 RepoSummary 可用 |
| GET | /integrations/reposummary | 查询真实依赖状态，含 businessReady |
| GET / POST | /projects | 列表 / 登记 `{name,source:{url,revision}}` |
| GET / DELETE | /projects/{id} | 详情 / 删除登记、结果及自有上传归档，不动外部 Git 仓库或远端任务 |
| POST | /projects/{id}/analyses | 向 RepoSummary 提交，202 返回任务 |
| GET | /projects/{id}/analysis | 轮询并保存状态，无任务时 not_started |
| GET | /projects/{id}/result | 完整结果，未完成返回 409 |
| GET | /projects/{id}/graph | 原始 nodes/edges，未完成返回 409 |
| GET | /projects/{id}/features | 特性列表，未完成返回 409 |
| POST | /projects/{id}/edits | 501，占位 |
| POST | /projects/{id}/agent/runs | 501，占位 |
| GET | /projects/{id}/diff | 501，占位 |
| POST | /projects/{id}/git/operations | 501，占位 |

错误 envelope 为 `{error:{code,message}}`；输入字段错误由 FastAPI 返回 422 detail。
成功项目响应不包含体积较大的 result，结果通过单独接口读取。
提交成功后清除上一轮结果，避免把旧图误认为新结果；提交失败则保留原记录。
远端 complete 但结果获取暂时失败时可刷新重试；结果校验失败则保存 failed 状态。

## 前端与运行

保留原 React/Ant Design 页面、组件和 CSS；API 适配层将新结果转换成旧组件需要的结构。
选择项目保存在浏览器 sessionStorage，发给后端的请求始终显式携带项目 ID。
未实现功能保留原入口并提示，或保持不可用状态。

`dev.sh` 管理三个用户级 systemd 服务：前端 3010，主 API 127.0.0.1:13010，
RepoSummary 127.0.0.1:18091。前端热更新，主后端自动重载；不使用数据库或容器。
前端代理 API 和接口文档；`Frontend/build` 不参与当前开发服务。
旧容器和数据卷独立保留，`.run/featx-host-nginx.conf` 仍被旧前端容器挂载。
这是无认证的本地开发服务，不适合直接公网或多租户部署。
