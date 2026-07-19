package cn.edu.pku.lixutian.service;

import cn.edu.pku.lixutian.config.ClusterState;
import cn.edu.pku.lixutian.dto.result.GitCommitResult;
import cn.edu.pku.lixutian.dto.result.GitWorkspaceStatusResult;
import cn.edu.pku.lixutian.service.code.AgentLanguage;
import com.github.javaparser.ParseException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

@Service
public class FeatureGitWorkflowService {
    private final RepositoryGitService repositoryGitService;
    private final CandidateCodeService candidateCodeService;
    private final CodeMapService codeMapService;

    public FeatureGitWorkflowService(
            RepositoryGitService repositoryGitService,
            CandidateCodeService candidateCodeService,
            CodeMapService codeMapService
    ) {
        this.repositoryGitService = repositoryGitService;
        this.candidateCodeService = candidateCodeService;
        this.codeMapService = codeMapService;
    }

    public GitWorkspaceStatusResult status() throws IOException, InterruptedException {
        return repositoryGitService.status();
    }

    public GitWorkspaceStatusResult stageCandidate(String key) throws IOException, InterruptedException {
        return repositoryGitService.stageCandidate(key);
    }

    public synchronized GitCommitResult commit(String operation, String requestedMessage)
            throws IOException, InterruptedException, ParseException {
        String normalizedOperation = normalizeOperation(operation);
        GitWorkspaceStatusResult before = repositoryGitService.status();
        if (before.getStagedPaths().isEmpty()) {
            throw new IllegalStateException("Confirm at least one file in the Diff Panel before committing.");
        }

        boolean complete = "COMPLETE".equals(before.getCommitScope());
        Integer featureId = null;
        if (complete) {
            featureId = confirmFeatureOperation(normalizedOperation);
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
        }

        GitCommitResult result = new GitCommitResult();
        result.setCommitHash(commitHash);
        result.setCommitScope(commitScope);
        result.setFeatureId(featureId);
        result.setStatus(repositoryGitService.status());
        return result;
    }

    public GitWorkspaceStatusResult discard() throws IOException, InterruptedException {
        return repositoryGitService.discardUncommittedChanges();
    }

    private Integer confirmFeatureOperation(String operation)
            throws ParseException, IOException, InterruptedException {
        ClusterState state = ClusterState.getInstance();
        AgentLanguage language = state.getAgentLanguage();
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
                if (state.getCandidateFeature() == null || state.getCandidateFeature().getFeatureId() == null) {
                    throw new IllegalStateException("No feature is selected for modification.");
                }
                yield codeMapService.modifyFeatureFromMemoryAndDatabase(
                        state.getCandidateFeature().getFeatureId(),
                        state.getNewFeatureDescription(),
                        language
                );
            }
            case "add" -> {
                if (state.getCandidateModuleId() == null) {
                    throw new IllegalStateException("No module is selected for the new feature.");
                }
                yield codeMapService.addFeatureFromMemoryAndDatabase(
                        state.getCandidateModuleId(),
                        state.getNewFeatureDescription(),
                        language
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
