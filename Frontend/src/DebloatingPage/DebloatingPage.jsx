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
    CheckOutlined,
    CheckCircleOutlined,
    CheckSquareOutlined,
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
const loadGitDiffEditor = () => import("./GitDiffEditor/GitDiffEditor");

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
const CANDIDATE_AUTO_SAVE_DELAY_MS = 800;
const LEGACY_ACTIVE_RUN_STORAGE_KEY = 'featx.activeRunId';
const ACTIVE_RUN_STORAGE_PREFIX = 'featx.activeRunId.';
const CANDIDATE_DRAFT_STORAGE_PREFIX = 'featx.candidateDraft.';
const FEATURE_REQUEST_DRAFT_KEY = 'featx.featureRequestDraft';

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

export const supportsReasoningGraphStages = (operationType) => (
    operationType === "edit" || operationType === "add" || operationType === "delete"
);

export const shouldShowCandidateDiff = (confirmEnabled, operationType, codeNode) => (
    confirmEnabled
    && (operationType === "delete" || operationType === "edit" || operationType === "add")
    && Boolean(codeNode)
);

export const candidateNodeTypeForDraft = (candidateFile, draft) => {
    if (candidateFile?.staged && (draft ?? "") === (candidateFile.stagedContent ?? "")) {
        return "Staged";
    }
    const originalContent = candidateFile?.originalContent ?? "";
    return (draft ?? "") === originalContent ? "Default" : "Modify";
};

export const candidateIdentifierForNode = (nodeId, candidatePaths = []) => {
    const normalizedNodeId = String(nodeId || '').trim();
    if (!normalizedNodeId) return normalizedNodeId;

    const candidates = candidatePaths
        .map((path) => String(path || '').trim().replace(/\\/g, '/'))
        .filter(Boolean)
        .map((path) => ({
            path,
            nodeId: path.endsWith('.java')
                ? path.slice(0, -'.java'.length).replaceAll('/', '.')
                : path,
        }));
    const exactMatch = candidates.find((candidate) => candidate.nodeId === normalizedNodeId);
    if (exactMatch) return exactMatch.path;

    const suffixMatches = candidates.filter((candidate) => (
        candidate.nodeId.endsWith(`.${normalizedNodeId}`)
        || normalizedNodeId.endsWith(`.${candidate.nodeId}`)
    ));
    return suffixMatches.length === 1 ? suffixMatches[0].path : normalizedNodeId;
};

export const candidateIdentifiersForConfirmAll = (graphData, unstagedCandidatePaths = []) => {
    const graphCandidateIds = Array.isArray(graphData?.nodes)
        ? graphData.nodes
            .filter((node) => node?.type === "Modify")
            .map((node) => String(node.id || '').trim())
            .filter(Boolean)
        : [];
    return [...new Set([...graphCandidateIds, ...unstagedCandidatePaths])];
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

const candidateDraftStorageKey = (runId, candidateKey) => (
    `${CANDIDATE_DRAFT_STORAGE_PREFIX}${encodeURIComponent(runId || '')}.${encodeURIComponent(candidateKey || '')}`
);

export const activeRunStorageKey = (repositoryId) => (
    `${ACTIVE_RUN_STORAGE_PREFIX}${encodeURIComponent(repositoryId ?? '')}`
);

const loadStoredActiveRunId = (repositoryId) => {
    if (repositoryId == null || repositoryId === '') return null;
    try {
        return sessionStorage.getItem(activeRunStorageKey(repositoryId));
    } catch (error) {
        return null;
    }
};

const storeActiveRunId = (repositoryId, runId) => {
    if (repositoryId == null || repositoryId === '') return;
    try {
        const storageKey = activeRunStorageKey(repositoryId);
        if (runId) {
            sessionStorage.setItem(storageKey, runId);
        } else {
            sessionStorage.removeItem(storageKey);
        }
    } catch (error) {
        // The backend remains authoritative when browser storage is unavailable.
    }
};

const loadLegacyActiveRunId = () => {
    try {
        return sessionStorage.getItem(LEGACY_ACTIVE_RUN_STORAGE_KEY);
    } catch (error) {
        return null;
    }
};

const clearLegacyActiveRunId = () => {
    try {
        sessionStorage.removeItem(LEGACY_ACTIVE_RUN_STORAGE_KEY);
    } catch (error) {
        // Ignore unavailable browser storage.
    }
};

const isStaleActiveRunError = (error) => (
    error?.response?.status === 404 || error?.response?.status === 409
);

export const resolveActiveRunIdForRepository = async (repositoryId, validateRun) => {
    if (repositoryId == null || repositoryId === '') return null;

    const scopedRunId = loadStoredActiveRunId(repositoryId);
    if (scopedRunId) {
        try {
            await validateRun(scopedRunId);
            clearLegacyActiveRunId();
            return scopedRunId;
        } catch (error) {
            if (!isStaleActiveRunError(error)) return scopedRunId;
            storeActiveRunId(repositoryId, null);
        }
    }

    const legacyRunId = loadLegacyActiveRunId();
    if (!legacyRunId) return null;
    try {
        await validateRun(legacyRunId);
        storeActiveRunId(repositoryId, legacyRunId);
        return legacyRunId;
    } catch (error) {
        if (!isStaleActiveRunError(error)) return legacyRunId;
        return null;
    } finally {
        clearLegacyActiveRunId();
    }
};

const loadCandidateDraft = (runId, candidate) => {
    if (!runId || !candidate?.key) return null;
    try {
        const stored = JSON.parse(sessionStorage.getItem(candidateDraftStorageKey(runId, candidate.key)) || 'null');
        if (!stored || stored.baseContent !== (candidate.modifiedContent || '')) return null;
        return typeof stored.draft === 'string' ? stored.draft : null;
    } catch (error) {
        return null;
    }
};

const removeCandidateDraft = (runId, candidateKey) => {
    if (!runId || !candidateKey) return;
    try {
        sessionStorage.removeItem(candidateDraftStorageKey(runId, candidateKey));
    } catch (error) {
        // Ignore unavailable browser storage.
    }
};

const removeRunCandidateDrafts = (runId) => {
    if (!runId) return;
    try {
        const prefix = `${CANDIDATE_DRAFT_STORAGE_PREFIX}${encodeURIComponent(runId)}.`;
        Object.keys(sessionStorage)
            .filter((key) => key.startsWith(prefix))
            .forEach((key) => sessionStorage.removeItem(key));
    } catch (error) {
        // Storage is an extra recovery layer; the backend candidate remains authoritative.
    }
};

const loadFeatureRequestDraft = (repositoryId) => {
    try {
        const draft = JSON.parse(sessionStorage.getItem(FEATURE_REQUEST_DRAFT_KEY) || 'null');
        return draft && String(draft.repositoryId) === String(repositoryId) ? draft : null;
    } catch (error) {
        return null;
    }
};

const clearFeatureRequestDraft = () => {
    try {
        sessionStorage.removeItem(FEATURE_REQUEST_DRAFT_KEY);
    } catch (error) {
        // Ignore unavailable browser storage.
    }
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
        repositoryDiff: "仓库 Git Diff",
        closeChangesPanel: "关闭代码变更",
        fetchingCode: "正在获取代码详情。",
        noRepositoryChanges: "当前仓库没有未提交的 Git 变更。",
        failedFetchRepositoryDiff: "获取仓库 Git Diff 失败。",
        candidateDiff: "候选代码 Git Diff",
        failedFetchCandidateDiff: "获取候选代码 Git Diff 失败。",
        candidateSaved: "候选代码已保存。",
        failedSaveCandidate: "保存候选代码失败。",
        saveBeforeApply: "请先保存编辑，再确认应用。",
        saveBeforeClose: "当前编辑尚未保存。",
        gitGeneratedDiff: "Git 生成的差异",
        confirmFile: "暂存文件",
        stagingFile: "正在暂存……",
        fileStaged: "此文件已暂存",
        candidateStaged: "文件已写入项目并暂存。",
        failedStageCandidate: "暂存候选文件失败。",
        unstageFile: "取消暂存",
        unstagingFile: "正在取消暂存……",
        candidateUnstaged: "已取消暂存，文件内容保持不变。",
        failedUnstageCandidate: "取消暂存失败。",
        noCandidateChanges: "当前文件没有可暂存的修改。",
        revertFile: "撤销此文件",
        revertingFile: "正在撤销……",
        candidateReverted: "此文件的候选修改已撤销。",
        failedRevertCandidate: "撤销候选文件失败。",
        commitChanges: "提交已暂存文件",
        noStagedFiles: "请先在 Diff Panel 中暂存至少一个文件。",
        partialCommitTitle: "确认部分提交",
        completeCommitTitle: "确认全部提交",
        metadataCommitTitle: "确认同步过期功能数据",
        partialCommitDescription: "这是选择性确认：本次只保留并提交已暂存文件；其余候选修改将被永久放弃，操作结束后不能继续提交。系统仍会完成本次功能确认，并同步更新功能数据、CodeMap 和静态分析。",
        completeCommitDescription: "所有候选文件均已确认；本次将提交剩余修改，并同步更新功能数据、CodeMap 和静态分析结果。",
        metadataCommitDescription: "源码已经处于目标状态，本次不会修改文件；系统将创建一条可审计的空提交，并清理过期的 Feature 和 CodeMap 数据。",
        partialCommitSuccess: "已提交确认的文件，其余候选修改已放弃。",
        completeCommitSuccess: "全部候选修改已提交。",
        metadataCommitSuccess: "源码无需修改，过期的功能数据已同步清理。",
        failedCommitChanges: "提交代码变更失败。",
        discardAllChanges: "放弃全部未提交修改",
        discardAllTitle: "放弃全部未提交修改？",
        discardAllDescription: "将恢复暂存区和工作区到最近一次提交，并删除非忽略的未跟踪文件；已经完成的提交和预处理输出会保留。",
        discardSuccess: "未提交修改已放弃，已有提交保持不变。",
        failedDiscardChanges: "放弃未提交修改失败。",
        committingChanges: "正在提交并更新项目数据……",
        noSubmittedChanges: "您尚未提交任何修改，请先提交。",
        confirmAllChanges: "确认全部候选变更",
        reviewAllChanges: "系统将自动接受所有尚未确认的 Agent 候选文件，不再要求逐文件暂存，并立即提交全部候选变更。请只在确认可以完整采用本次 Agent 输出时继续。",
        confirmApply: "接受并提交全部",
        decline: "取消",
        failedConfirmAllChanges: "确认全部候选变更失败。",
        incompleteConfirmAllChanges: "仍有候选文件无法自动确认，请逐文件检查后重试。",
        submittingFeatureDeletion: "正在提交功能删除。",
        submittingFeatureAddition: "正在提交新增功能。",
        submittingFeatureModification: "正在提交功能修改。",
        preparingModification: "正在准备修改。",
        streamingPythonAgent: "正在流式生成 Python 代码。",
        streamingAgent: "正在执行三阶段 Agent 代码生成。",
        codeGenerationFinished: "代码生成已完成。请检查差异后确认或放弃。",
        codeGenerationFailed: "代码生成失败，未创建可确认的候选修改。",
        restoringAgentRun: "正在恢复刷新前的 Agent 任务……",
        failedRestoreAgentRun: "无法恢复刷新前的 Agent 任务，请重新提交需求。",
        focusGraphReady: "查看代码检索的初始图、扩展图和推理图。",
        focusGraphPending: "Java/Python 新增或修改提交后可查看三阶段图。",
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
        repositoryDiff: "Repository Git Diff",
        closeChangesPanel: "Close Diff Panel",
        fetchingCode: "Fetching code details.",
        noRepositoryChanges: "The repository has no uncommitted Git changes.",
        failedFetchRepositoryDiff: "Failed to fetch repository Git diff.",
        candidateDiff: "Candidate Git Diff",
        failedFetchCandidateDiff: "Failed to fetch candidate Git diff.",
        candidateSaved: "Candidate code saved.",
        failedSaveCandidate: "Failed to save candidate code.",
        saveBeforeApply: "Save your edits before applying the change.",
        saveBeforeClose: "The current edits have not been saved.",
        gitGeneratedDiff: "Git-generated diff",
        confirmFile: "Stage file",
        stagingFile: "Staging...",
        fileStaged: "File staged",
        candidateStaged: "The file was written to the project and staged.",
        failedStageCandidate: "Failed to stage the candidate file.",
        unstageFile: "Unstage file",
        unstagingFile: "Unstaging...",
        candidateUnstaged: "The file was unstaged without changing its content.",
        failedUnstageCandidate: "Failed to unstage the candidate file.",
        noCandidateChanges: "This file has no changes to stage.",
        revertFile: "Revert this file",
        revertingFile: "Reverting...",
        candidateReverted: "The candidate change for this file was reverted.",
        failedRevertCandidate: "Failed to revert the candidate file.",
        commitChanges: "Commit staged files",
        noStagedFiles: "Stage at least one file in the Diff Panel first.",
        partialCommitTitle: "Confirm partial commit",
        completeCommitTitle: "Confirm complete commit",
        metadataCommitTitle: "Confirm stale metadata reconciliation",
        partialCommitDescription: "This is a selective confirmation. Only staged files will be kept and committed. Every other candidate will be permanently discarded and cannot be committed after this operation ends. FeatX will still complete the feature confirmation and update feature data, CodeMap, and static analysis.",
        completeCommitDescription: "All candidate files are confirmed. This commits the remaining changes and updates feature data, CodeMap, and static analysis.",
        metadataCommitDescription: "The source already matches the target state. FeatX will create an auditable empty commit and remove the stale Feature and CodeMap data without changing files.",
        partialCommitSuccess: "Confirmed files were committed; all other candidates were discarded.",
        completeCommitSuccess: "All candidate changes were committed.",
        metadataCommitSuccess: "No source changes were needed; stale feature data was reconciled.",
        failedCommitChanges: "Failed to commit code changes.",
        discardAllChanges: "Discard all uncommitted changes",
        discardAllTitle: "Discard all uncommitted changes?",
        discardAllDescription: "The index and worktree return to the latest commit, and non-ignored untracked files are removed. Existing commits and preprocessing output are preserved.",
        discardSuccess: "Uncommitted changes were discarded; existing commits were preserved.",
        failedDiscardChanges: "Failed to discard uncommitted changes.",
        committingChanges: "Committing and updating project data...",
        noSubmittedChanges: "You haven't made any modifications. Please submit first.",
        confirmAllChanges: "Confirm all candidate changes",
        reviewAllChanges: "FeatX will automatically accept every unconfirmed Agent candidate without requiring each file to be staged individually, then immediately commit the complete candidate set. Continue only when the entire Agent output should be applied.",
        confirmApply: "Accept and commit all",
        decline: "Cancel",
        failedConfirmAllChanges: "Failed to confirm all candidate changes.",
        incompleteConfirmAllChanges: "Some candidate files could not be confirmed automatically. Review them individually and try again.",
        submittingFeatureDeletion: "Submitting feature deletion.",
        submittingFeatureAddition: "Submitting feature addition.",
        submittingFeatureModification: "Submitting feature modification.",
        preparingModification: "Preparing modification.",
        streamingPythonAgent: "Streaming Python Agent code generation.",
        streamingAgent: "Running the three-stage Agent code generation.",
        codeGenerationFinished: "Code generation finished. Review the diff and confirm or drop it.",
        codeGenerationFailed: "Code generation failed; no candidate changes are available to confirm.",
        restoringAgentRun: "Restoring the Agent run from before the refresh...",
        failedRestoreAgentRun: "The Agent run from before the refresh could not be restored. Submit the request again.",
        focusGraphReady: "Show the initial, expanded, and reasoning code graphs.",
        focusGraphPending: "Graph stages are available after Java/Python Add or Modify submit.",
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

export const FeatureListItem = ({
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
                [styles.featureItemEditing]: isEditing,
            })}
            style={compressed && preservedHeight && !isEditing ? {
                height: `${preservedHeight}px`,
                minHeight: `${preservedHeight}px`,
                maxHeight: `${preservedHeight}px`,
                "--feature-line-clamp": preservedLineCount,
            } : undefined}
        >
            <div ref={featureContentRef} className={styles.featureContent}>
                {isEditing ? (
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
                ) : minimal ? (
                    <span className={styles.featureNumber}>{itemNumber}</span>
                ) : compressed ? (
                    <div className={styles.featureDescription}>
                        <span className={styles.featureNumber}>{itemNumber}</span>{" "}
                        <span>{description}</span>
                    </div>
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
                const sortedModels = [...availableModels].sort(
                    new Intl.Collator('en', {numeric: true, sensitivity: 'base'}).compare
                );
                setModels(sortedModels);
                setSelectedModel(
                    sortedModels.includes(catalog.defaultModel)
                        ? catalog.defaultModel
                        : sortedModels[0] || null
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
        let active = true;
        const fetchData = async () => {
            let project = await API.getCurrentProject().catch(() => null)
            if (!project?.repoId) {
                const storedRepositoryId = API.getSelectedRepositoryId()
                if (storedRepositoryId) {
                    await API.postProjectPath(storedRepositoryId)
                    project = await API.getCurrentProject().catch(() => null)
                }
            }
            if (!active) return
            setCurrentProject(project)
            await refreshGitStatus()
            const res = await getFeatureData()
            if (!active) return
            const repositoryId = project?.repositoryId ?? project?.repoId
            const restoredRunId = await resolveActiveRunIdForRepository(
                repositoryId,
                API.getAgentRun
            )
            if (!active) return
            setActiveRunId(restoredRunId)
            setActiveRunStorageReady(repositoryId != null)
            const requestDraft = !restoredRunId
                ? loadFeatureRequestDraft(project?.repositoryId ?? project?.repoId)
                : null
            if (requestDraft?.operation === 'edit') {
                const target = res.flatMap((module) => module.featureList || []).find(
                    (feature) => String(feature.featureId) === String(requestDraft.featureId)
                )
                if (target) {
                    goEdit(target)
                    setEditedText(requestDraft.text || '')
                    setRequestDraftDirty(true)
                    return
                }
            }
            if (requestDraft?.operation === 'add') {
                const targetModule = res.find(
                    (module) => String(module.moduleId) === String(requestDraft.moduleId)
                )
                if (targetModule) {
                    goAdd(targetModule)
                    setEditedText(requestDraft.text || '')
                    setRequestDraftDirty(true)
                    return
                }
            }
            if (!restoredRunId && res.length > 0 && res[0].featureList.length > 0) {
                setActiveKey(res[0].moduleId)
                handleSelect(res[0].featureList[0])
            }
        }
        fetchData()
        return () => {
            active = false;
        }
        // This is a one-time bootstrap. The functions intentionally use the
        // initial session snapshot and would restart requests if dependencies changed.
        // eslint-disable-next-line react-hooks/exhaustive-deps
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
    const [activeRunId, setActiveRunId] = useState(null);
    const [metadataOnlyEligible, setMetadataOnlyEligible] = useState(false);
    const [activeRunStorageReady, setActiveRunStorageReady] = useState(false);

    useEffect(() => {
        const repositoryId = currentProject?.repositoryId ?? currentProject?.repoId;
        if (!activeRunStorageReady || repositoryId == null) return;
        storeActiveRunId(repositoryId, activeRunId);
    }, [activeRunId, activeRunStorageReady, currentProject]);

    const getFeatureGraphData = (featureId, selectedType, runId = activeRunId) => {
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
        candidateDraftRef.current = '';
        autoSaveAttemptRef.current = null;
        setCandidateDirty(false);
        setCandidateSaveError(null);
        setStagingCandidate(false);
        setUnstagingCandidate(false);
        setRevertingCandidate(false);
        if (selectedType === 'delete') {
            API.getMinGraphData(featureId, runId).then((data) => {
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
            API.getNewGraphData(runId).then((data) => {
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
    const [candidateSaveError, setCandidateSaveError] = useState(null);
    const [requestDraftDirty, setRequestDraftDirty] = useState(false);
    const [savingCandidate, setSavingCandidate] = useState(false);
    const [stagingCandidate, setStagingCandidate] = useState(false);
    const [unstagingCandidate, setUnstagingCandidate] = useState(false);
    const [revertingCandidate, setRevertingCandidate] = useState(false);
    const [gitStatus, setGitStatus] = useState(EMPTY_GIT_STATUS);
    const [loadingConfirm, setLoadingConfirm] = useState(false);
    const candidateDraftRef = useRef(candidateDraft);
    const autoSaveAttemptRef = useRef(null);
    const saveCandidateDiffRef = useRef(null);
    candidateDraftRef.current = candidateDraft;

    useEffect(() => {
        const shouldWarn = Boolean(
            activeRunId
            || candidateDirty
            || savingCandidate
            || requestDraftDirty
            || operationProgress?.running
        );
        if (!shouldWarn) return undefined;
        const warnBeforeUnload = (event) => {
            event.preventDefault();
            event.returnValue = '';
        };
        window.addEventListener('beforeunload', warnBeforeUnload);
        return () => window.removeEventListener('beforeunload', warnBeforeUnload);
    }, [activeRunId, candidateDirty, operationProgress?.running, requestDraftDirty, savingCandidate]);

    useEffect(() => {
        if (!activeRunId || !candidateFile?.key) return;
        const storageKey = candidateDraftStorageKey(activeRunId, candidateFile.key);
        if (!candidateDirty) {
            try {
                sessionStorage.removeItem(storageKey);
            } catch (error) {
                // Ignore unavailable browser storage.
            }
            return;
        }
        try {
            sessionStorage.setItem(storageKey, JSON.stringify({
                baseContent: candidateFile.modifiedContent || '',
                draft: candidateDraft,
            }));
        } catch (error) {
            // Large files may exceed browser storage; the unload warning still protects them.
        }
    }, [activeRunId, candidateDirty, candidateDraft, candidateFile]);

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

    const getCodeDiff = (classNodeId, codeNode) => {
        if (isCandidateDiff && candidateDirty) {
            message.warning(copy.saveBeforeClose);
            return false;
        }
        setIsRepositoryDiff(false);
        setIsCandidateDiff(false);
        setRepositoryDiffError(false);
        setCandidateFile(null);
        setCandidateDraft('');
        candidateDraftRef.current = '';
        autoSaveAttemptRef.current = null;
        setCandidateDirty(false);
        setCandidateSaveError(null);
        setSelectedCodeNodeId(String(classNodeId));
        setDiffDrawerOpen(true);
        setLoadingCode(true)

        const candidateReady = shouldShowCandidateDiff(confirmEnabled, selectedType, codeNode);

        if (candidateReady) {
            const candidateIdentifier = candidateIdentifierForNode(
                classNodeId,
                gitStatus.candidatePaths
            );
            const candidateRequest = codeNode?.type === "Modify" || codeNode?.type === "Staged"
                ? API.getCandidateDiff(candidateIdentifier, selectedType, activeRunId)
                : API.getManualCandidate(classNodeId, selectedType, activeRunId);
            candidateRequest
                .then((data) => {
                    const restoredDraft = loadCandidateDraft(activeRunId, data);
                    const nextDraft = restoredDraft ?? (data.modifiedContent || '');
                    setCandidateFile(data);
                    setCandidateDraft(nextDraft);
                    candidateDraftRef.current = nextDraft;
                    autoSaveAttemptRef.current = null;
                    setCandidateDirty(restoredDraft !== null
                        && restoredDraft !== (data.modifiedContent || ''));
                    setCandidateSaveError(null);
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

    const saveCandidateDiff = (editorContent, options = {}) => {
        if (!candidateFile || savingCandidate) return Promise.resolve(null);

        const silent = options.silent === true;
        const contentToSave = typeof editorContent === 'string' ? editorContent : candidateDraft;
        if (contentToSave === (candidateFile.modifiedContent || '')) return Promise.resolve(candidateFile);
        candidateDraftRef.current = contentToSave;
        setCandidateDraft(contentToSave);
        setSavingCandidate(true);
        setCandidateSaveError(null);
        return API.updateCandidateDiff(candidateFile.key, selectedType, contentToSave, activeRunId)
            .then(async (data) => {
                const savedContent = data.modifiedContent || '';
                const currentDraft = candidateDraftRef.current;
                const hasNewerDraft = currentDraft !== contentToSave;
                const nextDraft = hasNewerDraft ? currentDraft : savedContent;
                if (!hasNewerDraft) {
                    candidateDraftRef.current = savedContent;
                }
                setCandidateFile(data);
                setCandidateDraft(nextDraft);
                setCandidateDirty(nextDraft !== savedContent);
                setCodeDiff(data.diff || '');
                await refreshGitStatus();
                setGraphData((current) => current ? {
                    ...current,
                    nodes: (current.nodes || []).map((node) => (
                        String(node.id) === String(selectedCodeNodeId)
                            ? {...node, type: candidateNodeTypeForDraft(data, nextDraft)}
                            : node
                    )),
                } : current);
                setSavingCandidate(false);
                setCandidateSaveError(null);
                if (!hasNewerDraft) {
                    removeCandidateDraft(activeRunId, candidateFile.key);
                }
                if (!silent) {
                    message.success(copy.candidateSaved);
                }
                return data;
            })
            .catch((error) => {
                setSavingCandidate(false);
                const detail = errorMessage(error, copy.failedSaveCandidate);
                setCandidateSaveError(detail);
                if (!silent) {
                    message.error(detail);
                }
                return null;
            });
    };

    saveCandidateDiffRef.current = saveCandidateDiff;

    useEffect(() => {
        if (!isCandidateDiff || !candidateFile?.key || !candidateDirty || savingCandidate) {
            return undefined;
        }
        if (autoSaveAttemptRef.current === candidateDraft) {
            return undefined;
        }
        const timer = window.setTimeout(() => {
            autoSaveAttemptRef.current = candidateDraft;
            saveCandidateDiffRef.current?.(candidateDraft, {silent: true});
        }, CANDIDATE_AUTO_SAVE_DELAY_MS);
        return () => window.clearTimeout(timer);
    }, [candidateDirty, candidateDraft, candidateFile?.key, isCandidateDiff, savingCandidate]);

    const stageCandidateDiff = () => {
        if (!candidateFile || stagingCandidate || unstagingCandidate) return;
        if (candidateDirty) {
            message.warning(copy.saveBeforeApply);
            return;
        }

        setStagingCandidate(true);
        API.stageCandidateFile(candidateFile.key, activeRunId)
            .then((status) => {
                const nextStatus = status || EMPTY_GIT_STATUS;
                const staged = Boolean(candidateFile.path && nextStatus.stagedPaths?.includes(candidateFile.path));
                const nextCandidateFile = {
                    ...candidateFile,
                    warning: null,
                    staged,
                    stagedContent: staged ? candidateDraft : null,
                };
                setGitStatus(nextStatus);
                setStagingCandidate(false);
                setCandidateFile(nextCandidateFile);
                setGraphData((current) => current ? {
                    ...current,
                    nodes: (current.nodes || []).map((node) => (
                        String(node.id) === String(selectedCodeNodeId)
                            ? {...node, type: candidateNodeTypeForDraft(nextCandidateFile, candidateDraft)}
                            : node
                    )),
                } : current);
                removeCandidateDraft(activeRunId, candidateFile.key);
                message.success(copy.candidateStaged);
            })
            .catch((error) => {
                setStagingCandidate(false);
                message.error(errorMessage(error, copy.failedStageCandidate));
            });
    };

    const unstageCandidateDiff = () => {
        if (!candidateFile || stagingCandidate || unstagingCandidate) return;

        setUnstagingCandidate(true);
        API.unstageCandidateFile(candidateFile.key, activeRunId)
            .then((status) => {
                const nextCandidateFile = {
                    ...candidateFile,
                    staged: false,
                    stagedContent: null,
                    warning: null,
                };
                setGitStatus(status || EMPTY_GIT_STATUS);
                setCandidateFile(nextCandidateFile);
                setGraphData((current) => current ? {
                    ...current,
                    nodes: (current.nodes || []).map((node) => (
                        String(node.id) === String(selectedCodeNodeId)
                            ? {...node, type: candidateNodeTypeForDraft(nextCandidateFile, candidateDraft)}
                            : node
                    )),
                } : current);
                message.success(copy.candidateUnstaged);
            })
            .catch((error) => {
                message.error(errorMessage(error, copy.failedUnstageCandidate));
            })
            .finally(() => setUnstagingCandidate(false));
    };

    const revertCandidateDiff = () => {
        if (!candidateFile || revertingCandidate || savingCandidate || stagingCandidate || unstagingCandidate) return;

        autoSaveAttemptRef.current = candidateDraft;
        setCandidateDirty(false);
        setCandidateSaveError(null);
        setRevertingCandidate(true);
        API.revertCandidateFile(candidateFile.key, activeRunId)
            .then((status) => {
                const revertedContent = candidateFile.originalContent ?? '';
                const revertedCandidate = {
                    ...candidateFile,
                    modifiedContent: revertedContent,
                    diff: '',
                    staged: false,
                    stagedContent: null,
                    deleted: false,
                    warning: null,
                };
                setGitStatus(status || EMPTY_GIT_STATUS);
                removeCandidateDraft(activeRunId, candidateFile.key);
                setCandidateFile(revertedCandidate);
                setCandidateDraft(revertedContent);
                candidateDraftRef.current = revertedContent;
                autoSaveAttemptRef.current = null;
                setCandidateDirty(false);
                setCandidateSaveError(null);
                setCodeDiff('');
                setGraphData((current) => current ? {
                    ...current,
                    nodes: (current.nodes || []).map((node) => (
                        String(node.id) === String(selectedCodeNodeId)
                            ? {...node, type: candidateNodeTypeForDraft(revertedCandidate, revertedContent)}
                            : node
                    )),
                } : current);
                message.success(copy.candidateReverted);
            })
            .catch((error) => {
                setCandidateDirty(
                    candidateDraftRef.current !== (candidateFile.modifiedContent || '')
                );
                message.error(errorMessage(error, copy.failedRevertCandidate));
            })
            .finally(() => setRevertingCandidate(false));
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
        candidateDraftRef.current = '';
        autoSaveAttemptRef.current = null;
        setCandidateDirty(false);
        setCandidateSaveError(null);
        setStagingCandidate(false);
        setUnstagingCandidate(false);
        setRevertingCandidate(false);
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

    const clearLocalDraftFeature = () => {
        if (!(selectedType === "add" && selectedFeatureItem?.isNew)) {
            return;
        }
        const draftFeatureId = selectedFeatureItem.featureId;
        setFeatureData((prev) => prev.map((module) => ({
            ...module,
            featureList: (module.featureList || []).filter((feature) => feature.featureId !== draftFeatureId),
        })));
    };

    const goSelect = (item) => {
        clearPostAgentTimers();
        clearFocusGraphStages();
        clearLocalDraftFeature();
        setSubmitEnabled(false);
        setModeTrans(false);
        setChatMode(false);
        setConfirmEnabled(false);
        setRequestDraftDirty(false);
        clearFeatureRequestDraft();

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
        clearLocalDraftFeature();
        setSubmitEnabled(false);
        setModeTrans(false);
        setChatMode(false);
        setConfirmEnabled(false);
        setRequestDraftDirty(false);
        clearFeatureRequestDraft();

        setSelectedFeatureItem(item)
        setSelectedType("delete")
        prepareDelete(item)
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
        clearLocalDraftFeature();
        setSubmitEnabled(true);
        setConfirmEnabled(false);
        setModeTrans(false);
        setChatMode(false);
        setRequestDraftDirty(false);
        clearFeatureRequestDraft();


        setSelectedFeatureItem(item)
        setSelectedType("edit")
        setEditedText(getFeatureDescription(item, language))
        getFeatureGraphData(item.featureId, "edit")
    }

    const [editedText, setEditedText] = useState('')
    const updateEditedText = (value) => {
        setEditedText(value)
        setRequestDraftDirty(true)
    }

    useEffect(() => {
        if (!requestDraftDirty || activeRunId || !currentProject) return
        if (selectedType !== 'edit' && selectedType !== 'add') return
        try {
            sessionStorage.setItem(FEATURE_REQUEST_DRAFT_KEY, JSON.stringify({
                repositoryId: currentProject.repositoryId ?? currentProject.repoId,
                operation: selectedType,
                featureId: selectedType === 'edit' ? selectedFeatureItem?.featureId : null,
                moduleId: selectedType === 'add' ? selectedFeatureItem?.moduleId : null,
                text: editedText,
            }))
        } catch (error) {
            // The unload warning remains active when browser storage is unavailable.
        }
    }, [activeRunId, currentProject, editedText, requestDraftDirty, selectedFeatureItem, selectedType])
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
                clearLocalDraftFeature();
                setActiveKey(newActiveKey)
            });
        } else {
            clearLocalDraftFeature();
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
        clearLocalDraftFeature();
        setSubmitEnabled(true)
        setModeTrans(false);
        setChatMode(false);
        setConfirmEnabled(false);
        setRequestDraftDirty(false);
        clearFeatureRequestDraft();


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


    const handleChat = (eventSource, runId, operationType = selectedType, restoring = false) => {
        clearPostAgentTimers()
        eventSourceRef.current = eventSource
        let settled = false
        let checkingConnection = false
        setChatContent("");
        setMetadataOnlyEligible(false)
        setChatMode(true)
        setModeTrans(true)
        const progressOperation = `${isPythonProject ? "python" : "java"}-${
            operationType === 'add' ? "add" : operationType === 'delete' ? "delete" : "modify"
        }`
        setOperationProgress({
            operation: progressOperation,
            stage: "agent-stream",
            message: restoring
                ? copy.restoringAgentRun
                : isPythonProject ? copy.streamingPythonAgent : copy.streamingAgent,
            currentStep: 8,
            totalSteps: 8,
            running: true,
            failed: false
        })
        const decodeEvent = (event) => {
            try {
                const binary = atob(event.data)
                const bytes = Uint8Array.from(binary, character => character.charCodeAt(0))
                return new TextDecoder().decode(bytes)
            } catch (error) {
                console.error('Failed to decode Agent SSE event:', error)
                return ''
            }
        }
        const appendEvent = (event) => {
            const decoded = decodeEvent(event)
            setChatContent(prev => prev + decoded);
            return decoded
        }
        const resetReplay = () => {
            setChatContent("")
        }
        const finishSuccess = (event) => {
            if (settled) return
            settled = true
            appendEvent(event)
            eventSource.close()
            eventSourceRef.current = null
            stopProgressPolling()
            setOperationProgress({
                operation: progressOperation,
                stage: "complete",
                message: copy.codeGenerationFinished,
                currentStep: 8,
                totalSteps: 8,
                running: false,
                failed: false
            })
            setLoadingFeatureList(false)
            setConfirmEnabled(true)
            API.getAgentRun(runId)
                .then((snapshot) => setMetadataOnlyEligible(Boolean(snapshot?.metadataOnlyEligible)))
                .catch(() => setMetadataOnlyEligible(false))
            setRequestDraftDirty(false)
            clearFeatureRequestDraft()

            graphRefreshTimerRef.current = setTimeout(() => {
                getFeatureGraphData(0, "new", runId)
                graphRefreshTimerRef.current = null
            }, 1500)

        }
        const finishFailure = (event, fallbackDetail = '') => {
            if (settled) return
            settled = true
            const detail = event ? appendEvent(event) : fallbackDetail
            eventSource.close()
            eventSourceRef.current = null
            stopProgressPolling()
            setLoadingFeatureList(false)
            setConfirmEnabled(false)
            setMetadataOnlyEligible(false)
            setActiveRunId(null)
            setSubmitEnabled(true)
            setRequestDraftDirty(true)
            setModeTrans(true)
            setChatMode(true)
            setOperationProgress((current) => ({
                ...(current || {}),
                stage: "failed",
                message: detail || copy.codeGenerationFailed,
                running: false,
                failed: true,
                error: detail || copy.codeGenerationFailed,
            }))
            message.error(detail || copy.codeGenerationFailed)
        }

        eventSource.addEventListener('reset', resetReplay)
        eventSource.addEventListener('status', appendEvent)
        eventSource.addEventListener('delta', appendEvent)
        eventSource.addEventListener('completed', finishSuccess)
        eventSource.addEventListener('failed', finishFailure)
        eventSource.onmessage = appendEvent
        eventSource.onerror = () => {
            if (settled || checkingConnection) return
            checkingConnection = true
            API.getAgentRun(runId)
                .then((snapshot) => {
                    checkingConnection = false
                    if (snapshot?.status === 'FAILED') {
                        finishFailure(null, snapshot.failureMessage || copy.codeGenerationFailed)
                    }
                    // PREPARED/RUNNING/COMPLETED runs remain recoverable. EventSource
                    // reconnects and the backend replays the complete output.
                })
                .catch((error) => {
                    checkingConnection = false
                    finishFailure(null, errorMessage(error, copy.failedRestoreAgentRun))
                })
        }
        return () => {
            settled = true
            eventSource.close();
            eventSourceRef.current = null;
        };
    }

    const [confirmEnabled, setConfirmEnabled] = useState(false)
    const [submitEnabled, setSubmitEnabled] = useState(false)
    const restoredRunRef = useRef(null)

    useEffect(() => {
        if (!activeRunId || !currentProject || loadingModels || !featureData.length) return
        if (restoredRunRef.current === activeRunId) return
        restoredRunRef.current = activeRunId

        API.getAgentRun(activeRunId)
            .then(async (snapshot) => {
                const operationType = snapshot.mode === 'delete'
                    ? 'delete'
                    : snapshot.mode?.startsWith('add') ? 'add' : 'edit'
                let targetFeature = null
                let targetModule = null

                if (operationType === 'add') {
                    targetModule = featureData.find(
                        (module) => String(module.moduleId) === String(snapshot.moduleId)
                    )
                    if (!targetModule) throw new Error(copy.failedRestoreAgentRun)
                    targetFeature = {
                        featureId: `restored-${snapshot.runId}`,
                        featureDescription: snapshot.request || copy.newSubfeature,
                        isNew: true,
                        moduleId: targetModule.moduleId,
                    }
                    setFeatureData((modules) => modules.map((module) => {
                        if (String(module.moduleId) !== String(targetModule.moduleId)) return module
                        const withoutOldRestore = (module.featureList || []).filter(
                            (feature) => feature.featureId !== targetFeature.featureId
                        )
                        return {...module, featureList: [targetFeature, ...withoutOldRestore]}
                    }))
                } else {
                    targetModule = featureData.find((module) => (module.featureList || []).some(
                        (feature) => String(feature.featureId) === String(snapshot.featureId)
                    ))
                    targetFeature = targetModule?.featureList?.find(
                        (feature) => String(feature.featureId) === String(snapshot.featureId)
                    )
                    if (!targetFeature) throw new Error(copy.failedRestoreAgentRun)
                }

                setActiveKey(targetModule.moduleId)
                setSelectedFeatureItem(targetFeature)
                setSelectedType(operationType)
                setEditedText(snapshot.request || getFeatureDescription(targetFeature, language))
                setSubmitEnabled(false)
                setConfirmEnabled(snapshot.status === 'COMPLETED')
                setMetadataOnlyEligible(Boolean(snapshot.metadataOnlyEligible))
                setLoadingFeatureList(snapshot.status === 'PREPARED' || snapshot.status === 'RUNNING')
                const recoveringDelete = operationType === 'delete'
                    && snapshot.status !== 'COMPLETED';
                getFeatureGraphData(
                    operationType === 'add' || (operationType === 'delete' && !recoveringDelete)
                        ? null
                        : targetFeature.featureId,
                    operationType === 'delete'
                        ? recoveringDelete ? 'select' : 'new'
                        : operationType,
                    snapshot.runId
                )

                if (supportsReasoningGraphStages(operationType)) {
                    await loadFocusGraphStages(snapshot.runId, operationType).catch(() => [])
                }
                handleChat(
                    API.getLlmResponse(
                        snapshot.runId,
                        snapshot.language || apiLanguage,
                        snapshot.model || selectedModel
                    ),
                    snapshot.runId,
                    operationType,
                    true
                )
            })
            .catch((error) => {
                console.error('Failed to restore Agent run:', error)
                setActiveRunId(null)
                setLoadingFeatureList(false)
                setSubmitEnabled(true)
                restoreSelectedFeature(featureData, null)
                message.warning(errorMessage(error, copy.failedRestoreAgentRun))
            })
        // Recovery is keyed by restoredRunRef; callback dependencies are
        // intentionally read from the render that claims this run id.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [activeRunId, currentProject, featureData, loadingModels, selectedModel])

    const hasPendingFeatureOperation = () => {
        const isFeatureOperation = selectedType === "delete" || selectedType === "edit" || selectedType === "add";
        if (!isFeatureOperation) {
            return false;
        }
        const pendingCandidateCount = gitStatus.pendingCandidatePaths?.length || 0;
        const committedCandidateCount = gitStatus.committedCandidatePaths?.length || 0;
        const operationRunning = Boolean(operationProgress?.running);
        return confirmEnabled
            || operationRunning
            || requestDraftDirty
            || candidateDirty
            || stagedFileCount > 0
            || pendingCandidateCount > 0
            || committedCandidateCount > 0;
    };

    const discardPendingChanges = async () => {
        setLoadingConfirm(true);
        try {
            const discardedRunId = activeRunId;
            const status = await API.discardFeatureChanges();
            setGitStatus(status || EMPTY_GIT_STATUS);
            eventSourceRef.current?.close()
            eventSourceRef.current = null
            clearPostAgentTimers();
            clearFocusGraphStages();
            resetGraphDiffDrawer();
            setConfirmEnabled(false);
            setMetadataOnlyEligible(false);
            setSubmitEnabled(false);
            setModeTrans(false);
            setChatMode(false);
            setOperationProgress(null);
            setRequestDraftDirty(false);
            clearFeatureRequestDraft();
            removeRunCandidateDrafts(discardedRunId);
            setActiveRunId(null);
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
            || operationProgress?.operation === "java-modify"
            || operationProgress?.operation === "java-add"
            || operationProgress?.operation === "java-delete"
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

    const loadFocusGraphStages = (runId, operationType = selectedType) => {
        if (!supportsReasoningGraphStages(operationType)) {
            clearFocusGraphStages()
            return Promise.resolve([])
        }
        return API.getFocusGraphStages(runId)
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
            eventSourceRef.current?.close()
            eventSourceRef.current = null
            stopProgressPolling()
            clearPostAgentTimers()
        }
    }, [])

    const prepareDelete = (item) => {
        if (!selectedModel) {
            message.warning(copy.modelUnavailable);
            return;
        }

        setLoadingFeatureList(true)
        setSubmitEnabled(false)
        setConfirmEnabled(false)
        clearFocusGraphStages()
        const expectedOperation = `${isPythonProject ? "python" : "java"}-delete`;
        setOperationProgress({
            operation: expectedOperation,
            stage: "submit",
            message: copy.submittingFeatureDeletion,
            currentStep: 0,
            totalSteps: 8,
            running: true,
            failed: false
        })
        startProgressPolling(expectedOperation)
        API.deleteFeature({
            featureId: item.featureId,
            featureDescription: item.featureDescription,
            language: apiLanguage,
        })
            .then(async (result) => {
                const runId = result?.runId
                restoredRunRef.current = runId
                setActiveRunId(runId)
                await loadFocusGraphStages(runId, "delete")
                handleChat(
                    API.getLlmResponse(runId, apiLanguage, selectedModel),
                    runId,
                    "delete"
                )
            })
            .catch((error) => {
                console.error('Error Delete Feature:', error)
                stopProgressPolling()
                setLoadingFeatureList(false)
                const msg = errorMessage(error, copy.failedPrepareDeletion)
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
            })
    }

    const submitEdit = (item) => {
        if (!selectedModel) return;

        setLoadingFeatureList(true)
        setSubmitEnabled(false)
        clearFocusGraphStages()
        const expectedOperation = `${isPythonProject ? "python" : "java"}-${
            selectedType === 'add' ? "add" : "modify"
        }`;
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

        if (selectedType == 'edit') {
            // console.log(editedText);
            API.modifyFeature(editedText, apiLanguage)
                .then(async (data) => {
                    const runId = data?.runId
                    restoredRunRef.current = runId
                    setRequestDraftDirty(false)
                    clearFeatureRequestDraft()
                    setActiveRunId(runId)
                    await loadFocusGraphStages(runId, "edit")
                    handleChat(API.getLlmResponse(runId, apiLanguage, selectedModel), runId)
                })
                .catch((error) => {
                    console.error('Error Modify Feature:', error)
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
                    const runId = data?.runId
                    restoredRunRef.current = runId
                    setRequestDraftDirty(false)
                    clearFeatureRequestDraft()
                    setActiveRunId(runId)
                    await loadFocusGraphStages(runId, "add")
                    handleChat(API.getLlmResponse(runId, apiLanguage, selectedModel), runId)
                })
                .catch((error) => {
                    console.error('Error Add Feature:', error)
                    stopProgressPolling()
                    const msg = errorMessage(error, copy.failedAddFeature)
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
                    setLoadingFeatureList(false)
                    setSubmitEnabled(true)
                });
        }
    }

    const finishFeatureCommit = async (commitResult) => {
        const completedRunId = activeRunId;
        const previousFeatureId = selectedFeatureItem?.featureId;
        const features = await getFeatureData();
        const preferredFeatureId = selectedType === 'add'
            ? commitResult.featureId
            : selectedType === 'edit' ? previousFeatureId : null;
        restoreSelectedFeature(features, preferredFeatureId);
        setConfirmEnabled(false);
        setMetadataOnlyEligible(false);
        setOperationProgress(null);
        removeRunCandidateDrafts(completedRunId);
        setActiveRunId(null);
        resetGraphDiffDrawer();
    };

    const commitPreparedFeatureChanges = async () => {
        const result = await API.commitFeatureChanges(selectedType, null, activeRunId);
        setGitStatus(result.status || EMPTY_GIT_STATUS);
        resetGraphDiffDrawer();
        await finishFeatureCommit(result);
        message.success(result.commitScope === 'METADATA_ONLY'
            ? copy.metadataCommitSuccess
            : result.commitScope === 'COMPLETE'
                ? copy.completeCommitSuccess
                : copy.partialCommitSuccess);
        return result;
    };

    const performFeatureCommit = async () => {
        setLoadingConfirm(true);
        try {
            await commitPreparedFeatureChanges();
        } catch (error) {
            message.error(errorMessage(error, copy.failedCommitChanges));
            throw error;
        } finally {
            setLoadingConfirm(false);
        }
    };

    const performConfirmAllChanges = async (status) => {
        setLoadingConfirm(true);
        try {
            const normalizePath = (path) => String(path || '').replaceAll('\\', '/');
            const originalPaths = new Set((status.unstagedCandidatePaths || []).map(normalizePath));
            const remainingPaths = new Set(originalPaths);
            const stagedKeys = new Set();
            const identifiers = candidateIdentifiersForConfirmAll(
                graphData,
                status.unstagedCandidatePaths || []
            );

            for (const identifier of identifiers) {
                const normalizedIdentifier = normalizePath(identifier);
                if (originalPaths.has(normalizedIdentifier) && !remainingPaths.has(normalizedIdentifier)) {
                    continue;
                }
                const candidate = await API.getCandidateDiff(identifier, selectedType, activeRunId);
                const candidatePath = normalizePath(candidate?.path);
                if (!candidate?.key || !remainingPaths.has(candidatePath) || stagedKeys.has(candidate.key)) {
                    continue;
                }
                await API.stageCandidateFile(candidate.key, activeRunId);
                stagedKeys.add(candidate.key);
                remainingPaths.delete(candidatePath);
            }

            if (remainingPaths.size > 0) {
                throw new Error(copy.incompleteConfirmAllChanges);
            }
            const readyToCommit = await refreshGitStatus();
            if (readyToCommit.commitScope !== 'COMPLETE') {
                throw new Error(copy.incompleteConfirmAllChanges);
            }
            await commitPreparedFeatureChanges();
        } catch (error) {
            message.error(errorMessage(error, copy.failedConfirmAllChanges));
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
        const metadataOnly = selectedType === 'delete'
            && confirmEnabled
            && metadataOnlyEligible
            && !stagedCount
            && !(status.candidatePaths?.length || 0)
            && !(status.pendingCandidatePaths?.length || 0);
        if (!stagedCount && !metadataOnly) {
            message.warning(copy.noStagedFiles);
            return;
        }

        const complete = status.commitScope === 'COMPLETE';
        const partial = !metadataOnly && !complete;
        const remainingCount = status.unstagedCandidatePaths?.length || 0;
        modal.confirm({
            title: metadataOnly
                ? copy.metadataCommitTitle
                : complete ? copy.completeCommitTitle : copy.partialCommitTitle,
            icon: <ExclamationCircleOutlined/>,
            content: (
                <div className={styles.commitConfirmation}>
                    {partial ? (
                        <Alert
                            type="warning"
                            showIcon
                            message={copy.partialCommitTitle}
                            description={copy.partialCommitDescription}
                        />
                    ) : (
                        <p>{metadataOnly
                            ? copy.metadataCommitDescription
                            : copy.completeCommitDescription}</p>
                    )}
                    {!metadataOnly && (
                        <p>
                            {language === 'zh'
                                ? `本次提交 ${stagedCount} 个已暂存文件${remainingCount ? `，仍有 ${remainingCount} 个候选文件未确认` : ''}。`
                                : `This commit contains ${stagedCount} staged file${stagedCount === 1 ? '' : 's'}${remainingCount ? `; ${remainingCount} candidate file${remainingCount === 1 ? '' : 's'} remain unconfirmed` : ''}.`}
                        </p>
                    )}
                </div>
            ),
            okText: copy.commitChanges,
            okButtonProps: partial ? {danger: true} : undefined,
            cancelText: copy.cancel,
            onOk: performFeatureCommit,
        });
    };

    const requestConfirmAllChanges = async () => {
        if (candidateDirty || savingCandidate) {
            message.warning(copy.saveBeforeApply);
            return;
        }
        const status = await refreshGitStatus();
        const remainingCount = status.unstagedCandidatePaths?.length || 0;
        if (!remainingCount) {
            await requestFeatureCommit();
            return;
        }

        const totalCount = status.candidatePaths?.length || remainingCount;
        modal.confirm({
            title: copy.confirmAllChanges,
            icon: <ExclamationCircleOutlined/>,
            content: (
                <div className={styles.commitConfirmation}>
                    <Alert
                        type="warning"
                        showIcon
                        message={copy.confirmAllChanges}
                        description={copy.reviewAllChanges}
                    />
                    <p>{language === 'zh'
                        ? `将确认并提交全部 ${totalCount} 个候选文件，其中 ${remainingCount} 个尚未逐文件确认。`
                        : `All ${totalCount} candidate file${totalCount === 1 ? '' : 's'} will be committed; ${remainingCount} ${remainingCount === 1 ? 'has' : 'have'} not been confirmed individually.`}</p>
                </div>
            ),
            okText: copy.confirmApply,
            okButtonProps: {danger: true},
            cancelText: copy.decline,
            onOk: () => performConfirmAllChanges(status),
        });
    };

    const hasFocusGraphStages = supportsReasoningGraphStages(selectedType)
        && Array.isArray(focusGraphStages)
        && focusGraphStages.length > 0;
    const stagedFileCount = gitStatus.stagedPaths?.length || 0;
    const unstagedCandidateCount = gitStatus.unstagedCandidatePaths?.length || 0;
    const metadataOnlyDeleteReady = Boolean(
        activeRunId
        && confirmEnabled
        && metadataOnlyEligible
        && selectedType === 'delete'
        && !stagedFileCount
        && !(gitStatus.candidatePaths?.length || 0)
        && !(gitStatus.pendingCandidatePaths?.length || 0)
    );
    const candidateHasStagedChanges = Boolean(
        candidateFile?.path && gitStatus.stagedPaths?.includes(candidateFile.path)
    );
    const candidateIsFullyStaged = candidateHasStagedChanges
        && !gitStatus.unstagedPaths?.includes(candidateFile.path);
    const candidateDiffersFromOriginal = Boolean(candidateFile)
        && candidateDraft !== (candidateFile.originalContent ?? '');
    const candidateDiffersFromStaged = Boolean(candidateFile?.staged)
        && candidateDraft !== (candidateFile.stagedContent ?? '');
    const candidateCanStage = candidateDiffersFromOriginal || candidateDiffersFromStaged;
    const candidateIsUnchanged = Boolean(candidateFile)
        && candidateNodeTypeForDraft(candidateFile, candidateDraft) === 'Default';
    const candidateDiffFiles = useMemo(() => candidateFile ? [{
        ...candidateFile,
        status: candidateFile.newFile ? 'A' : candidateFile.deleted ? 'D' : 'M',
    }] : [], [candidateFile]);

    useEffect(() => {
        const candidateReady = confirmEnabled
            && (selectedType === "delete" || selectedType === "edit" || selectedType === "add")
            && Array.isArray(graphData?.nodes)
            && graphData.nodes.length > 0;
        if (candidateReady) {
            loadGitDiffEditor();
        }
    }, [confirmEnabled, graphData, selectedType]);
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
                                                                setEditedText={updateEditedText}
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
                                                    <Tooltip title={stagedFileCount || metadataOnlyDeleteReady
                                                        ? copy.commitChanges
                                                        : copy.noStagedFiles}>
                                                        <Button
                                                            type="primary"
                                                            size="small"
                                                            icon={<CheckCircleOutlined/>}
                                                            disabled={(!stagedFileCount && !metadataOnlyDeleteReady)
                                                                || candidateDirty || savingCandidate || stagingCandidate}
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
                                                    <Tooltip title={copy.confirmAllChanges}>
                                                        <Button
                                                            type="primary"
                                                            danger
                                                            size="small"
                                                            icon={<CheckSquareOutlined/>}
                                                            disabled={!unstagedCandidateCount
                                                                || !confirmEnabled
                                                                || loadingConfirm
                                                                || candidateDirty
                                                                || savingCandidate
                                                                || stagingCandidate}
                                                            onClick={(event) => {
                                                                event.stopPropagation();
                                                                requestConfirmAllChanges();
                                                            }}
                                                            className={styles.gitCommitButton}
                                                            aria-label={copy.confirmAllChanges}
                                                        >
                                                            {unstagedCandidateCount || null}
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
                                        {candidateFile.warning && (
                                            <Alert type="warning" showIcon message={candidateFile.warning}/>
                                        )}
                                        {candidateSaveError && (
                                            <Alert type="error" showIcon message={candidateSaveError}/>
                                        )}
                                        <div className={styles.candidateEditorView}>
                                            <CodeDiffComponent
                                                files={candidateDiffFiles}
                                                value={candidateDraft}
                                                onChange={(value) => {
                                                    const nodeType = candidateNodeTypeForDraft(candidateFile, value);
                                                    candidateDraftRef.current = value;
                                                    autoSaveAttemptRef.current = null;
                                                    setCandidateDraft(value);
                                                    setCandidateDirty(value !== (candidateFile.modifiedContent || ''));
                                                    setCandidateSaveError(null);
                                                    setGraphData((current) => current ? {
                                                        ...current,
                                                        nodes: (current.nodes || []).map((node) => (
                                                            String(node.id) === String(selectedCodeNodeId)
                                                                ? {...node, type: nodeType}
                                                                : node
                                                        )),
                                                    } : current);
                                                }}
                                                onSave={saveCandidateDiff}
                                                readOnly={candidateIsFullyStaged}
                                            />
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
                                        title={candidateIsFullyStaged ? "" : candidateDirty
                                            ? copy.saveBeforeApply
                                            : !candidateCanStage ? copy.noCandidateChanges
                                                : !confirmEnabled ? copy.noSubmittedChanges : ""}
                                    >
                                        <div className={styles.centerButtonWrapper}>
                                            {candidateIsFullyStaged ? (
                                                <Button
                                                    type="primary"
                                                    danger
                                                    icon={<UndoOutlined/>}
                                                    loading={unstagingCandidate}
                                                    disabled={savingCandidate || stagingCandidate}
                                                    onClick={unstageCandidateDiff}
                                                >
                                                    {unstagingCandidate ? copy.unstagingFile : copy.unstageFile}
                                                </Button>
                                            ) : (
                                                <>
                                                    <Button
                                                        type="primary"
                                                        icon={<CheckOutlined/>}
                                                        loading={stagingCandidate}
                                                        disabled={!confirmEnabled
                                                            || candidateDirty
                                                            || savingCandidate
                                                            || unstagingCandidate
                                                            || !candidateCanStage}
                                                        onClick={stageCandidateDiff}
                                                    >
                                                        {stagingCandidate ? copy.stagingFile : copy.confirmFile}
                                                    </Button>
                                                    {(selectedType === 'delete' || selectedType === 'edit' || selectedType === 'add') && (
                                                        <Button
                                                            danger
                                                            icon={<UndoOutlined/>}
                                                            loading={revertingCandidate}
                                                            disabled={savingCandidate
                                                                || stagingCandidate
                                                                || unstagingCandidate
                                                                || candidateIsUnchanged}
                                                            onClick={revertCandidateDiff}
                                                        >
                                                            {revertingCandidate ? copy.revertingFile : copy.revertFile}
                                                        </Button>
                                                    )}
                                                </>
                                            )}
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
