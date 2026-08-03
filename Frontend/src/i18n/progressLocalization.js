const normalizedLanguage = (language) => language === "zh" ? "zh" : "en";

const hasValue = (value) => value !== null && value !== undefined && value !== "";

const summaryFailureMessage = (language, args, fallback) => {
    if (!hasValue(args?.error)) return fallback;
    return language === "zh"
        ? `${fallback} 原因：${args.error}`
        : `${fallback} Cause: ${args.error}`;
};

const summaryFunctionProgress = (language, args, fallback) => {
    if (!hasValue(args?.done) || !hasValue(args?.total)) return fallback;
    return language === "zh"
        ? `正在生成函数摘要（${args.done}/${args.total}）。`
        : `Generating function summaries (${args.done}/${args.total}).`;
};

const summaryFeatureProgress = (language, args, fallback) => {
    if (hasValue(args?.done) && hasValue(args?.total)) {
        return language === "zh"
            ? `正在生成功能特征描述（${args.done}/${args.total}）。`
            : `Generating feature descriptions (${args.done}/${args.total}).`;
    }
    if (hasValue(args?.featureCount)) {
        return language === "zh"
            ? `正在为 ${args.featureCount} 项功能特征准备描述。`
            : `Preparing descriptions for ${args.featureCount} features.`;
    }
    return fallback;
};

const summaryClusteringProgress = (language, args, fallback) => {
    if (hasValue(args?.functionCount)) {
        return language === "zh"
            ? `正在聚类 Java 方法：聚类 ${args.clusterId} 包含 ${args.functionCount} 个函数。`
            : `Clustering Java methods: cluster ${args.clusterId} contains ${args.functionCount} functions.`;
    }
    if (hasValue(args?.fileCount)) {
        return language === "zh"
            ? `正在聚类 Java 文件：聚类 ${args.clusterId} 包含 ${args.fileCount} 个文件。`
            : `Clustering Java files: cluster ${args.clusterId} contains ${args.fileCount} files.`;
    }
    return fallback;
};

const completedSummaryDetail = (step, language, label) => {
    const key = summaryMessageKey(step);
    const args = step?.messageArgs || {};
    if (key === "progress.summary.function-summary" && hasValue(args.done) && hasValue(args.total)) {
        return language === "zh"
            ? `函数摘要已完成（${args.done}/${args.total}）。`
            : `Function summaries completed (${args.done}/${args.total}).`;
    }
    if (key === "progress.summary.feature-description") {
        if (hasValue(args.done) && hasValue(args.total)) {
            return language === "zh"
                ? `功能特征描述已完成（${args.done}/${args.total}）。`
                : `Feature descriptions completed (${args.done}/${args.total}).`;
        }
        if (hasValue(args.featureCount)) {
            return language === "zh"
                ? `${args.featureCount} 项功能特征的描述已完成。`
                : `Descriptions completed for ${args.featureCount} features.`;
        }
    }
    if (key === "progress.summary.clustering") {
        if (hasValue(args.functionCount)) {
            return language === "zh"
                ? `Java 方法聚类已完成：聚类 ${args.clusterId} 包含 ${args.functionCount} 个函数。`
                : `Java method clustering completed: cluster ${args.clusterId} contains ${args.functionCount} functions.`;
        }
        if (hasValue(args.fileCount)) {
            return language === "zh"
                ? `Java 文件聚类已完成：聚类 ${args.clusterId} 包含 ${args.fileCount} 个文件。`
                : `Java file clustering completed: cluster ${args.clusterId} contains ${args.fileCount} files.`;
        }
    }
    return language === "zh" ? `${label}已完成。` : `${label} completed.`;
};

const SUMMARY_COPY = {
    zh: {
        fallbackLabel: "摘要步骤",
        fallbackRunning: "正在处理功能特征摘要。",
        summaryComplete: "功能特征摘要已完成。",
        summaryFailed: "功能特征摘要生成失败。",
        waiting: "等待中。",
        statuses: {
            pending: "等待中",
            running: "进行中",
            done: "已完成",
            complete: "已完成",
            failed: "失败",
        },
        messages: {
            "progress.summary.start": ["启动摘要", "正在启动功能特征摘要。"],
            "progress.summary.structure": ["分析项目结构", "正在分析文件和依赖关系。"],
            "progress.summary.function-summary": [
                "生成函数摘要",
                (args) => summaryFunctionProgress("zh", args, "正在生成函数摘要。"),
            ],
            "progress.summary.clustering": [
                "聚类功能特征",
                (args) => summaryClusteringProgress("zh", args, "正在将代码聚类为功能特征。"),
            ],
            "progress.summary.feature-description": [
                "生成功能特征描述",
                (args) => summaryFeatureProgress("zh", args, "正在生成功能特征描述。"),
            ],
            "progress.summary.module-description": ["生成主题描述", "正在生成主题描述。"],
            "progress.summary.database": ["写入摘要数据", "正在将主题、功能特征和 CodeMap 写入数据库。"],
            "progress.summary.embedding-cache": ["构建嵌入缓存", "正在构建静态图和持久化嵌入缓存。"],
            "progress.summary.complete": ["摘要完成", "功能特征摘要已完成。"],
            "progress.summary.failed": ["摘要失败", "功能特征摘要生成失败。"],
            "progress.summary.llm-retry": ["重试描述生成", "LLM 调用失败，正在重试。"],
            "progress.summary.warning": ["摘要警告", "摘要处理遇到异常，正在等待任务状态更新。"],
        },
    },
    en: {
        fallbackLabel: "Summary step",
        fallbackRunning: "Processing the feature summary.",
        summaryComplete: "Feature summary completed.",
        summaryFailed: "Feature summary generation failed.",
        waiting: "Waiting.",
        statuses: {
            pending: "Pending",
            running: "In progress",
            done: "Completed",
            complete: "Completed",
            failed: "Failed",
        },
        messages: {
            "progress.summary.start": ["Start summary", "Starting feature summarization."],
            "progress.summary.structure": ["Analyze project structure", "Analyzing files and dependencies."],
            "progress.summary.function-summary": [
                "Generate function summaries",
                (args) => summaryFunctionProgress("en", args, "Generating function summaries."),
            ],
            "progress.summary.clustering": [
                "Cluster features",
                (args) => summaryClusteringProgress("en", args, "Clustering code into features."),
            ],
            "progress.summary.feature-description": [
                "Generate feature descriptions",
                (args) => summaryFeatureProgress("en", args, "Generating feature descriptions."),
            ],
            "progress.summary.module-description": ["Generate Epic descriptions", "Generating Epic descriptions."],
            "progress.summary.database": ["Write summary data", "Writing Epics, features, and CodeMap data to the database."],
            "progress.summary.embedding-cache": ["Build embedding cache", "Building the static graph and persistent embedding cache."],
            "progress.summary.complete": ["Summary complete", "Feature summary completed."],
            "progress.summary.failed": ["Summary failed", "Feature summary generation failed."],
            "progress.summary.llm-retry": ["Retry description generation", "The LLM call failed and is being retried."],
            "progress.summary.warning": ["Summary warning", "Summary processing encountered an error and is awaiting a status update."],
        },
    },
};

const summaryMessageKey = (record) => {
    if (record?.messageKey) return record.messageKey;
    const stage = record?.id || record?.currentStage;
    return stage ? `progress.summary.${stage}` : "";
};

const getSummaryMessage = (record, language) => {
    const lang = normalizedLanguage(language);
    return SUMMARY_COPY[lang].messages[summaryMessageKey(record)];
};

const renderMessage = (message, args) => typeof message === "function" ? message(args || {}) : message;

export const localizeSummaryStatus = (status, language) => {
    const lang = normalizedLanguage(language);
    const normalizedStatus = String(status || "").toLowerCase();
    return SUMMARY_COPY[lang].statuses[normalizedStatus] || (lang === "zh" ? "未知" : "Unknown");
};

export const localizeSummaryStageLabel = (step, language) => {
    const lang = normalizedLanguage(language);
    return getSummaryMessage(step, lang)?.[0] || SUMMARY_COPY[lang].fallbackLabel;
};

export const localizeSummaryProgressMessage = (progress, language) => {
    const lang = normalizedLanguage(language);
    const copy = SUMMARY_COPY[lang];
    const status = String(progress?.status || "").toLowerCase();
    if (status === "failed") {
        return summaryFailureMessage(lang, progress?.messageArgs, copy.summaryFailed);
    }
    if (status === "complete" || status === "done") return copy.summaryComplete;

    const message = getSummaryMessage(progress, lang)?.[1];
    return renderMessage(message, progress?.messageArgs) || copy.fallbackRunning;
};

export const localizeSummaryStepDetail = (step, language) => {
    const lang = normalizedLanguage(language);
    const copy = SUMMARY_COPY[lang];
    const status = String(step?.status || "pending").toLowerCase();
    const label = localizeSummaryStageLabel(step, lang);

    if (status === "pending") return copy.waiting;
    if (status === "failed") {
        const fallback = lang === "zh" ? `${label}失败。` : `${label} failed.`;
        return summaryFailureMessage(lang, step?.messageArgs, fallback);
    }

    const message = renderMessage(getSummaryMessage(step, lang)?.[1], step?.messageArgs);
    if (status === "done" || status === "complete") {
        return completedSummaryDetail(step, lang, label);
    }
    return message || copy.fallbackRunning;
};

export const formatLocalizedDuration = (milliseconds, language) => {
    const lang = normalizedLanguage(language);
    const safeMilliseconds = Number.isFinite(Number(milliseconds))
        ? Math.max(0, Number(milliseconds))
        : 0;
    const totalSeconds = Math.floor(safeMilliseconds / 1000);
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;

    if (lang === "zh") {
        if (hours > 0) return `${hours} 小时 ${minutes} 分 ${seconds} 秒`;
        if (minutes > 0) return `${minutes} 分 ${seconds} 秒`;
        return `${seconds} 秒`;
    }
    if (hours > 0) return `${hours}h ${minutes}m ${seconds}s`;
    if (minutes > 0) return `${minutes}m ${seconds}s`;
    return `${seconds}s`;
};

const operationCount = (language, args, key, zhUnit, enUnit) => {
    if (!hasValue(args?.[key])) return "";
    return language === "zh" ? `（${args[key]} ${zhUnit}）` : ` (${args[key]} ${enUnit})`;
};

const OPERATION_STAGE_COPY = {
    zh: {
        "collect-code-map": "正在收集当前功能特征的 CodeMap 方法。",
        "delta-query": "正在使用 LLM 提取功能变更查询。",
        "focusgraph-context": "正在根据相似功能特征构建 FocusGraph 上下文。",
        "load-summary": "正在加载功能特征摘要、方法和依赖图。",
        "feature-retrieval": (args) => `正在检索最相关的功能特征${operationCount("zh", args, "featureCount", "项候选", "candidates")}。`,
        "delete-seeds": "正在使用当前功能特征的 CodeMap 构建删除种子。",
        "seed-methods": (args) => `正在收集所选功能特征的种子方法${operationCount("zh", args, "seedMethodCount", "个方法", "methods")}。`,
        "load-enre": "正在加载 ENRE 依赖图。",
        "graph-expansion": "正在构建初始图并扩展 FocusGraph。",
        "max-graph": "正在构建 Java 扩展图。",
        "java-graph": "正在构建 Java 推理图。",
        "graph-ranking": (args) => `正在对扩展图节点进行相关性排序${operationCount("zh", args, "expandedNodeCount", "个节点", "nodes")}。`,
        "top-k-subgraph": (args) => `正在选取 Top-K 推理子图${operationCount("zh", args, "selectedNodeCount", "个节点", "nodes")}。`,
        "ownership-check": (args) => `正在保护与其他功能特征共享的 CodeMap 方法${operationCount("zh", args, "protectedSharedMethods", "个共享方法", "shared methods")}。`,
        "ast-delete-boundary": "正在确定 Python AST 删除边界。",
        "context-prompt": "正在组装 Agent 所需的代码上下文。",
        "prepare-agent": "正在准备 Agent 输入。",
        idle: "当前没有正在运行的功能操作。",
    },
    en: {
        "collect-code-map": "Collecting the current feature's CodeMap methods.",
        "delta-query": "Extracting the feature delta query with the LLM.",
        "focusgraph-context": "Building FocusGraph context from similar features.",
        "load-summary": "Loading feature summaries, methods, and dependency graphs.",
        "feature-retrieval": (args) => `Retrieving the most relevant features${operationCount("en", args, "featureCount", "项候选", "candidates")}.`,
        "delete-seeds": "Using the current feature's CodeMap to build deletion seeds.",
        "seed-methods": (args) => `Collecting seed methods from the selected features${operationCount("en", args, "seedMethodCount", "个方法", "methods")}.`,
        "load-enre": "Loading the ENRE dependency graph.",
        "graph-expansion": "Building the initial graph and expanding FocusGraph.",
        "max-graph": "Building the expanded Java graph.",
        "java-graph": "Building the Java reasoning graph.",
        "graph-ranking": (args) => `Ranking expanded graph nodes by relevance${operationCount("en", args, "expandedNodeCount", "个节点", "nodes")}.`,
        "top-k-subgraph": (args) => `Selecting the Top-K reasoning subgraph${operationCount("en", args, "selectedNodeCount", "个节点", "nodes")}.`,
        "ownership-check": (args) => `Protecting CodeMap methods shared with other features${operationCount("en", args, "protectedSharedMethods", "个共享方法", "shared methods")}.`,
        "ast-delete-boundary": "Determining the Python AST deletion boundary.",
        "context-prompt": "Assembling the code context required by the Agent.",
        "prepare-agent": "Preparing Agent inputs.",
        idle: "No feature operation is running.",
    },
};

const operationKind = (operation) => {
    const normalized = String(operation || "").toLowerCase();
    if (normalized.endsWith("-add")) return "add";
    if (normalized.endsWith("-delete")) return "delete";
    return "modify";
};

const operationPlatform = (operation) => {
    const normalized = String(operation || "").toLowerCase();
    if (normalized.startsWith("python-")) return "Python";
    if (normalized.startsWith("java-")) return "Java";
    return "";
};

const operationCopy = (operation, language) => {
    const lang = normalizedLanguage(language);
    const kind = operationKind(operation);
    const platform = operationPlatform(operation);

    if (lang === "zh") {
        const action = {add: "新增", modify: "修改", delete: "删除"}[kind];
        return {
            submit: `正在提交功能${action}请求。`,
            start: `正在准备功能${action}所需的上下文。`,
            complete: "推理上下文已就绪，正在启动 Agent 代码生成。",
            generationComplete: "代码生成已完成，请检查差异后确认或放弃。",
            restoring: "正在恢复刷新前的 Agent 任务。",
            stream: kind === "delete"
                ? "正在生成用于删除功能的代码变更。"
                : `正在流式生成${platform ? ` ${platform}` : ""} 代码变更。`,
            failed: `功能${action}处理失败。`,
            fallback: `正在处理功能${action}请求。`,
        };
    }

    const action = {add: "addition", modify: "modification", delete: "deletion"}[kind];
    return {
        submit: `Submitting the feature ${action} request.`,
        start: `Preparing context for feature ${action}.`,
        complete: "The reasoning context is ready. Starting Agent code generation.",
        generationComplete: "Code generation finished. Review the diff and confirm or discard it.",
        restoring: "Restoring the Agent run from before the refresh.",
        stream: kind === "delete"
            ? "Generating code changes for feature deletion."
            : `Streaming${platform ? ` ${platform}` : ""} code changes.`,
        failed: `Feature ${action} failed.`,
        fallback: `Processing the feature ${action} request.`,
    };
};

const operationStage = (progress) => {
    const key = String(progress?.messageKey || "");
    if (key.startsWith("progress.operation.")) return key.slice("progress.operation.".length);
    return String(progress?.stage || "").toLowerCase();
};

export const localizeOperationProgressMessage = (progress, language) => {
    const lang = normalizedLanguage(language);
    const stage = operationStage(progress);
    const copy = operationCopy(progress?.operation, lang);

    if (stage === "submit") return copy.submit;
    if (stage === "start") return copy.start;
    if (stage === "complete") return copy.complete;
    if (stage === "generation-complete") return copy.generationComplete;
    if (stage === "agent-stream") return progress?.messageArgs?.restoring ? copy.restoring : copy.stream;
    if (stage === "failed" || progress?.failed) return copy.failed;

    return renderMessage(OPERATION_STAGE_COPY[lang][stage], progress?.messageArgs) || copy.fallback;
};
