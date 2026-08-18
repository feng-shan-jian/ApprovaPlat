package com.ruoyi.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;

/**
 * OSS 对象台账和短信审计真实 MySQL、HTTP 分页集成测试。
 * 测试使用正式表和固定排序，只验证分页不再受历史截断上限影响。
 */
@SpringBootTest(classes = RuoYiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "spring.datasource.druid.master.url=${FLOWABLE_IT_JDBC_URL}",
            "spring.datasource.druid.master.username=${FLOWABLE_IT_USERNAME}",
            "spring.datasource.druid.master.password=${FLOWABLE_IT_PASSWORD}",
            "flowable.it.expected-schema=${FLOWABLE_IT_EXPECTED_SCHEMA}",
            "token.secret=eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eA==",
            "flowable.database-schema-update=false",
            "flowable.async-executor-activate=false",
            "flowable.async-history-executor-activate=false",
            "spring.quartz.auto-startup=false"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SysOssSmsPaginationIT
{
    private static final long TEST_USER_ID = 82L;
    private static final int OSS_OBJECT_COUNT = 1001;
    private static final int SMS_LOG_COUNT = 501;
    private static final String SHA256 = "a".repeat(64);

    @Autowired
    @Qualifier("dynamicDataSource")
    private DataSource dataSource;
    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private FilterChainProxy securityFilterChain;
    @Value("${flowable.it.expected-schema}")
    private String expectedSchema;

    private JdbcTemplate jdbc;
    private MockMvc mockMvc;
    private Long ossConfigId;
    private Long smsConfigId;

    /**
     * 连接隔离 schema，创建本测试专属配置并置备超过历史上限的正式数据。
     * @return void，无返回值
     */
    @BeforeAll
    void prepare()
    {
        jdbc = new JdbcTemplate(dataSource);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(securityFilterChain).build();
        assertThat(jdbc.queryForObject("select database()", String.class))
                .isEqualTo(expectedSchema);
        assertThat(expectedSchema).endsWith("_flowable_it");
        ossConfigId = createOssConfig();
        smsConfigId = createSmsConfig();
        insertOssObjects();
        insertSmsLogs();
    }

    /**
     * 删除本测试创建的对象、日志和配置，避免污染隔离 schema。
     * @return void，无返回值
     */
    @AfterAll
    void cleanup()
    {
        if (jdbc == null)
        {
            return;
        }
        if (ossConfigId != null)
        {
            jdbc.update("delete from sys_oss_object where config_id=?", ossConfigId);
            jdbc.update("delete from sys_oss_config where config_id=?", ossConfigId);
        }
        if (smsConfigId != null)
        {
            jdbc.update("delete from sys_sms_log where config_id=?", smsConfigId);
            jdbc.update("delete from sys_sms_config where config_id=?", smsConfigId);
        }
    }

    /**
     * 清理当前线程认证主体，防止不同 HTTP 用例之间泄漏权限。
     * @return void，无返回值
     */
    @AfterEach
    void clearIdentity()
    {
        SecurityContextHolder.clearContext();
    }

    /**
     * 验证 OSS 第 1 页返回标准 rows、total，且总数超过历史 1000 条截断上限。
     * @return void，无返回值
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void ossObjectsUseServerPaginationBeyondLegacyLimit() throws Exception
    {
        ResultActions firstPage = mockMvc.perform(get("/system/oss/objects")
                .with(authentication(Set.of("system:oss:list")))
                .param("pageNum", "1")
                .param("pageSize", "100"));
        firstPage.andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(100))
                .andExpect(jsonPath("$.total").value(OSS_OBJECT_COUNT));

        mockMvc.perform(get("/system/oss/objects")
                        .with(authentication(Set.of("system:oss:list")))
                        .param("pageNum", "11")
                        .param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.total").value(OSS_OBJECT_COUNT));
    }

    /**
     * 验证短信日志第 6 页能够读取历史 500 条上限之外的最后一条审计。
     * @return void，无返回值
     * @throws Exception MockMvc 请求执行失败
     */
    @Test
    void smsLogsUseServerPaginationBeyondLegacyLimit() throws Exception
    {
        mockMvc.perform(get("/system/sms/logs")
                        .with(authentication(Set.of("system:sms:list")))
                        .param("pageNum", "1")
                        .param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(100))
                .andExpect(jsonPath("$.total").value(SMS_LOG_COUNT));

        mockMvc.perform(get("/system/sms/logs")
                        .with(authentication(Set.of("system:sms:list")))
                        .param("pageNum", "6")
                        .param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.total").value(SMS_LOG_COUNT));
    }

    /**
     * 创建停用 OSS 配置并返回正式配置主键。
     * @return Long，测试专属 OSS 配置主键
     */
    private Long createOssConfig()
    {
        String name = "oss-pagination-it-" + System.nanoTime();
        jdbc.update("insert into sys_oss_config "
                + "(config_name,endpoint,region,bucket_name,access_key,secret_key,path_style," 
                + "access_policy,status,create_by) values (?,?,?,?,?,'secret','Y','PRIVATE','1',?)",
                name, "https://s3.example.test", "us-east-1", "pagination-it-bucket",
                "access-key", "flowable-it");
        return jdbc.queryForObject("select config_id from sys_oss_config where config_name=?",
                Long.class, name);
    }

    /**
     * 创建停用短信配置并返回正式配置主键。
     * @return Long，测试专属短信配置主键
     */
    private Long createSmsConfig()
    {
        String name = "sms-pagination-it-" + System.nanoTime();
        jdbc.update("insert into sys_sms_config "
                + "(config_name,provider,access_key_id,access_key_secret,sign_name,status,create_by) "
                + "values (?,'ALIYUN','access-key','secret','测试签名','1',?)",
                name, "flowable-it");
        return jdbc.queryForObject("select config_id from sys_sms_config where config_name=?",
                Long.class, name);
    }

    /**
     * 批量创建 1001 条对象台账，覆盖原有 1000 条固定上限。
     * @return void，无返回值
     */
    private void insertOssObjects()
    {
        List<Object[]> arguments = new ArrayList<>(OSS_OBJECT_COUNT);
        for (int index = 0; index < OSS_OBJECT_COUNT; index++)
        {
            arguments.add(new Object[] { ossConfigId, "it/object-" + index, "object-" + index + ".txt",
                    ".txt", "text/plain", 1L, SHA256, "PRIVATE", "ACTIVE", "flowable-it" });
        }
        jdbc.batchUpdate("insert into sys_oss_object "
                + "(config_id,object_key,original_name,file_suffix,content_type,file_size,sha256," 
                + "access_policy,status,create_by) values (?,?,?,?,?,?,?,?,?,?)", arguments);
    }

    /**
     * 批量创建 501 条短信审计，覆盖原有 500 条固定上限。
     * @return void，无返回值
     */
    private void insertSmsLogs()
    {
        List<Object[]> arguments = new ArrayList<>(SMS_LOG_COUNT);
        for (int index = 0; index < SMS_LOG_COUNT; index++)
        {
            arguments.add(new Object[] { smsConfigId, "ALIYUN", "ADMIN_TEST", "+8613800000000", 1,
                    "SMS_100", "DELIVERED", "request-" + index, "flowable-it" });
        }
        jdbc.batchUpdate("insert into sys_sms_log "
                + "(config_id,provider,source_type,recipient_masked,recipient_count,template_id,status," 
                + "provider_request_id,create_by) values (?,?,?,?,?,?,?,?,?)", arguments);
    }

    /**
     * 为 MockMvc 请求注入真实 Spring Security 认证主体和权限集合。
     * @param permissions Set&lt;String&gt;，当前请求允许使用的权限标识
     * @return RequestPostProcessor，请求执行前写入认证上下文的处理器
     */
    private RequestPostProcessor authentication(Set<String> permissions)
    {
        return request -> {
            SysUser user = new SysUser(TEST_USER_ID);
            user.setUserName("oss_sms_pagination_it");
            LoginUser loginUser = new LoginUser(TEST_USER_ID, null, user, permissions);
            UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                    loginUser, null, loginUser.getAuthorities());
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(token);
            request.getSession(true).setAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
            return request;
        };
    }
}
