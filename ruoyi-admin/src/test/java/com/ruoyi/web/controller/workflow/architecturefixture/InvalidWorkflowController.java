package com.ruoyi.web.controller.workflow.architecturefixture;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 仅供 ArchUnit 自检使用的非法依赖夹具，证明 Controller 直连 JDBC 会被正式规则拒绝。
 */
public class InvalidWorkflowController
{
    /** 故意构造的非法 JDBC 依赖，不参与任何生产代码或 Spring 容器。 */
    private JdbcTemplate jdbcTemplate;
}
