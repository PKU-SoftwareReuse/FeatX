import {BASE_URL, http} from "./client";

const codeApi = {
    getDeleteDiffByClass: (classId) => http.get(`${BASE_URL}/code/deleteDiffByClass`, {
        params: {classId},
    }).then((response) => response.data),
    getContextByClass: (classId) => http.get(`${BASE_URL}/code/contextByClass`, {
        params: {classId},
    }).then((response) => response.data),
    getCandidateDiff: (classId, operation, runId) => http.get(`${BASE_URL}/code/candidateDiff`, {
        params: {classId, operation, runId},
    }).then((response) => response.data),
    getManualCandidate: (classId, operation, runId) => http.get(`${BASE_URL}/code/manualCandidate`, {
        params: {classId, operation, runId},
    }).then((response) => response.data),
    updateCandidateDiff: (key, operation, content, runId) => http.put(`${BASE_URL}/code/candidateDiff`, {
        key,
        operation,
        content,
        runId,
    }).then((response) => response.data),
};

export default codeApi;
