-- Flowable 8.0.0 新建 schema 的只读验收脚本。
-- 所有检查都应返回 PASS；本脚本不会创建、修改或删除数据。

SELECT
    'schema_versions' AS check_name,
    CASE
        WHEN COUNT(*) = 2
         AND MIN(VALUE_) = '8.0.0.0'
         AND MAX(VALUE_) = '8.0.0.0'
        THEN 'PASS'
        ELSE 'FAIL'
    END AS result,
    GROUP_CONCAT(CONCAT(NAME_, '=', VALUE_) ORDER BY NAME_ SEPARATOR ', ') AS detail
FROM ACT_GE_PROPERTY
WHERE NAME_ IN ('common.schema.version', 'schema.version');

WITH model_unique_indexes AS (
    SELECT
        INDEX_NAME,
        NON_UNIQUE,
        GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') AS columns_in_order
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ACT_RE_MODEL'
    GROUP BY INDEX_NAME, NON_UNIQUE
)
SELECT
    'model_version_unique_constraint' AS check_name,
    CASE WHEN COUNT(*) = 1 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT(
        'matching_indexes=', COUNT(*),
        ', indexes=', COALESCE(GROUP_CONCAT(INDEX_NAME ORDER BY INDEX_NAME SEPARATOR ','), 'none')
    ) AS detail
FROM model_unique_indexes
WHERE NON_UNIQUE = 0
  AND columns_in_order = 'KEY_,VERSION_,TENANT_ID_';

SELECT
    'model_version_duplicate_groups' AS check_name,
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('duplicate_groups=', COUNT(*)) AS detail
FROM
(
    SELECT 1
    FROM ACT_RE_MODEL
    WHERE KEY_ IS NOT NULL
      AND VERSION_ IS NOT NULL
    GROUP BY KEY_, VERSION_, TENANT_ID_
    HAVING COUNT(*) > 1
) AS duplicate_model_versions;

WITH expected_tables AS (
    -- Flowable 8 运行时启用 BPMN Process 与 DMN，清单必须同时冻结两套正式引擎表。
    SELECT 'ACT_DMN_DECISION' AS table_name
    UNION ALL SELECT 'ACT_DMN_DEPLOYMENT'
    UNION ALL SELECT 'ACT_DMN_DEPLOYMENT_RESOURCE'
    UNION ALL SELECT 'ACT_DMN_HI_DECISION_EXECUTION'
    UNION ALL SELECT 'ACT_EVT_LOG'
    UNION ALL SELECT 'ACT_GE_BYTEARRAY'
    UNION ALL SELECT 'ACT_GE_PROPERTY'
    UNION ALL SELECT 'ACT_HI_ACTINST'
    UNION ALL SELECT 'ACT_HI_ATTACHMENT'
    UNION ALL SELECT 'ACT_HI_COMMENT'
    UNION ALL SELECT 'ACT_HI_DETAIL'
    UNION ALL SELECT 'ACT_HI_ENTITYLINK'
    UNION ALL SELECT 'ACT_HI_IDENTITYLINK'
    UNION ALL SELECT 'ACT_HI_PROCINST'
    UNION ALL SELECT 'ACT_HI_TASKINST'
    UNION ALL SELECT 'ACT_HI_TSK_LOG'
    UNION ALL SELECT 'ACT_HI_VARINST'
    UNION ALL SELECT 'ACT_PROCDEF_INFO'
    UNION ALL SELECT 'ACT_RE_DEPLOYMENT'
    UNION ALL SELECT 'ACT_RE_MODEL'
    UNION ALL SELECT 'ACT_RE_PROCDEF'
    UNION ALL SELECT 'ACT_RU_ACTINST'
    UNION ALL SELECT 'ACT_RU_DEADLETTER_JOB'
    UNION ALL SELECT 'ACT_RU_ENTITYLINK'
    UNION ALL SELECT 'ACT_RU_EVENT_SUBSCR'
    UNION ALL SELECT 'ACT_RU_EXECUTION'
    UNION ALL SELECT 'ACT_RU_EXTERNAL_JOB'
    UNION ALL SELECT 'ACT_RU_HISTORY_JOB'
    UNION ALL SELECT 'ACT_RU_IDENTITYLINK'
    UNION ALL SELECT 'ACT_RU_JOB'
    UNION ALL SELECT 'ACT_RU_SUSPENDED_JOB'
    UNION ALL SELECT 'ACT_RU_TASK'
    UNION ALL SELECT 'ACT_RU_TIMER_JOB'
    UNION ALL SELECT 'ACT_RU_VARIABLE'
    UNION ALL SELECT 'FLW_RU_BATCH'
    UNION ALL SELECT 'FLW_RU_BATCH_PART'
),
flowable_objects AS (
    SELECT TABLE_NAME AS actual_name, UPPER(TABLE_NAME) AS table_name, TABLE_TYPE
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND (
          UPPER(TABLE_NAME) LIKE 'ACT!_%' ESCAPE '!'
          OR UPPER(TABLE_NAME) LIKE 'FLW!_%' ESCAPE '!'
      )
),
actual_base_tables AS (
    SELECT DISTINCT table_name
    FROM flowable_objects
    WHERE TABLE_TYPE = 'BASE TABLE'
),
checks AS (
    SELECT
        1 AS sort_no,
        'missing_required_tables' AS check_name,
        COUNT(*) AS issue_count,
        GROUP_CONCAT(e.table_name ORDER BY e.table_name SEPARATOR ', ') AS objects
    FROM expected_tables e
    LEFT JOIN actual_base_tables a ON a.table_name = e.table_name
    WHERE a.table_name IS NULL

    UNION ALL

    SELECT
        2,
        'unexpected_flowable_objects',
        COUNT(*),
        GROUP_CONCAT(
            CONCAT(o.actual_name, '[', o.TABLE_TYPE, ']')
            ORDER BY o.actual_name SEPARATOR ', '
        )
    FROM flowable_objects o
    LEFT JOIN expected_tables e ON e.table_name = o.table_name
    WHERE e.table_name IS NULL

    UNION ALL

    SELECT
        3,
        'disabled_module_objects',
        COUNT(*),
        GROUP_CONCAT(o.actual_name ORDER BY o.actual_name SEPARATOR ', ')
    FROM flowable_objects o
    WHERE o.table_name LIKE 'ACT!_ID!_%' ESCAPE '!'
       OR o.table_name LIKE 'ACT!_CMMN!_%' ESCAPE '!'
       OR o.table_name LIKE 'ACT!_APP!_%' ESCAPE '!'
       OR o.table_name LIKE 'ACT!_FO!_%' ESCAPE '!'
       OR o.table_name LIKE 'ACT!_CO!_%' ESCAPE '!'
       OR o.table_name LIKE 'FLW!_EVENT!_%' ESCAPE '!'
       OR o.table_name LIKE 'FLW!_CHANNEL!_%' ESCAPE '!'
)
SELECT
    check_name,
    CASE WHEN issue_count = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('issues=', issue_count, ', objects=', COALESCE(objects, 'none')) AS detail
FROM checks
ORDER BY sort_no;

SELECT
    'deadletter_jobs' AS check_name,
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('actual=', COUNT(*)) AS detail
FROM ACT_RU_DEADLETTER_JOB;
