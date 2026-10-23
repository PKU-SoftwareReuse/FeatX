# RepoSummary HTTP 空壳

Python >= 3.10，只有标准库。仅提供 `/health` 和三个 `/v1/analyses` 接口；
旧的摘要任务、图检索、缓存和代码辅助接口已全部移除。

```bash
PYTHONPATH=PyBackend/src REPOSUMMARY_HTTP_HOST=127.0.0.1 REPOSUMMARY_HTTP_PORT=18091 \
  python3 -m featx_pybackend.api.server
```

健康检查：200，`businessReady:false`。业务接口：501 `NOT_IMPLEMENTED`。
不创建任务、不分析代码、不访问文件/数据库、不加载模型、不生成演示图。

启动新应用与空壳：项目根目录 `./dev.sh up`。
同门实现见 [完整接口契约](../docs/REPOSUMMARY_API.md)。
