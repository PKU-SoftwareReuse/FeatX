package com.mycode.service.code;

import java.util.Arrays;

public enum AgentRunMode {
    JAVA_ADD("java-add", "Java", AgentOperation.ADD),
    JAVA_MODIFY("java-modify", "Java", AgentOperation.MODIFY),
    JAVA_DELETE("java-delete", "Java", AgentOperation.DELETE),
    PYTHON_ADD("python-add", "Python", AgentOperation.ADD),
    PYTHON_MODIFY("python-modify", "Python", AgentOperation.MODIFY),
    PYTHON_DELETE("python-delete", "Python", AgentOperation.DELETE);

    private final String id;
    private final String projectLanguage;
    private final AgentOperation operation;

    AgentRunMode(String id, String projectLanguage, AgentOperation operation) {
        this.id = id;
        this.projectLanguage = projectLanguage;
        this.operation = operation;
    }

    public String id() {
        return id;
    }

    public String projectLanguage() {
        return projectLanguage;
    }

    public AgentOperation operation() {
        return operation;
    }

    public static boolean matchesOperation(String modeId, String requestedOperation) {
        AgentOperation requested = switch (normalize(requestedOperation)) {
            case "add" -> AgentOperation.ADD;
            case "edit", "modify" -> AgentOperation.MODIFY;
            case "delete" -> AgentOperation.DELETE;
            default -> null;
        };
        if (requested == null) {
            return false;
        }
        return Arrays.stream(values())
                .anyMatch(mode -> mode.id.equals(normalize(modeId)) && mode.operation == requested);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
