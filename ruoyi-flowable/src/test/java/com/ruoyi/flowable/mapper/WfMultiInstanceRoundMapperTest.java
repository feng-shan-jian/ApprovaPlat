package com.ruoyi.flowable.mapper;

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;

/**
 * 在 H2 MySQL 兼容模式中快速执行轮次 Mapper 数据库中立契约。
 *
 * H2 仅提供事务和正式 Mapper XML 的快速反馈；MySQL JSON/CHECK、STORED 生成列、
 * 排序规则、数据库时钟和 InnoDB 并发仍由 MySQL 环境类独立验收。
 */
class WfMultiInstanceRoundMapperTest
{
    private static JdbcDataSource dataSource;
    private static SqlSessionFactory h2SessionFactory;

    /**
     * 创建独立 H2 数据源、轮次表并加载正式 Mapper XML。
     *
     * @return void，当前环境向继承的契约提供 SqlSessionFactory
     * @throws Exception 建表或 Mapper XML 加载失败时向 JUnit 报告
     */
    @BeforeAll
    static void setUpFactory() throws Exception
    {
        dataSource = WorkflowH2SchemaMapperSupport.createDataSource(
                "wf_multi_instance_round_contract", true, 5000);
        WorkflowH2SchemaMapperSupport.executeSchema(dataSource,
                WorkflowH2SchemaMapperSupport.MULTI_INSTANCE_ROUND_SCHEMA);
        h2SessionFactory = WorkflowH2SchemaMapperSupport.createSessionFactory(dataSource,
                new JdbcTransactionFactory(), "round-h2",
                WfMultiInstanceRoundMapper.class,
                "mapper/flowable/WfMultiInstanceRoundMapper.xml");
    }

    /**
     * 显式关闭带 DB_CLOSE_DELAY=-1 的 H2 数据库。
     *
     * @return void，不保留内存数据库
     */
    @AfterAll
    static void tearDownFactory()
    {
        WorkflowH2SchemaMapperSupport.shutdown(dataSource);
    }

    /**
     * 每个契约场景前清空轮次表，避免自然键和主键状态跨用例污染。
     *
     * @return void，当前场景从空表开始
     * @throws Exception 清理失败时向 JUnit 报告
     */
    @BeforeEach
    void clearTable() throws Exception
    {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement())
        {
            statement.executeUpdate("delete from wf_multi_instance_round");
        }
    }

    /**
     * 以组合方式执行 H2 上的完整 Mapper/CAS 合同，不通过父类共享测试状态。
     *
     * @return void，任一数据库中立合同漂移时失败
     * @throws Exception 并发合同等待失败时向 JUnit 报告
     */
    @Test
    void honorsSharedMapperContract() throws Exception
    {
        try (WfMultiInstanceRoundMapperContract contract =
                new WfMultiInstanceRoundMapperContract(h2SessionFactory,
                        LocalDateTime.now().withNano(0)))
        {
            contract.verifyAll();
        }
    }
}
