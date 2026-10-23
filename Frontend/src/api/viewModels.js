// Presentation-only mappings. No source parsing, inferred graph relations or fake results.
import {text} from './client';

export function projectView(project) {
    return {
        ...project,
        repoId: project.id, repositoryId: project.id,
        projectName: project.name, projectType: 'GENERIC',
        gitLink: project.source.url || '', gitName: project.source.kind === 'upload' ? text('本地上传', 'Local upload') : project.source.url,
        description: '', descriptionCn: '',
        summaryFlag: project.analysis?.status === 'complete',
        language: '—', loc: null, noc: null, nom: null, nof: null,
    };
}

export function featureView(result) {
    if (!result.features.length) return [];
    // A single display group preserves the existing accordion; it is not an inferred module.
    const label = text('功能特征', 'Features');
    return [{moduleId: 'reposummary', moduleDesc: label, moduleDescCN: label,
        featureList: result.features.map(feature => ({
            featureId: feature.id,
            featureDescription: feature.description || feature.name,
            featureDescriptionCn: feature.description || feature.name,
            name: feature.name, nodeIds: feature.nodeIds,
        }))}];
}

export function graphView(result, featureId) {
    const feature = result.features.find(item => String(item.id) === String(featureId));
    if (!feature) throw new Error(text('特性不存在，请刷新项目', 'Feature not found; refresh the project'));
    const ids = new Set(feature.nodeIds);
    return {
        nodes: result.graph.nodes.filter(node => ids.has(node.id)).map(node => ({
            ...node, semanticType: node.type, type: 'Default',
            // Use an explicit display label; never interpret a name as a Java/Python signature.
            displayLabel: `${node.name}\n——————————\n${node.type}`,
            methods: [], funcFile: node.location?.path || '',
        })),
        edges: result.graph.edges.filter(edge => ids.has(edge.source) && ids.has(edge.target))
            .map(edge => ({...edge, from: edge.source, to: edge.target})),
    };
}

export function progressView(task) {
    const descriptions = {
        not_started: text('尚未发起分析；可在项目设置中提交。', 'Analysis not started; submit from project settings.'),
        queued: text('等待 RepoSummary 分析。', 'Waiting for RepoSummary.'),
        running: text('RepoSummary 正在分析。', 'RepoSummary is analyzing.'),
        complete: text('分析完成。', 'Analysis complete.'),
        failed: text('分析失败。', 'Analysis failed.'),
    };
    return {status: task.status === 'not_started' ? 'pending' : task.status,
        displayMessage: task.error || task.message || descriptions[task.status],
        percent: task.status === 'complete' ? 100 : Math.round((task.progress || 0) * 100),
        currentStep: task.status === 'complete' ? 1 : 0, totalSteps: 1, elapsedMs: null, steps: []};
}
