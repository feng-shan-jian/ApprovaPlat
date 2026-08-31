package com.ruoyi.flowable.service.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.impl.persistence.entity.ModelEntity;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.Model;
import org.flowable.engine.repository.ModelQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfCategory;
import com.ruoyi.flowable.domain.WfForm;
import com.ruoyi.flowable.domain.dto.WorkflowModelDto;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfCategoryMapper;
import com.ruoyi.flowable.mapper.WfFormMapper;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 使用真实 Flowable H2 仓储验证模型元数据更新的部署门禁和 revision 并发裁决。
 */
class WorkflowModelServiceUpdateIntegrationTest
{
    /** 测试事务内使用的规范操作人。 */
    private static final String ACTOR_ID = "100";

    /** 未部署夹具的初始名称。 */
    private static final String ORIGINAL_NAME = "原模型名称";

    /** 未部署夹具的初始分类。 */
    private static final String ORIGINAL_CATEGORY = "original-category";

    /** 更新请求中的目标名称。 */
    private static final String UPDATED_NAME = "更新后的模型名称";

    /** 更新请求中的目标分类。 */
    private static final String UPDATED_CATEGORY = "updated-category";

    /** 更新请求引用的真实有效表单主键。 */
    private static final Long UPDATED_FORM_ID = 200L;

    /** 初始 metaInfo，保留未知字段以同时验证结构化合并语义。 */
    private static final String ORIGINAL_META_INFO = """
            {"createUser":"100","description":"原描述","formType":2,"legacy":"保留"}
            """.trim();

    /** 模型编辑器源码；元数据更新成功或失败均不得修改。 */
    private static final byte[] MODEL_SOURCE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         targetNamespace="http://approvaplat.test/metadata-guard">
              <process id="metadataGuard" name="元数据门禁" isExecutable="true">
                <startEvent id="start" />
                <endEvent id="end" />
                <sequenceFlow id="flow" sourceRef="start" targetRef="end" />
              </process>
            </definitions>
            """.getBytes(StandardCharsets.UTF_8);

    /** 每个用例独占的真实 H2、Flowable 与 Spring 事务基础设施。 */
    private WorkflowFlowableEngineTestSupport engineSupport;

    /** 绕过 Mockito 统计、直接执行真实 Flowable 命令的仓储入口。 */
    private RepositoryService actualRepositoryService;

    /** 生产服务使用的真实仓储 spy，仅观察查询和保存调用。 */
    private RepositoryService repositoryService;

    /** 分类真实性校验入口。 */
    private WfCategoryMapper categoryMapper;

    /** 表单真实性校验入口。 */
    private WfFormMapper formMapper;

    /** 本测试直接调用的生产模型服务。 */
    private WorkflowModelService service;

    /**
     * 为每个用例启动独立真实 Flowable H2，并装配生产事务和身份边界。
     *
     * @return void，无返回值；失败时由 JUnit 报告环境启动错误
     */
    @BeforeEach
    void setUp()
    {
        engineSupport = WorkflowFlowableEngineTestSupport.start(
                "model-metadata-edit-guard", Map.of());
        ProcessEngine processEngine = engineSupport.processEngine();
        actualRepositoryService = processEngine.getRepositoryService();
        repositoryService = spy(actualRepositoryService);

        categoryMapper = mock(WfCategoryMapper.class);
        formMapper = mock(WfFormMapper.class);
        when(categoryMapper.selectByCode(UPDATED_CATEGORY)).thenReturn(activeCategory());
        when(formMapper.selectById(UPDATED_FORM_ID)).thenReturn(activeForm());

        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveCurrentIdentity())
                .thenReturn(new WorkflowCurrentIdentity(ACTOR_ID, Set.of()));
        WorkflowEngineOperations engineOperations = engineSupport.transactionalProxy(
                new WorkflowEngineOperations(
                        new WorkflowAuthenticationContext(processEngine.getIdentityService(),
                                new WorkflowIdentityCodec()),
                        new WorkflowExceptionTranslator(), identityResolver));

        service = new WorkflowModelService(engineOperations, repositoryService,
                mock(WorkflowBpmnService.class),
                mock(WorkflowBpmnIdentityValidator.class), categoryMapper, formMapper,
                mock(WorkflowDeploymentArtifactRepository.class),
                mock(WorkflowFormTemplateValidator.class),
                mock(WorkflowExtensionDeploymentService.class),
                mock(WorkflowControlledLoopDeploymentService.class),
                mock(WorkflowConditionDeploymentService.class),
                mock(WorkflowParticipantRuleDeploymentService.class),
                mock(WorkflowDmnDecisionService.class),
                mock(WorkflowFormFieldExtensionService.class),
                mock(WorkflowCallActivityReferenceService.class),
                mock(WorkflowTaskSlaDeploymentService.class));
    }

    /**
     * 关闭当前用例独占的引擎和 H2 数据源，避免线程、连接及模型状态跨用例残留。
     *
     * @return void，无返回值；清理失败时由测试基础设施抛出原异常
     */
    @AfterEach
    void tearDown()
    {
        if (engineSupport != null)
        {
            engineSupport.close();
        }
    }

    /**
     * 验证 deploymentId 已存在时直接短路，且不执行 deployed 查询或任何模型写入。
     *
     * @return void，无返回值；断言固定 409 协议以及模型、源码和部署零变化
     */
    @Test
    void rejectsModelWithDeploymentIdBeforeAnyMetadataMutation()
    {
        Deployment deployment = createRealDeployment("field-short-circuit");
        String modelId = createModel("field-short-circuit-model", deployment.getId());
        ModelSnapshot before = snapshot(modelId);
        long deploymentCountBefore = actualRepositoryService.createDeploymentQuery().count();
        clearInvocations(repositoryService);

        ServiceException failure = assertThrows(ServiceException.class,
                () -> service.updateModel(updateRequest(modelId)));

        assertDeployedEditConflict(failure);
        assertSnapshotEquals(before, snapshot(modelId));
        assertArrayEquals(MODEL_SOURCE,
                actualRepositoryService.getModelEditorSource(modelId));
        assertEquals(deploymentCountBefore,
                actualRepositoryService.createDeploymentQuery().count());
        verify(repositoryService, never()).createModelQuery();
        verify(repositoryService, never()).saveModel(any(Model.class));
        verify(categoryMapper, never()).selectByCode(anyString());
    }

    /**
     * 验证字段为空时完整复用 deployed 查询结果，不允许仅依赖 Model.deploymentId。
     *
     * @return void，无返回值；真实 H2 查询命中部署关系后必须返回固定 409 且不保存
     */
    @Test
    void rejectsModelWhenDeployedQueryFindsRelationWithEmptyLoadedField()
    {
        Deployment deployment = createRealDeployment("query-fallback");
        String modelId = createModel("query-fallback-model", deployment.getId());
        ModelSnapshot before = snapshot(modelId);
        assertEquals(1L, actualRepositoryService.createModelQuery()
                .modelId(modelId).deployed().count());

        // 模拟字段投影暂未携带部署 ID；兜底判断仍必须由真实 H2 deployed 查询裁决。
        Model modelWithoutDeploymentField = mock(Model.class);
        when(modelWithoutDeploymentField.getId()).thenReturn(modelId);
        when(modelWithoutDeploymentField.getDeploymentId()).thenReturn(null);
        clearInvocations(repositoryService);
        doReturn(modelWithoutDeploymentField).when(repositoryService).getModel(modelId);

        ServiceException failure = assertThrows(ServiceException.class,
                () -> service.updateModel(updateRequest(modelId)));

        assertDeployedEditConflict(failure);
        assertSnapshotEquals(before, snapshot(modelId));
        verify(repositoryService, times(1)).createModelQuery();
        verify(repositoryService, never()).saveModel(any(Model.class));
        verify(categoryMapper, never()).selectByCode(anyString());
    }

    /**
     * 验证真实 deployed 查询返回零时，名称、分类、描述和表单元数据仍可一次性更新。
     *
     * @return void，无返回值；断言 saveModel 恰好一次并保留未知 metaInfo 字段
     */
    @Test
    void updatesAllSupportedMetadataForUndeployedModelExactlyOnce() throws Exception
    {
        String modelId = createModel("undeployed-update", null);
        ModelSnapshot before = snapshot(modelId);
        assertEquals(0L, actualRepositoryService.createModelQuery()
                .modelId(modelId).deployed().count());
        clearInvocations(repositoryService);

        service.updateModel(updateRequest(modelId));

        ModelSnapshot after = snapshot(modelId);
        assertEquals(UPDATED_NAME, after.name());
        assertEquals(UPDATED_CATEGORY, after.category());
        assertEquals(before.revision() + 1, after.revision());
        assertNull(after.deploymentId());
        JsonNode metadata = JsonMapper.shared().readTree(after.metaInfo());
        assertEquals("更新后的描述", metadata.path("description").textValue());
        assertEquals(0, metadata.path("formType").intValue());
        assertEquals(UPDATED_FORM_ID.longValue(), metadata.path("formId").longValue());
        assertEquals(ACTOR_ID, metadata.path("createUser").textValue());
        assertEquals("保留", metadata.path("legacy").textValue());
        assertArrayEquals(MODEL_SOURCE,
                actualRepositoryService.getModelEditorSource(modelId));
        verify(repositoryService, times(1)).createModelQuery();
        verify(repositoryService, times(1)).saveModel(any(Model.class));
        verify(categoryMapper, times(1)).selectByCode(UPDATED_CATEGORY);
        verify(formMapper, times(1)).selectById(UPDATED_FORM_ID);
    }

    /**
     * 验证元数据检查后真实部署事务先提交时，陈旧更新只能由 revision 乐观锁返回 409。
     *
     * @return void，无返回值；断言部署赢家的关系、模型字段、revision 和源码不被覆盖
     */
    @Test
    void deploymentWinnerForcesOptimisticLockConflictWithoutOverwritingWinner()
    {
        String modelId = createModel("deployment-winner", null);
        ModelSnapshot before = snapshot(modelId);
        Model staleModel = actualRepositoryService.getModel(modelId);
        long deploymentCountBefore = actualRepositoryService.createDeploymentQuery().count();
        AtomicBoolean winnerCommitted = new AtomicBoolean(false);
        AtomicReference<String> winnerDeploymentId = new AtomicReference<>();
        TransactionTemplate winnerTransaction = requiresNewTransaction();
        ModelQuery undeployedQuery = mock(ModelQuery.class);
        when(undeployedQuery.modelId(modelId)).thenReturn(undeployedQuery);
        when(undeployedQuery.deployed()).thenReturn(undeployedQuery);
        when(undeployedQuery.count()).thenReturn(0L);
        clearInvocations(repositoryService);
        // 避免 H2 REPEATABLE_READ 把陈旧写转换为序列化死锁；检查结果已由前两类用例覆盖，
        // 本用例保留真实陈旧 Model、真实赢家事务和真实 saveModel，精确验证 Flowable revision 异常。
        doReturn(staleModel).when(repositoryService).getModel(modelId);
        doReturn(undeployedQuery).when(repositoryService).createModelQuery();

        doAnswer(invocation ->
        {
            // 外层更新已完成部署检查和字段变更；独立事务此时先提交真实部署关系并推进 REV_。
            if (winnerCommitted.compareAndSet(false, true))
            {
                winnerTransaction.executeWithoutResult(status ->
                {
                    Deployment deployment = createRealDeployment("concurrent-winner");
                    Model winner = actualRepositoryService.getModel(modelId);
                    winner.setDeploymentId(deployment.getId());
                    actualRepositoryService.saveModel(winner);
                    winnerDeploymentId.set(deployment.getId());
                });
            }
            // 继续执行真实 Flowable saveModel，让数据库 revision 条件裁决陈旧更新。
            actualRepositoryService.saveModel(invocation.getArgument(0));
            return null;
        }).when(repositoryService).saveModel(any(Model.class));

        ServiceException failure = assertThrows(ServiceException.class,
                () -> service.updateModel(updateRequest(modelId)));

        assertEquals(HttpStatus.CONFLICT, failure.getCode());
        assertEquals("工作流状态已发生变化，请刷新后重试", failure.getMessage());
        assertNull(failure.getSubCode());
        assertTrue(hasCause(failure, FlowableOptimisticLockingException.class));
        assertTrue(winnerCommitted.get());
        assertNotNull(winnerDeploymentId.get());

        ModelSnapshot after = snapshot(modelId);
        assertEquals(before.name(), after.name());
        assertEquals(before.category(), after.category());
        assertEquals(before.metaInfo(), after.metaInfo());
        assertEquals(before.revision() + 1, after.revision());
        assertEquals(winnerDeploymentId.get(), after.deploymentId());
        assertArrayEquals(MODEL_SOURCE,
                actualRepositoryService.getModelEditorSource(modelId));
        assertEquals(deploymentCountBefore + 1,
                actualRepositoryService.createDeploymentQuery().count());
        verify(repositoryService, times(1)).createModelQuery();
        verify(repositoryService, times(1)).saveModel(any(Model.class));
    }

    /**
     * 创建包含全部受支持元数据字段的更新请求。
     *
     * @param modelId String，待更新 Flowable 模型主键
     * @return WorkflowModelDto，名称、分类、描述和流程级表单均已设置的请求
     */
    private WorkflowModelDto updateRequest(String modelId)
    {
        WorkflowModelDto request = new WorkflowModelDto();
        request.setModelId(modelId);
        request.setModelName(UPDATED_NAME);
        request.setCategory(UPDATED_CATEGORY);
        request.setDescription("更新后的描述");
        request.setFormType(0);
        request.setFormId(UPDATED_FORM_ID);
        return request;
    }

    /**
     * 在真实 Flowable H2 仓储中创建模型及编辑器源码。
     *
     * @param key String，当前用例唯一的模型版本分组标识
     * @param deploymentId String，可选真实部署主键；null 表示未部署
     * @return String，持久化后的 Flowable 模型主键
     */
    private String createModel(String key, String deploymentId)
    {
        Model model = actualRepositoryService.newModel();
        model.setName(ORIGINAL_NAME);
        model.setKey(key);
        model.setCategory(ORIGINAL_CATEGORY);
        model.setVersion(1);
        model.setMetaInfo(ORIGINAL_META_INFO);
        model.setDeploymentId(deploymentId);
        actualRepositoryService.saveModel(model);
        actualRepositoryService.addModelEditorSource(model.getId(), MODEL_SOURCE);
        return model.getId();
    }

    /**
     * 创建带真实 BPMN 资源的 Flowable 部署，供模型 deploymentId 建立正式关系。
     *
     * @param suffix String，避免不同用例或并发阶段的资源名冲突
     * @return Deployment，已经提交到真实 H2 的部署记录
     */
    private Deployment createRealDeployment(String suffix)
    {
        return actualRepositoryService.createDeployment()
                .name("metadata-guard-" + suffix)
                .addBytes("metadata-guard-" + suffix + ".bpmn20.xml", MODEL_SOURCE)
                .deploy();
    }

    /**
     * 创建必须开启新事务的可重复读模板，使并发赢家能够在外层更新保存前独立提交。
     *
     * @return TransactionTemplate，REQUIRES_NEW 且使用 REPEATABLE_READ 隔离级别
     */
    private TransactionTemplate requiresNewTransaction()
    {
        TransactionTemplate template = new TransactionTemplate(engineSupport.transactionManager());
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return template;
    }

    /**
     * 构造分类真实性校验使用的有效分类记录。
     *
     * @return WfCategory，分类编码匹配更新请求且未逻辑删除
     */
    private WfCategory activeCategory()
    {
        WfCategory category = new WfCategory();
        category.setCode(UPDATED_CATEGORY);
        category.setCategoryName("更新分类");
        category.setDelFlag("0");
        return category;
    }

    /**
     * 构造表单真实性校验使用的有效表单记录。
     *
     * @return WfForm，正文完整且未逻辑删除的流程表单
     */
    private WfForm activeForm()
    {
        WfForm form = new WfForm();
        form.setFormId(UPDATED_FORM_ID);
        form.setFormName("更新表单");
        form.setContent("{\"fields\":[]}");
        form.setDelFlag("0");
        return form;
    }

    /**
     * 读取模型并冻结本测试关注的数据库字段。
     *
     * @param modelId String，待读取 Flowable 模型主键
     * @return ModelSnapshot，名称、分类、metaInfo、deploymentId 和 REV_ 快照
     */
    private ModelSnapshot snapshot(String modelId)
    {
        Model model = actualRepositoryService.getModel(modelId);
        assertNotNull(model);
        return new ModelSnapshot(model.getName(), model.getCategory(), model.getMetaInfo(),
                model.getDeploymentId(), ((ModelEntity) model).getRevision());
    }

    /**
     * 断言部署后元数据编辑使用固定冲突协议且不增加 subCode。
     *
     * @param failure ServiceException，updateModel 抛出的业务异常
     * @return void，无返回值；协议不匹配时由 JUnit 报告
     */
    private void assertDeployedEditConflict(ServiceException failure)
    {
        assertEquals(HttpStatus.CONFLICT, failure.getCode());
        assertEquals("已部署模型不能修改", failure.getMessage());
        assertNull(failure.getSubCode());
    }

    /**
     * 逐字段比较两个模型数据库快照。
     *
     * @param expected ModelSnapshot，操作前快照
     * @param actual ModelSnapshot，操作后快照
     * @return void，无返回值；任一字段变化时由 JUnit 报告
     */
    private void assertSnapshotEquals(ModelSnapshot expected, ModelSnapshot actual)
    {
        assertEquals(expected.name(), actual.name());
        assertEquals(expected.category(), actual.category());
        assertEquals(expected.metaInfo(), actual.metaInfo());
        assertEquals(expected.deploymentId(), actual.deploymentId());
        assertEquals(expected.revision(), actual.revision());
    }

    /**
     * 判断异常 cause 链是否包含指定类型，用于证明 409 确由真实 Flowable revision 裁决。
     *
     * @param failure Throwable，待检查的完整异常链
     * @param causeType Class&lt;? extends Throwable&gt;，期望出现的异常类型
     * @return boolean，cause 链包含目标类型时返回 true
     */
    private boolean hasCause(Throwable failure, Class<? extends Throwable> causeType)
    {
        Throwable current = failure;
        while (current != null)
        {
            if (causeType.isInstance(current))
            {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 模型更新边界需要保持或验证的 ACT_RE_MODEL 字段快照。
     *
     * @param name String，NAME_ 模型名称
     * @param category String，CATEGORY_ 分类编码
     * @param metaInfo String，META_INFO_ 结构化元数据
     * @param deploymentId String，DEPLOYMENT_ID_ 部署关系
     * @param revision int，REV_ 乐观锁修订号
     */
    private record ModelSnapshot(String name, String category, String metaInfo,
            String deploymentId, int revision)
    {
    }
}
