import {http, getSelectedRepositoryId, setSelectedRepositoryId, projectPath, notifyUnavailable} from './client';
import {projectView, progressView} from './viewModels';

const data = promise => promise.then(response => response.data);
const statusErrors = new Map();
export function normalizeGitUrl(value) {
    let url = value.trim();
    if (/^[\w.-]+\/[\w.-]+$/.test(url)) url = `https://github.com/${url}`;
    else if (/^[\w.-]+@[^/:]+:.+$/.test(url)) url = `ssh://${url.replace(':', '/')}`;
    let parsed;
    try {parsed = new URL(url);} catch (_) {throw new Error('请输入有效的 Git 仓库地址');}
    if (!['https:', 'http:', 'ssh:'].includes(parsed.protocol) || !parsed.hostname || parsed.password
        || (parsed.protocol !== 'ssh:' && parsed.username)) {
        throw new Error('请填写不含密码或令牌的 Git URL');
    }
    return url;
}
const projectApi = {
    capabilities: {edits: true, agent: true, diff: true, git: true, upload: true},
    notifyUnavailable,
    getSelectedRepositoryId,
    testConnect: () => http.get('/health'),
    getProjectsInfo: async () => (await data(http.get('/projects'))).map(projectView),
    postProjectPath: async id => {
        const task = await data(http.get(`${projectPath(id)}/summary/status`));
        const running = ['queued', 'running'].includes(task.status);
        if (running || !task.hasSummary) {
            const detail = running ? '项目正在总结，请等待完成后再打开。' : '项目尚无可用摘要，请先重新生成。';
            const error = new Error(detail);
            error.response = {status:409, data:{message:detail, error:{code:running ? 'SUMMARY_RUNNING' : 'RESULT_NOT_READY', message:detail}}};
            throw error;
        }
        const project = await data(http.get(projectPath(id)));
        setSelectedRepositoryId(project.id);
        return {data: projectView(project)};
    },
    getCurrentProject: async () => {
        const id = getSelectedRepositoryId();
        if (!id) return null;
        try {return projectView(await data(http.get(projectPath(id))));}
        catch (error) {if (error.response?.status === 404) setSelectedRepositoryId(null); throw error;}
    },
    postResummary: async (id, hints = {}) => {
        try {
            const task = await data(http.post(`${projectPath(id)}/summary`, {hints}));
            statusErrors.delete(String(id));
            return task;
        } catch (error) {statusErrors.set(String(id), error.message); throw error;}
    },
    getSummaryProgressAll: async () => {
        const projects = await data(http.get('/projects'));
        return Object.fromEntries(await Promise.all(projects.map(async project => {
            try {
                // Poll real active jobs only. A bare registration does not create a remote job.
                const task = project.analysis ? await data(http.get(`${projectPath(project.id)}/summary/status`))
                    : {status: 'not_started', message: statusErrors.get(project.id) || project.summaryError};
                return [project.id, progressView(task)];
            } catch (error) {return [project.id, progressView({status: 'failed', error: error.message})];}
        })));
    },
    postDropRepo: async id => {
        await http.delete(projectPath(id));
        statusErrors.delete(String(id));
        if (String(getSelectedRepositoryId()) === String(id)) setSelectedRepositoryId(null);
    },
    updateProject: async (id, values) => projectView(await data(http.patch(projectPath(id), {name:values.projectName,icon:values.icon,tags:values.tags}))),
    uploadProject: (form, onUploadProgress) => http.post('/projects/upload', form, {timeout: 120000, onUploadProgress}).then(response => projectView(response.data)),
    gitDownRepo: async (gitName, commitId) => data(http.post('/projects/git/check', {
        url: normalizeGitUrl(gitName), commitId: commitId.trim() || null,
    }, {timeout: 40000})),
    gitClear: () => Promise.resolve(), // 检查仓库不创建本地副本。
    gitRepo: async (gitName, commitId, name) => projectView(await data(http.post('/projects', {
        name, source: {url: normalizeGitUrl(gitName), commitId: commitId.trim() || null},
    }, {timeout: 600000}))),
};
export default projectApi;
