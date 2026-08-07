-- SQL 连接器数据源目录。外库只保存环境引用，运行时不得把凭据正文写入数据库、BPMN 或日志。

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
       CAST('{"additionalProperties":false,"properties":{"dataSourceKey":{"pattern":"^[A-Za-z][A-Za-z0-9_.-]{0,127}$","type":"string"},"maxRows":{"maximum":1000,"minimum":1,"type":"integer"},"parameters":{"additionalProperties":{"pattern":"^[A-Za-z_][A-Za-z0-9_]{0,127}$","type":"string"},"type":"object"},"resultVariable":{"pattern":"^[A-Za-z_][A-Za-z0-9_]{0,127}$","type":"string"},"sql":{"maxLength":8192,"minLength":1,"type":"string"}},"required":["dataSourceKey","sql","parameters"],"type":"object"}' AS JSON),
       '7d996f19c7bbcf60852177c02db36fbd86cd4e088cecc420dc6a08c72a3f3cdc',
       'system', current_timestamp(3)
FROM `wf_bpmn_extension` e
WHERE e.extension_key = 'approva.sql-connector'
  AND NOT EXISTS
  (
      SELECT 1 FROM `wf_bpmn_extension_version` v
      WHERE v.extension_id = e.extension_id AND v.version_no = 1
  );
