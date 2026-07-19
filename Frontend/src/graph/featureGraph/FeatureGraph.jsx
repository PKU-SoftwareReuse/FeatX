// FeatureGraph.jsx
import React, {useEffect, useRef} from 'react';
import styles from './FeatureGraph.module.css';
import {DataSet, Network} from 'vis-network/standalone/esm/vis-network';
import GraphOption from "../GraphOption";

const FeatureGraph = ({graphData, onNodeClick, onBackgroundClick, nodeFontSize}) => {
    const containerRef = useRef(null);
    const networkRef = useRef(null);
    const lastSelectedNodeRef = useRef(null);
    const onNodeClickRef = useRef(onNodeClick);
    const onBackgroundClickRef = useRef(onBackgroundClick);

    onNodeClickRef.current = onNodeClick;
    onBackgroundClickRef.current = onBackgroundClick;

    useEffect(() => {
        if (containerRef.current && graphData && graphData.nodes.length > 0) {
            const nodes = graphData.nodes.map(node => {
                const funcList = Array.isArray(node.methods)
                    ? node.methods.join('\n')
                    : node.methods;
                return {
                    id: node.id,
                    label: `${node.id}\n——————————\n${funcList}`,
                    // ✅❌
                    color: {
                        background: GraphOption.typeColors[node.type]?.background || '#DDD',
                        border: GraphOption.typeColors[node.type]?.border || '#999',
                        hover: {
                            background: GraphOption.typeColors[node.type]?.background || '#DDD',
                            border: GraphOption.typeColors[node.type]?.border || '#999',
                        },
                        highlight: {
                            background: GraphOption.typeColors[node.type]?.highlight || '#BBB',
                            border: GraphOption.typeColors[node.type]?.border || '#999',
                        }
                    }
                }
            });

            const edges = graphData.edges.map(edge => ({
                from: edge.from,
                to: edge.to,
                arrows: 'to',
            }));

            const data = {
                nodes: new DataSet(nodes),
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
            lastSelectedNodeRef.current = null;

            network.on('click', (params) => {
                if (params.nodes?.length > 0) {
                    const nodeId = params.nodes[0];
                    const previousNodeId = lastSelectedNodeRef.current;
                    const accepted = onNodeClickRef.current?.(nodeId);
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
        };
    }, [graphData, nodeFontSize]);

    return (
        <div
            ref={containerRef} className={styles.graphArea}
        />
    );
};

export default FeatureGraph;
