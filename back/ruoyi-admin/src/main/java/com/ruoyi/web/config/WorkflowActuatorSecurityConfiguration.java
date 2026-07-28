package com.ruoyi.web.config;

import java.util.Set;
import org.springframework.boot.actuate.autoconfigure.web.server.ConditionalOnManagementPort;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 独立管理端口的 Actuator 安全边界。生产配置把该端口绑定到回环地址，本过滤链只允许
 * 无令牌采集健康状态和 Prometheus 指标，任何后来误暴露的其他 Actuator 端点仍拒绝访问。
 */
@Configuration(proxyBeanMethods = false)
public class WorkflowActuatorSecurityConfiguration
{
    /** 允许匿名管理端点绑定的明确回环地址，拒绝依赖 DNS 解析结果。 */
    private static final Set<String> LOOPBACK_ADDRESSES = Set.of(
            "127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

    /**
     * 创建优先于业务 JWT 链的 Actuator 专用过滤链；匹配范围仅限 Actuator 端点，因而不会
     * 放宽登录、工作流或其他业务 API 的认证要求。
     *
     * @param http HttpSecurity，独立管理 WebServer 当前安全构建器
     * @return SecurityFilterChain，只匿名放行 health 与 prometheus 的无状态过滤链
     * @throws Exception Spring Security 构建过滤链失败
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnManagementPort(ManagementPortType.DIFFERENT)
    public SecurityFilterChain workflowActuatorSecurityFilterChain(HttpSecurity http)
            throws Exception
    {
        return http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(EndpointRequest.to("health", "prometheus"))
                        .permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    /**
     * 创建生产管理端口启动门禁；配置绑定完成后核对回环地址和端口隔离，避免匿名健康端点
     * 因部署漂移监听公网或与业务 API 共用端口。
     *
     * @param environment Environment，读取生产门禁和两个 WebServer 的最终配置
     * @return InitializingBean，ApplicationContext 初始化阶段执行的 fail-closed 校验器
     */
    @Bean
    public InitializingBean workflowManagementEndpointValidator(Environment environment)
    {
        return () -> validateProductionManagementEndpoint(environment);
    }

    /**
     * 仅在工作流生产门禁开启时校验管理端口；地址必须是明确回环字面量，管理端口必须是
     * 1 至 65535 的固定端口且不能与业务端口相同。
     *
     * @param environment Environment，读取已解析的生产部署属性
     * @return void，非生产直接返回，生产配置不安全时阻止应用启动
     */
    void validateProductionManagementEndpoint(Environment environment)
    {
        boolean productionGateEnabled = environment.getProperty(
                "flowable.runtime.production-gate-enabled", Boolean.class, false);
        if (!productionGateEnabled)
        {
            return;
        }

        String managementAddress = environment.getProperty(
                "management.server.address", "").trim();
        Integer applicationPort = environment.getProperty(
                "server.port", Integer.class, 8080);
        Integer managementPort = environment.getProperty(
                "management.server.port", Integer.class);
        if (!LOOPBACK_ADDRESSES.contains(managementAddress))
        {
            throw new IllegalStateException("生产管理端口必须绑定明确回环地址");
        }
        if (managementPort == null || managementPort < 1 || managementPort > 65535)
        {
            throw new IllegalStateException("生产管理端口必须配置为1至65535的固定端口");
        }
        if (managementPort.equals(applicationPort))
        {
            throw new IllegalStateException("生产管理端口必须与业务端口隔离");
        }
    }
}
