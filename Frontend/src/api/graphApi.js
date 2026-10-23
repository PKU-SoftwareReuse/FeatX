import {loadResult} from './featureApi';
import {graphView} from './viewModels';
import {unavailable, text} from './client';
const graphApi = {
    getInitialGraphData: async featureId => graphView(await loadResult(), featureId),
    getMinGraphData: () => unavailable(text('删除结果图', 'Deletion result graph')),
    getCandidateGraphData: () => unavailable(text('候选修改图', 'Candidate change graph')),
};
export default graphApi;
