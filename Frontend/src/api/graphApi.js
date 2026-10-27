import {loadResult} from './featureApi';
import {graphView} from './viewModels';
import {http, projectPath} from './client';
const graphApi = {
    getGraphReferences: (projectId, files, signal) => http.post(`${projectPath(projectId)}/graph/references`, {files}, {signal,timeout:1000}).then(response=>response.data),
    previewFeatureGraph: (projectId, body, signal) => http.post(`${projectPath(projectId)}/graph/preview`, body, {signal, timeout:2000}).then(response => response.data),
    getInitialGraphData: async featureId => graphView(await loadResult(), featureId),
    getMinGraphData: async featureId => graphView(await loadResult(), featureId),
    getCandidateGraphData: runId => http.get(`${projectPath()}/agent/runs/${encodeURIComponent(runId)}/graph`).then(response => response.data),
};
export default graphApi;
