# FeatX · 轻量开发框架

通过编辑功能特征来编辑软件。本分支已改为语言无关的 Python 主后端：
不使用数据库、静态分析、模型或 Java 工具链，图的唯一来源是独立 RepoSummary 服务。

## 本机开发（不使用 Docker）

```bash
./dev.sh up       # 启动
./dev.sh restart  # 重启
./dev.sh down     # 停止
./dev.sh logs     # 查看日志
```

访问 **http://服务器IP:3010**。React 开发服务器支持前端热更新，
Python 主后端开启自动重载；修改 RepoSummary 空壳后执行 restart。
API 文档仍在 `/docs`。API 和 RepoSummary 分别只监听本机 13010、18091，前端统一代理。

进程由当前用户的 systemd 管理，关闭终端不会停止；没有配置开机自启。
已有 `data/` 原地使用。旧 Docker 容器和数据卷不动，新 featx-quick 容器已移除。
这是无鉴权的开发服务，不要作为公网生产环境使用。

首次在新机器准备依赖：
```bash
python3 -m venv Backend/.venv
Backend/.venv/bin/pip install -r Backend/requirements.txt
(cd Frontend && npm ci)
```

## 当前能做什么

- 登记 Git URL 和可选 revision；也支持原来的文件夹上传（不限语言，保留目录结构）。
- 上传文件保存至 `data/uploads/`，最多 2000 个文件、100 MiB；删除上传项目会一并删除归档。
- 用 JSON 文件保存项目、远端任务关联和已完成分析结果，重启仍可读取。
- 通过 HTTP 提交分析、轮询任务、取回结果，展示特性列表和节点/边图。
- 图展示不限制语言；实际分析支持哪些语言由 RepoSummary 决定。
- 编辑、Agent、Diff、Git 仅有未实现接口，原入口提示未实现或保持禁用，不执行写操作。

**附带的 RepoSummary 只是 HTTP 空壳。** 健康接口返回 `businessReady:false`，
业务接口返回 501；因此默认界面显示待接入，不会产生任务、分析结果或示例图。
真实闭环需要同门实现 [RepoSummary 接口契约](docs/REPOSUMMARY_API.md)。

## 架构与目录

```text
Frontend/    React + Ant Design + vis-network，项目页与工作台
Backend/     FastAPI + Uvicorn，项目/分析/图/扩展接口，JSON 文件存储
PyBackend/   独立 RepoSummary HTTP 空壳，无第三方运行依赖
data/        运行时生成 projects/<id>.json，Git 忽略
dev.sh       本机 systemd 开发服务入口
```

本机开发服务器在 3010 提供前端，并把 `/api/v1` 和 `/docs` 代理到 Python 后端。不启动数据库、消息队列、分析工具或模型。
旧 `datasets/`、`models/`、`repos/` 已删除；旧容器和数据卷保留不动。
`.run/featx-host-nginx.conf` 仍保留给旧容器使用，不能因新框架不使用就直接删除。

## 接入真实 RepoSummary

设置服务地址后重启：
```bash
REPOSUMMARY_HTTP_URL=http://your-service:8091 ./dev.sh restart
```
外部服务需要下载上传归档时，同时设置 `FEATX_DOWNLOAD_BASE_URL` 为它可达的主应用地址，
例如 `http://本机IP:3010`。服务根地址不带 `/v1`。

## 边界

- 单实例、单 worker、本地 demo；使用进程内锁及原子 JSON 替换，不支持多进程/多副本共享写入。
- GET 分析状态会轮询远端并保存新状态，没有后台队列；关闭页面不会取消远端任务。
- 删除项目会删除登记、结果和自有上传归档；不删除外部 Git 仓库、不取消远端任务。
- 提交超时结果可能不确定，不自动重试；应先核对远端任务后再重新提交。
- 新接口不兼容旧 Java API，也不读取共享 CSV、旧数据库或模型缓存。

最小冒烟：`PYTHONPATH=Backend Backend/.venv/bin/python -m unittest discover -s Backend/tests -v`。

详见 [架构与主 API](docs/ARCHITECTURE.md) 和 [RepoSummary 交接文档](docs/REPOSUMMARY_API.md)。
