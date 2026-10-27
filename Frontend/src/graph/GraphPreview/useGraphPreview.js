import {useCallback, useEffect, useRef, useState} from 'react';
import API from '../../api';
import {freezeReferences} from '../References/referenceGraph';

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

const replayKey = projectId => `featx.graphReplay.${projectId}`;
const validSnapshots = data => ['initial','expanded','pruned'].every(key => Array.isArray(data?.stages?.[key]?.nodes))
    && Array.isArray(data?.keptElementIds) && Array.isArray(data?.removedElementIds);
function readReplay(projectId) {
    if (!projectId) return null;
    try {
        const saved = JSON.parse(sessionStorage.getItem(replayKey(projectId)));
        if (saved?.projectId === projectId && saved.runId && validSnapshots(saved.data)) return saved;
        sessionStorage.removeItem(replayKey(projectId));
        return null;
    } catch (_) {return null;}
}

export default function useGraphPreview(projectId) {
    const [preview, setPreview] = useState(null);
    const [lastReplay, setLastReplay] = useState(() => readReplay(projectId));
    const previewRef = useRef(preview);
    previewRef.current = preview;
    const session = useRef(null);
    const sequence = useRef(0);
    const boundRun = useRef(null);
    const mounted = useRef(true);
    const cancel = useCallback((clear = true) => {
        const current = session.current;
        if (current) {
            current.done = true;
            current.controller.abort();
            current.referencesController?.abort();
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

    useEffect(() => {cancel();boundRun.current = null;setLastReplay(readReplay(projectId));}, [projectId, cancel]);
    const clearReplay = useCallback(() => {
        cancel();boundRun.current = null;setLastReplay(null);
        try {sessionStorage.removeItem(replayKey(projectId));} catch (_) { /* Storage may be unavailable. */ }
    }, [cancel, projectId]);
    const bindReplay = useCallback(runId => {
        boundRun.current = runId;
        setLastReplay(saved => {
            if (!saved || saved.projectId !== projectId) return saved;
            const next = {...saved,runId};
            try {sessionStorage.setItem(replayKey(projectId), JSON.stringify(next));} catch (_) { /* Keep in memory. */ }
            return next;
        });
    }, [projectId]);

    const start = useCallback((projectId, body, fallbackGraph, cached = null, keepFinal = false) => {
        const previous = previewRef.current;
        cancel();
        if (!cached) {
            boundRun.current = null;setLastReplay(null);
            try {sessionStorage.removeItem(replayKey(projectId));} catch (_) { /* Keep in memory. */ }
        }
        const replayProps = {replaying:Boolean(cached),description:body.description};
        const initial = cached ? cached.stages.initial : body.operation === 'add' ? emptyGraph : fallbackGraph || emptyGraph;
        const id = ++sequence.current;
        let resolve;
        const promise = new Promise(done => {resolve = done;});
        const current = {id,projectId,body,resolve,controller:new AbortController(),referencesController:new AbortController(),startedAt:performance.now(),
            data:null,referencesLocked:Boolean(cached),readyAt:null,phase:'retrieving',done:false,frame:null,timeout:null,
            reduced:window.matchMedia('(prefers-reduced-motion: reduce)').matches};
        session.current = current;
        setPreview({...replayProps,id,active:true,phase:'retrieving',data:cached,initial,reduced:current.reduced,direction:1});
        const finish = outcome => {
            if (session.current !== current || current.done) return;
            current.done = true;
            current.controller.abort();
            current.referencesController?.abort();
            cancelAnimationFrame(current.frame);
            clearTimeout(current.timeout);
            document.removeEventListener('visibilitychange', current.tick);
            session.current = null;
            if (mounted.current) setPreview(cached && !keepFinal ? previous : {...replayProps,replaying:false,id,active:false,phase:'final',data:current.data,initial,reduced:current.reduced,direction:-1});
            resolve(outcome || {cancelled:false});
        };
        const saveSnapshot = () => {
            if (cached) return;
            const snapshot = {projectId,body,data:current.data,runId:boundRun.current};
            setLastReplay(snapshot);
            try {sessionStorage.setItem(replayKey(projectId), JSON.stringify(snapshot));} catch (_) { /* In-memory replay remains available. */ }
        };
        const lockReferences = (edges=[], partial=false) => {
            if (current.done || current.referencesLocked || session.current !== current) return;
            current.referencesLocked = true;
            current.referencesController.abort();
            current.data = freezeReferences(current.data, edges, partial);
            saveSnapshot();
            setPreview(previous => previous?.id===id ? {...previous,data:current.data} : previous);
        };
        current.tick = () => {
            if (session.current !== current || current.done) return;
            cancelAnimationFrame(current.frame);
            const now = performance.now();
            if (now - current.startedAt >= PREVIEW_DEADLINE) {finish(); return;}
            if (current.data && now >= current.readyAt) {
                if (!current.referencesLocked) lockReferences([],true);
                const phase = current.timeline.find(part => now - current.readyAt < part.end);
                if (!phase) {finish(); return;}
                if (current.phase !== phase.phase) {
                    current.phase = phase.phase;
                    setPreview({...replayProps,id,active:true,phase:phase.phase,data:current.data,initial,
                        phaseStartedAt:current.readyAt + phase.start,phaseDuration:phase.duration,
                        reduced:current.reduced,direction:['reversing','pruning','final','transition'].includes(phase.phase) ? -1 : 1});
                }
            }
            current.frame = requestAnimationFrame(current.tick);
        };
        document.addEventListener('visibilitychange', current.tick);
        current.frame = requestAnimationFrame(current.tick);
        if (!cached) current.timeout = setTimeout(() => finish({cancelled:false,notice:'关联图谱准备超时，继续等待智能体任务。'}), 2000);
        const result = cached ? Promise.resolve(cached) : API.previewFeatureGraph(projectId, body, current.controller.signal);
        result.then(data => {
            if (session.current !== current || current.done) return;
            if (!validSnapshots(data)) {
                throw new Error('预览数据不完整');
            }
            clearTimeout(current.timeout);
            current.data = data;
            saveSnapshot();
            if (!cached) {
                const files = data.stages.expanded.nodes.map(node=>node.funcFile || String(node.id));
                if (files.length<2) lockReferences();
                else {
                    const request = API.getGraphReferences ? API.getGraphReferences(projectId,files,current.referencesController.signal) : Promise.resolve({projectId,edges:[]});
                    request.then(result=>{
                        if (result.projectId === projectId) lockReferences(result.edges || [],Boolean(result.partial));
                        else lockReferences([],true);
                    }).catch(()=>lockReferences([],true));
                }
            }
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
    const recordReferenceDisplay = useCallback((id, snapshot) => {
        const current = session.current;
        if (!current || current.id!==id || current.done || current.data?.referenceDisplay) return;
        current.data = {...current.data,...snapshot};
        setPreview(previous=>previous?.id===id ? {...previous,data:current.data} : previous);
        setLastReplay(previous=>{
            if (!previous || previous.projectId!==current.projectId) return previous;
            const next={...previous,data:current.data};
            try {sessionStorage.setItem(replayKey(current.projectId),JSON.stringify(next));} catch (_) { /* Keep in memory. */ }
            return next;
        });
    }, []);
    const replayPreview = useCallback(({keepFinal = false, runId} = {}) => {
        if (session.current || !lastReplay || lastReplay.projectId !== projectId || !runId || lastReplay.runId !== runId) return Promise.resolve({cancelled:true});
        return start(lastReplay.projectId, lastReplay.body, lastReplay.data.stages.initial, lastReplay.data, keepFinal);
    }, [lastReplay, projectId, start]);
    return {preview,startPreview:start,clearPreview:cancel,replayPreview,lastReplay,clearReplay,bindReplay,recordReferenceDisplay};
}
