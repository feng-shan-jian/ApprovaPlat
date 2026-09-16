package com.ruoyi.web.controller.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.Filter;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.catalina.startup.Tomcat;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.ruoyi.common.annotation.RepeatSubmit;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.filter.RepeatableFilter;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;
import com.ruoyi.flowable.domain.WfAttachment;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfAttachmentMapper;
import com.ruoyi.flowable.mapper.WorkflowIdentityMapper;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentService;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentStorage;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentUploadJournal;
import com.ruoyi.framework.config.ResourcesConfig;
import com.ruoyi.framework.interceptor.impl.SameUrlDataInterceptor;
import com.ruoyi.framework.security.filter.JwtAuthenticationTokenFilter;
import com.ruoyi.framework.web.exception.GlobalExceptionHandler;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.service.ISysMenuService;

/**
 * 真实 loopback multipart、生产 MVC/权限/防重链、附件服务、MyBatis/InnoDB 和文件系统。
 * 替身仅限 TokenService、RedisCache、身份/菜单主数据及未调用的流程授权；失败用例注入一次 Mapper 写失败。
 * 不启动完整应用、审计 AOP 或 Flowable；绑定用例在真实事务中调用附件领域入口。
 */
class WfAttachmentMultipartMySqlIT
{
    @TempDir Path directory;
    private final long ownerId = (UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE) | 2L;
    private final List<String> ownedKeys = new CopyOnWriteArrayList<>();
    private final HttpClient client = HttpClient.newHttpClient();
    private Tomcat server;
    private AnnotationConfigWebApplicationContext context;
    private JdbcTemplate jdbc;
    private WfAttachmentMapper mapper;
    private WorkflowAttachmentProperties properties;
    private WorkflowAttachmentService service;
    private TransactionTemplate transaction;
    private URI base;

    @BeforeEach
    void start() throws Exception
    {
        var source = new MysqlDataSource();
        source.setURL(requiredEnvironment("WORKFLOW_MYSQL_TEST_URL"));
        source.setUser(requiredEnvironment("WORKFLOW_MYSQL_TEST_USERNAME"));
        source.setPassword(requiredEnvironment("WORKFLOW_MYSQL_TEST_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        // 在任何 DML 前只读验证；缺少上传账本时失败，不自动迁移常驻数据库。
        assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo("approvaplat_it");
        assertThat(Integer.parseInt(jdbc.queryForObject("select version()", String.class).split("\\.")[0]))
                .isGreaterThanOrEqualTo(8);
        assertThat(jdbc.queryForList("select table_name from information_schema.tables where table_schema=database()",
                String.class)).contains("wf_attachment", "wf_attachment_upload", "wf_attachment_quota_guard");
        var configuration = new org.apache.ibatis.session.Configuration(new Environment("attachment-http",
                new SpringManagedTransactionFactory(), source));
        configuration.addMapper(WfAttachmentMapper.class);
        String resource = "mapper/flowable/WfAttachmentMapper.xml";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource))
        {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        mapper = spy(new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration))
                .getMapper(WfAttachmentMapper.class));
        doAnswer(call -> {
            ownedKeys.add(call.getArgument(0));
            return call.callRealMethod();
        }).when(mapper).insertUpload(anyString());
        var manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        properties = new WorkflowAttachmentProperties();
        properties.setMaxSize(1024);
        properties.setMinFreeBytes(0);
        var storage = new WorkflowAttachmentStorage(directory, properties.getMaxSize());
        var identities = mock(WorkflowIdentityMapper.class);
        when(identities.selectActiveUserIdsByUserIds(anyList())).thenAnswer(call -> call.getArgument(0));
        var resolver = new WorkflowIdentityResolver(identities, new WorkflowIdentityCodec());
        service = new WorkflowAttachmentService(mapper, storage, properties, resolver,
                mock(WorkflowProcessAccessService.class),
                new WorkflowAttachmentUploadJournal(mapper, storage, properties, manager), manager);

        var permissions = Set.of("workflow:attachment:upload", "workflow:attachment:query", "workflow:attachment:remove");
        var menus = mock(ISysMenuService.class);
        when(menus.selectMenuPermsByUserId(anyLong())).thenReturn(permissions);
        var sessions = Map.of("owner", login(ownerId, permissions),
                "other", login(ownerId + 1, permissions),
                "no-upload", login(ownerId, Set.of("workflow:attachment:query")));
        var tokens = mock(TokenService.class);
        when(tokens.getLoginUser(any())).thenAnswer(call ->
                sessions.get(((HttpServletRequest) call.getArgument(0)).getHeader("Authorization")));
        var authentication = new JwtAuthenticationTokenFilter();
        ReflectionTestUtils.setField(authentication, "tokenService", tokens);
        var cache = mock(RedisCache.class);
        Map<String, Object> cached = new ConcurrentHashMap<>();
        when(cache.getCacheObject(anyString())).thenAnswer(call -> cached.get(call.getArgument(0)));
        doAnswer(call -> {
            cached.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(cache).setCacheObject(anyString(), any(), anyInt(), eq(TimeUnit.MILLISECONDS));

        context = new AnnotationConfigWebApplicationContext();
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "token.header=Authorization");
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("attachmentService", service);
            factory.registerSingleton("menuService", menus);
            factory.registerSingleton("redisCache", cache);
        });
        context.register(WebConfig.class);
        server = new Tomcat();
        server.setBaseDir(Files.createDirectory(directory.resolve("tomcat")).toString());
        server.setPort(0);
        server.getConnector().setProperty("address", "127.0.0.1");
        var web = server.addContext("", Files.createDirectory(directory.resolve("web")).toString());
        var servlet = Tomcat.addServlet(web, "mvc", new DispatcherServlet(context));
        servlet.setLoadOnStartup(1);
        servlet.setMultipartConfigElement(new MultipartConfigElement(directory.toString()));
        web.addServletMappingDecoded("/", "mvc");
        var repeatable = new RepeatableFilter();
        Filter filter = (request, response, chain) -> {
            try
            {
                authentication.doFilter(request, response,
                        (authenticated, result) -> repeatable.doFilter(authenticated, result, chain));
            }
            finally
            {
                SecurityContextHolder.clearContext();
            }
        };
        var definition = new FilterDef();
        definition.setFilterName("production-filters");
        definition.setFilter(filter);
        web.addFilterDef(definition);
        var mapping = new FilterMap();
        mapping.setFilterName("production-filters");
        mapping.addURLPattern("/*");
        web.addFilterMap(mapping);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getConnector().getLocalPort());
    }

    @AfterEach
    void stop() throws Exception
    {
        try
        {
            if (server != null)
            {
                try { server.stop(); }
                finally { server.destroy(); }
            }
        }
        finally
        {
            if (context != null) context.close();
            if (jdbc != null)
            {
                // UUID 对象键和本用例随机 owner 限定清理，不截断或清理其他测试的数据。
                for (String key : ownedKeys)
                {
                    jdbc.update("delete from wf_attachment_upload where storage_key=?", key);
                }
                if (!ownedKeys.isEmpty())
                {
                    jdbc.update("delete from wf_attachment where owner_user_id=?", ownerId);
                    jdbc.update("delete from wf_attachment_quota_guard where owner_user_id=?", ownerId);
                    assertThat(metadataCount()).isZero();
                    assertThat(journalCount()).isZero();
                }
            }
        }
    }

    @Test
    void differentFilesInSameFieldReachStorageWithinFiveSeconds() throws Exception
    {
        // 阳性对照证明正式拦截器已注册：其他带注解入口仍拒绝重复请求。
        assertThat(request("POST", "/repeat-control", "owner", "{}", "application/json").getIntValue("code"))
                .isEqualTo(200);
        assertThat(request("POST", "/repeat-control", "owner", "{}", "application/json").getIntValue("code"))
                .isEqualTo(500);
        long started = System.nanoTime();
        String first = successfulUpload(upload("owner", "first.txt", "first-file"));
        String second = successfulUpload(upload("owner", "second.txt", "different-second-file"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(second).isNotEqualTo(first);
        assertStored(first, "first-file");
        assertStored(second, "different-second-file");
        assertThat(metadataCount()).isEqualTo(2);
        assertThat(journalCount()).isZero();
    }

    @Test
    void failedBusinessWriteAllowsImmediateRetryAndKeepsRecoveryJournal() throws Exception
    {
        doThrow(new ServiceException("injected metadata failure", 500)).doCallRealMethod()
                .when(mapper).insert(any(WfAttachment.class));
        long started = System.nanoTime();
        JSONObject failed = upload("owner", "retry.txt", "retry-content");
        assertThat(failed.getIntValue("code")).isEqualTo(500);
        assertThat(failed.getString("msg")).isEqualTo("injected metadata failure");
        assertThat(metadataCount()).isZero();
        assertThat(journalCount()).isOne();
        assertThat(Files.readString(physical(ownedKeys.get(0)))).isEqualTo("retry-content");
        String retried = successfulUpload(upload("owner", "retry.txt", "retry-content"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertStored(retried, "retry-content");
        assertThat(metadataCount()).isOne();
        assertThat(journalCount()).isOne();
        assertThat(ownedKeys).hasSize(2);
    }

    @Test
    void permissionFileSizeAndUserQuotaStillRejectMultipartRequests() throws Exception
    {
        assertThat(upload("no-upload", "denied.txt", "content").getIntValue("code")).isEqualTo(403);
        assertThat(upload("owner", "large.txt", "x".repeat(1025)).getIntValue("code")).isEqualTo(400);
        assertThat(ownedKeys).isEmpty();
        assertThat(metadataCount()).isZero();
        properties.setMaxTemporaryCount(1);
        successfulUpload(upload("owner", "one.txt", "content"));
        JSONObject exceeded = upload("owner", "two.txt", "second");
        assertThat(exceeded.getString("subCode")).isEqualTo("WORKFLOW_ATTACHMENT_QUOTA_EXCEEDED");
        assertThat(metadataCount()).isOne();
        assertThat(journalCount()).isOne();
        assertThat(Files.exists(physical(ownedKeys.get(1)))).isFalse();
    }

    @Test
    void temporaryOwnershipAndCrossInstanceRebindingRemainEnforced() throws Exception
    {
        String id = successfulUpload(upload("owner", "owned.txt", "owned-content"));
        String path = "/workflow/attachment/" + id;
        assertThat(request("GET", path, "other", "", "text/plain").getIntValue("code")).isEqualTo(403);
        assertThat(request("DELETE", path, "other", "", "text/plain").getIntValue("code")).isEqualTo(403);
        Map<String, List<String>> references = Map.of("evidence", List.of(id));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                service.bindTaskAttachments(Long.toString(ownerId + 1), "process-one", "task-one", "review", references)))
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode()).isEqualTo(403));
        transaction.executeWithoutResult(status -> service.bindTaskAttachments(
                Long.toString(ownerId), "process-one", "task-one", "review", references));
        WfAttachment bound = mapper.selectById(id);
        assertThat(bound.status().name()).isEqualTo("BOUND");
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> service.bindTaskAttachments(
                Long.toString(ownerId), "process-two", "task-two", "review", references)))
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode()).isEqualTo(403));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> service.bindTaskAttachments(
                Long.toString(ownerId), "process-one", "task-two", "review", Map.of("evidence", List.of(id, id)))))
                .isInstanceOf(ServiceException.class);
        assertThat(mapper.selectById(id)).isEqualTo(bound);
        assertStored(id, "owned-content");
    }

    private JSONObject upload(String token, String name, String contents) throws Exception
    {
        String boundary = "attachment-" + UUID.randomUUID();
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"fieldName\"\r\n\r\nevidence\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name
                + "\"\r\nContent-Type: text/plain\r\n\r\n" + contents + "\r\n--" + boundary + "--\r\n";
        return request("POST", "/workflow/attachment", token, body, "multipart/form-data; boundary=" + boundary);
    }

    private JSONObject request(String method, String path, String token, String body, String contentType) throws Exception
    {
        var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", token).header("Content-Type", contentType)
                .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.parseObject(response.body());
    }

    private String successfulUpload(JSONObject response)
    {
        assertThat(response.getIntValue("code")).as(response.toJSONString()).isEqualTo(200);
        JSONObject data = response.getJSONObject("data");
        assertThat(data).doesNotContainKeys("storageKey", "ownerUserId");
        return data.getString("attachmentId");
    }

    private void assertStored(String id, String contents) throws Exception
    {
        WfAttachment row = mapper.selectById(id);
        assertThat(row.ownerUserId()).isEqualTo(ownerId);
        assertThat(row.fieldName()).isEqualTo("evidence");
        assertThat(row.fileSize()).isEqualTo(contents.getBytes(StandardCharsets.UTF_8).length);
        assertThat(Files.readString(physical(row.storageKey()))).isEqualTo(contents);
    }

    private Path physical(String key)
    {
        return directory.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME).resolve(key);
    }

    private long metadataCount()
    {
        return jdbc.queryForObject("select count(*) from wf_attachment where owner_user_id=?", Long.class, ownerId);
    }

    private long journalCount()
    {
        return ownedKeys.stream().mapToLong(key -> jdbc.queryForObject(
                "select count(*) from wf_attachment_upload where storage_key=?", Long.class, key)).sum();
    }

    private LoginUser login(long id, Set<String> permissions)
    {
        var user = new SysUser();
        user.setUserId(id);
        user.setUserName("attachment-http-it");
        return new LoginUser(id, null, user, permissions);
    }

    private String requiredEnvironment(String name)
    {
        String value = System.getenv(name);
        if (value == null) throw new IllegalStateException("MySQL IT requires " + name);
        return value;
    }

    @Configuration
    @EnableWebMvc
    @EnableMethodSecurity
    @Import({WfAttachmentController.class, GlobalExceptionHandler.class, ResourcesConfig.class,
            SameUrlDataInterceptor.class, PermissionService.class, RepeatControl.class})
    static class WebConfig
    {
        @Bean
        StandardServletMultipartResolver multipartResolver()
        {
            return new StandardServletMultipartResolver();
        }
    }

    /** 只存在于测试容器的阳性对照，防止未接入拦截链也被误算通过。 */
    @RestController
    static class RepeatControl
    {
        @RepeatSubmit
        @PostMapping("/repeat-control")
        public AjaxResult submit() { return AjaxResult.success(); }
    }
}
