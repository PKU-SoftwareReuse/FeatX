package cn.edu.pku.lixutian.service;

import cn.edu.pku.lixutian.dao.FeatureGitOperation;
import cn.edu.pku.lixutian.dao.repository.FeatureGitOperationRepository;
import cn.edu.pku.lixutian.service.code.AgentRunContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class FeatureOperationJournalService {
    public static final String STARTED = "STARTED";
    public static final String GIT_COMMITTED = "GIT_COMMITTED";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String COMPENSATED = "COMPENSATED";
    public static final String RECONCILIATION_REQUIRED = "RECONCILIATION_REQUIRED";

    private final FeatureGitOperationRepository repository;

    public FeatureOperationJournalService(FeatureGitOperationRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void begin(AgentRunContext run, String operation, String scope, String baseCommit) {
        FeatureGitOperation entry = repository.findByRunId(run.runId()).orElseGet(FeatureGitOperation::new);
        Instant now = Instant.now();
        entry.setRunId(run.runId());
        entry.setRepositoryId(run.repositoryId());
        entry.setOperation(operation);
        entry.setFeatureId(run.featureId());
        entry.setModuleId(run.moduleId());
        entry.setCommitScope(scope);
        entry.setStatus(STARTED);
        entry.setBaseCommit(baseCommit);
        entry.setCommitHash(null);
        entry.setCompensationCommitHash(null);
        entry.setErrorMessage(null);
        if (entry.getCreatedAt() == null) {
            entry.setCreatedAt(now);
        }
        entry.setUpdatedAt(now);
        repository.saveAndFlush(entry);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markGitCommitted(String runId, String commitHash) {
        update(runId, GIT_COMMITTED, commitHash, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(String runId) {
        update(runId, COMPLETED, null, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String runId, Throwable failure) {
        update(runId, FAILED, null, null, message(failure));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompensated(String runId, String compensationCommit, Throwable failure) {
        update(runId, COMPENSATED, null, compensationCommit, message(failure));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReconciliationRequired(String runId, Throwable failure) {
        update(runId, RECONCILIATION_REQUIRED, null, null, message(failure));
    }

    @Transactional(readOnly = true)
    public List<FeatureGitOperation> unresolved(Integer repositoryId) {
        return repository.findByRepositoryIdAndStatusIn(
                repositoryId,
                List.of(GIT_COMMITTED, RECONCILIATION_REQUIRED)
        );
    }

    private void update(
            String runId,
            String status,
            String commitHash,
            String compensationCommit,
            String error
    ) {
        FeatureGitOperation entry = repository.findByRunId(runId)
                .orElseThrow(() -> new IllegalStateException("Feature operation journal is missing for run " + runId));
        entry.setStatus(status);
        if (commitHash != null) {
            entry.setCommitHash(commitHash);
        }
        if (compensationCommit != null) {
            entry.setCompensationCommitHash(compensationCommit);
        }
        if (error != null) {
            entry.setErrorMessage(error);
        }
        entry.setUpdatedAt(Instant.now());
        repository.saveAndFlush(entry);
    }

    private String message(Throwable failure) {
        if (failure == null) {
            return null;
        }
        String value = failure.getMessage();
        if (value == null || value.isBlank()) {
            value = failure.getClass().getSimpleName();
        }
        return value.length() <= 4000 ? value : value.substring(0, 4000);
    }
}
