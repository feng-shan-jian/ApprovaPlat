-- 平台 SMTP 配置只以本表为正式来源；迁移不得写入默认账号、授权码或示例配置。
CREATE TABLE IF NOT EXISTS `sys_mail_config`
(
    `config_id`                 BIGINT       NOT NULL COMMENT '平台单例配置主键，固定为 1',
    `smtp_host`                 VARCHAR(255) NOT NULL COMMENT 'SMTP 服务器主机名或地址',
    `smtp_port`                 INT          NOT NULL COMMENT 'SMTP 服务器端口，范围 1 至 65535',
    `encryption_mode`           VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'NONE、STARTTLS 或 SSL',
    `username`                  VARCHAR(255) NOT NULL COMMENT 'SMTP 登录账号',
    `credential_ciphertext`     TEXT         NOT NULL COMMENT '由 RuoYi Token 根密钥派生子密钥生成的 AES-256-GCM 密文及认证标签',
    `credential_iv`             VARBINARY(12) NOT NULL COMMENT '每次加密随机生成的 12 字节 GCM IV',
    `from_address`              VARCHAR(255) NOT NULL COMMENT '发件邮箱地址',
    `sender_name`               VARCHAR(255) NOT NULL COMMENT '用户可见发件人名称',
    `revision`                  BIGINT       NOT NULL COMMENT '乐观锁版本，首次保存为 1',
    `create_by`                 VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '创建人账号',
    `create_time`               DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次保存时间',
    `update_by`                 VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '最后修改人账号',
    `update_time`               DATETIME(3)           DEFAULT NULL COMMENT '最后修改时间',
    PRIMARY KEY (`config_id`),
    CONSTRAINT `chk_sys_mail_config_singleton` CHECK (`config_id` = 1),
    CONSTRAINT `chk_sys_mail_config_port` CHECK (`smtp_port` BETWEEN 1 AND 65535),
    CONSTRAINT `chk_sys_mail_config_encryption` CHECK (`encryption_mode` IN ('NONE', 'STARTTLS', 'SSL')),
    CONSTRAINT `chk_sys_mail_config_iv` CHECK (OCTET_LENGTH(`credential_iv`) = 12),
    CONSTRAINT `chk_sys_mail_config_ciphertext` CHECK (OCTET_LENGTH(`credential_ciphertext`) > 0),
    CONSTRAINT `chk_sys_mail_config_revision` CHECK (`revision` >= 1)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '平台单例 SMTP 邮件服务配置';
