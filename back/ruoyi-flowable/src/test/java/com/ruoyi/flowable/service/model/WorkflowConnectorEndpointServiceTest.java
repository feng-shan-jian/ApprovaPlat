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
import com.ruoyi.flowable.domain.WfConnectorEndpoint;
import com.ruoyi.flowable.domain.dto.WorkflowConnectorEndpointRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfConnectorEndpointMapper;

/**
 * HTTP 连接器端点白名单、修订和摘要领域服务测试。
 */
class WorkflowConnectorEndpointServiceTest
{
    private WfConnectorEndpointMapper mapper;
    private WorkflowConnectorEndpointService service;

    /**
     * 建立真实工作流事务与可信用户边界，Mapper 使用可核验替身。
     * @return void，初始化后可执行写事务路径
     */
    @BeforeEach
    void setUp()
    {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                Connection.TRANSACTION_REPEATABLE_READ);
        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveCurrentIdentity())
                .thenReturn(new WorkflowCurrentIdentity("7", Set.of()));
        WorkflowEngineOperations operations = new WorkflowEngineOperations(
                new WorkflowAuthenticationContext(mock(IdentityService.class),
                        new WorkflowIdentityCodec()),
                new WorkflowExceptionTranslator(), identityResolver);
        mapper = mock(WfConnectorEndpointMapper.class);
        service = new WorkflowConnectorEndpointService(operations, mapper);
    }

    /**
     * 清理当前线程事务特征，避免污染后续测试。
     * @return void，清理后线程不携带模拟事务
     */
    @AfterEach
    void tearDown()
    {
        TransactionSynchronizationManager.clear();
    }

    /**
     * 验证新增端点规范化 URL、方法、路径和审计身份，并生成可复算摘要。
     * @return void，持久化字段或摘要协议漂移时测试失败
     */
    @Test
    void createsNormalizedEndpointRevision()
    {
        when(mapper.insert(any())).thenAnswer(invocation ->
        {
            WfConnectorEndpoint endpoint = invocation.getArgument(0);
            endpoint.setEndpointId(11L);
            return 1;
        });

        Long endpointId = service.create(request(" approva.audit ", "HTTP://LOCALHOST:8089/",
                List.of("POST", "GET"), "/api/", "NONE", null, null, "PRIVATE"));

        assertThat(endpointId).isEqualTo(11L);
        ArgumentCaptor<WfConnectorEndpoint> captor =
                ArgumentCaptor.forClass(WfConnectorEndpoint.class);
        verify(mapper).insert(captor.capture());
        WfConnectorEndpoint stored = captor.getValue();
        assertThat(stored.getEndpointKey()).isEqualTo("approva.audit");
        assertThat(stored.getBaseUrl()).isEqualTo("http://localhost:8089");
        assertThat(stored.getAllowedMethods()).isEqualTo("GET,POST");
        assertThat(stored.getPathPrefix()).isEqualTo("/api");
        assertThat(stored.getRevisionNo()).isEqualTo(1);
        assertThat(stored.getStatus()).isEqualTo("ENABLED");
        assertThat(stored.getCreateBy()).isEqualTo("7");
        assertThat(stored.getChecksum())
                .isEqualTo(WorkflowConnectorEndpointService.endpointChecksum(stored));
    }

    /**
     * 验证修订必须锁定当前行、保持稳定键并以乐观版本条件发布下一修订。
     * @return void，稳定键或修订并发协议漂移时测试失败
     */
    @Test
    void publishesNextRevisionWithoutChangingStableKey()
    {
        WfConnectorEndpoint current = endpoint("approva.audit", 3, "ENABLED");
        when(mapper.selectByIdForUpdate(11L)).thenReturn(current);
        when(mapper.updateRevision(any(), org.mockito.ArgumentMatchers.eq(3))).thenReturn(1);

        Integer revision = service.update(11L, request("approva.audit", "https://api.example.com",
                List.of("POST"), "/v2", "BEARER", "WORKFLOW_CONNECTOR_SECRET_AUDIT",
                null, "PUBLIC"));

        assertThat(revision).isEqualTo(4);
        ArgumentCaptor<WfConnectorEndpoint> captor =
                ArgumentCaptor.forClass(WfConnectorEndpoint.class);
        verify(mapper).updateRevision(captor.capture(), org.mockito.ArgumentMatchers.eq(3));
        assertThat(captor.getValue().getRevisionNo()).isEqualTo(4);
        assertThat(captor.getValue().getUpdateBy()).isEqualTo("7");

        assertConflict(() -> service.update(11L, request("approva.changed",
                "https://api.example.com", List.of("POST"), "/v2", "NONE", null,
                null, "PUBLIC")), "稳定键不允许修改");
    }

    /**
     * 验证认证组合、超时、网络范围和编码路径穿越均由领域层失败关闭。
     * @return void，非法端点进入持久化层时测试失败
     */
    @Test
    void rejectsInvalidSecurityAndBoundaryConfiguration()
    {
        assertBadRequest(() -> service.update(0L, request("approva.bad",
                "https://api.example.com", List.of("POST"), "/api", "NONE", null,
                null, "PUBLIC")), "主键");
        assertBadRequest(() -> service.create(request("approva.bad", "https://api.example.com",
                List.of("POST"), "/api/%2e%2e/admin", "NONE", null, null, "PUBLIC")),
                "路径前缀");
        assertBadRequest(() -> service.create(request("approva.bad", "https://api.example.com",
                List.of("POST"), "/api", "BEARER", "literal-secret", null, "PUBLIC")),
                "密钥引用");
        assertBadRequest(() -> service.create(request("approva.bad", "https://api.example.com",
                List.of("POST"), "/api", "NONE", null, null, "INTERNAL")),
                "网络范围");
        WorkflowConnectorEndpointRequest invalidTimeout = new WorkflowConnectorEndpointRequest(
                "approva.bad", "非法端点", "https://api.example.com", List.of("POST"),
                "/api", "NONE", null, null, 99, 500, "PUBLIC");
        assertBadRequest(() -> service.create(invalidTimeout), "超时配置");
        verify(mapper, never()).insert(any());
    }

    /**
     * 验证部署锁定只接受启用且摘要完整的当前修订。
     * @return void，停用或被篡改端点仍可部署时测试失败
     */
    @Test
    void locksOnlyEnabledChecksumValidEndpointForDeployment()
    {
        WfConnectorEndpoint endpoint = endpoint("approva.audit", 2, "ENABLED");
        endpoint.setChecksum(WorkflowConnectorEndpointService.endpointChecksum(endpoint));
        when(mapper.selectEnabledByKeyForUpdate("approva.audit")).thenReturn(endpoint);

        assertThat(service.lockEnabledForDeployment("approva.audit")).isSameAs(endpoint);

        endpoint.setChecksum("0".repeat(64));
        assertConflict(() -> service.lockEnabledForDeployment("approva.audit"), "校验和不一致");
        when(mapper.selectEnabledByKeyForUpdate("approva.missing")).thenReturn(null);
        assertConflict(() -> service.lockEnabledForDeployment("approva.missing"), "不存在或已停用");
    }

    /**
     * 构造常用端点请求。
     * @param key String，稳定端点键
     * @param baseUrl String，基础 URL
     * @param methods List&lt;String&gt;，允许方法
     * @param pathPrefix String，路径前缀
     * @param authType String，认证类型
     * @param secretRef String，可空外部密钥引用
     * @param apiKeyHeader String，可空 API Key 请求头
     * @param networkScope String，网络范围
     * @return WorkflowConnectorEndpointRequest，字段完整请求
     */
    private WorkflowConnectorEndpointRequest request(String key, String baseUrl,
            List<String> methods, String pathPrefix, String authType, String secretRef,
            String apiKeyHeader, String networkScope)
    {
        return new WorkflowConnectorEndpointRequest(key, "审计回调", baseUrl, methods,
                pathPrefix, authType, secretRef, apiKeyHeader, 1000, 3000, networkScope);
    }

    /**
     * 构造可复算摘要的端点实体。
     * @param key String，稳定端点键
     * @param revision int，修订号
     * @param status String，端点状态
     * @return WfConnectorEndpoint，字段完整实体
     */
    private WfConnectorEndpoint endpoint(String key, int revision, String status)
    {
        WfConnectorEndpoint endpoint = new WfConnectorEndpoint();
        endpoint.setEndpointId(11L);
        endpoint.setEndpointKey(key);
        endpoint.setEndpointName("审计回调");
        endpoint.setBaseUrl("https://api.example.com");
        endpoint.setAllowedMethods("POST");
        endpoint.setPathPrefix("/api");
        endpoint.setAuthType("NONE");
        endpoint.setConnectTimeoutMs(1000);
        endpoint.setRequestTimeoutMs(3000);
        endpoint.setNetworkScope("PUBLIC");
        endpoint.setRevisionNo(revision);
        endpoint.setStatus(status);
        return endpoint;
    }

    /**
     * 断言业务调用以 400 拒绝。
     * @param command Runnable，待执行命令
     * @param message String，预期提示片段
     * @return void，异常状态或提示不匹配时测试失败
     */
    private void assertBadRequest(Runnable command, String message)
    {
        assertThatThrownBy(command::run)
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining(message);
    }

    /**
     * 断言业务调用以 409 拒绝。
     * @param command Runnable，待执行命令
     * @param message String，预期提示片段
     * @return void，异常状态或提示不匹配时测试失败
     */
    private void assertConflict(Runnable command, String message)
    {
        assertThatThrownBy(command::run)
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining(message);
    }
}
