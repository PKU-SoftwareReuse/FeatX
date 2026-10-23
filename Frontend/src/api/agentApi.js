import {unavailable, unavailableError, text} from './client';
const agentApi = {
    getLlmModels: () => unavailable(text('Agent 模型选择', 'Agent model selection')),
    getLlmResponse: () => {throw unavailableError('Agent');},
    getAgentRun: () => unavailable('Agent'),
    getLlmProgress: () => unavailable('Agent'),
    getFocusGraphStages: () => unavailable(text('推理图阶段', 'Reasoning graph stages')),
};
export default agentApi;
