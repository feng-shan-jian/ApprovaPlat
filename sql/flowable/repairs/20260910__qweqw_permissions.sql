-- T03：仅修复已核实的 qweqw 当前模板，不修改任何历史部署快照。
-- field102 原有 disabled=false、required=true；明确保持“可见、可读、可写、必填”。
-- 执行前在目标数据库运行本文件；摘要不匹配时 UPDATE 为 0 行，必须重新审核，禁止移除摘要条件。
SET @qweqw_before_sha = 'eea8f5d7f46b8af9ed6c5d70224e90c6b08d4852d8a17c86935a75070af5086f';
START TRANSACTION;
SELECT form_id, form_name, SHA2(content, 256) AS before_sha256,
       JSON_EXTRACT(content, '$.fields[0]') AS field_before
FROM wf_form WHERE form_id = 1 AND form_name = 'qweqw' FOR UPDATE;
UPDATE wf_form
SET content = JSON_SET(content,
    '$.fields[0].workflowHidden', CAST('false' AS JSON),
    '$.fields[0].workflowReadable', CAST('true' AS JSON),
    '$.fields[0].workflowWritable', CAST('true' AS JSON))
WHERE form_id = 1 AND form_name = 'qweqw' AND SHA2(content, 256) = @qweqw_before_sha;
SELECT ROW_COUNT() AS repaired_rows;
SELECT form_id, form_name, SHA2(content, 256) AS after_sha256,
       JSON_EXTRACT(content, '$.fields[0]') AS field_after
FROM wf_form WHERE form_id = 1 AND form_name = 'qweqw';
COMMIT;
