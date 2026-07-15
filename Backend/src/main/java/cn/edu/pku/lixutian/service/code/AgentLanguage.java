package cn.edu.pku.lixutian.service.code;

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
                ? "# === 阶段 I：信息需求分析 ===\n"
                : "# === Stage I: Information Requirement Analysis ===\n";
    }

    public String stageTwoDescription() {
        return this == CN
                ? "\n# === 阶段 II：修改方案规划 ===\n"
                : "\n# === Stage II: Modification Planning ===\n";
    }

    public String stageThreeDescription(String filename) {
        return this == CN
                ? "\n# === 阶段 III：具体文件修改 " + filename + " ===\n"
                : "\n# === Stage III: Concrete File Modification " + filename + " ===\n";
    }

    public String pipelineCompleteDescription() {
        return this == CN
                ? "\n# === 流程已完成！ ===\n"
                : "\n# === Pipeline complete! ===\n";
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
