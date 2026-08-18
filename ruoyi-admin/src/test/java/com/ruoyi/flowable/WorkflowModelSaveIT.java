package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Model;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import tools.jackson.databind.json.JsonMapper;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowModelDto;
import com.ruoyi.flowable.domain.vo.WorkflowModelSaveResult;
import com.ruoyi.flowable.service.model.WorkflowModelService;
import com.ruoyi.framework.web.service.TokenService;

/**
 * 模型保存真实 MySQL、Flowable revision 和权限集成测试。
 * 测试只使用现有模型表的 revision/唯一约束，不创建额外幂等或锁表。
 */
@SpringBootTest(classes = RuoYiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "spring.datasource.druid.master.url=${FLOWABLE_IT_JDBC_URL}",
            "spring.datasource.druid.master.username=${FLOWABLE_IT_USERNAME}",
            "spring.datasource.druid.master.password=${FLOWABLE_IT_PASSWORD}",
            "flowable.it.expected-schema=${FLOWABLE_IT_EXPECTED_SCHEMA}",
            "token.secret=eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eA==",
            "flowable.database-schema-update=false",
            "flowable.async-executor-activate=false",
            "flowable.async-history-executor-activate=false",
            "spring.quartz.auto-startup=false"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkflowModelSaveIT
{
    /** 隔离基线自带且可通过身份目录校验的 admin 用户主键。 */
    private static final long TEST_USER_ID = 1L;
    private static final String CATEGORY_PREFIX = "model_save_it_";
    private static final String BPMN = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
            + "xmlns:flowable=\"http://flowable.org/bpmn\" "
            + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><process id=\"%s\" "
            + "name=\"%s\" isExecutable=\"true\"><startEvent id=\"start\"><extensionElements>"
            + "<flowable:formProperty id=\"requestReason\" name=\"申请原因\" type=\"string\" "
            + "readable=\"true\" writable=\"true\" required=\"true\"/>"
            + "</extensionElements></startEvent><endEvent id=\"end\"/>"
            + "<sequenceFlow id=\"flow\" sourceRef=\"start\" targetRef=\"end\"/>"
            + "</process></definitions>";

    @Autowired
    private WorkflowModelService modelService;
    @Autowired
    private RepositoryService repositoryService;
    @Autowired
    private WorkflowDeploymentArtifactRepository artifactRepository;
    private MockMvc mockMvc;
    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private FilterChainProxy securityFilterChain;
    @Autowired
    private TokenService tokenService;
    @Autowired
    @Qualifier("dynamicDataSource")
    private DataSource dataSource;
    @Value("${flowable.it.expected-schema}")
    private String expectedSchema;

    private JdbcTemplate jdbc;
    private String categoryCode;
    private final List<String> modelIds = new ArrayList<>();
    /** 本轮真实父部署主键，清理时必须级联删除以避免污染 IT schema。 */
    private final List<String> deploymentIds = new ArrayList<>();
    /** 本轮真实 Redis 登录态主键，清理时逐一删除。 */
    private final List<String> loginTokenIds = new ArrayList<>();

    /**
     * 在显式集成 schema 创建本类唯一有效分类。
     * @return void，无返回值
     * @throws SQLException 连接元数据读取失败
     */
    @BeforeAll
    void prepare() throws SQLException
    {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(securityFilterChain).build();
        jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo(expectedSchema);
        assertThat(expectedSchema).endsWith("_flowable_it");
        categoryCode = CATEGORY_PREFIX + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("insert into wf_category(category_name,code,create_by,del_flag) values(?,?,?,'0')",
                "模型保存集成测试", categoryCode, TEST_USER_ID);
    }

    /**
     * 清除线程身份，避免测试之间泄漏权限上下文。
     * @return void，无返回值
     */
    @AfterEach
    void clearIdentity()
    {
        SecurityContextHolder.clearContext();
    }

    /**
     * 清理本类创建的部署、业务资源、模型和分类，并核对没有残留部署。
     * @return void，无返回值
     */
    @AfterAll
    void cleanup()
    {
        SecurityContextHolder.clearContext();
        for (String tokenId : loginTokenIds)
        {
            tokenService.delLoginUser(tokenId);
        }
        for (String deploymentId : deploymentIds)
        {
            artifactRepository.delete(deploymentId);
            if (repositoryService.createDeploymentQuery().deploymentId(deploymentId).count() == 1)
            {
                repositoryService.deleteDeployment(deploymentId, true);
            }
        }
        assertThat(deploymentIds.stream()
                .filter(id -> repositoryService.createDeploymentQuery().deploymentId(id).count() > 0))
                .isEmpty();
        for (String modelId : modelIds)
        {
            if (repositoryService.createModelQuery().modelId(modelId).count() == 1)
            {
                repositoryService.deleteModel(modelId);
            }
        }
        jdbc.update("delete from wf_category where code=?", categoryCode);
    }

    /**
     * 验证相同 XML 重复提交不产生新 revision 或新模型。
     * @return void，无返回值
     */
    @Test
    void repeatedSaveIsNoOp()
    {
        setUser(Set.of("workflow:model:save"));
        String modelId = createModel("重复保存");
        int initialRevision = revision(modelId);
        WorkflowModelSaveResult first = save(modelId, BPMN.formatted("repeat", "重复保存"), initialRevision);
        int revisionAfterFirst = revision(modelId);
        WorkflowModelSaveResult second = save(modelId, BPMN.formatted("repeat", "重复保存"), initialRevision);
        assertThat(second).isEqualTo(first);
        assertThat(revisionAfterFirst).isEqualTo(initialRevision + 1);
        assertThat(revision(modelId)).isEqualTo(revisionAfterFirst);
        assertThat(repositoryService.createModelQuery().modelId(modelId).count()).isOne();
    }

    /**
     * 验证旧 revision 在内容变化时返回稳定 409。
     * @return void，无返回值
     */
    @Test
    void staleRevisionReturnsConflict()
    {
        setUser(Set.of("workflow:model:save"));
        String modelId = createModel("版本冲突");
        int stale = revision(modelId);
        save(modelId, BPMN.formatted("conflict", "版本冲突一"), stale);
        assertThatThrownBy(() -> save(modelId, BPMN.formatted("conflict", "版本冲突二"), stale))
                .isInstanceOfSatisfying(ServiceException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getSubCode()).isEqualTo(WorkflowModelService.MODEL_VERSION_CONFLICT_SUB_CODE);
                });
    }

    /**
     * 验证已部署模型保存会复制到新的最高业务版本。
     * @return void，无返回值
     */
    @Test
    void deployedSaveCopiesNextVersion()
    {
        setUser(Set.of("workflow:model:save", "workflow:model:deploy"));
        String modelId = createModel("版本复制");
        save(modelId, BPMN.formatted("copy", "版本复制一"), revision(modelId));
        deploymentIds.add(modelService.deployModel(modelId));
        WorkflowModelSaveResult result = save(modelId, BPMN.formatted("copy", "版本复制二"), revision(modelId));
        assertThat(result.modelId()).isNotEqualTo(modelId);
        assertThat(result.version()).isEqualTo(2);
        modelIds.add(result.modelId());
    }

    /**
     * 两个线程使用同一 revision 保存不同 XML，必须只有一个真实提交成功。
     * @return void，无返回值
     * @throws Exception 并发线程执行失败
     */
    @Test
    void concurrentSaveHasOneWinner() throws Exception
    {
        setUser(Set.of("workflow:model:save"));
        String modelId = createModel("并发唯一");
        int expected = revision(modelId);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Object> one = executor.submit(() -> concurrentSave(start, modelId, expected, "并发一"));
        Future<Object> two = executor.submit(() -> concurrentSave(start, modelId, expected, "并发二"));
        start.countDown();
        List<Object> results = List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
        executor.shutdownNow();
        assertThat(results.stream().filter(WorkflowModelSaveResult.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(ServiceException.class::isInstance)
                .map(ServiceException.class::cast).findFirst()).hasValueSatisfying(e ->
                        assertThat(e.getCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(results.stream().filter(ServiceException.class::isInstance)
                .map(ServiceException.class::cast).findFirst()).hasValueSatisfying(e ->
                        assertThat(e.getSubCode())
                                .isEqualTo(WorkflowModelService.MODEL_VERSION_CONFLICT_SUB_CODE));
    }

    /**
     * 非法 BPMN 必须在持久化前失败，模型 revision 和源码保持不变。
     * @return void，无返回值
     */
    @Test
    void invalidBpmnRollsBack()
    {
        setUser(Set.of("workflow:model:save"));
        String modelId = createModel("非法回滚");
        int before = revision(modelId);
        String sourceBefore = modelService.getBpmnXml(modelId);
        assertThatThrownBy(() -> save(modelId, "<definitions><broken>", before))
                .isInstanceOf(ServiceException.class);
        assertThat(revision(modelId)).isEqualTo(before);
        assertThat(modelService.getBpmnXml(modelId)).isEqualTo(sourceBefore);
    }

    /**
     * 通过真实 Spring MVC 和 Security 链验证未认证、无权限及有权限保存分支。
     * @return void，无返回值
     */
    @Test
    void savePermissionIsEnforcedOverHttp() throws Exception
    {
        setUser(Set.of("workflow:model:save"));
        String modelId = createModelWithoutIdentity("权限");
        String xml = BPMN.formatted("permission", "权限");
        int before = revision(modelId);
        SecurityContextHolder.clearContext();

        mockMvc.perform(post("/workflow/model/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonMapper.shared().createObjectNode()
                                .put("modelId", modelId).put("bpmnXml", xml)
                                .put("expectedRevision", before).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(HttpStatus.UNAUTHORIZED));

        mockMvc.perform(post("/workflow/model/save")
                        .header("Authorization", "Bearer " + createLoginToken(Set.of()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonMapper.shared().createObjectNode()
                                .put("modelId", modelId).put("bpmnXml", xml)
                                .put("expectedRevision", before).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(HttpStatus.FORBIDDEN));

        mockMvc.perform(post("/workflow/model/save")
                        .header("Authorization", "Bearer "
                                + createLoginToken(Set.of("workflow:model:save")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonMapper.shared().createObjectNode()
                                .put("modelId", modelId).put("bpmnXml", xml)
                                .put("expectedRevision", before).toString()))
                .andExpect(status().isOk());
        assertThat(revision(modelId)).isGreaterThan(before);
        SecurityContextHolder.clearContext();
    }

    /**
     * 以真实权限主体创建模型。
     * @param name String，测试模型名称
     * @return String，Flowable 模型主键
     */
    private String createModel(String name)
    {
        setUser(Set.of("workflow:model:save"));
        return createModelWithoutIdentity(name);
    }

    /**
     * 创建模型夹具并登记清理主键。
     * @param name String，测试模型名称
     * @return String，Flowable 模型主键
     */
    private String createModelWithoutIdentity(String name)
    {
        WorkflowModelDto request = new WorkflowModelDto();
        request.setModelName(name);
        request.setModelKey("model-save-it-" + UUID.randomUUID().toString().replace("-", ""));
        request.setCategory(categoryCode);
        String id = modelService.createModel(request);
        modelIds.add(id);
        return id;
    }

    /**
     * 提交真实保存请求。
     * @param modelId String，模型主键
     * @param xml String，待保存 BPMN XML
     * @param expectedRevision int，详情读取的 revision
     * @return WorkflowModelSaveResult，服务端真实保存结果
     */
    private WorkflowModelSaveResult save(String modelId, String xml, int expectedRevision)
    {
        WorkflowModelDto request = new WorkflowModelDto();
        request.setModelId(modelId);
        request.setBpmnXml(xml);
        request.setExpectedRevision(expectedRevision);
        return modelService.saveModel(request);
    }

    /**
     * 读取 Flowable 模型持久化 revision。
     * @param modelId String，模型主键
     * @return int，当前 REV_ 修订号
     */
    private int revision(String modelId)
    {
        return ((org.flowable.engine.impl.persistence.entity.ModelEntity)
                repositoryService.getModel(modelId)).getRevision();
    }

    /**
     * 在线程中建立独立身份并执行保存，保留稳定业务异常供主线程断言。
     * @param start CountDownLatch，并发放行屏障
     * @param modelId String，模型主键
     * @param expected int，共享 revision 基线
     * @param name String，流程名称
     * @return Object，保存结果或 ServiceException
     */
    private Object concurrentSave(CountDownLatch start, String modelId, int expected, String name)
    {
        setUser(Set.of("workflow:model:save"));
        try
        {
            start.await(10, TimeUnit.SECONDS);
            return save(modelId, BPMN.formatted("concurrent", name), expected);
        }
        catch (ServiceException exception)
        {
            return exception;
        }
        catch (Exception exception)
        {
            throw new AssertionError(exception);
        }
        finally
        {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 设置当前线程的正式登录主体和权限集合。
     * @param permissions Set&lt;String&gt;，本次操作所需权限
     * @return void，无返回值
     */
    private void setUser(Set<String> permissions)
    {
        SysUser user = new SysUser(TEST_USER_ID);
        user.setUserName("workflow_model_save_it");
        LoginUser loginUser = new LoginUser(TEST_USER_ID, null, user, permissions);
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                loginUser, null, loginUser.getAuthorities());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(token);
        SecurityContextHolder.setContext(context);
    }

    /**
     * 创建真实 JWT 和 Redis 登录态，供 MockMvc 请求经过正式认证过滤器。
     * @param permissions Set&lt;String&gt;，写入 Token 登录快照的权限集合
     * @return String，可直接写入 Bearer Authorization 头的 JWT
     */
    private String createLoginToken(Set<String> permissions)
    {
        SysUser user = new SysUser(TEST_USER_ID);
        user.setUserName("admin");
        LoginUser loginUser = new LoginUser(TEST_USER_ID, null, user, permissions);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "workflow-model-save-it");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try
        {
            String jwt = tokenService.createToken(loginUser);
            loginTokenIds.add(loginUser.getToken());
            return jwt;
        }
        finally
        {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
