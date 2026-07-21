package cn.edu.pku.lixutian.service;

import cn.edu.pku.lixutian.config.ProjectState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OperationProgressServiceTest {
    @Test
    void progressIsStoredPerRepository(@TempDir Path first, @TempDir Path second) {
        ProjectState firstProject = ProjectState.selectWorkspace("progress-a", 401, first.toString(), "JAVA");
        ProjectState secondProject = ProjectState.selectWorkspace("progress-b", 402, second.toString(), "JAVA");
        OperationProgressService service = new OperationProgressService();

        try (ProjectState.Scope ignored = ProjectState.bindProject("progress-a", firstProject)) {
            service.start("modify", 8, "prepare", "Preparing first project");
        }
        try (ProjectState.Scope ignored = ProjectState.bindProject("progress-b", secondProject)) {
            service.start("add", 8, "prepare", "Preparing second project");
            assertEquals("add", service.getSnapshot().getOperation());
        }
        try (ProjectState.Scope ignored = ProjectState.bindProject("progress-a", firstProject)) {
            assertEquals("modify", service.getSnapshot().getOperation());
            assertEquals("Preparing first project", service.getSnapshot().getMessage());
        }
    }
}
