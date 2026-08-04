package com.mycode.service.code;

public enum AgentLanguage {
    CN,
    EN;

    public static AgentLanguage orDefault(AgentLanguage language) {
        return language == null ? EN : language;
    }

    public String promptLanguageName() {
        return this == CN ? "中文" : "英语";
    }

    public String featureLabel() {
        return this == CN ? "功能特征：" : "Feature:";
    }

    public String noExtraInformation() {
        return this == CN ? "无" : "None";
    }

    public String stageOneDescription() {
        return this == CN
                ? "## 阶段 I：信息需求分析\n"
                : "## Stage I: Information Requirement Analysis\n";
    }

    public String stageOneRecheckDescription() {
        return this == CN
                ? "\n#### 补充上下文复核\n"
                : "\n#### Additional Context Recheck\n";
    }

    public String stageTwoDescription() {
        return this == CN
                ? "\n## 阶段 II：修改方案规划\n"
                : "\n## Stage II: Modification Planning\n";
    }

    public String stageThreeDescription() {
        return this == CN
                ? "\n## 阶段 III：具体文件修改\n"
                : "\n## Stage III: Concrete File Modification\n";
    }

    public String stageThreeFileDescription(String filename) {
        return this == CN
                ? "\n#### 文件：`" + filename + "`\n"
                : "\n#### File: `" + filename + "`\n";
    }

    public String fileAlreadyCurrentDescription(String filename) {
        return this == CN
                ? "\n> `" + filename + "` 已符合目标状态，本文件无需修改。\n"
                : "\n> `" + filename + "` already matches the target state; no changes are required.\n";
    }

    public String fileSkippedForSafetyDescription(String filename) {
        return this == CN
                ? "\n> `" + filename + "` 无法在安全边界内完成修改，已保留原文件并继续处理后续文件。\n"
                : "\n> `" + filename + "` could not be changed within the safety boundary; the original file "
                        + "was preserved and processing continued with the remaining files.\n";
    }

    public String pipelineCompleteDescription() {
        return this == CN
                ? "\n> 流程已完成。\n"
                : "\n> Pipeline complete.\n";
    }

    public String pipelineErrorDescription() {
        return this == CN
                ? "错误: 智能体流程执行失败。"
                : "Error: Agent pipeline failed.";
    }

    public String recommendReasonExample() {
        return this == CN ? "该文件……" : "This file...";
    }

    public String modificationPlanExample() {
        return this == CN
                ? "详细修改方案。第一步……第二步……需要……"
                : "Detailed modification plan. First, ... Second, ... I need ...";
    }

    public String modificationNoteExample() {
        return this == CN
                ? "需要注意的重点或难点。"
                : "Some important or difficult point need to pay attention to. ";
    }
}
