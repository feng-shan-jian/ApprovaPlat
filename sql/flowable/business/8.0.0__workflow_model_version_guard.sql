-- 首个正式基线的 Flowable 模型 key 与版本号数据库完整性门禁。
-- 本脚本只在全新安装的 Flowable 官方表之后执行；唯一约束创建时若发现重复版本组，
-- MySQL 会明确失败，不删除或覆盖任何正式模型数据。

-- 识别任意名称但列顺序一致的唯一索引，使脚本具备重复执行安全性。
SET @wf_model_unique_index_count :=
(
    SELECT COUNT(*)
      FROM
      (
          SELECT `INDEX_NAME`
            FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'ACT_RE_MODEL'
             AND NON_UNIQUE = 0
           GROUP BY `INDEX_NAME`
          HAVING GROUP_CONCAT(`COLUMN_NAME` ORDER BY `SEQ_IN_INDEX` SEPARATOR ',')
                 = 'KEY_,VERSION_,TENANT_ID_'
      ) AS matching_unique_indexes
);

-- 首个基线只允许在模型结构和数据无歧义时建立唯一约束。
SET @wf_model_guard_ddl := IF(
    @wf_model_unique_index_count = 0,
    'ALTER TABLE `ACT_RE_MODEL` ADD CONSTRAINT `ACT_UNIQ_MODEL` UNIQUE (`KEY_`, `VERSION_`, `TENANT_ID_`)',
    'DO 0'
);

PREPARE wf_model_guard_statement FROM @wf_model_guard_ddl;
EXECUTE wf_model_guard_statement;
DEALLOCATE PREPARE wf_model_guard_statement;

SET @wf_model_unique_index_count := NULL;
SET @wf_model_guard_ddl := NULL;
