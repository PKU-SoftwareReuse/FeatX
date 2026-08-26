import {BASE_URL, http} from "./client";

const graphApi = {
    getMinGraphData: (featureId, runId) => http.get(`${BASE_URL}/graph/feature/debloatGraph`, {
        params: {featureId, runId},
    }).then((response) => response.data),
    getInitialGraphData: (featureId) => http.get(`${BASE_URL}/graph/feature/initialGraph`, {
        params: {featureId},
    }).then((response) => response.data),
    getNewGraphData: (runId) => http.get(`${BASE_URL}/graph/feature/newGraph`, {
        params: {runId},
    }).then((response) => response.data),
};

export default graphApi;
