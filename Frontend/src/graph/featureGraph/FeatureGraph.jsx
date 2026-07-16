// FeatureGraph.jsx
import React, {useEffect, useRef, forwardRef, useImperativeHandle} from 'react';
import styles from './FeatureGraph.module.css';
import {DataSet, Network} from 'vis-network/standalone/esm/vis-network';
import GraphOption from "../GraphOption";

const FeatureGraph = forwardRef(({graphData, onNodeClick}, ref) => {
    const containerRef = useRef(null);
    const networkRef = useRef(null);
    const dataRef = useRef(null); // 保存 DataSet

    const lastSelectedNodeRef = useRef(null); // 记录上一次选中的节点 ID

    const addClickHandler = (networkInstance, dataset) => {
        // 点击节点后展示更多信息
        networkInstance.current.on('click', function (params) {
            const nodeId = params.nodes[0];
            const edgeId = params.edges[0];
            if (nodeId) {
                const selectedNode = dataset.nodes.get(nodeId); // 正确获取节点的方式
                onNodeClick(nodeId);
                lastSelectedNodeRef.current = nodeId; // 更新记录
            }else {
                // 点击空白 → 恢复上一次选中的节点
                if (lastSelectedNodeRef.current) {
                    networkInstance.current.selectNodes([lastSelectedNodeRef.current]);
                }
            }
        });
    }

    useImperativeHandle(ref, () => ({
        selectRandomNode:()=>{
            if(dataRef.current && dataRef.current.nodes.length > 0 && networkRef.current) {
                const allNodeIds = dataRef.current.nodes.getIds()
                const randomNodeId = allNodeIds[Math.floor(Math.random() * allNodeIds.length)];
                networkRef.current.selectNodes([randomNodeId]);
                onNodeClick(randomNodeId);
                lastSelectedNodeRef.current = randomNodeId; // 更新记录
            }
        }
    }))

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
            dataRef.current = data; // 保存起来

            networkRef.current = new Network(containerRef.current, data, GraphOption.options);

            addClickHandler(networkRef, data);
            const firstNodeId = nodes[0]?.id;
            if (firstNodeId) {
                networkRef.current.selectNodes([firstNodeId]);
                onNodeClick(firstNodeId);
                lastSelectedNodeRef.current = firstNodeId;
            }
        } else {
            dataRef.current = null;
        }

        return () => {
            networkRef.current?.destroy();
            networkRef.current = null;
        };
    }, [graphData]);

    return (
        <div
            ref={containerRef} className={styles.graphArea}
        />
    );
});

export default FeatureGraph;
