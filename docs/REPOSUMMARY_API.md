# RepoSummary v1 · 同门接入契约

本契约替代旧的 13 个业务接口。RepoSummary 是唯一分析服务；FeatX 主后端不做静态分析、
模型推理或语言特有处理，也不共享数据库、CSV、源码目录或模型文件。

## 责任与通信

- RepoSummary 接收 Git 来源，自行获取代码、分析，并返回完整的语言无关结果。
- 主后端只登记项目、提交任务、查询状态、校验/保存结果；前端负责图布局和过滤。
- UTF-8 JSON，服务根地址不带 `/v1`。HTTP 调用超时 15 秒；长任务必须快速返回任务 ID。
- 无 SSE 或回调。工作台约每 2.5 秒查询主后端，由主后端代为轮询 RepoSummary。
- 任务/结果需在 RepoSummary 端可重复查询；重启主后端不会丢失已保存的远端任务 ID。
- 当前为可信内部网络，没有鉴权协议。RepoSummary 实现方需自行防范仓库 URL SSRF、
  不可信仓库内容和凭据泄露；分析仓库不能任意执行仓库代码。

## 1. GET /health

返回 200：

```json
{"status":"ready","version":1,"mode":"stub","businessReady":false}
```

`businessReady` 必须为布尔值；真实业务可用时改为 true。HTTP 健康不代表分析可用。
当前壳额外返回空 `models` 字段，不影响兼容。

## 2. POST /v1/analyses

请求：

```json
{"projectId":"local-project-id","source":{"url":"https://example.com/team/repository.git","revision":null}}
```

- projectId 是主后端生成的不透明 ID。
- URL 支持 http、https、ssh://；HTTP URL 不接受内嵌凭据，ssh:// 可含用户名但不能含密码。
- revision 为分支、tag、commit 或 null（默认分支）。不按语言筛选项目。
- 未来支持私有仓库时再约定安全的凭据引用，不能在 URL 中携带 token。

成功返回 202，任务对象：

```json
{"taskId":"remote-task-id","status":"queued","progress":0,"message":"等待分析","error":null}
```

不要返回假的 taskId 或未实际保存的任务。重复提交/幂等策略由未来版本约定；
当前主后端对已知 queued/running 任务拒绝再提交，但提交超时后不知道远端是否已创建，
因此不会自动重试，需要人工核对远端。

## 3. GET /v1/analyses/

返回 200，同上任务结构。字段：

| 字段     | 类型           | 说明                                 |
| -------- | -------------- | ------------------------------------ |
| taskId   | string         | 与提交响应及查询路径一致             |
| status   | string         | queued / running / complete / failed |
| progress | number 或 null | 0～1，可省略                         |
| message  | string         | 可选，人类可读的进度描述             |
| error    | string 或 null | failed 时填写原因                    |

complete 只能在结果接口已可读后返回。主后端将在看到 complete 后调用结果接口，
校验通过才将本地状态与结果一起原子保存；临时连接错误可通过刷新重试。

## 4. GET /v1/analyses//result

返回 200，完整结果。以下仅为**契约示例，不是内置演示数据**：

```json
{
  "version":"1",
  "source":{"url":"https://example.com/team/repository.git","revision":null},
  "language":null,
  "features":[
    {"id":"f1","name":"用户登录","description":"验证用户身份","nodeIds":["n1","n2"]}
  ],
  "graph":{
    "nodes":[
      {"id":"n1","name":"登录入口","type":"entrypoint","location":{"path":"src/auth.ext","startLine":1,"endLine":20},"attributes":{}},
      {"id":"n2","name":"身份校验","type":"component","location":null,"attributes":{}}
    ],
    "edges":[{"source":"n1","target":"n2","type":"depends-on"}]
  }
}
```

约束：

- version 当前固定字符串 `"1"`，source 必须回传原请求 URL/revision；不要用解析后的 commit 替换请求 revision。
- language 可选，仅展示元信息，不用于选择处理逻辑；混合语言项目也使用同一结构。
- features、graph.nodes、graph.edges 必须存在，可以为空。空图是成功空结果，不是失败。
- 特性与节点各自的 id 唯一。边端点和 feature.nodeIds 必须引用已有节点。
- 节点 type、边 type 为任意字符串，不限定类/方法。location 是可选展示信息，主后端不会读取该路径。
- 属性使用 attributes 字典扩展。主后端保留它，不据此推导节点或关系。
- 所有关系必须由 RepoSummary 给出。FeatX 不会补边、执行 AST、调用图或数据流分析。
- 结果来源不匹配、重复 ID、悬空引用等将被主后端拒绝，任务标为失败，不展示部分错误结果。

## 5. 错误与空壳

错误统一使用非 2xx 与结构：

```json
{"error":{"code":"NOT_IMPLEMENTED","message":"RepoSummary business logic is not implemented"}}
```

当前三个业务接口均返回 501，不保存任何任务。主后端转换为
`REPOSUMMARY_NOT_IMPLEMENTED`，前端显示待接入。
连接错误显示 `REPOSUMMARY_UNREACHABLE`；其他远端 HTTP 错误显示 `REPOSUMMARY_ERROR`；
非法结果显示 `INVALID_REPOSUMMARY_RESULT`。这些错误不会被画成成功空图。
未知任务在真实实现中应返回 404；不存在的路由在空壳中返回 404。

## 6. 实现与启动位置

现有入口：`PyBackend/src/featx_pybackend/api/server.py`。可替换内部实现或整个独立服务，
只需遵守本契约，不必沿用 Python 标准库服务框架。

```bash
PYTHONPATH=PyBackend/src REPOSUMMARY_HTTP_HOST=127.0.0.1 REPOSUMMARY_HTTP_PORT=18091 \
  python3 -m featx_pybackend.api.server
```

主后端使用 `REPOSUMMARY_HTTP_URL`，可通过 `./dev.sh restart` 应用 shell 环境变量。
服务地址必须从主后端运行位置可达。没有共享存储或数据库接入要求。

## 上传项目（新增）

原文件夹上传入口保留，主 API `POST /api/v1/projects/upload` 接收 multipart：
`folderName`，成对重复的 `files` 和 `paths`（项目相对路径）。不限语言，最多 2000 个文件、100 MiB，
需 Content-Length。原始文件打包保存在 `data/uploads/<projectId>.zip`，不分析、不执行、不解压。
删除上传项目时一并删除该归档；Git 项目仍只删除登记。

上传项目的分析请求使用如下 source（替代 Git 的 url/revision）：

```json
{"kind":"upload","uploadId":"32位项目ID","filename":"project.zip","downloadUrl":"http://127.0.0.1:13010/api/v1/projects/项目ID/archive?token=下载凭证"}
```

RepoSummary 通过 downloadUrl 下载归档，安全解压并分析；结果 source 回传 kind/uploadId/filename。
下载凭证仅在服务间请求中提供，不出现在项目列表或详情；主后端校验后提供 ZIP。
本机 RepoSummary 默认即可下载。外部服务接入时，设置 `FEATX_DOWNLOAD_BASE_URL`
为它可达的主应用地址，例如 `http://本机IP:3010`。
