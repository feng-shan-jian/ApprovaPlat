-- 模型设计草稿独立于正式模型资源和申请草稿。NULL XML 是 CAS 墓碑，防止删除后旧标签覆盖。
CREATE TABLE IF NOT EXISTS wf_model_design_draft (
    model_id VARCHAR(64) NOT NULL,
    owner_user_id VARCHAR(64) NOT NULL,
    base_model_revision INT NOT NULL,
    revision INT NOT NULL,
    bpmn_xml MEDIUMTEXT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (model_id, owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个人模型设计草稿（不可部署）';
