import {memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState} from 'react';
import {ReactFlow, Background, Controls, ControlButton, Handle, MarkerType, MiniMap, Panel, Position, ReactFlowProvider, useReactFlow, useNodesState, useViewport} from '@xyflow/react';
import {AppstoreOutlined, FileTextOutlined, CheckCircleOutlined, ClockCircleOutlined, EyeOutlined} from '@ant-design/icons';
import '@xyflow/react/dist/style.css';
import styles from './WorkspaceGraph.module.css';
import {iconForFile} from '../../utils/fileIcon';
import GraphPreviewOverlay from '../GraphPreview/GraphPreviewOverlay';
import {fileGrid} from './gridLayout';
import useFileReferences from '../References/useFileReferences';
import ReferenceEdge from '../References/ReferenceEdge';
import {selectReferences,focusReferences,referenceSnapshots,extendPositions} from '../References/referenceGraph';

const FileNode = memo(({data, selected}) => {
    const [expanded, setExpanded] = useState(false);
    const path = data.path;
    const slash = path.lastIndexOf('/');
    const elements = data.elements;
    const pruning = data.previewPhase === 'pruning';
    const dropping = pruning && !data.keptFile;
    const entering = data.previewPhase === 'expanding' && data.newFile;
    const keepIds = data.keepIds || new Set();
    const originalIds = data.originalIds || new Set();
    return <article data-prune-target={dropping ? 'file' : undefined} className={`${styles.node} ${selected ? styles.selected : ''} ${data.state === 'Modify' ? styles.modified : ''} ${data.state === 'Staged' ? styles.staged : ''} ${entering ? styles.previewEntering : ''} ${dropping ? styles.previewDropping : ''}`} style={{'--preview-delay': `${data.enterDelay || 0}ms`, '--prune-duration': `${data.pruneDuration || 2000}ms`}}>
        {[Position.Left,Position.Right,Position.Top,Position.Bottom].map(side=><span key={side}>
            <Handle id={`in-${side}`} type="target" position={side} isConnectable={false}/>
            <Handle id={`out-${side}`} type="source" position={side} isConnectable={false}/>
        </span>)}
        {data.previewMode && <div className={styles.previewBadge}>{data.previewPhase === 'final' || data.previewPhase === 'transition' ? '裁剪结果' : data.originalFile ? '原有关联' : '扩展关联'}</div>}
        {(data.reviewMode || selected) && <div className={`${styles.stateStrip} ${data.state === 'Staged' ? styles.confirmedStrip : data.state === 'Modify' ? styles.pendingStrip : ''}`}>
            {data.reviewMode && <span className={styles.stateLabel} title={data.state === 'Staged' ? '已选择保留，尚未创建 Git 提交' : data.state === 'Modify' ? '尚未选择保留；部分提交时会放弃该修改' : '此文件没有待选择修改'}>
                {data.state === 'Staged' ? <><CheckCircleOutlined/>已选择 · 待提交</> : data.state === 'Modify' ? <><ClockCircleOutlined/>待选择修改</> : '未修改'}
            </span>}
            {selected && <span className={styles.viewing}><EyeOutlined/>正在查看</span>}
        </div>}
        <header><img className={styles.fileIcon} src={iconForFile(path)} alt="" draggable={false}/><div><strong title={path}>{path.slice(slash + 1)}</strong><small title={`摘要提供的相对路径：${path}`}>{slash < 0 ? '项目根目录' : path.slice(0, slash + 1)}</small></div></header>
        <div className={`${styles.elements} nowheel`} style={expanded ? {maxHeight:270, overflowY:'auto'} : undefined}>
            {(expanded && !data.previewMode ? elements : elements.slice(0, 5)).map(element => <div key={`${path}::${element}`} data-prune-target={pruning && !dropping && !keepIds.has(`${path}::${element}`) ? 'element' : undefined} title={element} className={pruning ? keepIds.has(`${path}::${element}`) ? styles.elementKept : styles.elementRemoved : data.previewPhase === 'expanding' && !originalIds.has(`${path}::${element}`) ? styles.elementAdded : ''}><i/><code>{element}</code></div>)}
            {!elements.length && <span className={styles.muted}>未提供关联元素</span>}
        </div>
        {elements.length > 5 && <button disabled={data.previewActive} className={`${styles.expand} nodrag nopan`} onClick={event => {event.stopPropagation(); setExpanded(value => !value);}}>{expanded ? '收起元素' : `展开其余 ${elements.length - 5} 个元素`}</button>}
        <footer>{elements.length} 个关联元素 <span>{data.previewMode ? pruning ? `保留 ${elements.filter(name => keepIds.has(`${path}::${name}`)).length} 个` : "关联图谱展示" : data.reviewMode ? "点击审阅差异" : "点击查看源码"}</span></footer>
    </article>;
});
const nodeTypes = {file: FileNode};

const edgeTypes = {reference:ReferenceEdge};
function positions(nodes, viewport, heights) {
    return fileGrid(nodes, viewport, heights).positions;
}

function Canvas({graphData, onNodeClick, onBackgroundClick, searchQuery = '', viewportKey, reviewMode = false, preview = null, layoutReferenceWidth, projectId, onReferenceSnapshot}) {
    const [nodes, setNodes, onNodesChange] = useNodesState([]);
    const [hoveredFile,setHoveredFile] = useState(null);
    const host = useRef(null);
    const [frameSize, setFrameSize] = useState({width:0,height:0});
    useLayoutEffect(() => {
        const element = host.current;
        let frame = null;
        let nextSize;
        // Fix the renderer's dimensions in pixels. ResizeObserver only reads;
        // updates happen on the next animation frame, outside its delivery loop.
        const schedule = (width, height) => {
            if (width <= 0 || height <= 0) return;
            nextSize = {width:Math.max(0, Math.floor(width)),height:Math.max(0, Math.floor(height))};
            if (frame !== null) return;
            frame = requestAnimationFrame(() => {
                frame = null;
                const next = nextSize;
                setFrameSize(current => current.width === next.width && current.height === next.height ? current : next);
            });
        };
        schedule(element.clientWidth, element.clientHeight);
        const observer = new ResizeObserver(entries => {
            const box = entries[0]?.contentRect;
            if (box) schedule(box.width, box.height);
        });
        observer.observe(element);
        return () => {observer.disconnect(); if (frame !== null) cancelAnimationFrame(frame);};
    }, []);
    // Drawer visibility changes the viewport, not the layout reference. Preserve the
    // reference height too: wrapping headers during opening must not reshuffle files.
    const [layoutSize, setLayoutSize] = useState({width:0,height:0,windowHeight:0});
    const windowHeight = window.innerHeight;
    useEffect(() => {
        if (!frameSize.width || !frameSize.height) return;
        const timer = setTimeout(() => setLayoutSize(current => {
            const width = layoutReferenceWidth || frameSize.width;
            const height = current.windowHeight
                ? Math.max(1, current.height + windowHeight - current.windowHeight)
                : frameSize.height;
            if (current.width === width && current.height === height && current.windowHeight === windowHeight) return current;
            return {width,height,windowHeight};
        }), 180);
        return () => clearTimeout(timer);
    }, [frameSize, layoutReferenceWidth, windowHeight]);
    const flow = useReactFlow();
    const {zoom} = useViewport();
    const callbacks = useRef({onNodeClick, onBackgroundClick});
    callbacks.current = {onNodeClick, onBackgroundClick};
    const previewMode = Boolean(preview);
    const previewActive = Boolean(preview?.active);
    const effectiveGraph = preview ? preview.data?.stages[
        ['final','transition'].includes(preview.phase) ? 'pruned' : preview.phase === 'retrieving' ? 'initial' : 'expanded'
    ] || preview.initial : graphData;
    const latest = useRef(effectiveGraph);
    latest.current = effectiveGraph;
    const previewPositions = useRef({id:null,positions:new Map()});
    const structure = JSON.stringify((effectiveGraph?.nodes || []).map(n => [n.id, n.funcFile, n.methods]));
    const references = useFileReferences(projectId, graphData, !previewMode);
    const referenceCandidates = useMemo(()=>previewMode ? effectiveGraph?.edges || [] : references,[previewMode,effectiveGraph?.edges,references]);
    const arrange = useCallback(() => {
        const source = latest.current?.nodes || [];
        const heights = new Map(flow.getNodes().map(node => [node.id, node.measured?.height]));
        const points = positions(source, layoutSize, heights);
        setNodes(current => {
            const old = new Map(current.map(node => [node.id, node]));
            return source.map(n => ({...old.get(String(n.id)),id:String(n.id),type:'file',position:points.get(String(n.id)),
                data:{path:n.funcFile || String(n.id), elements:(n.methods || []).map(String),state:n.type}}));
        });
        requestAnimationFrame(() => flow.fitView({padding:0.1, maxZoom:1, duration:180}));
        // Node layout depends only on node structure, never on arriving edges.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [structure, setNodes, flow, layoutSize]);
    useEffect(() => {if (!previewMode) arrange();}, [arrange, previewMode]);
    useEffect(() => {
        if (!previewMode) return;
        const source = latest.current?.nodes || [];
        const initialFiles = new Set((preview.data?.stages.initial.nodes || preview.initial?.nodes || []).map(node => String(node.id)));
        if (previewPositions.current.id !== preview.id) {
            previewPositions.current = {id:preview.id,positions:new Map(flow.getNodes().filter(node => initialFiles.has(node.id)).map(node => [node.id,node.position]))};
        }
        const cache = extendPositions(source, previewPositions.current.positions, layoutSize);
        previewPositions.current.positions = cache;
        const compact = ['final','transition'].includes(preview.phase);
        const stage = compact ? 'pruned' : preview.phase==='retrieving' ? 'initial' : 'expanded';
        const saved = preview.data?.referenceLayouts?.[stage];
        const finalPositions = saved ? new Map(saved) : compact ? positions(source, layoutSize) : cache;
        setNodes(current => {
            const old = new Map(current.map(node => [node.id,node]));
            return source.map(node => ({...old.get(String(node.id)),id:String(node.id),type:'file',selected:false,
                position:finalPositions.get(String(node.id)),initialWidth:290,initialHeight:280,
                className:styles.previewPosition,
                data:{path:node.funcFile || String(node.id),elements:(node.methods || []).map(String),state:'Default',connected:false}}));
        });
        if (!['retrieving','expanding','final'].includes(preview.phase)) return;
        const timer = setTimeout(() => flow.fitView({padding:.1,maxZoom:1,minZoom:.03,duration:preview.reduced ? 0 : 650}), 140);
        return () => clearTimeout(timer);
        // Stages share node identities and preserve positions; no per-frame React updates.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [previewMode, preview?.id, preview?.phase, preview?.data?.referenceLayouts, structure, setNodes, flow, layoutSize]);
    useEffect(() => {
        if (!preview?.active || !preview.data || !preview.data.referencesLocked || preview.data.referenceDisplay || !onReferenceSnapshot) return;
        const initial = previewPositions.current.positions;
        onReferenceSnapshot(preview.id,referenceSnapshots(preview.data,layoutSize,initial));
    }, [preview,layoutSize,onReferenceSnapshot]);
    useEffect(()=>setHoveredFile(null),[structure,previewActive]);
    useEffect(() => {
        if (previewMode) return;
        const types = new Map((graphData?.nodes || []).map(n => [String(n.id), n.type]));
        setNodes(current => current.map(n => n.data.state === types.get(n.id) ? n : {...n, data:{...n.data,state:types.get(n.id)}}));
    }, [graphData, setNodes, previewMode]);
    useEffect(() => {
        const query = searchQuery.trim().toLowerCase();
        if (!query || previewMode) return;
        const match = (latest.current?.nodes || []).find(n => `${n.funcFile || n.id} ${(n.methods || []).join(' ')}`.toLowerCase().includes(query));
        if (match) flow.fitView({nodes:[{id:String(match.id)}], maxZoom:1, padding:0.5, duration:200});
    }, [searchQuery, flow, previewMode]);
    useEffect(() => {
        if (previewActive) return;
        const timer = setTimeout(() => flow.fitView({padding:0.1,maxZoom:1,duration:180}), 300);
        return () => clearTimeout(timer);
    }, [viewportKey, flow, previewActive, frameSize]);
    const handleNodeClick = (_, node) => {
        if (previewActive) return;
        const original = latest.current?.nodes?.find(n => String(n.id) === node.id);
        const accepted = callbacks.current.onNodeClick?.(original?.id ?? node.id, original);
        if (accepted !== false) setNodes(current => current.map(n => ({...n, selected:n.id === node.id})));
    };
    const clear = () => setNodes(current => current.map(n => ({...n,selected:false})));
    const query = previewMode ? '' : searchQuery.trim().toLowerCase();
    const keepIds = useMemo(() => new Set(preview?.data?.keptElementIds || []), [preview?.data]);
    const keptFiles = useMemo(() => new Set((preview?.data?.stages.pruned.nodes || []).map(node => node.id)), [preview?.data]);
    const initialFiles = useMemo(() => new Set((preview?.data?.stages.initial.nodes || preview?.initial?.nodes || []).map(node => String(node.id))), [preview?.data,preview?.initial]);
    const originalIds = useMemo(() => new Set((preview?.data?.stages.initial.nodes || preview?.initial?.nodes || []).flatMap(node => (node.methods || []).map(name => `${node.id}::${name}`))), [preview?.data,preview?.initial]);
    const points = useMemo(()=>new Map(nodes.map(node=>[node.id,node.position])),[nodes]);
    const stage = ['final','transition'].includes(preview?.phase) ? 'pruned' : preview?.phase==='retrieving' ? 'initial' : 'expanded';
    const selectedReferences = useMemo(()=>preview?.data?.referenceDisplay?.[stage]
        || selectReferences(referenceCandidates,nodes,points),[preview?.data?.referenceDisplay,stage,referenceCandidates,nodes,points]);
    const graphEdges = useMemo(()=>{
        const focus = previewActive ? null : hoveredFile;
        const selected = focusReferences(selectedReferences,referenceCandidates,nodes,points,focus);
        const nodeMap = new Map(nodes.map(node=>[node.id,node]));
        const left = Math.min(...nodes.map(node=>node.position.x))-28;
        const right = Math.max(...nodes.map(node=>node.position.x+290))+28;
        return selected.flatMap(edge=>{
            const source=nodeMap.get(edge.source),target=nodeMap.get(edge.target);
            if (!source || !target) return [];
            const a=source.position,b=target.position,dx=b.x-a.x,dy=b.y-a.y;
            let from,to,detourX;
            if (Math.abs(dy)>380 || (Math.abs(dy)<40 && Math.abs(dx)>420)) {
                from=to=Position.Top;
                detourX = Math.abs(a.x-left)+Math.abs(b.x-left) <= Math.abs(a.x+290-right)+Math.abs(b.x+290-right) ? left : right;
            } else if (Math.abs(dy)>80) {
                from=dy>0 ? Position.Bottom : Position.Top;to=dy>0 ? Position.Top : Position.Bottom;
            } else {
                from=dx>=0 ? Position.Right : Position.Left;to=dx>=0 ? Position.Left : Position.Right;
            }
            const adjacent=focus && (edge.source===focus || edge.target===focus);
            const stroke=adjacent ? '#4f8bc7' : '#91a9c0';
            const marker={type:MarkerType.ArrowClosed,color:stroke,width:12,height:12,orient:'auto-start-reverse'};
            const dropping=preview?.phase==='pruning' && (!keptFiles.has(edge.source)||!keptFiles.has(edge.target));
            return [{...edge,type:'reference',sourceHandle:`out-${from}`,targetHandle:`in-${to}`,
                markerEnd:edge.forward ? marker : undefined,markerStart:edge.backward ? marker : undefined,
                selectable:false,focusable:true,zIndex:0,
                ariaLabel:`文件引用：${edge.source} ${edge.forward && edge.backward ? '与' : edge.forward ? '引用' : '被引用于'} ${edge.target}`,
                style:{stroke,strokeWidth:adjacent ? 1.8 : 1.2,opacity:focus && !adjacent ? .45 : .8},
                data:{evidence:edge.evidence,detourX,dropping,duration:preview?.phaseDuration},
            }];
        });
    },[selectedReferences,referenceCandidates,nodes,points,hoveredFile,previewActive,preview?.phase,preview?.phaseDuration,keptFiles]);
    const shownNodes = useMemo(()=>nodes.map((node,index) => ({...node,
        data:{...node.data,reviewMode:reviewMode && !previewMode,previewMode,previewActive,previewPhase:preview?.phase,pruneDuration:preview?.phaseDuration,
            keepIds,originalIds,originalFile:initialFiles.has(node.id),newFile:!initialFiles.has(node.id),keptFile:keptFiles.has(node.id),
            enterDelay:preview?.reduced ? 0 : Math.min(800,index * 800 / Math.max(1,nodes.length))},
        style:{opacity:!query || `${node.data.path} ${node.data.elements.join(' ')}`.toLowerCase().includes(query) ? 1 : .28},
    })),[nodes,reviewMode,previewMode,previewActive,preview?.phase,preview?.phaseDuration,preview?.reduced,
        keepIds,originalIds,initialFiles,keptFiles,query]);
    return <div ref={host} className={styles.canvas}>
        <div className={styles.viewport} style={frameSize}>
        {frameSize.width > 0 && frameSize.height > 0 && <ReactFlow proOptions={{hideAttribution:true}} nodes={shownNodes} edges={graphEdges} nodeTypes={nodeTypes} edgeTypes={edgeTypes} onNodesChange={changes => onNodesChange(changes.filter(c => c.type !== 'select'))}
            onNodeMouseEnter={(_,node)=>{if(!previewActive)setHoveredFile(node.id);}} onNodeMouseLeave={()=>setHoveredFile(null)}
            onNodeClick={handleNodeClick} onPaneClick={() => {if (!previewActive && callbacks.current.onBackgroundClick?.(clear) !== false) clear();}}
            nodesConnectable={false} nodesDraggable={!previewActive} panOnDrag={!previewActive} zoomOnScroll={!previewActive} zoomOnPinch={!previewActive} zoomOnDoubleClick={!previewActive}
            deleteKeyCode={null} selectionKeyCode={null} nodesFocusable={!previewActive} minZoom={.03} maxZoom={1.8}
            fitView fitViewOptions={{padding:0.1,maxZoom:1}}>
            <Background color="#dbe5f2" gap={24} size={1}/>
            {!previewActive && <Controls showInteractive={false} position="bottom-left" fitViewOptions={{padding:0.1,maxZoom:1}}>
                <ControlButton onClick={arrange} title="整理布局" aria-label="整理布局"><AppstoreOutlined/></ControlButton>
                <div className={styles.zoom}>{Math.round(zoom * 100)}%</div>
            </Controls>}
            <MiniMap style={{width:128,height:86}} pannable={!previewActive} zoomable={!previewActive} nodeColor="#cce2ff" maskColor="rgba(239,245,253,.65)"/>
            {!previewActive && <Panel position="top-left" className={styles.canvasHint}>{query && !shownNodes.some(n => n.style.opacity === 1) ? '没有找到匹配的文件或元素' : '拖动画布平移 · 滚轮缩放'}</Panel>}
        </ReactFlow>}
        </div>
        {previewActive && <GraphPreviewOverlay preview={preview}/>}
        {!nodes.length && !previewActive && <div className={styles.empty}><FileTextOutlined/><strong>暂无代码图谱</strong><span>选择左侧功能项，查看关联文件与代码元素</span></div>}
    </div>;
}
export default function WorkspaceGraph(props) {return <ReactFlowProvider><Canvas {...props}/></ReactFlowProvider>;}
