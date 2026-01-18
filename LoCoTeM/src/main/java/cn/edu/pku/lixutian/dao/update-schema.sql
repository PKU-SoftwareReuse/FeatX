CREATE TABLE code_map
(
    id          INT AUTO_INCREMENT NOT NULL,
    feature     INT                NULL,
    method_name VARCHAR(255)       NULL,
    CONSTRAINT pk_code_map PRIMARY KEY (id)
);


CREATE TABLE features
(
    id              INT AUTO_INCREMENT NOT NULL,
    module          INT                NULL,
    feature_id      INT                NULL,
    feature_desc    VARCHAR(255)       NULL,
    feature_desc_cn VARCHAR(255)       NULL,
    CONSTRAINT pk_features PRIMARY KEY (id)
);


CREATE TABLE graph_edge
(
    id   INT AUTO_INCREMENT NOT NULL,
    repo INT                NULL,
    src  VARCHAR(255)       NULL,
    dest VARCHAR(255)       NULL,
    CONSTRAINT pk_graph_edge PRIMARY KEY (id)
);


CREATE TABLE modules
(
    id             INT AUTO_INCREMENT NOT NULL,
    repo           INT                NULL,
    cluster_id     INT                NULL,
    module_desc    VARCHAR(255)       NULL,
    module_desc_cn VARCHAR(255)       NULL,
    CONSTRAINT pk_modules PRIMARY KEY (id)
);


CREATE TABLE project_info
(
    id            INT AUTO_INCREMENT NOT NULL,
    repo_name     VARCHAR(255)       NULL,
    `description` VARCHAR(255)       NULL,
    git_link      VARCHAR(255)       NULL,
    loc           INT                NULL,
    noc           INT                NULL,
    nom           INT                NULL,
    nof           INT                NULL,
    summary_flag  BIT(1)             NULL,
    CONSTRAINT pk_project_info PRIMARY KEY (id)
);

ALTER TABLE features
    MODIFY COLUMN feature_desc TEXT;

ALTER TABLE code_map
    ADD CONSTRAINT FK_CODE_MAP_ON_FEATURE FOREIGN KEY (feature) REFERENCES features (id);

ALTER TABLE features
    ADD CONSTRAINT FK_FEATURES_ON_MODULE FOREIGN KEY (module) REFERENCES modules (id);

ALTER TABLE modules
    ADD CONSTRAINT FK_MODULES_ON_REPO FOREIGN KEY (repo) REFERENCES project_info (id);

ALTER TABLE graph_edge
    ADD CONSTRAINT FK_GRAPH_EDGE_ON_REPO FOREIGN KEY (repo) REFERENCES project_info (id);

SET FOREIGN_KEY_CHECKS=0;
TRUNCATE TABLE code_map;
TRUNCATE TABLE graph_edge;
TRUNCATE TABLE features;
TRUNCATE TABLE modules;
SET FOREIGN_KEY_CHECKS=1;

use lotm;