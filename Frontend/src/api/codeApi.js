import {http, projectPath, unavailable} from './client';
const diff = () => http.get(`${projectPath()}/diff`).then(response => response.data);
const codeApi = {
    getSourceFile: path => http.get(`${projectPath()}/files/content`, {params: {path}}).then(response => response.data),
    getDeleteDiffByClass: diff,
    getContextByClass: () => unavailable('源码内容查看'),
    getCandidateDiff: diff,
    getManualCandidate: diff,
    updateCandidateDiff: () => unavailable('代码编辑'),
};
export default codeApi;
