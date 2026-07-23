package cn.edu.pku.lixutian.service;

import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.dto.result.GitCommitResult;
import cn.edu.pku.lixutian.dto.result.GitWorkspaceStatusResult;
import cn.edu.pku.lixutian.service.code.AgentRunContext;
import cn.edu.pku.lixutian.service.code.AgentRunRegistry;
import com.github.javaparser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FeatureGitWorkflowService {
    private static final Logger logger = LoggerFactory.getLogger(FeatureGitWorkflowService.class);

    private final RepositoryGitService repositoryGitService;
    private final CandidateCodeService candidateCodeService;
    private final CodeMapService codeMapService;
    private final AgentRunRegistry agentRunRegistry;
    private final FeatureOperationJournalService journalService;
    private final Map<Integer, Object> commitLocks = new ConcurrentHashMap<>();

    public FeatureGitWorkflowService(
            RepositoryGitService repositoryGitService,
            CandidateCodeService candidateCodeService,
            CodeMapService codeMapService,
            AgentRunRegistry agentRunRegistry,
            FeatureOperationJournalService journalService
    ) {
        this.repositoryGitService = repositoryGitService;
        this.candidateCodeService = candidateCodeService;
        this.codeMapService = codeMapService;
        this.agentRunRegistry = agentRunRegistry;
        this.journalService = journalService;
    }

    public GitWorkspaceStatusResult status() throws IOException, InterruptedException {
        return repositoryGitService.status();
    }

    public GitWorkspaceStatusResult stageCandidate(String key, String runId) throws IOException, InterruptedException {
        agentRunRegistry.requireCompletedIfActive(runId);
        return repositoryGitService.stageCandidate(key);
    }

    public GitWorkspaceStatusResult unstageCandidate(String key, String runId) throws IOException, InterruptedException {
        agentRunRegistry.requireCompletedIfActive(runId);
        return repositoryGitService.unstageCandidate(key);
    }

    public GitWorkspaceStatusResult revertCandidate(String key, String runId) throws IOException, InterruptedException {
        agentRunRegistry.requireCompletedIfActive(runId);
        GitWorkspaceStatusResult result = repositoryGitService.revertCandidate(key);
        agentRunRegistry.replaceCompletedModifications(
                runId,
                new LinkedHashMap<>(ProjectState.getInstance().getModifications())
        );
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public GitCommitResult commit(String operation, String requestedMessage, String runId)
            throws IOException, InterruptedException, ParseException {
        Integer repositoryId = ProjectState.getInstance().getRepoId();
        if (repositoryId == null) {
            throw new IllegalStateException("No project is currently selected.");
        }
        synchronized (commitLocks.computeIfAbsent(repositoryId, ignored -> new Object())) {
            return commitLocked(operation, requestedMessage, runId);
        }
    }

    private GitCommitResult commitLocked(String operation, String requestedMessage, String runId)
            throws IOException, InterruptedException, ParseException {
        String normalizedOperation = normalizeOperation(operation);
        AgentRunContext agentRun = agentRunRegistry.requireCompletedOperation(runId, normalizedOperation);
        return commitCandidateChanges(normalizedOperation, requestedMessage, runId, agentRun);
    }

    private GitCommitResult commitCandidateChanges(
            String operation,
            String requestedMessage,
            String runId,
            AgentRunContext agentRun
    ) throws IOException, InterruptedException, ParseException {
        GitWorkspaceStatusResult before = repositoryGitService.status();
        Set<String> stagedKeys = candidateCodeService.candidateKeysForProjectPaths(before.getStagedPaths());
        boolean metadataOnly = "delete".equals(operation)
                && stagedKeys.isEmpty()
                && before.getStagedPaths().isEmpty()
                && agentRunRegistry.modifications(runId).isEmpty()
                && agentRunRegistry.metadataOnlyEligible(runId);
        if (stagedKeys.isEmpty() && !metadataOnly) {
            throw new IllegalStateException("Confirm at least one file in the Diff Panel before committing.");
        }

        boolean complete = "COMPLETE".equals(before.getCommitScope());
        String commitScope = metadataOnly ? "METADATA_ONLY" : complete ? "COMPLETE" : "PARTIAL";

        Map<String, String> allModifications = new LinkedHashMap<>(ProjectState.getInstance().getModifications());
        List<String> candidatePaths = candidateCodeService.projectPathsForCandidateKeys(stagedKeys);
        Map<String, String> stagedContents = repositoryGitService.readIndexContents(candidatePaths);
        String baseCommit = repositoryGitService.headCommit();
        journalService.begin(agentRun, operation, commitScope, baseCommit);

        Integer featureId;
        String commitHash = null;
        boolean committed = false;
        try {
            if (!metadataOnly) {
                Map<String, String> selectedModifications = candidateCodeService.adoptStagedCandidateContents(
                        stagedKeys,
                        stagedContents
                );
                if (selectedModifications.isEmpty()) {
                    throw new IllegalStateException("The staged files are not part of the active feature operation.");
                }
                candidateCodeService.validateCandidateSet(stagedKeys);
                agentRunRegistry.replaceCompletedModifications(runId, selectedModifications);
            }
            for (String stagedKey : stagedKeys) {
                candidateCodeService.materializeCandidate(stagedKey);
            }
            if (!metadataOnly) {
                repositoryGitService.replaceStagedPaths(candidatePaths);
            }
            GitWorkspaceStatusResult readyToCommit = repositoryGitService.status();
            if (!metadataOnly && readyToCommit.getStagedPaths().isEmpty()) {
                throw new IllegalStateException("There are no staged changes to commit.");
            }
            commitHash = repositoryGitService.commit(defaultCommitMessage(
                    requestedMessage,
                    operation,
                    commitScope
            ), metadataOnly);
            committed = true;
            journalService.markGitCommitted(runId, commitHash);
            featureId = confirmFeatureOperation(operation, agentRun, metadataOnly);
            codeMapService.verifyFeatureOperation(operation, agentRun.featureId(), featureId);
            if (!commitHash.equals(repositoryGitService.headCommit())) {
                throw new IllegalStateException("Repository HEAD changed while feature metadata was being updated.");
            }
            markJournalCompletedAfterCommit(runId);
            repositoryGitService.discardUncommittedChanges();
            agentRunRegistry.clear();
        } catch (IOException | InterruptedException | ParseException | RuntimeException exception) {
            RuntimeException compensationFailure = null;
            String compensationCommit = null;
            if (committed) {
                try {
                    repositoryGitService.restoreRepositoryToHead();
                    compensationCommit = repositoryGitService.revertCommit(commitHash);
                    repositoryGitService.restoreRepositoryToHead();
                } catch (IOException | InterruptedException | RuntimeException failure) {
                    compensationFailure = new IllegalStateException(
                            "Feature metadata update failed after Git commit " + commitHash
                                    + ", and automatic Git compensation also failed. Manual reconciliation is required.",
                            failure
                    );
                }
            }
            codeMapService.invalidateRepository(agentRun.repositoryId());
            try {
                if (!committed) {
                    journalService.markFailed(runId, exception);
                } else if (compensationFailure == null) {
                    journalService.markCompensated(runId, compensationCommit, exception);
                } else {
                    journalService.markReconciliationRequired(runId, compensationFailure);
                }
            } catch (RuntimeException journalFailure) {
                logger.error("Could not update feature operation journal for run {}", runId, journalFailure);
            }
            if (!committed || compensationFailure == null) {
                candidateCodeService.restoreCandidateModifications(allModifications);
                agentRunRegistry.replaceCompletedModifications(runId, allModifications);
            }
            if (compensationFailure != null) {
                compensationFailure.addSuppressed(exception);
                throw compensationFailure;
            }
            throw exception;
        }

        GitCommitResult result = new GitCommitResult();
        result.setCommitHash(commitHash);
        result.setCommitScope(commitScope);
        result.setFeatureId(featureId);
        result.setStatus(repositoryGitService.status());
        return result;
    }

    private void markJournalCompletedAfterCommit(String runId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            journalService.markCompleted(runId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    journalService.markCompleted(runId);
                } catch (RuntimeException failure) {
                    logger.error("Could not mark committed feature operation {} as complete", runId, failure);
                }
            }
        });
    }

    public GitWorkspaceStatusResult discard() throws IOException, InterruptedException {
        GitWorkspaceStatusResult result = repositoryGitService.discardUncommittedChanges();
        agentRunRegistry.clear();
        return result;
    }

    private Integer confirmFeatureOperation(
            String operation,
            AgentRunContext agentRun,
            boolean metadataOnly
    )
            throws ParseException, IOException, InterruptedException {
        return switch (operation) {
            case "delete" -> {
                if (agentRun.featureId() == null) {
                    throw new IllegalStateException("The completed delete run has no feature id.");
                }
                Integer deletedFeatureId = agentRun.featureId();
                if (metadataOnly) {
                    codeMapService.deleteFeatureMetadataOnly(deletedFeatureId);
                } else {
                    codeMapService.deleteFeatureFromMemoryAndDatabase(deletedFeatureId);
                }
                yield deletedFeatureId;
            }
            case "edit" -> {
                yield codeMapService.modifyFeatureFromMemoryAndDatabase(
                        agentRun.featureId(),
                        agentRun.newRequest(),
                        agentRun.language()
                );
            }
            case "add" -> {
                yield codeMapService.addFeatureFromMemoryAndDatabase(
                        agentRun.moduleId(),
                        agentRun.newRequest(),
                        agentRun.language()
                );
            }
            default -> throw new IllegalArgumentException("Unsupported feature operation: " + operation);
        };
    }

    private String normalizeOperation(String operation) {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("Feature operation is required.");
        }
        String normalized = operation.trim().toLowerCase(Locale.ROOT);
        if (!List.of("delete", "edit", "add").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported feature operation: " + operation);
        }
        return normalized;
    }

    private String defaultCommitMessage(String requestedMessage, String operation, String scope) {
        if (requestedMessage != null && !requestedMessage.isBlank()) {
            return requestedMessage;
        }
        String action = switch (operation) {
            case "delete" -> "feature deletion";
            case "edit" -> "feature modification";
            case "add" -> "feature addition";
            default -> "feature change";
        };
        return "PARTIAL".equals(scope)
                ? "FeatX: commit part of " + action
                : "METADATA_ONLY".equals(scope)
                ? "FeatX: reconcile " + action + " metadata"
                : "FeatX: apply " + action;
    }
}
