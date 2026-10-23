import {http, projectPath, unavailable, text} from './client';
const diff = () => http.get(`${projectPath()}/diff`).then(response => response.data);
const codeApi = {
    getDeleteDiffByClass: diff,
    getContextByClass: () => unavailable(text('源码内容查看', 'Source content viewing')),
    getCandidateDiff: diff,
    getManualCandidate: diff,
    updateCandidateDiff: () => unavailable(text('代码编辑', 'Code editing')),
};
export default codeApi;
