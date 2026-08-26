import agentApi from "./agentApi";
import codeApi from "./codeApi";
import featureApi from "./featureApi";
import gitApi from "./gitApi";
import graphApi from "./graphApi";
import projectApi from "./projectApi";

export {
    agentApi,
    codeApi,
    featureApi,
    gitApi,
    graphApi,
    projectApi,
};

const API = {
    ...projectApi,
    ...graphApi,
    ...featureApi,
    ...agentApi,
    ...codeApi,
    ...gitApi,
};

export default API;
