-- Flowable 8 工作流业务表最终基线。
-- 开发期结构变化直接删除并从空库重建，不创建备份、回填或兼容迁移。

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

CREATE TABLE IF NOT EXISTS `wf_controlled_loop_execution`
(
    `execution_id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '循环轮次审计主键',
    `deploy_id`             VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Flowable 部署主键',
    `process_definition_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '流程定义主键',
    `process_instance_id`   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '流程实例主键',
    `activity_id`           VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '循环用户任务标识',
    `task_id`               VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本轮真实任务主键',
    `iteration_no`          INT          NOT NULL COMMENT '本节点在该流程实例内从 1 开始的完成轮次',
    `actor_user_id`         VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本轮真实完成人主键',
    `decision_value`        VARCHAR(128) NOT NULL COMMENT '经表单 schema 校验后的判断字段值',
    `outcome`               VARCHAR(16)  NOT NULL COMMENT '本轮结果：REPEAT 再次进入、EXIT 退出',
    `create_time`           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '本轮完成时间',
    PRIMARY KEY (`execution_id`),
    UNIQUE KEY `uk_wf_controlled_loop_task` (`task_id`),
    UNIQUE KEY `uk_wf_controlled_loop_iteration`
        (`process_instance_id`, `activity_id`, `iteration_no`),
    KEY `idx_wf_controlled_loop_instance_time` (`process_instance_id`, `create_time`),
    KEY `idx_wf_controlled_loop_retention` (`create_time`, `execution_id`),
    KEY `idx_wf_controlled_loop_deploy` (`deploy_id`, `activity_id`),
    CONSTRAINT `chk_wf_controlled_loop_iteration_no` CHECK (`iteration_no` BETWEEN 1 AND 50),
    CONSTRAINT `chk_wf_controlled_loop_actor` CHECK (`actor_user_id` REGEXP '^[1-9][0-9]{0,18}$'),
    CONSTRAINT `chk_wf_controlled_loop_outcome` CHECK (`outcome` IN ('REPEAT', 'EXIT')),
    CONSTRAINT `chk_wf_controlled_loop_decision_value` CHECK
        (CHAR_LENGTH(`decision_value`) BETWEEN 1 AND 128)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '受控重复审批循环逐轮运行审计';

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

CREATE TABLE IF NOT EXISTS `wf_business_calendar`
(
    `calendar_id`   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '业务日历主键',
    `calendar_key`  VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '部署引用稳定编码',
    `calendar_name` VARCHAR(128) NOT NULL COMMENT '用户可见名称',
    `timezone`      VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'IANA 时区',
    `working_days`  VARCHAR(13) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '逗号分隔 ISO 工作周序号',
    `work_start`    TIME         NOT NULL COMMENT '工作日开始时间',
    `work_end`      TIME         NOT NULL COMMENT '工作日结束时间',
    `status`        VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'ENABLED 或 DISABLED',
    `description`   VARCHAR(500) DEFAULT NULL COMMENT '日历说明',
    `create_by`     VARCHAR(64)  NOT NULL COMMENT '创建人用户主键',
    `create_time`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_by`     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '修改人用户主键',
    `update_time`   DATETIME(3)           DEFAULT NULL COMMENT '修改时间',
    PRIMARY KEY (`calendar_id`),
    UNIQUE KEY `uk_wf_business_calendar_key` (`calendar_key`),
    CONSTRAINT `chk_wf_business_calendar_key` CHECK (`calendar_key` REGEXP '^[A-Z][A-Z0-9_.-]{1,63}$'),
    CONSTRAINT `chk_wf_business_calendar_status` CHECK (`status` IN ('ENABLED', 'DISABLED')),
    CONSTRAINT `chk_wf_business_calendar_window` CHECK (`work_start` < `work_end`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '审批 SLA 正式业务日历';

CREATE TABLE IF NOT EXISTS `wf_business_calendar_day`
(
    `calendar_id`   BIGINT      NOT NULL COMMENT '业务日历主键',
    `calendar_date` DATE        NOT NULL COMMENT '日历时区自然日',
    `working_day`   TINYINT(1)  NOT NULL COMMENT '1 补班，0 节假日',
    `day_name`      VARCHAR(128) DEFAULT NULL COMMENT '日期说明',
    PRIMARY KEY (`calendar_id`, `calendar_date`),
    CONSTRAINT `fk_wf_business_calendar_day_calendar` FOREIGN KEY (`calendar_id`)
        REFERENCES `wf_business_calendar` (`calendar_id`) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT `chk_wf_business_calendar_day_working` CHECK (`working_day` IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '业务日历节假日与补班覆盖';

CREATE TABLE IF NOT EXISTS `wf_task_sla_execution`
(
    `sla_execution_id`      BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'SLA 执行主键',
    `deployment_id`         VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '部署主键',
    `process_instance_id`   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '流程实例主键',
    `process_definition_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '流程定义主键',
    `task_id`               VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原审批任务主键',
    `task_definition_key`   VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原审批节点标识',
    `assignee_user_id`      VARCHAR(64)  DEFAULT NULL COMMENT '当前办理人用户主键',
    `status`                VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'ACTIVE、COMPLETED 或 ESCALATED',
    `started_at`            DATETIME(3)  NOT NULL COMMENT 'SLA 开始 UTC 时间',
    `reminder_due_at`       DATETIME(3)  NOT NULL COMMENT '首次提醒 UTC 时间',
    `escalation_due_at`     DATETIME(3)  NOT NULL COMMENT '升级 UTC 时间',
    `reminders_sent`        INT          NOT NULL DEFAULT 0 COMMENT '已发送提醒次数',
    `paused_at`             DATETIME(3)           DEFAULT NULL COMMENT '当前暂停 UTC 时间',
    `paused_millis`         BIGINT       NOT NULL DEFAULT 0 COMMENT '累计暂停毫秒数',
    `revision`              INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `update_time`           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后状态时间',
    PRIMARY KEY (`sla_execution_id`),
    UNIQUE KEY `uk_wf_task_sla_execution_task` (`task_id`),
    KEY `idx_wf_task_sla_execution_instance` (`process_instance_id`, `status`, `sla_execution_id`),
    KEY `idx_wf_task_sla_execution_retention` (`status`, `update_time`, `sla_execution_id`),
    CONSTRAINT `chk_wf_task_sla_execution_status` CHECK (`status` IN ('ACTIVE', 'COMPLETED', 'ESCALATED')),
    CONSTRAINT `chk_wf_task_sla_execution_counter` CHECK (`reminders_sent` >= 0 AND `paused_millis` >= 0 AND `revision` >= 0),
    CONSTRAINT `chk_wf_task_sla_execution_due` CHECK (`started_at` <= `reminder_due_at` AND `reminder_due_at` < `escalation_due_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '真实审批任务 SLA 状态';

CREATE TABLE IF NOT EXISTS `wf_task_sla_audit`
(
    `audit_id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '审计主键',
    `sla_execution_id` BIGINT       NOT NULL COMMENT 'SLA 执行主键',
    `action_type`      VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '生命周期动作',
    `action_ordinal`   INT          NOT NULL COMMENT '重复动作序号',
    `actor_user_id`    VARCHAR(64)  DEFAULT NULL COMMENT '可空操作人用户主键',
    `detail`           VARCHAR(500) NOT NULL COMMENT '脱敏动作摘要',
    `create_time`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '动作时间',
    PRIMARY KEY (`audit_id`),
    UNIQUE KEY `uk_wf_task_sla_audit_action` (`sla_execution_id`, `action_type`, `action_ordinal`),
    KEY `idx_wf_task_sla_audit_time` (`create_time`, `audit_id`),
    CONSTRAINT `fk_wf_task_sla_audit_execution` FOREIGN KEY (`sla_execution_id`)
        REFERENCES `wf_task_sla_execution` (`sla_execution_id`) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT `chk_wf_task_sla_audit_action` CHECK
        (`action_type` IN ('CREATE', 'ASSIGN', 'REMINDER', 'ESCALATE', 'COMPLETE', 'PAUSE', 'RESUME')),
    CONSTRAINT `chk_wf_task_sla_audit_ordinal` CHECK (`action_ordinal` >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '审批 SLA 不可变运行审计';

INSERT INTO `wf_business_calendar`
    (`calendar_key`, `calendar_name`, `timezone`, `working_days`, `work_start`, `work_end`,
     `status`, `description`, `create_by`, `create_time`, `update_by`, `update_time`)
SELECT 'DEFAULT_CN', '默认工作日历', 'Asia/Shanghai', '1,2,3,4,5', '09:00', '18:00',
       'ENABLED', '周一至周五工作，节假日和补班可按正式日期覆盖维护',
       'system', current_timestamp(3), '', NULL
WHERE NOT EXISTS
(
    SELECT 1 FROM `wf_business_calendar` WHERE `calendar_key` = 'DEFAULT_CN'
);

INSERT INTO `wf_bpmn_extension`
    (`extension_key`, `extension_name`, `extension_type`, `status`, `description`,
     `create_by`, `create_time`, `update_by`, `update_time`)
SELECT 'approva.collaboration-outbox', '跨参与方可靠消息', 'JAVA', 'ENABLED',
       '在 Flowable 事务内登记 outbox，由后台 worker 按关联键顺序认证投递',
       'system', current_timestamp(3), '', NULL
WHERE NOT EXISTS
(
    SELECT 1 FROM `wf_bpmn_extension`
    WHERE `extension_key` = 'approva.collaboration-outbox'
);

INSERT INTO `wf_bpmn_extension_version`
    (`extension_id`, `version_no`, `implementation_key`, `config_schema`,
     `checksum`, `create_by`, `create_time`)
SELECT e.extension_id, 1, 'COLLABORATION_OUTBOX_V1',
       CAST('{"additionalProperties":false,"properties":{"correlationVariable":{"type":"string"},"endpointKey":{"type":"string"},"maxAttempts":{"maximum":20,"minimum":1,"type":"integer"},"messageName":{"type":"string"},"path":{"type":"string"},"targetProcessDefinitionKey":{"type":"string"},"variableNames":{"items":{"type":"string"},"maxItems":128,"type":"array"}},"required":["endpointKey","path","messageName","targetProcessDefinitionKey","variableNames","maxAttempts"],"type":"object"}' AS JSON),
       '6741a2065519d613389cc52c0e9ae8a1c3609a2d7a0660d0af0c88833acdb592',
       'system', current_timestamp(3)
FROM `wf_bpmn_extension` e
WHERE e.extension_key = 'approva.collaboration-outbox'
  AND NOT EXISTS
  (
      SELECT 1 FROM `wf_bpmn_extension_version` v
      WHERE v.extension_id = e.extension_id AND v.version_no = 1
  );

INSERT INTO `wf_bpmn_extension`
    (`extension_key`, `extension_name`, `extension_type`, `status`, `description`,
     `create_by`, `create_time`, `update_by`, `update_time`)
SELECT 'approva.form.textarea', '多行文本', 'FORM_FIELD', 'ENABLED',
       '固定为服务端安装的多行文本渲染器，用于 BPMN 内嵌 FormData',
       'system', current_timestamp(3), '', NULL
WHERE NOT EXISTS
(
    SELECT 1 FROM `wf_bpmn_extension`
    WHERE `extension_key` = 'approva.form.textarea'
);

INSERT INTO `wf_bpmn_extension_version`
    (`extension_id`, `version_no`, `implementation_key`, `config_schema`,
     `checksum`, `create_by`, `create_time`)
SELECT e.extension_id, 1, 'FORM_FIELD_TEXTAREA_V1',
       CAST('{"additionalProperties":false,"properties":{},"type":"object"}' AS JSON),
       '1b6a6597e25bcf0ffeb06415b043465ec85a7cceddde850d1551e3a39b2ad78b',
       'system', current_timestamp(3)
FROM `wf_bpmn_extension` e
WHERE e.extension_key = 'approva.form.textarea'
  AND NOT EXISTS
  (
      SELECT 1 FROM `wf_bpmn_extension_version` v
      WHERE v.extension_id = e.extension_id AND v.version_no = 1
  );

INSERT INTO `wf_bpmn_extension`
    (`extension_key`, `extension_name`, `extension_type`, `status`, `description`,
     `create_by`, `create_time`, `update_by`, `update_time`)
SELECT 'approva.cel-expression', 'CEL 安全表达式', 'CEL', 'ENABLED',
       '仅使用节点显式声明的标量变量计算确定性结果，不提供文件、网络、进程或 Bean 函数',
       'system', current_timestamp(3), '', NULL
WHERE NOT EXISTS
(
    SELECT 1 FROM `wf_bpmn_extension` WHERE `extension_key` = 'approva.cel-expression'
);

INSERT INTO `wf_bpmn_extension_version`
    (`extension_id`, `version_no`, `implementation_key`, `config_schema`,
     `checksum`, `create_by`, `create_time`)
SELECT e.extension_id, 1, 'CEL_EXPRESSION_V1',
       CAST('{"additionalProperties":false,"properties":{"expression":{"maxLength":4096,"minLength":1,"type":"string"},"resultType":{"enum":["BOOL","INT","DOUBLE","STRING"],"type":"string"},"resultVariable":{"pattern":"^[A-Za-z_][A-Za-z0-9_]{0,127}$","type":"string"},"variables":{"items":{"additionalProperties":false,"properties":{"name":{"pattern":"^[A-Za-z_][A-Za-z0-9_]{0,127}$","type":"string"},"type":{"enum":["BOOL","INT","DOUBLE","STRING"],"type":"string"}},"required":["name","type"],"type":"object"},"maxItems":32,"type":"array"}},"required":["expression","resultVariable","resultType","variables"],"type":"object"}' AS JSON),
       '6b5c7dcf648f27ff1fd13c654ff149a7f84b90dc2719abd33e2ef078a5970db6',
       'system', current_timestamp(3)
FROM `wf_bpmn_extension` e
WHERE e.extension_key = 'approva.cel-expression'
  AND NOT EXISTS
  (
      SELECT 1 FROM `wf_bpmn_extension_version` v
      WHERE v.extension_id = e.extension_id AND v.version_no = 1
  );

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

CREATE TABLE IF NOT EXISTS `wf_sql_datasource`
(
    `datasource_id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'SQL 数据源目录主键',
    `datasource_key`         VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '设计器引用的稳定逻辑键',
    `datasource_name`        VARCHAR(128) NOT NULL COMMENT '数据源显示名称',
    `connection_type`        VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'PRIMARY 或 EXTERNAL',
    `jdbc_url_ref`           VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '外库 JDBC URL 环境引用',
    `username_ref`           VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '外库用户名环境引用',
    `password_ref`           VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '外库密码环境引用',
    `allowed_tables`         VARCHAR(8192) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '排序后的 AST 表白名单',
    `connect_timeout_ms`     INT          NOT NULL COMMENT '外库建连超时毫秒',
    `query_timeout_seconds`  INT          NOT NULL COMMENT '单条 SQL 超时秒数',
    `revision_no`            INT          NOT NULL COMMENT '不可回退修订号',
    `status`                 VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED 或 DISABLED',
    `checksum`               CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '当前修订 SHA-256',
    `create_by`              VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '创建人正式用户主键',
    `create_time`            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_by`              VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '最后修改人正式用户主键',
    `update_time`            DATETIME(3)           DEFAULT NULL COMMENT '最后修改时间',
    PRIMARY KEY (`datasource_id`),
    UNIQUE KEY `uk_wf_sql_datasource_key` (`datasource_key`),
    KEY `idx_wf_sql_datasource_status` (`status`, `datasource_name`),
    CONSTRAINT `chk_wf_sql_datasource_key` CHECK
        (`datasource_key` REGEXP '^[A-Za-z][A-Za-z0-9_.-]{0,127}$'),
    CONSTRAINT `chk_wf_sql_datasource_type` CHECK (`connection_type` IN ('PRIMARY', 'EXTERNAL')),
    CONSTRAINT `chk_wf_sql_datasource_status` CHECK (`status` IN ('ENABLED', 'DISABLED')),
    CONSTRAINT `chk_wf_sql_datasource_revision` CHECK (`revision_no` > 0),
    CONSTRAINT `chk_wf_sql_datasource_connect_timeout` CHECK (`connect_timeout_ms` BETWEEN 100 AND 10000),
    CONSTRAINT `chk_wf_sql_datasource_query_timeout` CHECK (`query_timeout_seconds` BETWEEN 1 AND 300),
    CONSTRAINT `chk_wf_sql_datasource_checksum` CHECK (`checksum` REGEXP '^[0-9a-f]{64}$')
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'SQL 连接器受控数据源目录';

INSERT INTO `wf_bpmn_extension`
    (`extension_key`, `extension_name`, `extension_type`, `status`, `description`,
     `create_by`, `create_time`, `update_by`, `update_time`)
SELECT 'approva.sql-connector', 'SQL 受控连接器', 'SQL', 'ENABLED',
       '只执行解析通过的单条命名参数 SQL 模板，并在部署时冻结数据源修订',
       'system', current_timestamp(3), '', NULL
WHERE NOT EXISTS
(
    SELECT 1 FROM `wf_bpmn_extension` WHERE `extension_key` = 'approva.sql-connector'
);

INSERT INTO `wf_bpmn_extension_version`
    (`extension_id`, `version_no`, `implementation_key`, `config_schema`,
     `checksum`, `create_by`, `create_time`)
SELECT e.extension_id, 1, 'SQL_CONNECTOR_V1',
       CAST('{"additionalProperties":false,"properties":{"dataSourceKey":{"pattern":"^[A-Za-z][A-Za-z0-9_.-]{0,127}$","type":"string"},"idempotencyColumn":{"pattern":"^[A-Za-z_][A-Za-z0-9_$]{0,127}$","type":"string"},"maxRows":{"maximum":1000,"minimum":1,"type":"integer"},"parameters":{"additionalProperties":{"pattern":"^[A-Za-z_][A-Za-z0-9_]{0,127}$","type":"string"},"type":"object"},"resultVariable":{"pattern":"^[A-Za-z_][A-Za-z0-9_]{0,127}$","type":"string"},"sql":{"maxLength":8192,"minLength":1,"type":"string"}},"required":["dataSourceKey","sql","parameters"],"type":"object"}' AS JSON),
       '262e474870a4b0dda95860efd908d21afc417aa38f83e90be0eb1a35a392c3c7',
       'system', current_timestamp(3)
FROM `wf_bpmn_extension` e
WHERE e.extension_key = 'approva.sql-connector'
  AND NOT EXISTS
  (
      SELECT 1 FROM `wf_bpmn_extension_version` v
      WHERE v.extension_id = e.extension_id AND v.version_no = 1
  );

CREATE TABLE IF NOT EXISTS `wf_integration_credential`
(
    `credential_id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '集成凭据主键',
    `credential_name`       VARCHAR(128) NOT NULL COMMENT '集成账号显示名称',
    `token_prefix`          CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Token 可识别前缀，不是凭据正文',
    `token_hash`            CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '完整 Token SHA-256',
    `scopes`                VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '排序后的 MESSAGE,SIGNAL,RECEIVE 范围',
    `allowed_variables`     VARCHAR(4096) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '排序后的变量白名单',
    `rate_limit_per_minute` INT          NOT NULL COMMENT '每分钟最大运行事件请求数',
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
    KEY `idx_wf_runtime_event_retention` (`status`, `complete_time`, `request_id`),
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

CREATE TABLE IF NOT EXISTS `wf_collaboration_channel`
(
    `channel_id`                    CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '目标流程与关联值组成的稳定 SHA-256 通道键',
    `target_process_definition_key` VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '接收方流程定义 key',
    `correlation_type`              VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'BUSINESS_KEY 或 PROCESS_INSTANCE',
    `correlation_value`             VARCHAR(255) NOT NULL COMMENT '业务关联键或目标实例主键',
    `outbound_sequence`             BIGINT       NOT NULL DEFAULT 0 COMMENT '已分配的最后一个出站序号',
    `inbound_sequence`              BIGINT       NOT NULL DEFAULT 0 COMMENT '已成功消费的最后一个入站序号',
    `revision_no`                   INT          NOT NULL DEFAULT 0 COMMENT '通道并发修订号',
    `create_time`                   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '通道创建时间',
    `update_time`                   DATETIME(3)           DEFAULT NULL COMMENT '最后序号推进时间',
    PRIMARY KEY (`channel_id`),
    UNIQUE KEY `uk_wf_collab_channel_target` (`target_process_definition_key`, `correlation_type`, `correlation_value`),
    CONSTRAINT `chk_wf_collab_channel_id` CHECK (`channel_id` REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT `chk_wf_collab_channel_type` CHECK (`correlation_type` IN ('BUSINESS_KEY', 'PROCESS_INSTANCE')),
    CONSTRAINT `chk_wf_collab_channel_sequence` CHECK (`outbound_sequence` >= 0 AND `inbound_sequence` >= 0),
    CONSTRAINT `chk_wf_collab_channel_revision` CHECK (`revision_no` >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Participant/MessageFlow 关联通道与严格顺序游标';

CREATE TABLE IF NOT EXISTS `wf_collaboration_message`
(
    `message_id`                    CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '调用方生成的协作消息幂等键',
    `credential_id`                 BIGINT       NOT NULL COMMENT '认证集成凭据主键',
    `actor_user_id`                 VARCHAR(64)  NOT NULL COMMENT '凭据绑定的可信系统操作人',
    `channel_id`                    CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '严格顺序通道主键',
    `sequence_no`                   BIGINT       NOT NULL COMMENT '调用方在同一关联通道内分配的连续序号',
    `message_name`                  VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'BPMN MessageFlow 消息名称',
    `source_process_definition_key` VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '' COMMENT '发送方流程定义 key 快照',
    `target_process_definition_key` VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '接收方流程定义 key',
    `correlation_key`               VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '接收实例业务键',
    `target_process_instance_id`    VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '唯一接收流程实例',
    `matched_process_instance_id`   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '成功关联的接收流程实例',
    `target_execution_id`           VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '唯一消息等待执行',
    `variables_json`                JSON         NOT NULL COMMENT '白名单标量变量，用于一致性重放与补偿',
    `payload_sha256`                CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '消息载荷稳定摘要',
    `status`                        VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'RECEIVED、RETRYING、PROCESSED 或 DEAD_LETTER',
    `attempt_count`                 INT          NOT NULL DEFAULT 0 COMMENT '已尝试投递次数',
    `max_attempts`                  INT          NOT NULL DEFAULT 5 COMMENT '最大投递次数',
    `compensation_count`            INT          NOT NULL DEFAULT 0 COMMENT '管理员人工补偿次数',
    `revision_no`                   INT          NOT NULL DEFAULT 0 COMMENT '状态并发修订号',
    `last_error_code`               VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '最后一次稳定错误编码',
    `last_error_summary`            VARCHAR(512) DEFAULT NULL COMMENT '脱敏错误摘要',
    `create_time`                   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次登记时间',
    `next_attempt_time`             DATETIME(3)           DEFAULT NULL COMMENT '下一次重试时间',
    `complete_time`                 DATETIME(3)           DEFAULT NULL COMMENT '完成或进入死信时间',
    PRIMARY KEY (`message_id`),
    UNIQUE KEY `uk_wf_collab_message_sequence` (`channel_id`, `sequence_no`),
    KEY `idx_wf_collab_target` (`target_process_definition_key`, `correlation_key`, `status`),
    KEY `idx_wf_collab_status` (`status`, `next_attempt_time`, `create_time`),
    KEY `idx_wf_collab_message_retention` (`status`, `complete_time`, `message_id`),
    CONSTRAINT `fk_wf_collab_credential` FOREIGN KEY (`credential_id`)
        REFERENCES `wf_integration_credential` (`credential_id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `fk_wf_collab_channel` FOREIGN KEY (`channel_id`)
        REFERENCES `wf_collaboration_channel` (`channel_id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `chk_wf_collab_id` CHECK (`message_id` REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    CONSTRAINT `chk_wf_collab_correlation` CHECK ((`correlation_key` IS NULL) <> (`target_process_instance_id` IS NULL)),
    CONSTRAINT `chk_wf_collab_status` CHECK (`status` IN ('RECEIVED', 'RETRYING', 'PROCESSED', 'DEAD_LETTER')),
    CONSTRAINT `chk_wf_collab_attempts` CHECK (`attempt_count` BETWEEN 0 AND `max_attempts` AND `max_attempts` BETWEEN 1 AND 20),
    CONSTRAINT `chk_wf_collab_compensation` CHECK (`compensation_count` >= 0 AND `revision_no` >= 0),
    CONSTRAINT `chk_wf_collab_completion` CHECK
        ((`status` IN ('RECEIVED', 'RETRYING') AND `complete_time` IS NULL)
         OR (`status` IN ('PROCESSED', 'DEAD_LETTER') AND `complete_time` IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Participant/MessageFlow 协作消息可靠投递与死信台账';

CREATE TABLE IF NOT EXISTS `wf_collaboration_outbox`
(
    `message_id`                    CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '由部署、实例、execution 和活动确定的幂等消息主键',
    `channel_id`                    CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '严格顺序通道主键',
    `sequence_no`                   BIGINT       NOT NULL COMMENT '同一关联通道内连续出站序号',
    `source_process_definition_key` VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '发送方流程定义 key',
    `source_process_instance_id`    VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '发送方流程实例主键',
    `source_execution_id`           VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '产生消息的 execution 主键',
    `source_element_id`             VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '产生消息的 SendTask 主键',
    `message_name`                  VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'BPMN MessageFlow 消息名称',
    `target_process_definition_key` VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '接收方流程定义 key',
    `correlation_key`               VARCHAR(255) NOT NULL COMMENT '接收实例业务关联键',
    `endpoint_id`                   BIGINT       NOT NULL COMMENT '冻结的 HTTP 端点主键',
    `endpoint_revision`             INT          NOT NULL COMMENT '冻结的 HTTP 端点修订号',
    `request_path`                  VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '受控接收路径',
    `delivery_config_json`          JSON         NOT NULL COMMENT '不含密钥正文的端点快照与投递策略',
    `variables_json`                JSON         NOT NULL COMMENT '部署白名单选取的标量变量快照',
    `payload_sha256`                CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '完整请求稳定摘要',
    `status`                        VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'PENDING、DELIVERING、RETRYING、PROCESSED、DEAD_LETTER 或 CANCELLED',
    `attempt_count`                 INT          NOT NULL DEFAULT 0 COMMENT '已开始的投递次数',
    `max_attempts`                  INT          NOT NULL DEFAULT 5 COMMENT '最大投递次数',
    `compensation_count`            INT          NOT NULL DEFAULT 0 COMMENT '管理员人工补偿次数',
    `revision_no`                   INT          NOT NULL DEFAULT 0 COMMENT '状态并发修订号',
    `lease_owner`                   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '当前后台 worker 租约',
    `lease_until`                   DATETIME(3)           DEFAULT NULL COMMENT '后台 worker 租约截止时间',
    `next_attempt_time`             DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下次允许领取时间',
    `last_http_status`              INT                   DEFAULT NULL COMMENT '最后一次 HTTP 状态',
    `last_error_code`               VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '最后一次稳定错误编码',
    `last_error_summary`            VARCHAR(512) DEFAULT NULL COMMENT '不含响应正文的脱敏错误摘要',
    `create_time`                   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '事务 outbox 登记时间',
    `last_attempt_time`             DATETIME(3)           DEFAULT NULL COMMENT '最后一次领取时间',
    `complete_time`                 DATETIME(3)           DEFAULT NULL COMMENT '送达、死信或取消时间',
    PRIMARY KEY (`message_id`),
    UNIQUE KEY `uk_wf_collab_outbox_sequence` (`channel_id`, `sequence_no`),
    UNIQUE KEY `uk_wf_collab_outbox_source` (`source_process_instance_id`, `source_execution_id`, `source_element_id`),
    KEY `idx_wf_collab_outbox_due` (`status`, `next_attempt_time`, `lease_until`, `create_time`),
    KEY `idx_wf_collab_outbox_retention` (`status`, `complete_time`, `message_id`),
    CONSTRAINT `fk_wf_collab_outbox_channel` FOREIGN KEY (`channel_id`)
        REFERENCES `wf_collaboration_channel` (`channel_id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `fk_wf_collab_outbox_endpoint` FOREIGN KEY (`endpoint_id`)
        REFERENCES `wf_connector_endpoint` (`endpoint_id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `chk_wf_collab_outbox_id` CHECK (`message_id` REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    CONSTRAINT `chk_wf_collab_outbox_hash` CHECK (`channel_id` REGEXP '^[0-9a-f]{64}$' AND `payload_sha256` REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT `chk_wf_collab_outbox_status` CHECK (`status` IN ('PENDING', 'DELIVERING', 'RETRYING', 'PROCESSED', 'DEAD_LETTER', 'CANCELLED')),
    CONSTRAINT `chk_wf_collab_outbox_attempts` CHECK (`attempt_count` BETWEEN 0 AND `max_attempts` AND `max_attempts` BETWEEN 1 AND 20),
    CONSTRAINT `chk_wf_collab_outbox_revision` CHECK (`compensation_count` >= 0 AND `revision_no` >= 0),
    CONSTRAINT `chk_wf_collab_outbox_lease` CHECK ((`lease_owner` IS NULL) = (`lease_until` IS NULL)),
    CONSTRAINT `chk_wf_collab_outbox_completion` CHECK
        ((`status` IN ('PENDING', 'DELIVERING', 'RETRYING') AND `complete_time` IS NULL)
         OR (`status` IN ('PROCESSED', 'DEAD_LETTER', 'CANCELLED') AND `complete_time` IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'SendTask 事务 outbox、顺序投递与补偿台账';

CREATE TABLE IF NOT EXISTS `wf_collaboration_message_audit`
(
    `audit_id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '协作消息审计主键',
    `message_id`        CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '入站或出站消息主键',
    `direction`         VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'INBOUND 或 OUTBOUND',
    `action`            VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'RECEIVE、CLAIM、DELIVER、RETRY、DEAD_LETTER、COMPENSATE 或 CANCEL',
    `actor_type`        VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'INTEGRATION、SYSTEM 或 USER',
    `actor_id`          VARCHAR(64)  NOT NULL COMMENT '脱敏操作人、凭据主键或 worker 标识',
    `from_status`       VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '动作前状态',
    `to_status`         VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '动作后状态',
    `attempt_no`        INT          NOT NULL DEFAULT 0 COMMENT '动作对应投递次数',
    `error_code`        VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '稳定失败编码',
    `summary`           VARCHAR(512) NOT NULL DEFAULT '' COMMENT '不含 Token 和业务正文的审计摘要',
    `create_time`       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '审计时间',
    PRIMARY KEY (`audit_id`),
    KEY `idx_wf_collab_audit_message` (`message_id`, `direction`, `audit_id`),
    KEY `idx_wf_collab_audit_status` (`direction`, `to_status`, `create_time`),
    CONSTRAINT `chk_wf_collab_audit_direction` CHECK (`direction` IN ('INBOUND', 'OUTBOUND')),
    CONSTRAINT `chk_wf_collab_audit_actor` CHECK (`actor_type` IN ('INTEGRATION', 'SYSTEM', 'USER')),
    CONSTRAINT `chk_wf_collab_audit_attempt` CHECK (`attempt_no` >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '协作消息逐次状态、重试和人工补偿审计';

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
    `source_type`     VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' COMMENT '抄送来源：MANUAL、AUTO 或 MANUAL_AUTO',
    `trigger_type`    VARCHAR(32)  NOT NULL DEFAULT 'MANUAL_COMPLETE' COMMENT '服务端固定的抄送触发类型',
    `trigger_node_id` VARCHAR(64)           DEFAULT NULL COMMENT '触发抄送的 BPMN 节点主键',
    `trigger_node_name` VARCHAR(255)        DEFAULT NULL COMMENT '触发抄送的 BPMN 节点名称快照',
    `read_status`     CHAR(1)      NOT NULL DEFAULT '0' COMMENT '阅读状态：0 未读，1 已读',
    `read_time`       DATETIME(3)           DEFAULT NULL COMMENT '首次阅读时间',
    `create_by`       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '创建者账号',
    `create_time`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_by`       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '更新者账号',
    `update_time`     DATETIME              DEFAULT NULL COMMENT '更新时间',
    `remark`          VARCHAR(255)          DEFAULT NULL COMMENT '备注',
    `del_flag`        CHAR(1)      NOT NULL DEFAULT '0' COMMENT '逻辑删除标志：0 有效，2 已删除',
    PRIMARY KEY (`copy_id`),
    UNIQUE KEY `uk_wf_copy_event_user` (`copy_event_id`, `user_id`),
    KEY `idx_wf_copy_user_status_time` (`user_id`, `del_flag`, `read_status`, `create_time`),
    KEY `idx_wf_copy_retention` (`create_time`, `copy_id`),
    KEY `idx_wf_copy_instance` (`instance_id`, `del_flag`),
    KEY `idx_wf_copy_task` (`task_id`, `del_flag`),
    KEY `idx_wf_copy_deployment` (`deployment_id`, `del_flag`),
    CONSTRAINT `chk_wf_copy_del_flag` CHECK (`del_flag` IN ('0', '2')),
    CONSTRAINT `chk_wf_copy_source_type` CHECK (`source_type` IN ('MANUAL', 'AUTO', 'MANUAL_AUTO')),
    CONSTRAINT `chk_wf_copy_trigger_type` CHECK (`trigger_type` IN (
        'MANUAL_COMPLETE', 'MANUAL_REJECT', 'MANUAL_RETURN', 'MANUAL_DELEGATE',
        'MANUAL_RESOLVE', 'MANUAL_TRANSFER', 'NODE_ARRIVED', 'NODE_COMPLETED',
        'PROCESS_COMPLETED')),
    CONSTRAINT `chk_wf_copy_read_state` CHECK ((`read_status` = '0' AND `read_time` IS NULL)
        OR (`read_status` = '1' AND `read_time` IS NOT NULL))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '流程抄送记录';

CREATE TABLE IF NOT EXISTS `wf_process_draft`
(
    `draft_id`                     CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端生成的申请草稿 UUID',
    `owner_user_id`                BIGINT       NOT NULL COMMENT '草稿所属正式用户主键',
    `process_definition_id`        VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '草稿绑定的 Flowable 精确流程定义主键',
    `process_definition_key`       VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '流程定义稳定 key 快照',
    `process_definition_version`   INT          NOT NULL COMMENT '草稿绑定的流程定义版本号',
    `deployment_id`                VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '流程定义所属部署主键',
    `process_name`                 VARCHAR(255) NOT NULL COMMENT '流程名称快照',
    `source_type`                  VARCHAR(16)  NOT NULL COMMENT '表单快照来源：TEMPLATE 或 EMBEDDED',
    `form_id`                      BIGINT                DEFAULT NULL COMMENT '模板表单主键；内嵌表单为空',
    `form_key`                     VARCHAR(128) NOT NULL COMMENT '开始节点部署表单键快照',
    `start_node_key`               VARCHAR(255) NOT NULL COMMENT '开始节点 key 快照',
    `form_name`                    VARCHAR(64)  NOT NULL COMMENT '开始表单名称快照',
    `node_name`                    VARCHAR(255) NOT NULL DEFAULT '' COMMENT '开始节点名称快照',
    `snapshot_create_time`         DATETIME(3)  NOT NULL COMMENT '原部署表单快照创建时间',
    `form_snapshot`                LONGTEXT     NOT NULL COMMENT '创建草稿时从部署表单复制的不可变 JSON 快照',
    `form_snapshot_sha256`         CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原始表单快照 SHA-256',
    `start_multi_instance_assignments` LONGTEXT NOT NULL COMMENT '部署 BPMN 发起会签或或签成员字段不可变 JSON 快照',
    `form_values`                  LONGTEXT     NOT NULL COMMENT '已通过草稿类型校验的字段值 JSON',
    `multi_instance_user_ids`      LONGTEXT     NOT NULL COMMENT '按活动保存的发起会签或或签成员 JSON',
    `business_key`                 VARCHAR(255)          DEFAULT NULL COMMENT '用户填写的可选业务主键',
    `draft_status`                 VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '草稿状态：ACTIVE、SUBMITTED、DELETED',
    `revision_no`                  BIGINT       NOT NULL DEFAULT 1 COMMENT '从 1 开始的乐观锁版本',
    `submitted_process_instance_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '成功提交后唯一 Flowable 流程实例主键',
    `submitted_time`               DATETIME(3)           DEFAULT NULL COMMENT '成功提交时间',
    `deleted_time`                 DATETIME(3)           DEFAULT NULL COMMENT '用户删除时间',
    `create_time`                  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '草稿创建时间',
    `update_time`                  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '草稿最后更新时间',
    PRIMARY KEY (`draft_id`),
    UNIQUE KEY `uk_wf_process_draft_instance` (`submitted_process_instance_id`),
    KEY `idx_wf_process_draft_owner_status_time`
        (`owner_user_id`, `draft_status`, `update_time`, `draft_id`),
    KEY `idx_wf_process_draft_owner_process_time`
        (`owner_user_id`, `process_definition_key`, `update_time`, `draft_id`),
    KEY `idx_wf_process_draft_definition_version`
        (`process_definition_key`, `process_definition_version`, `draft_status`),
    KEY `idx_wf_process_draft_deployment_status`
        (`deployment_id`, `draft_status`),
    KEY `idx_wf_process_draft_submitted_retention`
        (`draft_status`, `submitted_time`, `draft_id`),
    KEY `idx_wf_process_draft_deleted_retention`
        (`draft_status`, `deleted_time`, `draft_id`),
    CONSTRAINT `chk_wf_process_draft_id` CHECK
        (`draft_id` REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    CONSTRAINT `chk_wf_process_draft_owner` CHECK (`owner_user_id` > 0),
    CONSTRAINT `chk_wf_process_draft_definition` CHECK
        (`process_definition_version` > 0
         AND CHAR_LENGTH(`process_definition_id`) BETWEEN 1 AND 64
         AND CHAR_LENGTH(`process_definition_key`) BETWEEN 1 AND 255
         AND CHAR_LENGTH(`deployment_id`) BETWEEN 1 AND 64),
    CONSTRAINT `chk_wf_process_draft_source` CHECK
    (
        (`source_type` = 'TEMPLATE' AND `form_id` IS NOT NULL AND `form_id` > 0)
        OR (`source_type` = 'EMBEDDED' AND `form_id` IS NULL)
    ),
    CONSTRAINT `chk_wf_process_draft_snapshot_json` CHECK (JSON_VALID(`form_snapshot`)),
    CONSTRAINT `chk_wf_process_draft_assignment_json` CHECK
        (JSON_VALID(`start_multi_instance_assignments`)
         AND JSON_TYPE(`start_multi_instance_assignments`) = 'ARRAY'),
    CONSTRAINT `chk_wf_process_draft_values_json` CHECK (JSON_VALID(`form_values`)),
    CONSTRAINT `chk_wf_process_draft_members_json` CHECK
        (JSON_VALID(`multi_instance_user_ids`)
         AND JSON_TYPE(`multi_instance_user_ids`) = 'OBJECT'),
    CONSTRAINT `chk_wf_process_draft_snapshot_hash` CHECK
        (`form_snapshot_sha256` REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT `chk_wf_process_draft_revision` CHECK (`revision_no` > 0),
    CONSTRAINT `chk_wf_process_draft_status` CHECK
        (`draft_status` IN ('ACTIVE', 'SUBMITTED', 'DELETED')),
    CONSTRAINT `chk_wf_process_draft_lifecycle` CHECK
    (
        (`draft_status` = 'ACTIVE'
            AND `submitted_process_instance_id` IS NULL AND `submitted_time` IS NULL
            AND `deleted_time` IS NULL)
        OR (`draft_status` = 'SUBMITTED'
            AND `submitted_process_instance_id` IS NOT NULL AND `submitted_time` IS NOT NULL
            AND `deleted_time` IS NULL)
        OR (`draft_status` = 'DELETED'
            AND `submitted_process_instance_id` IS NULL AND `submitted_time` IS NULL
            AND `deleted_time` IS NOT NULL)
    ),
    CONSTRAINT `chk_wf_process_draft_times` CHECK
        (`snapshot_create_time` <= `create_time`
         AND `update_time` >= `create_time`
         AND (`submitted_time` IS NULL OR `submitted_time` >= `create_time`)
         AND (`deleted_time` IS NULL OR `deleted_time` >= `create_time`))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '当前用户申请草稿及不可变部署表单快照';

CREATE TABLE IF NOT EXISTS `wf_attachment_quota_guard`
(
    `owner_user_id` BIGINT      NOT NULL COMMENT '配额互斥主键：正式正数用户主键',
    `create_time`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次创建配额互斥行的时间',
    PRIMARY KEY (`owner_user_id`),
    CONSTRAINT `chk_wf_attachment_quota_guard_owner` CHECK (`owner_user_id` > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '工作流附件用户配额事务互斥行';

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
    `attachment_status`   VARCHAR(16)  NOT NULL DEFAULT 'TEMP' COMMENT '附件状态：TEMP、DRAFT、BOUND、EXPIRED、DELETED',
    `expire_time`         DATETIME(3)  NOT NULL COMMENT '临时附件失效时间；绑定后仅作上传审计',
    `draft_id`            CHAR(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT 'DRAFT 状态绑定的申请草稿 UUID',
    `process_instance_id` VARCHAR(64)           DEFAULT NULL COMMENT '绑定的 Flowable 流程实例主键',
    `task_id`             VARCHAR(64)           DEFAULT NULL COMMENT '可选的 Flowable 任务主键',
    `node_key`            VARCHAR(255)          DEFAULT NULL COMMENT '提交附件的 BPMN 节点 key',
    `bound_time`          DATETIME(3)           DEFAULT NULL COMMENT '成功绑定流程对象的时间',
    `storage_deleted_time` DATETIME(3)          DEFAULT NULL COMMENT '私有文件已物理删除的时间',
    `cleanup_retry_count` INT          NOT NULL DEFAULT 0 COMMENT '物理清理连续失败并已调度重试的次数',
    `cleanup_next_retry_time` DATETIME(3)       DEFAULT NULL COMMENT '下次允许进入物理清理候选的时间',
    `cleanup_last_error_code` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '最近一次清理失败的稳定脱敏错误码',
    `cleanup_claim_token` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '当前清理批次 UUID 令牌',
    `cleanup_lease_until` DATETIME(3)           DEFAULT NULL COMMENT '清理领取租约到期时间',
    `create_time`         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '上传完成并登记元数据的时间',
    `update_time`         DATETIME(3)           DEFAULT NULL COMMENT '最后状态更新时间',
    PRIMARY KEY (`attachment_id`),
    UNIQUE KEY `uk_wf_attachment_storage_key` (`storage_key`),
    KEY `idx_wf_attachment_owner_status_expire` (`owner_user_id`, `attachment_status`, `expire_time`),
    KEY `idx_wf_attachment_status_expire` (`attachment_status`, `expire_time`),
    KEY `idx_wf_attachment_cleanup_due`
        (`attachment_status`, `storage_deleted_time`, `cleanup_next_retry_time`,
         `cleanup_lease_until`, `expire_time`, `attachment_id`),
    KEY `idx_wf_attachment_metadata_retention` (`storage_deleted_time`, `attachment_id`),
    KEY `idx_wf_attachment_draft_field` (`draft_id`, `field_name`, `attachment_status`),
    KEY `idx_wf_attachment_instance_field` (`process_instance_id`, `field_name`, `attachment_status`),
    CONSTRAINT `fk_wf_attachment_draft` FOREIGN KEY (`draft_id`)
        REFERENCES `wf_process_draft` (`draft_id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `chk_wf_attachment_status` CHECK
        (`attachment_status` IN ('TEMP', 'DRAFT', 'BOUND', 'EXPIRED', 'DELETED')),
    CONSTRAINT `chk_wf_attachment_file_size` CHECK (`file_size` > 0),
    CONSTRAINT `chk_wf_attachment_sha256` CHECK (`sha256` REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT `chk_wf_attachment_state_relation` CHECK
    (
        (`attachment_status` = 'BOUND'
            AND `draft_id` IS NULL
            AND `process_instance_id` IS NOT NULL
            AND `node_key` IS NOT NULL
            AND `bound_time` IS NOT NULL
            AND `storage_deleted_time` IS NULL)
        OR
        (`attachment_status` = 'DRAFT'
            AND `draft_id` IS NOT NULL
            AND `process_instance_id` IS NULL
            AND `task_id` IS NULL
            AND `node_key` IS NULL
            AND `bound_time` IS NULL
            AND `storage_deleted_time` IS NULL)
        OR
        (`attachment_status` IN ('TEMP', 'EXPIRED', 'DELETED')
            AND `draft_id` IS NULL
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
    ),
    CONSTRAINT `chk_wf_attachment_cleanup_lease` CHECK
    (
        (`cleanup_claim_token` IS NULL AND `cleanup_lease_until` IS NULL)
        OR
        (`cleanup_claim_token` REGEXP
            '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
            AND `cleanup_lease_until` IS NOT NULL
            AND `storage_deleted_time` IS NULL
            AND `attachment_status` IN ('EXPIRED', 'DELETED'))
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '工作流私有附件元数据';

-- BPMN 业务错误与升级正式目录：稳定编码进入作者 XML，名称和通知策略在部署快照中冻结。
CREATE TABLE IF NOT EXISTS `wf_bpmn_event_code`
(
    `event_code_id`       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '错误或升级编码目录主键',
    `event_type`          VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'ERROR 或 ESCALATION',
    `event_code`          VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'BPMN 捕获匹配稳定编码',
    `event_name`          VARCHAR(128) NOT NULL COMMENT '用户可见事件名称',
    `notification_policy` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NONE' COMMENT 'NONE 或 INITIATOR',
    `status`              VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED 或 DISABLED',
    `description`         VARCHAR(500)          DEFAULT NULL COMMENT '业务含义和适用范围',
    `create_by`           VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '创建人正式用户主键',
    `create_time`         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_by`           VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '最后修改人正式用户主键',
    `update_time`         DATETIME(3)           DEFAULT NULL COMMENT '最后修改时间',
    PRIMARY KEY (`event_code_id`),
    UNIQUE KEY `uk_wf_bpmn_event_code` (`event_type`, `event_code`),
    KEY `idx_wf_bpmn_event_code_status` (`event_type`, `status`, `event_code`),
    CONSTRAINT `chk_wf_bpmn_event_code_type` CHECK (`event_type` IN ('ERROR', 'ESCALATION')),
    CONSTRAINT `chk_wf_bpmn_event_code_value` CHECK (`event_code` REGEXP '^[A-Z][A-Z0-9_.-]{1,63}$'),
    CONSTRAINT `chk_wf_bpmn_event_code_notice` CHECK (`notification_policy` IN ('NONE', 'INITIATOR')),
    CONSTRAINT `chk_wf_bpmn_event_code_status` CHECK (`status` IN ('ENABLED', 'DISABLED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'BPMN 业务错误与升级编码正式目录';

-- 独立运行审计不依赖 Flowable 历史清理周期；未匹配 Error 导致主事务回滚时仍保留诊断证据。
CREATE TABLE IF NOT EXISTS `wf_bpmn_event_audit`
(
    `audit_id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '运行审计主键',
    `idempotency_key`       CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '部署实例执行节点事件复合 SHA-256',
    `deployment_id`         VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Flowable 部署主键',
    `process_instance_id`   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Flowable 流程实例主键',
    `process_definition_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Flowable 流程定义主键',
    `execution_id`          VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '触发执行主键',
    `source_element_id`     VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '受控产生节点标识',
    `source_type`           VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SERVICE_TASK、HTTP、SQL、DMN 或 MANUAL',
    `event_type`            VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'ERROR 或 ESCALATION',
    `event_code`            VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '冻结业务编码',
    `event_name`            VARCHAR(128) NOT NULL COMMENT '部署时冻结事件名称',
    `match_status`          VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'CAPTURED 或 UNMATCHED',
    `boundary_event_id`     VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '精确匹配边界事件标识',
    `interrupting`          TINYINT(1)           DEFAULT NULL COMMENT '匹配边界是否中断附着活动',
    `message_summary`       VARCHAR(500)          DEFAULT NULL COMMENT '受控标量业务摘要，不保存异常堆栈',
    `initiator_user_id`     VARCHAR(64)           DEFAULT NULL COMMENT '通知使用的流程发起人主键',
    `create_time`           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '触发时间',
    PRIMARY KEY (`audit_id`),
    UNIQUE KEY `uk_wf_bpmn_event_audit_idempotency` (`idempotency_key`),
    KEY `idx_wf_bpmn_event_audit_instance` (`process_instance_id`, `audit_id`),
    KEY `idx_wf_bpmn_event_audit_code` (`event_type`, `event_code`, `audit_id`),
    KEY `idx_wf_bpmn_event_audit_retention` (`create_time`, `audit_id`),
    CONSTRAINT `chk_wf_bpmn_event_audit_hash` CHECK (`idempotency_key` REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT `chk_wf_bpmn_event_audit_source` CHECK (`source_type` IN ('SERVICE_TASK', 'HTTP', 'SQL', 'DMN', 'MANUAL')),
    CONSTRAINT `chk_wf_bpmn_event_audit_type` CHECK (`event_type` IN ('ERROR', 'ESCALATION')),
    CONSTRAINT `chk_wf_bpmn_event_audit_match` CHECK (`match_status` IN ('CAPTURED', 'UNMATCHED')),
    CONSTRAINT `chk_wf_bpmn_event_audit_boundary` CHECK
    (
        (`match_status` = 'CAPTURED' AND `boundary_event_id` IS NOT NULL AND `interrupting` IS NOT NULL)
        OR (`match_status` = 'UNMATCHED' AND `boundary_event_id` IS NULL AND `interrupting` IS NULL)
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'BPMN 业务错误与升级独立运行审计';

CREATE TABLE IF NOT EXISTS `wf_notification_policy`
(
    `policy_id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '通知策略主键',
    `scope_type`            VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'DEFAULT、PROCESS 或 NODE',
    `process_definition_key` VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '流程定义 key，默认策略为空',
    `task_definition_key`   VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '节点 key，仅 NODE 策略必填',
    `event_type`            VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '普通审批生命周期事件',
    `scope_key`             VARCHAR(540) CHARACTER SET ascii COLLATE ascii_bin AS
        (CONCAT(`scope_type`, ':', COALESCE(`process_definition_key`, ''), ':', COALESCE(`task_definition_key`, ''))) STORED COMMENT '空值安全的策略作用域自然键',
    `recipient_rules`       VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '逗号分隔的固定接收人规则',
    `channels`              VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'INBOX、EMAIL、SMS 或固定顺序组合',
    `sms_template_id`       VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '短信供应商审核模板 ID',
    `title_template`        VARCHAR(160) NOT NULL COMMENT '仅允许白名单变量的标题模板',
    `content_template`      VARCHAR(700) NOT NULL COMMENT '仅允许白名单变量的正文模板',
    `max_attempts`          TINYINT UNSIGNED NOT NULL DEFAULT 6 COMMENT '每个通道最大投递次数',
    `status`                VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED 或 DISABLED',
    `revision`              INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `create_by`             VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '创建人',
    `create_time`           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_by`             VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '更新人',
    `update_time`           DATETIME(3)           DEFAULT NULL,
    PRIMARY KEY (`policy_id`),
    UNIQUE KEY `uk_wf_notification_policy_scope` (`scope_key`, `event_type`),
    KEY `idx_wf_notification_policy_match` (`event_type`, `status`, `process_definition_key`, `task_definition_key`),
    CONSTRAINT `chk_wf_notification_policy_scope` CHECK
    (
        (`scope_type` = 'DEFAULT' AND `process_definition_key` IS NULL AND `task_definition_key` IS NULL)
        OR (`scope_type` = 'PROCESS' AND `process_definition_key` IS NOT NULL AND `task_definition_key` IS NULL)
        OR (`scope_type` = 'NODE' AND `process_definition_key` IS NOT NULL AND `task_definition_key` IS NOT NULL)
    ),
    CONSTRAINT `chk_wf_notification_policy_channels` CHECK (`channels` IN
        ('INBOX', 'EMAIL', 'SMS', 'INBOX,EMAIL', 'INBOX,SMS', 'EMAIL,SMS', 'INBOX,EMAIL,SMS')),
    CONSTRAINT `chk_wf_notification_policy_sms_template` CHECK
        ((`channels` LIKE '%SMS%' AND `sms_template_id` IS NOT NULL AND `sms_template_id` <> '')
            OR (`channels` NOT LIKE '%SMS%' AND `sms_template_id` IS NULL)),
    CONSTRAINT `chk_wf_notification_policy_status` CHECK (`status` IN ('ENABLED', 'DISABLED')),
    CONSTRAINT `chk_wf_notification_policy_attempts` CHECK (`max_attempts` BETWEEN 1 AND 20)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '普通审批流程与节点通知策略';

CREATE TABLE IF NOT EXISTS `wf_notification_preference`
(
    `user_id`       BIGINT      NOT NULL COMMENT '正式用户主键',
    `inbox_enabled` TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '是否接收站内通知',
    `email_enabled` TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '是否接收邮件通知',
    `sms_enabled`   TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否接收短信通知',
    `revision`      INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `update_time`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`user_id`),
    CONSTRAINT `fk_wf_notification_preference_user` FOREIGN KEY (`user_id`)
        REFERENCES `sys_user` (`user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '用户审批通知通道偏好';

CREATE TABLE IF NOT EXISTS `wf_notification_outbox`
(
    `outbox_id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '出站主键',
    `idempotency_key`        CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '业务事件、接收人和通道摘要',
    `source_type`            VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'APPROVAL、SLA 或 BPMN_EVENT',
    `source_id`              VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '普通审批事件键或关联业务审计主键',
    `event_type`             VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源域内稳定事件类型',
    `channel`                VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'INBOX、EMAIL 或 SMS',
    `recipient_user_id`      BIGINT       NOT NULL COMMENT '正式接收人主键',
    `process_definition_key` VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `process_instance_id`    VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `task_id`                VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    `task_definition_key`    VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    `actor_user_id`          VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    `title`                  VARCHAR(160) NOT NULL,
    `content`                VARCHAR(700) NOT NULL,
    `sms_template_id`        VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '登记时冻结的短信模板 ID',
    `route_path`             VARCHAR(500) NOT NULL COMMENT '站内安全相对路由',
    `status`                 VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    `delivery_cycle`         SMALLINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '补偿后递增且不可回退的投递周期',
    `attempt_count`          TINYINT UNSIGNED NOT NULL DEFAULT 0,
    `total_attempt_count`    INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '跨补偿周期累计且不可回退的投递次数',
    `max_attempts`           TINYINT UNSIGNED NOT NULL,
    `next_attempt_at`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `lease_owner`            VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    `lease_expires_at`       DATETIME(3) DEFAULT NULL,
    `last_error_code`        VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    `last_error_summary`     VARCHAR(500) DEFAULT NULL COMMENT '脱敏失败摘要',
    `revision`               INT UNSIGNED NOT NULL DEFAULT 0,
    `create_time`            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `processed_time`         DATETIME(3) DEFAULT NULL,
    PRIMARY KEY (`outbox_id`),
    UNIQUE KEY `uk_wf_notification_outbox_idempotency` (`idempotency_key`),
    KEY `idx_wf_notification_outbox_source` (`source_type`, `source_id`, `channel`, `outbox_id`),
    KEY `idx_wf_notification_outbox_due` (`status`, `next_attempt_at`, `lease_expires_at`, `outbox_id`),
    KEY `idx_wf_notification_outbox_retention` (`status`, `processed_time`, `outbox_id`),
    KEY `idx_wf_notification_outbox_instance` (`process_instance_id`, `outbox_id`),
    CONSTRAINT `fk_wf_notification_outbox_user` FOREIGN KEY (`recipient_user_id`)
        REFERENCES `sys_user` (`user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_wf_notification_outbox_hash` CHECK (`idempotency_key` REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT `chk_wf_notification_outbox_source` CHECK
        (`source_type` IN ('APPROVAL', 'SLA', 'BPMN_EVENT') AND `source_id` <> ''),
    CONSTRAINT `chk_wf_notification_outbox_channel` CHECK (`channel` IN ('INBOX', 'EMAIL', 'SMS')),
    CONSTRAINT `chk_wf_notification_outbox_sms_template` CHECK
        ((`channel` = 'SMS' AND `sms_template_id` IS NOT NULL AND `sms_template_id` <> '')
            OR (`channel` <> 'SMS' AND `sms_template_id` IS NULL)),
    CONSTRAINT `chk_wf_notification_outbox_status` CHECK (`status` IN ('PENDING', 'RETRYING', 'DELIVERING', 'PROCESSED', 'DEAD_LETTER', 'CANCELLED')),
    CONSTRAINT `chk_wf_notification_outbox_attempts` CHECK (`max_attempts` BETWEEN 1 AND 20 AND `attempt_count` <= `max_attempts`),
    CONSTRAINT `chk_wf_notification_outbox_sequence` CHECK (`delivery_cycle` >= 1 AND `total_attempt_count` >= `attempt_count`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '工作流统一通知可靠 outbox';

CREATE TABLE IF NOT EXISTS `wf_notification_inbox`
(
    `notification_id`    BIGINT       NOT NULL AUTO_INCREMENT,
    `outbox_id`          BIGINT       NOT NULL COMMENT '创建收件箱时的 outbox 软关联主键',
    `notification_key`   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源类型、来源主键和事件类型生成的稳定 SHA-256 关联键',
    `source_type`        VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'APPROVAL、SLA 或 BPMN_EVENT',
    `source_id`          VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源域内稳定业务事实主键',
    `recipient_user_id`  BIGINT       NOT NULL,
    `event_type`         VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `title`              VARCHAR(160) NOT NULL,
    `content`            VARCHAR(700) NOT NULL,
    `process_instance_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `task_id`            VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    `route_path`         VARCHAR(500) NOT NULL,
    `read_status`        VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNREAD',
    `create_time`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `read_time`          DATETIME(3) DEFAULT NULL,
    PRIMARY KEY (`notification_id`),
    UNIQUE KEY `uk_wf_notification_inbox_notification` (`notification_key`, `recipient_user_id`),
    KEY `idx_wf_notification_inbox_user` (`recipient_user_id`, `read_status`, `notification_id`),
    KEY `idx_wf_notification_inbox_source`
        (`source_type`, `source_id`, `recipient_user_id`, `notification_id`),
    KEY `idx_wf_notification_inbox_retention` (`read_status`, `read_time`, `notification_id`),
    CONSTRAINT `fk_wf_notification_inbox_user` FOREIGN KEY (`recipient_user_id`)
        REFERENCES `sys_user` (`user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_wf_notification_inbox_hash` CHECK
        (`notification_key` REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT `chk_wf_notification_inbox_source` CHECK
        (`source_type` IN ('APPROVAL', 'SLA', 'BPMN_EVENT') AND `source_id` <> ''),
    CONSTRAINT `chk_wf_notification_inbox_status` CHECK (`read_status` IN ('UNREAD', 'READ')),
    CONSTRAINT `chk_wf_notification_inbox_read` CHECK
    ((`read_status` = 'UNREAD' AND `read_time` IS NULL) OR (`read_status` = 'READ' AND `read_time` IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '工作流统一用户站内通知';

INSERT INTO `wf_notification_policy`
    (`scope_type`, `process_definition_key`, `task_definition_key`, `event_type`,
     `recipient_rules`, `channels`, `title_template`, `content_template`, `max_attempts`,
     `status`, `create_by`)
VALUES
    ('DEFAULT', NULL, NULL, 'TASK_ARRIVED', 'TASK_RECIPIENT', 'INBOX',
     '新待办：{{taskName}}', '流程“{{processName}}”有新的待办任务“{{taskName}}”。', 6, 'ENABLED', 'system'),
    ('DEFAULT', NULL, NULL, 'TASK_CLAIMED', 'TASK_RECIPIENT', 'INBOX',
     '任务已认领：{{taskName}}', '您已成为流程“{{processName}}”任务“{{taskName}}”的办理人。', 6, 'ENABLED', 'system'),
    ('DEFAULT', NULL, NULL, 'TASK_UNCLAIMED', 'TASK_RECIPIENT', 'INBOX',
     '任务已释放：{{taskName}}', '流程“{{processName}}”任务“{{taskName}}”已重新进入待认领状态。', 6, 'ENABLED', 'system'),
    ('DEFAULT', NULL, NULL, 'TASK_DELEGATED', 'TASK_RECIPIENT', 'INBOX',
     '任务已委派：{{taskName}}', '流程“{{processName}}”任务“{{taskName}}”已委派给您。', 6, 'ENABLED', 'system'),
    ('DEFAULT', NULL, NULL, 'TASK_DELEGATION_RESOLVED', 'TASK_RECIPIENT', 'INBOX',
     '委派任务已归还：{{taskName}}', '流程“{{processName}}”任务“{{taskName}}”已归还原办理人。', 6, 'ENABLED', 'system'),
    ('DEFAULT', NULL, NULL, 'TASK_TRANSFERRED', 'TASK_RECIPIENT', 'INBOX',
     '任务已转办：{{taskName}}', '流程“{{processName}}”任务“{{taskName}}”已转办给您。', 6, 'ENABLED', 'system'),
    ('DEFAULT', NULL, NULL, 'TASK_RETURNED', 'TASK_RECIPIENT', 'INBOX',
     '申请已退回修改', '流程“{{processName}}”已退回，请修改后重新提交。', 6, 'ENABLED', 'system'),
    ('DEFAULT', NULL, NULL, 'TASK_RESUBMITTED', 'TASK_RECIPIENT', 'INBOX',
     '申请已重新提交：{{taskName}}', '流程“{{processName}}”已修改并重新提交，请继续处理“{{taskName}}”。', 6, 'ENABLED', 'system'),
    ('DEFAULT', NULL, NULL, 'TASK_COMPLETED', 'INITIATOR', 'INBOX',
     '审批节点已完成：{{taskName}}', '流程“{{processName}}”的节点“{{taskName}}”已完成。', 6, 'ENABLED', 'system'),
    ('DEFAULT', NULL, NULL, 'PROCESS_COMPLETED', 'INITIATOR', 'INBOX',
     '流程已完成：{{processName}}', '您的流程“{{processName}}”已完成。', 6, 'ENABLED', 'system'),
    ('DEFAULT', NULL, NULL, 'PROCESS_CANCELED', 'INITIATOR', 'INBOX',
     '流程已取消：{{processName}}', '流程“{{processName}}”已取消。', 6, 'ENABLED', 'system'),
    ('DEFAULT', NULL, NULL, 'PROCESS_REJECTED', 'INITIATOR', 'INBOX',
     '流程已驳回：{{processName}}', '流程“{{processName}}”已驳回并结束。', 6, 'ENABLED', 'system'),
    ('DEFAULT', NULL, NULL, 'PROCESS_TERMINATED', 'INITIATOR', 'INBOX',
     '流程已终止：{{processName}}', '流程“{{processName}}”已由管理员终止。', 6, 'ENABLED', 'system'),
    ('DEFAULT', NULL, NULL, 'MANUAL_URGE', 'TASK_RECIPIENT', 'INBOX',
     '审批催办：{{taskName}}', '发起人提醒您处理流程“{{processName}}”的待办“{{taskName}}”。', 6, 'ENABLED', 'system'),
    ('DEFAULT', NULL, NULL, 'COPY_CREATED', 'TASK_RECIPIENT', 'INBOX',
     '流程抄送：{{processName}}', '您收到流程“{{processName}}”的审批抄送。', 6, 'ENABLED', 'system')
ON DUPLICATE KEY UPDATE `policy_id` = `policy_id`;

INSERT INTO `wf_bpmn_event_code`
    (`event_type`, `event_code`, `event_name`, `notification_policy`, `status`, `description`,
     `create_by`, `create_time`, `update_by`, `update_time`)
SELECT 'ERROR', 'APPROVAL_BUSINESS_ERROR', '审批业务校验失败', 'INITIATOR', 'ENABLED',
       '用于明确可预期且需要进入人工纠错路径的审批业务错误',
       'system', current_timestamp(3), '', NULL
WHERE NOT EXISTS
(
    SELECT 1 FROM `wf_bpmn_event_code`
    WHERE `event_type` = 'ERROR' AND `event_code` = 'APPROVAL_BUSINESS_ERROR'
);

INSERT INTO `wf_bpmn_event_code`
    (`event_type`, `event_code`, `event_name`, `notification_policy`, `status`, `description`,
     `create_by`, `create_time`, `update_by`, `update_time`)
SELECT 'ESCALATION', 'APPROVAL_ESCALATION', '审批升级处理', 'INITIATOR', 'ENABLED',
       '用于需要保留主处理路径并并行通知升级办理人的非中断升级场景',
       'system', current_timestamp(3), '', NULL
WHERE NOT EXISTS
(
    SELECT 1 FROM `wf_bpmn_event_code`
    WHERE `event_type` = 'ESCALATION' AND `event_code` = 'APPROVAL_ESCALATION'
);

INSERT INTO `wf_bpmn_extension`
    (`extension_key`, `extension_name`, `extension_type`, `status`, `description`,
     `create_by`, `create_time`, `update_by`, `update_time`)
SELECT 'approva.raise-bpmn-event', '产生 BPMN 业务错误或升级', 'JAVA', 'ENABLED',
       '仅按正式编码目录显式产生 BPMN Error 或 Escalation，普通 Java 异常不会被转换',
       'system', current_timestamp(3), '', NULL
WHERE NOT EXISTS
(
    SELECT 1 FROM `wf_bpmn_extension` WHERE `extension_key` = 'approva.raise-bpmn-event'
);

INSERT INTO `wf_bpmn_extension_version`
    (`extension_id`, `version_no`, `implementation_key`, `config_schema`,
     `checksum`, `create_by`, `create_time`)
SELECT e.extension_id, 1, 'RAISE_BPMN_EVENT',
       CAST('{"additionalProperties":false,"properties":{"conditionVariable":{"pattern":"^[A-Za-z_][A-Za-z0-9_]{0,127}$","type":"string"},"eventCode":{"pattern":"^[A-Z][A-Z0-9_.-]{1,63}$","type":"string"},"eventType":{"enum":["ERROR","ESCALATION"],"type":"string"},"expectedValue":{"maxLength":256,"type":"string"},"messageVariable":{"pattern":"^[A-Za-z_][A-Za-z0-9_]{0,127}$","type":"string"},"operator":{"enum":["ALWAYS","EQUALS","NOT_EQUALS","TRUE","FALSE","PRESENT","EMPTY"],"type":"string"},"sourceType":{"enum":["SERVICE_TASK","HTTP","SQL","DMN","MANUAL"],"type":"string"}},"required":["eventType","eventCode","sourceType"],"type":"object"}' AS JSON),
       '7a9c7346819c5065a4473b84cb967f830433c938307a0eebd07c4510dd382c6b', 'system', current_timestamp(3)
FROM `wf_bpmn_extension` e
WHERE e.extension_key = 'approva.raise-bpmn-event'
  AND NOT EXISTS
  (
      SELECT 1 FROM `wf_bpmn_extension_version` v
      WHERE v.extension_id = e.extension_id AND v.version_no = 1
  );
