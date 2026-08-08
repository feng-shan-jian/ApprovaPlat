-- 将 8.0.0 正式基线中的 wf_copy 升级为自动抄送与首次阅读审计事实表。
-- 新增列均为旧行提供确定性默认值；迁移不删除抄送事实，也不修改唯一幂等键。

ALTER TABLE `wf_copy`
    ADD COLUMN `source_type` VARCHAR(16) NOT NULL DEFAULT 'MANUAL'
        COMMENT '抄送来源：MANUAL、AUTO 或 MANUAL_AUTO' AFTER `originator_name`,
    ADD COLUMN `trigger_type` VARCHAR(32) NOT NULL DEFAULT 'MANUAL_COMPLETE'
        COMMENT '服务端固定的抄送触发类型' AFTER `source_type`,
    ADD COLUMN `trigger_node_id` VARCHAR(64) DEFAULT NULL
        COMMENT '触发抄送的 BPMN 节点主键' AFTER `trigger_type`,
    ADD COLUMN `trigger_node_name` VARCHAR(255) DEFAULT NULL
        COMMENT '触发抄送的 BPMN 节点名称快照' AFTER `trigger_node_id`,
    ADD COLUMN `read_status` CHAR(1) NOT NULL DEFAULT '0'
        COMMENT '阅读状态：0 未读，1 已读' AFTER `trigger_node_name`,
    ADD COLUMN `read_time` DATETIME(3) DEFAULT NULL
        COMMENT '首次阅读时间' AFTER `read_status`,
    MODIFY COLUMN `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        COMMENT '创建时间',
    DROP INDEX `idx_wf_copy_user_status_time`,
    ADD KEY `idx_wf_copy_user_status_time`
        (`user_id`, `del_flag`, `read_status`, `create_time`),
    ADD CONSTRAINT `chk_wf_copy_source_type`
        CHECK (`source_type` IN ('MANUAL', 'AUTO', 'MANUAL_AUTO')),
    ADD CONSTRAINT `chk_wf_copy_trigger_type`
        CHECK (`trigger_type` IN (
            'MANUAL_COMPLETE', 'MANUAL_REJECT', 'MANUAL_RETURN', 'MANUAL_DELEGATE',
            'MANUAL_RESOLVE', 'MANUAL_TRANSFER', 'NODE_ARRIVED', 'NODE_COMPLETED',
            'PROCESS_COMPLETED')),
    ADD CONSTRAINT `chk_wf_copy_read_state`
        CHECK ((`read_status` = '0' AND `read_time` IS NULL)
            OR (`read_status` = '1' AND `read_time` IS NOT NULL));
