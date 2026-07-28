-- 已存在 Flowable 8 目标库的工作流附件配额并发门禁增量脚本。
-- 本脚本幂等新增 guard 表并预置固定全局行，不修改附件历史数据；执行后必须运行正式业务表验收脚本。

CREATE TABLE IF NOT EXISTS `wf_attachment_quota_guard`
(
    `owner_user_id` BIGINT      NOT NULL COMMENT '配额互斥主键：0 为全局容量，其余为正式用户主键',
    `create_time`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次创建配额互斥行的时间',
    PRIMARY KEY (`owner_user_id`),
    CONSTRAINT `chk_wf_attachment_quota_guard_owner` CHECK (`owner_user_id` >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '工作流附件全局及用户配额事务互斥行，正常生命周期内不得删除';

-- 全局行必须在恢复工作流写入前存在，避免上传事务通过 INSERT IGNORE 竞争创建后升级行锁。
INSERT IGNORE INTO `wf_attachment_quota_guard` (`owner_user_id`)
VALUES (0);
