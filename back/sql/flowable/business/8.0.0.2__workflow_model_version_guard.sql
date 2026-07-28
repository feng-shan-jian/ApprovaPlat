-- Flowable 模型 key 与版本号的数据库级并发完整性门禁。
-- 本脚本可重复执行；唯一约束创建时若发现既有重复版本组，MySQL 会立即失败且不会删除正式模型数据。

-- 识别任意名称但列顺序一致的既有唯一索引，使脚本能够安全重复执行。
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

-- ACT_RE_MODEL 缺失时 ALTER 会明确失败；重复数据存在时唯一约束创建也会原子失败。
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
