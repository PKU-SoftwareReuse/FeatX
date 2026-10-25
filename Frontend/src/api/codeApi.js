import {http, projectPath} from './client';
const diff = (path, _mode, runId) => http.get(`${projectPath()}/diff`, {params:{path, runId}}).then(response => response.data);
const codeApi = {
    getSourceFile: path => http.get(`${projectPath()}/files/content`, {params: {path}}).then(response => response.data),
    getDeleteDiffByClass: (path, mode, runId) => diff(path, mode, runId).then(result => result.diff),
    getContextByClass: path => http.get(`${projectPath()}/files/content`, {params:{path}}).then(response => response.data.content),
    getCandidateDiff: diff,
    getManualCandidate: diff,
    updateCandidateDiff: (path, _mode, content, runId) => http.put(`${projectPath()}/diff`, {path, content, runId}).then(response => response.data),
};
export default codeApi;
