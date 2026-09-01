package com.ruoyi.flowable.mapper;

import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;

/** 在 H2 MySQL 模式中直接执行正式出站 Mapper XML 的能力合同。 */
class WfCollaborationOutboxCapabilityMapperTest
{
    /**
     * 执行完整状态、数据库租约、条件更新和 revision CAS 合同。
     * @return void，测试结束显式关闭 H2 内存库
     * @throws Exception Mapper 合同或数据库清理失败时向 JUnit 报告
     */
    @Test
    void honorsCapabilityContractInH2MySqlMode() throws Exception
    {
        JdbcDataSource dataSource = WorkflowH2SchemaMapperSupport.createDataSource(
                "collaboration-outbox-capability", true, 10000);
        try
        {
            WorkflowH2SchemaMapperSupport.executeSchema(dataSource,
                    "db/h2/wf_collaboration_outbox_capability.sql");
            SqlSessionFactory sessionFactory = WorkflowH2SchemaMapperSupport.createSessionFactory(
                    dataSource, new JdbcTransactionFactory(), "collaboration-outbox-h2",
                    WfCollaborationOutboxMapper.class,
                    "mapper/flowable/WfCollaborationOutboxMapper.xml");
            try (WfCollaborationOutboxCapabilityMapperContract contract =
                    new WfCollaborationOutboxCapabilityMapperContract(
                            sessionFactory, 1L, "0".repeat(64)))
            {
                contract.verifyAll();
            }
        }
        finally
        {
            WorkflowH2SchemaMapperSupport.shutdown(dataSource);
        }
    }
}
