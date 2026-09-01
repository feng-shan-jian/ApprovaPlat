package com.ruoyi.flowable.service.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfIntegrationCredential;
import com.ruoyi.flowable.domain.dto.WorkflowIntegrationCredentialCreateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowIntegrationCredentialRotateRequest;
import com.ruoyi.flowable.domain.vo.WorkflowIntegrationCredentialSecretView;
import com.ruoyi.flowable.domain.vo.WorkflowIntegrationCredentialView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.mapper.WfIntegrationCredentialMapper;
import com.ruoyi.flowable.runtime.WorkflowCredentialRateLimitMetrics;
import com.ruoyi.flowable.runtime.WorkflowRedisAtomicOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 验证集成账号状态与操作能力由服务端统一投影，并继续执行最终写入门禁。 */
class WorkflowIntegrationCredentialCapabilityProjectionTest
{
    private static final Instant NOW = Instant.parse("2026-09-01T04:00:00Z");
    private static final String TOKEN = "abcdefghijkl-valid-integration-token-1234567890";
    private static final WorkflowCurrentIdentity ACTOR =
            new WorkflowCurrentIdentity("42", Set.of());

    /**
     * 验证 ACTIVE、EXPIRED、REVOKED 完整矩阵、精确到期边界和吊销优先级。
     * @return void，列表中任一状态或能力偏离矩阵时测试失败
     */
    @Test
    void projectsCompleteMatrixFromOneListTimeSnapshot()
    {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW);
        ServiceHarness harness = harness(clock);
        List<WfIntegrationCredential> rows = List.of(
                credential(1L, null, null),
                credential(2L, NOW.plusSeconds(1), null),
                credential(3L, NOW, null),
                credential(4L, NOW.minusSeconds(1), null),
                credential(5L, NOW.minusSeconds(1), NOW.minusSeconds(2)));
        when(harness.mapper().selectList()).thenReturn(rows);

        List<WorkflowIntegrationCredentialView> views = harness.service().list();

        assertCapability(views.get(0), "ACTIVE", true, true);
        assertCapability(views.get(1), "ACTIVE", true, true);
        assertCapability(views.get(2), "EXPIRED", false, false);
        assertCapability(views.get(3), "EXPIRED", false, false);
        assertCapability(views.get(4), "REVOKED", false, false);
        assertEquals(rows.get(2).getExpiresAt(), views.get(2).expiresAt());
        assertEquals(rows.get(4).getRevokedAt(), views.get(4).revokedAt());
        verify(clock, times(1)).instant();
    }

    /**
     * 验证列表、创建和轮换的正式响应均携带严格布尔能力，且明文 Token 仍仅在秘密响应中出现。
     * @return void，任一响应缺少状态能力或写端 CAS 路径变化时测试失败
     */
    @Test
    void includesCapabilitiesInListCreateAndRotateResponses()
    {
        ServiceHarness listHarness = harness(fixedClock());
        when(listHarness.mapper().selectList()).thenReturn(
                List.of(credential(10L, null, null)));
        WorkflowIntegrationCredentialView listed = listHarness.service().list().get(0);
        assertCapability(listed, "ACTIVE", true, true);

        ServiceHarness createHarness = harness(fixedClock());
        doAnswer(invocation ->
        {
            WfIntegrationCredential inserted = invocation.getArgument(0);
            inserted.setCredentialId(11L);
            return 1;
        }).when(createHarness.mapper()).insert(any(WfIntegrationCredential.class));
        WorkflowIntegrationCredentialSecretView created = createHarness.service().create(
                new WorkflowIntegrationCredentialCreateRequest("Webhook", List.of("MESSAGE"),
                        List.of("businessKey"), 60, null));
        assertNotNull(created.token());
        assertCapability(created.credential(), "ACTIVE", true, true);

        ServiceHarness rotateHarness = harness(fixedClock());
        WfIntegrationCredential active = credential(12L, NOW.plusSeconds(3600), null);
        when(rotateHarness.mapper().selectByIdForUpdate(12L)).thenReturn(active);
        when(rotateHarness.mapper().rotate(any(WfIntegrationCredential.class), eq(1)))
                .thenReturn(1);
        WorkflowIntegrationCredentialSecretView rotated = rotateHarness.service().rotate(
                12L, new WorkflowIntegrationCredentialRotateRequest(null));
        assertNotNull(rotated.token());
        assertEquals(2, rotated.revisionNo());
        assertCapability(rotated.credential(), "ACTIVE", true, true);
        verify(rotateHarness.mapper()).rotate(active, 1);
    }

    /**
     * 验证到期或吊销账号的轮换、吊销仍返回原有 409，且状态门禁前不产生 Mapper 写入。
     * @return void，非法状态若进入 rotate 或 revoke 写入则测试失败
     */
    @Test
    void rejectsExpiredAndRevokedManagementWrites()
    {
        List<RejectedState> states = List.of(
                new RejectedState(credential(20L, NOW, null), "集成账号已经到期"),
                new RejectedState(credential(21L, NOW.minusSeconds(1), NOW.minusSeconds(2)),
                        "集成账号已经吊销"));

        for (RejectedState state : states)
        {
            ServiceHarness rotateHarness = harness(fixedClock());
            when(rotateHarness.mapper().selectByIdForUpdate(state.credential().getCredentialId()))
                    .thenReturn(state.credential());
            assertConflict(state.message(), () -> rotateHarness.service().rotate(
                    state.credential().getCredentialId(),
                    new WorkflowIntegrationCredentialRotateRequest(null)));
            verify(rotateHarness.mapper(), never()).rotate(any(WfIntegrationCredential.class),
                    any());

            ServiceHarness revokeHarness = harness(fixedClock());
            when(revokeHarness.mapper().selectByIdForUpdate(state.credential().getCredentialId()))
                    .thenReturn(state.credential());
            assertConflict(state.message(), () -> revokeHarness.service().revoke(
                    state.credential().getCredentialId()));
            verify(revokeHarness.mapper(), never()).revoke(anyLong(), anyString());
        }
    }

    /**
     * 验证 JSON 合同使用 status/rotateAllowed/revokeAllowed 原名，能力值保持 JSON boolean 类型。
     * @return void，字段改名或被序列化为数字、字符串时测试失败
     * @throws Exception Jackson 序列化或解析失败时测试直接失败
     */
    @Test
    void serializesCapabilityFieldsWithStrictNamesAndTypes() throws Exception
    {
        ServiceHarness harness = harness(fixedClock());
        when(harness.mapper().selectList()).thenReturn(
                List.of(credential(30L, null, null)));
        WorkflowIntegrationCredentialView view = harness.service().list().get(0);

        JsonNode json = JsonMapper.shared().readTree(
                JsonMapper.shared().writeValueAsString(view));

        assertTrue(json.get("status").isString());
        assertEquals("ACTIVE", json.get("status").asString());
        assertTrue(json.get("rotateAllowed").isBoolean());
        assertTrue(json.get("rotateAllowed").asBoolean());
        assertTrue(json.get("revokeAllowed").isBoolean());
        assertTrue(json.get("revokeAllowed").asBoolean());
    }

    /**
     * 验证过期和吊销 Token 认证继续统一返回 401，不向调用方泄露具体失效原因。
     * @return void，认证错误或 Redis 副作用出现状态差异时测试失败
     */
    @Test
    void keepsExpiredAndRevokedAuthenticationFailuresUniform()
    {
        for (WfIntegrationCredential credential : List.of(
                credential(40L, NOW, null),
                credential(41L, NOW.minusSeconds(1), NOW.minusSeconds(2))))
        {
            credential.setTokenPrefix(TOKEN.substring(0, 12));
            credential.setTokenHash(sha256(TOKEN));
            ServiceHarness harness = harness(fixedClock());
            when(harness.mapper().selectByPrefix(TOKEN.substring(0, 12)))
                    .thenReturn(credential);

            ServiceException exception = assertThrows(ServiceException.class,
                    () -> harness.service().authenticateAndConsume(TOKEN, "MESSAGE"));

            assertEquals(HttpStatus.UNAUTHORIZED, exception.getCode());
            assertEquals("INTEGRATION_TOKEN_INVALID", exception.getSubCode());
            assertEquals("集成 Token 无效或已失效", exception.getMessage());
            verifyNoInteractions(harness.redisOperations());
        }
    }

    /**
     * 创建具备同步读写引擎边界、Mapper 和可注入时钟的服务夹具。
     * @param clock Clock，测试控制的服务端时钟
     * @return ServiceHarness，可调用正式服务并验证协作者的夹具
     */
    private ServiceHarness harness(Clock clock)
    {
        WfIntegrationCredentialMapper mapper = mock(WfIntegrationCredentialMapper.class);
        WorkflowRedisAtomicOperations redisOperations =
                mock(WorkflowRedisAtomicOperations.class);
        WorkflowIntegrationCredentialService service =
                new WorkflowIntegrationCredentialService(engineOperations(), mapper,
                        redisOperations, mock(WorkflowCredentialRateLimitMetrics.class),
                        mock(SecureRandom.class), clock);
        return new ServiceHarness(service, mapper, redisOperations);
    }

    /**
     * 创建只执行回调本身的工作流引擎边界 mock，保留服务公开入口与事务编排代码。
     * @return WorkflowEngineOperations，支持 read 和 writeAsCurrentUser 的同步执行器
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private WorkflowEngineOperations engineOperations()
    {
        WorkflowEngineOperations operations = mock(WorkflowEngineOperations.class);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get())
                .when(operations).read(any(Supplier.class));
        doAnswer(invocation -> ((Function<WorkflowCurrentIdentity, ?>)
                invocation.getArgument(0)).apply(ACTOR))
                .when(operations).writeAsCurrentUser(any(Function.class));
        return operations;
    }

    /**
     * 创建指定到期和吊销事实的正式凭据行。
     * @param credentialId Long，测试凭据主键
     * @param expiresAt Instant，到期时刻，空表示长期有效
     * @param revokedAt Instant，吊销时刻，空表示未吊销
     * @return WfIntegrationCredential，具备正式管理与认证字段的凭据
     */
    private WfIntegrationCredential credential(Long credentialId, Instant expiresAt,
            Instant revokedAt)
    {
        WfIntegrationCredential credential = new WfIntegrationCredential();
        credential.setCredentialId(credentialId);
        credential.setCredentialName("Credential-" + credentialId);
        credential.setTokenPrefix("abcdefghijkl");
        credential.setScopes("MESSAGE");
        credential.setAllowedVariables("businessKey");
        credential.setRateLimitPerMinute(60);
        credential.setExpiresAt(expiresAt == null ? null : Date.from(expiresAt));
        credential.setRevokedAt(revokedAt == null ? null : Date.from(revokedAt));
        credential.setRevisionNo(1);
        credential.setCreateBy(ACTOR.userId());
        return credential;
    }

    /**
     * 断言服务端状态与两项纯业务操作能力。
     * @param view WorkflowIntegrationCredentialView，服务返回的脱敏视图
     * @param status String，预期 ACTIVE、EXPIRED 或 REVOKED
     * @param rotateAllowed boolean，预期轮换能力
     * @param revokeAllowed boolean，预期吊销能力
     * @return void，任一字段不一致时测试失败
     */
    private void assertCapability(WorkflowIntegrationCredentialView view, String status,
            boolean rotateAllowed, boolean revokeAllowed)
    {
        assertEquals(status, view.status());
        assertEquals(rotateAllowed, view.rotateAllowed());
        assertEquals(revokeAllowed, view.revokeAllowed());
    }

    /**
     * 执行管理动作并断言原有 409 状态和错误消息。
     * @param message String，预期稳定错误消息
     * @param action Runnable，预期被服务端状态门禁拒绝的动作
     * @return void，动作未失败或合同变化时测试失败
     */
    private void assertConflict(String message, Runnable action)
    {
        ServiceException exception = assertThrows(ServiceException.class, action::run);
        assertEquals(HttpStatus.CONFLICT, exception.getCode());
        assertEquals(message, exception.getMessage());
    }

    /**
     * 计算测试 Token 的小写 SHA-256，确保认证经过真实哈希匹配后才验证状态。
     * @param value String，测试 Token 正文
     * @return String，64 位小写十六进制摘要
     */
    private String sha256(String value)
    {
        try
        {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException(exception);
        }
    }

    /** @return Clock，固定在到期边界的 UTC 服务端时钟。 */
    private Clock fixedClock()
    {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    /** 服务、正式 Mapper 和 Redis 原子组件测试夹具。 */
    private record ServiceHarness(WorkflowIntegrationCredentialService service,
            WfIntegrationCredentialMapper mapper,
            WorkflowRedisAtomicOperations redisOperations)
    {
    }

    /** 非法状态凭据及其必须保持的原有 409 消息。 */
    private record RejectedState(WfIntegrationCredential credential, String message)
    {
    }
}
