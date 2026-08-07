-- BPMN 设计器用户偏好正式表。
-- 本脚本只幂等创建缺失表，不读取浏览器本地状态，也不删除或覆盖现有业务数据。

CREATE TABLE IF NOT EXISTS `wf_designer_preference`
(
    `user_id`                  BIGINT      NOT NULL COMMENT '若依正式用户主键',
    `theme`                    VARCHAR(16) NOT NULL DEFAULT 'SYSTEM' COMMENT '设计器主题：LIGHT、DARK、SYSTEM',
    `grid_enabled`             TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '是否显示并启用网格吸附',
    `minimap_enabled`          TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '是否显示小地图',
    `lint_enabled`             TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '是否启用客户端 Lint',
    `token_simulation_enabled` TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否启用 Token 流程模拟',
    `properties_collapsed`     TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否折叠右侧属性面板',
    `create_time`              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次创建时间',
    `update_time`              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最近更新时间',
    PRIMARY KEY (`user_id`),
    CONSTRAINT `fk_wf_designer_preference_user` FOREIGN KEY (`user_id`)
        REFERENCES `sys_user` (`user_id`) ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT `chk_wf_designer_preference_theme` CHECK (`theme` IN ('LIGHT', 'DARK', 'SYSTEM')),
    CONSTRAINT `chk_wf_designer_preference_flags` CHECK
    (
        `grid_enabled` IN (0, 1)
        AND `minimap_enabled` IN (0, 1)
        AND `lint_enabled` IN (0, 1)
        AND `token_simulation_enabled` IN (0, 1)
        AND `properties_collapsed` IN (0, 1)
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'BPMN 设计器用户偏好';
