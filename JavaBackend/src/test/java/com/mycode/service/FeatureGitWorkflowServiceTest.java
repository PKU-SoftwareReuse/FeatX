package com.mycode.service;

import com.mycode.config.ClusterState;
import com.mycode.config.ProjectState;
import com.mycode.dto.result.FeatureResult;
import com.mycode.dto.result.GitCommitResult;
import com.mycode.dto.result.GitWorkspaceStatusResult;
import com.mycode.service.code.AgentRunContext;
import com.mycode.service.code.AgentRunMode;
import com.mycode.service.code.AgentRunRegistry;
import com.mycode.service.code.AgentLanguage;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
    void partialCommitAppliesStagedFileAndDiscardsRemainingCandidates(@TempDir Path repository) throws Exception {
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
                runRegistry,
                mock(FeatureOperationJournalService.class)
        );
        selectFeatureForEdit();

        GitWorkspaceStatusResult stagedFirst = workflow.stageCandidate("first.py", runId);
        assertEquals("PARTIAL", stagedFirst.getCommitScope());
        assertEquals(java.util.List.of("first.py"), stagedFirst.getStagedPaths());
        assertEquals(java.util.List.of("second.py"), stagedFirst.getUnstagedCandidatePaths());
        when(codeMapService.modifyFeatureFromMemoryAndDatabase(7, "updated feature", AgentLanguage.EN))
                .thenReturn(7);

        GitCommitResult partial = workflow.commit("edit", null, runId);
        assertEquals("PARTIAL", partial.getCommitScope());
        assertEquals(7, partial.getFeatureId());
        assertEquals("print('first changed')\n", Files.readString(repository.resolve("first.py")));
        assertEquals("print('second')\n", Files.readString(repository.resolve("second.py")));
        assertTrue(partial.getStatus().getStagedPaths().isEmpty());
        assertTrue(partial.getStatus().getPendingCandidatePaths().isEmpty());
        assertTrue(ProjectState.getInstance().getModifications().isEmpty());
        assertFalse(runRegistry.hasActiveOperation(52));
        assertEquals("2", runGit(repository, "rev-list", "--count", "HEAD").trim());
        verify(codeMapService).modifyFeatureFromMemoryAndDatabase(
                7,
                "updated feature",
                AgentLanguage.EN
        );
        verify(codeMapService).verifyFeatureOperation("edit", 7, 7);
    }

    @Test
    void completeCommitAppliesEveryStagedCandidate(@TempDir Path repository) throws Exception {
        initializeRepository(repository);
        CandidateCodeService candidateService = prepareTwoPythonCandidates(repository);
        RepositoryGitService gitService = new RepositoryGitService(candidateService);
        CodeMapService codeMapService = mock(CodeMapService.class);
        TestRun run = completedEditRun();
        AgentRunRegistry runRegistry = run.registry();
        FeatureGitWorkflowService workflow = new FeatureGitWorkflowService(
                gitService,
                candidateService,
                codeMapService,
                runRegistry,
                mock(FeatureOperationJournalService.class)
        );
        selectFeatureForEdit();
        when(codeMapService.modifyFeatureFromMemoryAndDatabase(7, "updated feature", AgentLanguage.EN))
                .thenReturn(7);

        workflow.stageCandidate("first.py", run.runId());
        GitWorkspaceStatusResult stagedAll = workflow.stageCandidate("second.py", run.runId());
        assertEquals("COMPLETE", stagedAll.getCommitScope());

        GitCommitResult complete = workflow.commit("edit", null, run.runId());

        assertEquals("COMPLETE", complete.getCommitScope());
        assertEquals("print('first changed')\n", Files.readString(repository.resolve("first.py")));
        assertEquals("print('second changed')\n", Files.readString(repository.resolve("second.py")));
        assertTrue(complete.getStatus().getPendingCandidatePaths().isEmpty());
        assertEquals("2", runGit(repository, "rev-list", "--count", "HEAD").trim());
    }

    @Test
    void partialDeleteCommitFinishesTheOperationAndDiscardsUnstagedCandidates(@TempDir Path repository)
            throws Exception {
        initializeRepository(repository);
        CandidateCodeService candidateService = prepareTwoPythonCandidates(repository);
        RepositoryGitService gitService = new RepositoryGitService(candidateService);
        CodeMapService codeMapService = mock(CodeMapService.class);
        TestRun run = completedDeleteRun();
        FeatureGitWorkflowService workflow = new FeatureGitWorkflowService(
                gitService,
                candidateService,
                codeMapService,
                run.registry(),
                mock(FeatureOperationJournalService.class)
        );
        selectFeature(99);

        workflow.stageCandidate("first.py", run.runId());
        GitCommitResult result = workflow.commit("delete", null, run.runId());

        assertEquals("PARTIAL", result.getCommitScope());
        assertEquals(7, result.getFeatureId());
        assertEquals("print('first changed')\n", Files.readString(repository.resolve("first.py")));
        assertEquals("print('second')\n", Files.readString(repository.resolve("second.py")));
        assertTrue(result.getStatus().getStagedPaths().isEmpty());
        assertTrue(result.getStatus().getPendingCandidatePaths().isEmpty());
        assertTrue(ProjectState.getInstance().getModifications().isEmpty());
        assertFalse(run.registry().hasActiveOperation(52));
        assertEquals("2", runGit(repository, "rev-list", "--count", "HEAD").trim());
        verify(codeMapService).deleteFeatureFromMemoryAndDatabase(7);
        verify(codeMapService).verifyFeatureOperation("delete", 7, 7);
    }

    @Test
    void metadataFailureRevertsTheGitCommitAndKeepsTheRunRetryable(@TempDir Path repository) throws Exception {
        initializeRepository(repository);
        CandidateCodeService candidateService = prepareTwoPythonCandidates(repository);
        RepositoryGitService gitService = new RepositoryGitService(candidateService);
        CodeMapService codeMapService = mock(CodeMapService.class);
        TestRun run = completedEditRun();
        FeatureGitWorkflowService workflow = new FeatureGitWorkflowService(
                gitService,
                candidateService,
                codeMapService,
                run.registry(),
                mock(FeatureOperationJournalService.class)
        );
        when(codeMapService.modifyFeatureFromMemoryAndDatabase(7, "updated feature", AgentLanguage.EN))
                .thenThrow(new IllegalStateException("database unavailable"));

        workflow.stageCandidate("first.py", run.runId());
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> workflow.commit("edit", null, run.runId())
        );

        assertTrue(failure.getMessage().contains("database unavailable"));
        assertEquals("print('first')\n", Files.readString(repository.resolve("first.py")));
        assertEquals("print('second')\n", Files.readString(repository.resolve("second.py")));
        assertEquals("3", runGit(repository, "rev-list", "--count", "HEAD").trim());
        assertTrue(runGit(repository, "log", "-1", "--pretty=%s").startsWith("Revert"));
        assertTrue(run.registry().hasActiveOperation(52));
        assertEquals(Set.of("first.py", "second.py"), ProjectState.getInstance().getModifications().keySet());
    }

    @Test
    void metadataOnlyDeleteCreatesAnAuditableEmptyCommit(@TempDir Path repository) throws Exception {
        initializeRepository(repository);
        ProjectState.getInstance().setProjectPath(repository.toString(), "PYTHON");
        ProjectState.getInstance().setRepoId(52);
        CandidateCodeService candidateService = new CandidateCodeService();
        CodeMapService codeMapService = mock(CodeMapService.class);
        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunContext context = registry.prepare(
                AgentRunMode.PYTHON_DELETE.id(),
                "",
                "stale feature metadata",
                "",
                "first.py\nsecond.py",
                AgentLanguage.EN,
                ProjectState.getInstance().getSrcPath(),
                ProjectState.getInstance().getProjectPath(),
                52,
                7,
                null,
                java.util.List.of()
        );
        registry.completePrepared(context.runId(), Map.of());
        FeatureGitWorkflowService workflow = new FeatureGitWorkflowService(
                new RepositoryGitService(candidateService),
                candidateService,
                codeMapService,
                registry,
                mock(FeatureOperationJournalService.class)
        );

        GitCommitResult result = workflow.commit("delete", null, context.runId());

        assertEquals("METADATA_ONLY", result.getCommitScope());
        assertEquals("2", runGit(repository, "rev-list", "--count", "HEAD").trim());
        assertEquals(
                "FeatX: reconcile feature deletion metadata",
                runGit(repository, "log", "-1", "--pretty=%s").trim()
        );
        verify(codeMapService).deleteFeatureMetadataOnly(7);
        verify(codeMapService).verifyFeatureOperation("delete", 7, 7);
        assertFalse(registry.hasActiveOperation(52));
    }

    @Test
    void stagedFileCanBeEditedRestoredAndRestaged(@TempDir Path repository) throws Exception {
        initializeRepository(repository);
        CandidateCodeService candidateService = prepareTwoPythonCandidates(repository);
        RepositoryGitService gitService = new RepositoryGitService(candidateService);

        gitService.stageCandidate("first.py");
        assertEquals(Set.of("first.py"), candidateService.stagedModificationKeys());

        var edited = gitService.updateCandidate(
                "first.py",
                "edit",
                "print('edited after stage')\n"
        );
        GitWorkspaceStatusResult editedStatus = gitService.status();

        assertTrue(edited.isStaged());
        assertEquals("print('first changed')\n", edited.getStagedContent());
        assertEquals(java.util.List.of("first.py"), editedStatus.getStagedPaths());
        assertEquals(java.util.List.of("first.py"), editedStatus.getUnstagedPaths());
        assertTrue(candidateService.stagedModificationKeys().isEmpty());
        assertTrue(runGit(repository, "diff", "--cached", "--", "first.py")
                .contains("+print('first changed')"));

        gitService.updateCandidate("first.py", "edit", "print('first changed')\n");
        GitWorkspaceStatusResult restoredStatus = gitService.status();
        assertTrue(restoredStatus.getUnstagedPaths().isEmpty());
        assertEquals(Set.of("first.py"), candidateService.stagedModificationKeys());

        gitService.updateCandidate("first.py", "edit", "print('restaged latest')\n");
        GitWorkspaceStatusResult restagedStatus = gitService.stageCandidate("first.py");
        assertTrue(restagedStatus.getUnstagedPaths().isEmpty());
        assertEquals(Set.of("first.py"), candidateService.stagedModificationKeys());
        String cachedDiff = runGit(repository, "diff", "--cached", "--", "first.py");
        assertTrue(cachedDiff.contains("+print('restaged latest')"));
        assertFalse(cachedDiff.contains("+print('first changed')"));
    }

    @Test
    void unstagingKeepsTheCandidateContentInTheWorktree(@TempDir Path repository) throws Exception {
        initializeRepository(repository);
        CandidateCodeService candidateService = prepareTwoPythonCandidates(repository);
        RepositoryGitService gitService = new RepositoryGitService(candidateService);

        gitService.stageCandidate("first.py");
        GitWorkspaceStatusResult unstaged = gitService.unstageCandidate("first.py");

        assertTrue(unstaged.getStagedPaths().isEmpty());
        assertEquals(java.util.List.of("first.py"), unstaged.getUnstagedPaths());
        assertEquals("print('first changed')\n", Files.readString(repository.resolve("first.py")));
        assertTrue(candidateService.stagedModificationKeys().isEmpty());
        assertFalse(candidateService.existingCandidate("first.py").orElseThrow().isStaged());
    }

    @Test
    void revertsOneCandidateWithoutEndingTheOperation(@TempDir Path repository) throws Exception {
        initializeRepository(repository);
        CandidateCodeService candidateService = prepareTwoPythonCandidates(repository);
        RepositoryGitService gitService = new RepositoryGitService(candidateService);
        TestRun run = completedEditRun();
        FeatureGitWorkflowService workflow = new FeatureGitWorkflowService(
                gitService,
                candidateService,
                mock(CodeMapService.class),
                run.registry(),
                mock(FeatureOperationJournalService.class)
        );

        workflow.stageCandidate("first.py", run.runId());
        GitWorkspaceStatusResult reverted = workflow.revertCandidate("first.py", run.runId());

        assertEquals("print('first')\n", Files.readString(repository.resolve("first.py")));
        assertEquals(java.util.List.of("second.py"), reverted.getPendingCandidatePaths());
        assertTrue(reverted.getStagedPaths().isEmpty());
        assertEquals(Set.of("second.py"), run.registry().modifications(run.runId()).keySet());
        assertTrue(run.registry().hasActiveOperation(52));

        var restoredCandidate = candidateService.existingCandidate("first.py").orElseThrow();
        assertEquals("print('first')\n", restoredCandidate.getModifiedContent());
        assertTrue(restoredCandidate.getDiff().isBlank());

        gitService.updateCandidate("first.py", "edit", "print('edited again')\n");
        assertEquals(
                Set.of("first.py", "second.py"),
                candidateService.pendingModificationKeys()
        );
    }

    @Test
    void discardRestoresAllCandidatesAndKeepsIgnoredPreprocessOutput(@TempDir Path repository) throws Exception {
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
                runRegistry,
                mock(FeatureOperationJournalService.class)
        );
        selectFeatureForEdit();

        workflow.stageCandidate("first.py", runId);
        Files.writeString(repository.resolve("notes.txt"), "untracked\n");
        Path ignoredOutput = repository.resolve("preprocess1/report.csv");
        Files.createDirectories(ignoredOutput.getParent());
        Files.writeString(ignoredOutput, "generated\n");

        GitWorkspaceStatusResult discarded = workflow.discard();

        assertEquals("print('first')\n", Files.readString(repository.resolve("first.py")));
        assertEquals("print('second')\n", Files.readString(repository.resolve("second.py")));
        assertFalse(Files.exists(repository.resolve("notes.txt")));
        assertTrue(Files.exists(ignoredOutput));
        assertTrue(discarded.getStagedPaths().isEmpty());
        assertTrue(discarded.getUnstagedPaths().isEmpty());
        assertTrue(discarded.getUntrackedPaths().isEmpty());
        assertEquals("1", runGit(repository, "rev-list", "--count", "HEAD").trim());
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
                new AgentRunRegistry(),
                mock(FeatureOperationJournalService.class)
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
                AgentRunMode.PYTHON_MODIFY.id(),
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

    private TestRun completedDeleteRun() {
        ProjectState project = ProjectState.getInstance();
        AgentRunRegistry registry = new AgentRunRegistry();
        AgentRunContext context = registry.prepare(
                AgentRunMode.PYTHON_DELETE.id(),
                "",
                "feature to delete",
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
        registry.completePrepared(context.runId(), ProjectState.getInstance().getModifications());
        return new TestRun(registry, context.runId());
    }

    private record TestRun(AgentRunRegistry registry, String runId) {
    }

    private void selectFeatureForEdit() {
        selectFeature(7);
    }

    private void selectFeature(Integer featureId) {
        FeatureResult feature = new FeatureResult();
        feature.setFeatureId(featureId);
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
