const {createProxyMiddleware} = require('http-proxy-middleware');

module.exports = app => {
    app.use(['/api', '/docs', '/openapi.json'], createProxyMiddleware({
        target: process.env.FEATX_DEV_BACKEND_URL || 'http://127.0.0.1:13010',
        changeOrigin: true,
        proxyTimeout: 120000,
    }));
};
