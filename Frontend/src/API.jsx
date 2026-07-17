import axios, {options} from "axios";

// const BASE_URL = "http://101.201.65.66:3000/api";
// const BASE_URL = "/api";
const BASE_URL = process.env.REACT_APP_API_BASE_URL || "http://127.0.0.1:8080";
// const BASE_URL = "http://10.7.1.126:8080";


const API = {
    testConnect: () => {
        return axios.get(`${BASE_URL}/connect/test`);
    },
    uploadProject: (projectData) => {
        return axios.post(`${BASE_URL}/project/upload`, projectData)
    },
    getProjectsInfo: () => {
        return axios.get(`${BASE_URL}/project/getList`)
            .then(response => response.data);
    },
    postProjectPath: (repoId) => {
        return axios.post(`${BASE_URL}/project/select`, {
            repoId: repoId,
        })
    },
    getCurrentProject: () => {
        return axios.get(`${BASE_URL}/project/current`)
            .then(response => response.data);
    },
    postResummary: (repoId) => {
        return axios.post(`${BASE_URL}/project/resummary`, {
            repoId: repoId,
        })
    },
    getSummaryProgressAll: () => {
        return axios.get(`${BASE_URL}/project/summary/progress/all`)
            .then(response => response.data);
    },
    postDropRepo: (repoId) => {
        return axios.post(`${BASE_URL}/project/drop`, {
            repoId: repoId,
        })
    },
    updateProject: (projectId, projectData) => {
        return axios.put(`${BASE_URL}/project/${projectId}`, projectData)
            .then(response => response.data);
    },
    gitDownRepo: (gitName, commitId) => {
        return axios.post(`${BASE_URL}/project/gitdown`, {
            gitRepoName: gitName,
            commitId: commitId,
        }).then(response => response.data);
    },
    gitClear: (gitName, commitId) => {
        return axios.post(`${BASE_URL}/project/gitclear`, {
            gitRepoName: gitName,
            commitId: commitId,
        });
    },
    gitRepo: (gitName, commitId, folderName) => {
        return axios.post(`${BASE_URL}/project/gitrepo`, {
            gitRepoName: gitName,
            commitId: commitId,
            repoName: folderName,
        }).then(response => response.data);
    },
    getMinGraphData: (featureId) => {
        return axios.get(`${BASE_URL}/graph/feature/debloatGraph`, {
            params: {
                featureId: featureId
            }
        })
            .then(response => response.data);
    },
    getMaxGraphData: (featureId) => {
        return axios.get(`${BASE_URL}/graph/feature/maxGraph`, {
            params: {
                featureId: featureId
            }
        })
            .then(response => response.data);
    },
    getNewGraphData: () => {
        return axios.get(`${BASE_URL}/graph/feature/newGraph`)
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
        return axios.get(`${BASE_URL}/feature/get`)
            .then(response => response.data);
    },
    getDeleteDiffByClass: (classId) => {
        return axios.get(`${BASE_URL}/code/deleteDiffByClass`, {
            params: {
                classId: classId
            }
        }).then(response => response.data);
    },
    getContextByClass: (classId) => {
        return axios.get(`${BASE_URL}/code/contextByClass`, {
            params: {
                classId: classId
            }
        }).then(response => response.data);
    },
    getNewDiffByClass: (classId) => {
        return axios.get(`${BASE_URL}/code/newDiffByClass`, {
            params: {
                classId: classId
            }
        }).then(response => response.data);
    },
    getRepositoryDiff: () => {
        return axios.get(`${BASE_URL}/code/repositoryDiff`)
            .then(response => response.data);
    },

    confirmDelete: () => {
        return axios.post(`${BASE_URL}/feature/confirm/delete`)
    },
    deleteFeature: (requestData) => {
        return axios.post(`${BASE_URL}/feature/delete`, requestData)
    },

    modifyFeature: (featureDescription, language) => {
        return axios.post(`${BASE_URL}/feature/modify`, {
            featureDescription: featureDescription,
            language: language,
        })
    },
    confirmModify: () => {
        return axios.post(`${BASE_URL}/feature/confirm/modify`)
            .then(response => response.data);
    },

    addFeature: (requestData) => {
        return axios.post(`${BASE_URL}/feature/add`, requestData)
    },
    confirmAdd: () => {
        return axios.post(`${BASE_URL}/feature/confirm/add`)
            .then(response => response.data);
    },
    getLlmModels: () => {
        return axios.get(`${BASE_URL}/llm/models`)
            .then(response => response.data);
    },
    getLlmResponse: (language, model) => {
        const query = new URLSearchParams({language, model});
        return new EventSource(`${BASE_URL}/llm/get?${query.toString()}`)
    },
    getLlmProgress: () => {
        return axios.get(`${BASE_URL}/llm/progress`)
            .then(response => response.data);
    },
    getFocusGraphStages: () => {
        return axios.get(`${BASE_URL}/llm/focusgraph/stages`)
            .then(response => response.data);
    },


    getNewFeatureCode: (classId) => {
        return axios.get(`${BASE_URL}/code/newFeatureCode`, {
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
