package com.ruoyi.flowable.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import com.ruoyi.flowable.domain.WfBpmnExtension;
import com.ruoyi.flowable.domain.WfBpmnExtensionVersion;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;
import com.ruoyi.flowable.testsupport.WorkflowMySqlITSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** R12：管理页实际使用的 Mapper 必须能从真实 MySQL 构造结果，不能以手工 new VO 替代。 */
class WfBpmnExtensionManagementMySqlIT
{
    private static WfBpmnExtensionMapper mapper;
    private static TransactionTemplate transaction;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void connect() throws Exception
    {
        var dataSource = WorkflowMySqlITSupport.createDataSource();
        WorkflowMySqlITSupport.verifyIsolatedBaseline(dataSource, "扩展注册表读取回归",
                "approvaplat_it", List.of("wf_bpmn_extension", "wf_bpmn_extension_version"));
        mapper = WorkflowH2SchemaMapperSupport.createSpringMapper(dataSource,
                "extension-management-mysql", WfBpmnExtensionMapper.class,
                "mapper/flowable/WfBpmnExtensionMapper.xml");
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc = new JdbcTemplate(dataSource);
    }

    @ParameterizedTest(name = "status={0}, creator={1}, version={2}, deleteAllowed={3}, deleteCheckAllowed={4}")
    @CsvSource({ "ENABLED,qa,true,false,false", "DISABLED,qa,true,false,true",
        "DISABLED,system,true,false,false", "DISABLED,qa,false,true,true",
        "ENABLED,qa,false,false,false", "DISABLED,system,false,false,false" })
    void readsManagementRowsAndLatestVersion(String status, String creator,
            boolean hasVersion, boolean deleteAllowed, boolean deleteCheckAllowed)
    {
        String key = "qa_registry_" + UUID.randomUUID().toString().replace("-", "");
        try
        {
            transaction.executeWithoutResult(tx ->
            {
                // Fixtures and assertions share one real transaction; failures still roll back all rows.
                tx.setRollbackOnly();
                WfBpmnExtension extension = new WfBpmnExtension();
                extension.setExtensionKey(key);
                extension.setExtensionName("注册表读取回归");
                extension.setExtensionType("JAVA");
                extension.setStatus(status);
                extension.setCreateBy(creator);
                assertThat(mapper.insertExtension(extension)).isOne();
                if (hasVersion)
                {
                    for (int number : List.of(1, 2))
                    {
                        WfBpmnExtensionVersion version = new WfBpmnExtensionVersion();
                        version.setExtensionId(extension.getExtensionId());
                        version.setVersionNo(number);
                        version.setImplementationKey("QA_HANDLER_" + number);
                        version.setConfigSchema("{}");
                        version.setChecksum("a".repeat(64));
                        version.setCreateBy("qa");
                        assertThat(mapper.insertVersion(version)).isOne();
                    }
                }
                var rows = mapper.selectManagementList();
                assertThat(rows).filteredOn(row -> key.equals(row.extensionKey()))
                        .singleElement().satisfies(row ->
                        {
                            assertThat(row.status()).isEqualTo(status);
                            assertThat(row.deleteAllowed()).isEqualTo(deleteAllowed);
                            assertThat(row.deleteCheckAllowed()).isEqualTo(deleteCheckAllowed);
                            assertThat(row.updateTime()).isNotNull();
                            if (hasVersion)
                            {
                                assertThat(row.versionNo()).isEqualTo(2);
                                assertThat(row.implementationKey()).isEqualTo("QA_HANDLER_2");
                            }
                            else
                            {
                                assertThat(row.versionId()).isNull();
                                assertThat(row.versionNo()).isNull();
                            }
                        });
            });
        }
        finally
        {
            assertThat(jdbc.queryForObject(
                    "select count(*) from wf_bpmn_extension where extension_key=?", Long.class, key)).isZero();
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "CEL", "HTTP", "SQL", "FORM_FIELD" })
    void nonJavaManagementProjectionHidesImplementationWithoutChangingVersion(String type)
    {
        String key = "qa_projection_" + UUID.randomUUID().toString().replace("-", "");
        try
        {
            transaction.executeWithoutResult(tx ->
            {
                tx.setRollbackOnly();
                WfBpmnExtension extension = new WfBpmnExtension();
                extension.setExtensionKey(key);
                extension.setExtensionName("固定实现投影回归");
                extension.setExtensionType(type);
                extension.setStatus("ENABLED");
                extension.setCreateBy("qa");
                mapper.insertExtension(extension);
                WfBpmnExtensionVersion version = new WfBpmnExtensionVersion();
                version.setExtensionId(extension.getExtensionId());
                version.setVersionNo(1);
                version.setImplementationKey("QA_FIXED_IMPLEMENTATION");
                version.setConfigSchema("{}");
                version.setChecksum("a".repeat(64));
                version.setCreateBy("qa");
                mapper.insertVersion(version);
                assertThat(mapper.selectManagementList()).filteredOn(row -> key.equals(row.extensionKey()))
                        .singleElement().satisfies(row -> assertThat(row.implementationKey()).isNull());
                assertThat(mapper.selectLatestEnabledByKey(key).implementationKey()).isEqualTo("QA_FIXED_IMPLEMENTATION");
            });
        }
        finally
        {
            assertThat(jdbc.queryForObject("select count(*) from wf_bpmn_extension where extension_key=?", Long.class, key)).isZero();
        }
    }
}
