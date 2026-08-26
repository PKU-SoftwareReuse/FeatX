import {BASE_URL, http} from "./client";

const gitApi = {
    getGitWorkspaceStatus: () => http.get(`${BASE_URL}/code/git/status`)
        .then((response) => response.data),
    stageCandidateFile: (key, runId) => http.post(`${BASE_URL}/code/git/stage`, {key, runId})
        .then((response) => response.data),
    unstageCandidateFile: (key, runId) => http.post(`${BASE_URL}/code/git/unstage`, {key, runId})
        .then((response) => response.data),
    revertCandidateFile: (key, runId) => http.post(`${BASE_URL}/code/git/revert`, {key, runId})
        .then((response) => response.data),
    commitFeatureChanges: (operation, commitMessage, runId) => http.post(`${BASE_URL}/code/git/commit`, {
        operation,
        message: commitMessage,
        runId,
    }).then((response) => response.data),
    discardFeatureChanges: () => http.post(`${BASE_URL}/code/git/discard`)
        .then((response) => response.data),
};

export default gitApi;
