# RepoSummary 服务

- `GET /health`：服务可用性。
- `POST /summary`：接收 projectId 和可选 hints，返回 runId。
- `GET /summary/{runId}`：返回阶段、状态和进度。
- 摘要写入 `SHARED_PROJECT_ROOT/<projectId>/summary.json`，主系统直接读取。

根目录 `./dev.sh restart` 启动。主系统上传后自动提交，也可在项目设置中重新生成。
服务接口与文件格式见现有接口约定。任务状态暂存在内存，重启会中断未完成任务；已生成文件保留。
