package com.ruoyi.flowable.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.config.WorkflowNotificationProperties;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.runtime.WorkflowNotificationMetrics;
import com.ruoyi.flowable.runtime.WorkflowRedisAtomicOperations;
import com.ruoyi.framework.web.service.PermissionService;

class WorkflowNotificationUrgeRedisFailureTest
{
    private static final Duration URGE_INTERVAL = Duration.ofMinutes(5);

    private WorkflowRedisAtomicOperations redisAtomicOperations;
    private WorkflowNotificationMetrics notificationMetrics;
    private WorkflowManualUrgeService manualUrgeService;

    /**
     * 创建仅替换外部边界的通知服务，直接验证生产冷却方法的稳定错误映射。
     *
     * @return void，每个用例获得隔离的 Redis 与指标调用记录
     */
    @BeforeEach
    void setUp()
    {
        WorkflowNotificationProperties properties = new WorkflowNotificationProperties();
        properties.setUrgeInterval(URGE_INTERVAL);
        redisAtomicOperations = mock(WorkflowRedisAtomicOperations.class);
        notificationMetrics = mock(WorkflowNotificationMetrics.class);
        manualUrgeService = new WorkflowManualUrgeService(mock(JdbcTemplate.class),
                mock(WorkflowEngineOperations.class), mock(PermissionService.class),
                mock(WorkflowNotificationRegistrar.class), redisAtomicOperations, properties,
                notificationMetrics);
    }

    /**
     * 验证 Redis 连接失败不会被误报为普通限流或系统 500。
     *
     * @return void，必须返回 503、稳定子码并累计依赖不可用指标
     */
    @Test
    void mapsRedisFailureToServiceUnavailable()
    {
        when(redisAtomicOperations.setIfAbsent(anyString(), eq(URGE_INTERVAL)))
                .thenThrow(new DataAccessResourceFailureException("redis unavailable"));

        assertThatThrownBy(() -> invokeCooldown())
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.getSubCode()).isEqualTo("WORKFLOW_REDIS_UNAVAILABLE");
                    assertThat(exception.getMessage()).isEqualTo("催办频控服务暂不可用");
                });
        verify(notificationMetrics).recordUrge("redis_unavailable");
    }

    /**
     * 验证 Redis 已存在冷却键时返回可执行的剩余秒数和稳定限流子码。
     *
     * @return void，必须返回 429 且不得误记为 Redis 依赖故障
     */
    @Test
    void mapsExistingCooldownToTooManyRequests()
    {
        when(redisAtomicOperations.setIfAbsent(anyString(), eq(URGE_INTERVAL)))
                .thenReturn(new WorkflowRedisAtomicOperations.ExpiringSetResult(false, 42));

        assertThatThrownBy(() -> invokeCooldown())
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(exception.getSubCode()).isEqualTo("WORKFLOW_URGE_COOLDOWN_ACTIVE");
                    assertThat(exception.getMessage()).isEqualTo("催办冷却中，请 42 秒后重试");
                });
        verify(notificationMetrics).recordUrge("cooldown_rejected");
    }

    /**
     * 调用生产服务的私有冷却边界，避免在测试中复制错误码映射逻辑。
     *
     * @return void，成功建立冷却时正常返回，拒绝或依赖失败时透传业务异常
     */
    private void invokeCooldown()
    {
        manualUrgeService.acquireCooldown("7", "instance-1");
    }
}
