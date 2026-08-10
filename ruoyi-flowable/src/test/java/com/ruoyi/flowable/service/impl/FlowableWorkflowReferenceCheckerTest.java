package com.ruoyi.flowable.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Model;
import org.flowable.engine.repository.ModelQuery;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.ruoyi.flowable.mapper.WfDeployFormMapper;

@ExtendWith(MockitoExtension.class)
class FlowableWorkflowReferenceCheckerTest
{
    @Mock
    private RepositoryService repositoryService;

    @Mock
    private WfDeployFormMapper deployFormMapper;

    private FlowableWorkflowReferenceChecker checker;

    /**
     * 为每个测试创建独立引用检查器。
     * @return void，无返回值
     */
    @BeforeEach
    void setUp()
    {
        checker = new FlowableWorkflowReferenceChecker(repositoryService, deployFormMapper);
    }

    /**
     * 验证未部署模型的分类会阻止分类删除。
     * @return void，断言失败时测试失败
     */
    @Test
    void detectsCategoryReferenceFromUndeployedModel()
    {
        ModelQuery query = categoryModelQuery("leave", 1L);
        when(repositoryService.createModelQuery()).thenReturn(query);

        assertThat(checker.hasCategoryReference("leave")).isTrue();
    }

    /**
     * 验证已部署流程定义的分类会阻止分类删除。
     * @return void，断言失败时测试失败
     */
    @Test
    void detectsCategoryReferenceFromProcessDefinition()
    {
        ModelQuery modelQuery = categoryModelQuery("leave", 0L);
        ProcessDefinitionQuery definitionQuery = mock(ProcessDefinitionQuery.class);
        when(repositoryService.createModelQuery()).thenReturn(modelQuery);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(definitionQuery);
        when(definitionQuery.processDefinitionCategory("leave")).thenReturn(definitionQuery);
        when(definitionQuery.count()).thenReturn(1L);

        assertThat(checker.hasCategoryReference("leave")).isTrue();
    }

    /**
     * 验证部署快照中的显式 form_id 会优先阻止表单删除。
     * @return void，断言失败时测试失败
     */
    @Test
    void detectsFormReferenceFromDeploymentSnapshot()
    {
        when(deployFormMapper.countByFormIds(anyCollection())).thenReturn(1);

        assertThat(checker.hasFormReference(List.of(7L))).isTrue();
    }

    /**
     * 验证未部署模型元数据中的 formId 和嵌套 formKey 均会阻止表单删除。
     * @param metaInfo String，包含表单引用的模型元数据 JSON
     * @return void，断言失败时测试失败
     */
    @ParameterizedTest
    @ValueSource(strings = { "{\"formId\":7}", "{\"nested\":{\"formKey\":\"key_7\"}}" })
    void detectsFormReferenceFromModelMetadata(String metaInfo)
    {
        Model model = mock(Model.class);
        when(model.getMetaInfo()).thenReturn(metaInfo);
        stubUndeployedModels(List.of(model));

        assertThat(checker.hasFormReference(List.of(7L))).isTrue();
    }

    /**
     * 验证未部署模型的 BPMN XML 中旧 key_&lt;formId&gt; 表单键会阻止删除。
     * @return void，断言失败时测试失败
     */
    @Test
    void detectsFormReferenceFromUndeployedModelBpmn()
    {
        Model model = mock(Model.class);
        when(model.getId()).thenReturn("model-1");
        when(model.getMetaInfo()).thenReturn("{}");
        when(model.hasEditorSource()).thenReturn(true);
        when(repositoryService.getModelEditorSource("model-1"))
                .thenReturn(bpmnXml("key_7").getBytes(StandardCharsets.UTF_8));
        stubUndeployedModels(List.of(model));

        assertThat(checker.hasFormReference(List.of(7L))).isTrue();
    }

    /**
     * 验证已部署流程定义的开始节点和用户任务表单键会阻止删除。
     * @return void，断言失败时测试失败
     */
    @Test
    void detectsFormReferenceFromDeployedDefinitionBpmn()
    {
        stubUndeployedModels(List.of());
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getId()).thenReturn("definition-1");
        stubProcessDefinitions(List.of(definition));
        when(repositoryService.getBpmnModel("definition-1"))
                .thenReturn(bpmnModel(null, "key_7"));

        assertThat(checker.hasFormReference(List.of(7L))).isTrue();
    }

    /**
     * 验证损坏的模型元数据会按保守策略判定存在引用。
     * @return void，断言失败时测试失败
     */
    @Test
    void treatsMalformedModelMetadataAsReference()
    {
        Model model = mock(Model.class);
        when(model.getMetaInfo()).thenReturn("{broken-json");
        stubUndeployedModels(List.of(model));

        assertThat(checker.hasFormReference(List.of(7L))).isTrue();
    }

    /**
     * 验证损坏的 BPMN 模型资源会按保守策略判定存在引用。
     * @return void，断言失败时测试失败
     */
    @Test
    void treatsMalformedModelBpmnAsReference()
    {
        Model model = mock(Model.class);
        when(model.getId()).thenReturn("model-broken");
        when(model.getMetaInfo()).thenReturn("{}");
        when(model.hasEditorSource()).thenReturn(true);
        when(repositoryService.getModelEditorSource("model-broken"))
                .thenReturn("not-bpmn".getBytes(StandardCharsets.UTF_8));
        stubUndeployedModels(List.of(model));

        assertThat(checker.hasFormReference(List.of(7L))).isTrue();
    }

    /**
     * 验证快照、模型和定义均无引用时允许删除继续执行。
     * @return void，断言失败时测试失败
     */
    @Test
    void returnsFalseWhenNoFormReferenceExists()
    {
        stubUndeployedModels(List.of());
        stubProcessDefinitions(List.of());

        assertThat(checker.hasFormReference(List.of(7L))).isFalse();
    }

    /**
     * 创建支持分类查询链的 ModelQuery mock。
     * @param categoryCode String，分类编码
     * @param count long，查询返回数量
     * @return ModelQuery，已配置查询链的 mock
     */
    private ModelQuery categoryModelQuery(String categoryCode, long count)
    {
        ModelQuery query = mock(ModelQuery.class);
        when(query.notDeployed()).thenReturn(query);
        when(query.modelCategory(categoryCode)).thenReturn(query);
        when(query.count()).thenReturn(count);
        return query;
    }

    /**
     * 配置未部署模型查询结果。
     * @param models List&lt;Model&gt;，查询应返回的模型
     * @return void，无返回值
     */
    private void stubUndeployedModels(List<Model> models)
    {
        ModelQuery query = mock(ModelQuery.class);
        when(repositoryService.createModelQuery()).thenReturn(query);
        when(query.notDeployed()).thenReturn(query);
        when(query.list()).thenReturn(models);
    }

    /**
     * 配置已部署流程定义查询结果。
     * @param definitions List&lt;ProcessDefinition&gt;，查询应返回的流程定义
     * @return void，无返回值
     */
    private void stubProcessDefinitions(List<ProcessDefinition> definitions)
    {
        ProcessDefinitionQuery query = mock(ProcessDefinitionQuery.class);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(query);
        when(query.list()).thenReturn(definitions);
    }

    /**
     * 创建包含可选开始节点和用户任务表单键的 BPMN 模型。
     * @param startFormKey String，开始节点表单键，可为空
     * @param taskFormKey String，用户任务表单键，可为空
     * @return BpmnModel，可由引用检查器遍历的模型
     */
    private BpmnModel bpmnModel(String startFormKey, String taskFormKey)
    {
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId("process-1");
        StartEvent startEvent = new StartEvent();
        startEvent.setId("start");
        startEvent.setFormKey(startFormKey);
        process.addFlowElement(startEvent);
        UserTask userTask = new UserTask();
        userTask.setId("approve");
        userTask.setFormKey(taskFormKey);
        process.addFlowElement(userTask);
        BpmnModel model = new BpmnModel();
        model.addProcess(process);
        return model;
    }

    /**
     * 创建最小可解析 BPMN XML。
     * @param formKey String，开始节点表单键
     * @return String，UTF-8 BPMN XML 文本
     */
    private String bpmnXml(String formKey)
    {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://ruoyi.example/workflow">
                  <process id="process_1" name="测试流程" isExecutable="true">
                    <startEvent id="start" flowable:formKey="%s"/>
                  </process>
                </definitions>
                """.formatted(formKey);
    }
}
