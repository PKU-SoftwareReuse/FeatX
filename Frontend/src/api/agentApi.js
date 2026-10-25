import {unavailable, unavailableError} from './client';
const agentApi = {
    getLlmModels: () => unavailable('Agent 模型选择'),
    getLlmResponse: () => {throw unavailableError('Agent');},
    getAgentRun: () => unavailable('Agent'),
    getLlmProgress: () => unavailable('Agent'),
    getFocusGraphStages: () => unavailable('推理图阶段'),
};
export default agentApi;
