import {http, projectPath} from './client';
const git = (operation, path, runId) => http.post(`${projectPath()}/git/operations`, {operation, path, runId}).then(response => response.data);
const gitApi = {
    getGitWorkspaceStatus: () => git('status'),
    stageCandidateFile: (path, runId) => git('stage', path, runId),
    unstageCandidateFile: (path, runId) => git('unstage', path, runId),
    revertCandidateFile: (path, runId) => git('revert', path, runId),
    commitFeatureChanges: (_mode, _message, runId) => git('commit', undefined, runId),
    discardFeatureChanges: () => git('discard'),
};
export default gitApi;
