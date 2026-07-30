ALTER TABLE project_info
    ADD COLUMN archived BIT(1) NOT NULL DEFAULT 0 AFTER summary_flag;
