import axios from 'axios';
import {message, unstableSetRender} from 'antd';
import {createRoot} from 'react-dom/client';

// Keep the original Ant Design feedback components working with this React runtime.
const feedbackRoots = new WeakMap();
unstableSetRender((content, host) => {
    const renderer = feedbackRoots.get(host) || createRoot(host);
    feedbackRoots.set(host, renderer);
    renderer.render(content);
    return async () => {
        await new Promise(resolve => setTimeout(resolve, 0));
        renderer.unmount();
        feedbackRoots.delete(host);
    };
});

export const BASE_URL = process.env.REACT_APP_API_BASE_URL || '/api';
const REPOSITORY_ID_KEY = 'featx.quick.repositoryId';
export const getSelectedRepositoryId = () => {
    try {return sessionStorage.getItem(REPOSITORY_ID_KEY);} catch (_) {return null;}
};
export const setSelectedRepositoryId = id => {
    try {
        if (id == null) sessionStorage.removeItem(REPOSITORY_ID_KEY);
        else sessionStorage.setItem(REPOSITORY_ID_KEY, String(id));
    } catch (_) { /* Selection is local to the browser, never server-global. */ }
};
export const http = axios.create({baseURL: BASE_URL, timeout: 20000});
http.interceptors.response.use(response => response, error => {
    const data = error.response?.data;
    const detail = data?.error?.message || data?.message
        || (Array.isArray(data?.detail) ? data.detail.map(item => String(item.msg).replace(/^Value error, /, '')).join('; ') : data?.detail);
    error.message = typeof detail === 'string' ? detail
        : '主后端不可达或请求超时';
    if (data && typeof data === 'object') data.message = error.message;
    return Promise.reject(error);
});
export const unavailableError = (label = '该功能') => {
    const detail = `${label}暂不可用。`;
    const error = new Error(detail);
    error.response = {status: 501, data: {code: 'NOT_IMPLEMENTED', message: detail}};
    return error;
};
export const unavailable = label => Promise.reject(unavailableError(label));
export const notifyUnavailable = label => message.info(unavailableError(label).message);
export const projectPath = id => {
    const selected = id ?? getSelectedRepositoryId();
    if (!selected) throw new Error('请先选择项目');
    return `/projects/${encodeURIComponent(selected)}`;
};
