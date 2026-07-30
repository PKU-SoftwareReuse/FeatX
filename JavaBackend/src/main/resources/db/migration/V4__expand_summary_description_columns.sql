ALTER TABLE modules
    MODIFY COLUMN module_desc TEXT NULL,
    MODIFY COLUMN module_desc_cn TEXT NULL;

ALTER TABLE features
    MODIFY COLUMN feature_desc TEXT NULL,
    MODIFY COLUMN feature_desc_cn TEXT NULL;
