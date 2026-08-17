package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.GraphicInfo;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricActivityInstanceQuery;
import org.flowable.image.ProcessDiagramGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessSnapshot;
import com.ruoyi.flowable.authorization.WorkflowTaskAccessSnapshot;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDetailQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowProcessDetailView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessFormSnapshotView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessViewerView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.service.process.WorkflowProcessDetailService;

class WorkflowTaskReadServiceTest
{
    private static final String TASK_ID = "task-1";

    private static final String INSTANCE_ID = "instance-1";

    private static final String DEFINITION_ID = "approval:1:10";

    private final ObjectMapper objectMapper = JsonMapper.shared();

    private WorkflowEngineOperations engineOperations;

    private WorkflowProcessAccessService accessService;

    private WorkflowProcessDetailService detailService;

    private RepositoryService repositoryService;

    private HistoryService historyService;

    private ProcessEngineConfiguration processEngineConfiguration;

    private ProcessDiagramGenerator diagramGenerator;

    private HistoricActivityInstanceQuery activityQuery;

    private WorkflowTaskReadService readService;

    /**
     * 为每个测试创建只读事务替身、授权服务和 Flowable 公共查询服务。
     *
     * @return 无返回值，初始化后测试可直接执行读取服务
     */
    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp()
    {
        engineOperations = mock(WorkflowEngineOperations.class);
        accessService = mock(WorkflowProcessAccessService.class);
        detailService = mock(WorkflowProcessDetailService.class);
        repositoryService = mock(RepositoryService.class);
        historyService = mock(HistoryService.class);
        processEngineConfiguration = mock(ProcessEngineConfiguration.class);
        diagramGenerator = mock(ProcessDiagramGenerator.class);
        activityQuery = mock(HistoricActivityInstanceQuery.class, RETURNS_SELF);

        when(engineOperations.read(any(Supplier.class))).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        when(historyService.createHistoricActivityInstanceQuery()).thenReturn(activityQuery);
        when(activityQuery.processInstanceId(any())).thenReturn(activityQuery);
        when(activityQuery.orderByHistoricActivityInstanceStartTime()).thenReturn(activityQuery);
        when(activityQuery.asc()).thenReturn(activityQuery);
        when(processEngineConfiguration.getProcessDiagramGenerator()).thenReturn(diagramGenerator);
        when(processEngineConfiguration.getActivityFontName()).thenReturn("SansSerif");
        when(processEngineConfiguration.getLabelFontName()).thenReturn("SansSerif");
        when(processEngineConfiguration.getAnnotationFontName()).thenReturn("SansSerif");
        when(processEngineConfiguration.getClassLoader()).thenReturn(getClass().getClassLoader());

        readService = new WorkflowTaskReadService(engineOperations, accessService, detailService,
                repositoryService, historyService, processEngineConfiguration);
    }

    /**
     * 验证变量入口只合并详情服务已经按部署 schema 投影的安全字段，当前值覆盖历史值。
     *
     * @return 无返回值，未授权调用、字段合并或不可变性错误时测试失败
     */
    @Test
    void returnsOnlySchemaProjectedVariablesWithCurrentTaskPrecedence()
    {
        WorkflowTaskAccessSnapshot task = taskSnapshot();
        WorkflowProcessFormSnapshotView historicForm = form("historic-task",
                Map.of("amount", objectMapper.valueToTree(100),
                        "result", objectMapper.valueToTree("old")));
        WorkflowProcessFormSnapshotView currentForm = form(TASK_ID,
                Map.of("result", objectMapper.valueToTree("approved")));
        WorkflowProcessDetailView detail = detail(task, historicForm, currentForm);
        when(accessService.requireReadableTask(TASK_ID)).thenReturn(task);
        when(detailService.getDetail(new WorkflowProcessDetailQueryDto(INSTANCE_ID, TASK_ID)))
                .thenReturn(detail);

        Map<String, JsonNode> result = readService.getProcessVariables(TASK_ID);

        assertThat(result).containsOnlyKeys("amount", "result");
        assertThat(result.get("amount").asInt()).isEqualTo(100);
        assertThat(result.get("result").asText()).isEqualTo("approved");
        assertThatThrownBy(() -> result.put("unsafe", objectMapper.nullNode()))
                .isInstanceOf(UnsupportedOperationException.class);
        verify(accessService).requireReadableTask(TASK_ID);
    }

    /**
     * 验证任务对象级授权失败时不会继续读取详情或任何流程变量。
     *
     * @return 无返回值，未授权请求触发详情服务时测试失败
     */
    @Test
    void stopsVariableReadWhenTaskAccessIsForbidden()
    {
        ServiceException forbidden = new ServiceException("无权执行当前工作流操作",
                HttpStatus.FORBIDDEN);
        when(accessService.requireReadableTask(TASK_ID)).thenThrow(forbidden);

        assertThatThrownBy(() -> readService.getProcessVariables(TASK_ID)).isSameAs(forbidden);

        verify(detailService, never()).getDetail(any());
    }

    /**
     * 验证授权实例通过 Flowable 8 公共生成器输出带节点和顺序流高亮的 PNG。
     *
     * @return 无返回值，授权、媒体签名或高亮集合错误时测试失败
     */
    @Test
    @SuppressWarnings("unchecked")
    void generatesAuthorizedPngWithHistoricHighlights()
    {
        WorkflowProcessAccessSnapshot instance = processSnapshot();
        BpmnModel model = diagramModel();
        HistoricActivityInstance userTask = activity("review", "userTask");
        HistoricActivityInstance sequenceFlow = activity("flow-1", "sequenceFlow");
        byte[] png = validPng();
        when(accessService.requireReadableInstance(INSTANCE_ID)).thenReturn(instance);
        when(repositoryService.getBpmnModel(DEFINITION_ID)).thenReturn(model);
        when(activityQuery.list()).thenReturn(List.of(userTask, sequenceFlow));
        when(diagramGenerator.generateDiagram(eq(model), eq("png"), anyList(), anyList(),
                anyString(), anyString(), anyString(), any(ClassLoader.class),
                anyDouble(), anyBoolean()))
                .thenReturn(new ByteArrayInputStream(png));

        byte[] result = readService.generateDiagram(INSTANCE_ID);

        assertThat(result).containsExactly(png);
        ArgumentCaptor<List<String>> nodes = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<String>> flows = ArgumentCaptor.forClass(List.class);
        verify(diagramGenerator).generateDiagram(eq(model), eq("png"), nodes.capture(),
                flows.capture(), anyString(), anyString(), anyString(), any(ClassLoader.class),
                eq(1.0), eq(true));
        assertThat(nodes.getValue()).containsExactly("review");
        assertThat(flows.getValue()).containsExactly("flow-1");
    }

    /**
     * 验证流程图对象级授权失败时不会读取 BPMN 或调用图形生成器。
     *
     * @return 无返回值，未授权请求触发图形生成时测试失败
     */
    @Test
    void stopsDiagramGenerationWhenInstanceAccessIsForbidden()
    {
        ServiceException forbidden = new ServiceException("无权执行当前工作流操作",
                HttpStatus.FORBIDDEN);
        when(accessService.requireReadableInstance(INSTANCE_ID)).thenThrow(forbidden);

        assertThatThrownBy(() -> readService.generateDiagram(INSTANCE_ID)).isSameAs(forbidden);

        verify(repositoryService, never()).getBpmnModel(any());
        verify(diagramGenerator, never()).generateDiagram(any(), anyString(), anyList(),
                anyList(), anyString(), anyString(), anyString(), any(ClassLoader.class),
                anyDouble(), anyBoolean());
    }

    /**
     * 验证图形生成器返回非 PNG 内容时服务拒绝向 Controller 输出错误媒体。
     *
     * @return 无返回值，无效媒体未返回服务端错误时测试失败
     */
    @Test
    void rejectsInvalidDiagramMedia()
    {
        when(accessService.requireReadableInstance(INSTANCE_ID)).thenReturn(processSnapshot());
        BpmnModel model = diagramModel();
        when(repositoryService.getBpmnModel(DEFINITION_ID)).thenReturn(model);
        when(activityQuery.list()).thenReturn(List.of());
        when(diagramGenerator.generateDiagram(eq(model), eq("png"), anyList(), anyList(),
                anyString(), anyString(), anyString(), any(ClassLoader.class),
                anyDouble(), anyBoolean()))
                .thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));

        assertThatThrownBy(() -> readService.generateDiagram(INSTANCE_ID))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR));
    }

    /**
     * 验证缺少 BPMN DI 的正式部署在调用 Flowable 图形生成器前被稳定拒绝。
     *
     * @return 无返回值，缺失图形坐标仍触发生成器或泄漏未知异常时测试失败
     */
    @Test
    void rejectsModelWithoutDiagramInterchangeBeforeGeneration()
    {
        when(accessService.requireReadableInstance(INSTANCE_ID)).thenReturn(processSnapshot());
        when(repositoryService.getBpmnModel(DEFINITION_ID)).thenReturn(new BpmnModel());

        assertThatThrownBy(() -> readService.generateDiagram(INSTANCE_ID))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR);
                    assertThat(exception.getMessage()).isEqualTo("流程定义缺少 BPMN DI 图形信息");
                });

        verify(diagramGenerator, never()).generateDiagram(any(), anyString(), anyList(),
                anyList(), anyString(), anyString(), anyString(), any(ClassLoader.class),
                anyDouble(), anyBoolean());
    }

    /**
     * 验证 Flowable 图形生成器的未受控运行时异常被转换为稳定服务端业务异常。
     *
     * @return 无返回值，NPE 等底层异常直接越过服务边界时测试失败
     */
    @Test
    void translatesUnexpectedDiagramGeneratorFailure()
    {
        when(accessService.requireReadableInstance(INSTANCE_ID)).thenReturn(processSnapshot());
        when(repositoryService.getBpmnModel(DEFINITION_ID)).thenReturn(diagramModel());
        when(activityQuery.list()).thenReturn(List.of());
        NullPointerException generatorFailure = new NullPointerException("missing shape");
        when(diagramGenerator.generateDiagram(any(), eq("png"), anyList(), anyList(),
                anyString(), anyString(), anyString(), any(ClassLoader.class),
                anyDouble(), anyBoolean())).thenThrow(generatorFailure);

        assertThatThrownBy(() -> readService.generateDiagram(INSTANCE_ID))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR);
                    assertThat(exception.getMessage()).isEqualTo("流程图生成失败");
                    assertThat(exception.getCause()).isSameAs(generatorFailure);
                });
    }

    /**
     * 创建任务对象授权快照。
     *
     * @return WorkflowTaskAccessSnapshot，当前活动任务的稳定快照
     */
    private WorkflowTaskAccessSnapshot taskSnapshot()
    {
        return new WorkflowTaskAccessSnapshot(TASK_ID, INSTANCE_ID, DEFINITION_ID,
                "review", "审核", "7", null, null, true, Instant.now(), null,
                null, null);
    }

    /**
     * 创建流程实例对象授权快照。
     *
     * @return WorkflowProcessAccessSnapshot，活动实例的稳定快照
     */
    private WorkflowProcessAccessSnapshot processSnapshot()
    {
        return new WorkflowProcessAccessSnapshot(INSTANCE_ID, DEFINITION_ID,
                "deployment-1", "business-1", "7", Instant.now(), null, null,
                null, "RUNNING");
    }

    /**
     * 创建包含最小 BPMN DI 节点坐标的流程图测试模型。
     *
     * @return BpmnModel，可进入受控图形生成器调用的模型
     */
    private BpmnModel diagramModel()
    {
        BpmnModel model = new BpmnModel();
        model.addGraphicInfo("review", new GraphicInfo(10, 10, 80, 40));
        return model;
    }

    /**
     * 创建已经过详情服务安全投影的表单快照视图。
     *
     * @param taskId String，表单所属任务主键
     * @param values Map&lt;String, JsonNode&gt;，安全字段值
     * @return WorkflowProcessFormSnapshotView，不可变表单快照视图
     */
    private WorkflowProcessFormSnapshotView form(String taskId, Map<String, JsonNode> values)
    {
        return new WorkflowProcessFormSnapshotView("review", taskId, 20L,
                "key_20", "review", "审核表单", "审核", "{\"fields\":[]}",
                false, values, Instant.now());
    }

    /**
     * 创建变量测试所需的完整流程详情视图。
     *
     * @param task WorkflowTaskAccessSnapshot，当前任务快照
     * @param historicForm WorkflowProcessFormSnapshotView，历史表单和值
     * @param currentForm WorkflowProcessFormSnapshotView，当前表单和值
     * @return WorkflowProcessDetailView，字段集合满足详情记录约束的视图
     */
    private WorkflowProcessDetailView detail(WorkflowTaskAccessSnapshot task,
            WorkflowProcessFormSnapshotView historicForm,
            WorkflowProcessFormSnapshotView currentForm)
    {
        WorkflowProcessViewerView viewer = new WorkflowProcessViewerView(
                Set.of(), Set.of(), Set.of("review"), Set.of(), Set.of());
        return new WorkflowProcessDetailView(INSTANCE_ID, DEFINITION_ID, "approval",
                "审批", 1, "default", "deployment-1", "business-1", "7", "用户",
                Instant.now(), null, null, "running", task, currentForm,
                List.of(historicForm), List.of(), "<definitions/>", viewer);
    }

    /**
     * 创建历史活动替身。
     *
     * @param activityId String，BPMN 活动或顺序流 key
     * @param activityType String，Flowable 历史活动类型
     * @return HistoricActivityInstance，具有流程图高亮所需字段的替身
     */
    private HistoricActivityInstance activity(String activityId, String activityType)
    {
        HistoricActivityInstance activity = mock(HistoricActivityInstance.class);
        when(activity.getActivityId()).thenReturn(activityId);
        when(activity.getActivityType()).thenReturn(activityType);
        return activity;
    }

    /**
     * 创建包含标准签名的最小 PNG 测试字节。
     *
     * @return byte[]，足以通过服务媒体签名门禁的字节
     */
    private byte[] validPng()
    {
        return new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47,
                0x0D, 0x0A, 0x1A, 0x0A, 0x00};
    }
}
