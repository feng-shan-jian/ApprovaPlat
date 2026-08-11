package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import javax.xml.stream.XMLInputFactory;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.CallActivity;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.flowable.identitylink.api.IdentityLink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfFormMapper;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;

class WorkflowCallActivityReferenceServiceTest
{
    private RepositoryService repositoryService;

    private ProcessDefinitionQuery definitionQuery;

    private WorkflowCallActivityReferenceService service;

    /**
     * 为每个测试创建 Flowable 仓储替身，并让链式查询返回同一受控查询对象。
     *
     * @return void，初始化完成后测试可精确配置定义查询结果
     */
    @BeforeEach
    void setUp()
    {
        repositoryService = mock(RepositoryService.class);
        definitionQuery = mock(ProcessDefinitionQuery.class);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(definitionQuery);
        when(definitionQuery.processDefinitionKey(anyString())).thenReturn(definitionQuery);
        when(definitionQuery.processDefinitionId(anyString())).thenReturn(definitionQuery);
        when(definitionQuery.processDefinitionWithoutTenantId()).thenReturn(definitionQuery);
        when(definitionQuery.latestVersion()).thenReturn(definitionQuery);
        service = new WorkflowCallActivityReferenceService(repositoryService);
    }

    /**
     * 验证部署编译把作者流程 key 替换为当前最新激活定义 ID，并写入 Flowable 精确绑定属性。
     *
     * @return void，目标仍按 key 解析或未写入 id 类型时测试失败
     */
    @Test
    void freezesLatestActiveDefinitionId()
    {
        ProcessDefinition target = definition("child:3:stable", "child-deployment", false);
        when(definitionQuery.singleResult()).thenReturn(target);

        byte[] compiled = service.freezeReferences(authorBpmn("child"));
        String compiledXml = new String(compiled, StandardCharsets.UTF_8);
        CallActivity call = parse(compiled).getMainProcess()
                .findFlowElementsOfType(CallActivity.class, true).get(0);

        assertThat(compiledXml).contains("calledElement=\"child:3:stable\"")
                .contains("flowable:calledElementType=\"id\"");
        assertThat(call.getCalledElement()).isEqualTo("child:3:stable");
        assertThat(call.getCalledElementType()).isEqualTo("id");
        assertThat(call.isSameDeployment()).isFalse();
    }

    /**
     * 验证动态调用表达式在访问定义目录之前即被拒绝，保证部署零副作用。
     *
     * @return void，动态表达式可进入运行时或产生仓储查询时测试失败
     */
    @Test
    void rejectsDynamicCalledElementBeforeRepositoryLookup()
    {
        assertThatThrownBy(() -> service.freezeReferences(authorBpmn("${childKey}")))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo("调用活动不允许使用动态流程表达式");
                });

        verify(definitionQuery, never()).processDefinitionKey(anyString());
        verify(definitionQuery, never()).singleResult();
    }

    /**
     * 验证保护集合从已发布编译资源提取精确定义 ID，而不是从作者 key 推断。
     *
     * @return void，精确目标遗漏或普通 key 被误保护时测试失败
     */
    @Test
    void collectsFrozenTargetsFromPublishedResources()
    {
        ProcessDefinition parent = definition("parent:1:id", "parent-deployment", false);
        when(definitionQuery.list()).thenReturn(List.of(parent));
        when(repositoryService.getBpmnModel("parent:1:id"))
                .thenReturn(parse(compiledBpmn("child:2:frozen")));

        assertThat(service.frozenTargetDefinitionIds())
                .isEqualTo(Set.of("child:2:frozen"));
    }

    /**
     * 验证目标部署仍被删除范围外父流程引用时返回 409，删除方可保持零副作用。
     *
     * @return void，外部引用未被拦截或错误码不稳定时测试失败
     */
    @Test
    void rejectsDeletingReferencedTargetDeployment()
    {
        ProcessDefinition child = definition("child:2:frozen", "child-deployment", false);
        ProcessDefinition parent = definition("parent:1:id", "parent-deployment", false);
        when(definitionQuery.list()).thenReturn(List.of(child, parent));
        when(repositoryService.getBpmnModel("parent:1:id"))
                .thenReturn(parse(compiledBpmn("child:2:frozen")));

        assertThatThrownBy(() -> service.assertDeploymentsNotReferenced(
                Set.of("child-deployment")))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getMessage()).isEqualTo("部署仍被调用活动引用，不能删除");
                });
    }

    /**
     * 验证发布目录按当前用户及候选组过滤，保留有权版本的真实激活状态供设计器回显。
     * @return void，越权定义泄露、角色授权遗漏或停用状态丢失时测试失败
     */
    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void filtersPublishedCatalogByReferencePermissionAndStatus()
    {
        WorkflowEngineOperations operations = mock(WorkflowEngineOperations.class);
        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        WorkflowDeploymentArtifactRepository artifactRepository =
                mock(WorkflowDeploymentArtifactRepository.class);
        WfFormMapper formMapper = mock(WfFormMapper.class);
        when(operations.read(any(Supplier.class))).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        when(identityResolver.resolveCurrentIdentity())
                .thenReturn(new WorkflowCurrentIdentity("7", Set.of("ROLE2")));
        when(definitionQuery.orderByProcessDefinitionKey()).thenReturn(definitionQuery);
        when(definitionQuery.orderByProcessDefinitionVersion()).thenReturn(definitionQuery);
        when(definitionQuery.asc()).thenReturn(definitionQuery);
        when(definitionQuery.desc()).thenReturn(definitionQuery);

        ProcessDefinition userAllowed = catalogDefinition(
                "expense:2:user", "expense", "费用审批", 2, false);
        ProcessDefinition groupAllowedSuspended = catalogDefinition(
                "audit:1:group", "audit", "审计复核", 1, true);
        ProcessDefinition forbidden = catalogDefinition(
                "secret:1:forbidden", "secret", "保密流程", 1, false);
        when(definitionQuery.list()).thenReturn(List.of(
                userAllowed, groupAllowedSuspended, forbidden));
        when(artifactRepository.selectForms(anyString())).thenReturn(List.of());
        IdentityLink userLink = identityLink("7", null);
        IdentityLink groupLink = identityLink(null, "ROLE2");
        IdentityLink forbiddenLink = identityLink("99", "ROLE9");
        when(repositoryService.getIdentityLinksForProcessDefinition(userAllowed.getId()))
                .thenReturn(List.of(userLink));
        when(repositoryService.getIdentityLinksForProcessDefinition(groupAllowedSuspended.getId()))
                .thenReturn(List.of(groupLink));
        when(repositoryService.getIdentityLinksForProcessDefinition(forbidden.getId()))
                .thenReturn(List.of(forbiddenLink));
        when(repositoryService.getBpmnModel(userAllowed.getId()))
                .thenReturn(parse(publishedBpmn("expense")));
        when(repositoryService.getBpmnModel(groupAllowedSuspended.getId()))
                .thenReturn(parse(publishedBpmn("audit")));

        WorkflowCallActivityReferenceService productionService =
                new WorkflowCallActivityReferenceService(repositoryService, operations,
                        identityResolver, artifactRepository, formMapper,
                        new WorkflowFormTemplateValidator());

        assertThat(productionService.listReferenceOptions(null))
                .extracting(option -> option.definitionId() + ":" + option.status())
                .containsExactly("expense:2:user:ACTIVE", "audit:1:group:SUSPENDED");
    }

    /**
     * 验证父子表单字段类型不兼容时在部署和快照写入之前失败。
     *
     * @return void，非法映射进入部署结果或写入依赖快照时测试失败
     */
    @Test
    void rejectsIncompatibleMappingBeforeSnapshotWrite()
    {
        WorkflowEngineOperations operations = mock(WorkflowEngineOperations.class);
        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        WorkflowDeploymentArtifactRepository artifactRepository =
                mock(WorkflowDeploymentArtifactRepository.class);
        WfFormMapper formMapper = mock(WfFormMapper.class);
        ProcessDefinition target = catalogDefinition(
                "child:1:stable", "child", "子流程", 1, false);
        when(definitionQuery.singleResult()).thenReturn(target);
        when(repositoryService.getIdentityLinksForProcessDefinition(target.getId()))
                .thenReturn(List.of());
        when(repositoryService.getBpmnModel(target.getId()))
                .thenReturn(parse(publishedBpmn("child")));
        when(artifactRepository.selectForms(target.getDeploymentId()))
                .thenReturn(List.of(deployForm("start", formField("childAmount", "el-input-number"))));

        WorkflowCallActivityReferenceService productionService =
                new WorkflowCallActivityReferenceService(repositoryService, operations,
                        identityResolver, artifactRepository, formMapper,
                        new WorkflowFormTemplateValidator());
        byte[] authorBytes = mappedAuthorBpmn(
                "child", "parentText", "childAmount");
        WorkflowBpmnDocument authorDocument = new WorkflowBpmnDocument(
                parse(authorBytes), new String(authorBytes, StandardCharsets.UTF_8),
                List.of(new WorkflowBpmnFormReference(WorkflowFormSourceType.EMBEDDED,
                        null, WorkflowFormSourceType.EMBEDDED_FORM_KEY, "start", "提交",
                        formField("parentText", "el-input"), "parent")));

        assertThatThrownBy(() -> productionService.prepare(
                authorBytes, authorDocument, new WorkflowCurrentIdentity("7", Set.of())))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo("调用活动输入变量字段类型不兼容");
                });
        verify(artifactRepository, never()).persist(anyString(), any());
    }

    /**
     * 创建流程定义替身并固定删除保护需要的主键、部署和挂起状态。
     *
     * @param id String，流程定义主键
     * @param deploymentId String，定义所属部署主键
     * @param suspended boolean，定义是否挂起
     * @return ProcessDefinition，可参与版本解析和删除引用检查的替身
     */
    private ProcessDefinition definition(String id, String deploymentId, boolean suspended)
    {
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getId()).thenReturn(id);
        when(definition.getKey()).thenReturn("child");
        when(definition.getDeploymentId()).thenReturn(deploymentId);
        when(definition.isSuspended()).thenReturn(suspended);
        return definition;
    }

    /**
     * 创建目录测试所需的完整流程定义替身。
     * @param id String，定义主键
     * @param key String，流程 key
     * @param name String，流程名称
     * @param version int，流程版本
     * @param suspended boolean，是否停用
     * @return ProcessDefinition，具备目录字段的定义替身
     */
    private ProcessDefinition catalogDefinition(String id, String key, String name,
            int version, boolean suspended)
    {
        ProcessDefinition definition = definition(id, "deployment-" + id, suspended);
        when(definition.getKey()).thenReturn(key);
        when(definition.getName()).thenReturn(name);
        when(definition.getVersion()).thenReturn(version);
        when(definition.getCategory()).thenReturn("test");
        return definition;
    }

    /**
     * 创建候选发起身份链接替身，目录服务将其复用为设计期引用权限。
     * @param userId String，可空用户主键
     * @param groupId String，可空角色或部门组主键
     * @return IdentityLink，受控身份链接
     */
    private IdentityLink identityLink(String userId, String groupId)
    {
        IdentityLink link = mock(IdentityLink.class);
        when(link.getUserId()).thenReturn(userId);
        when(link.getGroupId()).thenReturn(groupId);
        return link;
    }

    /**
     * 构造作者 BPMN，调用目标保持设计阶段 key 或表达式原值。
     *
     * @param calledElement String，作者配置的调用目标
     * @return byte[]，UTF-8 BPMN 资源
     */
    private byte[] authorBpmn(String calledElement)
    {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                + "xmlns:flowable=\"http://flowable.org/bpmn\" targetNamespace=\"urn:test\">"
                + "<process id=\"parent\" isExecutable=\"true\"><startEvent id=\"start\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"call\"/>"
                + "<callActivity id=\"call\" calledElement=\"" + calledElement + "\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"call\" targetRef=\"end\"/>"
                + "<endEvent id=\"end\"/></process></definitions>";
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 构造已编译为精确定义 ID 的父流程资源。
     *
     * @param targetDefinitionId String，冻结的目标流程定义主键
     * @return byte[]，包含 flowable:calledElementType=id 的 UTF-8 BPMN
     */
    private byte[] compiledBpmn(String targetDefinitionId)
    {
        String xml = new String(authorBpmn(targetDefinitionId), StandardCharsets.UTF_8)
                .replace("<callActivity id=\"call\"",
                        "<callActivity id=\"call\" flowable:calledElementType=\"id\"");
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 构造包含一条 Flowable 原生输入映射的作者 BPMN。
     *
     * @param calledElement String，作者选择的子流程 key
     * @param source String，父流程来源变量
     * @param target String，子流程目标变量
     * @return byte[]，UTF-8 BPMN 资源
     */
    private byte[] mappedAuthorBpmn(String calledElement, String source, String target)
    {
        String xml = new String(authorBpmn(calledElement), StandardCharsets.UTF_8)
                .replace("<callActivity id=\"call\" calledElement=\"" + calledElement + "\"/>",
                        "<callActivity id=\"call\" calledElement=\"" + calledElement
                                + "\"><extensionElements><flowable:in source=\"" + source
                                + "\" target=\"" + target
                                + "\"/></extensionElements></callActivity>");
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 构造部署表单快照。
     *
     * @param nodeKey String，快照所属节点 key
     * @param content String，已校验表单 JSON
     * @return WfDeployForm，子流程不可变表单快照
     */
    private WfDeployForm deployForm(String nodeKey, String content)
    {
        WfDeployForm snapshot = new WfDeployForm();
        snapshot.setNodeKey(nodeKey);
        snapshot.setContent(content);
        return snapshot;
    }

    /**
     * 构造只包含一个可映射标量字段的正式表单 JSON。
     *
     * @param variable String，字段变量名
     * @param tag String，Element Plus 组件 tag
     * @return String，可通过正式模板校验器的表单 JSON
     */
    private String formField(String variable, String tag)
    {
        return "{\"fields\":[{\"__vModel__\":\"" + variable
                + "\",\"__config__\":{\"layout\":\"colFormItem\",\"tag\":\""
                + tag + "\",\"label\":\"字段\"}}]}";
    }

    /**
     * 构造目录测试所需的单开始节点已发布流程资源。
     * @param processKey String，流程 key
     * @return byte[]，UTF-8 BPMN 资源
     */
    private byte[] publishedBpmn(String processKey)
    {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                + "targetNamespace=\"urn:test\"><process id=\"" + processKey
                + "\" isExecutable=\"true\"><startEvent id=\"start\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"end\"/>"
                + "<endEvent id=\"end\"/></process></definitions>";
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 使用 Flowable 公共转换器解析测试 BPMN，断言正式编译结果而非字符串片段。
     *
     * @param bytes byte[]，待解析 BPMN
     * @return org.flowable.bpmn.model.BpmnModel，结构化流程模型
     */
    private org.flowable.bpmn.model.BpmnModel parse(byte[] bytes)
    {
        try
        {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
            return new BpmnXMLConverter().convertToBpmnModel(
                    factory.createXMLStreamReader(new ByteArrayInputStream(bytes)));
        }
        catch (Exception exception)
        {
            throw new AssertionError("测试 BPMN 解析失败", exception);
        }
    }
}
