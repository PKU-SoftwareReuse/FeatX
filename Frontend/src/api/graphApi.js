import {loadResult} from './featureApi';
import {graphView} from './viewModels';
import {http, projectPath} from './client';
const graphApi = {
    getInitialGraphData: async featureId => graphView(await loadResult(), featureId),
    getMinGraphData: async featureId => graphView(await loadResult(), featureId),
    getCandidateGraphData: runId => http.get(`${projectPath()}/agent/runs/${encodeURIComponent(runId)}/graph`).then(response => response.data),
};
export default graphApi;
