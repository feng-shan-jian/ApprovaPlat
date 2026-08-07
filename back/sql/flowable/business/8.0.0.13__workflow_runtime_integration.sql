CREATE TABLE IF NOT EXISTS `wf_integration_credential`
(
    `credential_id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '集成凭据主键',
    `credential_name`       VARCHAR(128) NOT NULL COMMENT '集成账号显示名称',
    `token_prefix`          CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Token 可识别前缀，不是凭据正文',
    `token_hash`            CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '完整 Token SHA-256',
    `scopes`                VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '排序后的 MESSAGE,SIGNAL,RECEIVE 范围',
    `allowed_variables`     VARCHAR(4096) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '排序后的变量白名单',
    `rate_limit_per_minute` INT          NOT NULL COMMENT '每分钟最大运行事件请求数',
    `rate_window_start`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '当前限流窗口开始时间',
    `rate_window_count`     INT          NOT NULL DEFAULT 0 COMMENT '当前窗口已消费请求数',
    `expires_at`            DATETIME(3)           DEFAULT NULL COMMENT '到期时间，空表示不过期',
    `revoked_at`            DATETIME(3)           DEFAULT NULL COMMENT '吊销时间，空表示未吊销',
    `revision_no`           INT          NOT NULL DEFAULT 1 COMMENT 'Token 轮换修订号',
    `last_used_at`          DATETIME(3)           DEFAULT NULL COMMENT '最近一次通过认证并消费限流的时间',
    `create_by`             VARCHAR(64)  NOT NULL COMMENT '创建凭据的正式用户主键，也是 Flowable 事件操作人',
    `create_time`           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_by`             VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '最后修改人正式用户主键',
    `update_time`           DATETIME(3)           DEFAULT NULL COMMENT '最后修改时间',
    PRIMARY KEY (`credential_id`),
    UNIQUE KEY `uk_wf_integration_token_prefix` (`token_prefix`),
    KEY `idx_wf_integration_active` (`revoked_at`, `expires_at`, `credential_name`),
    CONSTRAINT `chk_wf_integration_token_prefix` CHECK (`token_prefix` REGEXP '^[A-Za-z0-9_-]{12}$'),
    CONSTRAINT `chk_wf_integration_token_hash` CHECK (`token_hash` REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT `chk_wf_integration_scopes` CHECK (`scopes` REGEXP '^(MESSAGE|RECEIVE|SIGNAL)(,(MESSAGE|RECEIVE|SIGNAL))*$'),
    CONSTRAINT `chk_wf_integration_variables` CHECK
        (`allowed_variables` = '' OR `allowed_variables` REGEXP '^[A-Za-z_][A-Za-z0-9_]*(,[A-Za-z_][A-Za-z0-9_]*)*$'),
    CONSTRAINT `chk_wf_integration_rate_limit` CHECK (`rate_limit_per_minute` BETWEEN 1 AND 10000),
    CONSTRAINT `chk_wf_integration_rate_window` CHECK (`rate_window_count` BETWEEN 0 AND `rate_limit_per_minute`),
    CONSTRAINT `chk_wf_integration_revision` CHECK (`revision_no` > 0),
    CONSTRAINT `chk_wf_integration_expiry` CHECK (`expires_at` IS NULL OR `expires_at` > `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '工作流集成账号与哈希 Token';

CREATE TABLE IF NOT EXISTS `wf_runtime_event_request`
(
    `request_id`                  CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '调用方规范小写 UUID 幂等键',
    `credential_id`               BIGINT       NOT NULL COMMENT '认证通过的集成凭据主键',
    `event_type`                  VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'MESSAGE、SIGNAL 或 RECEIVE',
    `event_name`                  VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '事件名或 ReceiveTask activityId',
    `correlation_type`            VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'PROCESS_INSTANCE 或 BUSINESS_KEY',
    `correlation_value`           VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '实例主键或业务键',
    `variables_sha256`            CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范请求载荷摘要',
    `matched_process_instance_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '唯一匹配的流程实例',
    `matched_execution_id`        VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '唯一匹配的订阅或接收执行',
    `status`                      VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'RECEIVED、PROCESSED 或 FAILED',
    `result_code`                 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '稳定结果码',
    `result_summary`              VARCHAR(512) DEFAULT NULL COMMENT '不含变量正文的结果摘要',
    `create_time`                 DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '请求首次登记时间',
    `complete_time`               DATETIME(3)           DEFAULT NULL COMMENT '处理完成或失败时间',
    PRIMARY KEY (`request_id`),
    KEY `idx_wf_runtime_event_credential` (`credential_id`, `create_time`),
    KEY `idx_wf_runtime_event_instance` (`matched_process_instance_id`, `create_time`),
    KEY `idx_wf_runtime_event_status` (`status`, `create_time`),
    CONSTRAINT `fk_wf_runtime_event_credential` FOREIGN KEY (`credential_id`)
        REFERENCES `wf_integration_credential` (`credential_id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `chk_wf_runtime_event_request_id` CHECK
        (`request_id` REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    CONSTRAINT `chk_wf_runtime_event_type` CHECK (`event_type` IN ('MESSAGE', 'SIGNAL', 'RECEIVE')),
    CONSTRAINT `chk_wf_runtime_event_correlation` CHECK (`correlation_type` IN ('PROCESS_INSTANCE', 'BUSINESS_KEY')),
    CONSTRAINT `chk_wf_runtime_event_variables_hash` CHECK (`variables_sha256` REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT `chk_wf_runtime_event_status` CHECK (`status` IN ('RECEIVED', 'PROCESSED', 'FAILED')),
    CONSTRAINT `chk_wf_runtime_event_completion` CHECK
    (
        (`status` = 'RECEIVED' AND `complete_time` IS NULL AND `result_code` IS NULL)
        OR
        (`status` IN ('PROCESSED', 'FAILED') AND `complete_time` IS NOT NULL
            AND `result_code` IS NOT NULL AND `result_summary` IS NOT NULL)
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '消息、信号与 ReceiveTask 运行事件幂等审计';
