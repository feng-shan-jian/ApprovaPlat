package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.CallActivity;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

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
     * 验证部署编译把固定流程 key 替换为当前最新激活定义 ID。
     *
     * @return void，目标仍按 key 解析或未写入 id 类型时测试失败
     */
    @Test
    void freezesLatestActiveDefinitionId()
    {
        ProcessDefinition target = definition("child:3:stable", "child-deployment", false);
        when(definitionQuery.singleResult()).thenReturn(target);

        byte[] compiled = service.freezeReferences(authorBpmn("child"));
        CallActivity call = parse(compiled).getMainProcess()
                .findFlowElementsOfType(CallActivity.class, true).get(0);

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
        when(definition.getDeploymentId()).thenReturn(deploymentId);
        when(definition.isSuspended()).thenReturn(suspended);
        return definition;
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
