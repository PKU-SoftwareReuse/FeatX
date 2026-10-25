import {memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState} from 'react';
import {ReactFlow, Background, Controls, ControlButton, Handle, MarkerType, MiniMap, Panel, Position, ReactFlowProvider, useReactFlow, useNodesState, useViewport} from '@xyflow/react';
import {AppstoreOutlined, FileTextOutlined} from '@ant-design/icons';
import '@xyflow/react/dist/style.css';
import styles from './WorkspaceGraph.module.css';
import {iconForFile} from '../../utils/fileIcon';

const FileNode = memo(({data, selected}) => {
    const [expanded, setExpanded] = useState(false);
    const path = data.path;
    const slash = path.lastIndexOf('/');
    const elements = data.elements;
    return <article className={`${styles.node} ${selected ? styles.selected : ''} ${data.state === 'Modify' ? styles.modified : ''} ${data.state === 'Staged' ? styles.staged : ''}`}>
        {data.connected && <><Handle type="target" position={Position.Left} isConnectable={false}/><Handle type="source" position={Position.Right} isConnectable={false}/></>}
        <header><img className={styles.fileIcon} src={iconForFile(path)} alt="" draggable={false}/><div><strong title={path}>{path.slice(slash + 1)}</strong><small title={`摘要提供的相对路径：${path}`}>{slash < 0 ? '项目根目录' : path.slice(0, slash + 1)}</small></div></header>
        <div className={`${styles.elements} nowheel`} style={expanded ? {maxHeight:270, overflowY:'auto'} : undefined}>
            {(expanded ? elements : elements.slice(0, 5)).map((element, index) => <div key={`${element}-${index}`} title={element}><i/><code>{element}</code></div>)}
            {!elements.length && <span className={styles.muted}>未提供关联元素</span>}
        </div>
        {elements.length > 5 && <button className={`${styles.expand} nodrag nopan`} onClick={event => {event.stopPropagation(); setExpanded(value => !value);}}>{expanded ? '收起元素' : `展开其余 ${elements.length - 5} 个元素`}</button>}
        <footer>{elements.length} 个关联元素 <span>点击查看源码</span></footer>
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

function Canvas({graphData, onNodeClick, onBackgroundClick, searchQuery = '', viewportKey}) {
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
    const latest = useRef(graphData);
    latest.current = graphData;
    const structure = JSON.stringify({nodes:(graphData?.nodes || []).map(n => [n.id, n.funcFile, n.methods]), edges:graphData?.edges || []});
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
    useEffect(() => {arrange();}, [arrange]);
    useEffect(() => {
        const types = new Map((graphData?.nodes || []).map(n => [String(n.id), n.type]));
        setNodes(current => current.map(n => n.data.state === types.get(n.id) ? n : {...n, data:{...n.data,state:types.get(n.id)}}));
    }, [graphData, setNodes]);
    useEffect(() => {
        const query = searchQuery.trim().toLowerCase();
        if (!query) return;
        const match = (latest.current?.nodes || []).find(n => `${n.funcFile || n.id} ${(n.methods || []).join(' ')}`.toLowerCase().includes(query));
        if (match) flow.fitView({nodes:[{id:String(match.id)}], maxZoom:1, padding:0.5, duration:200});
    }, [searchQuery, flow]);
    useEffect(() => {
        const timer = setTimeout(() => flow.fitView({padding:0.2,maxZoom:1,duration:180}), 300);
        return () => clearTimeout(timer);
    }, [viewportKey, flow]);
    const handleNodeClick = (_, node) => {
        const original = latest.current?.nodes?.find(n => String(n.id) === node.id);
        const accepted = callbacks.current.onNodeClick?.(original?.id ?? node.id, original);
        if (accepted !== false) setNodes(current => current.map(n => ({...n, selected:n.id === node.id})));
    };
    const clear = () => setNodes(current => current.map(n => ({...n,selected:false})));
    const query = searchQuery.trim().toLowerCase();
    const shownNodes = nodes.map(n => ({...n, style:{opacity:!query || `${n.data.path} ${n.data.elements.join(' ')}`.toLowerCase().includes(query) ? 1 : 0.28}}));
    return <div ref={host} className={styles.canvas}>
        <div className={styles.viewport} style={frameSize}>
        {frameSize.width > 0 && frameSize.height > 0 && <ReactFlow proOptions={{hideAttribution:true}} nodes={shownNodes} edges={graphEdges} nodeTypes={nodeTypes} onNodesChange={changes => onNodesChange(changes.filter(c => c.type !== 'select'))}
            onNodeClick={handleNodeClick} onPaneClick={() => {if (callbacks.current.onBackgroundClick?.(clear) !== false) clear();}}
            nodesConnectable={false} deleteKeyCode={null} selectionKeyCode={null} nodesFocusable minZoom={0.15} maxZoom={1.8}
            fitView fitViewOptions={{padding:0.2,maxZoom:1}}>
            <Background color="#dbe5f2" gap={24} size={1}/>
            <Controls showInteractive={false} position="bottom-left" fitViewOptions={{padding:0.2,maxZoom:1}}>
                <ControlButton onClick={arrange} title="整理布局" aria-label="整理布局"><AppstoreOutlined/></ControlButton>
                <div className={styles.zoom}>{Math.round(zoom * 100)}%</div>
            </Controls>
            <MiniMap style={{width:128,height:86}} pannable zoomable nodeColor="#cce2ff" maskColor="rgba(239,245,253,.65)"/>
            <Panel position="top-left" className={styles.canvasHint}>{query && !shownNodes.some(n => n.style.opacity === 1) ? '没有找到匹配的文件或元素' : '拖动画布平移 · 滚轮缩放'}</Panel>
        </ReactFlow>}
        </div>
        {!nodes.length && <div className={styles.empty}><FileTextOutlined/><strong>暂无代码图谱</strong><span>选择左侧功能项，查看关联文件与代码元素</span></div>}
    </div>;
}
export default function WorkspaceGraph(props) {return <ReactFlowProvider><Canvas {...props}/></ReactFlowProvider>;}
