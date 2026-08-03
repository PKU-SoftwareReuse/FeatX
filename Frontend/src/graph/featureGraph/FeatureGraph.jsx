// FeatureGraph.jsx
import React, {useEffect, useMemo, useRef} from 'react';
import styles from './FeatureGraph.module.css';
import {DataSet, Network} from 'vis-network/standalone/esm/vis-network';
import GraphOption from "../GraphOption";

const nodeColor = (type) => ({
    background: GraphOption.typeColors[type]?.background || '#DDD',
    border: GraphOption.typeColors[type]?.border || '#999',
    hover: {
        background: GraphOption.typeColors[type]?.background || '#DDD',
        border: GraphOption.typeColors[type]?.border || '#999',
    },
    highlight: {
        background: GraphOption.typeColors[type]?.highlight || '#BBB',
        border: GraphOption.typeColors[type]?.border || '#999',
    },
});

const methodDisplayName = (signature) => {
    const qualifiedName = String(signature || '').split('(', 1)[0].trim();
    if (!qualifiedName) return '';
    const separator = qualifiedName.lastIndexOf('.');
    const methodName = separator >= 0 ? qualifiedName.slice(separator + 1) : qualifiedName;
    if (methodName !== '<init>') return methodName;

    const owner = separator >= 0 ? qualifiedName.slice(0, separator) : '';
    const ownerSeparator = owner.lastIndexOf('.');
    return owner ? owner.slice(ownerSeparator + 1) : methodName;
};

const displayedMethods = (methods) => {
    const values = Array.isArray(methods)
        ? methods
        : String(methods || '').split('\n');
    return [...new Set(values.map(methodDisplayName).filter(Boolean))];
};

const graphStructure = (graphData) => JSON.stringify({
    nodes: (graphData?.nodes || []).map((node) => ({
        id: node.id,
        methods: Array.isArray(node.methods) ? node.methods : node.methods || '',
    })),
    edges: (graphData?.edges || []).map((edge) => ({
        from: edge.from,
        to: edge.to,
    })),
});

const FeatureGraph = ({graphData, onNodeClick, onBackgroundClick, nodeFontSize}) => {
    const containerRef = useRef(null);
    const networkRef = useRef(null);
    const nodesDataSetRef = useRef(null);
    const nodeTypesRef = useRef(new Map());
    const graphDataRef = useRef(graphData);
    const lastSelectedNodeRef = useRef(null);
    const onNodeClickRef = useRef(onNodeClick);
    const onBackgroundClickRef = useRef(onBackgroundClick);
    const structureKey = useMemo(() => graphStructure(graphData), [graphData]);

    graphDataRef.current = graphData;
    onNodeClickRef.current = onNodeClick;
    onBackgroundClickRef.current = onBackgroundClick;

    useEffect(() => {
        const currentGraphData = graphDataRef.current;
        if (containerRef.current && currentGraphData && currentGraphData.nodes.length > 0) {
            const nodes = currentGraphData.nodes.map(node => {
                const funcList = displayedMethods(node.methods).join('\n');
                return {
                    id: node.id,
                    label: `${node.id}\n——————————\n${funcList}`,
                    color: nodeColor(node.type),
                };
            });

            const edges = currentGraphData.edges.map(edge => ({
                from: edge.from,
                to: edge.to,
                arrows: 'to',
            }));

            const nodesDataSet = new DataSet(nodes);
            const data = {
                nodes: nodesDataSet,
                edges: new DataSet(edges),
            };
            const baseOptions = nodeFontSize
                ? {
                    ...GraphOption.options,
                    nodes: {
                        ...GraphOption.options.nodes,
                        font: {
                            ...GraphOption.options.nodes.font,
                            size: nodeFontSize,
                        },
                    },
                }
                : GraphOption.options;
            const reduceMotion = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
            const networkOptions = reduceMotion
                ? {...baseOptions, physics: {enabled: false}}
                : baseOptions;
            const network = new Network(containerRef.current, data, networkOptions);
            networkRef.current = network;
            nodesDataSetRef.current = nodesDataSet;
            nodeTypesRef.current = new Map(currentGraphData.nodes.map((node) => [String(node.id), node.type]));
            lastSelectedNodeRef.current = null;

            network.on('click', (params) => {
                if (params.nodes?.length > 0) {
                    const nodeId = params.nodes[0];
                    const graphNode = graphDataRef.current?.nodes?.find(
                        (node) => String(node.id) === String(nodeId)
                    );
                    const previousNodeId = lastSelectedNodeRef.current;
                    const accepted = onNodeClickRef.current?.(nodeId, graphNode);
                    if (accepted === false) {
                        if (previousNodeId != null) {
                            network.selectNodes([previousNodeId]);
                        } else {
                            network.unselectAll();
                        }
                        return;
                    }
                    lastSelectedNodeRef.current = nodeId;
                    return;
                }

                const clearSelection = () => {
                    network.unselectAll();
                    lastSelectedNodeRef.current = null;
                };
                const shouldClear = onBackgroundClickRef.current?.(clearSelection);
                if (shouldClear === false) {
                    if (lastSelectedNodeRef.current != null) {
                        network.selectNodes([lastSelectedNodeRef.current]);
                    }
                    return;
                }
                clearSelection();
            });
        } else {
            lastSelectedNodeRef.current = null;
        }

        return () => {
            networkRef.current?.destroy();
            networkRef.current = null;
            nodesDataSetRef.current = null;
            nodeTypesRef.current = new Map();
        };
    }, [structureKey, nodeFontSize]);

    useEffect(() => {
        const network = networkRef.current;
        const nodesDataSet = nodesDataSetRef.current;
        if (!network || !nodesDataSet || !graphData?.nodes?.length) return;

        const changedNodes = graphData.nodes.filter(
            (node) => nodeTypesRef.current.get(String(node.id)) !== node.type
        );
        if (changedNodes.length === 0) return;

        const nodeIds = graphData.nodes.map((node) => node.id);
        const nodeIdsByKey = new Map(nodeIds.map((nodeId) => [String(nodeId), nodeId]));
        const positions = network.getPositions(nodeIds);
        nodesDataSet.update(changedNodes.map((node) => ({
            id: node.id,
            color: nodeColor(node.type),
        })));
        nodeTypesRef.current = new Map(graphData.nodes.map((node) => [String(node.id), node.type]));

        Object.entries(positions || {}).forEach(([nodeId, position]) => {
            network.moveNode(nodeIdsByKey.get(nodeId) ?? nodeId, position.x, position.y);
        });
        network.stopSimulation();
        network.redraw();
    }, [graphData]);

    return (
        <div
            ref={containerRef} className={styles.graphArea}
        />
    );
};

export default FeatureGraph;
