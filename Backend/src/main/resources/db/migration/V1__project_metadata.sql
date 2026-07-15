ALTER TABLE project_info
    MODIFY COLUMN description TEXT NULL,
    MODIFY COLUMN git_link VARCHAR(2048) NULL,
    ADD COLUMN description_cn TEXT NULL AFTER description,
    ADD COLUMN project_type VARCHAR(16) NULL AFTER description_cn;

UPDATE project_info
SET project_type = CASE
    WHEN LOWER(COALESCE(description, '')) LIKE '%[python]%'
        OR LOWER(COALESCE(description, '')) LIKE '%python repo%'
        THEN 'PYTHON'
    ELSE 'JAVA'
END
WHERE project_type IS NULL;

ALTER TABLE project_info
    MODIFY COLUMN project_type VARCHAR(16) NOT NULL;
