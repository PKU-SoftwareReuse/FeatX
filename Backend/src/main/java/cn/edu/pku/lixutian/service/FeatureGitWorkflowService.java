package cn.edu.pku.lixutian.service;

import cn.edu.pku.lixutian.config.ClusterState;
import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.dto.result.GitCommitResult;
import cn.edu.pku.lixutian.dto.result.GitWorkspaceStatusResult;
import cn.edu.pku.lixutian.service.code.AgentRunContext;
import cn.edu.pku.lixutian.service.code.AgentRunRegistry;
import com.github.javaparser.ParseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FeatureGitWorkflowService {
    private final RepositoryGitService repositoryGitService;
    private final CandidateCodeService candidateCodeService;
    private final CodeMapService codeMapService;
    private final AgentRunRegistry agentRunRegistry;
    private final Map<Integer, Object> commitLocks = new ConcurrentHashMap<>();

    public FeatureGitWorkflowService(
            RepositoryGitService repositoryGitService,
            CandidateCodeService candidateCodeService,
            CodeMapService codeMapService,
            AgentRunRegistry agentRunRegistry
    ) {
        this.repositoryGitService = repositoryGitService;
        this.candidateCodeService = candidateCodeService;
        this.codeMapService = codeMapService;
        this.agentRunRegistry = agentRunRegistry;
    }

    public GitWorkspaceStatusResult status() throws IOException, InterruptedException {
        return repositoryGitService.status();
    }

    public GitWorkspaceStatusResult stageCandidate(String key, String runId) throws IOException, InterruptedException {
        agentRunRegistry.requireCompletedIfActive(runId);
        return repositoryGitService.stageCandidate(key);
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
        GitWorkspaceStatusResult before = repositoryGitService.status();
        if (before.getStagedPaths().isEmpty()) {
            throw new IllegalStateException("Confirm at least one file in the Diff Panel before committing.");
        }

        boolean complete = "COMPLETE".equals(before.getCommitScope());
        Integer featureId = null;
        if (complete) {
            candidateCodeService.validateCompleteCandidateSet();
            featureId = confirmFeatureOperation(normalizedOperation, agentRun);
            repositoryGitService.stagePaths(candidateCodeService.allCandidateProjectPaths());
        }

        GitWorkspaceStatusResult readyToCommit = repositoryGitService.status();
        List<String> committedPaths = readyToCommit.getStagedPaths();
        if (committedPaths.isEmpty()) {
            throw new IllegalStateException("There are no staged changes to commit.");
        }
        String commitScope = complete ? "COMPLETE" : "PARTIAL";
        String commitHash = repositoryGitService.commit(defaultCommitMessage(
                requestedMessage,
                normalizedOperation,
                commitScope
        ));
        candidateCodeService.markCommittedPaths(committedPaths);

        if (complete) {
            candidateCodeService.discardCandidateState();
            agentRunRegistry.clear();
        }

        GitCommitResult result = new GitCommitResult();
        result.setCommitHash(commitHash);
        result.setCommitScope(commitScope);
        result.setFeatureId(featureId);
        result.setStatus(repositoryGitService.status());
        return result;
    }

    public GitWorkspaceStatusResult discard() throws IOException, InterruptedException {
        GitWorkspaceStatusResult result = repositoryGitService.discardUncommittedChanges();
        agentRunRegistry.clear();
        return result;
    }

    private Integer confirmFeatureOperation(String operation, AgentRunContext agentRun)
            throws ParseException, IOException, InterruptedException {
        ClusterState state = ClusterState.getInstance();
        return switch (operation) {
            case "delete" -> {
                if (state.getCandidateFeature() == null || state.getCandidateFeature().getFeatureId() == null) {
                    throw new IllegalStateException("No feature is selected for deletion.");
                }
                Integer deletedFeatureId = state.getCandidateFeature().getFeatureId();
                codeMapService.deleteFeatureFromMemoryAndDatabase(deletedFeatureId);
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
                : "FeatX: apply " + action;
    }
}
