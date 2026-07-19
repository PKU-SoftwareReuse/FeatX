// DebloatingPage.jsx

import styles from './DebloatingPage.module.css';
import React, {useCallback, useEffect, useMemo, useRef, useState} from "react";
import {Alert, AutoComplete, Splitter, Collapse, ConfigProvider, Modal, Input, Card, List, Spin, Button, Tooltip, Select, message, Progress} from "antd";
import {
    CloseOutlined,
    DeleteTwoTone,
    EditTwoTone,
    PlusSquareTwoTone,
    ExclamationCircleOutlined,
    SearchOutlined,
    SwapOutlined,
    ApartmentOutlined,
    DiffOutlined,
    SaveOutlined,
    CheckOutlined,
    CheckCircleOutlined,
    UndoOutlined
} from '@ant-design/icons';
import classNames from "classnames";

import API from "../API";
import FeatureGraph from "../graph/featureGraph/FeatureGraph";
import CodeDiffComponent from "./CodeDiffComponent/CodeDiffComponent";
import MarkdownRendererComponent from "./MarkdownRenderComponent/MarkdownRenderComponent";
import FocusGraphStageModal from "./FocusGraphStageModal/FocusGraphStageModal";
import {getLocalizedField, useLanguage} from "../i18n/LanguageContext";

const {Panel} = Collapse;
const {TextArea} = Input;
const GitDiffEditor = React.lazy(() => import("./GitDiffEditor/GitDiffEditor"));

const DEBLOATING_THEME = {
    token: {
        fontSize: 13,
        fontSizeSM: 12,
        fontSizeLG: 14,
    },
};

const DIFF_DRAWER_DEFAULT_RATIO = 0.42;
const DIFF_DRAWER_MIN_WIDTH = 520;
const DIFF_DRAWER_MAX_WIDTH = 980;
const DIFF_DRAWER_OVERLAY_BREAKPOINT = 960;
const FEATURE_PANEL_MINIMAL_WIDTH = 148;
const FEATURE_PANEL_DESCRIPTION_MIN_WIDTH = 220;
const GRAPH_PANEL_TARGET_WIDTH = 440;
const WORKSPACE_MAX_WIDTH = 1600;
const DIFF_DRAWER_LAYOUT_SETTLE_MS = 360;

const EMPTY_GIT_STATUS = {
    branch: '',
    commitScope: 'NONE',
    stagedPaths: [],
    unstagedPaths: [],
    untrackedPaths: [],
    candidatePaths: [],
    pendingCandidatePaths: [],
    committedCandidatePaths: [],
    unstagedCandidatePaths: [],
};

const getWorkspaceSideGap = (viewportWidth) => (
    viewportWidth <= DIFF_DRAWER_OVERLAY_BREAKPOINT
        ? 0
        : Math.round(Math.min(56, Math.max(24, viewportWidth * 0.03)))
);

const getWorkspaceContainerWidth = (viewportWidth) => Math.min(
    WORKSPACE_MAX_WIDTH,
    viewportWidth - getWorkspaceSideGap(viewportWidth) * 2
);

const getDiffDrawerWidthBounds = () => {
    const viewportWidth = typeof window === "undefined" ? 1440 : window.innerWidth;
    if (viewportWidth <= DIFF_DRAWER_OVERLAY_BREAKPOINT) {
        return {min: viewportWidth, max: viewportWidth};
    }

    const max = Math.max(0, Math.min(
        DIFF_DRAWER_MAX_WIDTH,
        getWorkspaceContainerWidth(viewportWidth) - 420
    ));
    return {min: Math.min(DIFF_DRAWER_MIN_WIDTH, max), max};
};

const clampDiffDrawerWidth = (width) => {
    const {min, max} = getDiffDrawerWidthBounds();
    return Math.round(Math.min(max, Math.max(min, width)));
};

const getDefaultDiffDrawerWidth = () => {
    const viewportWidth = typeof window === "undefined" ? 1440 : window.innerWidth;
    return clampDiffDrawerWidth(viewportWidth * DIFF_DRAWER_DEFAULT_RATIO);
};

const DEBLOATING_COPY = {
    zh: {
        noAction: "当前没有可执行的操作",
        switchTitle: "确认切换操作",
        switchDescription: "确定要放弃当前修改吗？",
        discard: "放弃全部未提交修改",
        cancel: "取消",
        newSubfeature: "新建子功能特征 ",
        completed: "操作已完成",
        applyingChanges: "正在应用代码变更……",
        featurePanel: "功能特征面板",
        searchFeatures: "搜索功能特征",
        noFeatureMatches: "未找到匹配的功能特征",
        fetchingFeatureSummary: "正在获取代码仓库的功能特征摘要。",
        pendingChanges: "请确认应用或放弃修改。",
        submit: "提交",
        agentPanel: "智能体生成",
        model: "模型",
        loadingModels: "正在获取模型……",
        modelUnavailable: "未获取到可用模型",
        graphPanel: "相关代码图谱",
        fetchingGraph: "正在获取相关代码图谱。",
        changesPanel: "代码变更",
        resizeChangesPanel: "调整代码变更面板宽度，双击恢复默认宽度",
        viewRepositoryDiff: "查看仓库 Git Diff",
        repositoryDiff: "仓库 Git Diff",
        closeChangesPanel: "关闭代码变更",
        fetchingCode: "正在获取代码详情。",
        noRepositoryChanges: "当前仓库没有未提交的 Git 变更。",
        failedFetchRepositoryDiff: "获取仓库 Git Diff 失败。",
        candidateDiff: "候选代码 Git Diff",
        failedFetchCandidateDiff: "获取候选代码 Git Diff 失败。",
        saveCandidate: "保存编辑",
        savingCandidate: "正在保存……",
        candidateSaved: "候选代码已保存。",
        failedSaveCandidate: "保存候选代码失败。",
        saveBeforeApply: "请先保存编辑，再确认应用。",
        saveBeforeClose: "当前编辑尚未保存。",
        gitGeneratedDiff: "Git 生成的差异",
        confirmFile: "确认此文件（暂存）",
        stagingFile: "正在暂存……",
        fileStaged: "此文件已暂存",
        candidateStaged: "文件已写入项目并暂存。",
        failedStageCandidate: "暂存候选文件失败。",
        commitChanges: "提交已确认文件",
        noStagedFiles: "请先在 Diff Panel 中确认至少一个文件。",
        partialCommitTitle: "确认部分提交",
        completeCommitTitle: "确认全部提交",
        partialCommitDescription: "本次只提交已暂存文件；剩余候选文件继续保留，功能数据与静态分析将在全部候选文件提交后统一更新。",
        completeCommitDescription: "所有候选文件均已确认；本次将提交剩余修改，并同步更新功能数据、CodeMap 和静态分析结果。",
        partialCommitSuccess: "已完成部分提交，未确认文件仍保留在代码图谱中。",
        completeCommitSuccess: "全部候选修改已提交。",
        failedCommitChanges: "提交代码变更失败。",
        discardAllChanges: "放弃全部未提交修改",
        discardAllTitle: "放弃全部未提交修改？",
        discardAllDescription: "将恢复暂存区和工作区到最近一次提交，并删除非忽略的未跟踪文件；已经完成的提交和预处理输出会保留。",
        discardSuccess: "未提交修改已放弃，已有提交保持不变。",
        failedDiscardChanges: "放弃未提交修改失败。",
        committingChanges: "正在提交并更新项目数据……",
        noSubmittedChanges: "您尚未提交任何修改，请先提交。",
        confirmAllChanges: "确认所有代码变更",
        reviewAllChanges: "您是否已检查全部代码变更（红色标记的节点）？",
        confirmApply: "确认应用",
        decline: "取消",
        applyChanges: "确认应用代码变更",
        submittingFeatureDeletion: "正在提交功能删除。",
        submittingFeatureAddition: "正在提交新增功能。",
        submittingFeatureModification: "正在提交功能修改。",
        preparingModification: "正在准备修改。",
        streamingPythonAgent: "正在流式生成 Python 代码。",
        codeGenerationFinished: "代码生成已完成。请检查差异后确认或放弃。",
        pythonDeleteReady: "Python 删除差异已准备好。请检查受影响文件后确认或放弃。",
        focusGraphReady: "查看 Python FocusGraph 的初始、扩展和推理阶段。",
        focusGraphPending: "Python 新增/修改提交后可查看 FocusGraph 阶段。",
        failedFetchFeatureSummary: "获取功能摘要失败。",
        failedFetchCodeMap: "获取代码图谱失败。",
        failedFetchGeneratedGraph: "获取生成后的图谱失败。",
        failedFetchCodeDiff: "获取代码差异失败。",
        failedFetchCodeContext: "获取代码上下文失败。",
        failedFetchGeneratedCodeDiff: "获取生成后的代码差异失败。",
        failedPrepareDeletion: "准备删除上下文失败。",
        failedPrepareModification: "准备修改上下文失败。",
        failedAddFeature: "新增功能失败。",
        failedApplyDeleteDiff: "应用删除差异失败。",
        failedApplyModifyDiff: "应用修改差异失败。",
        failedApplyAddDiff: "应用新增差异失败。",
    },
    en: {
        noAction: "Maybe Not Todo",
        switchTitle: "You are trying to do another thing",
        switchDescription: "Do you want to give up your modification?",
        discard: "Discard all uncommitted changes",
        cancel: "Cancel",
        newSubfeature: "new SubFeature Item ",
        completed: "This is a [Fake] success message",
        applyingChanges: "Applying the Code Diff...",
        featurePanel: "Feature Panel",
        searchFeatures: "Search features",
        noFeatureMatches: "No matching features",
        fetchingFeatureSummary: "Fetching repo's feature summary.",
        pendingChanges: "You have made some modifications. Please confirm or drop it.",
        submit: "Submit",
        agentPanel: "Agent Panel",
        model: "Model",
        loadingModels: "Loading models...",
        modelUnavailable: "No models available",
        graphPanel: "CodeMap Panel",
        fetchingGraph: "fetching codeMap.",
        changesPanel: "Diff Panel",
        resizeChangesPanel: "Resize the diff panel; double-click to reset",
        viewRepositoryDiff: "View repository Git diff",
        repositoryDiff: "Repository Git Diff",
        closeChangesPanel: "Close Diff Panel",
        fetchingCode: "Fetching code details.",
        noRepositoryChanges: "The repository has no uncommitted Git changes.",
        failedFetchRepositoryDiff: "Failed to fetch repository Git diff.",
        candidateDiff: "Candidate Git Diff",
        failedFetchCandidateDiff: "Failed to fetch candidate Git diff.",
        saveCandidate: "Save edits",
        savingCandidate: "Saving...",
        candidateSaved: "Candidate code saved.",
        failedSaveCandidate: "Failed to save candidate code.",
        saveBeforeApply: "Save your edits before applying the change.",
        saveBeforeClose: "The current edits have not been saved.",
        gitGeneratedDiff: "Git-generated diff",
        confirmFile: "Confirm file (stage)",
        stagingFile: "Staging...",
        fileStaged: "File staged",
        candidateStaged: "The file was written to the project and staged.",
        failedStageCandidate: "Failed to stage the candidate file.",
        commitChanges: "Commit confirmed files",
        noStagedFiles: "Confirm at least one file in the Diff Panel first.",
        partialCommitTitle: "Confirm partial commit",
        completeCommitTitle: "Confirm complete commit",
        partialCommitDescription: "Only staged files will be committed. Remaining candidates stay available; feature data and static analysis are updated after every candidate is committed.",
        completeCommitDescription: "All candidate files are confirmed. This commits the remaining changes and updates feature data, CodeMap, and static analysis.",
        partialCommitSuccess: "Partial commit completed. Unconfirmed files remain in the code graph.",
        completeCommitSuccess: "All candidate changes were committed.",
        failedCommitChanges: "Failed to commit code changes.",
        discardAllChanges: "Discard all uncommitted changes",
        discardAllTitle: "Discard all uncommitted changes?",
        discardAllDescription: "The index and worktree return to the latest commit, and non-ignored untracked files are removed. Existing commits and preprocessing output are preserved.",
        discardSuccess: "Uncommitted changes were discarded; existing commits were preserved.",
        failedDiscardChanges: "Failed to discard uncommitted changes.",
        committingChanges: "Committing and updating project data...",
        noSubmittedChanges: "You haven't made any modifications. Please submit first.",
        confirmAllChanges: "Confirm All Code Diff",
        reviewAllChanges: "Have you read all the diff(the node marked with red)?",
        confirmApply: "Yes",
        decline: "No",
        applyChanges: "Confirm Apply the Diff",
        submittingFeatureDeletion: "Submitting feature deletion.",
        submittingFeatureAddition: "Submitting feature addition.",
        submittingFeatureModification: "Submitting feature modification.",
        preparingModification: "Preparing modification.",
        streamingPythonAgent: "Streaming Python Agent code generation.",
        codeGenerationFinished: "Code generation finished. Review the diff and confirm or drop it.",
        pythonDeleteReady: "Deterministic Python delete diff is ready. Review the affected files and confirm or drop it.",
        focusGraphReady: "Show Python FocusGraph initial, expanded, and reasoning graphs.",
        focusGraphPending: "FocusGraph stages are available after Python Add/Modify submit.",
        failedFetchFeatureSummary: "Failed to fetch feature summary.",
        failedFetchCodeMap: "Failed to fetch CodeMap.",
        failedFetchGeneratedGraph: "Failed to fetch generated graph.",
        failedFetchCodeDiff: "Failed to fetch code diff.",
        failedFetchCodeContext: "Failed to fetch code context.",
        failedFetchGeneratedCodeDiff: "Failed to fetch generated code diff.",
        failedPrepareDeletion: "Failed to prepare deletion context.",
        failedPrepareModification: "Failed to prepare modification context.",
        failedAddFeature: "Failed to add feature.",
        failedApplyDeleteDiff: "Failed to apply delete diff.",
        failedApplyModifyDiff: "Failed to apply modify diff.",
        failedApplyAddDiff: "Failed to apply add diff.",
    },
};

const getModuleDescription = (module, language) =>
    getLocalizedField(module, "moduleDesc", language);

const getFeatureDescription = (feature, language) =>
    getLocalizedField(feature, "featureDescription", language);

const getFeatureDisplayIndex = (featureList, item) => item.isNew || item.isNewGenerated
    ? 0
    : featureList
        .filter((feature) => !feature.isNew && !feature.isNewGenerated)
        .findIndex((feature) => feature === item) + 1;

const FeatureListItem = ({
    item,
    moduleId,
    itemNumber,
    description,
    selectedType,
    selectedFeatureItem,
    submitEnabled,
    selectedModel,
    editedText,
    setEditedText,
    copy,
    onClickItem,
    submitEdit,
    handleDelete,
    handleEdit,
    compressed,
    minimal,
}) => {
    const descriptionRef = useRef(null);
    const featureContentRef = useRef(null);
    const [singleLine, setSingleLine] = useState(false);
    const [preservedHeight, setPreservedHeight] = useState(null);
    const [preservedLineCount, setPreservedLineCount] = useState(1);
    const isSelected = selectedFeatureItem != null
        && item.featureId === selectedFeatureItem.featureId;
    const isEditing = (selectedType === "edit" || selectedType === "add") && isSelected;

    useEffect(() => {
        if (compressed || !featureContentRef.current) return undefined;

        const itemElement = featureContentRef.current.closest("[data-feature-id]");
        if (!itemElement) return undefined;

        const measureHeight = () => {
            const height = Math.ceil(itemElement.getBoundingClientRect().height);
            if (height > 0) {
                setPreservedHeight((current) => current === height ? current : height);
            }
        };

        const frameId = window.requestAnimationFrame(measureHeight);
        if (typeof ResizeObserver === "undefined") {
            window.addEventListener("resize", measureHeight);
            return () => {
                window.cancelAnimationFrame(frameId);
                window.removeEventListener("resize", measureHeight);
            };
        }

        const observer = new ResizeObserver(measureHeight);
        observer.observe(itemElement);
        return () => {
            window.cancelAnimationFrame(frameId);
            observer.disconnect();
        };
    }, [compressed, description, isEditing, itemNumber]);

    useEffect(() => {
        if (compressed) return undefined;
        if (isEditing || !descriptionRef.current) {
            setSingleLine(false);
            return undefined;
        }

        const descriptionElement = descriptionRef.current;
        const measureLineCount = () => {
            const computedStyle = window.getComputedStyle(descriptionElement);
            const parsedLineHeight = Number.parseFloat(computedStyle.lineHeight);
            const fontSize = Number.parseFloat(computedStyle.fontSize) || 14;
            const lineHeight = Number.isFinite(parsedLineHeight) ? parsedLineHeight : fontSize * 1.2;
            const lineCount = Math.max(
                1,
                Math.round(descriptionElement.getBoundingClientRect().height / lineHeight)
            );
            setSingleLine(lineCount === 1);
            setPreservedLineCount((current) => current === lineCount ? current : lineCount);
        };

        const frameId = window.requestAnimationFrame(measureLineCount);
        if (typeof ResizeObserver === "undefined") {
            window.addEventListener("resize", measureLineCount);
            return () => {
                window.cancelAnimationFrame(frameId);
                window.removeEventListener("resize", measureLineCount);
            };
        }

        const observer = new ResizeObserver(measureLineCount);
        observer.observe(descriptionElement);
        return () => {
            window.cancelAnimationFrame(frameId);
            observer.disconnect();
        };
    }, [compressed, description, isEditing, itemNumber]);

    return (
        <List.Item
            onClick={() => onClickItem(item)}
            data-feature-id={String(item.featureId)}
            data-module-id={String(moduleId)}
            className={classNames({
                [styles.item]: true,
                [styles.selectedItem]: isSelected,
                [styles.featureItemCondensed]: compressed,
                [styles.featureItemMinimal]: minimal,
            })}
            style={compressed && preservedHeight ? {
                height: `${preservedHeight}px`,
                minHeight: `${preservedHeight}px`,
                maxHeight: `${preservedHeight}px`,
                "--feature-line-clamp": preservedLineCount,
            } : undefined}
        >
            <div ref={featureContentRef} className={styles.featureContent}>
                {minimal ? (
                    <span className={styles.featureNumber}>{itemNumber}</span>
                ) : compressed ? (
                    <div className={styles.featureDescription}>
                        <span className={styles.featureNumber}>{itemNumber}</span>{" "}
                        <span>{description}</span>
                    </div>
                ) : isEditing ? (
                    <Tooltip title={!submitEnabled ? copy.pendingChanges : ""}>
                        <div className={styles.featureEditor}>
                            <span className={styles.featureNumber}>{itemNumber}</span>
                            <TextArea
                                value={editedText}
                                onChange={(event) => setEditedText(event.target.value)}
                                onClick={(event) => event.stopPropagation()}
                                size="middle"
                                autoSize={{minRows: 2, maxRows: 5}}
                                disabled={!submitEnabled || !selectedModel}
                            />
                            <Button
                                type="primary"
                                onClick={() => submitEdit(item)}
                                disabled={!submitEnabled || !selectedModel}
                            >
                                {copy.submit}
                            </Button>
                        </div>
                    </Tooltip>
                ) : (
                    <div ref={descriptionRef} className={styles.featureDescription}>
                        <span className={styles.featureNumber}>{itemNumber}</span>{" "}
                        <span>{description}</span>
                    </div>
                )}
            </div>
            <div className={classNames(styles.featureActions, {
                [styles.featureActionsSingleLine]: singleLine,
                [styles.featureActionsMinimal]: minimal,
            })}>
                <Button
                    type="text"
                    icon={<DeleteTwoTone twoToneColor="#F74E52"/>}
                    onClick={(event) => {
                        event.stopPropagation();
                        handleDelete(item);
                    }}
                    className={classNames(styles.icon, {
                        [styles.selectedIcon]: isSelected && selectedType === "delete",
                    })}
                />
                <Button
                    type="text"
                    icon={<EditTwoTone twoToneColor="#EFA92B"/>}
                    onClick={(event) => {
                        event.stopPropagation();
                        handleEdit(item);
                    }}
                    className={classNames(styles.icon, {
                        [styles.selectedIcon]: isSelected && (selectedType === "edit" || selectedType === "add"),
                    })}
                />
            </div>
        </List.Item>
    );
};

const DebloatingPage = () => {
    const {language, apiLanguage} = useLanguage();
    const copy = DEBLOATING_COPY[language];

    const [loadingFeatureList, setLoadingFeatureList] = useState(false);
    const [loadingFeatureGraph, setLoadingFeatureGraph] = useState(false);
    const [loadingCode, setLoadingCode] = useState(false);
    const [models, setModels] = useState([]);
    const [selectedModel, setSelectedModel] = useState(null);
    const [loadingModels, setLoadingModels] = useState(true);
    const [currentProject, setCurrentProject] = useState(null);
    const [operationProgress, setOperationProgress] = useState(null);
    const [focusGraphStages, setFocusGraphStages] = useState([]);
    const [focusGraphModalOpen, setFocusGraphModalOpen] = useState(false);
    const progressTimerRef = useRef(null);
    const expectedProgressOperationRef = useRef(null);
    const graphRefreshTimerRef = useRef(null);
    const chatCloseTimerRef = useRef(null);
    const eventSourceRef = useRef(null);
    const isPythonProject = currentProject?.projectType === "PYTHON";

    const clearPostAgentTimers = () => {
        if (eventSourceRef.current) {
            eventSourceRef.current.close();
            eventSourceRef.current = null;
        }
        if (graphRefreshTimerRef.current) {
            clearTimeout(graphRefreshTimerRef.current);
            graphRefreshTimerRef.current = null;
        }
        if (chatCloseTimerRef.current) {
            clearTimeout(chatCloseTimerRef.current);
            chatCloseTimerRef.current = null;
        }
    };

    const clearFocusGraphStages = () => {
        setFocusGraphStages([]);
        setFocusGraphModalOpen(false);
    };

    useEffect(() => {
        let active = true;

        API.getLlmModels()
            .then((catalog) => {
                if (!active) return;
                const availableModels = Array.isArray(catalog.models) ? catalog.models : [];
                setModels(availableModels);
                setSelectedModel(
                    availableModels.includes(catalog.defaultModel)
                        ? catalog.defaultModel
                        : availableModels[0] || null
                );
            })
            .catch((error) => {
                if (!active) return;
                console.error("Error fetching LLM models:", error);
                setModels([]);
                setSelectedModel(null);
            })
            .finally(() => {
                if (active) setLoadingModels(false);
            });

        return () => {
            active = false;
        };
    }, []);

    useEffect(() => {
        const fetchData = async () => {
            const project = await API.getCurrentProject().catch(() => null)
            setCurrentProject(project)
            await refreshGitStatus()
            const res = await getFeatureData()
            if (res.length > 0 && res[0].featureList.length > 0) {
                setActiveKey(res[0].moduleId)
                handleSelect(res[0].featureList[0])
            }
        }
        fetchData()
    }, [])


    const [featureData, setFeatureData] = useState([]);
    const [selectedFeatureItem, setSelectedFeatureItem] = useState(null);
    const [selectedType, setSelectedType] = useState(null);

    const getFeatureData = async () => {
        setLoadingFeatureList(true)
        setLoadingFeatureGraph(true);
        setLoadingCode(true);
        try {
            const res = await API.getFeatures()
            setFeatureData(res)
            setLoadingFeatureList(false)
            if (!res.length) {
                setLoadingFeatureGraph(false);
                setLoadingCode(false);
            }
            return res
        } catch (error) {
            console.error(error)
            setLoadingFeatureList(false)
            setLoadingFeatureGraph(false)
            setLoadingCode(false)
            message.error(errorMessage(error, copy.failedFetchFeatureSummary))
            return []
        }
    }

    const [graphData, setGraphData] = useState({nodes: [], edges: []});

    const getFeatureGraphData = (featureId, selectedType) => {
        setLoadingFeatureGraph(true);
        setLoadingCode(false);
        setGraphData({nodes: [], edges: []});
        setCodeDiff('');
        setSelectedCodeNodeId('');
        setDiffDrawerOpen(false);
        setIsRepositoryDiff(false);
        setIsCandidateDiff(false);
        setRepositoryDiffError(false);
        setCandidateFile(null);
        setCandidateDraft('');
        setCandidateDirty(false);
        setStagingCandidate(false);
        if (selectedType === 'delete') {
            API.getMinGraphData(featureId).then((data) => {
                setGraphData(data)
                setLoadingFeatureGraph(false)
            }).catch((error) => {
                console.error('Error fetching FeatureGraph data:', error)
                setLoadingFeatureGraph(false)
                message.error(errorMessage(error, copy.failedFetchCodeMap))
            })
        } else if (selectedType === 'edit' || selectedType === 'select') {
            API.getMaxGraphData(featureId).then((data) => {
                setGraphData(data)
                setLoadingFeatureGraph(false)
            }).catch((error) => {
                console.error('Error fetching FeatureGraph data:', error)
                setLoadingFeatureGraph(false)
                message.error(errorMessage(error, copy.failedFetchCodeMap))
            })
        } else if (selectedType === 'add') {
            setGraphData(null)
            setLoadingFeatureGraph(false);
        } else if (selectedType === 'new') {
            API.getNewGraphData().then((data) => {
                setGraphData(data)
                setLoadingFeatureGraph(false)
            }).catch((error) => {
                console.error('Error fetching FeatureGraph data:', error)
                setLoadingFeatureGraph(false)
                message.error(errorMessage(error, copy.failedFetchGeneratedGraph))
            })
        } else {
            setGraphData({nodes: [], edges: []})
            setLoadingFeatureGraph(false)
        }

    }

    const [codeDiff, setCodeDiff] = useState('');
    const [diffDrawerOpen, setDiffDrawerOpen] = useState(false);
    const [diffDrawerLayoutSettled, setDiffDrawerLayoutSettled] = useState(false);
    const [diffDrawerWidth, setDiffDrawerWidth] = useState(getDefaultDiffDrawerWidth);
    const [diffDrawerResizing, setDiffDrawerResizing] = useState(false);
    const [viewportWidth, setViewportWidth] = useState(() => (
        typeof window === "undefined" ? 1440 : window.innerWidth
    ));
    const [featurePanelSize, setFeaturePanelSize] = useState("45%");
    const drawerResizeRef = useRef(null);
    const [selectedCodeNodeId, setSelectedCodeNodeId] = useState('');
    const [isRepositoryDiff, setIsRepositoryDiff] = useState(false);
    const [repositoryDiffError, setRepositoryDiffError] = useState(false);
    const [isCandidateDiff, setIsCandidateDiff] = useState(false);
    const [candidateFile, setCandidateFile] = useState(null);
    const [candidateDraft, setCandidateDraft] = useState('');
    const [candidateDirty, setCandidateDirty] = useState(false);
    const [savingCandidate, setSavingCandidate] = useState(false);
    const [stagingCandidate, setStagingCandidate] = useState(false);
    const [gitStatus, setGitStatus] = useState(EMPTY_GIT_STATUS);
    const [loadingConfirm, setLoadingConfirm] = useState(false);

    const refreshGitStatus = () => API.getGitWorkspaceStatus()
        .then((status) => {
            setGitStatus(status || EMPTY_GIT_STATUS);
            return status || EMPTY_GIT_STATUS;
        })
        .catch((error) => {
            console.error('Error fetching Git workspace status:', error);
            return EMPTY_GIT_STATUS;
        });

    useEffect(() => {
        const handleWindowResize = () => {
            setViewportWidth(window.innerWidth);
            setDiffDrawerWidth((width) => clampDiffDrawerWidth(width));
        };
        window.addEventListener('resize', handleWindowResize);
        return () => window.removeEventListener('resize', handleWindowResize);
    }, []);

    useEffect(() => {
        if (!diffDrawerOpen) {
            setDiffDrawerLayoutSettled(false);
            return undefined;
        }

        const timer = window.setTimeout(
            () => setDiffDrawerLayoutSettled(true),
            DIFF_DRAWER_LAYOUT_SETTLE_MS
        );
        return () => window.clearTimeout(timer);
    }, [diffDrawerOpen]);

    const startDrawerResize = useCallback((event) => {
        if (event.button !== 0 || window.innerWidth <= DIFF_DRAWER_OVERLAY_BREAKPOINT) return;
        event.preventDefault();
        event.stopPropagation();
        event.currentTarget.focus();
        event.currentTarget.setPointerCapture?.(event.pointerId);
        drawerResizeRef.current = {
            pointerId: event.pointerId,
            startX: event.clientX,
            startWidth: diffDrawerWidth,
        };
        setDiffDrawerResizing(true);
    }, [diffDrawerWidth]);

    const resizeDrawer = useCallback((event) => {
        const resize = drawerResizeRef.current;
        if (!resize || resize.pointerId !== event.pointerId) return;
        setDiffDrawerWidth(clampDiffDrawerWidth(
            resize.startWidth + resize.startX - event.clientX
        ));
    }, []);

    const stopDrawerResize = useCallback((event) => {
        const resize = drawerResizeRef.current;
        if (!resize || (event.pointerId != null && resize.pointerId !== event.pointerId)) return;
        drawerResizeRef.current = null;
        setDiffDrawerResizing(false);
        if (event.currentTarget?.hasPointerCapture?.(resize.pointerId)) {
            event.currentTarget.releasePointerCapture(resize.pointerId);
        }
    }, []);

    const resizeDrawerWithKeyboard = useCallback((event) => {
        if (window.innerWidth <= DIFF_DRAWER_OVERLAY_BREAKPOINT) return;
        const step = event.shiftKey ? 64 : 16;
        let nextWidth = null;
        if (event.key === 'ArrowLeft') nextWidth = diffDrawerWidth + step;
        if (event.key === 'ArrowRight') nextWidth = diffDrawerWidth - step;
        if (event.key === 'Home') nextWidth = getDiffDrawerWidthBounds().min;
        if (event.key === 'End') nextWidth = getDiffDrawerWidthBounds().max;
        if (nextWidth == null) return;
        event.preventDefault();
        event.stopPropagation();
        setDiffDrawerWidth(clampDiffDrawerWidth(nextWidth));
    }, [diffDrawerWidth]);

    const resetDrawerWidth = useCallback(() => {
        setDiffDrawerWidth(getDefaultDiffDrawerWidth());
    }, []);

    const closeDiffDrawer = useCallback(() => {
        if (isCandidateDiff && candidateDirty) {
            message.warning(copy.saveBeforeClose);
            return;
        }
        setDiffDrawerOpen(false);
    }, [candidateDirty, copy.saveBeforeClose, isCandidateDiff]);

    useEffect(() => {
        if (!diffDrawerOpen) return undefined;

        const handleEscape = (event) => {
            if (event.key === 'Escape') {
                closeDiffDrawer();
            }
        };

        window.addEventListener('keydown', handleEscape);
        return () => window.removeEventListener('keydown', handleEscape);
    }, [closeDiffDrawer, diffDrawerOpen]);

    const getCodeDiff = (classNodeId) => {
        if (isCandidateDiff && candidateDirty) {
            message.warning(copy.saveBeforeClose);
            return false;
        }
        setIsRepositoryDiff(false);
        setIsCandidateDiff(false);
        setRepositoryDiffError(false);
        setCandidateFile(null);
        setCandidateDraft('');
        setCandidateDirty(false);
        setSelectedCodeNodeId(String(classNodeId));
        setDiffDrawerOpen(true);
        setLoadingCode(true)

        const candidateReady = confirmEnabled
            && (selectedType === "delete" || selectedType === "edit" || selectedType === "add");

        if (candidateReady) {
            API.getCandidateDiff(classNodeId, selectedType)
                .then((data) => {
                    setCandidateFile(data);
                    setCandidateDraft(data.modifiedContent || '');
                    setCodeDiff(data.diff || '');
                    setIsCandidateDiff(true);
                    setLoadingCode(false);
                })
                .catch((error) => {
                    console.error('Error fetching candidate Git diff:', error);
                    setLoadingCode(false);
                    message.error(errorMessage(error, copy.failedFetchCandidateDiff));
                });
        } else if (selectedType == "delete") {
            API.getDeleteDiffByClass(classNodeId).then((data) => {
                setCodeDiff(data)
                setLoadingCode(false);
            }).catch(error => {
                console.error('Error fetching code diff:', error)
                setLoadingCode(false);
                message.error(errorMessage(error, copy.failedFetchCodeDiff))
                // getCodeDiff(classNodeId)
            })
        } else if (selectedType == "edit" || selectedType === "add") {
            // Before code generation this remains the legacy, concatenated read-only context.
            API.getContextByClass(classNodeId).then((data) => {
                setCodeDiff(data)
                setLoadingCode(false);
            }).catch(error => {
                console.error('Error fetching code diff:', error)
                setLoadingCode(false);
                message.error(errorMessage(error, copy.failedFetchCodeContext))
            })
        } else if (selectedType == "select") {
            // 修改前显示Context
            API.getContextByClass(classNodeId).then((data) => {
                setCodeDiff(data)
                setLoadingCode(false);
            }).catch(error => {
                console.error('Error fetching code diff:', error)
                setLoadingCode(false);
                message.error(errorMessage(error, copy.failedFetchCodeContext))
                // getCodeDiff(classNodeId)
            })
        } else {
            setLoadingCode(false);
            setDiffDrawerOpen(false);
            alert(copy.noAction)
            return false;
        }

        return true;
    }

    const getRepositoryDiff = () => {
        if (isCandidateDiff && candidateDirty) {
            message.warning(copy.saveBeforeClose);
            return;
        }
        setIsRepositoryDiff(true);
        setIsCandidateDiff(false);
        setCandidateFile(null);
        setCandidateDirty(false);
        setRepositoryDiffError(false);
        setSelectedCodeNodeId('Git');
        setDiffDrawerOpen(true);
        setCodeDiff('');
        setLoadingCode(true);
        API.getRepositoryDiff()
            .then((data) => {
                setCodeDiff(typeof data === 'string' ? data : '');
                setLoadingCode(false);
            })
            .catch((error) => {
                setCodeDiff('');
                setRepositoryDiffError(true);
                setLoadingCode(false);
                message.error(errorMessage(error, copy.failedFetchRepositoryDiff));
            });
    };

    const saveCandidateDiff = (editorContent) => {
        if (!candidateFile || savingCandidate) return;

        const contentToSave = typeof editorContent === 'string' ? editorContent : candidateDraft;
        if (contentToSave === (candidateFile.modifiedContent || '')) return;
        setCandidateDraft(contentToSave);
        setSavingCandidate(true);
        API.updateCandidateDiff(candidateFile.key, selectedType, contentToSave)
            .then((data) => {
                setCandidateFile(data);
                setCandidateDraft((currentDraft) => {
                    const savedContent = data.modifiedContent || '';
                    if (currentDraft === contentToSave) {
                        setCandidateDirty(false);
                        return savedContent;
                    }
                    setCandidateDirty(currentDraft !== savedContent);
                    return currentDraft;
                });
                setCodeDiff(data.diff || '');
                setSavingCandidate(false);
                message.success(copy.candidateSaved);
            })
            .catch((error) => {
                setSavingCandidate(false);
                message.error(errorMessage(error, copy.failedSaveCandidate));
            });
    };

    const stageCandidateDiff = () => {
        if (!candidateFile || stagingCandidate) return;
        if (candidateDirty) {
            message.warning(copy.saveBeforeApply);
            return;
        }

        setStagingCandidate(true);
        API.stageCandidateFile(candidateFile.key)
            .then((status) => {
                setGitStatus(status || EMPTY_GIT_STATUS);
                setStagingCandidate(false);
                setCandidateFile((current) => current ? {...current, warning: null} : current);
                message.success(copy.candidateStaged);
            })
            .catch((error) => {
                setStagingCandidate(false);
                message.error(errorMessage(error, copy.failedStageCandidate));
            });
    };

    const [modal, contextHolder] = Modal.useModal();

    const resetGraphDiffDrawer = () => {
        setSelectedCodeNodeId('');
        setDiffDrawerOpen(false);
        setLoadingCode(false);
        setCodeDiff('');
        setIsRepositoryDiff(false);
        setIsCandidateDiff(false);
        setRepositoryDiffError(false);
        setCandidateFile(null);
        setCandidateDraft('');
        setCandidateDirty(false);
        setStagingCandidate(false);
    };

    const handleGraphBackgroundClick = (clearGraphSelection) => {
        if (isCandidateDiff && candidateDirty) {
            confirmDiscardAndRun(async (features) => {
                clearGraphSelection();
                restoreSelectedFeature(features, selectedFeatureItem?.featureId);
            });
            return false;
        }

        resetGraphDiffDrawer();
        return true;
    };

    const onClickItem = (item) => {
        if (selectedFeatureItem == null || item.featureId != selectedFeatureItem.featureId) {
            handleSelect(item)
        }
    }

    const handleSelect = (item, onSelected) => {
        if (hasPendingFeatureOperation()) {
            confirmDiscardAndRun(() => {
                goSelect(item)
                onSelected?.()
            });
        } else {
            goSelect(item)
            onSelected?.()
        }
    }

    const goSelect = (item) => {
        clearPostAgentTimers();
        clearFocusGraphStages();
        setSubmitEnabled(false);
        setModeTrans(false);
        setChatMode(false);
        setConfirmEnabled(false);

        setSelectedFeatureItem(item)
        setSelectedType("select")
        getFeatureGraphData(item.featureId, "select")
        setConfirmEnabled(false)
    }

    const handleDelete = (item) => {
        if (selectedType === "delete" && selectedFeatureItem?.featureId === item.featureId) {
            return;
        }
        if (hasPendingFeatureOperation()) {
            confirmDiscardAndRun(() => {
                goDelete(item)
            });
        } else {
            goDelete(item)
        }
    }

    const goDelete = (item) => {
        clearPostAgentTimers();
        clearFocusGraphStages();
        setSubmitEnabled(false);
        setModeTrans(false);
        setChatMode(false);
        setConfirmEnabled(false);

        setSelectedFeatureItem(item)
        setSelectedType("delete")
        getFeatureGraphData(item.featureId, "delete")
        if (isPythonProject) {
            preparePythonDelete(item)
        } else {
            setConfirmEnabled(true)
        }
    }

    const handleEdit = (item) => {
        if (selectedType === "edit" && selectedFeatureItem?.featureId === item.featureId) {
            return;
        }
        if (hasPendingFeatureOperation()) {
            confirmDiscardAndRun(() => {
                goEdit(item)
            });
        } else {
            goEdit(item)
        }
    }

    const goEdit = (item) => {
        clearPostAgentTimers();
        clearFocusGraphStages();
        setSubmitEnabled(true);
        setConfirmEnabled(false);
        setModeTrans(false);
        setChatMode(false);


        setSelectedFeatureItem(item)
        setSelectedType("edit")
        setEditedText(getFeatureDescription(item, language))
        getFeatureGraphData(item.featureId, "edit")
    }

    const [editedText, setEditedText] = useState('')
    const [activeKey, setActiveKey] = useState(null);
    const [featureSearchText, setFeatureSearchText] = useState('');
    const [featureScrollTarget, setFeatureScrollTarget] = useState(null);
    const featureScrollContainerRef = useRef(null);

    const featureSearchOptions = useMemo(() => {
        const query = featureSearchText.trim().toLocaleLowerCase();
        if (!query) return [];

        const matches = [];
        featureData.forEach((module, moduleIndex) => {
            const displayedModuleDescription = getModuleDescription(module, language);
            const searchableModuleDescriptions = [
                getModuleDescription(module, "zh"),
                getModuleDescription(module, "en"),
            ];

            module.featureList.forEach((item) => {
                const displayIndex = getFeatureDisplayIndex(module.featureList, item);
                const itemNumber = `${moduleIndex + 1}.${displayIndex}`;
                const displayedDescription = getFeatureDescription(item, language);
                const searchableText = [
                    itemNumber,
                    item.featureId,
                    getFeatureDescription(item, "zh"),
                    getFeatureDescription(item, "en"),
                    ...searchableModuleDescriptions,
                ].join(" ").toLocaleLowerCase();

                if (!searchableText.includes(query)) return;

                const selectionText = `${itemNumber} ${displayedDescription}`.trim();
                matches.push({
                    key: `${String(module.moduleId)}-${String(item.featureId)}`,
                    value: selectionText,
                    moduleId: String(module.moduleId),
                    featureId: String(item.featureId),
                    label: (
                        <div className={styles.featureSearchOption}>
                            <div className={styles.featureSearchOptionTitle}>{selectionText}</div>
                            <div className={styles.featureSearchOptionModule}>
                                {`${moduleIndex + 1}. ${displayedModuleDescription}`}
                            </div>
                        </div>
                    ),
                });
            });
        });

        return matches.slice(0, 50);
    }, [featureData, featureSearchText, language]);

    useEffect(() => {
        if (!featureScrollTarget || String(activeKey) !== featureScrollTarget.moduleId) {
            return undefined;
        }

        const timeoutId = window.setTimeout(() => {
            const targetElement = Array.from(
                featureScrollContainerRef.current?.querySelectorAll("[data-feature-id][data-module-id]") || []
            ).find((element) => (
                element.dataset.featureId === featureScrollTarget.featureId
                && element.dataset.moduleId === featureScrollTarget.moduleId
            ));

            if (!targetElement) return;

            const reduceMotion = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
            targetElement.scrollIntoView({
                behavior: reduceMotion ? "auto" : "smooth",
                block: "center",
                inline: "nearest",
            });
            setFeatureScrollTarget(null);
        }, 250);

        return () => window.clearTimeout(timeoutId);
    }, [activeKey, featureData, featureScrollTarget]);

    const handleFeatureSearchSelect = (value) => {
        const selectedOption = featureSearchOptions.find((option) => option.value === value);
        if (!selectedOption) return;

        const targetModule = featureData.find(
            (module) => String(module.moduleId) === selectedOption.moduleId
        );
        const targetFeature = targetModule?.featureList.find(
            (feature) => String(feature.featureId) === selectedOption.featureId
        );
        if (!targetModule || !targetFeature) return;

        setFeatureSearchText(value);
        handleSelect(targetFeature, () => {
            setDiffDrawerOpen(false);
            setActiveKey(targetModule.moduleId);
            setFeatureScrollTarget({
                moduleId: selectedOption.moduleId,
                featureId: selectedOption.featureId,
            });
        });
    };

    const changeActiveKey = (newActiveKey) => {
        if (hasPendingFeatureOperation()) {
            confirmDiscardAndRun(() => {
                setActiveKey(newActiveKey)
            });
        } else {
            setActiveKey(newActiveKey)
            setSelectedFeatureItem(null)
        }
    }

    // 新增元素模板，根据需求修改
    const createNewFeature = (moduleId) => {
        let timestamp = Date.now();
        return {
            featureId: `new-${timestamp}`, // 唯一id
            featureDescription: `${copy.newSubfeature}${timestamp}`,
            isNew: true,
            moduleId: moduleId, // 添加moduleId
        }
    }

    const handleAdd = (module) => {
        if (hasPendingFeatureOperation() && !(selectedType === "add" && activeKey === module.moduleId)) {
            confirmDiscardAndRun(() => {
                goAdd(module)
            });
        } else if (selectedType == "add" && activeKey == module.moduleId) {
            return
        } else {
            goAdd(module)
        }
    }

    // Add 按钮事件
    const goAdd = (module) => {
        clearPostAgentTimers();
        clearFocusGraphStages();
        setSubmitEnabled(true)
        setModeTrans(false);
        setChatMode(false);
        setConfirmEnabled(false);


        setActiveKey(module.moduleId)
        setSelectedType("add")

        let newFeatureItem = createNewFeature(module.moduleId);

        setFeatureData((prev) =>
            prev.map((m) => {
                if (m.moduleId === module.moduleId) {
                    return {
                        ...m,
                        featureList: [newFeatureItem, ...m.featureList],
                    };
                }
                return m;
            })
        );
        setEditedText(newFeatureItem.featureDescription)
        setSelectedFeatureItem(newFeatureItem)
        getFeatureGraphData(null, "add")

    };

    const [chatMode, setChatMode] = useState(false)
    const [chatContent, setChatContent] = useState("")
    const containerRef = useRef(null);
    const [modeTrans, setModeTrans] = useState(false)

    useEffect(() => {
        if (containerRef.current) {
            requestAnimationFrame(() => {
                containerRef.current.scrollTop = containerRef.current.scrollHeight;
            });
        }
    }, [chatContent]);


    const handleChat = (eventSource) => {
        clearPostAgentTimers()
        eventSourceRef.current = eventSource
        setChatContent("");
        setChatMode(true)
        setModeTrans(true)
        if (isPythonProject) {
            setOperationProgress({
                operation: selectedType === 'add' ? "python-add" : "python-modify",
                stage: "agent-stream",
                message: copy.streamingPythonAgent,
                currentStep: 8,
                totalSteps: 8,
                running: true,
                failed: false
            })
        }
        eventSource.onmessage = (event) => {
            const decoded = decodeURIComponent(escape(atob(event.data)));
            setChatContent(prev => prev + decoded);
        };
        eventSource.onerror = () => {

            eventSource.close(); // 关闭连接
            eventSourceRef.current = null
            if (isPythonProject) {
                stopProgressPolling()
                setOperationProgress({
                    operation: selectedType === 'add' ? "python-add" : "python-modify",
                    stage: "complete",
                    message: copy.codeGenerationFinished,
                    currentStep: 8,
                    totalSteps: 8,
                    running: false,
                    failed: false
                })
            }
            setLoadingFeatureList(false);
            setConfirmEnabled(true)

            graphRefreshTimerRef.current = setTimeout(() => {
                getFeatureGraphData(0, "new")
                graphRefreshTimerRef.current = null
            }, 1500); // 延迟 3000 毫秒（3秒）

            chatCloseTimerRef.current = setTimeout(() => {
                setChatMode(false);
                chatCloseTimerRef.current = null
            }, 3000); // 延迟 3000 毫秒（3秒）
        };
        return () => {
            eventSource.close();
            eventSourceRef.current = null;
        };
    }

    const [confirmEnabled, setConfirmEnabled] = useState(false)
    const [submitEnabled, setSubmitEnabled] = useState(false)

    const hasPendingFeatureOperation = () => (
        selectedType === "delete" || selectedType === "edit" || selectedType === "add"
    );

    const discardPendingChanges = async () => {
        setLoadingConfirm(true);
        try {
            const status = await API.discardFeatureChanges();
            setGitStatus(status || EMPTY_GIT_STATUS);
            clearPostAgentTimers();
            clearFocusGraphStages();
            resetGraphDiffDrawer();
            setConfirmEnabled(false);
            setSubmitEnabled(false);
            setModeTrans(false);
            setChatMode(false);
            setOperationProgress(null);
            setSelectedType(null);
            setSelectedFeatureItem(null);
            const features = await getFeatureData();
            message.success(copy.discardSuccess);
            return features;
        } catch (error) {
            message.error(errorMessage(error, copy.failedDiscardChanges));
            throw error;
        } finally {
            setLoadingConfirm(false);
        }
    };

    const restoreSelectedFeature = (features, preferredFeatureId) => {
        const modules = Array.isArray(features) ? features : [];
        const preferred = modules
            .flatMap((module) => module.featureList || [])
            .find((feature) => String(feature.featureId) === String(preferredFeatureId));
        const fallbackModule = modules.find((module) => (module.featureList || []).length > 0);
        const target = preferred || fallbackModule?.featureList?.[0];
        if (target) {
            const targetModule = modules.find((module) => (module.featureList || []).some(
                (feature) => String(feature.featureId) === String(target.featureId)
            ));
            if (targetModule) setActiveKey(targetModule.moduleId);
            goSelect(target);
        } else {
            setGraphData({nodes: [], edges: []});
            setLoadingFeatureGraph(false);
            setLoadingCode(false);
        }
    };

    const confirmDiscardAndRun = (nextAction) => {
        modal.confirm({
            title: copy.discardAllTitle,
            icon: <ExclamationCircleOutlined/>,
            content: copy.discardAllDescription,
            okText: copy.discard,
            okButtonProps: {danger: true},
            cancelText: copy.cancel,
            onOk: async () => {
                const features = await discardPendingChanges();
                await nextAction?.(features);
            },
        });
    };

    const requestDiscardCurrentOperation = () => {
        const preferredFeatureId = selectedFeatureItem?.featureId;
        confirmDiscardAndRun((features) => restoreSelectedFeature(features, preferredFeatureId));
    };

    useEffect(() => {
        if (confirmEnabled) {
            refreshGitStatus();
        }
    }, [confirmEnabled]);

    const errorMessage = (error, fallback) => {
        const data = error?.response?.data;
        if (typeof data === "string" && data.trim()) {
            return data;
        }
        if (data?.message) {
            return data.message;
        }
        if (error?.message) {
            return error.message;
        }
        return fallback;
    }

    const progressPercent = () => {
        if (!operationProgress) {
            return 0;
        }
        const total = operationProgress.totalSteps || 1;
        const current = operationProgress.currentStep || 0;
        return Math.max(0, Math.min(100, Math.round((current / total) * 100)));
    }

    const isFeatureModifyProgress = () => {
        return operationProgress?.operation === "python-modify"
            || operationProgress?.operation === "python-add"
            || operationProgress?.operation === "python-delete"
            || operationProgress?.stage === "submit"
            || operationProgress?.stage === "agent-stream";
    }

    const featureListLoadingOverlay = () => {
        if (!isFeatureModifyProgress()) {
            return copy.fetchingFeatureSummary;
        }
        return (
            <div className={styles.featureListProgress}>
                <div className={styles.featureListProgressMessage}>
                    {operationProgress?.message || copy.preparingModification}
                </div>
                <Progress
                    percent={progressPercent()}
                    size="small"
                    status={operationProgress?.failed ? "exception" : "active"}
                    showInfo
                />
            </div>
        );
    }

    const stopProgressPolling = () => {
        if (progressTimerRef.current) {
            clearInterval(progressTimerRef.current);
            progressTimerRef.current = null;
        }
        expectedProgressOperationRef.current = null;
    }

    const fetchOperationProgress = (expectedOperation = expectedProgressOperationRef.current) => {
        API.getLlmProgress()
            .then((progress) => {
                if (expectedOperation && progress.operation !== expectedOperation) {
                    return;
                }
                setOperationProgress(progress)
                if (!progress.running || progress.failed) {
                    stopProgressPolling()
                }
            })
            .catch((error) => {
                console.error('Error fetching operation progress:', error)
            })
    }

    const startProgressPolling = (expectedOperation) => {
        stopProgressPolling()
        expectedProgressOperationRef.current = expectedOperation || null;
        fetchOperationProgress(expectedOperation)
        progressTimerRef.current = setInterval(() => fetchOperationProgress(expectedOperation), 1000)
    }

    const loadFocusGraphStages = () => {
        if (!isPythonProject || (selectedType !== "edit" && selectedType !== "add")) {
            clearFocusGraphStages()
            return Promise.resolve([])
        }
        return API.getFocusGraphStages()
            .then((stages) => {
                const normalized = Array.isArray(stages) ? stages : []
                setFocusGraphStages(normalized)
                return normalized
            })
            .catch((error) => {
                console.error('Error fetching FocusGraph stages:', error)
                setFocusGraphStages([])
                return []
            })
    }

    useEffect(() => {
        return () => {
            stopProgressPolling()
            clearPostAgentTimers()
        }
    }, [])

    const preparePythonDelete = (item) => {
        setLoadingFeatureList(true)
        setSubmitEnabled(false)
        setConfirmEnabled(false)
        setOperationProgress({
            operation: "python-delete",
            stage: "submit",
            message: copy.submittingFeatureDeletion,
            currentStep: 0,
            totalSteps: 4,
            running: true,
            failed: false
        })
        startProgressPolling("python-delete")
        API.deleteFeature({
            featureId: item.featureId,
            featureDescription: item.featureDescription
        })
            .then(() => {
                stopProgressPolling()
                setLoadingFeatureList(false)
                setConfirmEnabled(true)
                setChatMode(false)
                setModeTrans(false)
                API.getLlmProgress()
                    .then((progress) => {
                        setOperationProgress(progress)
                    })
                    .catch(() => {
                        setOperationProgress({
                            operation: "python-delete",
                            stage: "complete",
                            message: copy.pythonDeleteReady,
                            currentStep: 4,
                            totalSteps: 4,
                            running: false,
                            failed: false
                        })
                    })
                getFeatureGraphData(0, "new")
            })
            .catch((error) => {
                console.error('Error Delete Feature:', error)
                stopProgressPolling()
                setLoadingFeatureList(false)
                const msg = errorMessage(error, copy.failedPrepareDeletion)
                setOperationProgress({
                    operation: "python-delete",
                    stage: "failed",
                    message: msg,
                    currentStep: operationProgress?.currentStep || 0,
                    totalSteps: operationProgress?.totalSteps || 4,
                    running: false,
                    failed: true,
                    error: msg
                })
                message.error(msg)
            })
    }

    const submitEdit = (item) => {
        if (!selectedModel) return;

        setLoadingFeatureList(true)
        setSubmitEnabled(false)
        clearFocusGraphStages()
        const expectedOperation = selectedType === 'add' ? "python-add" : "python-modify";
        if (isPythonProject) {
            setOperationProgress({
                operation: expectedOperation,
                stage: "submit",
                message: selectedType === 'add' ? copy.submittingFeatureAddition : copy.submittingFeatureModification,
                currentStep: 0,
                totalSteps: 8,
                running: true,
                failed: false
            })
            startProgressPolling(expectedOperation)
        }

        if (selectedType == 'edit') {
            // console.log(editedText);
            API.modifyFeature(editedText, apiLanguage)
                .then(async (data) => {
                    if (isPythonProject) {
                        await loadFocusGraphStages()
                    }
                    handleChat(API.getLlmResponse(apiLanguage, selectedModel))
                })
                .catch((error) => {
                    console.error('Error Modify Feature:', error)
                    if (isPythonProject) {
                        stopProgressPolling()
                        const msg = errorMessage(error, copy.failedPrepareModification)
                        setOperationProgress({
                            operation: expectedOperation,
                            stage: "failed",
                            message: msg,
                            currentStep: operationProgress?.currentStep || 0,
                            totalSteps: operationProgress?.totalSteps || 8,
                            running: false,
                            failed: true,
                            error: msg
                        })
                        message.error(msg)
                    }
                    setLoadingFeatureList(false)
                    setSubmitEnabled(true)
                });
        } else if (selectedType == 'add') {
            // 构建请求参数，包含moduleId
            const requestData = {
                featureDescription: editedText,
                moduleId: item.moduleId || selectedFeatureItem.moduleId,
                language: apiLanguage,
            };

            API.addFeature(requestData)
                .then(async (data) => {
                    if (isPythonProject) {
                        await loadFocusGraphStages()
                    }
                    handleChat(API.getLlmResponse(apiLanguage, selectedModel))
                })
                .catch((error) => {
                    console.error('Error Add Feature:', error)
                    if (isPythonProject) {
                        stopProgressPolling()
                        const msg = errorMessage(error, copy.failedAddFeature)
                        setOperationProgress({
                            operation: "python-add",
                            stage: "failed",
                            message: msg,
                            currentStep: operationProgress?.currentStep || 0,
                            totalSteps: operationProgress?.totalSteps || 8,
                            running: false,
                            failed: true,
                            error: msg
                        })
                        message.error(msg)
                    }
                    setLoadingFeatureList(false)
                    setSubmitEnabled(true)
                });
        }
    }

    const finishCompleteCommit = async (commitResult) => {
        const previousFeatureId = selectedFeatureItem?.featureId;
        const features = await getFeatureData();
        const preferredFeatureId = selectedType === 'add'
            ? commitResult.featureId
            : selectedType === 'edit' ? previousFeatureId : null;
        restoreSelectedFeature(features, preferredFeatureId);
        setConfirmEnabled(false);
        setOperationProgress(null);
        resetGraphDiffDrawer();
    };

    const performFeatureCommit = async () => {
        setLoadingConfirm(true);
        try {
            const result = await API.commitFeatureChanges(selectedType);
            setGitStatus(result.status || EMPTY_GIT_STATUS);
            resetGraphDiffDrawer();
            if (result.commitScope === 'COMPLETE') {
                await finishCompleteCommit(result);
                message.success(copy.completeCommitSuccess);
            } else {
                if (selectedType === 'delete' && !isPythonProject) {
                    getFeatureGraphData(selectedFeatureItem?.featureId, 'delete');
                } else {
                    getFeatureGraphData(0, 'new');
                }
                message.success(copy.partialCommitSuccess);
            }
        } catch (error) {
            message.error(errorMessage(error, copy.failedCommitChanges));
            throw error;
        } finally {
            setLoadingConfirm(false);
        }
    };

    const requestFeatureCommit = async () => {
        if (candidateDirty || savingCandidate) {
            message.warning(copy.saveBeforeApply);
            return;
        }
        const status = await refreshGitStatus();
        const stagedCount = status.stagedPaths?.length || 0;
        if (!stagedCount) {
            message.warning(copy.noStagedFiles);
            return;
        }

        const complete = status.commitScope === 'COMPLETE';
        const remainingCount = status.unstagedCandidatePaths?.length || 0;
        modal.confirm({
            title: complete ? copy.completeCommitTitle : copy.partialCommitTitle,
            icon: <ExclamationCircleOutlined/>,
            content: (
                <div className={styles.commitConfirmation}>
                    <p>{complete ? copy.completeCommitDescription : copy.partialCommitDescription}</p>
                    <p>
                        {language === 'zh'
                            ? `本次提交 ${stagedCount} 个已暂存文件${remainingCount ? `，仍有 ${remainingCount} 个候选文件未确认` : ''}。`
                            : `This commit contains ${stagedCount} staged file${stagedCount === 1 ? '' : 's'}${remainingCount ? `; ${remainingCount} candidate file${remainingCount === 1 ? '' : 's'} remain unconfirmed` : ''}.`}
                    </p>
                </div>
            ),
            okText: copy.commitChanges,
            cancelText: copy.cancel,
            onOk: performFeatureCommit,
        });
    };

    const hasFocusGraphStages = isPythonProject
        && (selectedType === "edit" || selectedType === "add")
        && Array.isArray(focusGraphStages)
        && focusGraphStages.length > 0;
    const stagedFileCount = gitStatus.stagedPaths?.length || 0;
    const candidateIsStaged = Boolean(
        candidateFile?.path && gitStatus.stagedPaths?.includes(candidateFile.path)
    );
    const showGitOperationActions = hasPendingFeatureOperation()
        && (confirmEnabled
            || stagedFileCount > 0
            || (gitStatus.pendingCandidatePaths?.length || 0) > 0);

    const workspaceContainerWidth = getWorkspaceContainerWidth(viewportWidth);
    const workspaceWidthWithDrawer = viewportWidth <= DIFF_DRAWER_OVERLAY_BREAKPOINT
        ? viewportWidth
        : Math.max(0, viewportWidth - diffDrawerWidth);
    const featurePanelCondensed = diffDrawerOpen
        && viewportWidth > DIFF_DRAWER_OVERLAY_BREAKPOINT;
    const splitterLayoutCondensed = featurePanelCondensed && diffDrawerLayoutSettled;
    const featurePanelDrawerSize = Math.round(Math.max(
        FEATURE_PANEL_MINIMAL_WIDTH,
        Math.min(380, workspaceWidthWithDrawer - GRAPH_PANEL_TARGET_WIDTH)
    ));
    const featurePanelMinimal = splitterLayoutCondensed
        && featurePanelDrawerSize < FEATURE_PANEL_DESCRIPTION_MIN_WIDTH;
    const graphPanelCompact = splitterLayoutCondensed
        && workspaceWidthWithDrawer - featurePanelDrawerSize < 480;


    return (
        <ConfigProvider theme={DEBLOATING_THEME}>
            {contextHolder}
            <Spin
                wrapperClassName={styles.pageSpin}
                spinning={loadingConfirm}
                tip={copy.committingChanges}
                size={"large"}
            >
                <div
                    className={classNames(styles.debloatingPage, {
                        [styles.diffDrawerResizing]: diffDrawerResizing,
                    })}
                    style={{
                        "--diff-drawer-width": `${diffDrawerWidth}px`,
                        "--workspace-container-width": `${workspaceContainerWidth}px`,
                    }}
                >
                    <main
                        className={classNames(styles.workspace, {
                            [styles.workspaceWithDrawer]: diffDrawerOpen,
                        })}
                    >
                        <Splitter
                            className={styles.background_area}
                            onResize={(sizes) => {
                                if (!diffDrawerOpen && Number.isFinite(sizes[0])) {
                                    setFeaturePanelSize(sizes[0]);
                                }
                            }}
                        >
                    {/*左侧可滚动功能列表 */}
                    <Splitter.Panel
                        size={splitterLayoutCondensed ? featurePanelDrawerSize : featurePanelSize}
                        min={splitterLayoutCondensed ? featurePanelDrawerSize : "20%"}
                        max={splitterLayoutCondensed ? featurePanelDrawerSize : "60%"}
                        resizable={!splitterLayoutCondensed}
                        className={classNames(styles.main_area, {
                            [styles.featurePanelAreaMinimal]: featurePanelMinimal,
                        })}
                    >
                        <Card
                            className={classNames(styles.panelCard, styles.featurePanelCard, {
                                [styles.featurePanelCardCompressed]: featurePanelCondensed,
                                [styles.featurePanelCardMinimal]: featurePanelMinimal,
                            })}
                            title={
                                <div className={styles.card_title} title={copy.featurePanel}>
                                    {featurePanelMinimal
                                        ? (language === "zh" ? "功" : "F")
                                        : copy.featurePanel}
                                </div>
                            }
                            extra={
                                <label className={styles.modelControl}>
                                    <span className={styles.modelLabel}>{copy.model}</span>
                                    <Select
                                        aria-label={copy.model}
                                        className={styles.modelSelect}
                                        value={selectedModel}
                                        options={models.map((model) => ({value: model, label: model}))}
                                        onChange={setSelectedModel}
                                        loading={loadingModels}
                                        disabled={loadingModels || models.length === 0 || loadingFeatureList}
                                        placeholder={loadingModels ? copy.loadingModels : copy.modelUnavailable}
                                        showSearch
                                        optionFilterProp="label"
                                        popupMatchSelectWidth={false}
                                        size="small"
                                    />
                                </label>
                            }
                            bordered={false}
                            bodyStyle={{paddingTop: 12, paddingBottom: 4}}
                        >
                            <div className={styles.featurePanelBody}>
                                <AutoComplete
                                    className={styles.featureSearch}
                                    value={featureSearchText}
                                    options={featureSearchOptions}
                                    onChange={setFeatureSearchText}
                                    onSelect={handleFeatureSearchSelect}
                                    filterOption={false}
                                    notFoundContent={featureSearchText.trim() ? copy.noFeatureMatches : null}
                                    disabled={loadingFeatureList || featureData.length === 0}
                                >
                                    <Input
                                        allowClear
                                        prefix={<SearchOutlined/>}
                                        placeholder={copy.searchFeatures}
                                        aria-label={copy.searchFeatures}
                                    />
                                </AutoComplete>
                                <Spin
                                    wrapperClassName={classNames(styles.panelSpin, styles.featureListSpin)}
                                    spinning={loadingFeatureList}
                                    tip={featureListLoadingOverlay()}
                                    size="large"
                                >
                                <div ref={featureScrollContainerRef} className={styles.scrollContainer}>
                                    <Collapse
                                        accordion
                                        bordered={false}
                                        className={styles.moduleCollapse}
                                        activeKey={activeKey}
                                        onChange={(activeKey) => changeActiveKey(activeKey)}
                                    >
                                        {featureData.map((module, moduleIndex) => (
                                            <Panel
                                                header={
                                                    <div className={styles.moduleTitle}>
                                                        <div className={styles.moduleHeading}>
                                                            <span className={styles.moduleNumber}>{moduleIndex + 1}.</span>{" "}
                                                            <span className={styles.moduleDescription}>
                                                                {getModuleDescription(module, language)}
                                                            </span>
                                                        </div>
                                                        <div className={styles.iconContainer}>
                                                            <Button
                                                                type="text"
                                                                icon={<PlusSquareTwoTone twoToneColor="#13B54D"/>}
                                                                onClick={(e) => {
                                                                    e.stopPropagation()
                                                                    handleAdd(module)
                                                                }}
                                                                className={classNames(styles.icon, {
                                                                    [styles.selectedIcon]: module.moduleId === activeKey && (selectedType === 'add'),
                                                                })}
                                                            />
                                                        </div>
                                                    </div>
                                                }
                                                key={module.moduleId}
                                            >
                                                <List
                                                    dataSource={module.featureList}
                                                    renderItem={(item, featureIndex) => {
                                                        // // 调试信息：打印当前feature的信息
                                                        // if (item.isNewGenerated) {
                                                        //     console.log('Rendering modified feature:', item);
                                                        // }
                                                        const displayIndex = getFeatureDisplayIndex(module.featureList, item);
                                                        return (
                                                            <FeatureListItem
                                                                item={item}
                                                                moduleId={module.moduleId}
                                                                itemNumber={`${moduleIndex + 1}.${displayIndex}`}
                                                                description={getFeatureDescription(item, language)}
                                                                selectedType={selectedType}
                                                                selectedFeatureItem={selectedFeatureItem}
                                                                submitEnabled={submitEnabled}
                                                                selectedModel={selectedModel}
                                                                editedText={editedText}
                                                                setEditedText={setEditedText}
                                                                copy={copy}
                                                                onClickItem={onClickItem}
                                                                submitEdit={submitEdit}
                                                                handleDelete={handleDelete}
                                                                handleEdit={handleEdit}
                                                                compressed={featurePanelCondensed}
                                                                minimal={featurePanelMinimal}
                                                            />
                                                        );
                                                    }}
                                                />
                                            </Panel>
                                        ))}
                                    </Collapse>
                                </div>
                                </Spin>
                            </div>
                        </Card>
                    </Splitter.Panel>


                    {/*中间功能去臃肿详情*/}
                    <Splitter.Panel min={splitterLayoutCondensed ? 0 : "55%"} className={styles.main_area}>
                        <div className={styles.panelShell}>
                            <Card
                                className={classNames(styles.panelCard, {
                                    [styles.graphPanelCardCompact]: graphPanelCompact,
                                    [styles.graphPanelCardGitActions]: showGitOperationActions,
                                })}
                                title={
                                    <div className={styles.card_title}>
                                        <div>
                                            {chatMode ? copy.agentPanel : copy.graphPanel}
                                        </div>
                                        <div className={styles.panelActions}>
                                            {showGitOperationActions && (
                                                <>
                                                    <Tooltip title={stagedFileCount ? copy.commitChanges : copy.noStagedFiles}>
                                                        <Button
                                                            type="primary"
                                                            size="small"
                                                            icon={<CheckCircleOutlined/>}
                                                            disabled={!stagedFileCount || candidateDirty || savingCandidate || stagingCandidate}
                                                            onClick={(event) => {
                                                                event.stopPropagation();
                                                                requestFeatureCommit();
                                                            }}
                                                            className={styles.gitCommitButton}
                                                            aria-label={copy.commitChanges}
                                                        >
                                                            {stagedFileCount > 0 ? stagedFileCount : null}
                                                        </Button>
                                                    </Tooltip>
                                                    <Tooltip title={copy.discardAllChanges}>
                                                        <Button
                                                            type="text"
                                                            danger
                                                            icon={<UndoOutlined/>}
                                                            disabled={loadingConfirm}
                                                            onClick={(event) => {
                                                                event.stopPropagation();
                                                                requestDiscardCurrentOperation();
                                                            }}
                                                            className={styles.icon}
                                                            aria-label={copy.discardAllChanges}
                                                        />
                                                    </Tooltip>
                                                </>
                                            )}
                                            <Tooltip title={copy.viewRepositoryDiff}>
                                                <Button
                                                    type="text"
                                                    icon={<DiffOutlined/>}
                                                    onClick={(event) => {
                                                        event.stopPropagation();
                                                        getRepositoryDiff();
                                                    }}
                                                    className={styles.icon}
                                                />
                                            </Tooltip>
                                            <Tooltip
                                                title={hasFocusGraphStages ? copy.focusGraphReady : copy.focusGraphPending}
                                            >
                                                <Button
                                                    type="text"
                                                    icon={<ApartmentOutlined/>}
                                                    onClick={(event) => {
                                                        event.stopPropagation();
                                                        setFocusGraphModalOpen(true);
                                                    }}
                                                    className={styles.icon}
                                                    disabled={!hasFocusGraphStages}
                                                />
                                            </Tooltip>
                                            <Button
                                                type="text"
                                                icon={<SwapOutlined/>}
                                                onClick={(e) => {
                                                    e.stopPropagation();
                                                    setChatMode(!chatMode)
                                                }}
                                                className={styles.icon}
                                                disabled={!modeTrans}
                                            />
                                        </div>
                                    </div>
                                }
                                bordered={false}
                                bodyStyle={{paddingTop: 12, paddingBottom: 4}}
                            >
                                <div className={classNames(styles.panelView, {
                                    [styles.panelViewHidden]: !chatMode,
                                })}>
                                    <div ref={containerRef} className={styles.scrollContainer}>
                                        <MarkdownRendererComponent content={chatContent}/>
                                    </div>
                                </div>
                                <div
                                    onClick={(event) => event.stopPropagation()}
                                    className={classNames(styles.panelView, {
                                        [styles.panelViewHidden]: chatMode,
                                    })}
                                >
                                    <Spin
                                        wrapperClassName={styles.panelSpin}
                                        spinning={loadingFeatureGraph}
                                        tip={copy.fetchingGraph}
                                        size={"large"}
                                    >
                                        <FeatureGraph
                                            graphData={graphData}
                                            onNodeClick={getCodeDiff}
                                            onBackgroundClick={handleGraphBackgroundClick}
                                            nodeFontSize={17}
                                        />
                                    </Spin>
                                </div>
                            </Card>
                        </div>
                    </Splitter.Panel>

                        </Splitter>
                    </main>

                    <aside
                        className={classNames(styles.diffDrawer, {
                            [styles.diffDrawerOpen]: diffDrawerOpen,
                        })}
                        aria-hidden={!diffDrawerOpen}
                        aria-label={copy.changesPanel}
                    >
                        <div
                            className={styles.diffDrawerResizeHandle}
                            role="separator"
                            aria-label={copy.resizeChangesPanel}
                            aria-orientation="vertical"
                            aria-valuemin={getDiffDrawerWidthBounds().min}
                            aria-valuemax={getDiffDrawerWidthBounds().max}
                            aria-valuenow={diffDrawerWidth}
                            tabIndex={diffDrawerOpen ? 0 : -1}
                            title={copy.resizeChangesPanel}
                            onPointerDown={startDrawerResize}
                            onPointerMove={resizeDrawer}
                            onPointerUp={stopDrawerResize}
                            onPointerCancel={stopDrawerResize}
                            onLostPointerCapture={stopDrawerResize}
                            onKeyDown={resizeDrawerWithKeyboard}
                            onDoubleClick={resetDrawerWidth}
                        />
                        <header className={styles.diffDrawerHeader}>
                            <div className={styles.diffDrawerTitle}>
                                <h2>{isRepositoryDiff
                                    ? copy.repositoryDiff
                                    : isCandidateDiff ? copy.candidateDiff : copy.changesPanel}</h2>
                                {(candidateFile?.path || selectedCodeNodeId) && (
                                    <p>{candidateFile?.path || selectedCodeNodeId}</p>
                                )}
                            </div>
                            <div className={styles.diffDrawerHeaderActions}>
                                {isCandidateDiff && (
                                    <Tooltip title={copy.saveCandidate}>
                                        <Button
                                            type="primary"
                                            icon={<SaveOutlined/>}
                                            loading={savingCandidate}
                                            disabled={!candidateDirty || candidateIsStaged}
                                            onClick={() => saveCandidateDiff()}
                                        >
                                            {savingCandidate ? copy.savingCandidate : copy.saveCandidate}
                                        </Button>
                                    </Tooltip>
                                )}
                                <Button
                                    type="text"
                                    icon={<CloseOutlined/>}
                                    aria-label={copy.closeChangesPanel}
                                    className={styles.diffDrawerClose}
                                    onClick={closeDiffDrawer}
                                />
                            </div>
                        </header>
                        <div className={classNames(styles.diffDrawerBody, {
                            [styles.diffDrawerBodyEditor]: isCandidateDiff,
                        })}>
                            <Spin spinning={loadingCode} tip={copy.fetchingCode} size="large">
                                {isCandidateDiff && candidateFile ? (
                                    <div className={styles.candidateDiffWorkspace}>
                                        <div className={styles.gitDiffMeta}>
                                            <DiffOutlined/>
                                            <span>{copy.gitGeneratedDiff}</span>
                                            {candidateFile.newFile && <span className={styles.diffStatus}>A</span>}
                                            {candidateFile.deleted && <span className={styles.diffStatusDanger}>D</span>}
                                        </div>
                                        {candidateFile.warning && (
                                            <Alert type="warning" showIcon message={candidateFile.warning}/>
                                        )}
                                        <div className={styles.candidateEditorView}>
                                            <React.Suspense fallback={<div className={styles.emptyDiff}>{copy.fetchingCode}</div>}>
                                                <GitDiffEditor
                                                    file={candidateFile}
                                                    value={candidateDraft}
                                                    onChange={(value) => {
                                                        setCandidateDraft(value);
                                                        setCandidateDirty(value !== (candidateFile.modifiedContent || ''));
                                                    }}
                                                    onSave={saveCandidateDiff}
                                                    readOnly={candidateIsStaged}
                                                />
                                            </React.Suspense>
                                        </div>
                                    </div>
                                ) : isRepositoryDiff && repositoryDiffError ? (
                                    <div className={styles.emptyDiff}>{copy.failedFetchRepositoryDiff}</div>
                                ) : isRepositoryDiff && !codeDiff.trim() ? (
                                    <div className={styles.emptyDiff}>{copy.noRepositoryChanges}</div>
                                ) : (
                                    <CodeDiffComponent
                                        diffText={codeDiff}
                                        isPlainCode={false}
                                    />
                                )}
                                {isCandidateDiff && candidateFile && (
                                    <Tooltip
                                        title={candidateDirty
                                            ? copy.saveBeforeApply
                                            : !confirmEnabled ? copy.noSubmittedChanges : ""}
                                    >
                                        <div className={styles.centerButtonWrapper}>
                                            <Button
                                                type="primary"
                                                icon={<CheckOutlined/>}
                                                loading={stagingCandidate}
                                                disabled={!confirmEnabled
                                                    || candidateDirty
                                                    || savingCandidate
                                                    || candidateIsStaged}
                                                onClick={stageCandidateDiff}
                                            >
                                                {candidateIsStaged
                                                    ? copy.fileStaged
                                                    : stagingCandidate ? copy.stagingFile : copy.confirmFile}
                                            </Button>
                                        </div>
                                    </Tooltip>
                                )}
                            </Spin>
                        </div>
                    </aside>
                </div>
            </Spin>
            <FocusGraphStageModal
                open={focusGraphModalOpen}
                onClose={() => setFocusGraphModalOpen(false)}
                stages={focusGraphStages}
            />
        </ConfigProvider>

    )
}

export default DebloatingPage;
