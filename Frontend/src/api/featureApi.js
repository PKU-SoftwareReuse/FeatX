import {http, projectPath, text} from './client';
import {featureView} from './viewModels';

export async function loadResult() {
    const base = projectPath();
    const {data: task} = await http.get(`${base}/analysis`);
    if (task.status !== 'complete') {
        if (task.status === 'not_started') {
            const {data: health} = await http.get('/integrations/reposummary');
            if (!health.businessReady) throw new Error(text('RepoSummary 待接入，业务尚未实现。', 'RepoSummary is online but not implemented yet.'));
        }
        throw new Error(task.error || task.message || text('尚无分析结果，请在项目设置中发起分析。', 'No result yet; submit analysis from project settings.'));
    }
    return (await http.get(`${base}/result`)).data;
}
const edit = body => http.post(`${projectPath()}/edits`, body).then(response => response.data);
const featureApi = {
    getFeatures: async () => featureView(await loadResult()),
    deleteFeature: body => edit({operation: 'delete', ...body}),
    modifyFeature: (description, language, model) => edit({operation: 'modify', description, language, model}),
    addFeature: body => edit({operation: 'add', ...body}),
};
export default featureApi;
