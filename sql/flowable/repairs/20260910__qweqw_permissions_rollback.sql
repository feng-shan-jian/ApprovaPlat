-- 仅回退与本次修复输出完全一致的当前模板。摘要不符则保持不动。
-- 不作为常规降级步骤：回退将恢复缺失权限的旧协议，只用于撤销本次定点数据修复。
SET @qweqw_original = '{"fields":[{"placeholder":"计数器","step-strictly":false,"controls-position":"","disabled":false,"label":"计数器","tag":"el-input-number","tagIcon":"number","span":24,"labelWidth":null,"required":true,"regList":[],"changeTag":true,"document":"https://element-plus.org/zh-CN/component/input-number","formId":102,"renderKey":1787460771377,"layout":"colFormItem","vModel":"field102"}],"formRef":"formRef","formModel":"formData","size":"default","labelPosition":"right","labelWidth":100,"formRules":"rules","gutter":15,"disabled":false,"span":24,"formBtns":true}';
SET @qweqw_repaired = JSON_SET(@qweqw_original,
    '$.fields[0].workflowHidden', CAST('false' AS JSON),
    '$.fields[0].workflowReadable', CAST('true' AS JSON),
    '$.fields[0].workflowWritable', CAST('true' AS JSON));
START TRANSACTION;
UPDATE wf_form SET content = @qweqw_original
WHERE form_id = 1 AND form_name = 'qweqw' AND SHA2(content, 256) = SHA2(@qweqw_repaired, 256)
  AND SHA2(@qweqw_original, 256) = 'eea8f5d7f46b8af9ed6c5d70224e90c6b08d4852d8a17c86935a75070af5086f';
SELECT ROW_COUNT() AS restored_rows;
COMMIT;
