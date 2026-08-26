import {BASE_URL, eventSourceQuery, http} from "./client";

const agentApi = {
    getLlmModels: () => http.get(`${BASE_URL}/llm/models`)
        .then((response) => response.data),
    getLlmResponse: (runId, language, model) => {
        const query = eventSourceQuery({runId});
        if (language) query.set("language", language);
        if (model) query.set("model", model);
        return new EventSource(`${BASE_URL}/llm/get?${query.toString()}`);
    },
    getAgentRun: (runId) => http.get(`${BASE_URL}/llm/run`, {params: {runId}})
        .then((response) => response.data),
    getLlmProgress: () => http.get(`${BASE_URL}/llm/progress`)
        .then((response) => response.data),
    getFocusGraphStages: (runId) => http.get(`${BASE_URL}/llm/focusgraph/stages`, {params: {runId}})
        .then((response) => response.data),
};

export default agentApi;
