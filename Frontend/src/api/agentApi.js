import {http, projectPath, BASE_URL} from './client';
const agentApi = {
    getLlmModels: () => http.get('/agent/models').then(response => response.data),
    getLlmResponse: runId => new EventSource(`${BASE_URL}${projectPath()}/agent/runs/${encodeURIComponent(runId)}/events`),
    getAgentRun: runId => http.get(`${projectPath()}/agent/runs/${encodeURIComponent(runId)}`).then(response => response.data),
    getAgentActivities: runId => http.get(`${projectPath()}/agent/runs/${encodeURIComponent(runId)}/activities`).then(response => response.data),
    getAgentLogs: (runId, cursor = 0) => http.get(`${projectPath()}/agent/runs/${encodeURIComponent(runId)}/logs`, {params:{cursor}}).then(response => response.data),
    getLlmProgress: () => http.get(`${projectPath()}/agent/progress`).then(response => response.data),
    // Codex supplies execution events, not the legacy multi-stage reasoning graphs.
    getFocusGraphStages: () => Promise.resolve([]),
};
export default agentApi;
