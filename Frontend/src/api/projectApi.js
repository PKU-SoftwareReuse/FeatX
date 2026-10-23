import {http, getSelectedRepositoryId, setSelectedRepositoryId, projectPath, unavailable, text, notifyUnavailable} from './client';
import {projectView, progressView} from './viewModels';

const data = promise => promise.then(response => response.data);
const statusErrors = new Map();
export function normalizeGitUrl(value) {
    let url = value.trim();
    if (/^[\w.-]+\/[\w.-]+$/.test(url)) url = `https://github.com/${url}`;
    else if (/^[\w.-]+@[^/:]+:.+$/.test(url)) url = `ssh://${url.replace(':', '/')}`;
    const parsed = new URL(url);
    if (!['https:', 'http:', 'ssh:'].includes(parsed.protocol) || !parsed.hostname || parsed.password
        || (parsed.protocol !== 'ssh:' && parsed.username)) {
        throw new Error(text('请填写不含密码或令牌的 Git URL', 'Use a Git URL without credentials'));
    }
    return url;
}
const projectApi = {
    capabilities: {edits: false, agent: false, diff: false, git: false, upload: true},
    notifyUnavailable,
    getSelectedRepositoryId,
    testConnect: () => http.get('/health'),
    getProjectsInfo: async () => (await data(http.get('/projects'))).map(projectView),
    postProjectPath: async id => {
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
    postResummary: async id => {
        try {
            const task = await data(http.post(`${projectPath(id)}/analyses`));
            statusErrors.delete(String(id));
            return task;
        } catch (error) {statusErrors.set(String(id), error.message); throw error;}
    },
    getSummaryProgressAll: async () => {
        const projects = await data(http.get('/projects'));
        return Object.fromEntries(await Promise.all(projects.map(async project => {
            try {
                // Poll real active jobs only. A bare registration does not create a remote job.
                const task = project.analysis ? await data(http.get(`${projectPath(project.id)}/analysis`))
                    : {status: 'not_started', message: statusErrors.get(project.id)};
                return [project.id, progressView(task)];
            } catch (error) {return [project.id, progressView({status: 'failed', error: error.message})];}
        })));
    },
    postDropRepo: async id => {
        await http.delete(projectPath(id));
        statusErrors.delete(String(id));
        if (String(getSelectedRepositoryId()) === String(id)) setSelectedRepositoryId(null);
    },
    updateProject: () => unavailable(text('项目设置修改', 'Project settings editing')),
    uploadProject: form => http.post('/projects/upload', form, {timeout: 120000}).then(response => projectView(response.data)),
    gitDownRepo: async gitName => {
        // Address validation only, not a clone/preview. The original dialog says so explicitly.
        const url = normalizeGitUrl(gitName);
        return {projectType: text('仅登记', 'Registration only'), repoName: new URL(url).pathname.split('/').filter(Boolean).pop()?.replace(/\.git$/, '') || 'Repository', paths: []};
    },
    gitClear: () => Promise.resolve(), // No clone was created, so there is nothing to remove.
    gitRepo: async (gitName, revision, name) => projectView(await data(http.post('/projects', {
        name, source: {url: normalizeGitUrl(gitName), revision: revision.trim() || null},
    }))),
};
export default projectApi;
