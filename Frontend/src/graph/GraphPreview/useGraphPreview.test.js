import {act, renderHook} from '@testing-library/react';
import useGraphPreview, {previewTimeline, PREVIEW_DEADLINE} from './useGraphPreview';
import API from '../../api';
jest.mock('../../api', () => ({previewFeatureGraph:jest.fn()}));
const graph = count => ({nodes:[],edges:[],elementCount:count,fileCount:count});
const data = {stages:{initial:graph(2),expanded:graph(8),pruned:graph(4)},keptElementIds:['a'],removedElementIds:['b']};
beforeEach(() => {jest.useFakeTimers();window.matchMedia=()=>({matches:false});API.previewFeatureGraph.mockReset();});
afterEach(() => {jest.useRealTimers();});

test('timeline holds expanded and final graphs and fits slow requests within deadline', () => {
    const stages=previewTimeline(data);
    expect(stages.map(s=>s.phase)).toEqual(['expanding','expanded','reversing','pruning','final','transition']);
    expect(400+stages[stages.length-1].end).toBeGreaterThanOrEqual(9500);
    expect(400+stages[stages.length-1].end).toBeLessThan(PREVIEW_DEADLINE);
    expect(stages.find(s=>s.phase==='expanded').duration).toBe(2000);
    expect(stages.find(s=>s.phase==='final').duration).toBe(2600);
    const slow=previewTimeline(data,false,PREVIEW_DEADLINE-2050);
    expect(2000+slow[slow.length-1].end).toBeLessThan(PREVIEW_DEADLINE);
    expect(previewTimeline({...data,stages:{initial:graph(0),expanded:graph(0),pruned:graph(0)},removedElementIds:[]})).toHaveLength(2);
});

test('normal playback resolves once; timeout resolves without stalling or accepting late data', async () => {
    API.previewFeatureGraph.mockResolvedValue(data);
    const {result}=renderHook(()=>useGraphPreview());let done;
    await act(async()=>{done=result.current.startPreview('p',{operation:'add',description:'登录'},graph(0));});
    await act(async()=>{jest.advanceTimersByTime(9900);await done;});
    expect(result.current.preview.active).toBe(false);
    expect(result.current.preview.data).toEqual(data);
    let late;API.previewFeatureGraph.mockImplementation(()=>new Promise(resolve=>{late=resolve;}));
    await act(async()=>{done=result.current.startPreview('p',{operation:'add',description:'登录'},graph(0));});
    let outcome;
    await act(async()=>{jest.advanceTimersByTime(2001);outcome=await done;});
    expect(outcome.notice).toContain('超时');
    await act(async()=>{late(data);});
    expect(result.current.preview.data).toBeNull();
});

test('unmount cancels presentation without creating another task request', async () => {
    API.previewFeatureGraph.mockReturnValue(new Promise(()=>{}));
    const {result,unmount}=renderHook(()=>useGraphPreview());let done;
    act(()=>{done=result.current.startPreview('p',{operation:'modify',description:'登录'},graph(2));});
    unmount();expect(await done).toEqual({cancelled:true});
    expect(API.previewFeatureGraph).toHaveBeenCalledTimes(1);
});

test('replay survives clearing and remounting, reuses snapshots without another request, restores graph', async () => {
    sessionStorage.clear();
    API.previewFeatureGraph.mockResolvedValue(data);
    let view=renderHook(()=>useGraphPreview('replay-project'));let done;
    await act(async()=>{done=view.result.current.startPreview('replay-project',{operation:'modify',featureId:'f',description:'登录'},graph(2));});
    act(()=>view.result.current.bindReplay('run-1'));
    await act(async()=>{jest.advanceTimersByTime(9900);await done;});
    act(()=>view.result.current.clearPreview());
    view.unmount();
    view=renderHook(()=>useGraphPreview('replay-project'));
    expect(view.result.current.lastReplay.data).toEqual(data);
    await act(async()=>{done=view.result.current.replayPreview({runId:'run-1'});});
    expect(view.result.current.preview.replaying).toBe(true);
    expect(API.previewFeatureGraph).toHaveBeenCalledTimes(1);
    await act(async()=>{jest.advanceTimersByTime(9900);await done;});
    expect(view.result.current.preview).toBeNull();
    expect(API.previewFeatureGraph).toHaveBeenCalledTimes(1);
    view.unmount();sessionStorage.clear();
});


test('replay while the agent is running holds the pruned snapshot instead of restoring the seed graph', async () => {
    sessionStorage.clear();API.previewFeatureGraph.mockResolvedValue(data);
    const {result,unmount}=renderHook(()=>useGraphPreview('running-project'));let done;
    await act(async()=>{done=result.current.startPreview('running-project',{operation:'modify',description:'登录'},graph(2));});
    act(()=>result.current.bindReplay('run-2'));
    await act(async()=>{jest.advanceTimersByTime(9900);await done;});
    act(()=>result.current.clearPreview());
    await act(async()=>{done=result.current.replayPreview({keepFinal:true,runId:'run-2'});});
    await act(async()=>{jest.advanceTimersByTime(9900);await done;});
    expect(result.current.preview.phase).toBe('final');
    expect(result.current.preview.active).toBe(false);
    expect(result.current.preview.replaying).toBe(false);
    expect(result.current.preview.data.stages.pruned.elementCount).toBe(4);
    expect(API.previewFeatureGraph).toHaveBeenCalledTimes(1);
    unmount();sessionStorage.clear();
});


test('resolved tasks invalidate replay and a different task cannot replay a saved snapshot', async () => {
    sessionStorage.clear();API.previewFeatureGraph.mockResolvedValue(data);
    const {result,unmount}=renderHook(()=>useGraphPreview('p'));let done;
    await act(async()=>{done=result.current.startPreview('p',{operation:'modify',description:'登录'},graph(2));});
    act(()=>result.current.bindReplay('current-run'));
    await act(async()=>{jest.advanceTimersByTime(9900);await done;});
    expect(await result.current.replayPreview({runId:'other-run'})).toEqual({cancelled:true});
    act(()=>result.current.clearReplay());
    expect(result.current.lastReplay).toBeNull();
    expect(sessionStorage.getItem('featx.graphReplay.p')).toBeNull();
    expect(await result.current.replayPreview({runId:'current-run'})).toEqual({cancelled:true});
    unmount();sessionStorage.clear();
});
