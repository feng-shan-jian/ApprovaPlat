package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.List;
import java.util.Set;
import org.flowable.engine.IdentityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfSqlDataSource;
import com.ruoyi.flowable.domain.dto.WorkflowSqlDataSourceRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfSqlDataSourceMapper;

/**
 * SQL 数据源白名单、环境引用、不可回退修订和摘要领域测试。
 */
class WorkflowSqlDataSourceServiceTest
{
    private WfSqlDataSourceMapper mapper;
    private WorkflowSqlDataSourceService service;

    /**
     * 建立真实工作流事务特征与可信用户边界。
     * @return void，初始化后可验证正式写事务路径
     */
    @BeforeEach
    void setUp()
    {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                Connection.TRANSACTION_REPEATABLE_READ);
        WorkflowIdentityResolver resolver = mock(WorkflowIdentityResolver.class);
        when(resolver.resolveCurrentIdentity())
                .thenReturn(new WorkflowCurrentIdentity("7", Set.of()));
        WorkflowEngineOperations operations = new WorkflowEngineOperations(
                new WorkflowAuthenticationContext(mock(IdentityService.class),
                        new WorkflowIdentityCodec()), new WorkflowExceptionTranslator(), resolver);
        mapper = mock(WfSqlDataSourceMapper.class);
        service = new WorkflowSqlDataSourceService(operations, mapper);
    }

    /**
     * 清理当前线程事务特征。
     * @return void，避免事务状态污染其他测试
     */
    @AfterEach
    void tearDown()
    {
        TransactionSynchronizationManager.clear();
    }

    /**
     * 验证主库目录规范化表白名单并生成可复算摘要。
     * @return void，持久化字段、审计身份或摘要漂移时测试失败
     */
    @Test
    void createsNormalizedPrimaryDataSource()
    {
        when(mapper.insert(any())).thenAnswer(invocation ->
        {
            WfSqlDataSource source = invocation.getArgument(0);
            source.setDataSourceId(12L);
            return 1;
        });

        Long id = service.create(request(" approva.primary ", "PRIMARY", null,
                null, null, List.of("WF_COPY", "sys_user")));

        assertThat(id).isEqualTo(12L);
        ArgumentCaptor<WfSqlDataSource> captor = ArgumentCaptor.forClass(WfSqlDataSource.class);
        verify(mapper).insert(captor.capture());
        WfSqlDataSource stored = captor.getValue();
        assertThat(stored.getDataSourceKey()).isEqualTo("approva.primary");
        assertThat(stored.getAllowedTables()).isEqualTo("sys_user,wf_copy");
        assertThat(stored.getRevisionNo()).isEqualTo(1);
        assertThat(stored.getCreateBy()).isEqualTo("7");
        assertThat(stored.getChecksum())
                .isEqualTo(WorkflowSqlDataSourceService.dataSourceChecksum(stored));
    }

    /**
     * 验证外库只接受受控环境引用，拒绝明文凭据、重复表和主库混入引用。
     * @return void，非法配置到达 Mapper 时测试失败
     */
    @Test
    void rejectsPlaintextCredentialsAndInvalidTables()
    {
        assertBadRequest(() -> service.create(request("approva.external", "EXTERNAL",
                "jdbc:mysql://localhost/test", "root", "password", List.of("wf_copy"))),
                "环境引用");
        assertBadRequest(() -> service.create(request("approva.primary", "PRIMARY",
                "WORKFLOW_SQL_JDBC_URL_TEST", null, null, List.of("wf_copy"))),
                "不能配置外部连接引用");
        assertBadRequest(() -> service.create(request("approva.primary", "PRIMARY", null,
                null, null, List.of("wf_copy", "WF_COPY"))), "重复");
        verify(mapper, never()).insert(any());
    }

    /**
     * 验证部署只接受已启用且摘要未漂移的数据源修订。
     * @return void，停用或篡改目录仍可冻结时测试失败
     */
    @Test
    void locksOnlyEnabledChecksumValidRevision()
    {
        WfSqlDataSource source = entity("approva.primary", 2, "ENABLED");
        source.setChecksum(WorkflowSqlDataSourceService.dataSourceChecksum(source));
        when(mapper.selectEnabledByKeyForUpdate("approva.primary")).thenReturn(source);
        assertThat(service.lockEnabledForDeployment("approva.primary")).isSameAs(source);

        source.setChecksum("0".repeat(64));
        assertConflict(() -> service.lockEnabledForDeployment("approva.primary"), "校验和不一致");
    }

    /**
     * 构造数据源请求。
     * @param key String，稳定逻辑键
     * @param type String，PRIMARY 或 EXTERNAL
     * @param urlRef String，可空 URL 引用
     * @param usernameRef String，可空用户名引用
     * @param passwordRef String，可空密码引用
     * @param tables List&lt;String&gt;，表白名单
     * @return WorkflowSqlDataSourceRequest，字段完整请求
     */
    private WorkflowSqlDataSourceRequest request(String key, String type, String urlRef,
            String usernameRef, String passwordRef, List<String> tables)
    {
        return new WorkflowSqlDataSourceRequest(key, "测试数据源", type, urlRef,
                usernameRef, passwordRef, tables, 1000, 10);
    }

    /**
     * 构造可复算摘要的数据源实体。
     * @param key String，稳定逻辑键
     * @param revision int，修订号
     * @param status String，目录状态
     * @return WfSqlDataSource，字段完整实体
     */
    private WfSqlDataSource entity(String key, int revision, String status)
    {
        WfSqlDataSource source = new WfSqlDataSource();
        source.setDataSourceId(12L);
        source.setDataSourceKey(key);
        source.setDataSourceName("测试数据源");
        source.setConnectionType("PRIMARY");
        source.setAllowedTables("wf_copy");
        source.setConnectTimeoutMs(1000);
        source.setQueryTimeoutSeconds(10);
        source.setRevisionNo(revision);
        source.setStatus(status);
        return source;
    }

    /**
     * 断言领域调用以 400 拒绝。
     * @param command Runnable，待执行命令
     * @param message String，预期消息片段
     * @return void，状态或消息不一致时测试失败
     */
    private void assertBadRequest(Runnable command, String message)
    {
        assertThatThrownBy(command::run)
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining(message);
    }

    /**
     * 断言领域调用以 409 拒绝。
     * @param command Runnable，待执行命令
     * @param message String，预期消息片段
     * @return void，状态或消息不一致时测试失败
     */
    private void assertConflict(Runnable command, String message)
    {
        assertThatThrownBy(command::run)
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining(message);
    }
}
