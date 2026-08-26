import {BASE_URL, http} from "./client";

const featureApi = {
    getFeatures: () => http.get(`${BASE_URL}/feature/get`)
        .then((response) => response.data),
    deleteFeature: (requestData) => http.post(`${BASE_URL}/feature/delete`, requestData)
        .then((response) => response.data),
    modifyFeature: (featureDescription, language, model) => http.post(`${BASE_URL}/feature/modify`, {
        featureDescription,
        language,
        model,
    }).then((response) => response.data),
    addFeature: (requestData) => http.post(`${BASE_URL}/feature/add`, requestData)
        .then((response) => response.data),
};

export default featureApi;
