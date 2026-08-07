package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.vo.WorkflowConnectorInvocationClaim;
import com.ruoyi.flowable.mapper.WfConnectorInvocationMapper;

/**
 * 外部连接器幂等台账领取、租约和独立终态提交测试。
 */
class WorkflowConnectorInvocationServiceTest
{
    private final WfConnectorInvocationMapper mapper = mock(WfConnectorInvocationMapper.class);
    private final WorkflowConnectorInvocationService service =
            new WorkflowConnectorInvocationService(mapper);

    /**
     * 验证首次调用插入 PENDING 后只能领取一次 RUNNING 租约。
     * @return void，并发领取结果不一致时测试失败
     */
    @Test
    void insertsAndClaimsOnlyOneLease()
    {
        AtomicReference<String> claimedToken = new AtomicReference<>();
        when(mapper.selectClaim("a".repeat(64))).thenAnswer(invocation ->
                claimedToken.get() == null
                        ? new WorkflowConnectorInvocationClaim(9L, "PENDING", null, 0, null)
                        : new WorkflowConnectorInvocationClaim(9L, "RUNNING",
                                claimedToken.get(), 1, null));
        when(mapper.claim(eq("a".repeat(64)), anyString(), anyInt())).thenAnswer(invocation ->
        {
            claimedToken.set(invocation.getArgument(1));
            return 1;
        });

        WorkflowConnectorInvocationClaim claim = service.begin("deployment", "instance",
                "execution", "activity", "HTTP", "endpoint", 2, "a".repeat(64), "POST", "/api");

        assertThat(claim.status()).isEqualTo("RUNNING");
        verify(mapper).insertIfAbsent("deployment", "instance", "execution", "activity",
                "HTTP", "endpoint", 2, "a".repeat(64), "POST", "/api");
        verify(mapper).claim(eq("a".repeat(64)), eq(claim.claimToken()), anyInt());
    }

    /**
     * 验证成功状态重放只返回只读结果，不再次领取或产生外部副作用。
     * @return void，幂等重放覆盖成功记录时测试失败
     */
    @Test
    void replaysSuccessfulInvocationWithoutClaimingAgain()
    {
        WorkflowConnectorInvocationClaim success =
                new WorkflowConnectorInvocationClaim(9L, "SUCCESS", null, 1, 201);
        when(mapper.selectClaim("b".repeat(64))).thenReturn(success);

        WorkflowConnectorInvocationClaim result = service.begin("deployment", "instance",
                "execution", "activity", "HTTP", "endpoint", 2, "b".repeat(64), "POST", "/api");

        assertThat(result).isEqualTo(success);
        verify(mapper).insertIfAbsent("deployment", "instance", "execution", "activity",
                "HTTP", "endpoint", 2, "b".repeat(64), "POST", "/api");
        verify(mapper, never()).claim(anyString(), anyString(), anyInt());
    }

    /**
     * 验证正在运行且租约未过期的调用返回冲突，并禁止伪造成功或失败终态。
     * @return void，重复领取覆盖运行中的调用时测试失败
     */
    @Test
    void rejectsConcurrentLeaseConflict()
    {
        when(mapper.selectClaim("c".repeat(64)))
                .thenReturn(new WorkflowConnectorInvocationClaim(9L, "RUNNING", "other",
                        2, null));
        when(mapper.claim(eq("c".repeat(64)), anyString(), anyInt())).thenReturn(0);

        assertThatThrownBy(() -> service.begin("deployment", "instance", "execution",
                "activity", "HTTP", "endpoint", 2, "c".repeat(64), "POST", "/api"))
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(mapper, never()).completeSuccess(anyLong(), anyString(), anyLong(), anyInt(), anyString());
    }

    /**
     * 验证成功和失败终态都必须使用领取令牌，过期租约不能被旧执行覆盖。
     * @return void，终态更新条件或摘要字段漂移时测试失败
     */
    @Test
    void completesOnlyWithMatchingClaimToken()
    {
        WorkflowConnectorInvocationClaim claim =
                new WorkflowConnectorInvocationClaim(9L, "RUNNING", "token", 1, null);
        when(mapper.completeSuccess(9L, "token", 12L, 200, "ok")).thenReturn(1);
        when(mapper.completeFailure(9L, "token", 15L, 500, "HTTP_STATUS", "bad"))
                .thenReturn(1);

        service.success(claim, 12L, 200, "ok");
        service.failure(claim, 15L, 500, "HTTP_STATUS", "bad");

        verify(mapper).completeSuccess(9L, "token", 12L, 200, "ok");
        verify(mapper).completeFailure(9L, "token", 15L, 500, "HTTP_STATUS", "bad");
    }
}
