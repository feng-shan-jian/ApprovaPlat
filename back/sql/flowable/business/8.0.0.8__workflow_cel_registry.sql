-- Flowable 8 CEL 受控表达式注册表升级。
-- 版本配置和摘要与 WorkflowCelSandbox 固定契约一致，部署快照只引用该不可变版本。

INSERT INTO `wf_bpmn_extension`
    (`extension_key`, `extension_name`, `extension_type`, `status`, `description`,
     `create_by`, `create_time`, `update_by`, `update_time`)
SELECT 'approva.cel-expression', 'CEL 安全表达式', 'CEL', 'ENABLED',
       '仅使用节点显式声明的标量变量计算确定性结果，不提供文件、网络、进程或 Bean 函数',
       'system', current_timestamp(3), '', NULL
WHERE NOT EXISTS
(
    SELECT 1 FROM `wf_bpmn_extension` WHERE `extension_key` = 'approva.cel-expression'
);

INSERT INTO `wf_bpmn_extension_version`
    (`extension_id`, `version_no`, `implementation_key`, `config_schema`,
     `checksum`, `create_by`, `create_time`)
SELECT e.extension_id, 1, 'CEL_EXPRESSION_V1',
       CAST('{"additionalProperties":false,"properties":{"expression":{"maxLength":4096,"minLength":1,"type":"string"},"resultType":{"enum":["BOOL","INT","DOUBLE","STRING"],"type":"string"},"resultVariable":{"pattern":"^[A-Za-z_][A-Za-z0-9_]{0,127}$","type":"string"},"variables":{"items":{"additionalProperties":false,"properties":{"name":{"pattern":"^[A-Za-z_][A-Za-z0-9_]{0,127}$","type":"string"},"type":{"enum":["BOOL","INT","DOUBLE","STRING"],"type":"string"}},"required":["name","type"],"type":"object"},"maxItems":32,"type":"array"}},"required":["expression","resultVariable","resultType","variables"],"type":"object"}' AS JSON),
       '6b5c7dcf648f27ff1fd13c654ff149a7f84b90dc2719abd33e2ef078a5970db6',
       'system', current_timestamp(3)
FROM `wf_bpmn_extension` e
WHERE e.extension_key = 'approva.cel-expression'
  AND NOT EXISTS
  (
      SELECT 1 FROM `wf_bpmn_extension_version` v
      WHERE v.extension_id = e.extension_id AND v.version_no = 1
  );
