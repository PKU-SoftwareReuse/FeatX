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
    postResummary: (repoId) => {
        return axios.post(`${BASE_URL}/project/resummary`, {
            repoId: repoId,
        })
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

    confirmDelete: () => {
        return axios.post(`${BASE_URL}/feature/confirm/delete`)
    },

    modifyFeature: (featureDescription) => {
        return axios.post(`${BASE_URL}/feature/modify`, {
            featureDescription: featureDescription
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
    getLlmResponse: () => {
        return new EventSource(`${BASE_URL}/llm/get`,)
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
