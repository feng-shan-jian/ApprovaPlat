package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessSnapshot;
import com.ruoyi.flowable.authorization.WorkflowTaskAccessSnapshot;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDetailQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowControlledLoopStateView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessDetailView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessFormSnapshotView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.mapper.WorkflowHistoricVariableMapper;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.model.WorkflowDeploymentService;
import com.ruoyi.flowable.service.task.WorkflowControlledLoopService;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceService;
import com.ruoyi.flowable.service.task.WorkflowTaskLifecycleService;
import com.ruoyi.system.service.ISysUserService;

/**
 * 使用真实 Flowable 8、H2 历史表和正式 MyBatis Mapper 验证详情变量按需读取边界。
 */
class WorkflowProcessDetailServiceIntegrationTest
{
    private ProcessEngine processEngine;
    private RepositoryService repositoryService;
    private RuntimeService runtimeService;
    private TaskService taskService;
    private HistoryService historyService;
    private SqlSession mapperSession;
    private WorkflowHistoricVariableMapper historicVariableMapper;
    private WorkflowProcessAccessService processAccessService;
    private WorkflowControlledLoopService controlledLoopService;
    private WorkflowProcessDetailService service;
    private String deploymentId;

    /**
     * 创建 FULL 历史级别的真实内存引擎，部署测试流程并装配正式详情服务。
     *
     * @return void，无返回值；每个测试使用独立数据库和 Mapper 会话
     */
    @BeforeEach
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void setUp()
    {
        processEngine = ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration()
                .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
                .setHistory("full")
                .buildProcessEngine();
        repositoryService = processEngine.getRepositoryService();
        runtimeService = processEngine.getRuntimeService();
        taskService = processEngine.getTaskService();
        historyService = spy(processEngine.getHistoryService());

        Deployment deployment = repositoryService.createDeployment()
                .addString("detail-variable-loading.bpmn20.xml", BPMN)
                .deploy();
        deploymentId = deployment.getId();
        historicVariableMapper = spy(createHistoricVariableMapper(
                processEngine.getProcessEngineConfiguration().getDataSource()));

        WorkflowEngineOperations engineOperations = mock(WorkflowEngineOperations.class);
        when(engineOperations.read(any(Supplier.class))).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        processAccessService = mock(WorkflowProcessAccessService.class);
        WorkflowDeploymentArtifactRepository artifactRepository =
                mock(WorkflowDeploymentArtifactRepository.class);
        when(artifactRepository.selectForms(deploymentId)).thenReturn(formSnapshots());
        WorkflowDeploymentService deploymentService = mock(WorkflowDeploymentService.class);
        when(deploymentService.getBpmnXml(anyString())).thenReturn(BPMN);
        WorkflowMultiInstanceService multiInstanceService = mock(WorkflowMultiInstanceService.class);
        WorkflowTaskLifecycleService taskLifecycleService = mock(WorkflowTaskLifecycleService.class);
        when(taskLifecycleService.isTaskReturnAllowed(anyString())).thenReturn(false);
        controlledLoopService = mock(WorkflowControlledLoopService.class);
        when(controlledLoopService.buildStates(anyString(), anyString(), anyString(), any()))
                .thenReturn(List.of());

        service = new WorkflowProcessDetailService(engineOperations, processAccessService,
                repositoryService, historyService, taskService, deploymentService,
                artifactRepository, historicVariableMapper, new WorkflowFormTemplateValidator(),
                mock(ISysUserService.class), multiInstanceService, taskLifecycleService,
                controlledLoopService);
    }

    /**
     * 关闭 Mapper 会话和真实 Flowable 引擎，避免测试间共享数据库或连接。
     *
     * @return void，无返回值
     */
    @AfterEach
    void tearDown()
    {
        if (mapperSession != null)
        {
            mapperSession.close();
        }
        if (processEngine != null)
        {
            processEngine.close();
        }
    }

    /**
     * 验证活动非局部表单只读取流程根变量，任务同名局部值不会覆盖返回结果。
     *
     * @return void，作用域、值投影或当前查询次数不符时测试失败
     */
    @Test
    void readsOnlyRootVariablesForActiveGlobalForm()
    {
        ProcessInstance instance = startMainProcess();
        Task task = activeTask(instance.getId(), "globalTask");
        runtimeService.setVariable(instance.getId(), "value", "root-current");
        taskService.setVariableLocal(task.getId(), "value", "wrong-task-local");

        WorkflowProcessDetailView detail = readDetail(instance.getId(), task.getId(), "running");

        assertThat(detail.currentTaskForm().taskLocal()).isFalse();
        assertThat(detail.currentTaskForm().values().get("value").textValue())
                .isEqualTo("root-current");
        verify(historyService).createHistoricVariableInstanceQuery();
        verify(historicVariableMapper).selectCurrentVariableMetadata(
                eq(instance.getId()), eq(task.getId()), eq(false), eq(List.of("value")), eq(2));
    }

    /**
     * 验证活动 task-local 表单只读取当前 taskId，流程根值和已完成任务同名局部值均被隔离。
     *
     * @return void，任务作用域或当前值投影不符时测试失败
     */
    @Test
    void readsOnlyCurrentTaskVariablesForActiveLocalForm()
    {
        ProcessInstance instance = startMainProcess();
        Task completed = activeTask(instance.getId(), "globalTask");
        taskService.setVariableLocal(completed.getId(), "value", "wrong-completed-local");
        completeWithSnapshot(completed, 2L, "global-form", "globalTask", false,
                Map.of("value", "global-submitted"));
        Task active = activeTask(instance.getId(), "localTask");
        runtimeService.setVariable(instance.getId(), "value", "wrong-root");
        taskService.setVariableLocal(active.getId(), "value", "current-local");

        WorkflowProcessDetailView detail = readDetail(instance.getId(), active.getId(), "running");

        assertThat(detail.currentTaskForm().taskLocal()).isTrue();
        assertThat(detail.currentTaskForm().values().get("value").textValue())
                .isEqualTo("current-local");
        verify(historyService).createHistoricVariableInstanceQuery();
        verify(historicVariableMapper).selectCurrentVariableMetadata(
                eq(instance.getId()), eq(active.getId()), eq(true), eq(List.of("value")), eq(2));
    }

    /**
     * 验证已完成任务只投影完成时正式提交快照，不查询后来变化的流程当前变量。
     *
     * @return void，历史值被当前值污染或发生当前变量查询时测试失败
     */
    @Test
    void completedTaskUsesSubmissionSnapshotWithoutCurrentVariableQuery()
    {
        ProcessInstance instance = startMainProcess();
        Task completed = activeTask(instance.getId(), "globalTask");
        runtimeService.setVariable(instance.getId(), "value", "submitted-value");
        completeWithSnapshot(completed, 2L, "global-form", "globalTask", false,
                Map.of("value", "submitted-value"));
        runtimeService.setVariable(instance.getId(), "value", "later-current-value");

        WorkflowProcessDetailView detail = readDetail(
                instance.getId(), completed.getId(), "running");

        assertThat(detail.currentTaskForm().snapshotTime()).isNotNull();
        assertThat(detail.currentTaskForm().values().get("value").textValue())
                .isEqualTo("submitted-value");
        verifyNoCurrentVariableRead();
    }

    /**
     * 验证退回修改继续继承开始提交快照，且不会为随后活动任务加载当前变量。
     *
     * @return void，退回表单来源或查询边界改变时测试失败
     */
    @Test
    void returnedStartFormKeepsSubmissionSnapshotWithoutCurrentVariableQuery()
    {
        ProcessInstance instance = startMainProcess();
        Task active = activeTask(instance.getId(), "globalTask");
        runtimeService.setVariable(instance.getId(), "startValue", "wrong-current-start");

        WorkflowProcessDetailView detail = readDetail(
                instance.getId(), active.getId(), "returned");

        assertThat(detail.currentTaskForm().nodeKey()).isEqualTo("mainStart");
        assertThat(detail.currentTaskForm().taskId()).isEqualTo(active.getId());
        assertThat(detail.currentTaskForm().snapshotTime()).isNull();
        assertThat(detail.currentTaskForm().values().get("startValue").textValue())
                .isEqualTo("start-submitted");
        verifyNoCurrentVariableRead();
    }

    /**
     * 验证受控循环新一轮仍从上一轮正式 task-local 提交继承缺失字段，当前查询只绑定新任务。
     *
     * @return void，循环继承或当前任务隔离行为改变时测试失败
     */
    @Test
    void controlledLoopKeepsPreviousSubmissionInheritance()
    {
        ProcessInstance instance = startLoopProcess();
        Task previous = activeTask(instance.getId(), "loopTask");
        runtimeService.setVariable(instance.getId(), "repeat", true);
        completeWithSnapshot(previous, 5L, "loop-form", "loopTask", true,
                Map.of("decision", "redo"));
        Task active = activeTask(instance.getId(), "loopTask");
        when(controlledLoopService.buildStates(
                deploymentId, "detailLoop", instance.getId(), "loopTask"))
                .thenReturn(List.of(new WorkflowControlledLoopStateView(
                        "loopTask", "循环审批", "decision", "redo", "approved",
                        3, 1, 2, true, List.of())));

        WorkflowProcessDetailView detail = readDetail(instance.getId(), active.getId(), "running");

        assertThat(detail.currentTaskForm().taskLocal()).isTrue();
        assertThat(detail.currentTaskForm().values().get("decision").textValue())
                .isEqualTo("redo");
        verify(historyService).createHistoricVariableInstanceQuery();
        verify(historicVariableMapper, never()).selectCurrentVariableMetadata(
                anyString(), anyString(), eq(true), anyList(), anyInt());
    }

    /**
     * 验证无任务详情和活动无表单详情均不会创建当前历史变量查询。
     *
     * @return void，任一无效入口触发当前变量读取时测试失败
     */
    @Test
    void noTaskAndNoFormDetailsDoNotQueryCurrentVariables()
    {
        ProcessInstance withoutTaskRequest = startMainProcess();
        readDetail(withoutTaskRequest.getId(), null, "running");
        verifyNoCurrentVariableRead();

        ProcessInstance withoutForm = startMainProcess();
        Task global = activeTask(withoutForm.getId(), "globalTask");
        completeWithSnapshot(global, 2L, "global-form", "globalTask", false,
                Map.of("value", "global-submitted"));
        Task local = activeTask(withoutForm.getId(), "localTask");
        completeWithSnapshot(local, 3L, "local-form", "localTask", true,
                Map.of("value", "local-submitted"));
        Task noForm = activeTask(withoutForm.getId(), "noFormTask");
        clearReadInvocations();

        WorkflowProcessDetailView detail = readDetailWithoutClearing(
                withoutForm.getId(), noForm.getId(), "running");

        assertThat(detail.currentTaskForm()).isNull();
        verifyNoCurrentVariableRead();
    }

    /**
     * 使用 Flowable 数据源和正式 XML 映射创建独立 MyBatis Mapper。
     *
     * @param dataSource DataSource，当前真实内存 Flowable 引擎的数据源
     * @return WorkflowHistoricVariableMapper，直接读取 ACT_HI_DETAIL/ACT_HI_VARINST 的 Mapper
     */
    private WorkflowHistoricVariableMapper createHistoricVariableMapper(DataSource dataSource)
    {
        Environment environment = new Environment("flowable-detail-it",
                new JdbcTransactionFactory(), dataSource);
        org.apache.ibatis.session.Configuration configuration =
                new org.apache.ibatis.session.Configuration(environment);
        configuration.addMapper(WorkflowHistoricVariableMapper.class);
        String resource = "mapper/flowable/WorkflowHistoricVariableMapper.xml";
        try (InputStream input = WorkflowProcessDetailServiceIntegrationTest.class
                .getClassLoader().getResourceAsStream(resource))
        {
            if (input == null)
            {
                throw new IllegalStateException("测试无法加载正式历史变量 Mapper");
            }
            new XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }
        catch (IOException | RuntimeException exception)
        {
            throw new IllegalStateException("测试读取或关闭正式历史变量 Mapper 失败", exception);
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        mapperSession = factory.openSession(true);
        return mapperSession.getMapper(WorkflowHistoricVariableMapper.class);
    }

    /**
     * 启动主流程，并把开始表单正式提交快照作为真实 Flowable 变量原子写入。
     *
     * @return ProcessInstance，停留在 globalTask 的活动实例
     */
    private ProcessInstance startMainProcess()
    {
        String snapshot = WorkflowFormSubmissionSnapshotCodec.encodeStart(
                deploymentId, 1L, "start-form", "mainStart",
                Map.of("startValue", "start-submitted"));
        return runtimeService.startProcessInstanceByKey("detailMain", Map.of(
                "startValue", "start-current",
                WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME, snapshot));
    }

    /**
     * 启动受控循环流程，并写入该流程自己的开始提交快照。
     *
     * @return ProcessInstance，停留在第一轮 loopTask 的活动实例
     */
    private ProcessInstance startLoopProcess()
    {
        String snapshot = WorkflowFormSubmissionSnapshotCodec.encodeStart(
                deploymentId, 4L, "loop-start-form", "loopStart",
                Map.of("startValue", "loop-start-submitted"));
        return runtimeService.startProcessInstanceByKey("detailLoop", Map.of(
                WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME, snapshot));
    }

    /**
     * 查询流程当前唯一指定节点任务。
     *
     * @param processInstanceId String，真实流程实例主键
     * @param taskDefinitionKey String，期望的 BPMN 用户任务节点主键
     * @return Task，唯一活动任务
     */
    private Task activeTask(String processInstanceId, String taskDefinitionKey)
    {
        Task task = taskService.createTaskQuery().processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey).active().singleResult();
        assertThat(task).as("活动任务 " + taskDefinitionKey).isNotNull();
        return task;
    }

    /**
     * 将业务字段提交快照写为真实 task-local 历史更新后完成任务。
     *
     * @param task Task，待完成的真实活动任务
     * @param formId Long，部署表单来源主键
     * @param formKey String，部署表单键
     * @param nodeKey String，BPMN 用户任务节点主键
     * @param taskLocal boolean，业务字段是否为 task-local 作用域
     * @param values Map&lt;String,Object&gt;，完成时的正式安全字段值
     * @return void，无返回值
     */
    private void completeWithSnapshot(Task task, Long formId, String formKey,
            String nodeKey, boolean taskLocal, Map<String, Object> values)
    {
        String snapshot = WorkflowFormSubmissionSnapshotCodec.encodeTask(
                deploymentId, formId, formKey, nodeKey, task.getId(), taskLocal, values);
        taskService.setVariableLocal(task.getId(),
                WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME, snapshot);
        taskService.complete(task.getId());
    }

    /**
     * 清空准备阶段调用记录后，配置对象授权并读取流程详情。
     *
     * @param processInstanceId String，真实流程实例主键
     * @param taskId String，可选的真实任务主键
     * @param businessStatus String，本次授权快照使用的业务状态
     * @return WorkflowProcessDetailView，正式服务返回的详情
     */
    private WorkflowProcessDetailView readDetail(String processInstanceId, String taskId,
            String businessStatus)
    {
        clearReadInvocations();
        return readDetailWithoutClearing(processInstanceId, taskId, businessStatus);
    }

    /**
     * 配置真实实例与任务对应的授权快照并调用详情服务，不改动已有调用计数。
     *
     * @param processInstanceId String，真实流程实例主键
     * @param taskId String，可选的真实任务主键
     * @param businessStatus String，本次授权快照使用的业务状态
     * @return WorkflowProcessDetailView，正式服务返回的详情
     */
    private WorkflowProcessDetailView readDetailWithoutClearing(String processInstanceId,
            String taskId, String businessStatus)
    {
        HistoricProcessInstance instance = processEngine.getHistoryService()
                .createHistoricProcessInstanceQuery().processInstanceId(processInstanceId)
                .singleResult();
        ProcessDefinition definition = repositoryService.getProcessDefinition(
                instance.getProcessDefinitionId());
        when(processAccessService.requireReadableInstance(processInstanceId)).thenReturn(
                new WorkflowProcessAccessSnapshot(processInstanceId, definition.getId(),
                        definition.getDeploymentId(), instance.getBusinessKey(),
                        instance.getStartUserId(), instance.getStartTime().toInstant(),
                        instance.getEndTime() == null ? null : instance.getEndTime().toInstant(),
                        instance.getDeleteReason(), businessStatus,
                        instance.getEndTime() == null ? "running" : "completed"));
        if (taskId != null)
        {
            HistoricTaskInstance historicTask = processEngine.getHistoryService()
                    .createHistoricTaskInstanceQuery().taskId(taskId).singleResult();
            Task activeTask = taskService.createTaskQuery().taskId(taskId).singleResult();
            when(processAccessService.requireReadableTask(taskId)).thenReturn(
                    new WorkflowTaskAccessSnapshot(taskId, processInstanceId,
                            definition.getId(), historicTask.getTaskDefinitionKey(),
                            historicTask.getName(), historicTask.getAssignee(),
                            historicTask.getOwner(), null, activeTask != null,
                            historicTask.getCreateTime().toInstant(),
                            historicTask.getEndTime() == null ? null
                                    : historicTask.getEndTime().toInstant(),
                            null, null));
        }
        return service.getDetail(new WorkflowProcessDetailQueryDto(processInstanceId, taskId));
    }

    /**
     * 清空当前变量读取相关 spy 记录，排除测试准备数据查询的影响。
     *
     * @return void，无返回值
     */
    private void clearReadInvocations()
    {
        clearInvocations(historyService, historicVariableMapper);
    }

    /**
     * 断言详情阶段完全没有当前变量查询；正式提交快照 Mapper 调用不在此禁用范围内。
     *
     * @return void，创建历史变量查询或调用当前变量 SQL 时测试失败
     */
    private void verifyNoCurrentVariableRead()
    {
        verify(historyService, never()).createHistoricVariableInstanceQuery();
        verify(historicVariableMapper, never()).selectCurrentVariableMetadata(
                anyString(), anyString(), anyBoolean(), anyList(), anyInt());
        verify(historicVariableMapper, never()).selectCurrentVariableBodies(
                anyString(), anyString(), anyBoolean(), anyList(), anyList());
    }

    /**
     * 创建与 BPMN 节点、表单键及测试部署严格绑定的不可变表单快照。
     *
     * @param formId Long，模板表单主键
     * @param formKey String，BPMN 表单键
     * @param nodeKey String，BPMN 节点主键
     * @param fieldName String，唯一可读业务字段名
     * @return WfDeployForm，供详情服务执行正式 schema 白名单投影
     */
    private WfDeployForm formSnapshot(Long formId, String formKey, String nodeKey,
            String fieldName)
    {
        WfDeployForm snapshot = new WfDeployForm();
        snapshot.setDeployId(deploymentId);
        snapshot.setSourceType("TEMPLATE");
        snapshot.setFormId(formId);
        snapshot.setFormKey(formKey);
        snapshot.setNodeKey(nodeKey);
        snapshot.setFormName(nodeKey + "表单");
        snapshot.setNodeName(nodeKey);
        snapshot.setContent("{\"fields\":[{\"__vModel__\":\"" + fieldName
                + "\",\"__config__\":{\"layout\":\"colFormItem\","
                + "\"tag\":\"el-input\"}}]}");
        snapshot.setCreateTime(Date.from(Instant.parse("2026-08-21T00:00:00Z")));
        return snapshot;
    }

    /**
     * 返回两个真实测试流程需要的全部部署表单快照。
     *
     * @return List&lt;WfDeployForm&gt;，节点和表单键联合唯一的正式快照集合
     */
    private List<WfDeployForm> formSnapshots()
    {
        return List.of(
                formSnapshot(1L, "start-form", "mainStart", "startValue"),
                formSnapshot(2L, "global-form", "globalTask", "value"),
                formSnapshot(3L, "local-form", "localTask", "value"),
                formSnapshot(4L, "loop-start-form", "loopStart", "startValue"),
                formSnapshot(5L, "loop-form", "loopTask", "decision"));
    }

    /** 真实 Flowable 测试流程：主链覆盖全局、task-local、无表单，循环链覆盖同节点新一轮继承。 */
    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
              xmlns:flowable="http://flowable.org/bpmn" targetNamespace="detail-variable-it">
              <process id="detailMain" name="详情变量主流程" isExecutable="true">
                <startEvent id="mainStart" flowable:formKey="start-form"/>
                <userTask id="globalTask" name="全局表单" flowable:formKey="global-form"
                  flowable:assignee="approver"/>
                <userTask id="localTask" name="局部表单" flowable:formKey="local-form"
                  flowable:localScope="true" flowable:assignee="approver"/>
                <userTask id="noFormTask" name="无表单" flowable:assignee="approver"/>
                <endEvent id="mainEnd"/>
                <sequenceFlow id="mainFlow1" sourceRef="mainStart" targetRef="globalTask"/>
                <sequenceFlow id="mainFlow2" sourceRef="globalTask" targetRef="localTask"/>
                <sequenceFlow id="mainFlow3" sourceRef="localTask" targetRef="noFormTask"/>
                <sequenceFlow id="mainFlow4" sourceRef="noFormTask" targetRef="mainEnd"/>
              </process>
              <process id="detailLoop" name="详情变量循环流程" isExecutable="true">
                <startEvent id="loopStart" flowable:formKey="loop-start-form"/>
                <userTask id="loopTask" name="循环审批" flowable:formKey="loop-form"
                  flowable:localScope="true" flowable:assignee="approver"/>
                <exclusiveGateway id="loopGateway" default="loopExit"/>
                <endEvent id="loopEnd"/>
                <sequenceFlow id="loopEnter" sourceRef="loopStart" targetRef="loopTask"/>
                <sequenceFlow id="loopDecision" sourceRef="loopTask" targetRef="loopGateway"/>
                <sequenceFlow id="loopRepeat" sourceRef="loopGateway" targetRef="loopTask">
                  <conditionExpression xsi:type="tFormalExpression"><![CDATA[${repeat == true}]]></conditionExpression>
                </sequenceFlow>
                <sequenceFlow id="loopExit" sourceRef="loopGateway" targetRef="loopEnd"/>
              </process>
            </definitions>
            """;
}
