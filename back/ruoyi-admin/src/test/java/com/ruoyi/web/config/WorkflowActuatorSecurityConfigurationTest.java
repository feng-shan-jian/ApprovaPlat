package com.ruoyi.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通过两个真实随机端口验证 Actuator 与业务 API 的安全边界，不使用 MockMvc 或伪造路由。
 */
@SpringBootTest(classes = WorkflowActuatorSecurityConfigurationTest.TestApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
                "server.address=127.0.0.1",
                "management.server.address=127.0.0.1",
                "management.server.port=0",
                "management.endpoints.enabled-by-default=false",
                "management.endpoints.web.exposure.include=health,prometheus,info",
                "management.endpoint.health.enabled=true",
                "management.endpoint.prometheus.enabled=true",
                "management.endpoint.info.enabled=true"
        })
class WorkflowActuatorSecurityConfigurationTest
{
    /** 真实业务 WebServer 随机端口。 */
    @LocalServerPort
    private int applicationPort;

    /** 真实独立管理 WebServer 随机端口。 */
    @LocalManagementPort
    private int managementPort;

    /**
     * 验证 health/prometheus 无令牌可采集、其他 Actuator 被拒绝，同时业务 API 仍要求认证
     * 且 Actuator 不会回落到业务端口。
     *
     * @return void，端口隔离或任一授权规则漂移时测试失败
     * @throws Exception 启动本机 HTTP 请求或读取响应失败
     */
    @Test
    void exposesOnlyHealthAndPrometheusAnonymouslyOnManagementPort()
            throws Exception
    {
        HttpResponse<String> health = get(managementPort, "/actuator/health");
        HttpResponse<String> prometheus = get(managementPort, "/actuator/prometheus");
        HttpResponse<String> deniedInfo = get(managementPort, "/actuator/info");
        HttpResponse<String> protectedBusiness = get(applicationPort, "/business/ping");
        HttpResponse<String> absentMainActuator = get(applicationPort, "/actuator/health");

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.body()).contains("# HELP");
        assertThat(deniedInfo.statusCode()).isEqualTo(403);
        assertThat(protectedBusiness.statusCode()).isEqualTo(403);
        assertThat(absentMainActuator.statusCode()).isEqualTo(403);
        assertThat(applicationPort).isNotEqualTo(managementPort);
    }

    /**
     * 验证生产门禁拒绝非回环管理地址，防止无令牌健康和指标端点监听外部网卡。
     *
     * @return void，非回环地址未阻止启动时测试失败
     */
    @Test
    void rejectsNonLoopbackManagementAddressInProduction()
    {
        MockEnvironment environment = productionEnvironment()
                .withProperty("management.server.address", "0.0.0.0");

        assertThatThrownBy(() -> new WorkflowActuatorSecurityConfiguration()
                .validateProductionManagementEndpoint(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("生产管理端口必须绑定明确回环地址");
    }

    /**
     * 验证生产门禁拒绝管理端口与业务端口复用，避免匿名 Actuator 规则进入业务入口。
     *
     * @return void，相同端口仍通过启动校验时测试失败
     */
    @Test
    void rejectsSharedApplicationAndManagementPortInProduction()
    {
        MockEnvironment environment = productionEnvironment()
                .withProperty("management.server.port", "8080");

        assertThatThrownBy(() -> new WorkflowActuatorSecurityConfiguration()
                .validateProductionManagementEndpoint(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("生产管理端口必须与业务端口隔离");
    }

    /**
     * 创建生产管理端口校验的安全基线环境。
     *
     * @return MockEnvironment，生产门禁开启、业务 8080 与回环管理 18080 的配置
     */
    private MockEnvironment productionEnvironment()
    {
        return new MockEnvironment()
                .withProperty("flowable.runtime.production-gate-enabled", "true")
                .withProperty("server.port", "8080")
                .withProperty("management.server.address", "127.0.0.1")
                .withProperty("management.server.port", "18080");
    }

    /**
     * 向指定本机端口发送无 Authorization 头的真实 GET 请求。
     *
     * @param port int，业务或独立管理 WebServer 端口
     * @param path String，以斜杠开头的请求路径
     * @return HttpResponse&lt;String&gt;，完整状态码和 UTF-8 响应正文
     * @throws Exception HTTP 客户端发送或接收失败
     */
    private HttpResponse<String> get(int port, String path) throws Exception
    {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path)).GET().build();
        return HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 仅加载本测试需要的 Web、Security、Actuator 与 Prometheus 自动配置。
     */
    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
            "com.alibaba.druid.spring.boot4.autoconfigure.DruidDataSourceAutoConfigure",
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
            "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
            "org.springframework.boot.quartz.autoconfigure.QuartzAutoConfiguration",
            "org.flowable.spring.boot.ProcessEngineAutoConfiguration",
            "org.flowable.spring.boot.ProcessEngineServicesAutoConfiguration",
            "org.flowable.spring.boot.EndpointAutoConfiguration"
    })
    @Import({ WorkflowActuatorSecurityConfiguration.class,
            BusinessSecurityConfiguration.class, BusinessController.class })
    static class TestApplication
    {
    }

    /**
     * 模拟生产业务链的兜底认证规则，用于证明 Actuator 专用链没有放宽业务请求。
     */
    @Configuration(proxyBeanMethods = false)
    static class BusinessSecurityConfiguration
    {
        /**
         * 创建要求认证的业务兜底链；测试不配置登录机制，因此无令牌请求稳定返回拒绝。
         *
         * @param http HttpSecurity，业务 WebServer 当前安全构建器
         * @return SecurityFilterChain，匹配非 Actuator 请求的无状态认证链
         * @throws Exception Spring Security 构建失败
         */
        @Bean
        @Order(100)
        SecurityFilterChain businessSecurityFilterChain(HttpSecurity http)
                throws Exception
        {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(session -> session.sessionCreationPolicy(
                            SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                    .build();
        }
    }

    /**
     * 提供一个真实业务路由，验证 Actuator 匿名规则不会匹配普通 API。
     */
    @RestController
    static class BusinessController
    {
        /**
         * 返回固定业务探测正文。
         *
         * @return String，仅在通过业务认证链后返回的固定文本
         */
        @GetMapping("/business/ping")
        String ping()
        {
            return "business-ok";
        }
    }
}
