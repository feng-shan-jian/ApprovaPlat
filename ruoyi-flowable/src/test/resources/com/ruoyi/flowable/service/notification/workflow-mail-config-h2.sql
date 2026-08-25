CREATE TABLE sys_mail_config
(
    config_id BIGINT NOT NULL PRIMARY KEY,
    smtp_host VARCHAR(255) NOT NULL,
    smtp_port INT NOT NULL,
    encryption_mode VARCHAR(20) NOT NULL,
    username VARCHAR(255) NOT NULL,
    credential_ciphertext CLOB NOT NULL,
    credential_iv VARBINARY(12) NOT NULL,
    from_address VARCHAR(255) NOT NULL,
    sender_name VARCHAR(255) NOT NULL,
    revision BIGINT NOT NULL,
    create_by VARCHAR(64) NOT NULL DEFAULT '',
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_by VARCHAR(64) NOT NULL DEFAULT '',
    update_time TIMESTAMP(3) NULL,
    CONSTRAINT chk_mail_config_singleton CHECK (config_id = 1),
    CONSTRAINT chk_mail_config_port CHECK (smtp_port BETWEEN 1 AND 65535),
    CONSTRAINT chk_mail_config_revision CHECK (revision >= 1)
);
