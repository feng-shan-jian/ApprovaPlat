-- 密钥正文仅以 AES-256-GCM 密文持久化，主密钥由服务身份环境注入，不能写入数据库。
CREATE TABLE IF NOT EXISTS wf_connector_managed_secret (
    secret_ref VARCHAR(128) NOT NULL PRIMARY KEY,
    revision INT NOT NULL,
    encrypted_value TEXT NULL,
    status VARCHAR(16) NOT NULL,
    update_by VARCHAR(64) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='受管连接凭据密文与撤销状态';
