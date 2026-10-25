import {http, projectPath, unavailable} from './client';
const diff = () => http.get(`${projectPath()}/diff`).then(response => response.data);
const codeApi = {
    getDeleteDiffByClass: diff,
    getContextByClass: () => unavailable('源码内容查看'),
    getCandidateDiff: diff,
    getManualCandidate: diff,
    updateCandidateDiff: () => unavailable('代码编辑'),
};
export default codeApi;
