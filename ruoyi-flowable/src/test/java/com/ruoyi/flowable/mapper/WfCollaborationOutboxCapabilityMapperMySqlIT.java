package com.ruoyi.flowable.mapper;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ruoyi.flowable.testsupport.WorkflowMySqlITSupport;

/** 在显式隔离 MySQL 8 基线上执行正式出站能力、数据库时钟和租约边界合同。 */
class WfCollaborationOutboxCapabilityMapperMySqlIT
{
    private static final String ENDPOINT_KEY = "collab-capability-it";
    private static final String CHANNEL_ID = "c".repeat(64);
    private static final String SOURCE_KEY = "collab-capability-it";
    private static MysqlDataSource dataSource;
    private static SqlSessionFactory sessionFactory;
    private long endpointId;

    /**
     * 校验真实 MySQL 隔离基线并加载生产 Mapper XML。
     * @return void，不创建或修改正式表结构
     * @throws Exception 环境门禁、连接或 XML 解析失败时向 JUnit 报告
     */
    @BeforeAll
    static void setUpMySql() throws Exception
    {
        dataSource = WorkflowMySqlITSupport.createDataSource();
        WorkflowMySqlITSupport.verifyIsolatedBaseline(dataSource,
                "协作 outbox 能力 Mapper IT", null,
                List.of("wf_connector_endpoint", "wf_collaboration_channel",
                        "wf_collaboration_outbox"));
        Environment environment = new Environment("collaboration-outbox-mysql",
                new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(WfCollaborationOutboxMapper.class);
        String mapperResource = "mapper/flowable/WfCollaborationOutboxMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(mapperResource))
        {
            new XMLMapperBuilder(input, configuration, mapperResource,
                    configuration.getSqlFragments()).parse();
        }
        sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    /**
     * 为当前场景创建满足正式外键与 CHECK 的端点和通道前置记录。
     * @return void，保存本次生成的端点主键供 outbox 合同使用
     * @throws SQLException 前置数据装配失败时向 JUnit 报告
     */
    @BeforeEach
    void setUpFixtures() throws SQLException
    {
        clearFixtures();
        try (Connection connection = dataSource.getConnection())
        {
            try (PreparedStatement endpoint = connection.prepareStatement(
                    "insert into wf_connector_endpoint(endpoint_key,endpoint_name,base_url,"
                    + "allowed_methods,path_prefix,auth_type,connect_timeout_ms,request_timeout_ms,"
                    + "network_scope,revision_no,status,checksum,create_by) "
                    + "values(?,?,?,?,?,'NONE',1000,5000,'PUBLIC',1,'ENABLED',?,?)",
                    Statement.RETURN_GENERATED_KEYS))
            {
                endpoint.setString(1, ENDPOINT_KEY);
                endpoint.setString(2, "协作能力 MySQL IT");
                endpoint.setString(3, "https://collab-capability.invalid");
                endpoint.setString(4, "POST");
                endpoint.setString(5, "/workflow/runtime-event/collaboration");
                endpoint.setString(6, "1".repeat(64));
                endpoint.setString(7, "workflow-it");
                assertEquals(1, endpoint.executeUpdate());
                try (ResultSet keys = endpoint.getGeneratedKeys())
                {
                    assertTrue(keys.next());
                    endpointId = keys.getLong(1);
                }
            }
            try (PreparedStatement channel = connection.prepareStatement(
                    "insert into wf_collaboration_channel(channel_id,"
                    + "target_process_definition_key,correlation_type,correlation_value,"
                    + "outbound_sequence,inbound_sequence,revision_no) "
                    + "values(?,?,'BUSINESS_KEY',?,0,0,0)"))
            {
                channel.setString(1, CHANNEL_ID);
                channel.setString(2, "capability-target");
                channel.setString(3, "collab-capability-it");
                assertEquals(1, channel.executeUpdate());
            }
        }
    }

    /**
     * 清理当前测试使用的精确前缀和前置记录。
     * @return void，不触碰隔离库中的其他验收数据
     * @throws SQLException 清理失败时向 JUnit 报告
     */
    @AfterEach
    void clearAfter() throws SQLException
    {
        clearFixtures();
    }

    /**
     * 在真实 MySQL 上执行与 H2 相同的完整能力和 CAS 合同。
     * @return void，数据库时钟和 lease_until 等号边界由 MySQL 实际执行
     * @throws Exception 合同或数据库访问失败时向 JUnit 报告
     */
    @Test
    void honorsCapabilityContractWithMySqlDatabaseClock() throws Exception
    {
        try (WfCollaborationOutboxCapabilityMapperContract contract =
                new WfCollaborationOutboxCapabilityMapperContract(
                        sessionFactory, endpointId, CHANNEL_ID))
        {
            contract.verifyAll();
            contract.verifyExactLeaseBoundaryWithFixedMySqlClock();
        }
    }

    /**
     * 按外键反向顺序删除当前 IT 的精确数据集合。
     * @return void，不删除任何非当前 SOURCE_KEY/主键的数据
     * @throws SQLException 清理失败时向 JUnit 报告
     */
    private void clearFixtures() throws SQLException
    {
        try (Connection connection = dataSource.getConnection())
        {
            try (PreparedStatement outbox = connection.prepareStatement(
                    "delete from wf_collaboration_outbox where source_process_definition_key=?"))
            {
                outbox.setString(1, SOURCE_KEY);
                outbox.executeUpdate();
            }
            try (PreparedStatement channel = connection.prepareStatement(
                    "delete from wf_collaboration_channel where channel_id=?"))
            {
                channel.setString(1, CHANNEL_ID);
                channel.executeUpdate();
            }
            try (PreparedStatement endpoint = connection.prepareStatement(
                    "delete from wf_connector_endpoint where endpoint_key=?"))
            {
                endpoint.setString(1, ENDPOINT_KEY);
                endpoint.executeUpdate();
            }
        }
    }
}
