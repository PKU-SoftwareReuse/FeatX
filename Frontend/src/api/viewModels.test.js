import {featureView, graphView, progressView, projectView} from './viewModels';
jest.mock('./client', () => ({text: (zh, en) => en}));

const result = {
    features: [{id: 'f-1', name: 'Authentication', description: 'Check access', nodeIds: ['node.1']}],
    graph: {nodes: [
        {id: 'node.1', name: 'service.fn()', type: 'custom-component', location: {path: 'src/main.rs'}},
        {id: 'node-2', name: 'Other', type: 'anything'},
    ], edges: [{source: 'node.1', target: 'node-2', type: 'depends'}]},
};

test('maps project registration without pretending analysis completed', () => {
    const project = projectView({id: 'p', name: 'Repo', source: {url: 'https://example.com/repo'}, analysis: null});
    expect(project.projectName).toBe('Repo');
    expect(project.repoId).toBe('p');
    expect(project.summaryFlag).toBe(false);
    expect(project.projectType).toBe('GENERIC');
});

test('adapts flat features into the original accordion shape without new analysis', () => {
    expect(featureView(result)[0].featureList[0].featureId).toBe('f-1');
    expect(featureView({features: []})).toEqual([]);
});

test('keeps graph IDs and arbitrary names, filters only supplied associations', () => {
    const graph = graphView(result, 'f-1');
    expect(graph.nodes).toHaveLength(1);
    expect(graph.nodes[0].id).toBe('node.1');
    expect(graph.nodes[0].displayLabel).toContain('service.fn()');
    expect(graph.nodes[0].semanticType).toBe('custom-component');
    expect(graph.edges).toEqual([]);
    expect(() => graphView(result, 'missing')).toThrow();
});

test('does not report pending registrations as running analysis', () => {
    expect(progressView({status: 'not_started'})).toMatchObject({status: 'pending', percent: 0});
    expect(progressView({status: 'failed', error: 'Unavailable'}).displayMessage).toBe('Unavailable');
});
