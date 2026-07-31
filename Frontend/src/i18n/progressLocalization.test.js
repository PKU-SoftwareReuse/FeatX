import {
    formatLocalizedDuration,
    localizeOperationProgressMessage,
    localizeSummaryProgressMessage,
    localizeSummaryStageLabel,
    localizeSummaryStatus,
    localizeSummaryStepDetail,
} from "./progressLocalization";

describe("feature-summary progress localization", () => {
    test("presents the backend module-description stage as Topic in Chinese and Epic in English", () => {
        const step = {
            id: "module-description",
            messageKey: "progress.summary.module-description",
            messageArgs: {},
            status: "running",
        };

        expect(localizeSummaryStageLabel(step, "zh")).toBe("生成主题描述");
        expect(localizeSummaryStepDetail(step, "zh")).toBe("正在生成主题描述。");
        expect(localizeSummaryStageLabel(step, "en")).toBe("Generate Epic descriptions");
        expect(localizeSummaryStepDetail(step, "en")).toBe("Generating Epic descriptions.");
    });

    test("localizes backend messages and statuses instead of displaying them verbatim", () => {
        const progress = {
            currentStage: "structure",
            messageKey: "progress.summary.structure",
            messageArgs: {},
            status: "running",
        };

        expect(localizeSummaryProgressMessage(progress, "zh")).toBe("正在分析文件和依赖关系。");
        expect(localizeSummaryStatus("running", "zh")).toBe("进行中");
        expect(localizeSummaryStatus("done", "en")).toBe("Completed");
    });

    test("uses structured arguments and falls back for unknown message keys", () => {
        expect(localizeSummaryProgressMessage({
            currentStage: "clustering",
            messageKey: "progress.summary.clustering",
            messageArgs: {clusterId: 4, functionCount: 90},
            status: "running",
        }, "zh")).toBe("正在聚类 Java 方法：聚类 4 包含 90 个函数。");

        expect(localizeSummaryProgressMessage({
            currentStage: "future-stage",
            messageKey: "progress.summary.future-stage",
            messageArgs: {},
            status: "running",
        }, "en")).toBe("Processing the feature summary.");
    });

    test("formats elapsed time for the active locale", () => {
        expect(formatLocalizedDuration(3_723_000, "zh")).toBe("1 小时 2 分 3 秒");
        expect(formatLocalizedDuration(3_723_000, "en")).toBe("1h 2m 3s");
    });
});

describe("feature-operation progress localization", () => {
    test("localizes known add, modify, and delete stages", () => {
        expect(localizeOperationProgressMessage({
            operation: "python-add",
            stage: "feature-retrieval",
            messageKey: "progress.operation.feature-retrieval",
            messageArgs: {featureCount: 12},
        }, "zh")).toBe("正在检索最相关的功能特征（12 项候选）。");

        expect(localizeOperationProgressMessage({
            operation: "java-modify",
            stage: "graph-ranking",
            messageKey: "progress.operation.graph-ranking",
            messageArgs: {expandedNodeCount: 30},
        }, "en")).toBe("Ranking expanded graph nodes by relevance (30 nodes).");

        expect(localizeOperationProgressMessage({
            operation: "python-delete",
            stage: "ast-delete-boundary",
            messageKey: "progress.operation.ast-delete-boundary",
            messageArgs: {},
        }, "zh")).toBe("正在确定 Python AST 删除边界。");
    });

    test("uses operation-aware copy for frontend and terminal stages", () => {
        expect(localizeOperationProgressMessage({
            operation: "java-add",
            stage: "submit",
            messageKey: "progress.operation.submit",
            messageArgs: {},
        }, "zh")).toBe("正在提交功能新增请求。");

        expect(localizeOperationProgressMessage({
            operation: "python-delete",
            stage: "agent-stream",
            messageKey: "progress.operation.agent-stream",
            messageArgs: {},
        }, "en")).toBe("Generating code changes for feature deletion.");
    });

    test("falls back for an unknown backend message key", () => {
        expect(localizeOperationProgressMessage({
            operation: "java-modify",
            stage: "future-stage",
            messageKey: "progress.operation.future-stage",
            messageArgs: {},
        }, "zh")).toBe("正在处理功能修改请求。");
    });
});
