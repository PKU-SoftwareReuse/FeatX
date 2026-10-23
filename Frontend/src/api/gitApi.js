import {http, projectPath} from './client';
const git = operation => http.post(`${projectPath()}/git/operations`, {operation}).then(response => response.data);
const gitApi = {
    getGitWorkspaceStatus: () => git('status'),
    stageCandidateFile: () => git('stage'),
    unstageCandidateFile: () => git('unstage'),
    revertCandidateFile: () => git('revert'),
    commitFeatureChanges: () => git('commit'),
    discardFeatureChanges: () => git('discard'),
};
export default gitApi;
