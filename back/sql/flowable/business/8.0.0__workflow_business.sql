-- Flowable 8 工作流业务表。
-- 本脚本仅创建缺失对象，不删除或覆盖已有业务数据；执行前仍需完成整库备份。

CREATE TABLE IF NOT EXISTS `wf_category`
(
    `category_id`   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '流程分类主键',
    `category_name` VARCHAR(64)  NOT NULL COMMENT '流程分类名称',
    `code`          VARCHAR(64)  NOT NULL COMMENT 'Flowable 模型和流程定义使用的分类编码',
    `create_by`     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '创建者账号',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '更新者账号',
    `update_time`   DATETIME              DEFAULT NULL COMMENT '更新时间',
    `remark`        VARCHAR(500)          DEFAULT NULL COMMENT '备注',
    `del_flag`      CHAR(1)      NOT NULL DEFAULT '0' COMMENT '逻辑删除标志：0 有效，2 已删除',
    `active_code`   VARCHAR(64) GENERATED ALWAYS AS
        (CASE WHEN `del_flag` = '0' THEN `code` ELSE NULL END) STORED
        COMMENT '仅有效分类参与唯一约束的生成列',
    PRIMARY KEY (`category_id`),
    UNIQUE KEY `uk_wf_category_active_code` (`active_code`),
    CONSTRAINT `chk_wf_category_del_flag` CHECK (`del_flag` IN ('0', '2'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '流程分类';

CREATE TABLE IF NOT EXISTS `wf_form`
(
    `form_id`     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '表单模板主键',
    `form_name`   VARCHAR(64)  NOT NULL COMMENT '表单名称',
    `content`     LONGTEXT     NOT NULL COMMENT '可编辑表单 JSON 模板',
    `create_by`   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '创建者账号',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '更新者账号',
    `update_time` DATETIME              DEFAULT NULL COMMENT '更新时间',
    `remark`      VARCHAR(255)          DEFAULT NULL COMMENT '备注',
    `del_flag`    CHAR(1)      NOT NULL DEFAULT '0' COMMENT '逻辑删除标志：0 有效，2 已删除',
    PRIMARY KEY (`form_id`),
    KEY `idx_wf_form_name` (`form_name`),
    CONSTRAINT `chk_wf_form_content_json` CHECK (JSON_VALID(`content`)),
    CONSTRAINT `chk_wf_form_del_flag` CHECK (`del_flag` IN ('0', '2'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '可编辑流程表单模板';

CREATE TABLE IF NOT EXISTS `wf_deploy_form`
(
    `deploy_id`  VARCHAR(64)  NOT NULL COMMENT 'Flowable 部署主键',
    `form_id`    BIGINT       NOT NULL COMMENT '快照来源表单主键',
    `form_key`   VARCHAR(128) NOT NULL COMMENT 'BPMN 表单键，兼容 key_<form_id>',
    `node_key`   VARCHAR(255) NOT NULL COMMENT 'BPMN 节点键',
    `form_name`  VARCHAR(64)  NOT NULL COMMENT '部署时表单名称快照',
    `node_name`  VARCHAR(255) NOT NULL DEFAULT '' COMMENT '部署时节点名称快照',
    `content`    LONGTEXT     NOT NULL COMMENT '部署时固化且不可回连覆盖的表单 JSON',
    `create_by`  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '部署操作人账号',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '快照创建时间',
    `del_flag`   CHAR(1)      NOT NULL DEFAULT '0' COMMENT '受控删除标志：0 有效，2 已删除',
    PRIMARY KEY (`deploy_id`, `form_key`, `node_key`),
    KEY `idx_wf_deploy_form_form_id` (`form_id`),
    CONSTRAINT `chk_wf_deploy_form_content_json` CHECK (JSON_VALID(`content`)),
    CONSTRAINT `chk_wf_deploy_form_del_flag` CHECK (`del_flag` IN ('0', '2'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '流程部署节点表单不可变快照';

CREATE TABLE IF NOT EXISTS `wf_copy`
(
    `copy_id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '抄送记录主键',
    `copy_event_id`   VARCHAR(128) NOT NULL COMMENT '同一次抄送业务事件的稳定幂等键',
    `title`           VARCHAR(255) NOT NULL DEFAULT '' COMMENT '抄送标题',
    `process_id`      VARCHAR(64)  NOT NULL COMMENT '流程定义主键',
    `process_name`    VARCHAR(255) NOT NULL DEFAULT '' COMMENT '流程名称快照',
    `category_id`     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '流程分类编码快照',
    `deployment_id`   VARCHAR(64)  NOT NULL COMMENT 'Flowable 部署主键',
    `instance_id`     VARCHAR(64)  NOT NULL COMMENT '流程实例主键',
    `task_id`         VARCHAR(64)           DEFAULT NULL COMMENT '产生抄送的任务主键',
    `user_id`         BIGINT       NOT NULL COMMENT '抄送接收用户主键',
    `originator_id`   BIGINT       NOT NULL COMMENT '流程发起用户主键',
    `originator_name` VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '流程发起用户名称快照',
    `create_by`       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '创建者账号',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '更新者账号',
    `update_time`     DATETIME              DEFAULT NULL COMMENT '更新时间',
    `remark`          VARCHAR(255)          DEFAULT NULL COMMENT '备注',
    `del_flag`        CHAR(1)      NOT NULL DEFAULT '0' COMMENT '逻辑删除标志：0 有效，2 已删除',
    PRIMARY KEY (`copy_id`),
    UNIQUE KEY `uk_wf_copy_event_user` (`copy_event_id`, `user_id`),
    KEY `idx_wf_copy_user_status_time` (`user_id`, `del_flag`, `create_time`),
    KEY `idx_wf_copy_instance` (`instance_id`, `del_flag`),
    KEY `idx_wf_copy_task` (`task_id`, `del_flag`),
    KEY `idx_wf_copy_deployment` (`deployment_id`, `del_flag`),
    CONSTRAINT `chk_wf_copy_del_flag` CHECK (`del_flag` IN ('0', '2'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '流程抄送记录';

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

CREATE TABLE IF NOT EXISTS `wf_attachment_quota_guard`
(
    `owner_user_id` BIGINT      NOT NULL COMMENT '配额互斥主键：0 为全局容量，其余为正式用户主键',
    `create_time`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次创建配额互斥行的时间',
    PRIMARY KEY (`owner_user_id`),
    CONSTRAINT `chk_wf_attachment_quota_guard_owner` CHECK (`owner_user_id` >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '工作流附件全局及用户配额事务互斥行，正常生命周期内不得删除';

-- 全局行在迁移期固定预置；上传事务直接 FOR UPDATE，禁止在并发请求中首次创建。
INSERT IGNORE INTO `wf_attachment_quota_guard` (`owner_user_id`)
VALUES (0);

CREATE TABLE IF NOT EXISTS `wf_attachment`
(
    `attachment_id`       CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端生成的附件 UUID',
    `owner_user_id`       BIGINT       NOT NULL COMMENT '临时附件所属用户主键',
    `field_name`          VARCHAR(128) NOT NULL COMMENT '附件所属表单字段名',
    `original_name`       VARCHAR(255) NOT NULL COMMENT '经服务端规范化的原始文件名',
    `storage_key`         VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '私有存储根目录内的相对对象键',
    `content_type`        VARCHAR(128) NOT NULL COMMENT '服务端探测的 MIME 类型',
    `file_size`           BIGINT       NOT NULL COMMENT '服务端实际写入的文件字节数',
    `sha256`              CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '文件内容 SHA-256 小写十六进制摘要',
    `attachment_status`   VARCHAR(16)  NOT NULL DEFAULT 'TEMP' COMMENT '附件状态：TEMP、BOUND、EXPIRED、DELETED',
    `expire_time`         DATETIME(3)  NOT NULL COMMENT '临时附件失效时间；绑定后仅作上传审计',
    `process_instance_id` VARCHAR(64)           DEFAULT NULL COMMENT '绑定的 Flowable 流程实例主键',
    `task_id`             VARCHAR(64)           DEFAULT NULL COMMENT '可选的 Flowable 任务主键',
    `node_key`            VARCHAR(255)          DEFAULT NULL COMMENT '提交附件的 BPMN 节点 key',
    `bound_time`          DATETIME(3)           DEFAULT NULL COMMENT '成功绑定流程对象的时间',
    `storage_deleted_time` DATETIME(3)          DEFAULT NULL COMMENT '私有文件已物理删除的时间',
    `cleanup_retry_count` INT          NOT NULL DEFAULT 0 COMMENT '物理清理连续失败并已调度重试的次数',
    `cleanup_next_retry_time` DATETIME(3)       DEFAULT NULL COMMENT '下次允许进入物理清理候选的时间',
    `cleanup_last_error_code` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '最近一次清理失败的稳定脱敏错误码',
    `create_time`         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '上传完成并登记元数据的时间',
    `update_time`         DATETIME(3)           DEFAULT NULL COMMENT '最后状态更新时间',
    PRIMARY KEY (`attachment_id`),
    UNIQUE KEY `uk_wf_attachment_storage_key` (`storage_key`),
    KEY `idx_wf_attachment_owner_status_expire` (`owner_user_id`, `attachment_status`, `expire_time`),
    KEY `idx_wf_attachment_status_expire` (`attachment_status`, `expire_time`),
    KEY `idx_wf_attachment_cleanup_due` (`attachment_status`, `cleanup_next_retry_time`, `expire_time`),
    KEY `idx_wf_attachment_instance_field` (`process_instance_id`, `field_name`, `attachment_status`),
    CONSTRAINT `chk_wf_attachment_status` CHECK (`attachment_status` IN ('TEMP', 'BOUND', 'EXPIRED', 'DELETED')),
    CONSTRAINT `chk_wf_attachment_file_size` CHECK (`file_size` > 0),
    CONSTRAINT `chk_wf_attachment_sha256` CHECK (`sha256` REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT `chk_wf_attachment_state_relation` CHECK
    (
        (`attachment_status` = 'BOUND'
            AND `process_instance_id` IS NOT NULL
            AND `node_key` IS NOT NULL
            AND `bound_time` IS NOT NULL
            AND `storage_deleted_time` IS NULL)
        OR
        (`attachment_status` IN ('TEMP', 'EXPIRED', 'DELETED')
            AND `process_instance_id` IS NULL
            AND `task_id` IS NULL
            AND `node_key` IS NULL
            AND `bound_time` IS NULL)
    ),
    CONSTRAINT `chk_wf_attachment_storage_deleted` CHECK
    (
        `storage_deleted_time` IS NULL
        OR `attachment_status` IN ('EXPIRED', 'DELETED')
    ),
    CONSTRAINT `chk_wf_attachment_cleanup_retry` CHECK
    (
        `cleanup_retry_count` >= 0
        AND
        (
            (`cleanup_next_retry_time` IS NULL
                AND `cleanup_last_error_code` IS NULL
                AND (`attachment_status` IN ('EXPIRED', 'DELETED')
                     OR `cleanup_retry_count` = 0))
            OR
            (`storage_deleted_time` IS NULL
                AND `attachment_status` IN ('EXPIRED', 'DELETED')
                AND `cleanup_retry_count` > 0
                AND `cleanup_next_retry_time` IS NOT NULL
                AND `cleanup_last_error_code` REGEXP '^[a-z][a-z0-9_]{0,63}$')
        )
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '工作流私有附件元数据';
