package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.runtime.WorkflowRedisAtomicOperations;
import com.ruoyi.flowable.service.process.WorkflowIntegrationCredentialService;

/**
 * 使用真实 MySQL 和 Redis 启动应用，仅在集成凭据原子限流边界注入 Redis 故障。
 */
@SpringBootTest(classes = {
    RuoYiApplication.class,
    WorkflowCredentialRedisUnavailableIT.RedisFailureConfiguration.class
},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "spring.datasource.druid.master.url=${FLOWABLE_IT_JDBC_URL}",
            "spring.datasource.druid.master.username=${FLOWABLE_IT_USERNAME}",
            "spring.datasource.druid.master.password=${FLOWABLE_IT_PASSWORD}",
            "spring.data.redis.host=${FLOWABLE_IT_REDIS_HOST:127.0.0.1}",
            "spring.data.redis.port=${FLOWABLE_IT_REDIS_PORT:6379}",
            "spring.data.redis.database=${FLOWABLE_IT_REDIS_DATABASE:15}",
            "spring.data.redis.timeout=250ms",
            "token.secret=cmV0ZW50aW9uLWl0LXJlYWwtaXNvbGF0ZWQtbXlzcWwtc2NoZW1hLXJldGVudGlvbi1pdC1yZWFsLWlzb2xhdGVkLW15c3FsLXNjaGVtYQ==",
            "flowable.database-schema-update=false",
            "flowable.async-executor-activate=false",
            "flowable.async-history-executor-activate=false",
            "spring.quartz.auto-startup=false"
        })
class WorkflowCredentialRedisUnavailableIT
{
    private static final String PREFIX = "workflow-credential-redis-down-it-";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private WorkflowIntegrationCredentialService credentialService;
    @Autowired
    private StringRedisTemplate redisTemplate;

    private final String runId = UUID.randomUUID().toString().replace("-", "");
    private String token;
    private Long credentialId;

    /**
     * 创建只保存摘要的真实凭据，并确认应用统一 Redis 连接可正常执行命令。
     * @return void，正式用户或凭据插入失败时立即失败
     * @throws Exception 当前 JDK 不支持 SHA-256 时抛出
     */
    @BeforeEach
    void setUp() throws Exception
    {
        token = "redis_down_" + runId;
        // 唯一健康检查 Key 只执行只读查询，用于证明应用仍连接真实 Redis，而非整体断开 Redis。
        assertThat(redisTemplate.hasKey(PREFIX + "health-" + runId)).isFalse();
        String actorUserId = jdbc.queryForObject(
                "select cast(min(user_id) as char) from sys_user where status='0' and del_flag='0'",
                String.class);
        jdbc.update("insert into wf_integration_credential "
                + "(credential_name, token_prefix, token_hash, scopes, allowed_variables, "
                + "rate_limit_per_minute, revision_no, create_by, create_time) "
                + "values (?, ?, ?, 'MESSAGE', '', 10, 1, ?, current_timestamp(3))",
                PREFIX + runId, token.substring(0, 12), sha256(token), actorUserId);
        credentialId = jdbc.queryForObject(
                "select credential_id from wf_integration_credential where token_prefix=?",
                Long.class, token.substring(0, 12));
    }

    /**
     * 精确删除本轮凭据并断言没有残留正式数据。
     * @return void，清理不完整时测试失败
     */
    @AfterEach
    void tearDown()
    {
        if (credentialId != null)
        {
            jdbc.update("delete from wf_integration_credential where credential_id=?",
                    credentialId);
        }
        assertThat(jdbc.queryForObject("select count(*) from wf_integration_credential "
                + "where credential_name=?", Integer.class, PREFIX + runId)).isZero();
    }

    /**
     * 验证凭据限流边界故障被映射为稳定 503，且凭据持久化状态没有认证副作用。
     * @return void，Redis 故障被放行、误映射或产生认证成功副作用时失败
     */
    @Test
    void returnsServiceUnavailableWithoutAuthenticationSideEffects()
    {
        assertThatThrownBy(() -> credentialService.authenticateAndConsume(token, "MESSAGE"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.getSubCode())
                            .isEqualTo("INTEGRATION_RATE_LIMIT_UNAVAILABLE");
                });
        assertThat(jdbc.queryForObject("select last_used_at is null "
                + "and revoked_at is null and revision_no=1 "
                + "from wf_integration_credential where credential_id=?",
                Boolean.class, credentialId)).isTrue();
    }

    /**
     * 提供只在凭据原子限流调用点失败的测试 Bean，避免影响应用其他 Redis 消费者启动。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class RedisFailureConfiguration
    {
        /**
         * 创建凭据限流故障原子组件。
         * @param redisTemplate StringRedisTemplate，应用真实 Redis 连接与序列化配置
         * @return WorkflowRedisAtomicOperations，执行分钟计数时抛出 DataAccessException
         */
        @Bean
        @Primary
        WorkflowRedisAtomicOperations unavailableWorkflowRedisAtomicOperations(
                StringRedisTemplate redisTemplate)
        {
            return new WorkflowRedisAtomicOperations(redisTemplate)
            {
                /**
                 * 在凭据分钟限流计数点注入稳定的 Redis 资源不可用异常。
                 * @param key String，凭据限流计数 Key
                 * @param ttl Duration，凭据限流窗口保留期
                 * @return long，此故障实现始终抛出异常而不返回计数
                 * @throws DataAccessResourceFailureException 始终抛出以验证 503 映射
                 */
                @Override
                public long incrementWithExpiry(String key, Duration ttl)
                {
                    throw new DataAccessResourceFailureException(
                            "credential rate limit unavailable");
                }
            };
        }
    }

    /**
     * 计算与生产 Token 服务一致的小写 SHA-256。
     * @param value String，测试明文 Token
     * @return String，64 位小写十六进制摘要
     * @throws Exception 当前 JDK 不支持 SHA-256 时抛出
     */
    private String sha256(String value) throws Exception
    {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
