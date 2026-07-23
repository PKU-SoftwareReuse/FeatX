CREATE TABLE IF NOT EXISTS feature_git_operations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_id VARCHAR(64) NOT NULL,
    repository_id INT NOT NULL,
    operation VARCHAR(16) NOT NULL,
    feature_id INT NULL,
    module_id INT NULL,
    commit_scope VARCHAR(24) NOT NULL,
    status VARCHAR(32) NOT NULL,
    base_commit VARCHAR(64) NOT NULL,
    commit_hash VARCHAR(64) NULL,
    compensation_commit_hash VARCHAR(64) NULL,
    error_message TEXT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_feature_git_operations_run_id (run_id),
    KEY idx_feature_git_operations_repository_status (repository_id, status)
);
