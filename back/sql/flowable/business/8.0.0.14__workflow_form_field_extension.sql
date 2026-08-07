-- 服务端固定的自定义表单字段目录与不可变 V1 版本。
-- 目录只能选择代码内安装的 FORM_FIELD_TEXTAREA_V1，不保存任意组件名或模板。
INSERT INTO `wf_bpmn_extension`
    (`extension_key`, `extension_name`, `extension_type`, `status`, `description`,
     `create_by`, `create_time`, `update_by`, `update_time`)
SELECT 'approva.form.textarea', '多行文本', 'FORM_FIELD', 'ENABLED',
       '固定为服务端安装的多行文本渲染器，用于 BPMN 内嵌 FormData',
       'system', current_timestamp(3), '', NULL
WHERE NOT EXISTS
(
    SELECT 1 FROM `wf_bpmn_extension`
    WHERE `extension_key` = 'approva.form.textarea'
);

INSERT INTO `wf_bpmn_extension_version`
    (`extension_id`, `version_no`, `implementation_key`, `config_schema`,
     `checksum`, `create_by`, `create_time`)
SELECT e.extension_id, 1, 'FORM_FIELD_TEXTAREA_V1',
       CAST('{"additionalProperties":false,"properties":{},"type":"object"}' AS JSON),
       '1b6a6597e25bcf0ffeb06415b043465ec85a7cceddde850d1551e3a39b2ad78b',
       'system', current_timestamp(3)
FROM `wf_bpmn_extension` e
WHERE e.extension_key = 'approva.form.textarea'
  AND NOT EXISTS
  (
      SELECT 1 FROM `wf_bpmn_extension_version` v
      WHERE v.extension_id = e.extension_id AND v.version_no = 1
  );
