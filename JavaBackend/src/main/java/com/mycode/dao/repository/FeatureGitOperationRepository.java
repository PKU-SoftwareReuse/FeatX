package com.mycode.dao.repository;

import com.mycode.dao.FeatureGitOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FeatureGitOperationRepository extends JpaRepository<FeatureGitOperation, Long> {
    Optional<FeatureGitOperation> findByRunId(String runId);

    List<FeatureGitOperation> findByRepositoryIdAndStatusIn(
            Integer repositoryId,
            List<String> statuses
    );
}
