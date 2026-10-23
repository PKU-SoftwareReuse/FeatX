# FeatX 前端

保留原 React 页面、样式和组件；`src/api/` 负责适配新 Python 后端的数据与接口。

在项目根目录运行 `./dev.sh up`，访问 3010。前端支持热更新，
`src/setupProxy.js` 将 `/api`、`/docs`、`/openapi.json` 代理到本机 13010。

- `src/`：页面、组件、图展示、API 适配与中英文文本。
- `public/`：HTML 模板、图标等静态资源。
- `package.json` / `package-lock.json`：依赖和版本锁。
- `node_modules/`：已安装依赖，不提交 Git。
- `build/`：`npm run build` 的静态产物，当前开发服务不使用。

独立构建：`npm run build`。本机统一启停请使用根目录 dev.sh，不要另起默认 3000 服务。
