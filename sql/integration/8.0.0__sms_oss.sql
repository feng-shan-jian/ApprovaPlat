-- ApprovaPlat 短信与 S3 兼容 OSS 正式数据结构及菜单权限。
-- 本脚本可重复执行；执行前仍应按正式变更流程完成整库备份。

CREATE TABLE IF NOT EXISTS `sys_sms_config`
(
    `config_id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '短信配置主键',
    `config_name`        VARCHAR(64)  NOT NULL COMMENT '配置显示名称',
    `provider`           VARCHAR(16)  NOT NULL COMMENT 'ALIYUN 或 TENCENT',
    `access_key_id`      VARCHAR(128) NOT NULL COMMENT '供应商访问密钥 ID',
    `access_key_secret`  VARCHAR(256) NOT NULL COMMENT '供应商访问密钥，仅服务端读取',
    `sign_name`          VARCHAR(64)  NOT NULL COMMENT '审核通过的短信签名',
    `sdk_app_id`         VARCHAR(64)           DEFAULT NULL COMMENT '腾讯云短信应用 ID',
    `region`             VARCHAR(64)           DEFAULT NULL COMMENT '腾讯云地域',
    `status`             CHAR(1)      NOT NULL DEFAULT '1' COMMENT '0 启用，1 停用',
    `create_by`          VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '创建者账号',
    `create_time`        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_by`          VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '更新者账号',
    `update_time`        DATETIME(3)           DEFAULT NULL COMMENT '更新时间',
    `remark`             VARCHAR(500)          DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`config_id`),
    UNIQUE KEY `uk_sys_sms_config_name` (`config_name`),
    KEY `idx_sys_sms_config_status` (`status`),
    CONSTRAINT `chk_sys_sms_config_provider` CHECK (`provider` IN ('ALIYUN', 'TENCENT')),
    CONSTRAINT `chk_sys_sms_config_status` CHECK (`status` IN ('0', '1')),
    CONSTRAINT `chk_sys_sms_config_tencent` CHECK
        (`provider` <> 'TENCENT' OR (`sdk_app_id` IS NOT NULL AND `sdk_app_id` <> ''
            AND `region` IS NOT NULL AND `region` <> ''))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '短信供应商配置';

CREATE TABLE IF NOT EXISTS `sys_sms_log`
(
    `log_id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '短信发送日志主键',
    `config_id`            BIGINT       NOT NULL COMMENT '供应商配置主键',
    `provider`             VARCHAR(16)  NOT NULL COMMENT '供应商快照',
    `source_type`          VARCHAR(32)  NOT NULL COMMENT 'ADMIN_TEST 或业务来源',
    `recipient_masked`     VARCHAR(400) NOT NULL COMMENT '不可逆脱敏接收人',
    `recipient_count`      INT          NOT NULL COMMENT '接收人数',
    `template_id`          VARCHAR(64)  NOT NULL COMMENT '供应商模板 ID',
    `status`               VARCHAR(16)  NOT NULL COMMENT 'PENDING、DELIVERED 或 FAILED',
    `provider_request_id`  VARCHAR(128)          DEFAULT NULL COMMENT '供应商请求追踪号',
    `error_code`           VARCHAR(96)           DEFAULT NULL COMMENT '稳定失败码',
    `error_summary`        VARCHAR(255)          DEFAULT NULL COMMENT '不含敏感数据的失败摘要',
    `create_by`            VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '发送主体账号',
    `create_time`          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '调用前落库时间',
    `finish_time`          DATETIME(3)           DEFAULT NULL COMMENT '供应商调用完成时间',
    PRIMARY KEY (`log_id`),
    KEY `idx_sys_sms_log_status` (`status`, `log_id`),
    KEY `idx_sys_sms_log_config` (`config_id`, `log_id`),
    CONSTRAINT `fk_sys_sms_log_config` FOREIGN KEY (`config_id`)
        REFERENCES `sys_sms_config` (`config_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_sys_sms_log_provider` CHECK (`provider` IN ('ALIYUN', 'TENCENT')),
    CONSTRAINT `chk_sys_sms_log_status` CHECK (`status` IN ('PENDING', 'DELIVERED', 'FAILED')),
    CONSTRAINT `chk_sys_sms_log_recipient_count` CHECK (`recipient_count` BETWEEN 1 AND 20)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '短信发送脱敏审计';

CREATE TABLE IF NOT EXISTS `sys_oss_config`
(
    `config_id`       BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'OSS 配置主键',
    `config_name`     VARCHAR(64)  NOT NULL COMMENT '配置显示名称',
    `endpoint`        VARCHAR(255) NOT NULL COMMENT 'S3 兼容服务根地址',
    `region`          VARCHAR(64)  NOT NULL COMMENT 'Signature V4 地域',
    `bucket_name`     VARCHAR(128) NOT NULL COMMENT '存储桶名称',
    `access_key`      VARCHAR(128) NOT NULL COMMENT '访问密钥 ID',
    `secret_key`      VARCHAR(256) NOT NULL COMMENT '访问密钥，仅服务端读取',
    `domain`          VARCHAR(255)          DEFAULT NULL COMMENT '公开对象 HTTPS 域名',
    `prefix`          VARCHAR(128)          DEFAULT NULL COMMENT '对象键业务前缀',
    `path_style`      CHAR(1)      NOT NULL DEFAULT 'Y' COMMENT 'Y 路径风格，N 虚拟主机风格',
    `access_policy`   VARCHAR(16)  NOT NULL DEFAULT 'PRIVATE' COMMENT 'PRIVATE 或 PUBLIC',
    `status`          CHAR(1)      NOT NULL DEFAULT '1' COMMENT '0 启用，1 停用',
    `create_by`       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '创建者账号',
    `create_time`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_by`       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '更新者账号',
    `update_time`     DATETIME(3)           DEFAULT NULL COMMENT '更新时间',
    `remark`          VARCHAR(500)          DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`config_id`),
    UNIQUE KEY `uk_sys_oss_config_name` (`config_name`),
    KEY `idx_sys_oss_config_status` (`status`),
    CONSTRAINT `chk_sys_oss_config_path_style` CHECK (`path_style` IN ('Y', 'N')),
    CONSTRAINT `chk_sys_oss_config_policy` CHECK (`access_policy` IN ('PRIVATE', 'PUBLIC')),
    CONSTRAINT `chk_sys_oss_config_status` CHECK (`status` IN ('0', '1')),
    CONSTRAINT `chk_sys_oss_config_public_domain` CHECK
        (`access_policy` <> 'PUBLIC' OR (`domain` IS NOT NULL AND `domain` <> ''))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'S3 兼容 OSS 配置';

CREATE TABLE IF NOT EXISTS `sys_oss_object`
(
    `object_id`       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '对象主键',
    `config_id`       BIGINT       NOT NULL COMMENT '上传时配置主键',
    `object_key`      VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端对象键',
    `original_name`   VARCHAR(255) NOT NULL COMMENT '原文件名',
    `file_suffix`     VARCHAR(17)  NOT NULL DEFAULT '' COMMENT '安全小写后缀',
    `content_type`    VARCHAR(255) NOT NULL COMMENT '服务端规范 MIME',
    `file_size`       BIGINT       NOT NULL COMMENT '实测字节数',
    `sha256`          CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '内容 SHA-256',
    `access_policy`   VARCHAR(16)  NOT NULL COMMENT '上传时访问策略快照',
    `public_url`      VARCHAR(1000)         DEFAULT NULL COMMENT '公开对象 URL；私有对象为空',
    `status`          VARCHAR(16)  NOT NULL COMMENT 'ACTIVE、DELETE_PENDING、DELETE_FAILED 或 DELETED',
    `last_error`      VARCHAR(255)          DEFAULT NULL COMMENT '脱敏删除失败摘要',
    `create_by`       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '上传者账号',
    `create_time`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '上传时间',
    `update_by`       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '最近操作账号',
    `update_time`     DATETIME(3)           DEFAULT NULL COMMENT '最近状态时间',
    `delete_time`     DATETIME(3)           DEFAULT NULL COMMENT '远端删除完成时间',
    PRIMARY KEY (`object_id`),
    UNIQUE KEY `uk_sys_oss_object_key` (`config_id`, `object_key`),
    KEY `idx_sys_oss_object_status` (`status`, `object_id`),
    CONSTRAINT `fk_sys_oss_object_config` FOREIGN KEY (`config_id`)
        REFERENCES `sys_oss_config` (`config_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_sys_oss_object_size` CHECK (`file_size` BETWEEN 0 AND 52428800),
    CONSTRAINT `chk_sys_oss_object_sha256` CHECK (`sha256` REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT `chk_sys_oss_object_policy` CHECK (`access_policy` IN ('PRIVATE', 'PUBLIC')),
    CONSTRAINT `chk_sys_oss_object_status` CHECK
        (`status` IN ('ACTIVE', 'DELETE_PENDING', 'DELETE_FAILED', 'DELETED')),
    CONSTRAINT `chk_sys_oss_object_public_url` CHECK
        ((`access_policy` = 'PUBLIC' AND `public_url` IS NOT NULL)
            OR (`access_policy` = 'PRIVATE' AND `public_url` IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'OSS 对象元数据与删除状态';

-- 已有工作流库补充短信偏好列；新装库已经由业务基线创建该列。
SET @sms_preference_column_exists =
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wf_notification_preference'
       AND COLUMN_NAME = 'sms_enabled');
SET @sms_preference_column_sql = IF(@sms_preference_column_exists = 0,
    'ALTER TABLE wf_notification_preference ADD COLUMN sms_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否接收短信通知'' AFTER email_enabled',
    'SELECT 1');
PREPARE sms_preference_column_statement FROM @sms_preference_column_sql;
EXECUTE sms_preference_column_statement;
DEALLOCATE PREPARE sms_preference_column_statement;

SET @sms_policy_template_column_exists =
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wf_notification_policy'
       AND COLUMN_NAME = 'sms_template_id');
SET @sms_policy_template_column_sql = IF(@sms_policy_template_column_exists = 0,
    'ALTER TABLE wf_notification_policy ADD COLUMN sms_template_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT ''短信供应商审核模板 ID'' AFTER channels',
    'SELECT 1');
PREPARE sms_policy_template_column_statement FROM @sms_policy_template_column_sql;
EXECUTE sms_policy_template_column_statement;
DEALLOCATE PREPARE sms_policy_template_column_statement;

SET @sms_outbox_template_column_exists =
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wf_notification_outbox'
       AND COLUMN_NAME = 'sms_template_id');
SET @sms_outbox_template_column_sql = IF(@sms_outbox_template_column_exists = 0,
    'ALTER TABLE wf_notification_outbox ADD COLUMN sms_template_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT ''登记时冻结的短信模板 ID'' AFTER content',
    'SELECT 1');
PREPARE sms_outbox_template_column_statement FROM @sms_outbox_template_column_sql;
EXECUTE sms_outbox_template_column_statement;
DEALLOCATE PREPARE sms_outbox_template_column_statement;

-- 重建通知通道 CHECK，使已有库允许 SMS 以及固定规范顺序的组合。
SET @policy_channel_check_exists =
    (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
     WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'wf_notification_policy'
       AND CONSTRAINT_NAME = 'chk_wf_notification_policy_channels');
SET @drop_policy_channel_check_sql = IF(@policy_channel_check_exists > 0,
    'ALTER TABLE wf_notification_policy DROP CHECK chk_wf_notification_policy_channels', 'SELECT 1');
PREPARE drop_policy_channel_check_statement FROM @drop_policy_channel_check_sql;
EXECUTE drop_policy_channel_check_statement;
DEALLOCATE PREPARE drop_policy_channel_check_statement;
ALTER TABLE `wf_notification_policy` ADD CONSTRAINT `chk_wf_notification_policy_channels` CHECK
    (`channels` IN ('INBOX', 'EMAIL', 'SMS', 'INBOX,EMAIL', 'INBOX,SMS', 'EMAIL,SMS', 'INBOX,EMAIL,SMS'));

SET @policy_sms_template_check_exists =
    (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
     WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'wf_notification_policy'
       AND CONSTRAINT_NAME = 'chk_wf_notification_policy_sms_template');
SET @add_policy_sms_template_check_sql = IF(@policy_sms_template_check_exists = 0,
    'ALTER TABLE wf_notification_policy ADD CONSTRAINT chk_wf_notification_policy_sms_template CHECK ((channels LIKE ''%SMS%'' AND sms_template_id IS NOT NULL AND sms_template_id <> '''') OR (channels NOT LIKE ''%SMS%'' AND sms_template_id IS NULL))',
    'SELECT 1');
PREPARE add_policy_sms_template_check_statement FROM @add_policy_sms_template_check_sql;
EXECUTE add_policy_sms_template_check_statement;
DEALLOCATE PREPARE add_policy_sms_template_check_statement;

SET @outbox_channel_check_exists =
    (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
     WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'wf_notification_outbox'
       AND CONSTRAINT_NAME = 'chk_wf_notification_outbox_channel');
SET @drop_outbox_channel_check_sql = IF(@outbox_channel_check_exists > 0,
    'ALTER TABLE wf_notification_outbox DROP CHECK chk_wf_notification_outbox_channel', 'SELECT 1');
PREPARE drop_outbox_channel_check_statement FROM @drop_outbox_channel_check_sql;
EXECUTE drop_outbox_channel_check_statement;
DEALLOCATE PREPARE drop_outbox_channel_check_statement;
ALTER TABLE `wf_notification_outbox` ADD CONSTRAINT `chk_wf_notification_outbox_channel` CHECK
    (`channel` IN ('INBOX', 'EMAIL', 'SMS'));

SET @outbox_sms_template_check_exists =
    (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
     WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'wf_notification_outbox'
       AND CONSTRAINT_NAME = 'chk_wf_notification_outbox_sms_template');
SET @add_outbox_sms_template_check_sql = IF(@outbox_sms_template_check_exists = 0,
    'ALTER TABLE wf_notification_outbox ADD CONSTRAINT chk_wf_notification_outbox_sms_template CHECK ((channel = ''SMS'' AND sms_template_id IS NOT NULL AND sms_template_id <> '''') OR (channel <> ''SMS'' AND sms_template_id IS NULL))',
    'SELECT 1');
PREPARE add_outbox_sms_template_check_statement FROM @add_outbox_sms_template_check_sql;
EXECUTE add_outbox_sms_template_check_statement;
DEALLOCATE PREPARE add_outbox_sms_template_check_statement;

-- 系统管理下新增两个真实入口，按 path 自然键幂等写入。
INSERT INTO `sys_menu` (`menu_name`,`parent_id`,`order_num`,`path`,`component`,`query`,`route_name`,
    `is_frame`,`is_cache`,`menu_type`,`visible`,`status`,`perms`,`icon`,`create_by`,`create_time`,`remark`)
SELECT '短信管理',1,10,'sms','system/sms/index','', 'SystemSms',1,0,'C','0','0','system:sms:list','message','admin',CURRENT_TIMESTAMP,'短信供应商配置与发送审计'
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `parent_id`=1 AND `path`='sms');

INSERT INTO `sys_menu` (`menu_name`,`parent_id`,`order_num`,`path`,`component`,`query`,`route_name`,
    `is_frame`,`is_cache`,`menu_type`,`visible`,`status`,`perms`,`icon`,`create_by`,`create_time`,`remark`)
SELECT '对象存储',1,11,'oss','system/oss/index','', 'SystemOss',1,0,'C','0','0','system:oss:list','upload','admin',CURRENT_TIMESTAMP,'S3 兼容 OSS 配置与对象台账'
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `parent_id`=1 AND `path`='oss');

-- 页面按钮权限只依附对应自然键页面，不使用环境相关固定菜单主键。
INSERT INTO `sys_menu` (`menu_name`,`parent_id`,`order_num`,`path`,`component`,`query`,`route_name`,
    `is_frame`,`is_cache`,`menu_type`,`visible`,`status`,`perms`,`icon`,`create_by`,`create_time`)
SELECT seed.menu_name,parent.menu_id,seed.order_num,'','','','',1,0,'F','0','0',seed.perms,'#','admin',CURRENT_TIMESTAMP
FROM (
    SELECT '短信配置新增' menu_name,1 order_num,'system:sms:add' perms UNION ALL
    SELECT '短信配置修改',2,'system:sms:edit' UNION ALL
    SELECT '短信配置删除',3,'system:sms:remove' UNION ALL
    SELECT '短信测试发送',4,'system:sms:send'
) seed
JOIN `sys_menu` parent ON parent.parent_id=1 AND parent.path='sms'
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` existing WHERE existing.perms=seed.perms);

INSERT INTO `sys_menu` (`menu_name`,`parent_id`,`order_num`,`path`,`component`,`query`,`route_name`,
    `is_frame`,`is_cache`,`menu_type`,`visible`,`status`,`perms`,`icon`,`create_by`,`create_time`)
SELECT seed.menu_name,parent.menu_id,seed.order_num,'','','','',1,0,'F','0','0',seed.perms,'#','admin',CURRENT_TIMESTAMP
FROM (
    SELECT 'OSS 配置新增' menu_name,1 order_num,'system:oss:add' perms UNION ALL
    SELECT 'OSS 配置修改',2,'system:oss:edit' UNION ALL
    SELECT 'OSS 配置删除',3,'system:oss:remove' UNION ALL
    SELECT 'OSS 连通测试',4,'system:oss:test' UNION ALL
    SELECT 'OSS 对象上传',5,'system:oss:upload' UNION ALL
    SELECT 'OSS 对象下载',6,'system:oss:download'
) seed
JOIN `sys_menu` parent ON parent.parent_id=1 AND parent.path='oss'
WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` existing WHERE existing.perms=seed.perms);

-- 内置管理员角色获得新增菜单；超级管理员仍按若依全权限规则工作。
INSERT IGNORE INTO `sys_role_menu` (`role_id`,`menu_id`)
SELECT role_info.role_id,menu_info.menu_id
FROM `sys_role` role_info
JOIN `sys_menu` menu_info ON menu_info.path IN ('sms','oss')
    OR menu_info.perms LIKE 'system:sms:%' OR menu_info.perms LIKE 'system:oss:%'
WHERE role_info.role_key='admin';
