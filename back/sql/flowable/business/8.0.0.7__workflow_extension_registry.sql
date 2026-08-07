-- BPMN 受控扩展目录、不可变版本和部署执行快照。
-- 本迁移只创建缺失对象并幂等写入服务端内置处理器目录，不覆盖已有扩展数据。
CREATE TABLE IF NOT EXISTS `wf_bpmn_extension`
(
    `extension_id`   BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'BPMN 扩展目录主键',
    `extension_key`  VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '设计器和作者 BPMN 使用的稳定扩展键',
    `extension_name` VARCHAR(128) NOT NULL COMMENT '扩展用户可见名称',
    `extension_type` VARCHAR(16)  NOT NULL COMMENT '扩展类型：JAVA、CEL、HTTP、SQL、DMN、FORM_FIELD',
    `status`         VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT '目录状态：ENABLED、DISABLED',
    `description`    VARCHAR(500)          DEFAULT NULL COMMENT '扩展业务说明',
    `create_by`      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '创建者正式用户主键',
    `create_time`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_by`      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '更新者正式用户主键',
    `update_time`    DATETIME(3)           DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`extension_id`),
    UNIQUE KEY `uk_wf_bpmn_extension_key` (`extension_key`),
    KEY `idx_wf_bpmn_extension_type_status` (`extension_type`, `status`),
    CONSTRAINT `chk_wf_bpmn_extension_key` CHECK
        (`extension_key` REGEXP '^[A-Za-z][A-Za-z0-9_.-]{0,127}$'),
    CONSTRAINT `chk_wf_bpmn_extension_type` CHECK
        (`extension_type` IN ('JAVA', 'CEL', 'HTTP', 'SQL', 'DMN', 'FORM_FIELD')),
    CONSTRAINT `chk_wf_bpmn_extension_status` CHECK
        (`status` IN ('ENABLED', 'DISABLED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'BPMN 受控扩展目录';

CREATE TABLE IF NOT EXISTS `wf_bpmn_extension_version`
(
    `version_id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '不可变扩展版本主键',
    `extension_id`       BIGINT      NOT NULL COMMENT '扩展目录主键',
    `version_no`         INT         NOT NULL COMMENT '单扩展内连续递增版本号',
    `implementation_key` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端已安装处理器稳定键',
    `config_schema`      JSON        NOT NULL COMMENT '服务端处理器提供的配置 JSON Schema',
    `checksum`           CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '版本定义 SHA-256',
    `create_by`          VARCHAR(64) NOT NULL DEFAULT '' COMMENT '发布者正式用户主键',
    `create_time`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发布时间',
    PRIMARY KEY (`version_id`),
    UNIQUE KEY `uk_wf_bpmn_extension_version` (`extension_id`, `version_no`),
    KEY `idx_wf_bpmn_extension_impl` (`implementation_key`),
    CONSTRAINT `fk_wf_bpmn_extension_version_extension` FOREIGN KEY (`extension_id`)
        REFERENCES `wf_bpmn_extension` (`extension_id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `chk_wf_bpmn_extension_version_no` CHECK (`version_no` > 0),
    CONSTRAINT `chk_wf_bpmn_extension_impl` CHECK
        (`implementation_key` REGEXP '^[A-Z][A-Z0-9_]{1,63}$'),
    CONSTRAINT `chk_wf_bpmn_extension_checksum` CHECK
        (`checksum` REGEXP '^[0-9a-f]{64}$')
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'BPMN 扩展不可变版本';

CREATE TABLE IF NOT EXISTS `wf_deploy_extension_snapshot`
(
    `snapshot_id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '部署扩展快照主键',
    `deploy_id`           VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Flowable 部署主键',
    `process_key`         VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'BPMN 可执行流程标识',
    `element_id`          VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'BPMN 活动元素标识',
    `extension_key`       VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '冻结扩展稳定键',
    `extension_version_id` BIGINT      NOT NULL COMMENT '冻结扩展版本主键',
    `version_no`          INT          NOT NULL COMMENT '冻结版本号冗余审计值',
    `extension_type`      VARCHAR(16)  NOT NULL COMMENT '冻结扩展类型',
    `implementation_key`  VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '冻结处理器稳定键',
    `config_json`         JSON         NOT NULL COMMENT '规范化节点配置',
    `version_checksum`    CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '扩展版本定义校验和',
    `snapshot_checksum`   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '完整执行快照校验和',
    `create_by`           VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '部署操作人正式用户主键',
    `create_time`         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '快照创建时间',
    PRIMARY KEY (`snapshot_id`),
    UNIQUE KEY `uk_wf_deploy_extension_element` (`deploy_id`, `process_key`, `element_id`),
    KEY `idx_wf_deploy_extension_version` (`extension_version_id`),
    KEY `idx_wf_deploy_extension_key` (`extension_key`, `version_no`),
    CONSTRAINT `fk_wf_deploy_extension_version` FOREIGN KEY (`extension_version_id`)
        REFERENCES `wf_bpmn_extension_version` (`version_id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `chk_wf_deploy_extension_version_no` CHECK (`version_no` > 0),
    CONSTRAINT `chk_wf_deploy_extension_type` CHECK
        (`extension_type` IN ('JAVA', 'CEL', 'HTTP', 'SQL', 'DMN', 'FORM_FIELD')),
    CONSTRAINT `chk_wf_deploy_extension_impl` CHECK
        (`implementation_key` REGEXP '^[A-Z][A-Z0-9_]{1,63}$'),
    CONSTRAINT `chk_wf_deploy_extension_version_checksum` CHECK
        (`version_checksum` REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT `chk_wf_deploy_extension_snapshot_checksum` CHECK
        (`snapshot_checksum` REGEXP '^[0-9a-f]{64}$')
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Flowable 部署扩展不可变执行快照';

INSERT INTO `wf_bpmn_extension`
    (`extension_key`, `extension_name`, `extension_type`, `status`, `description`,
     `create_by`, `create_time`, `update_by`, `update_time`)
SELECT 'approva.set-variable', '设置流程变量', 'JAVA', 'ENABLED',
       '将受控字符串、数字或布尔常量写入流程变量', 'system', current_timestamp(3), '', NULL
WHERE NOT EXISTS
(
    SELECT 1 FROM `wf_bpmn_extension` WHERE `extension_key` = 'approva.set-variable'
);

INSERT INTO `wf_bpmn_extension_version`
    (`extension_id`, `version_no`, `implementation_key`, `config_schema`,
     `checksum`, `create_by`, `create_time`)
SELECT e.extension_id, 1, 'SET_VARIABLE',
       CAST('{"type":"object","additionalProperties":false,"required":["targetVariable","value"],"properties":{"targetVariable":{"type":"string","pattern":"^[A-Za-z_][A-Za-z0-9_]{0,127}$"},"value":{"type":["string","number","boolean"]}}}' AS JSON),
       '42bca2710135b3faac369facee8c103683edf52b63f95c2ec2fb18f14fd3b3f0',
       'system', current_timestamp(3)
FROM `wf_bpmn_extension` e
WHERE e.extension_key = 'approva.set-variable'
  AND NOT EXISTS
  (
      SELECT 1 FROM `wf_bpmn_extension_version` v
      WHERE v.extension_id = e.extension_id AND v.version_no = 1
  );
