# RepoSummary 接口约定

项目上传后提交总结任务；服务通过共享文件保存摘要，通过 HTTP 提供任务状态。
摘要保留字符串形式的代码元素列表，任务接口采用异步提交和状态查询。接口字段保持稳定，服务内部实现独立于展示层。
接口不使用版本前缀；实现时移除旧版本路径，不保留兼容入口。

## 1. 分工和文件

主系统负责分配 projectId、文件夹上传、Git clone/checkout、项目管理、展示及摘要编辑。
RepoSummary 负责从共享目录读取源码、执行总结、保存摘要 JSON，并完整提供任务状态与阶段进度。
主系统直接读取共享文件，不通过 RepoSummary HTTP 下载结果。
双方配置 SHARED_PROJECT_ROOT，实际访问同一份共享文件（挂载路径可以不同）：

共享目录 `/home/lixutian/Projects_SSD/FeatX-shared`

```text
SHARED_PROJECT_ROOT/<projectId>/
  source/        # 主系统准备好的项目源码
  summary.json   # 项目唯一的正式摘要文件
```

源码准备完成才可提交任务。主系统不解析源码，不根据代码元素名称反查文件。
项目元信息、当前 runId 和生成参数由主系统管理，不重复保存完整摘要。

## 2. 摘要格式与文件节点

```json
{
  "projectId": "7c9ec08e3f8e4b61a9f0bcce5a13d762",
  "runId": "summary_20261023_001",
  "description": "一个支持用户身份认证、权限管理与业务操作的管理系统。",
  "epics": [
    {
      "id": "epic_auth",
      "description": "用户身份与访问管理",
      "features": [
        {
          "id": "feature_login",
          "description": "用户登录并建立会话",
          "codeElements": [
            "server/auth.py::AuthService.login",
            "server/auth.py::checkPassword",
            "web/api/session.ts::createSession"
          ]
        }
      ]
    }
  ]
}
```

- projectId 由主系统分配；runId 由 RepoSummary 为每次总结分配，两者不得混用。
- 顶层 description 是项目整体简介：由 RepoSummary 根据源码生成的一句中文纯文本，概括项目用途和核心能力，建议 30～100 字；不要返回 Markdown、任务状态或技术栈清单。新生成结果应提供非空字符串。
- 主系统读取 description 并展示在 Welcome 项目卡片；不从 Epic 描述拼接推导真实项目简介。为兼容已有文件，缺失或空字符串仍可读取，页面显示“暂无项目简介”；null 或非字符串不合法。重新总结时 description 与 epics 在同一份文件中一起原子替换。
- Epic.id 在项目内唯一；Feature.id 在整个项目内唯一。用户修改描述时保留 ID。
- Epic.description 是主题描述，features 是内容；Feature.description 是功能描述，codeElements 是内容。
- codeElements 保留为非空字符串数组，每项表达“文件路径 + 代码元素名称”。
- 不改为 filePath/name 对象，不要求同门按文件分组，也不要求返回 nodes/edges。
- 文件路径相对 source/；代码元素名称不限定语言、类型、命名或方法签名规则。
- 示例采用 :: 拼接；实际字符串拆分由主系统展示适配层负责，与同门输出约定对齐。
  若沿用 ::，应只按第一个分隔符切开，保留元素名内部可能存在的 ::，不解析名称本身。
- 同一 Feature 内不重复保存完全相同的字符串；不同 Feature 可关联同一代码元素。
- 数组允许为空，用于空结果或用户尚未关联代码的功能项；空结果不代替失败状态。

返回格式与展示模型分离。主系统自行拆分字符串并按文件归类：每个文件显示一个节点，
节点内列出当前 Feature 对应的代码元素名称。只处理返回文本，不重新读取或分析源码。
当前只展示文件节点，不画边，也不增加 Feature 中心节点。文件分组不回写为新的摘要格式。

## 3. 启发参数

参数放在提交请求的 hints 中，不混入文件路径或项目 ID：

```json
{
  "projectId": "7c9ec08e3f8e4b61a9f0bcce5a13d762",
  "hints": {
    "targetEpicCount": 6,
    "targetFeaturesPerEpic": 4,
    "instructions": "按用户可感知的业务能力划分，不要按目录机械分组。"
  }
}
```

hints 可省略，各字段也可省略或为 null，表示由 RepoSummary 自行决定。
两个数量字段为正整数，是软目标，不是结果必须满足的硬校验。
instructions 为可选文字引导。初版 targetFeaturesPerEpic 是所有主题的统一参考数量，
不引入按主题分配数量的复杂参数。需要严格数量约束时再明确约定。
RepoSummary 必须实际使用约定的提示；不支持的参数不能默默当成已经生效。

## 4. HTTP 接口

### GET /health

```json
{"status":"ready","businessReady":false}
```

只报告服务和业务可用性，不代替某个项目的任务状态。

### POST /summary — 首次总结或重新总结

请求即上述 projectId + 可选 hints。RepoSummary 快速创建任务，返回 HTTP 202：

```json
{
  "projectId":"7c9ec08e3f8e4b61a9f0bcce5a13d762",
  "runId":"summary_20261023_001",
  "status":"queued"
}
```

这不是完成结果。后台任务随后读取源码、生成摘要、保存结果。
每次用户主动重新总结都调用同一个接口，获得新的 runId，可使用新的 hints。
不单设 resummary 接口。同一项目有 queued/running 任务时返回 409，并给出正在执行的 runId，
不得并发覆盖同一项目。主系统保存返回的 runId 和提交参数。
提交超时不自动重试，以免重复触发总结。

### GET /summary/ — 查询完整状态与阶段进度

```json
{
  "projectId": "7c9ec08e3f8e4b61a9f0bcce5a13d762",
  "runId": "summary_20261023_001",
  "status": "running",
  "totalStages": 3,
  "currentStageIndex": 2,
  "overallPercent": null,
  "elapsedMs": 120000,
  "stages": [
    {
      "id": "read_project",
      "name": "读取项目",
      "status": "complete",
      "message": "项目读取完成",
      "progress": {"percent": 100, "completed": 12, "total": 12, "unit": "file"}
    },
    {
      "id": "generate_summary",
      "name": "生成功能摘要",
      "status": "running",
      "message": "已完成 4 个功能项，共 10 个",
      "progress": {"percent": 40, "completed": 4, "total": 10, "unit": "feature"}
    },
    {
      "id": "save_result",
      "name": "保存摘要文件",
      "status": "pending",
      "message": "等待前序阶段完成",
      "progress": null
    }
  ],
  "error": null
}
```

以上阶段仅为示例。阶段数量、顺序、标识、名称、状态、说明、进度和耗时都由 RepoSummary 提供。
主系统不预设三个或四个阶段，也不把示例阶段名硬编码成流程。

- 整体 status：queued、running、complete、failed。
- totalStages：同门提供的总阶段数，计划确定后必须等于 stages 数组长度。
- stages：有序的完整阶段列表，包括尚未开始的阶段。阶段 id 在本次任务内唯一且稳定。
- currentStageIndex：当前阶段的 1 起始序号，对应 stages 中的位置。排队或完成时可为 null；
  失败时可保留失败阶段序号。不能返回超出数组范围的序号。
- 阶段 status：pending、running、complete、failed；name 和 message 是供页面直接展示的文字。
- progress：当前阶段的真实进度。percent 为 0～100 或 null；completed、total、unit 在可量化时提供。
  主系统不自行用数量、阶段序号或 hints 推算百分比。
- overallPercent：整个任务的百分比，只使用同门提供的值；无法可靠量化时为 null，
  不把不同阶段视作等权重而自行计算。
- elapsedMs：同门提供的任务已用毫秒数；尚无法提供时可为 null。
- error：正常时 null，任务失败时返回原因。

任务刚接收、阶段计划尚未确定时，可返回 totalStages:null、currentStageIndex:null、stages:[]。
阶段计划一旦确定，应提供完整列表；若确实需要调整，必须同步返回一致的数量、列表和当前位置。
无法量化的进度返回 null；页面仅显示阶段文字和运行中状态，不能用定时器模拟百分比。
所有状态来自同门的真实执行过程。未知或无法恢复的 runId 返回 404，不伪装为完成。

主系统按保存的 runId 轮询（建议 2 秒一次），到终态停止。
无需结果下载接口、回调接口、WebSocket 或消息队列。

### 完成后读取共享文件

RepoSummary 必须先写完整临时文件、关闭文件，再原子替换项目的 summary.json，
最后才把任务状态置为 complete。complete 表示正式文件已准备好，不只是模型调用结束。
主系统据约定目录自行定位并读取 summary.json，不接受服务返回的任意绝对文件路径。
读取后验证 projectId、runId 和摘要结构；若文件缺失、非法或属于旧任务，报告结果异常，
不能把旧摘要当成这一次成功的结果。

同门不再提供 GET /summary/{runId}/result。主系统自己的 GET /api/projects/{projectId}/summary
仍然保留，它负责把共享文件的内容提供给浏览器，而不是让浏览器直接访问文件系统。

## 5. 重新总结、编辑和结果保存

- projectId 不变；重新总结产生新 runId，旧状态不能冒充新一轮的完成状态。
- 主系统同时区分“项目是否已有有效摘要”和“最新一次总结任务状态”。
- 重新总结时旧摘要仍可查看，但暂停摘要编辑，避免与生成结果互相覆盖。
- 事先提醒重新总结会替换手工编辑内容，不执行自动合并。
- RepoSummary 先写完整临时结果，成功后原子替换 summary.json；失败不覆盖原摘要。
- 主系统在 complete 后直接读取共享 JSON，校验项目 ID、runId、层级、ID 唯一性和字段类型。
- 成功后再标记新摘要可用，解除编辑限制；失败则继续保留旧摘要。
- 打开项目只读取正式摘要，不重新发起总结；不再有从同门下载结果并覆盖文件的操作。
- 用户编辑由主系统原子更新 summary.json，保留生成来源 runId；增删改查只修改摘要，不修改源码。
- 暂不提供取消任务、历史结果列表或版本合并能力。

## 6. 错误返回

```json
{"error":{"code":"SOURCE_NOT_FOUND","message":"项目源码尚未准备好"}}
```

无效 ID/参数 400，源码或任务不存在 404，同项目任务冲突 409，
空壳业务未实现 501，服务内部错误 500。任务执行失败通过状态接口的 failed + error 反馈。
202 只表示接收，不表示成功；不能用空 epics 和 200 冒充总结完成。

## 7. 主系统配套接口

主系统保留 /api，不带版本号：

- POST /api/projects/{projectId}/summary：转交 hints，启动首次/再次总结。
- GET /api/projects/{projectId}/summary/status：转发同门的完整阶段与进度快照，附加本机 hasSummary、summaryRunId。
- GET /api/projects/{projectId}/summary：读取正式摘要。
- POST /api/projects/{projectId}/epics/{epicId}/features：新增功能项。
- GET /api/projects/{projectId}/features/{featureId}：读取功能项。
- PATCH /api/projects/{projectId}/features/{featureId}：修改描述和 codeElements。
- DELETE /api/projects/{projectId}/features/{featureId}：删除功能项，不删除源码。

沿用原页面布局与组件。在现有总结/重新总结操作中提供可选参数，在原进度位置展示任务状态。
左侧读取真实 Epic / Feature 两级结构，中央保持原文件节点样式，只显示节点与关联代码元素。
