// Presentation-only mappings. No source parsing, inferred graph relations or fake results.

export function projectView(project) {
    return {
        ...project,
        repoId: project.id, repositoryId: project.id,
        projectName: project.name, projectType: 'GENERIC',
        gitLink: project.source.kind === 'upload' ? project.gitLink || '' : project.source.url || '', gitName: project.source.kind === 'upload' ? '本地上传' : project.source.url,
        description: project.description || '',
        summaryFlag: project.analysis?.status === 'complete' || Boolean(project.summaryRunId),
        summaryPending: ['queued', 'running'].includes(project.analysis?.status),
        language: '—', loc: null, noc: null, nom: null, nof: null,
    };
}

export function featureView(result) {
    return result.epics.map(epic => ({
        moduleId: epic.id, moduleDesc: epic.description,
        featureList: epic.features.map(feature => ({
            featureId: feature.id, featureDescription: feature.description,
            codeElements: feature.codeElements,
        })),
    }));
}

export function graphView(result, featureId) {
    const feature = result.epics.flatMap(epic => epic.features).find(item => String(item.id) === String(featureId));
    if (!feature) throw new Error('特性不存在，请刷新项目');
    return graphForElements(feature.codeElements);
}

export function graphForElements(codeElements) {
    const files = new Map();
    for (const qualifiedName of codeElements) {
        const delimiter = qualifiedName.indexOf('::');
        if (delimiter <= 0) throw new Error('代码元素缺少文件路径分隔符 ::');
        const path = qualifiedName.slice(0, delimiter);
        const name = qualifiedName.slice(delimiter + 2);
        if (!files.has(path)) files.set(path, new Set());
        files.get(path).add(name);
    }
    return {nodes: [...files].map(([path, names]) => ({
        id: path, type: 'Default', funcFile: path, methods: [...names],
        displayLabel: `${path}\n——————————\n${[...names].join('\n')}`,
    })), edges: []};
}

export function progressView(task) {
    const stages = task.stages || [];
    const current = stages[(task.currentStageIndex || 0) - 1];
    return {
        status: task.status === 'not_started' ? 'pending' : task.status,
        displayMessage: task.error || current?.message || task.message
            || '尚未开始总结。',
        percent: task.overallPercent ?? null,
        currentStep: task.currentStageIndex ?? (task.status === 'complete' ? task.totalStages : 0),
        totalSteps: task.totalStages ?? null,
        elapsedMs: task.elapsedMs ?? null,
        steps: stages.map(stage => ({...stage, percent: stage.progress?.percent ?? null})),
    };
}
