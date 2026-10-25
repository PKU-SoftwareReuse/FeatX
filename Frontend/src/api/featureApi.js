import {http, projectPath} from './client';
import {featureView} from './viewModels';

export async function loadResult() {
    const base = projectPath();
    const {data: task} = await http.get(`${base}/summary/status`);
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
    deleteFeature: body => edit({operation: 'delete', ...body}),
    modifyFeature: (description, model) => edit({operation: 'modify', description, model}),
    addFeature: body => edit({operation: 'add', ...body}),
};
export default featureApi;
