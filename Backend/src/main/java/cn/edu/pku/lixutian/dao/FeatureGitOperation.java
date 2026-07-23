package cn.edu.pku.lixutian.dao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "feature_git_operations")
@Getter
@Setter
public class FeatureGitOperation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, unique = true, length = 64)
    private String runId;

    @Column(name = "repository_id", nullable = false)
    private Integer repositoryId;

    @Column(nullable = false, length = 16)
    private String operation;

    @Column(name = "feature_id")
    private Integer featureId;

    @Column(name = "module_id")
    private Integer moduleId;

    @Column(name = "commit_scope", nullable = false, length = 24)
    private String commitScope;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "base_commit", nullable = false, length = 64)
    private String baseCommit;

    @Column(name = "commit_hash", length = 64)
    private String commitHash;

    @Column(name = "compensation_commit_hash", length = 64)
    private String compensationCommitHash;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
