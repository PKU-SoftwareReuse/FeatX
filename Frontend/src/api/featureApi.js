import {http, projectPath} from './client';
import {featureView} from './viewModels';

export async function loadResult() {
    const base = projectPath();
    const {data: task} = await http.get(`${base}/summary/status`);
    if (['queued', 'running'].includes(task.status)) {
        throw new Error('项目正在总结，请等待完成后再打开。');
    }
    if (!task.hasSummary) {
        if (task.status === 'not_started') {
            const {data: health} = await http.get('/integrations/reposummary');
            if (!health.businessReady) throw new Error('RepoSummary 待接入，业务尚未实现。');
        }
        throw new Error(task.error || task.message || '尚无分析结果，请在项目设置中发起分析。');
    }
    return (await http.get(`${base}/summary`)).data;
}
const edit = body => http.post(`${projectPath()}/edits`, body).then(response => response.data);
const featureApi = {
    getFeatures: async () => featureView(await loadResult()),
    deleteFeature: body => edit({operation: 'delete', featureId: body.featureId, description: body.featureDescription, model: body.model}),
    modifyFeature: (description, model, featureId) => edit({operation: 'modify', description, model, featureId}),
    addFeature: body => edit({operation: 'add', epicId: body.moduleId, description: body.featureDescription, model: body.model}),
};
export default featureApi;
