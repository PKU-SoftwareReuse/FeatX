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
