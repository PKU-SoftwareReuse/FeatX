package com.mycode.service.code;

public enum AgentOperation {
    ADD("功能新增"),
    MODIFY("功能修改"),
    DELETE("功能删除");

    private final String promptLabel;

    AgentOperation(String promptLabel) {
        this.promptLabel = promptLabel;
    }

    public String promptLabel() {
        return promptLabel;
    }

    public boolean isAddition() {
        return this == ADD;
    }

    public boolean isDeletion() {
        return this == DELETE;
    }
}
