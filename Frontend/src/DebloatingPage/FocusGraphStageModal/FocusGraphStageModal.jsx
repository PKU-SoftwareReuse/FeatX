import React, {useEffect, useMemo, useRef, useState} from "react";
import {Button, Modal, Segmented, Tag} from "antd";
import {PlayCircleOutlined} from "@ant-design/icons";
import {DataSet, Network} from "vis-network/standalone/esm/vis-network";
import styles from "./FocusGraphStageModal.module.css";

const STAGE_ORDER = ["initial", "expanded", "reasoning"];

const stageLabel = {
    initial: "Initial Graph",
    expanded: "Expanded Graph",
    reasoning: "Reasoning Graph",
};

const categoryColor = (node) => {
    if (node.fadeOut) {
        return {
            background: "rgba(229, 231, 235, 0.28)",
            border: "rgba(148, 163, 184, 0.34)",
            highlight: {background: "rgba(229, 231, 235, 0.36)", border: "rgba(148, 163, 184, 0.42)"},
        };
    }
    if (node.category === "Class") {
        return {
            background: "#fff7e6",
            border: "#d48806",
            highlight: {background: "#ffe7ba", border: "#ad6800"},
        };
    }
    if (node.srcType === "original") {
        return {
            background: "#e6f4ff",
            border: "#1677ff",
            highlight: {background: "#bae0ff", border: "#0958d9"},
        };
    }
    return {
        background: "#f6ffed",
        border: "#52c41a",
        highlight: {background: "#d9f7be", border: "#389e0d"},
    };
};

const shortLabel = (node) => {
    const raw = node.methodSignature || node.label || node.id || "";
    const parts = String(raw).split(".");
    const tail = parts.slice(-2).join(".");
    return tail.length > 34 ? `${tail.slice(0, 31)}...` : tail;
};

const toVisNode = (node, currentStage, previousStage, reasoningIds) => {
    const isAdded = currentStage === "expanded" && previousStage && !previousStage.nodes.some((item) => item.id === node.id);
    const isKept = currentStage === "reasoning" && reasoningIds.has(node.id);
    return {
        id: node.id,
        label: shortLabel(node),
        title: [
            node.methodSignature || node.label || node.id,
            node.funcFile ? `File: ${node.funcFile}` : "",
            node.category ? `Category: ${node.category}` : "",
            node.srcType ? `Source: ${node.srcType}` : "",
            typeof node.score === "number" ? `Score: ${node.score.toFixed(4)}` : "",
        ].filter(Boolean).join("\n"),
        shape: node.category === "Class" ? "box" : "dot",
        size: node.category === "Class" ? 18 : 14,
        borderWidth: isAdded || isKept ? 3 : 1.5,
        color: categoryColor(node),
        font: {
            size: 12,
            face: "Inter, Arial, sans-serif",
            color: node.fadeOut ? "rgba(71, 85, 105, 0.55)" : "#1f2937",
        },
    };
};

const toVisEdge = (edge, fadeOut = false) => ({
    id: `${edge.from}->${edge.to}:${edge.type || "rel"}`,
    from: edge.from,
    to: edge.to,
    arrows: "to",
    label: edge.type || "",
    color: fadeOut ? {color: "rgba(148, 163, 184, 0.24)"} : {color: "#64748b"},
    font: {size: 10, color: fadeOut ? "rgba(100, 116, 139, 0.38)" : "#64748b", strokeWidth: 0},
    smooth: {type: "dynamic"},
});

const normalizeStage = (stage) => ({
    ...stage,
    nodes: Array.isArray(stage?.nodes) ? stage.nodes : [],
    edges: Array.isArray(stage?.edges) ? stage.edges : [],
});

const FocusGraphStageModal = ({open, onClose, stages}) => {
    const containerRef = useRef(null);
    const networkRef = useRef(null);
    const nodesRef = useRef(null);
    const edgesRef = useRef(null);
    const timersRef = useRef([]);
    const [activeStageId, setActiveStageId] = useState("initial");

    const stageMap = useMemo(() => {
        const map = new Map();
        (stages || []).forEach((stage) => {
            const normalized = normalizeStage(stage);
            if (normalized.id) {
                map.set(normalized.id, normalized);
            }
        });
        return map;
    }, [stages]);

    const orderedStages = useMemo(
        () => STAGE_ORDER.map((id) => stageMap.get(id)).filter(Boolean),
        [stageMap]
    );

    const activeStage = stageMap.get(activeStageId) || orderedStages[0];
    const activeIndex = orderedStages.findIndex((stage) => stage.id === activeStage?.id);

    const clearTimers = () => {
        timersRef.current.forEach((timer) => clearTimeout(timer));
        timersRef.current = [];
    };

    const createNetwork = () => {
        if (!containerRef.current || networkRef.current) {
            return;
        }
        nodesRef.current = new DataSet([]);
        edgesRef.current = new DataSet([]);
        networkRef.current = new Network(
            containerRef.current,
            {nodes: nodesRef.current, edges: edgesRef.current},
            {
                autoResize: true,
                nodes: {
                    shadow: false,
                    margin: 8,
                },
                edges: {
                    width: 1.2,
                    selectionWidth: 1.5,
                },
                physics: {
                    enabled: true,
                    stabilization: {iterations: 120},
                    barnesHut: {
                        gravitationalConstant: -6500,
                        centralGravity: 0.22,
                        springLength: 110,
                        springConstant: 0.04,
                    },
                },
                interaction: {
                    hover: true,
                    tooltipDelay: 80,
                    navigationButtons: true,
                    keyboard: false,
                },
            }
        );
    };

    const setGraph = (stage, previousStage = null) => {
        if (!stage || !nodesRef.current || !edgesRef.current) {
            return;
        }
        const reasoningStage = stageMap.get("reasoning");
        const reasoningIds = new Set((reasoningStage?.nodes || []).map((node) => node.id));
        const nodeItems = stage.nodes.map((node) => toVisNode(node, stage.id, previousStage, reasoningIds));
        const edgeItems = stage.edges.map((edge) => toVisEdge(edge));
        nodesRef.current.clear();
        edgesRef.current.clear();
        nodesRef.current.add(nodeItems);
        edgesRef.current.add(edgeItems);
        networkRef.current?.fit({animation: {duration: 450, easingFunction: "easeInOutQuad"}});
    };

    const playReasoningTransition = () => {
        const expanded = stageMap.get("expanded");
        const reasoning = stageMap.get("reasoning");
        if (!expanded || !reasoning || !nodesRef.current || !edgesRef.current) {
            return;
        }
        const reasoningIds = new Set(reasoning.nodes.map((node) => node.id));
        const reasoningEdgeIds = new Set(reasoning.edges.map((edge) => `${edge.from}->${edge.to}:${edge.type || "rel"}`));

        const fadingNodes = expanded.nodes
            .filter((node) => !reasoningIds.has(node.id))
            .map((node) => toVisNode({...node, fadeOut: true}, "reasoning", expanded, reasoningIds));
        if (fadingNodes.length) {
            nodesRef.current.update(fadingNodes);
        }
        const fadingEdges = expanded.edges
            .filter((edge) => !reasoningEdgeIds.has(`${edge.from}->${edge.to}:${edge.type || "rel"}`))
            .map((edge) => toVisEdge(edge, true));
        if (fadingEdges.length) {
            edgesRef.current.update(fadingEdges);
        }

        timersRef.current.push(setTimeout(() => {
            setGraph(reasoning, expanded);
            setActiveStageId("reasoning");
        }, 900));
    };

    const playAnimation = () => {
        clearTimers();
        const initial = stageMap.get("initial");
        const expanded = stageMap.get("expanded");
        const reasoning = stageMap.get("reasoning");
        if (!initial || !expanded || !reasoning) {
            setGraph(orderedStages[0]);
            return;
        }
        setActiveStageId("initial");
        setGraph(initial);
        timersRef.current.push(setTimeout(() => {
            setActiveStageId("expanded");
            setGraph(expanded, initial);
        }, 900));
        timersRef.current.push(setTimeout(() => {
            playReasoningTransition();
        }, 2100));
    };

    useEffect(() => {
        if (!open) {
            clearTimers();
            return undefined;
        }
        createNetwork();
        playAnimation();
        return () => clearTimers();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [open, orderedStages.length]);

    useEffect(() => {
        return () => {
            clearTimers();
            networkRef.current?.destroy();
            networkRef.current = null;
        };
    }, []);

    const handleStageChange = (value) => {
        clearTimers();
        setActiveStageId(value);
        const nextStage = stageMap.get(value);
        const previousIndex = STAGE_ORDER.indexOf(value) - 1;
        const previousStage = previousIndex >= 0 ? stageMap.get(STAGE_ORDER[previousIndex]) : null;
        setGraph(nextStage, previousStage);
    };

    return (
        <Modal
            title="FocusGraph Stages"
            open={open}
            onCancel={onClose}
            footer={null}
            width={980}
            destroyOnClose={false}
        >
            <div className={styles.toolbar}>
                <Segmented
                    value={activeStage?.id || "initial"}
                    onChange={handleStageChange}
                    options={orderedStages.map((stage) => ({
                        label: stage.label || stageLabel[stage.id] || stage.id,
                        value: stage.id,
                    }))}
                />
                <Button icon={<PlayCircleOutlined/>} onClick={playAnimation}>
                    Replay
                </Button>
            </div>

            <div className={styles.metaRow}>
                <Tag color={activeStage?.id === "reasoning" ? "blue" : activeStage?.id === "expanded" ? "green" : "geekblue"}>
                    {activeStage?.label || stageLabel[activeStage?.id] || activeStage?.id}
                </Tag>
                <span>{activeStage?.description}</span>
                <span className={styles.counts}>
                    Nodes {activeStage?.nodes?.length || 0} · Edges {activeStage?.edges?.length || 0}
                </span>
            </div>

            <div className={styles.legend}>
                <span><i className={styles.seedDot}/> seed/original method</span>
                <span><i className={styles.expandDot}/> one-hop expanded method</span>
                <span><i className={styles.classDot}/> class node</span>
                <span><i className={styles.fadeDot}/> filtered out during reasoning</span>
            </div>

            <div ref={containerRef} className={styles.graphCanvas}/>

            <div className={styles.stageList}>
                {orderedStages.map((stage, index) => (
                    <div
                        key={stage.id}
                        className={`${styles.stageCard} ${index === activeIndex ? styles.stageCardActive : ""}`}
                    >
                        <strong>{index + 1}. {stage.label || stageLabel[stage.id] || stage.id}</strong>
                        <span>{stage.nodes.length} nodes, {stage.edges.length} edges</span>
                    </div>
                ))}
            </div>
        </Modal>
    );
};

export default FocusGraphStageModal;
