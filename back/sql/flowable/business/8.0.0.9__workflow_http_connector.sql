-- HTTP 连接器端点白名单与幂等调用台账。
-- 端点只保存外部密钥引用；密钥正文必须由运行环境的受控 Secret 提供器注入。

CREATE TABLE IF NOT EXISTS `wf_connector_endpoint`
(
    `endpoint_id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '连接器端点主键',
    `endpoint_key`        VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '设计器使用的稳定端点键',
    `endpoint_name`       VARCHAR(128) NOT NULL COMMENT '端点用户可见名称',
    `base_url`            VARCHAR(1024) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '只含协议、主机和端口的基础 URL',
    `allowed_methods`     VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '排序后的允许 HTTP 方法',
    `path_prefix`         VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '允许请求的绝对路径前缀',
    `auth_type`           VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'NONE、BEARER 或 API_KEY',
    `secret_ref`          VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '外部环境密钥引用，不保存密钥正文',
    `api_key_header`      VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT 'API_KEY 请求头名称',
    `connect_timeout_ms`  INT          NOT NULL COMMENT '连接超时毫秒数',
    `request_timeout_ms`  INT          NOT NULL COMMENT '请求整体超时毫秒数',
    `network_scope`       VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'PUBLIC 或 PRIVATE',
    `revision_no`         INT          NOT NULL COMMENT '端点配置不可回退修订号',
    `status`              VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED 或 DISABLED',
    `checksum`            CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '当前修订配置 SHA-256',
    `create_by`           VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '创建人正式用户主键',
    `create_time`         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_by`           VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '最后修改人正式用户主键',
    `update_time`         DATETIME(3)           DEFAULT NULL COMMENT '最后修改时间',
    PRIMARY KEY (`endpoint_id`),
    UNIQUE KEY `uk_wf_connector_endpoint_key` (`endpoint_key`),
    KEY `idx_wf_connector_endpoint_status` (`status`, `endpoint_name`),
    CONSTRAINT `chk_wf_connector_endpoint_key` CHECK
        (`endpoint_key` REGEXP '^[A-Za-z][A-Za-z0-9_.-]{0,127}$'),
    CONSTRAINT `chk_wf_connector_endpoint_auth` CHECK
        (`auth_type` IN ('NONE', 'BEARER', 'API_KEY')),
    CONSTRAINT `chk_wf_connector_endpoint_network` CHECK
        (`network_scope` IN ('PUBLIC', 'PRIVATE')),
    CONSTRAINT `chk_wf_connector_endpoint_revision` CHECK (`revision_no` > 0),
    CONSTRAINT `chk_wf_connector_endpoint_status` CHECK
        (`status` IN ('ENABLED', 'DISABLED')),
    CONSTRAINT `chk_wf_connector_endpoint_connect_timeout` CHECK
        (`connect_timeout_ms` BETWEEN 100 AND 10000),
    CONSTRAINT `chk_wf_connector_endpoint_request_timeout` CHECK
        (`request_timeout_ms` BETWEEN 500 AND 120000),
    CONSTRAINT `chk_wf_connector_endpoint_checksum` CHECK
        (`checksum` REGEXP '^[0-9a-f]{64}$')
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'HTTP 连接器端点白名单';

CREATE TABLE IF NOT EXISTS `wf_connector_invocation`
(
    `invocation_id`       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '调用台账主键',
    `deployment_id`       VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '冻结逻辑所属 Flowable 部署主键',
    `process_instance_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '流程实例主键',
    `execution_id`        VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '活动执行主键',
    `element_id`          VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'BPMN 元素标识',
    `endpoint_key`        VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '冻结端点键',
    `endpoint_revision`   INT          NOT NULL COMMENT '冻结端点修订号',
    `idempotency_key`     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '透传外部系统的稳定 SHA-256 幂等键',
    `request_method`      VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'HTTP 方法',
    `request_path`        VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '不含凭据和查询值的相对路径',
    `status`              VARCHAR(16)  NOT NULL COMMENT 'PENDING、RUNNING、SUCCESS 或 FAILED',
    `attempt_count`       INT          NOT NULL DEFAULT 0 COMMENT '累计尝试次数',
    `duration_ms`         BIGINT                DEFAULT NULL COMMENT '最近一次尝试耗时',
    `http_status`         INT                   DEFAULT NULL COMMENT '最近一次响应状态',
    `result_summary`      VARCHAR(500)          DEFAULT NULL COMMENT '长度和摘要等脱敏结果',
    `error_code`          VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '稳定错误码',
    `claim_token`         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '当前尝试领取令牌',
    `lease_expires_at`    DATETIME(3)           DEFAULT NULL COMMENT '当前尝试租约到期时间',
    `create_time`         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次创建时间',
    `update_time`         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后尝试时间',
    PRIMARY KEY (`invocation_id`),
    UNIQUE KEY `uk_wf_connector_invocation_idempotency` (`idempotency_key`),
    KEY `idx_wf_connector_invocation_instance` (`process_instance_id`, `element_id`),
    KEY `idx_wf_connector_invocation_status` (`status`, `update_time`),
    CONSTRAINT `chk_wf_connector_invocation_revision` CHECK (`endpoint_revision` > 0),
    CONSTRAINT `chk_wf_connector_invocation_idempotency` CHECK
        (`idempotency_key` REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT `chk_wf_connector_invocation_status` CHECK
        (`status` IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED')),
    CONSTRAINT `chk_wf_connector_invocation_attempt` CHECK (`attempt_count` >= 0),
    CONSTRAINT `chk_wf_connector_invocation_http_status` CHECK
        (`http_status` IS NULL OR `http_status` BETWEEN 100 AND 599)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'HTTP 连接器幂等调用台账';

INSERT INTO `wf_bpmn_extension`
    (`extension_key`, `extension_name`, `extension_type`, `status`, `description`,
     `create_by`, `create_time`, `update_by`, `update_time`)
SELECT 'approva.http-connector', 'HTTP 受控连接器', 'HTTP', 'ENABLED',
       '只调用端点白名单中已启用且在部署时冻结的 HTTP 端点',
       'system', current_timestamp(3), '', NULL
WHERE NOT EXISTS
(
    SELECT 1 FROM `wf_bpmn_extension` WHERE `extension_key` = 'approva.http-connector'
);

INSERT INTO `wf_bpmn_extension_version`
    (`extension_id`, `version_no`, `implementation_key`, `config_schema`,
     `checksum`, `create_by`, `create_time`)
SELECT e.extension_id, 1, 'HTTP_CONNECTOR_V1',
       CAST('{"additionalProperties":false,"properties":{"bodyVariable":{"pattern":"^[A-Za-z_][A-Za-z0-9_]{0,127}$","type":"string"},"endpointKey":{"pattern":"^[A-Za-z][A-Za-z0-9_.-]{0,127}$","type":"string"},"method":{"enum":["GET","POST","PUT","PATCH","DELETE"],"type":"string"},"path":{"pattern":"^/[A-Za-z0-9._~!$&''()*+,;=:@%/-]{0,511}$","type":"string"},"statusVariable":{"pattern":"^[A-Za-z_][A-Za-z0-9_]{0,127}$","type":"string"}},"required":["endpointKey","method","path"],"type":"object"}' AS JSON),
       '1e01f5bb398c3ef1755cfc53d0dffb8899464969289b7ecf10b5e6e5a9fdc2a9',
       'system', current_timestamp(3)
FROM `wf_bpmn_extension` e
WHERE e.extension_key = 'approva.http-connector'
  AND NOT EXISTS
  (
      SELECT 1 FROM `wf_bpmn_extension_version` v
      WHERE v.extension_id = e.extension_id AND v.version_no = 1
  );
