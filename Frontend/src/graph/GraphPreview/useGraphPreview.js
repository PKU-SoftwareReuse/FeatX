import {useCallback, useEffect, useRef, useState} from 'react';
import API from '../../api';

export const PREVIEW_DEADLINE = 10000;
const emptyGraph = {nodes:[],edges:[],fileCount:0,elementCount:0};
export function previewTimeline(data, reduced = false, budget = Infinity) {
    const expanding = data.stages.expanded.elementCount > data.stages.initial.elementCount;
    const pruning = data.removedElementIds.length > 0;
    const items = [
        ['expanding', expanding ? (reduced ? 150 : 2200) : 0],
        ['expanded', expanding ? (reduced ? 150 : 2000) : 0],
        ['reversing', pruning ? (reduced ? 100 : 400) : 0],
        ['pruning', pruning ? (reduced ? 200 : 2000) : 0],
        ['final', data.stages.expanded.elementCount ? (reduced ? 200 : 2600) : 300],
        ['transition', reduced ? 80 : 200],
    ];
    const scale = Math.min(1, Math.max(0, budget) / items.reduce((sum, [,duration]) => sum + duration, 0));
    let elapsed = 0;
    return items.filter(([,duration]) => duration > 0).map(([phase,duration]) => {
        duration *= scale;
        const start = elapsed; elapsed += duration;
        return {phase,duration,start,end:elapsed};
    });
}

export default function useGraphPreview() {
    const [preview, setPreview] = useState(null);
    const session = useRef(null);
    const sequence = useRef(0);
    const mounted = useRef(true);
    const cancel = useCallback((clear = true) => {
        const current = session.current;
        if (current) {
            current.done = true;
            current.controller.abort();
            cancelAnimationFrame(current.frame);
            clearTimeout(current.timeout);
            document.removeEventListener('visibilitychange', current.tick);
            current.resolve({cancelled:true});
            session.current = null;
        }
        if (clear && mounted.current) setPreview(null);
    }, []);
    useEffect(() => {
        mounted.current = true;
        return () => {mounted.current = false; cancel(false);};
    }, [cancel]);

    const start = useCallback((projectId, body, fallbackGraph) => {
        cancel();
        const initial = body.operation === 'add' ? emptyGraph : fallbackGraph || emptyGraph;
        const id = ++sequence.current;
        let resolve;
        const promise = new Promise(done => {resolve = done;});
        const current = {id,resolve,controller:new AbortController(),startedAt:performance.now(),
            data:null,readyAt:null,phase:'retrieving',done:false,frame:null,timeout:null,
            reduced:window.matchMedia('(prefers-reduced-motion: reduce)').matches};
        session.current = current;
        setPreview({id,active:true,phase:'retrieving',data:null,initial,reduced:current.reduced,direction:1});
        const finish = outcome => {
            if (session.current !== current || current.done) return;
            current.done = true;
            current.controller.abort();
            cancelAnimationFrame(current.frame);
            clearTimeout(current.timeout);
            document.removeEventListener('visibilitychange', current.tick);
            session.current = null;
            if (mounted.current) setPreview({id,active:false,phase:'final',data:current.data,initial,reduced:current.reduced,direction:-1});
            resolve(outcome || {cancelled:false});
        };
        current.tick = () => {
            if (session.current !== current || current.done) return;
            cancelAnimationFrame(current.frame);
            const now = performance.now();
            if (now - current.startedAt >= PREVIEW_DEADLINE) {finish(); return;}
            if (current.data && now >= current.readyAt) {
                const phase = current.timeline.find(part => now - current.readyAt < part.end);
                if (!phase) {finish(); return;}
                if (current.phase !== phase.phase) {
                    current.phase = phase.phase;
                    setPreview({id,active:true,phase:phase.phase,data:current.data,initial,
                        phaseStartedAt:current.readyAt + phase.start,phaseDuration:phase.duration,
                        reduced:current.reduced,direction:['reversing','pruning','final','transition'].includes(phase.phase) ? -1 : 1});
                }
            }
            current.frame = requestAnimationFrame(current.tick);
        };
        document.addEventListener('visibilitychange', current.tick);
        current.frame = requestAnimationFrame(current.tick);
        current.timeout = setTimeout(() => finish({cancelled:false,notice:'关联图谱准备超时，继续等待智能体任务。'}), 2000);
        API.previewFeatureGraph(projectId, body, current.controller.signal).then(data => {
            if (session.current !== current || current.done) return;
            if (!['initial','expanded','pruned'].every(key => Array.isArray(data?.stages?.[key]?.nodes)) || !Array.isArray(data?.keptElementIds) || !Array.isArray(data?.removedElementIds)) {
                throw new Error('预览数据不完整');
            }
            clearTimeout(current.timeout);
            current.data = data;
            current.readyAt = Math.max(current.startedAt + 400, performance.now());
            current.timeline = previewTimeline(data, current.reduced,
                PREVIEW_DEADLINE - (current.readyAt - current.startedAt) - 50);
            current.tick();
        }).catch(error => {
            if (session.current !== current || current.done) return;
            finish({cancelled:false,notice:error.code === 'ECONNABORTED' ? '关联图谱准备超时，智能体任务仍按原流程执行。' : `关联图谱暂不可用：${error.message || '请求失败'}`});
        });
        return promise;
    }, [cancel]);
    return {preview,startPreview:start,clearPreview:cancel};
}
