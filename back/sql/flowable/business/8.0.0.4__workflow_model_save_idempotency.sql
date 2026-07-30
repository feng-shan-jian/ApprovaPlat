-- 流程模型设计保存的持久化幂等表。
-- 本脚本只幂等创建缺失表，不删除、覆盖或清理任何正式保存记录；精确结构由只读 verify 脚本复核。

CREATE TABLE IF NOT EXISTS `wf_model_save_idempotency`
(
    `request_id`     CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '用户一次保存意图的规范小写 UUID',
    `user_id`        VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '事务内重新核验的规范工作流用户主键',
    `source_model_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '保存请求最初指向的 Flowable 模型主键',
    `payload_sha256` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范保存载荷的 SHA-256 小写十六进制摘要',
    `saved_model_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '真实保存成功的 Flowable 模型主键',
    `create_time`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '幂等请求首次登记时间',
    `complete_time`  DATETIME(3)          DEFAULT NULL COMMENT '模型与 BPMN 源码完成同事务持久化的时间',
    PRIMARY KEY (`request_id`),
    KEY `idx_wf_model_save_user_time` (`user_id`, `create_time`),
    KEY `idx_wf_model_save_source_time` (`source_model_id`, `create_time`),
    KEY `idx_wf_model_save_saved_model` (`saved_model_id`),
    CONSTRAINT `chk_wf_model_save_request_id` CHECK
        (`request_id` REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    CONSTRAINT `chk_wf_model_save_user_id` CHECK (`user_id` REGEXP '^[1-9][0-9]{0,18}$'),
    CONSTRAINT `chk_wf_model_save_source_id` CHECK (CHAR_LENGTH(`source_model_id`) BETWEEN 1 AND 64),
    CONSTRAINT `chk_wf_model_save_payload_sha256` CHECK (`payload_sha256` REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT `chk_wf_model_save_completion` CHECK
    (
        (`saved_model_id` IS NULL AND `complete_time` IS NULL)
        OR
        (`saved_model_id` IS NOT NULL
            AND CHAR_LENGTH(`saved_model_id`) BETWEEN 1 AND 64
            AND `complete_time` IS NOT NULL
            AND `complete_time` >= `create_time`)
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '流程模型设计保存持久化幂等记录；模型主键为审计软引用，不依赖 ACT 表级联';
