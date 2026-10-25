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
```

## 配置

`.env` 只保留当前使用的四项：

| 配置 | 作用 | 生效方式 |
|---|---|---|
| REPOSUMMARY_HTTP_URL | 主后端连接的总结服务地址 | `./dev.sh restart` |
| SHARED_PROJECT_ROOT | 双方共享的项目根目录 | `./dev.sh restart` |
| GITHUB_PROXY_URL | GitHub 代理地址；空值或不配置则直连 | 下次 Git 请求 |
| GIT_CLONE_TIMEOUT_SECONDS | Git 下载和版本切换的超时秒数 | 下次 Git 请求 |

`dev.sh` 读取 `.env` 中的服务配置，shell 环境变量可覆盖它；不会执行 `.env` 内容。
`.env.example` 为配置样例。不要提交私有 `.env` 或运行数据。

## 项目导入

- 文件夹上传保留文件与目录结构，最多 2000 个文件、100 MiB。
- 没有 `.git` 时初始化 `main`；有完整有效的 `.git` 时保留历史、分支和远程配置。
- 上传后追加“导入项目起点”提交，遵循 `.gitignore`，不推送远程。
- Git 导入支持仓库、GitHub 分支和标签链接；Commit ID 单独选填。
- GitHub 有代理配置时走代理，代理失败时报错，不静默直连。
- 导入成功后自动调用 RepoSummary；项目设置中可重新总结。

## 目录

```text
Backend/      主后端：项目、上传、Git、任务、共享文件和接口
Frontend/     中文界面、组件和 API 适配层
PyBackend/    RepoSummary 服务
 data/        项目管理记录及上传归档，不提交 Git
 docs/        架构和 RepoSummary 接口约定
 dev.sh       本机服务管理入口
```

共享目录位于 `SHARED_PROJECT_ROOT/<projectId>/`：
- `source/`：项目文件及 Git 仓库。
- `summary.json`：唯一的正式功能摘要。

主系统读取摘要展示 Epic / Feature；中央图谱按文件显示相关代码元素，暂不绘制边。
功能编辑、智能体代码生成、Diff 和 Git 写操作的业务接口仍待实现。
删除项目会删除其本地管理记录、上传归档和共享项目目录，不删除远端 Git 仓库。

详见 [架构说明](docs/ARCHITECTURE.md) 和 [RepoSummary 接口约定](docs/REPOSUMMARY_API.md)。
