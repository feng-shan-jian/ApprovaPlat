-- 已存在 Flowable 8 目标库的附件物理清理持久化退避增量脚本。
-- 本脚本可重复执行；任一同名对象结构冲突都会明确失败，执行后必须运行正式业务表验收脚本。

SET @wf_attachment_retry_column_count :=
(
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wf_attachment'
      AND COLUMN_NAME = 'cleanup_retry_count'
      AND COLUMN_TYPE = 'int'
      AND IS_NULLABLE = 'NO'
      AND COLUMN_DEFAULT = '0'
      AND EXTRA = ''
      AND GENERATION_EXPRESSION = ''
);
SET @wf_attachment_retry_ddl := IF(
    @wf_attachment_retry_column_count = 0,
    'ALTER TABLE `wf_attachment` ADD COLUMN `cleanup_retry_count` INT NOT NULL DEFAULT 0 COMMENT ''物理清理连续失败并已调度重试的次数'' AFTER `storage_deleted_time`',
    'DO 0'
);
PREPARE wf_attachment_retry_statement FROM @wf_attachment_retry_ddl;
EXECUTE wf_attachment_retry_statement;
DEALLOCATE PREPARE wf_attachment_retry_statement;

SET @wf_attachment_retry_column_count :=
(
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wf_attachment'
      AND COLUMN_NAME = 'cleanup_next_retry_time'
      AND DATA_TYPE = 'datetime'
      AND DATETIME_PRECISION = 3
      AND IS_NULLABLE = 'YES'
      AND COLUMN_DEFAULT IS NULL
      AND EXTRA = ''
      AND GENERATION_EXPRESSION = ''
);
SET @wf_attachment_retry_ddl := IF(
    @wf_attachment_retry_column_count = 0,
    'ALTER TABLE `wf_attachment` ADD COLUMN `cleanup_next_retry_time` DATETIME(3) DEFAULT NULL COMMENT ''下次允许进入物理清理候选的时间'' AFTER `cleanup_retry_count`',
    'DO 0'
);
PREPARE wf_attachment_retry_statement FROM @wf_attachment_retry_ddl;
EXECUTE wf_attachment_retry_statement;
DEALLOCATE PREPARE wf_attachment_retry_statement;

SET @wf_attachment_retry_column_count :=
(
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wf_attachment'
      AND COLUMN_NAME = 'cleanup_last_error_code'
      AND DATA_TYPE = 'varchar'
      AND CHARACTER_MAXIMUM_LENGTH = 64
      AND CHARACTER_SET_NAME = 'ascii'
      AND COLLATION_NAME = 'ascii_bin'
      AND IS_NULLABLE = 'YES'
      AND COLUMN_DEFAULT IS NULL
      AND EXTRA = ''
      AND GENERATION_EXPRESSION = ''
);
SET @wf_attachment_retry_ddl := IF(
    @wf_attachment_retry_column_count = 0,
    'ALTER TABLE `wf_attachment` ADD COLUMN `cleanup_last_error_code` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT ''最近一次清理失败的稳定脱敏错误码'' AFTER `cleanup_next_retry_time`',
    'DO 0'
);
PREPARE wf_attachment_retry_statement FROM @wf_attachment_retry_ddl;
EXECUTE wf_attachment_retry_statement;
DEALLOCATE PREPARE wf_attachment_retry_statement;

-- 索引契约同时冻结列数、顺序、前缀长度、排序、类型和可见性；同名错误索引必须拒绝升级。
SET @wf_attachment_retry_index_count :=
(
    SELECT COUNT(*)
    FROM
    (
        SELECT INDEX_NAME
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'wf_attachment'
          AND INDEX_NAME = 'idx_wf_attachment_cleanup_due'
        GROUP BY INDEX_NAME
        HAVING COUNT(*) = 3
           AND MIN(NON_UNIQUE) = 1
           AND MAX(NON_UNIQUE) = 1
           AND GROUP_CONCAT(
                   CONCAT(
                       SEQ_IN_INDEX, ':',
                       COALESCE(COLUMN_NAME, '<expression>'), ':',
                       COALESCE(CAST(SUB_PART AS CHAR), 'NULL'), ':',
                       COALESCE(COLLATION, 'NULL'), ':',
                       INDEX_TYPE, ':',
                       IS_VISIBLE
                   )
                   ORDER BY SEQ_IN_INDEX SEPARATOR '|'
               ) = CONCAT(
                   '1:attachment_status:NULL:A:BTREE:YES|',
                   '2:cleanup_next_retry_time:NULL:A:BTREE:YES|',
                   '3:expire_time:NULL:A:BTREE:YES'
               )
    ) matching_retry_index
);
SET @wf_attachment_retry_ddl := IF(
    @wf_attachment_retry_index_count = 0,
    'ALTER TABLE `wf_attachment` ADD INDEX `idx_wf_attachment_cleanup_due` (`attachment_status`, `cleanup_next_retry_time`, `expire_time`)',
    'DO 0'
);
PREPARE wf_attachment_retry_statement FROM @wf_attachment_retry_ddl;
EXECUTE wf_attachment_retry_statement;
DEALLOCATE PREPARE wf_attachment_retry_statement;

-- MySQL 8.0/8.4 会补充字符集引导符、反引号、空白和冗余括号；仅移除这些版本表示差异后校验完整词法序列。
-- 预期 SHA-256 来自 MySQL 8.4.9 实际 CHECK_CLAUSE；任何未识别的表示或业务逻辑变化都会 fail-closed。
SET @wf_attachment_retry_check_count :=
(
    SELECT COUNT(*)
    FROM information_schema.TABLE_CONSTRAINTS tc
    JOIN information_schema.CHECK_CONSTRAINTS cc
      ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
     AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
    WHERE tc.CONSTRAINT_SCHEMA = DATABASE()
      AND tc.TABLE_NAME = 'wf_attachment'
      AND tc.CONSTRAINT_NAME = 'chk_wf_attachment_cleanup_retry'
      AND tc.CONSTRAINT_TYPE = 'CHECK'
      AND tc.ENFORCED = 'YES'
      AND SHA2(
              REGEXP_REPLACE(
                  LOWER(cc.CHECK_CLAUSE),
                  '_utf8mb4|_ascii|[[:space:]`()]',
                  ''
              ),
              256
          ) = 'f44964aab58ce25fe25af57c9fc1269e3981d3104545530997241be965b189c6'
);
SET @wf_attachment_retry_ddl := IF(
    @wf_attachment_retry_check_count = 0,
    'ALTER TABLE `wf_attachment` ADD CONSTRAINT `chk_wf_attachment_cleanup_retry` CHECK (`cleanup_retry_count` >= 0 AND ((`cleanup_next_retry_time` IS NULL AND `cleanup_last_error_code` IS NULL AND (`attachment_status` IN (''EXPIRED'', ''DELETED'') OR `cleanup_retry_count` = 0)) OR (`storage_deleted_time` IS NULL AND `attachment_status` IN (''EXPIRED'', ''DELETED'') AND `cleanup_retry_count` > 0 AND `cleanup_next_retry_time` IS NOT NULL AND `cleanup_last_error_code` REGEXP ''^[a-z][a-z0-9_]{0,63}$'')))',
    'DO 0'
);
PREPARE wf_attachment_retry_statement FROM @wf_attachment_retry_ddl;
EXECUTE wf_attachment_retry_statement;
DEALLOCATE PREPARE wf_attachment_retry_statement;

SET @wf_attachment_retry_column_count := NULL;
SET @wf_attachment_retry_index_count := NULL;
SET @wf_attachment_retry_check_count := NULL;
SET @wf_attachment_retry_ddl := NULL;
