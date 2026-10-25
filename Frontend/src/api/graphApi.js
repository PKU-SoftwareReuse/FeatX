import {loadResult} from './featureApi';
import {graphView} from './viewModels';
import {unavailable} from './client';
const graphApi = {
    getInitialGraphData: async featureId => graphView(await loadResult(), featureId),
    getMinGraphData: () => unavailable('删除结果图'),
    getCandidateGraphData: () => unavailable('候选修改图'),
};
export default graphApi;
