import {
    BASE_URL,
    getSelectedRepositoryId,
    http,
    setSelectedRepositoryId,
} from "./client";

const projectApi = {
    getSelectedRepositoryId,
    testConnect: () => http.get(`${BASE_URL}/connect/test`),
    uploadProject: (projectData) => http.post(`${BASE_URL}/project/upload`, projectData),
    getProjectsInfo: () => http.get(`${BASE_URL}/project/getList`)
        .then((response) => response.data),
    postProjectPath: (repoId) => http.post(`${BASE_URL}/project/select`, {repoId})
        .then((response) => {
            setSelectedRepositoryId(repoId);
            return response;
        }),
    getCurrentProject: () => http.get(`${BASE_URL}/project/current`)
        .then((response) => response.data),
    postResummary: (repoId) => http.post(`${BASE_URL}/project/resummary`, {repoId}),
    getSummaryProgressAll: () => http.get(`${BASE_URL}/project/summary/progress/all`)
        .then((response) => response.data),
    postDropRepo: (repoId) => http.post(`${BASE_URL}/project/drop`, {repoId})
        .then((response) => {
            if (String(getSelectedRepositoryId()) === String(repoId)) {
                setSelectedRepositoryId(null);
            }
            return response;
        }),
    updateProject: (projectId, projectData) => http.put(`${BASE_URL}/project/${projectId}`, projectData)
        .then((response) => response.data),
    gitDownRepo: (gitName, commitId) => http.post(`${BASE_URL}/project/gitdown`, {
        gitRepoName: gitName,
        commitId,
    }).then((response) => response.data),
    gitClear: (gitName, commitId) => http.post(`${BASE_URL}/project/gitclear`, {
        gitRepoName: gitName,
        commitId,
    }),
    gitRepo: (gitName, commitId, folderName) => http.post(`${BASE_URL}/project/gitrepo`, {
        gitRepoName: gitName,
        commitId,
        repoName: folderName,
    }).then((response) => response.data),
};

export default projectApi;
