import {memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState} from 'react';
import {ReactFlow, Background, Controls, ControlButton, Handle, MarkerType, MiniMap, Panel, Position, ReactFlowProvider, useReactFlow, useNodesState, useViewport} from '@xyflow/react';
import {AppstoreOutlined, FileTextOutlined, CheckCircleOutlined, ClockCircleOutlined, EyeOutlined} from '@ant-design/icons';
import '@xyflow/react/dist/style.css';
import styles from './WorkspaceGraph.module.css';
import {iconForFile} from '../../utils/fileIcon';
import GraphPreviewOverlay from '../GraphPreview/GraphPreviewOverlay';

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
        {data.connected && <><Handle type="target" position={Position.Left} isConnectable={false}/><Handle type="source" position={Position.Right} isConnectable={false}/></>}
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

function positions(nodes, edges) {
    // Display layout only; never create semantic relationships.
    if (!edges.length) {
        const columns = nodes.length <= 4 ? 2 : 3;
        return new Map(nodes.map((n, i) => [String(n.id), {x:(i % columns) * 370, y:Math.floor(i / columns) * 330}]));
    }
    const incoming = new Map(nodes.map(n => [String(n.id), 0]));
    const next = new Map(nodes.map(n => [String(n.id), []]));
    edges.forEach(e => {if (incoming.has(e.target) && next.has(e.source)) {incoming.set(e.target, incoming.get(e.target) + 1); next.get(e.source).push(e.target);}});
    const queue = [...incoming.keys()].filter(id => incoming.get(id) === 0);
    const level = new Map(queue.map(id => [id, 0]));
    for (let i = 0; i < queue.length; i++) for (const target of next.get(queue[i])) {
        level.set(target, Math.max(level.get(target) || 0, level.get(queue[i]) + 1));
        incoming.set(target, incoming.get(target) - 1);
        if (!incoming.get(target)) queue.push(target);
    }
    const counts = new Map();
    return new Map(nodes.map(n => {
        const column = level.get(String(n.id)) || 0;
        const row = counts.get(column) || 0; counts.set(column, row + 1);
        return [String(n.id), {x:column * 430, y:row * 330}];
    }));
}

function Canvas({graphData, onNodeClick, onBackgroundClick, searchQuery = '', viewportKey, reviewMode = false, preview = null}) {
    const [nodes, setNodes, onNodesChange] = useNodesState([]);
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
    const structure = JSON.stringify({nodes:(effectiveGraph?.nodes || []).map(n => [n.id, n.funcFile, n.methods]), edges:effectiveGraph?.edges || []});
    const graphEdges = useMemo(() => {
        const ids = new Set((latest.current?.nodes || []).map(n => String(n.id)));
        return (latest.current?.edges || []).map((e, i) => ({
            id:String(e.id ?? `edge-${i}`), source:String(e.source ?? e.from), target:String(e.target ?? e.to),
            label:e.label || e.relation || e.type || undefined, type:'default',
            markerEnd:{type:MarkerType.ArrowClosed, color:'#8caed9'}, style:{stroke:'#8caed9',strokeWidth:1.5},
            labelStyle:{fill:'#627b9b',fontSize:11}, labelBgStyle:{fill:'#f5f9ff'},
        })).filter(e => ids.has(e.source) && ids.has(e.target));
        // Only structural changes rebuild display edges.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [structure]);
    const arrange = useCallback(() => {
        const source = latest.current?.nodes || [];
        const points = positions(source, graphEdges);
        const connected = new Set(graphEdges.flatMap(e => [e.source, e.target]));
        setNodes(source.map(n => ({id:String(n.id),type:'file',position:points.get(String(n.id)),
            data:{path:n.funcFile || String(n.id), elements:(n.methods || []).map(String),state:n.type, connected:connected.has(String(n.id))}})));
        requestAnimationFrame(() => flow.fitView({padding:0.2, maxZoom:1, duration:180}));
    }, [graphEdges, setNodes, flow]);
    useEffect(() => {if (!previewMode) arrange();}, [arrange, previewMode]);
    useEffect(() => {
        if (!previewMode) return;
        const source = latest.current?.nodes || [];
        const initialFiles = new Set((preview.data?.stages.initial.nodes || preview.initial?.nodes || []).map(node => String(node.id)));
        if (previewPositions.current.id !== preview.id) {
            previewPositions.current = {id:preview.id,positions:new Map(flow.getNodes().filter(node => initialFiles.has(node.id)).map(node => [node.id,node.position]))};
        }
        const cache = previewPositions.current.positions;
        const columns = source.length <= 4 ? 2 : Math.max(2, Math.ceil(Math.sqrt(source.length * 1.4)));
        let slot = 0;
        for (const node of source) {
            const id = String(node.id);
            if (cache.has(id)) continue;
            let point, collision;
            do {
                point = {x:(slot % columns) * 370,y:Math.floor(slot / columns) * 330}; slot++;
                collision = false;
                for (const occupied of cache.values()) {
                    if (Math.abs(occupied.x - point.x) < 330 && Math.abs(occupied.y - point.y) < 290) {collision = true;break;}
                }
            } while (collision);
            cache.set(id, point);
        }
        const compact = ['final','transition'].includes(preview.phase);
        const finalPositions = compact ? positions(source, []) : cache;
        setNodes(current => {
            const old = new Map(current.map(node => [node.id,node]));
            return source.map(node => ({...old.get(String(node.id)),id:String(node.id),type:'file',selected:false,
                position:finalPositions.get(String(node.id)),initialWidth:290,initialHeight:280,
                className:styles.previewPosition,
                data:{path:node.funcFile || String(node.id),elements:(node.methods || []).map(String),state:'Default',connected:false}}));
        });
        if (!['retrieving','expanding','final'].includes(preview.phase)) return;
        const timer = setTimeout(() => flow.fitView({padding:.18,maxZoom:1,minZoom:.03,duration:preview.reduced ? 0 : 650}), 140);
        return () => clearTimeout(timer);
        // Stages share node identities and preserve positions; no per-frame React updates.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [previewMode, preview?.id, preview?.phase, structure, setNodes, flow]);
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
        const timer = setTimeout(() => flow.fitView({padding:0.2,maxZoom:1,duration:180}), 300);
        return () => clearTimeout(timer);
    }, [viewportKey, flow, previewActive]);
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
    const shownNodes = nodes.map((node,index) => ({...node,
        data:{...node.data,reviewMode:reviewMode && !previewMode,previewMode,previewActive,previewPhase:preview?.phase,pruneDuration:preview?.phaseDuration,
            keepIds,originalIds,originalFile:initialFiles.has(node.id),newFile:!initialFiles.has(node.id),keptFile:keptFiles.has(node.id),
            enterDelay:preview?.reduced ? 0 : Math.min(800,index * 800 / Math.max(1,nodes.length))},
        style:{opacity:!query || `${node.data.path} ${node.data.elements.join(' ')}`.toLowerCase().includes(query) ? 1 : .28},
    }));
    return <div ref={host} className={styles.canvas}>
        <div className={styles.viewport} style={frameSize}>
        {frameSize.width > 0 && frameSize.height > 0 && <ReactFlow proOptions={{hideAttribution:true}} nodes={shownNodes} edges={graphEdges} nodeTypes={nodeTypes} onNodesChange={changes => onNodesChange(changes.filter(c => c.type !== 'select'))}
            onNodeClick={handleNodeClick} onPaneClick={() => {if (!previewActive && callbacks.current.onBackgroundClick?.(clear) !== false) clear();}}
            nodesConnectable={false} nodesDraggable={!previewActive} panOnDrag={!previewActive} zoomOnScroll={!previewActive} zoomOnPinch={!previewActive} zoomOnDoubleClick={!previewActive}
            deleteKeyCode={null} selectionKeyCode={null} nodesFocusable={!previewActive} minZoom={previewMode ? .03 : .15} maxZoom={1.8}
            fitView fitViewOptions={{padding:0.2,maxZoom:1}}>
            <Background color="#dbe5f2" gap={24} size={1}/>
            {!previewActive && <Controls showInteractive={false} position="bottom-left" fitViewOptions={{padding:0.2,maxZoom:1}}>
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
