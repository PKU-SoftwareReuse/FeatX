package cn.edu.pku.lixutian.service;

import cn.edu.pku.lixutian.config.ClusterState;
import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.dto.result.FeatureResult;
import cn.edu.pku.lixutian.dto.result.GitCommitResult;
import cn.edu.pku.lixutian.dto.result.GitWorkspaceStatusResult;
import cn.edu.pku.lixutian.service.code.AgentRunContext;
import cn.edu.pku.lixutian.service.code.AgentRunRegistry;
import cn.edu.pku.lixutian.service.code.AgentLanguage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FeatureGitWorkflowServiceTest {
    @AfterEach
    void resetGlobalState() {
        ProjectState.getInstance().setModifications(Map.of());
        ProjectState.getInstance().setPythonModifiedMethods(Set.of());
        ClusterState state = ClusterState.getInstance();
        state.setCandidateFeature(null);
        state.setCandidateModuleId(null);
        state.setNewFeatureDescription(null);
    }

    @Test
    void stagesOneFileThenCreatesPartialAndCompleteCommits(@TempDir Path repository) throws Exception {
        initializeRepository(repository);
        CandidateCodeService candidateService = prepareTwoPythonCandidates(repository);
        RepositoryGitService gitService = new RepositoryGitService(candidateService);
        CodeMapService codeMapService = mock(CodeMapService.class);
        TestRun run = completedEditRun();
        AgentRunRegistry runRegistry = run.registry();
        String runId = run.runId();
        FeatureGitWorkflowService workflow = new FeatureGitWorkflowService(
                gitService,
                candidateService,
                codeMapService,
                runRegistry
        );
        selectFeatureForEdit();

        GitWorkspaceStatusResult stagedFirst = workflow.stageCandidate("first.py", runId);
        assertEquals("PARTIAL", stagedFirst.getCommitScope());
        assertEquals(java.util.List.of("first.py"), stagedFirst.getStagedPaths());
        assertEquals(java.util.List.of("second.py"), stagedFirst.getUnstagedCandidatePaths());

        GitCommitResult partial = workflow.commit("edit", null, runId);
        assertEquals("PARTIAL", partial.getCommitScope());
        assertEquals("print('first changed')\n", Files.readString(repository.resolve("first.py")));
        assertEquals("print('second')\n", Files.readString(repository.resolve("second.py")));
        assertEquals(java.util.List.of("second.py"), partial.getStatus().getPendingCandidatePaths());
        verifyNoInteractions(codeMapService);

        GitWorkspaceStatusResult stagedSecond = workflow.stageCandidate("second.py", runId);
        assertEquals("COMPLETE", stagedSecond.getCommitScope());
        when(codeMapService.modifyFeatureFromMemoryAndDatabase(7, "updated feature", ClusterState.getInstance().getAgentLanguage()))
                .thenReturn(7);

        GitCommitResult complete = workflow.commit("edit", null, runId);
        assertEquals("COMPLETE", complete.getCommitScope());
        assertEquals(7, complete.getFeatureId());
        assertTrue(complete.getStatus().getStagedPaths().isEmpty());
        assertTrue(complete.getStatus().getPendingCandidatePaths().isEmpty());
        assertTrue(ProjectState.getInstance().getModifications().isEmpty());
        assertEquals("3", runGit(repository, "rev-list", "--count", "HEAD").trim());
        verify(codeMapService).modifyFeatureFromMemoryAndDatabase(
                7,
                "updated feature",
                ClusterState.getInstance().getAgentLanguage()
        );
    }

    @Test
    void discardKeepsPartialCommitAndIgnoredPreprocessOutput(@TempDir Path repository) throws Exception {
        initializeRepository(repository);
        CandidateCodeService candidateService = prepareTwoPythonCandidates(repository);
        RepositoryGitService gitService = new RepositoryGitService(candidateService);
        TestRun run = completedEditRun();
        AgentRunRegistry runRegistry = run.registry();
        String runId = run.runId();
        FeatureGitWorkflowService workflow = new FeatureGitWorkflowService(
                gitService,
                candidateService,
                mock(CodeMapService.class),
                runRegistry
        );
        selectFeatureForEdit();

        workflow.stageCandidate("first.py", runId);
        workflow.commit("edit", null, runId);
        workflow.stageCandidate("second.py", runId);
        Files.writeString(repository.resolve("notes.txt"), "untracked\n");
        Path ignoredOutput = repository.resolve("preprocess1/report.csv");
        Files.createDirectories(ignoredOutput.getParent());
        Files.writeString(ignoredOutput, "generated\n");

        GitWorkspaceStatusResult discarded = workflow.discard();

        assertEquals("print('first changed')\n", Files.readString(repository.resolve("first.py")));
        assertEquals("print('second')\n", Files.readString(repository.resolve("second.py")));
        assertFalse(Files.exists(repository.resolve("notes.txt")));
        assertTrue(Files.exists(ignoredOutput));
        assertTrue(discarded.getStagedPaths().isEmpty());
        assertTrue(discarded.getUnstagedPaths().isEmpty());
        assertTrue(discarded.getUntrackedPaths().isEmpty());
        assertEquals("2", runGit(repository, "rev-list", "--count", "HEAD").trim());
    }

    @Test
    void discardInitializesLegacyNonGitProjectAndKeepsGeneratedOutput(@TempDir Path repository) throws Exception {
        Files.writeString(repository.resolve("feature.py"), "print('original')\n");
        Path generatedOutput = repository.resolve("preprocess1/report.csv");
        Files.createDirectories(generatedOutput.getParent());
        Files.writeString(generatedOutput, "generated\n");
        ProjectState.getInstance().setProjectPath(repository.toString(), "PYTHON");
        ProjectState.getInstance().setRepoId(51);

        CandidateCodeService candidateService = new CandidateCodeService();
        ProjectState.getInstance().setModifications(Map.of("feature.py", "print('candidate')\n"));
        candidateService.preparePythonCandidate(
                "feature.py",
                "feature.py",
                ProjectState.getInstance().getModifications().get("feature.py")
        );
        FeatureGitWorkflowService workflow = new FeatureGitWorkflowService(
                new RepositoryGitService(candidateService),
                candidateService,
                mock(CodeMapService.class),
                new AgentRunRegistry()
        );

        GitWorkspaceStatusResult discarded = workflow.discard();

        assertTrue(Files.isDirectory(repository.resolve(".git")));
        assertEquals("featx-dev/main", discarded.getBranch());
        assertEquals("print('original')\n", Files.readString(repository.resolve("feature.py")));
        assertTrue(Files.exists(generatedOutput));
        assertTrue(discarded.getCandidatePaths().isEmpty());
        assertTrue(discarded.getStagedPaths().isEmpty());
        assertTrue(discarded.getUnstagedPaths().isEmpty());
    }

    private CandidateCodeService prepareTwoPythonCandidates(Path repository) throws Exception {
        ProjectState.getInstance().setProjectPath(repository.toString(), "PYTHON");
        ProjectState.getInstance().setRepoId(52);
        CandidateCodeService candidateService = new CandidateCodeService();
        Map<String, String> modifications = new LinkedHashMap<>();
        modifications.put("first.py", "print('first changed')\n");
        modifications.put("second.py", "print('second changed')\n");
        ProjectState.getInstance().setModifications(modifications);
        candidateService.preparePythonCandidate("first.py", "first.py", modifications.get("first.py"));
        candidateService.preparePythonCandidate("second.py", "second.py", modifications.get("second.py"));
        return candidateService;
    }

    private TestRun completedEditRun() {
        ProjectState project = ProjectState.getInstance();
        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunContext context = registry.prepare(
                "modify-python",
                "updated feature",
                "original feature",
                "",
                "first.py\nsecond.py",
                AgentLanguage.EN,
                project.getSrcPath(),
                project.getProjectPath(),
                project.getRepoId(),
                7,
                null,
                java.util.List.of()
        );
        registry.claim(context.runId());
        registry.complete(context.runId(), ProjectState.getInstance().getModifications());
        return new TestRun(registry, context.runId());
    }

    private record TestRun(AgentRunRegistry registry, String runId) {
    }

    private void selectFeatureForEdit() {
        FeatureResult feature = new FeatureResult();
        feature.setFeatureId(7);
        ClusterState.getInstance().setCandidateFeature(feature);
        ClusterState.getInstance().setNewFeatureDescription("updated feature");
    }

    private void initializeRepository(Path repository) throws Exception {
        runGit(repository, "init", "-b", "main");
        runGit(repository, "config", "user.name", "FeatX Test");
        runGit(repository, "config", "user.email", "featx-test@localhost");
        Files.writeString(repository.resolve("first.py"), "print('first')\n");
        Files.writeString(repository.resolve("second.py"), "print('second')\n");
        Files.writeString(repository.resolve(".gitignore"), "/preprocess1/\n/delombok/\n/preprocess2/\n");
        runGit(repository, "add", "-A");
        runGit(repository, "commit", "-m", "baseline");
    }

    private String runGit(Path workingDirectory, String... arguments) throws IOException, InterruptedException {
        String[] command = new String[arguments.length + 1];
        command[0] = "git";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }
}
