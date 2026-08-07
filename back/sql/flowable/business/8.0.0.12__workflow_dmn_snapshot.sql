CREATE TABLE IF NOT EXISTS `wf_deploy_dmn_snapshot`
(
    `snapshot_id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'DMN 部署快照主键',
    `deploy_id`                VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Flowable 流程部署主键',
    `process_key`              VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '流程定义 key',
    `element_id`               VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'BusinessRuleTask 元素标识',
    `source_decision_id`       VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '设计阶段选择的 DMN 决策主键',
    `decision_key`             VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '冻结决策 key',
    `decision_version`         INT          NOT NULL COMMENT '冻结决策版本',
    `source_deployment_id`     VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源 DMN 部署主键',
    `resource_name`            VARCHAR(255) NOT NULL COMMENT '冻结 DMN 资源名',
    `resource_checksum`        CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源 XML SHA-256',
    `frozen_deployment_id`     VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '绑定流程部署的 DMN 子部署主键',
    `frozen_decision_id`       VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '冻结后的 DMN 决策主键',
    `snapshot_checksum`        CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '完整快照 SHA-256',
    `create_by`                VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '部署操作人正式用户主键',
    `create_time`              DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`snapshot_id`),
    UNIQUE KEY `uk_wf_deploy_dmn_element` (`deploy_id`, `process_key`, `element_id`),
    KEY `idx_wf_deploy_dmn_source` (`source_decision_id`),
    KEY `idx_wf_deploy_dmn_frozen` (`frozen_deployment_id`),
    CONSTRAINT `chk_wf_deploy_dmn_version` CHECK (`decision_version` > 0),
    CONSTRAINT `chk_wf_deploy_dmn_resource_checksum` CHECK (`resource_checksum` REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT `chk_wf_deploy_dmn_snapshot_checksum` CHECK (`snapshot_checksum` REGEXP '^[0-9a-f]{64}$')
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '流程部署冻结 DMN 决策快照';
