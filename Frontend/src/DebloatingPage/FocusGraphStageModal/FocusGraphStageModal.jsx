import React, {useEffect, useMemo, useRef, useState} from "react";
import {Button, Modal, Segmented, Tag} from "antd";
import {PlayCircleOutlined} from "@ant-design/icons";
import {DataSet, Network} from "vis-network/standalone/esm/vis-network";
import styles from "./FocusGraphStageModal.module.css";

const STAGE_ORDER = ["initial", "expanded", "reasoning"];

const INITIAL_HOLD_MS = 1200;
const EXPAND_NODE_STAGGER_MS = 160;
const EXPAND_SETTLE_MS = 850;
const FILTER_FADE_DURATION_MS = 1800;
const FILTER_FADE_FRAMES = 24;
const FILTER_REMOVE_PAUSE_MS = 90;
const MIN_NODE_DISTANCE = 96;
const GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5));
const EXPANDED_VIEW_PADDING = 96;

const stageLabel = {
    initial: "Initial Graph",
    expanded: "Expanded Graph",
    reasoning: "Reasoning Graph",
};

const rgba = ([r, g, b], alpha = 1) => `rgba(${r}, ${g}, ${b}, ${alpha})`;

const paletteFor = (node) => {
    if (node.fadeOut) {
        return {
            background: [229, 231, 235],
            border: [148, 163, 184],
            highlightBackground: [229, 231, 235],
            highlightBorder: [148, 163, 184],
        };
    }
    if (node.category === "Class") {
        return {
            background: [255, 247, 230],
            border: [212, 136, 6],
            highlightBackground: [255, 231, 186],
            highlightBorder: [173, 104, 0],
        };
    }
    if (node.srcType === "original") {
        return {
            background: [230, 244, 255],
            border: [22, 119, 255],
            highlightBackground: [186, 224, 255],
            highlightBorder: [9, 88, 217],
        };
    }
    return {
        background: [246, 255, 237],
        border: [82, 196, 26],
        highlightBackground: [217, 247, 190],
        highlightBorder: [56, 158, 13],
    };
};

const categoryColor = (node, opacity = 1) => {
    const palette = paletteFor(node);
    const safeOpacity = Math.max(0.05, Math.min(1, opacity));
    const borderOpacity = node.fadeOut ? Math.max(0.18, safeOpacity * 0.65) : Math.max(0.2, safeOpacity);
    return {
        background: rgba(palette.background, node.fadeOut ? safeOpacity * 0.5 : safeOpacity),
        border: rgba(palette.border, borderOpacity),
        highlight: {
            background: rgba(palette.highlightBackground, safeOpacity),
            border: rgba(palette.highlightBorder, borderOpacity),
        },
    };
};

const shortLabel = (node) => {
    const raw = node.methodSignature || node.label || node.id || "";
    const parts = String(raw).split(".");
    const tail = parts.slice(-2).join(".");
    return tail.length > 34 ? `${tail.slice(0, 31)}...` : tail;
};

const edgeId = (edge) => `${edge.from}->${edge.to}:${edge.type || "rel"}`;

const positionOrZero = (position) => position || {x: 0, y: 0};

const toVisNode = (node, options = {}) => {
    const {
        stageId,
        previousStage,
        reasoningIds = new Set(),
        position,
        opacity = 1,
        sizeScale = 1,
        fadeOut = false,
    } = options;
    const resolvedNode = {...node, fadeOut: fadeOut || node.fadeOut};
    const isAdded = stageId === "expanded" && previousStage && !previousStage.nodes.some((item) => item.id === node.id);
    const isKept = stageId === "reasoning" && reasoningIds.has(node.id);
    const baseSize = node.category === "Class" ? 16 : 14;
    const pos = positionOrZero(position);
    const fontOpacity = resolvedNode.fadeOut ? Math.max(0.18, opacity * 0.55) : opacity;
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
        shape: "dot",
        size: Math.max(4, baseSize * sizeScale),
        borderWidth: isAdded || isKept ? 3 : 1.5,
        color: categoryColor(resolvedNode, opacity),
        font: {
            size: 12,
            face: "Inter, Arial, sans-serif",
            color: rgba(resolvedNode.fadeOut ? [71, 85, 105] : [31, 41, 55], fontOpacity),
        },
        x: pos.x,
        y: pos.y,
        fixed: false,
        physics: false,
    };
};

const toFadingNodeVisual = (node, opacity) => {
    const safeOpacity = Math.max(0, Math.min(1, opacity));
    const palette = paletteFor({...node, fadeOut: true});
    return {
        id: node.id,
        color: {
            background: rgba(palette.background, safeOpacity * 0.42),
            border: rgba(palette.border, safeOpacity * 0.46),
            highlight: {
                background: rgba(palette.highlightBackground, safeOpacity * 0.5),
                border: rgba(palette.highlightBorder, safeOpacity * 0.5),
            },
        },
        font: {
            size: 12,
            face: "Inter, Arial, sans-serif",
            color: rgba([71, 85, 105], safeOpacity * 0.62),
        },
    };
};

const toVisEdge = (edge, options = {}) => {
    const {fadeOut = false, opacity = 1} = options;
    const safeOpacity = Math.max(0.05, Math.min(1, opacity));
    return {
        id: edgeId(edge),
        from: edge.from,
        to: edge.to,
        arrows: {to: {enabled: true, scaleFactor: 0.55}},
        label: edge.type || "",
        color: {
            color: fadeOut ? rgba([148, 163, 184], safeOpacity * 0.55) : rgba([100, 116, 139], safeOpacity),
            highlight: fadeOut ? rgba([148, 163, 184], 0.5) : "#475569",
        },
        font: {
            size: 10,
            color: fadeOut ? rgba([100, 116, 139], safeOpacity * 0.42) : rgba([100, 116, 139], safeOpacity),
            strokeWidth: 0,
        },
        width: fadeOut ? 1 : 1.2,
        dashes: false,
        smooth: {type: "cubicBezier", roundness: 0.22},
    };
};

const toFadingEdgeVisual = (edge, opacity) => {
    const safeOpacity = Math.max(0, Math.min(1, opacity));
    return {
        id: edgeId(edge),
        color: {
            color: rgba([148, 163, 184], safeOpacity * 0.46),
            highlight: rgba([148, 163, 184], safeOpacity * 0.5),
        },
        font: {
            size: 10,
            color: rgba([100, 116, 139], safeOpacity * 0.38),
            strokeWidth: 0,
        },
        width: Math.max(0.2, safeOpacity * 1.1),
    };
};

const uniqueBy = (items, keyFn) => {
    const seen = new Set();
    return (items || []).filter((item) => {
        const key = keyFn(item);
        if (!key || seen.has(key)) {
            return false;
        }
        seen.add(key);
        return true;
    });
};

const normalizeStage = (stage) => ({
    ...stage,
    nodes: uniqueBy(Array.isArray(stage?.nodes) ? stage.nodes : [], (node) => node.id),
    edges: uniqueBy(Array.isArray(stage?.edges) ? stage.edges : [], edgeId),
});

const ringPositions = (ids, radiusBase, radiusStep, maxPerRing, startAngle = -Math.PI / 2) => {
    const positions = {};
    if (!ids.length) {
        return positions;
    }
    if (ids.length === 1) {
        positions[ids[0]] = {x: 0, y: 0};
        return positions;
    }
    let cursor = 0;
    let ring = 0;
    while (cursor < ids.length) {
        const radius = radiusBase + ring * radiusStep;
        const arcCapacity = Math.max(1, Math.floor((2 * Math.PI * Math.max(radius, 1)) / MIN_NODE_DISTANCE));
        const ringCapacity = Math.max(1, Math.min(maxPerRing + ring * 6, arcCapacity));
        const count = Math.min(ringCapacity, ids.length - cursor);
        for (let offset = 0; offset < count; offset += 1) {
            const id = ids[cursor + offset];
            const angleOffset = ring % 2 === 0 ? 0 : Math.PI / count;
            const angle = startAngle + angleOffset + (2 * Math.PI * offset) / count;
            positions[id] = {
                x: Math.cos(angle) * radius,
                y: Math.sin(angle) * radius,
            };
        }
        cursor += count;
        ring += 1;
    }
    return positions;
};

const resolveCollisions = (positions, ids, fixedIds = new Set()) => {
    const idList = ids.filter((id) => positions[id]);
    for (let iteration = 0; iteration < 90; iteration += 1) {
        let changed = false;
        for (let i = 0; i < idList.length; i += 1) {
            for (let j = i + 1; j < idList.length; j += 1) {
                const a = idList[i];
                const b = idList[j];
                const aFixed = fixedIds.has(a);
                const bFixed = fixedIds.has(b);
                if (aFixed && bFixed) {
                    continue;
                }
                const pa = positions[a];
                const pb = positions[b];
                const dx = pb.x - pa.x;
                const dy = pb.y - pa.y;
                const distance = Math.sqrt(dx * dx + dy * dy);
                if (distance >= MIN_NODE_DISTANCE) {
                    continue;
                }
                const angle = distance > 0.001 ? Math.atan2(dy, dx) : (i + j + 1) * GOLDEN_ANGLE;
                const push = (MIN_NODE_DISTANCE - distance + 8) / (aFixed || bFixed ? 1 : 2);
                const moveX = Math.cos(angle) * push;
                const moveY = Math.sin(angle) * push;
                if (!aFixed) {
                    pa.x -= moveX;
                    pa.y -= moveY;
                }
                if (!bFixed) {
                    pb.x += moveX;
                    pb.y += moveY;
                }
                changed = true;
            }
        }
        if (!changed) {
            break;
        }
    }
};

const connectedAnchor = (nodeId, edges, anchorIds) => {
    const anchors = edges
        .flatMap((edge) => {
            if (edge.from === nodeId && anchorIds.has(edge.to)) {
                return [edge.to];
            }
            if (edge.to === nodeId && anchorIds.has(edge.from)) {
                return [edge.from];
            }
            return [];
        });
    return anchors[0] || null;
};

const computeStagePositions = (stageMap) => {
    const initial = stageMap.get("initial");
    const expanded = stageMap.get("expanded");
    const reasoning = stageMap.get("reasoning");
    const initialIds = (initial?.nodes || []).map((node) => node.id);
    const initialPositions = ringPositions(initialIds, 130, 100, 10);
    const expandedPositions = {...initialPositions};
    const reasoningPositions = {};

    if (expanded) {
        const initialIdSet = new Set(initialIds);
        const expandedIds = expanded.nodes.map((node) => node.id);
        const newIds = expandedIds.filter((id) => !initialIdSet.has(id));
        const fallbackAnchors = initialIds.length ? initialIds : expandedIds;
        const grouped = new Map();

        newIds.forEach((id, index) => {
            const anchor = connectedAnchor(id, expanded.edges, initialIdSet)
                || fallbackAnchors[index % Math.max(1, fallbackAnchors.length)]
                || null;
            if (!grouped.has(anchor)) {
                grouped.set(anchor, []);
            }
            grouped.get(anchor).push(id);
        });

        let groupIndex = 0;
        grouped.forEach((ids, anchor) => {
            const anchorPosition = positionOrZero(expandedPositions[anchor] || initialPositions[anchor]);
            const baseAngle = anchorPosition.x || anchorPosition.y
                ? Math.atan2(anchorPosition.y, anchorPosition.x)
                : -Math.PI / 2 + groupIndex * 0.85;
            const perRing = 7;
            ids.forEach((id, index) => {
                const ring = Math.floor(index / perRing);
                const ringStart = ring * perRing;
                const count = Math.min(perRing, ids.length - ringStart);
                const offset = index - ringStart - (count - 1) / 2;
                const angle = baseAngle + offset * 0.42 + (ring % 2 ? 0.2 : 0);
                const distance = 160 + ring * 105;
                expandedPositions[id] = {
                    x: anchorPosition.x + Math.cos(angle) * distance,
                    y: anchorPosition.y + Math.sin(angle) * distance,
                };
            });
            groupIndex += 1;
        });

        const fallbackIds = expandedIds.filter((id) => !expandedPositions[id]);
        Object.entries(ringPositions(fallbackIds, 210, 100, 12, -Math.PI / 3)).forEach(([id, position]) => {
            expandedPositions[id] = position;
        });
        resolveCollisions(initialPositions, initialIds);
        resolveCollisions(expandedPositions, expandedIds, initialIdSet);
    }

    const reasoningFallbackIds = (reasoning?.nodes || [])
        .filter((node) => !expandedPositions[node.id] && !initialPositions[node.id])
        .map((node) => node.id);
    const reasoningFallbackPositions = ringPositions(reasoningFallbackIds, 170, 100, 12);

    (reasoning?.nodes || []).forEach((node) => {
        reasoningPositions[node.id] = expandedPositions[node.id]
            || initialPositions[node.id]
            || reasoningFallbackPositions[node.id];
    });

    return {
        initial: initialPositions,
        expanded: expandedPositions,
        reasoning: reasoningPositions,
    };
};

const FocusGraphStageModal = ({open, onClose, stages}) => {
    const containerRef = useRef(null);
    const networkRef = useRef(null);
    const nodesRef = useRef(null);
    const edgesRef = useRef(null);
    const timersRef = useRef([]);
    const animationRunRef = useRef(0);
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

    const stagePositions = useMemo(() => computeStagePositions(stageMap), [stageMap]);
    const activeStage = stageMap.get(activeStageId) || orderedStages[0];
    const activeIndex = orderedStages.findIndex((stage) => stage.id === activeStage?.id);

    const clearTimers = () => {
        animationRunRef.current += 1;
        timersRef.current.forEach((timer) => {
            if (timer.kind === "interval") {
                clearInterval(timer.id);
            } else {
                clearTimeout(timer.id);
            }
        });
        timersRef.current = [];
    };

    const scheduleTimeout = (callback, delay) => {
        const timer = setTimeout(callback, delay);
        timersRef.current.push({kind: "timeout", id: timer});
        return timer;
    };

    const scheduleInterval = (callback, delay) => {
        const timer = setInterval(callback, delay);
        timersRef.current.push({kind: "interval", id: timer});
        return timer;
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
                layout: {
                    improvedLayout: false,
                    randomSeed: 37,
                },
                nodes: {
                    shadow: false,
                    margin: 8,
                },
                edges: {
                    width: 1.2,
                    selectionWidth: 1.5,
                },
                physics: {
                    enabled: false,
                },
                interaction: {
                    hover: true,
                    tooltipDelay: 80,
                    navigationButtons: false,
                    keyboard: false,
                    dragNodes: true,
                    dragView: true,
                    zoomView: true,
                },
            }
        );
    };

    const positionForStage = (stageId, nodeId) => (
        stagePositions[stageId]?.[nodeId]
        || stagePositions.expanded?.[nodeId]
        || stagePositions.initial?.[nodeId]
        || {x: 0, y: 0}
    );

    const upsertNode = (node, options = {}) => {
        if (!nodesRef.current) {
            return;
        }
        const existing = nodesRef.current.get(node.id);
        const item = toVisNode(node, options);
        if (options.preserveCurrentPosition && existing) {
            delete item.x;
            delete item.y;
        }
        if (existing) {
            nodesRef.current.update(item);
        } else {
            nodesRef.current.add(item);
        }
        if (!options.preserveCurrentPosition && typeof item.x === "number" && typeof item.y === "number") {
            networkRef.current?.moveNode(item.id, item.x, item.y);
        }
    };

    const upsertEdge = (edge, options = {}) => {
        if (!edgesRef.current) {
            return;
        }
        const item = toVisEdge(edge, options);
        if (edgesRef.current.get(item.id)) {
            edgesRef.current.update(item);
        } else {
            edgesRef.current.add(item);
        }
    };

    const applyExpandedViewport = () => {
        const expanded = stageMap.get("expanded") || stageMap.get("reasoning") || stageMap.get("initial");
        const container = containerRef.current;
        if (!networkRef.current || !container || !expanded?.nodes?.length) {
            return;
        }
        const positions = expanded.nodes
            .map((node) => positionForStage("expanded", node.id))
            .filter((position) => Number.isFinite(position.x) && Number.isFinite(position.y));
        if (!positions.length) {
            return;
        }
        const minX = Math.min(...positions.map((position) => position.x));
        const maxX = Math.max(...positions.map((position) => position.x));
        const minY = Math.min(...positions.map((position) => position.y));
        const maxY = Math.max(...positions.map((position) => position.y));
        const width = Math.max(1, maxX - minX);
        const height = Math.max(1, maxY - minY);
        const canvasWidth = Math.max(1, container.clientWidth);
        const canvasHeight = Math.max(1, container.clientHeight);
        const scale = Math.min(
            1.05,
            canvasWidth / (width + EXPANDED_VIEW_PADDING * 2),
            canvasHeight / (height + EXPANDED_VIEW_PADDING * 2)
        );
        networkRef.current.moveTo({
            position: {
                x: (minX + maxX) / 2,
                y: (minY + maxY) / 2,
            },
            scale: Math.max(0.25, scale),
            animation: false,
        });
    };

    const setGraph = (stage, previousStage = null, options = {}) => {
        if (!stage || !nodesRef.current || !edgesRef.current) {
            return;
        }
        const reasoningStage = stageMap.get("reasoning");
        const reasoningIds = new Set((reasoningStage?.nodes || []).map((node) => node.id));
        const nodeItems = stage.nodes.map((node) => toVisNode(node, {
            stageId: stage.id,
            previousStage,
            reasoningIds,
            position: positionForStage(stage.id, node.id),
        }));
        const edgeItems = stage.edges.map((edge) => toVisEdge(edge));
        nodesRef.current.clear();
        edgesRef.current.clear();
        nodesRef.current.add(nodeItems);
        edgesRef.current.add(edgeItems);
        stage.nodes.forEach((node) => {
            const position = positionForStage(stage.id, node.id);
            networkRef.current?.moveNode(node.id, position.x, position.y);
        });
        applyExpandedViewport();
    };

    const playExpansionTransition = (runId) => {
        const initial = stageMap.get("initial");
        const expanded = stageMap.get("expanded");
        if (!initial || !expanded || !nodesRef.current || !edgesRef.current || animationRunRef.current !== runId) {
            return 0;
        }
        setActiveStageId("expanded");
        const reasoningStage = stageMap.get("reasoning");
        const reasoningIds = new Set((reasoningStage?.nodes || []).map((node) => node.id));
        const initialIds = new Set(initial.nodes.map((node) => node.id));
        const visibleIds = new Set(initialIds);
        const addedEdgeIds = new Set(initial.edges.map(edgeId));

        initial.nodes.forEach((node) => {
            upsertNode(node, {
                stageId: "expanded",
                previousStage: initial,
                reasoningIds,
                position: positionForStage("expanded", node.id),
                preserveCurrentPosition: true,
            });
        });
        initial.edges.forEach((edge) => upsertEdge(edge));

        const newNodes = expanded.nodes.filter((node) => !initialIds.has(node.id));
        const staggerMs = newNodes.length > 24 ? 90 : EXPAND_NODE_STAGGER_MS;

        newNodes.forEach((node, index) => {
            const delay = index * staggerMs;
            scheduleTimeout(() => {
                if (animationRunRef.current !== runId) {
                    return;
                }
                upsertNode(node, {
                    stageId: "expanded",
                    previousStage: initial,
                    reasoningIds,
                    position: positionForStage("expanded", node.id),
                });
                visibleIds.add(node.id);
                expanded.edges.forEach((edge) => {
                    const id = edgeId(edge);
                    if (addedEdgeIds.has(id)) {
                        return;
                    }
                    const edgeTouchesNode = edge.from === node.id || edge.to === node.id;
                    const bothVisible = visibleIds.has(edge.from) && visibleIds.has(edge.to);
                    if (edgeTouchesNode && bothVisible) {
                        upsertEdge(edge);
                        addedEdgeIds.add(id);
                    }
                });
            }, delay);
        });

        const totalMs = Math.max(1200, newNodes.length ? (newNodes.length - 1) * staggerMs + 400 : 0);
        scheduleTimeout(() => {
            if (animationRunRef.current !== runId) {
                return;
            }
            expanded.nodes.forEach((node) => {
                upsertNode(node, {
                    stageId: "expanded",
                    previousStage: initial,
                    reasoningIds,
                    position: positionForStage("expanded", node.id),
                    preserveCurrentPosition: true,
                });
            });
            expanded.edges.forEach((edge) => upsertEdge(edge));
            applyExpandedViewport();
        }, totalMs + EXPAND_SETTLE_MS / 2);
        return totalMs + EXPAND_SETTLE_MS;
    };

    const playReasoningTransition = (runId) => {
        const expanded = stageMap.get("expanded");
        const reasoning = stageMap.get("reasoning");
        if (!expanded || !reasoning || !nodesRef.current || !edgesRef.current || animationRunRef.current !== runId) {
            return;
        }
        setActiveStageId("reasoning");
        const reasoningIds = new Set(reasoning.nodes.map((node) => node.id));
        const reasoningEdgeIds = new Set(reasoning.edges.map(edgeId));
        const fadingNodes = expanded.nodes.filter((node) => !reasoningIds.has(node.id));
        const fadingEdges = expanded.edges.filter((edge) => !reasoningEdgeIds.has(edgeId(edge)));

        reasoning.nodes.forEach((node) => {
            upsertNode(node, {
                stageId: "reasoning",
                reasoningIds,
                position: positionForStage("reasoning", node.id),
                preserveCurrentPosition: true,
            });
        });
        reasoning.edges.forEach((edge) => upsertEdge(edge));

        if (fadingNodes.length) {
            nodesRef.current.update(fadingNodes.map((node) => toFadingNodeVisual(node, 1)));
        }
        if (fadingEdges.length) {
            edgesRef.current.update(fadingEdges.map((edge) => toFadingEdgeVisual(edge, 1)));
        }

        let frame = 0;
        const interval = scheduleInterval(() => {
            if (animationRunRef.current !== runId) {
                clearInterval(interval);
                return;
            }
            frame += 1;
            const ratio = Math.min(1, frame / FILTER_FADE_FRAMES);
            const opacity = Math.pow(1 - ratio, 1.35);
            if (fadingNodes.length) {
                nodesRef.current.update(fadingNodes.map((node) => toFadingNodeVisual(node, opacity)));
            }
            if (fadingEdges.length) {
                edgesRef.current.update(fadingEdges.map((edge) => toFadingEdgeVisual(edge, opacity)));
            }
            if (frame >= FILTER_FADE_FRAMES) {
                clearInterval(interval);
            }
        }, FILTER_FADE_DURATION_MS / FILTER_FADE_FRAMES);

        scheduleTimeout(() => {
            if (animationRunRef.current !== runId) {
                return;
            }
            edgesRef.current.remove(fadingEdges.map(edgeId));
            nodesRef.current.remove(fadingNodes.map((node) => node.id));
            reasoning.nodes.forEach((node) => {
                upsertNode(node, {
                    stageId: "reasoning",
                    reasoningIds,
                    position: positionForStage("reasoning", node.id),
                    preserveCurrentPosition: true,
                });
            });
            reasoning.edges.forEach((edge) => upsertEdge(edge));
            applyExpandedViewport();
        }, FILTER_FADE_DURATION_MS + FILTER_REMOVE_PAUSE_MS);
    };

    const playAnimation = () => {
        clearTimers();
        const runId = animationRunRef.current;
        const initial = stageMap.get("initial");
        const expanded = stageMap.get("expanded");
        const reasoning = stageMap.get("reasoning");
        if (!initial || !expanded || !reasoning) {
            setGraph(orderedStages[0]);
            setActiveStageId(orderedStages[0]?.id || "initial");
            return;
        }
        setActiveStageId("initial");
        setGraph(initial);
        scheduleTimeout(() => {
            const expansionDuration = playExpansionTransition(runId);
            scheduleTimeout(() => playReasoningTransition(runId), expansionDuration);
        }, INITIAL_HOLD_MS);
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
        setGraph(nextStage, previousStage, {animatedFit: true});
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
