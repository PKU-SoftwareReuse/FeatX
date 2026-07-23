package cn.edu.pku.lixutian.service.code;

public enum AgentOperation {
    ADD("feature addition"),
    MODIFY("feature modification"),
    DELETE("feature deletion");

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
