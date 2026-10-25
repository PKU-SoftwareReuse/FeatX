import {featureView, graphView, progressView, projectView} from './viewModels';
jest.mock('./client', () => ({text: (zh, en) => en}));
const result = {epics: [{id: 'e1', description: 'Epic', features: [
    {id: 'f1', description: 'Feature', codeElements: ['src/main.rs::crate::auth::login', 'src/main.rs::validate', 'web/main.ts::submit']},
]}]};
test('retains real epic hierarchy and groups elements by file without edges', () => {
    expect(featureView(result)[0].moduleId).toBe('e1');
    expect(featureView(result)[0].featureList[0].featureId).toBe('f1');
    const graph = graphView(result, 'f1');
    expect(graph.nodes).toHaveLength(2);
    expect(graph.nodes[0].methods).toEqual(['crate::auth::login', 'validate']);
    expect(graph.edges).toEqual([]);
});
test('only displays service-supplied stages and percentages', () => {
    const progress = progressView({status:'running', totalStages:3, currentStageIndex:2, overallPercent:42,
        elapsedMs:4000, stages:[{name:'One'},{name:'Two',message:'Working',progress:{percent:21}},{name:'Three'}]});
    expect(progress.percent).toBe(42);
    expect(progress.totalSteps).toBe(3);
    expect(progress.steps[1].percent).toBe(21);
    expect(progress.displayMessage).toBe('Working');
    expect(progressView({status:'running'}).percent).toBeNull();
});
test('registered projects are not falsely marked summarized', () => {
    expect(projectView({id:'p',name:'P',source:{url:'https://example.com/p'},analysis:null}).summaryFlag).toBe(false);
});
