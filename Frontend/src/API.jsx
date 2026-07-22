import axios from "axios";

// const BASE_URL = "http://101.201.65.66:3000/api";
// const BASE_URL = "/api";
const BASE_URL = process.env.REACT_APP_API_BASE_URL || "http://127.0.0.1:8080";
// const BASE_URL = "http://10.7.1.126:8080";

const WORKSPACE_ID_KEY = "featx.workspaceId";
const REPOSITORY_ID_KEY = "featx.repositoryId";

const readSessionValue = (key) => {
    try {
        return window.sessionStorage.getItem(key);
    } catch (error) {
        return null;
    }
};

const writeSessionValue = (key, value) => {
    try {
        if (value == null || value === "") {
            window.sessionStorage.removeItem(key);
        } else {
            window.sessionStorage.setItem(key, String(value));
        }
    } catch (error) {
        // Requests still work through the backend's legacy workspace when storage is unavailable.
    }
};

const createWorkspaceId = () => {
    if (typeof window.crypto?.randomUUID === "function") {
        return window.crypto.randomUUID();
    }
    return `workspace-${Date.now()}-${Math.random().toString(16).slice(2)}`;
};

const claimWorkspaceId = (storedWorkspaceId) => {
    let claimedWorkspaceId = storedWorkspaceId || createWorkspaceId();
    const documentId = createWorkspaceId();
    try {
        const claimKey = `featx.workspaceClaim.${claimedWorkspaceId}`;
        if (window.localStorage.getItem(claimKey)) {
            claimedWorkspaceId = createWorkspaceId();
        }
        const activeClaimKey = `featx.workspaceClaim.${claimedWorkspaceId}`;
        window.localStorage.setItem(activeClaimKey, documentId);
        window.addEventListener("unload", () => {
            if (window.localStorage.getItem(activeClaimKey) === documentId) {
                window.localStorage.removeItem(activeClaimKey);
            }
        });
    } catch (error) {
        // sessionStorage still isolates normal tabs when shared storage is unavailable.
    }
    return claimedWorkspaceId;
};

const workspaceId = (() => {
    const claimed = claimWorkspaceId(readSessionValue(WORKSPACE_ID_KEY));
    writeSessionValue(WORKSPACE_ID_KEY, claimed);
    return claimed;
})();

const http = axios.create();
http.interceptors.request.use((config) => {
    const repositoryId = readSessionValue(REPOSITORY_ID_KEY);
    config.headers = config.headers || {};
    config.headers["X-FeatX-Workspace-Id"] = workspaceId;
    if (repositoryId) {
        config.headers["X-FeatX-Repo-Id"] = repositoryId;
    }
    return config;
});

const eventSourceQuery = (params) => {
    const query = new URLSearchParams(params);
    query.set("workspaceId", workspaceId);
    const repositoryId = readSessionValue(REPOSITORY_ID_KEY);
    if (repositoryId) query.set("repoId", repositoryId);
    return query;
};

const API = {
    getSelectedRepositoryId: () => readSessionValue(REPOSITORY_ID_KEY),
    testConnect: () => {
        return http.get(`${BASE_URL}/connect/test`);
    },
    uploadProject: (projectData) => {
        return http.post(`${BASE_URL}/project/upload`, projectData)
    },
    getProjectsInfo: () => {
        return http.get(`${BASE_URL}/project/getList`)
            .then(response => response.data);
    },
    postProjectPath: (repoId) => {
        return http.post(`${BASE_URL}/project/select`, {
            repoId: repoId,
        }).then(response => {
            writeSessionValue(REPOSITORY_ID_KEY, repoId);
            return response;
        })
    },
    getCurrentProject: () => {
        return http.get(`${BASE_URL}/project/current`)
            .then(response => response.data);
    },
    postResummary: (repoId) => {
        return http.post(`${BASE_URL}/project/resummary`, {
            repoId: repoId,
        })
    },
    getSummaryProgressAll: () => {
        return http.get(`${BASE_URL}/project/summary/progress/all`)
            .then(response => response.data);
    },
    postDropRepo: (repoId) => {
        return http.post(`${BASE_URL}/project/drop`, {
            repoId: repoId,
        }).then(response => {
            if (String(readSessionValue(REPOSITORY_ID_KEY)) === String(repoId)) {
                writeSessionValue(REPOSITORY_ID_KEY, null);
            }
            return response;
        })
    },
    updateProject: (projectId, projectData) => {
        return http.put(`${BASE_URL}/project/${projectId}`, projectData)
            .then(response => response.data);
    },
    gitDownRepo: (gitName, commitId) => {
        return http.post(`${BASE_URL}/project/gitdown`, {
            gitRepoName: gitName,
            commitId: commitId,
        }).then(response => response.data);
    },
    gitClear: (gitName, commitId) => {
        return http.post(`${BASE_URL}/project/gitclear`, {
            gitRepoName: gitName,
            commitId: commitId,
        });
    },
    gitRepo: (gitName, commitId, folderName) => {
        return http.post(`${BASE_URL}/project/gitrepo`, {
            gitRepoName: gitName,
            commitId: commitId,
            repoName: folderName,
        }).then(response => response.data);
    },
    getMinGraphData: (featureId, runId) => {
        return http.get(`${BASE_URL}/graph/feature/debloatGraph`, {
            params: {
                featureId: featureId,
                runId: runId,
            }
        })
            .then(response => response.data);
    },
    getMaxGraphData: (featureId) => {
        return http.get(`${BASE_URL}/graph/feature/maxGraph`, {
            params: {
                featureId: featureId
            }
        })
            .then(response => response.data);
    },
    getNewGraphData: (runId) => {
        return http.get(`${BASE_URL}/graph/feature/newGraph`, {params: {runId}})
            .then(response => response.data);
    },


    // getTemplateStaticInfo: () => {
    //     return axios.get(`${BASE_URL}/template/static`)
    //         .then(response => response.data);
    // },
    // getCachedTemplateSummary: () => {
    //     return axios.get(`${BASE_URL}/template/cachedSummary`)
    //         .then(response => response.data);
    // },
    // getTemplateSummaryInfo: () => {
    //     return new EventSource(`${BASE_URL}/template/llm`);
    // },
    // getTemplateJsonFile: () => {
    //     return fetch(`${BASE_URL}/template/download/json`);
    // },
    getFeatures: () => {
        return http.get(`${BASE_URL}/feature/get`)
            .then(response => response.data);
    },
    getDeleteDiffByClass: (classId) => {
        return http.get(`${BASE_URL}/code/deleteDiffByClass`, {
            params: {
                classId: classId
            }
        }).then(response => response.data);
    },
    getContextByClass: (classId) => {
        return http.get(`${BASE_URL}/code/contextByClass`, {
            params: {
                classId: classId
            }
        }).then(response => response.data);
    },
    getNewDiffByClass: (classId) => {
        return http.get(`${BASE_URL}/code/newDiffByClass`, {
            params: {
                classId: classId
            }
        }).then(response => response.data);
    },
    getRepositoryDiff: () => {
        return http.get(`${BASE_URL}/code/repositoryDiff`)
            .then(response => response.data);
    },
    getCandidateDiff: (classId, operation, runId) => {
        return http.get(`${BASE_URL}/code/candidateDiff`, {
            params: {classId, operation, runId}
        }).then(response => response.data);
    },
    getManualCandidate: (classId, operation, runId) => {
        return http.get(`${BASE_URL}/code/manualCandidate`, {
            params: {classId, operation, runId}
        }).then(response => response.data);
    },
    updateCandidateDiff: (key, operation, content, runId) => {
        return http.put(`${BASE_URL}/code/candidateDiff`, {
            key,
            operation,
            content,
            runId,
        }).then(response => response.data);
    },
    getGitWorkspaceStatus: () => {
        return http.get(`${BASE_URL}/code/git/status`)
            .then(response => response.data);
    },
    stageCandidateFile: (key, runId) => {
        return http.post(`${BASE_URL}/code/git/stage`, {key, runId})
            .then(response => response.data);
    },
    unstageCandidateFile: (key, runId) => {
        return http.post(`${BASE_URL}/code/git/unstage`, {key, runId})
            .then(response => response.data);
    },
    revertCandidateFile: (key, runId) => {
        return http.post(`${BASE_URL}/code/git/revert`, {key, runId})
            .then(response => response.data);
    },
    commitFeatureChanges: (operation, commitMessage, runId) => {
        return http.post(`${BASE_URL}/code/git/commit`, {
            operation,
            message: commitMessage,
            runId,
        }).then(response => response.data);
    },
    discardFeatureChanges: () => {
        return http.post(`${BASE_URL}/code/git/discard`)
            .then(response => response.data);
    },

    confirmDelete: (runId) => {
        return http.post(`${BASE_URL}/feature/confirm/delete`, null, {params: {runId}})
    },
    deleteFeature: (requestData) => {
        return http.post(`${BASE_URL}/feature/delete`, requestData)
            .then(response => response.data)
    },

    modifyFeature: (featureDescription, language) => {
        return http.post(`${BASE_URL}/feature/modify`, {
            featureDescription: featureDescription,
            language: language,
        }).then(response => response.data)
    },
    confirmModify: (runId) => {
        return http.post(`${BASE_URL}/feature/confirm/modify`, null, {params: {runId}})
            .then(response => response.data);
    },

    addFeature: (requestData) => {
        return http.post(`${BASE_URL}/feature/add`, requestData)
            .then(response => response.data)
    },
    confirmAdd: (runId) => {
        return http.post(`${BASE_URL}/feature/confirm/add`, null, {params: {runId}})
            .then(response => response.data);
    },
    getLlmModels: () => {
        return http.get(`${BASE_URL}/llm/models`)
            .then(response => response.data);
    },
    getLlmResponse: (runId, language, model) => {
        const query = eventSourceQuery({runId});
        if (language) query.set('language', language);
        if (model) query.set('model', model);
        return new EventSource(`${BASE_URL}/llm/get?${query.toString()}`)
    },
    getAgentRun: (runId) => {
        return http.get(`${BASE_URL}/llm/run`, {params: {runId}})
            .then(response => response.data);
    },
    getLlmProgress: () => {
        return http.get(`${BASE_URL}/llm/progress`)
            .then(response => response.data);
    },
    getFocusGraphStages: (runId) => {
        return http.get(`${BASE_URL}/llm/focusgraph/stages`, {params: {runId}})
            .then(response => response.data);
    },


    getNewFeatureCode: (classId) => {
        return http.get(`${BASE_URL}/code/newFeatureCode`, {
            params: {
                classId: classId
            }
        })
            .then(response => response.data);
    }

}

// // Reference
// export const fetchUserInfo = async (userId) => {
//     return (await axios.get(`${API_URL}/user/info`, {
//         paramsInfo: {
//             userId: userId
//         }
//     })).data;
// };
//
// export const selectIeDetail = async (requestData) => {
//     return (await axios.post(`${API_URL}/ie_detail/select`, requestData)).data.data;
// }
//
// export const deleteSelectedIeDetail = async (selectedRowKeys) => {
//     return (await axios.delete(`${API_URL}/ie_detail/delete`, {
//         data: {detailIdList: selectedRowKeys}
//     })).data;
// }
//
// export const insertIeDetail = async (requestData) => {
//     return (await axios.put(`${API_URL}/ie_detail/insert`, requestData)).data;
// }

export default API;
