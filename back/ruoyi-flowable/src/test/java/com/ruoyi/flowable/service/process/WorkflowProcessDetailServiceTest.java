package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricActivityInstanceQuery;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.task.Comment;
import org.flowable.identitylink.api.IdentityLinkInfo;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.flowable.variable.api.history.HistoricVariableInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.node.DoubleNode;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessSnapshot;
import com.ruoyi.flowable.authorization.WorkflowTaskAccessSnapshot;
import com.ruoyi.flowable.domain.WorkflowCurrentVariableMetadataRow;
import com.ruoyi.flowable.domain.WorkflowHistoricSubmissionRow;
import com.ruoyi.flowable.domain.WorkflowHistoricVariableBodyRow;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDetailQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowProcessActivityView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessDetailView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessFormSnapshotView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.mapper.WfDeployFormMapper;
import com.ruoyi.flowable.mapper.WorkflowHistoricVariableMapper;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.model.WorkflowDeploymentService;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceModelContract;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceService;
import com.ruoyi.flowable.service.task.WorkflowTaskLifecycleService;
import com.ruoyi.system.service.ISysUserService;

/**
 * WorkflowProcessDetailService 的完整详情和安全门禁单元测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowProcessDetailServiceTest
{
    @Mock
    private WorkflowEngineOperations engineOperations;

    @Mock
    private WorkflowProcessAccessService processAccessService;

    @Mock
    private RepositoryService repositoryService;

    @Mock
    private HistoryService historyService;

    @Mock
    private TaskService taskService;

    @Mock
    private WorkflowDeploymentService deploymentService;

    @Mock
    private WfDeployFormMapper deployFormMapper;

    @Mock
    private WorkflowHistoricVariableMapper historicVariableMapper;

    @Mock
    private WorkflowMultiInstanceService multiInstanceService;

    @Mock
    private WorkflowTaskLifecycleService taskLifecycleService;

    @Mock
    private ISysUserService userService;

    @Mock
    private HistoricActivityInstanceQuery activityQuery;

    @Mock
    private HistoricTaskInstanceQuery historicTaskQuery;

    @Mock
    private HistoricVariableInstanceQuery processVariableQuery;

    @Mock
    private HistoricVariableInstanceQuery taskVariableQuery;

    private WorkflowProcessDetailService service;

    /** 测试快照元数据主键到第二阶段正文的映射。 */
    private final Map<String, WorkflowHistoricVariableBodyRow> submissionBodies =
            new LinkedHashMap<>();

    /**
     * 为每个场景建立可执行 Supplier 的只读引擎边界。
     * @return 无返回值，每个测试获得独立 Mockito 状态
     */
    @BeforeEach
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void setUp()
    {
        submissionBodies.clear();
        when(engineOperations.read(any(Supplier.class))).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        service = new WorkflowProcessDetailService(engineOperations, processAccessService,
                repositoryService, historyService, taskService, deploymentService,
                deployFormMapper, historicVariableMapper,
                new WorkflowFormTemplateValidator(), userService, multiInstanceService,
                taskLifecycleService);
    }

    /**
     * 验证完整详情只回显 schema 字段，使用 completedBy 并构建意见、候选与 Viewer 状态。
     * @return 无返回值，断言表单、变量、时间线、BPMN 和对象关系完整闭环
     */
    @Test
    void buildsAuthorizedDetailWithSafeFormValuesAndActualCompleter()
    {
        stubAuthorizedObjects("instance-1", "task-1");
        ProcessDefinition definition = stubDefinitionAndModel();
        when(repositoryService.getProcessDefinition("definition-1")).thenReturn(definition);
        when(deployFormMapper.selectByDeploymentId("deployment-1"))
                .thenReturn(List.of(startSnapshot(), taskSnapshot()));

        HistoricActivityInstance start = activity("start-activity", "start", "开始",
                "startEvent", null, "2026-07-25T08:00:00Z", "2026-07-25T08:00:01Z");
        HistoricActivityInstance approve = activity("approve-activity", "approve", "审批",
                "userTask", "task-1", "2026-07-25T08:01:00Z", "2026-07-25T09:00:00Z");
        HistoricActivityInstance flow = activity("flow-activity", "flow-1", null,
                "sequenceFlow", null, "2026-07-25T08:00:01Z", "2026-07-25T08:00:02Z");
        HistoricActivityInstance end = activity("end-activity", "end", "结束",
                "endEvent", null, "2026-07-25T09:00:00Z", "2026-07-25T09:00:01Z");
        stubActivityQuery(List.of(start, flow, approve, end));

        HistoricTaskInstance historicTask = mock(HistoricTaskInstance.class);
        when(historicTask.getId()).thenReturn("task-1");
        when(historicTask.getProcessInstanceId()).thenReturn("instance-1");
        when(historicTask.getProcessDefinitionId()).thenReturn("definition-1");
        when(historicTask.getTaskDefinitionKey()).thenReturn("approve");
        when(historicTask.getAssignee()).thenReturn("8");
        when(historicTask.getCompletedBy()).thenReturn("7");
        when(historicTask.getEndTime())
                .thenReturn(Date.from(Instant.parse("2026-07-25T09:00:00Z")));
        IdentityLinkInfo candidate = mock(IdentityLinkInfo.class);
        when(candidate.getType()).thenReturn("candidate");
        when(candidate.getGroupId()).thenReturn("ROLE2");
        doReturn(List.of(candidate)).when(historicTask).getIdentityLinks();
        stubHistoricTaskQuery(List.of(historicTask));

        HistoricVariableInstance applicant = variable("applicant", "string", "张三", null);
        HistoricVariableInstance internalStatus = variable(
                "processStatus", "string", "running", null);
        HistoricVariableInstance secret = variable("secret", "string", "不可回显", null);
        HistoricVariableInstance binary = variable("attachment", "bytes", null, null);
        HistoricVariableInstance notANumber = variable(
                "notANumber", "json", DoubleNode.valueOf(Double.NaN), null);
        HistoricVariableInstance infinity = variable(
                "infinity", "json", DoubleNode.valueOf(Double.POSITIVE_INFINITY), null);
        HistoricVariableInstance decision = variable(
                "decision", "json", Map.of("approved", true), "task-1");
        HistoricVariableInstance unsafeLocal = variable(
                "unsafe", "serializable", null, "task-1");
        stubVariableQueries(List.of(applicant, internalStatus, secret, binary,
                        notANumber, infinity),
                List.of(decision, unsafeLocal));
        WorkflowHistoricSubmissionRow originalStartSubmission = submissionUpdate(
                "detail-start-original", "2026-07-25T08:00:00Z", null, "start-activity",
                WorkflowFormSubmissionSnapshotCodec.encodeStart("deployment-1", 1L,
                        "key_1", "start", Map.of("applicant", "李四")));
        WorkflowHistoricSubmissionRow startSubmission = submissionUpdate("detail-start",
                "2026-07-25T08:00:01Z", null, "start-activity",
                WorkflowFormSubmissionSnapshotCodec.encodeStart("deployment-1", 1L,
                        "key_1", "start", Map.of("applicant", "张三")));
        WorkflowHistoricSubmissionRow taskSubmission = stringBlobSubmissionUpdate("detail-task-1",
                "2026-07-25T09:00:00Z", "task-1", "approve-activity",
                WorkflowFormSubmissionSnapshotCodec.encodeTask("deployment-1", 2L,
                        "key_2", "approve", "task-1", true,
                        Map.of("decision", Map.of("approved", true))));
        stubSubmissionUpdates(List.of(originalStartSubmission, startSubmission, taskSubmission));

        Comment comment = mock(Comment.class);
        when(comment.getId()).thenReturn("comment-1");
        when(comment.getProcessInstanceId()).thenReturn("instance-1");
        when(comment.getTaskId()).thenReturn("task-1");
        when(comment.getType()).thenReturn("2");
        when(comment.getFullMessage()).thenReturn("材料不足，退回修改");
        when(comment.getUserId()).thenReturn("7");
        when(comment.getTime()).thenReturn(Date.from(Instant.parse("2026-07-25T09:00:00Z")));
        when(taskService.getProcessInstanceComments("instance-1")).thenReturn(List.of(comment));
        when(deploymentService.getBpmnXml("definition-1")).thenReturn("<definitions/>");
        when(userService.selectUserById(anyLong())).thenAnswer(invocation ->
        {
            long userId = invocation.getArgument(0);
            SysUser user = new SysUser();
            user.setUserId(userId);
            user.setNickName("用户" + userId);
            return user;
        });

        WorkflowProcessDetailView detail = service.getDetail(
                new WorkflowProcessDetailQueryDto("instance-1", "task-1"));

        assertThat(detail.processInstanceId()).isEqualTo("instance-1");
        assertThat(detail.startUserName()).isEqualTo("用户9");
        assertThat(detail.processStatus()).isEqualTo("completed");
        assertThat(detail.bpmnXml()).isEqualTo("<definitions/>");
        assertThat(detail.isExistTaskForm()).isTrue();
        assertThat(detail.processFormList()).hasSize(2);
        WorkflowProcessFormSnapshotView startForm = detail.processFormList().get(0);
        assertThat(startForm.values()).containsOnlyKeys("applicant");
        // 重新提交保留底层审计版本，但用户详情必须只投影最后一次覆盖后的原表单。
        assertThat(startForm.values().get("applicant").textValue()).isEqualTo("张三");
        assertThat(startForm.snapshotTime())
                .isEqualTo(Instant.parse("2026-07-25T08:00:01Z"));
        WorkflowProcessFormSnapshotView taskForm = detail.currentTaskForm();
        assertThat(taskForm.taskLocal()).isTrue();
        assertThat(taskForm.values()).containsOnlyKeys("decision");
        assertThat(taskForm.values().get("decision").get("approved").booleanValue()).isTrue();

        WorkflowProcessActivityView taskActivity = detail.historyProcNodeList().stream()
                .filter(item -> "task-1".equals(item.taskId()))
                .findFirst().orElseThrow();
        assertThat(taskActivity.assigneeId()).isEqualTo("8");
        assertThat(taskActivity.completedById()).isEqualTo("7");
        assertThat(taskActivity.completedByName()).isEqualTo("用户7");
        assertThat(taskActivity.candidates()).singleElement()
                .extracting(candidateView -> candidateView.identityId())
                .isEqualTo("ROLE2");
        assertThat(taskActivity.comments()).singleElement()
                .extracting(commentView -> commentView.typeName())
                .isEqualTo("退回");
        assertThat(detail.flowViewer().finishedSequenceFlowIds()).contains("flow-1");
        assertThat(detail.flowViewer().returnedActivityIds()).contains("approve");
        verify(internalStatus, never()).getValue();
        verify(secret, never()).getValue();
        verify(binary, never()).getValue();
        verify(unsafeLocal, never()).getValue();
        verify(processVariableQuery).excludeVariableInitialization();
        verify(taskVariableQuery).excludeVariableInitialization();
        verify(historyService, never()).createHistoricDetailQuery();
    }

    /**
     * 验证两个任务在同一毫秒提交同名全局字段时，各自只读取与 taskId 绑定的不可变快照。
     *
     * @return 无返回值，后序最终变量不得污染前序历史表单
     */
    @Test
    void keepsSameMillisecondGlobalSubmissionsIsolatedFromLaterValue()
    {
        when(processAccessService.requireReadableInstance("instance-1"))
                .thenReturn(processSnapshot("instance-1"));
        ProcessDefinition definition = stubDefinitionAndModel(List.of(
                new TestTaskSpec("firstReview", "初审", "key_first", false),
                new TestTaskSpec("secondReview", "复审", "key_second", false)));
        when(repositoryService.getProcessDefinition("definition-1")).thenReturn(definition);
        when(deployFormMapper.selectByDeploymentId("deployment-1")).thenReturn(List.of(
                valueSnapshot(11L, "key_first", "firstReview", "初审表", "sharedValue"),
                valueSnapshot(12L, "key_second", "secondReview", "复审表", "sharedValue")));

        HistoricActivityInstance firstActivity = activity("activity-first", "firstReview",
                "初审", "userTask", "task-first", "2026-07-25T08:10:00Z",
                "2026-07-25T09:00:00Z");
        HistoricActivityInstance secondActivity = activity("activity-second", "secondReview",
                "复审", "userTask", "task-second", "2026-07-25T09:00:00Z",
                "2026-07-25T09:00:00Z");
        stubActivityQuery(List.of(firstActivity, secondActivity));
        stubHistoricTaskQuery(List.of(
                historicTask("task-first", "firstReview", "2026-07-25T09:00:00Z"),
                historicTask("task-second", "secondReview", "2026-07-25T09:00:00Z")));
        when(taskService.getProcessInstanceComments("instance-1")).thenReturn(List.of());

        HistoricVariableInstance finalShared = variable(
                "sharedValue", "string", "second-value", null);
        stubVariableQueries(List.of(finalShared), List.of());
        WorkflowHistoricSubmissionRow firstSubmission = submissionUpdate("detail-first",
                "2026-07-25T09:00:00Z", "task-first", "activity-first",
                WorkflowFormSubmissionSnapshotCodec.encodeTask("deployment-1", 11L,
                        "key_first", "firstReview", "task-first", false,
                        Map.of("sharedValue", "first-value")));
        WorkflowHistoricSubmissionRow secondSubmission = submissionUpdate("detail-second",
                "2026-07-25T09:00:00Z", "task-second", "activity-second",
                WorkflowFormSubmissionSnapshotCodec.encodeTask("deployment-1", 12L,
                        "key_second", "secondReview", "task-second", false,
                        Map.of("sharedValue", "second-value")));
        stubSubmissionUpdates(List.of(firstSubmission, secondSubmission));
        when(deploymentService.getBpmnXml("definition-1")).thenReturn("<definitions/>");

        WorkflowProcessDetailView detail = service.getDetail(
                new WorkflowProcessDetailQueryDto("instance-1", null));

        assertThat(detail.processFormList()).hasSize(2);
        assertThat(detail.processFormList().get(0).values().get("sharedValue").textValue())
                .isEqualTo("first-value");
        assertThat(detail.processFormList().get(1).values().get("sharedValue").textValue())
                .isEqualTo("second-value");
        assertThat(detail.processFormList()).extracting(WorkflowProcessFormSnapshotView::snapshotTime)
                .containsOnly(Instant.parse("2026-07-25T09:00:00Z"));
        verify(finalShared, never()).getValue();
    }

    /**
     * 验证 localScope 表单只读取对应任务内部快照，其他任务同名局部值不能串入。
     *
     * @return 无返回值，两个局部任务必须分别回显各自提交值
     */
    @Test
    void readsLocalScopeSubmissionOnlyFromMatchingTask()
    {
        when(processAccessService.requireReadableInstance("instance-1"))
                .thenReturn(processSnapshot("instance-1"));
        ProcessDefinition definition = stubDefinitionAndModel(List.of(
                new TestTaskSpec("localOne", "局部一", "key_local_1", true),
                new TestTaskSpec("localTwo", "局部二", "key_local_2", true)));
        when(repositoryService.getProcessDefinition("definition-1")).thenReturn(definition);
        when(deployFormMapper.selectByDeploymentId("deployment-1")).thenReturn(List.of(
                valueSnapshot(21L, "key_local_1", "localOne", "局部表一", "decision"),
                valueSnapshot(22L, "key_local_2", "localTwo", "局部表二", "decision")));
        stubActivityQuery(List.of(
                activity("activity-local-1", "localOne", "局部一", "userTask",
                        "task-local-1", "2026-07-25T08:10:00Z", "2026-07-25T08:20:00Z"),
                activity("activity-local-2", "localTwo", "局部二", "userTask",
                        "task-local-2", "2026-07-25T08:20:00Z", "2026-07-25T08:30:00Z")));
        stubHistoricTaskQuery(List.of(
                historicTask("task-local-1", "localOne", "2026-07-25T08:20:00Z"),
                historicTask("task-local-2", "localTwo", "2026-07-25T08:30:00Z")));
        when(taskService.getProcessInstanceComments("instance-1")).thenReturn(List.of());

        HistoricVariableInstance firstFinal = variable(
                "decision", "string", "wrong-first-final", "task-local-1");
        HistoricVariableInstance secondFinal = variable(
                "decision", "string", "wrong-second-final", "task-local-2");
        stubVariableQueries(List.of(), List.of(firstFinal, secondFinal));
        stubSubmissionUpdates(List.of(
                submissionUpdate("detail-local-1", "2026-07-25T08:20:00Z",
                        "task-local-1", "activity-local-1",
                        WorkflowFormSubmissionSnapshotCodec.encodeTask("deployment-1", 21L,
                                "key_local_1", "localOne", "task-local-1", true,
                                Map.of("decision", "local-first"))),
                submissionUpdate("detail-local-2", "2026-07-25T08:30:00Z",
                        "task-local-2", "activity-local-2",
                        WorkflowFormSubmissionSnapshotCodec.encodeTask("deployment-1", 22L,
                                "key_local_2", "localTwo", "task-local-2", true,
                                Map.of("decision", "local-second")))));
        when(deploymentService.getBpmnXml("definition-1")).thenReturn("<definitions/>");

        WorkflowProcessDetailView detail = service.getDetail(
                new WorkflowProcessDetailQueryDto("instance-1", null));

        assertThat(detail.processFormList()).hasSize(2);
        assertThat(detail.processFormList().get(0).taskLocal()).isTrue();
        assertThat(detail.processFormList().get(0).values().get("decision").textValue())
                .isEqualTo("local-first");
        assertThat(detail.processFormList().get(1).values().get("decision").textValue())
                .isEqualTo("local-second");
        verify(firstFinal, never()).getValue();
        verify(secondFinal, never()).getValue();
    }

    /**
     * 验证重新提交后的活动任务覆盖同节点历史退回标记，并继续回显当前变量与正式退回能力。
     *
     * @return 无返回值，活动任务不得伪装成已提交快照或由前端猜测退回能力
     */
    @Test
    void usesCurrentVariablesForActiveTaskWithNullSnapshotTime()
    {
        when(processAccessService.requireReadableInstance("instance-1"))
                .thenReturn(activeProcessSnapshot("instance-1"));
        when(processAccessService.requireReadableTask("task-active"))
                .thenReturn(new WorkflowTaskAccessSnapshot("task-active", "instance-1",
                        "definition-1", "approve", "审批", "8", null, null,
                        true, Instant.parse("2026-07-25T08:01:00Z"), null));
        ProcessDefinition definition = stubDefinitionAndModel();
        when(repositoryService.getProcessDefinition("definition-1")).thenReturn(definition);
        when(deployFormMapper.selectByDeploymentId("deployment-1"))
                .thenReturn(List.of(taskSnapshot()));
        stubActivityQuery(List.of(
                activity("approve-returned", "approve", "审批", "userTask", "task-returned",
                        "2026-07-25T08:01:00Z", "2026-07-25T08:30:00Z"),
                activity("approve-active", "approve", "审批", "userTask", "task-active",
                        "2026-07-25T08:31:00Z", null)));
        stubHistoricTaskQuery(List.of(
                historicTask("task-returned", "approve", "2026-07-25T08:30:00Z"),
                historicTask("task-active", "approve", null)));
        Comment returnComment = mock(Comment.class);
        when(returnComment.getId()).thenReturn("comment-return");
        when(returnComment.getProcessInstanceId()).thenReturn("instance-1");
        when(returnComment.getTaskId()).thenReturn("task-returned");
        when(returnComment.getType()).thenReturn("2");
        when(returnComment.getFullMessage()).thenReturn("材料修改后重新提交");
        when(returnComment.getTime()).thenReturn(
                Date.from(Instant.parse("2026-07-25T08:30:00Z")));
        when(taskService.getProcessInstanceComments("instance-1"))
                .thenReturn(List.of(returnComment));
        HistoricVariableInstance currentDecision = variable(
                "decision", "json", Map.of("approved", false), "task-active");
        HistoricVariableInstance unsafeCurrent = variable(
                "unsafe", "serializable", null, "task-active");
        stubVariableQueries(List.of(), List.of(currentDecision, unsafeCurrent));
        stubCurrentJsonVariable(currentDecision, "task-active", true,
                "{\"approved\":false}");
        stubSubmissionUpdates(List.of());
        when(deploymentService.getBpmnXml("definition-1")).thenReturn("<definitions/>");
        stubActiveRuntimeTasks("task-active");
        when(taskLifecycleService.isTaskReturnAllowed("task-active")).thenReturn(true);

        WorkflowProcessDetailView detail = service.getDetail(
                new WorkflowProcessDetailQueryDto("instance-1", "task-active"));

        assertThat(detail.processFormList()).isEmpty();
        assertThat(detail.currentTaskForm()).isNotNull();
        assertThat(detail.currentTaskForm().snapshotTime()).isNull();
        assertThat(detail.returnAllowed()).isTrue();
        assertThat(detail.flowViewer().unfinishedActivityIds()).contains("approve");
        assertThat(detail.flowViewer().returnedActivityIds()).doesNotContain("approve");
        assertThat(detail.currentTaskForm().values().get("decision")
                .get("approved").booleanValue()).isFalse();
        verify(currentDecision, never()).getValue();
        verify(unsafeCurrent, never()).getValue();
    }

    /**
     * 验证详情 API 从正式部署模型投影动态多实例下一办理人必填能力及 ANY 模式。
     *
     * @return 无返回值；页面能力缺失、模式错误或依赖运行时试错时测试失败
     */
    @Test
    void projectsRequiredNextUsersForControlledMultiInstanceSuccessor()
    {
        HistoricVariableInstance decision = variable(
                "decision", "json", Map.of("approved", false), "task-active");
        prepareActiveDetail(decision, "decision");
        stubCurrentJsonVariable(decision, "task-active", true,
                "{\"approved\":false}");
        TaskQuery activeTaskQuery = stubActiveRuntimeTasks("task-active");
        addRequiredAnyMultiInstanceSuccessor();

        WorkflowProcessDetailView detail = service.getDetail(
                new WorkflowProcessDetailQueryDto("instance-1", "task-active"));

        assertThat(detail.nextUserAssignmentPolicy()).isEqualTo("REQUIRED_ANY");
        assertThat(detail.nextUserSelectionRequired()).isTrue();
        assertThat(detail.nextUserSelectionMode()).isEqualTo("ANY");
        verify(activeTaskQuery).processInstanceId("instance-1");
        verify(activeTaskQuery).active();
        verify(activeTaskQuery).list();
    }

    /**
     * 验证实例存在多个活动任务时不投影模型中的动态下一办理人能力。
     *
     * @return 无返回值；并行执行树仍暴露 REQUIRED_*、required 或 mode 时测试失败
     */
    @Test
    void disablesNextUserSelectionForMultipleActiveTasks()
    {
        HistoricVariableInstance decision = variable(
                "decision", "json", Map.of("approved", false), "task-active");
        prepareActiveDetail(decision, "decision");
        stubCurrentJsonVariable(decision, "task-active", true,
                "{\"approved\":false}");
        TaskQuery activeTaskQuery = stubActiveRuntimeTasks("task-active", "task-parallel");
        addRequiredAnyMultiInstanceSuccessor();

        WorkflowProcessDetailView detail = service.getDetail(
                new WorkflowProcessDetailQueryDto("instance-1", "task-active"));

        assertThat(detail.nextUserAssignmentPolicy()).isEqualTo("DISABLED");
        assertThat(detail.nextUserSelectionRequired()).isFalse();
        assertThat(detail.nextUserSelectionMode()).isNull();
        verify(activeTaskQuery).processInstanceId("instance-1");
        verify(activeTaskQuery).active();
        verify(activeTaskQuery).list();
    }

    /**
     * 验证历史变量更新超过容量门禁时拒绝截断，且不读取任意变量正文。
     *
     * @return 无返回值，断言稳定 500 和零正文读取
     */
    @Test
    void rejectsVariableUpdateHistoryBeyondSafetyLimit()
    {
        prepareEmptyAuthorizedDetail();
        stubVariableQueries(List.of(), List.of());
        WorkflowHistoricSubmissionRow update = submissionUpdate("detail-limit",
                "2026-07-25T08:00:00Z", null, null, "{}");
        stubSubmissionUpdates(java.util.Collections.nCopies(
                WorkflowProcessDetailService.MAX_VARIABLE_UPDATE_ROWS + 1, update));

        assertThatThrownBy(() -> service.getDetail(
                new WorkflowProcessDetailQueryDto("instance-1", null)))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR));
    }

    /**
     * 验证内部快照正文损坏时明确失败，绝不回退最终流程变量冒充历史值。
     *
     * @return 无返回值，损坏快照必须返回数据异常
     */
    @Test
    void rejectsCorruptedInternalSubmissionWithoutFallback()
    {
        prepareEmptyAuthorizedDetail();
        HistoricVariableInstance finalValue = variable("sharedValue", "string", "final", null);
        stubVariableQueries(List.of(finalValue), List.of());
        WorkflowHistoricSubmissionRow corrupted = submissionUpdate("detail-corrupt",
                "2026-07-25T08:00:00Z", null, null, "{not-json");
        stubSubmissionUpdates(List.of(corrupted));

        assertThatThrownBy(() -> service.getDetail(
                new WorkflowProcessDetailQueryDto("instance-1", null)))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR));

        verify(finalValue, never()).getValue();
    }

    /**
     * 验证 longString 即使伪装成固定内部变量，也不能反序列化非 String 对象。
     *
     * @return 无返回值，非 String Java 序列化正文必须返回稳定数据异常
     */
    @Test
    void rejectsNonStringLongSubmissionPayload()
    {
        prepareEmptyAuthorizedDetail();
        stubVariableQueries(List.of(), List.of());
        byte[] maliciousBytes = serializeValue(List.of("不得反序列化"));
        WorkflowHistoricSubmissionRow malicious = new WorkflowHistoricSubmissionRow(
                "detail-object", "instance-1", null, null,
                WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME, 1, "longString",
                Date.from(Instant.parse("2026-07-25T08:00:00Z")), "VariableUpdate",
                "bytes-detail-object", 0, 0, null, 1, 1,
                (long) maliciousBytes.length);
        submissionBodies.put(malicious.detailId(), new WorkflowHistoricVariableBodyRow(
                malicious.detailId(), null, maliciousBytes));
        stubSubmissionUpdates(List.of(malicious));

        assertThatThrownBy(() -> service.getDetail(
                new WorkflowProcessDetailQueryDto("instance-1", null)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR));
    }

    /**
     * 验证固定内部变量名只要存在坏行就返回 500，不能被 SQL 过滤后冒充升级前旧实例。
     *
     * @return 无返回值，错误历史类型必须在任何正文查询前失败
     */
    @Test
    void rejectsMalformedFixedNameRowBeforeReadingBodies()
    {
        prepareEmptyAuthorizedDetail();
        stubVariableQueries(List.of(), List.of());
        WorkflowHistoricSubmissionRow valid = submissionUpdate("detail-malformed",
                "2026-07-25T08:00:00Z", null, null, "{}");
        WorkflowHistoricSubmissionRow malformed = copySubmissionMetadata(valid,
                "FormProperty", valid.storedBytes());
        stubSubmissionUpdates(List.of(malformed));

        assertThatThrownBy(() -> service.getDetail(
                new WorkflowProcessDetailQueryDto("instance-1", null)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR));

        verify(historicVariableMapper, never()).selectSubmissionBodies(
                anyString(), anyString(), anyList());
    }

    /**
     * 验证 longString 仍必须使用序列化 Blob，不能把类型与 TEXT_ 的矛盾静默兼容。
     *
     * @return 无返回值，错误存储组合必须在读取正文前返回 500
     */
    @Test
    void rejectsInlineLongStringSubmissionBeforeReadingBodies()
    {
        prepareEmptyAuthorizedDetail();
        stubVariableQueries(List.of(), List.of());
        WorkflowHistoricSubmissionRow inline = submissionUpdate("detail-inline-long",
                "2026-07-25T08:00:00Z", null, null, "{}");
        WorkflowHistoricSubmissionRow malformed = new WorkflowHistoricSubmissionRow(
                inline.detailId(), inline.processInstanceId(), inline.activityInstanceId(),
                inline.taskId(), inline.variableName(), inline.revision(), "longString",
                inline.submittedAt(), inline.detailType(), inline.byteArrayId(),
                inline.textPresent(), inline.text2Present(), inline.textBytes(),
                inline.byteArrayPresent(), inline.byteArrayBodyPresent(), inline.storedBytes());
        stubSubmissionUpdates(List.of(malformed));

        assertThatThrownBy(() -> service.getDetail(
                new WorkflowProcessDetailQueryDto("instance-1", null)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR));

        verify(historicVariableMapper, never()).selectSubmissionBodies(
                anyString(), anyString(), anyList());
    }

    /**
     * 验证多行快照各自低于单行限制但累计超过限制时，在物化任一正文前整体失败。
     *
     * @return 无返回值，累计容量门禁必须返回 500 且正文 Mapper 零调用
     */
    @Test
    void rejectsCumulativeSubmissionStorageBeforeReadingBodies()
    {
        prepareEmptyAuthorizedDetail();
        stubVariableQueries(List.of(), List.of());
        long perRowBytes = WorkflowProcessDetailService.MAX_TOTAL_SUBMISSION_STORED_BYTES / 2L + 1L;
        WorkflowHistoricSubmissionRow first = longSubmissionUpdate("detail-total-1",
                "2026-07-25T08:00:00Z", null, null, "{}");
        WorkflowHistoricSubmissionRow second = longSubmissionUpdate("detail-total-2",
                "2026-07-25T08:00:01Z", "task-2", null, "{}");
        stubSubmissionUpdates(List.of(
                copySubmissionMetadata(first, first.detailType(), perRowBytes),
                copySubmissionMetadata(second, second.detailType(), perRowBytes)));

        assertThatThrownBy(() -> service.getDetail(
                new WorkflowProcessDetailQueryDto("instance-1", null)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR));

        verify(historicVariableMapper, never()).selectSubmissionBodies(
                anyString(), anyString(), anyList());
    }

    /**
     * 验证三条活动 json Blob 各自不超过单项限制、但累计超过 2 MiB 时在正文查询前整体拒绝。
     *
     * @return 无返回值，累计容量门禁必须返回稳定 500，且正文 Mapper 和 Flowable getValue 均零调用
     */
    @Test
    void rejectsCumulativeCurrentVariableStorageBeforeReadingBodies()
    {
        HistoricVariableInstance first = variable(
                "decision", "json", Map.of("safe", true), "task-active");
        HistoricVariableInstance second = variable(
                "comment", "json", Map.of("safe", true), "task-active");
        HistoricVariableInstance third = variable(
                "evidence", "json", Map.of("safe", true), "task-active");
        prepareActiveDetail(first, "decision");
        when(deployFormMapper.selectByDeploymentId("deployment-1")).thenReturn(List.of(
                snapshot(2L, "key_2", "approve", "审批表", "审批", """
                        {"fields":[
                          {"__vModel__":"decision","__config__":{"layout":"colFormItem","tag":"el-input"}},
                          {"__vModel__":"comment","__config__":{"layout":"colFormItem","tag":"el-input"}},
                          {"__vModel__":"evidence","__config__":{"layout":"colFormItem","tag":"el-input"}}
                        ]}
                        """)));
        stubVariableQueries(List.of(), List.of(first, second, third));

        long perRowBytes = WorkflowProcessDetailService.MAX_TOTAL_CURRENT_VARIABLE_STORED_BYTES
                / 3L + 1L;
        assertThat(perRowBytes)
                .isLessThanOrEqualTo(WorkflowProcessDetailService.MAX_CURRENT_VARIABLE_BODY_BYTES);
        // 先读取变量 mock 并固化元数据，避免在 Mapper 的 when 尚未闭合时触发嵌套 mock 调用。
        List<WorkflowCurrentVariableMetadataRow> currentMetadata = List.of(
                currentJsonBlobMetadata(first, perRowBytes),
                currentJsonBlobMetadata(second, perRowBytes),
                currentJsonBlobMetadata(third, perRowBytes));
        when(historicVariableMapper.selectCurrentVariableMetadata(
                eq("instance-1"), eq("task-active"), eq(true), anyList(), anyInt()))
                .thenReturn(currentMetadata);

        assertThatThrownBy(() -> service.getDetail(
                new WorkflowProcessDetailQueryDto("instance-1", "task-active")))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR);
                    assertThat(exception.getMessage())
                            .isEqualTo("活动表单变量累计正文超过安全上限");
                });

        verify(historicVariableMapper, never()).selectCurrentVariableBodies(
                anyString(), anyString(), eq(true), anyList(), anyList());
        verify(first, never()).getValue();
        verify(second, never()).getValue();
        verify(third, never()).getValue();
    }

    /**
     * 验证活动 string+Blob 的恶意 Java 对象流由 String-only 过滤器拒绝，且从不调用 Flowable getValue。
     *
     * @return 无返回值，非 String 正文必须返回 500 且变量值保持未初始化
     */
    @Test
    void rejectsMaliciousCurrentStringBlobWithoutInitializingFlowableValue()
    {
        HistoricVariableInstance variable = variable(
                "decision", "string", "不得调用", "task-active");
        prepareActiveDetail(variable, "decision");
        byte[] malicious = serializeValue(List.of("不得反序列化"));
        stubCurrentRawVariable(variable, "task-active", true, null, malicious,
                (long) malicious.length);

        assertThatThrownBy(() -> service.getDetail(
                new WorkflowProcessDetailQueryDto("instance-1", "task-active")))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR));

        verify(variable, never()).getValue();
    }

    /**
     * 验证 Flowable 8 的活动 string+Blob 可以按物理元数据安全恢复，且不初始化引擎变量值。
     *
     * @return 无返回值，受限字符串必须真实回显且 Flowable getValue 保持零调用
     */
    @Test
    void readsCurrentStringBlobWithoutInitializingFlowableValue()
    {
        HistoricVariableInstance variable = variable(
                "decision", "string", "不得调用", "task-active");
        prepareActiveDetail(variable, "decision");
        String expected = "来自 Flowable 字符串 Blob 的安全正文";
        byte[] serialized = serializeValue(expected);
        stubCurrentRawVariable(variable, "task-active", true, null, serialized,
                (long) serialized.length);

        WorkflowProcessDetailView detail = service.getDetail(
                new WorkflowProcessDetailQueryDto("instance-1", "task-active"));

        assertThat(detail.currentTaskForm().values().get("decision").textValue())
                .isEqualTo(expected);
        verify(variable, never()).getValue();
    }

    /**
     * 验证活动 longJson 在任意嵌套层级拒绝三类原型污染键，且从不调用 Flowable getValue。
     *
     * @param forbiddenKey String，必须拒绝的 __proto__、prototype 或 constructor 键
     * @return 无返回值，危险键必须返回 500 且变量值保持未初始化
     */
    @ParameterizedTest
    @ValueSource(strings = { "__proto__", "prototype", "constructor" })
    void rejectsDangerousNestedJsonKeysWithoutInitializingFlowableValue(String forbiddenKey)
    {
        HistoricVariableInstance variable = variable(
                "decision", "longJson", Map.of("safe", true), "task-active");
        prepareActiveDetail(variable, "decision");
        byte[] json = ("{\"outer\":{\"" + forbiddenKey + "\":true}}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        stubCurrentRawVariable(variable, "task-active", true, null, json,
                (long) json.length);

        assertThatThrownBy(() -> service.getDetail(
                new WorkflowProcessDetailQueryDto("instance-1", "task-active")))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR));

        verify(variable, never()).getValue();
    }

    /**
     * 验证活动 longJson 在合法根节点后存在尾随 JSON 时由严格解析器整体拒绝。
     *
     * @return 无返回值，尾随 token 必须返回 500 且不得调用 Flowable getValue
     */
    @Test
    void rejectsTrailingLongJsonTokensWithoutInitializingFlowableValue()
    {
        HistoricVariableInstance variable = variable(
                "decision", "longJson", Map.of("safe", true), "task-active");
        prepareActiveDetail(variable, "decision");
        byte[] json = "{\"approved\":true}{\"trailing\":true}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        stubCurrentRawVariable(variable, "task-active", true, null, json,
                (long) json.length);

        assertThatThrownBy(() -> service.getDetail(
                new WorkflowProcessDetailQueryDto("instance-1", "task-active")))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR));

        verify(variable, never()).getValue();
    }

    /**
     * 验证活动 longJson 的元数据声明正文超限时不会执行第二阶段 Blob 查询。
     *
     * @return 无返回值，单项容量门禁必须返回 500、正文 Mapper 和 getValue 均零调用
     */
    @Test
    void rejectsOversizedCurrentLongJsonBeforeReadingBody()
    {
        HistoricVariableInstance variable = variable(
                "decision", "longJson", Map.of("safe", true), "task-active");
        prepareActiveDetail(variable, "decision");
        stubCurrentRawVariable(variable, "task-active", true, null, null,
                (long) WorkflowProcessDetailService.MAX_CURRENT_VARIABLE_BODY_BYTES + 1L);

        assertThatThrownBy(() -> service.getDetail(
                new WorkflowProcessDetailQueryDto("instance-1", "task-active")))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR));

        verify(historicVariableMapper, never()).selectCurrentVariableBodies(
                anyString(), anyString(), eq(true), anyList(), anyList());
        verify(variable, never()).getValue();
    }

    /**
     * 验证伪造任务与实例关系时在读取定义、表单、变量和意见正文前返回 409。
     * @return 无返回值，断言对象关系门禁与零敏感正文读取
     */
    @Test
    void rejectsForgedTaskInstanceRelationshipBeforeReadingDetailContent()
    {
        when(processAccessService.requireReadableInstance("instance-1"))
                .thenReturn(processSnapshot("instance-1"));
        when(processAccessService.requireReadableTask("task-forged"))
                .thenReturn(new WorkflowTaskAccessSnapshot("task-forged", "instance-other",
                        "definition-1", "approve", "审批", "7", null, null,
                        true, Instant.parse("2026-07-25T08:00:00Z"), null));

        assertThatThrownBy(() -> service.getDetail(
                new WorkflowProcessDetailQueryDto("instance-1", "task-forged")))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(repositoryService, never()).getProcessDefinition(anyString());
        verify(deployFormMapper, never()).selectByDeploymentId(anyString());
        verify(taskService, never()).getProcessInstanceComments(anyString());
    }

    /**
     * 验证历史活动超过门禁时明确失败，不截断时间线伪造完整审计结果。
     * @return 无返回值，断言 500 容量异常和不继续读取任务/变量/意见
     */
    @Test
    void rejectsActivityHistoryBeyondSafetyLimit()
    {
        when(processAccessService.requireReadableInstance("instance-1"))
                .thenReturn(processSnapshot("instance-1"));
        ProcessDefinition definition = stubDefinitionAndModel();
        when(repositoryService.getProcessDefinition("definition-1")).thenReturn(definition);
        when(deployFormMapper.selectByDeploymentId("deployment-1")).thenReturn(List.of());
        stubActivityQuery(java.util.Collections.nCopies(
                WorkflowProcessDetailService.MAX_ACTIVITY_ROWS + 1,
                mock(HistoricActivityInstance.class)));

        assertThatThrownBy(() -> service.getDetail(
                new WorkflowProcessDetailQueryDto("instance-1", null)))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR));

        verify(historyService, never()).createHistoricTaskInstanceQuery();
        verify(taskService, never()).getProcessInstanceComments(anyString());
    }

    /**
     * 配置已通过对象授权的实例和任务快照。
     * @param instanceId String，流程实例主键
     * @param taskId String，任务主键
     * @return 无返回值，详情可继续执行真实关系查询
     */
    private void stubAuthorizedObjects(String instanceId, String taskId)
    {
        when(processAccessService.requireReadableInstance(instanceId))
                .thenReturn(processSnapshot(instanceId));
        when(processAccessService.requireReadableTask(taskId))
                .thenReturn(new WorkflowTaskAccessSnapshot(taskId, instanceId, "definition-1",
                        "approve", "审批", "8", null, null, false,
                        Instant.parse("2026-07-25T08:01:00Z"),
                        Instant.parse("2026-07-25T09:00:00Z")));
    }

    /**
     * 创建已授权流程实例快照。
     * @param instanceId String，流程实例主键
     * @return WorkflowProcessAccessSnapshot，定义、部署和发起人关系完整的实例快照
     */
    private WorkflowProcessAccessSnapshot processSnapshot(String instanceId)
    {
        return new WorkflowProcessAccessSnapshot(instanceId, "definition-1", "deployment-1",
                "business-1", "9", Instant.parse("2026-07-25T08:00:00Z"),
                Instant.parse("2026-07-25T09:00:01Z"), null, null, "COMPLETED");
    }

    /**
     * 创建尚未结束的已授权流程实例快照。
     *
     * @param instanceId String，流程实例主键
     * @return WorkflowProcessAccessSnapshot，结束时间为空且状态为 ACTIVE 的实例快照
     */
    private WorkflowProcessAccessSnapshot activeProcessSnapshot(String instanceId)
    {
        return new WorkflowProcessAccessSnapshot(instanceId, "definition-1", "deployment-1",
                "business-1", "9", Instant.parse("2026-07-25T08:00:00Z"),
                null, null, null, "ACTIVE");
    }

    /**
     * 配置不含活动、任务和表单的最小已授权详情读取场景。
     *
     * @return 无返回值，调用方可单独配置变量查询异常分支
     */
    private void prepareEmptyAuthorizedDetail()
    {
        when(processAccessService.requireReadableInstance("instance-1"))
                .thenReturn(processSnapshot("instance-1"));
        ProcessDefinition definition = stubDefinitionAndModel();
        when(repositoryService.getProcessDefinition("definition-1")).thenReturn(definition);
        when(deployFormMapper.selectByDeploymentId("deployment-1")).thenReturn(List.of());
        stubActivityQuery(List.of());
        stubHistoricTaskQuery(List.of());
        when(taskService.getProcessInstanceComments("instance-1")).thenReturn(List.of());
    }

    /**
     * 配置包含一个 schema 白名单变量的活动任务详情场景。
     *
     * @param variable HistoricVariableInstance，活动任务局部变量元数据 mock
     * @param variableName String，部署表单 schema 声明的唯一字段名
     * @return 无返回值，调用方只需继续配置该变量的数据库存储元数据和正文
     */
    private void prepareActiveDetail(HistoricVariableInstance variable, String variableName)
    {
        when(processAccessService.requireReadableInstance("instance-1"))
                .thenReturn(activeProcessSnapshot("instance-1"));
        when(processAccessService.requireReadableTask("task-active"))
                .thenReturn(new WorkflowTaskAccessSnapshot("task-active", "instance-1",
                        "definition-1", "approve", "审批", "8", null, null,
                        true, Instant.parse("2026-07-25T08:01:00Z"), null));
        ProcessDefinition definition = stubDefinitionAndModel();
        when(repositoryService.getProcessDefinition("definition-1")).thenReturn(definition);
        when(deployFormMapper.selectByDeploymentId("deployment-1")).thenReturn(List.of(
                valueSnapshot(2L, "key_2", "approve", "审批表", variableName)));
        stubActivityQuery(List.of(activity("approve-active", "approve", "审批",
                "userTask", "task-active", "2026-07-25T08:01:00Z", null)));
        stubHistoricTaskQuery(List.of(historicTask("task-active", "approve", null)));
        when(taskService.getProcessInstanceComments("instance-1")).thenReturn(List.of());
        stubVariableQueries(List.of(), List.of(variable));
        stubSubmissionUpdates(List.of());
        when(deploymentService.getBpmnXml("definition-1")).thenReturn("<definitions/>");
        // 活动详情默认复现唯一当前任务；并行场景会在测试体中用多任务结果覆盖该夹具。
        stubActiveRuntimeTasks("task-active");
    }

    /**
     * 配置实例当前真实活动任务查询，复现写命令 prepare() 使用的运行时结构门禁。
     *
     * @param taskIds String[]，TaskService 返回的活动任务主键；多个主键表示并行或复杂执行树
     * @return TaskQuery，供测试核验实例、活动态和最终列表查询均已执行
     */
    private TaskQuery stubActiveRuntimeTasks(String... taskIds)
    {
        TaskQuery activeTaskQuery = mock(TaskQuery.class);
        when(taskService.createTaskQuery()).thenReturn(activeTaskQuery);
        when(activeTaskQuery.processInstanceId("instance-1")).thenReturn(activeTaskQuery);
        when(activeTaskQuery.active()).thenReturn(activeTaskQuery);
        List<Task> activeTasks = List.of(taskIds).stream().map(taskId ->
        {
            Task task = mock(Task.class);
            when(task.getId()).thenReturn(taskId);
            return task;
        }).toList();
        when(activeTaskQuery.list()).thenReturn(activeTasks);
        return activeTaskQuery;
    }

    /**
     * 在测试部署模型中把当前普通审批节点连接到受控 REQUIRED_ANY 动态多实例节点。
     *
     * @return 无返回值，仓储 mock 中的 BPMN 模型被原位补齐唯一无条件后继
     */
    private void addRequiredAnyMultiInstanceSuccessor()
    {
        BpmnModel model = repositoryService.getBpmnModel("definition-1");
        org.flowable.bpmn.model.Process process = model.getProcessById("leave");
        UserTask source = (UserTask) process.getFlowElement("approve", false);
        UserTask target = new UserTask();
        target.setId("dynamic-review");
        target.setName("动态或签");
        target.setAssignee(WorkflowMultiInstanceModelContract.ASSIGNEE_EXPRESSION);
        MultiInstanceLoopCharacteristics loop = new MultiInstanceLoopCharacteristics();
        loop.setInputDataItem(WorkflowMultiInstanceModelContract.COLLECTION_EXPRESSION);
        loop.setElementVariable(WorkflowMultiInstanceModelContract.ELEMENT_VARIABLE);
        loop.setCompletionCondition(WorkflowMultiInstanceModelContract.ANY_COMPLETION_CONDITION);
        target.setLoopCharacteristics(loop);
        process.addFlowElement(target);
        SequenceFlow sequenceFlow = new SequenceFlow(source.getId(), target.getId());
        sequenceFlow.setId("approve-to-dynamic-review");
        sequenceFlow.setSourceFlowElement(source);
        sequenceFlow.setTargetFlowElement(target);
        source.getOutgoingFlows().add(sequenceFlow);
        target.getIncomingFlows().add(sequenceFlow);
        process.addFlowElement(sequenceFlow);
    }

    /**
     * 配置测试流程定义及含开始表单、局部任务表单和结束节点的 BPMN 模型。
     * @return ProcessDefinition，关系字段完整的流程定义 mock
     */
    private ProcessDefinition stubDefinitionAndModel()
    {
        return stubDefinitionAndModel(List.of(
                new TestTaskSpec("approve", "审批", "key_2", true)));
    }

    /**
     * 配置包含指定用户任务的测试流程定义和 BPMN 模型。
     *
     * @param taskSpecs List&lt;TestTaskSpec&gt;，用户任务节点、表单键和 localScope 设置
     * @return ProcessDefinition，关系字段完整的流程定义 mock
     */
    private ProcessDefinition stubDefinitionAndModel(List<TestTaskSpec> taskSpecs)
    {
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getId()).thenReturn("definition-1");
        when(definition.getKey()).thenReturn("leave");
        when(definition.getName()).thenReturn("请假流程");
        when(definition.getVersion()).thenReturn(3);
        when(definition.getCategory()).thenReturn("hr");
        when(definition.getDeploymentId()).thenReturn("deployment-1");

        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId("leave");
        StartEvent start = new StartEvent();
        start.setId("start");
        start.setName("开始");
        start.setFormKey("key_1");
        SequenceFlow flow = new SequenceFlow();
        flow.setId("flow-1");
        EndEvent end = new EndEvent();
        end.setId("end");
        end.setName("结束");
        process.addFlowElement(start);
        process.addFlowElement(flow);
        for (TestTaskSpec taskSpec : taskSpecs)
        {
            UserTask userTask = new UserTask();
            userTask.setId(taskSpec.nodeKey());
            userTask.setName(taskSpec.nodeName());
            userTask.setFormKey(taskSpec.formKey());
            if (taskSpec.localScope())
            {
                ExtensionAttribute localScope = new ExtensionAttribute();
                localScope.setName("localScope");
                localScope.setValue("true");
                userTask.addAttribute(localScope);
            }
            process.addFlowElement(userTask);
        }
        process.addFlowElement(end);
        model.addProcess(process);
        when(repositoryService.getBpmnModel("definition-1")).thenReturn(model);
        return definition;
    }

    /**
     * 配置历史活动原生有界分页查询。
     * @param rows List&lt;HistoricActivityInstance&gt;，查询返回的活动行
     * @return 无返回值，详情服务可执行确定性 listPage
     */
    private void stubActivityQuery(List<HistoricActivityInstance> rows)
    {
        when(historyService.createHistoricActivityInstanceQuery()).thenReturn(activityQuery);
        when(activityQuery.processInstanceId("instance-1")).thenReturn(activityQuery);
        when(activityQuery.orderByHistoricActivityInstanceStartTime()).thenReturn(activityQuery);
        when(activityQuery.orderByActivityId()).thenReturn(activityQuery);
        when(activityQuery.asc()).thenReturn(activityQuery);
        when(activityQuery.listPage(0, WorkflowProcessDetailService.MAX_ACTIVITY_ROWS + 1))
                .thenReturn(rows);
    }

    /**
     * 配置历史任务及 identity link 预加载的有界分页查询。
     * @param rows List&lt;HistoricTaskInstance&gt;，查询返回的任务行
     * @return 无返回值，详情服务可执行确定性 listPage
     */
    private void stubHistoricTaskQuery(List<HistoricTaskInstance> rows)
    {
        when(historyService.createHistoricTaskInstanceQuery()).thenReturn(historicTaskQuery);
        when(historicTaskQuery.processInstanceId("instance-1")).thenReturn(historicTaskQuery);
        when(historicTaskQuery.includeIdentityLinks()).thenReturn(historicTaskQuery);
        when(historicTaskQuery.orderByHistoricTaskInstanceStartTime())
                .thenReturn(historicTaskQuery);
        when(historicTaskQuery.orderByTaskId()).thenReturn(historicTaskQuery);
        when(historicTaskQuery.asc()).thenReturn(historicTaskQuery);
        when(historicTaskQuery.listPage(0, WorkflowProcessDetailService.MAX_TASK_ROWS + 1))
                .thenReturn(rows);
    }

    /**
     * 配置流程全局变量和任务局部变量的两次有界查询。
     * @param processRows List&lt;HistoricVariableInstance&gt;，流程全局变量行
     * @param taskRows List&lt;HistoricVariableInstance&gt;，任务局部变量行
     * @return 无返回值，详情服务可按作用域建立变量元数据索引
     */
    private void stubVariableQueries(List<HistoricVariableInstance> processRows,
            List<HistoricVariableInstance> taskRows)
    {
        when(historyService.createHistoricVariableInstanceQuery())
                .thenReturn(processVariableQuery, taskVariableQuery);
        when(processVariableQuery.processInstanceId("instance-1"))
                .thenReturn(processVariableQuery);
        when(processVariableQuery.excludeTaskVariables()).thenReturn(processVariableQuery);
        when(processVariableQuery.excludeLocalVariables()).thenReturn(processVariableQuery);
        when(processVariableQuery.excludeVariableInitialization()).thenReturn(processVariableQuery);
        when(processVariableQuery.orderByVariableName()).thenReturn(processVariableQuery);
        when(processVariableQuery.asc()).thenReturn(processVariableQuery);
        when(processVariableQuery.listPage(0, WorkflowProcessDetailService.MAX_VARIABLE_ROWS + 1))
                .thenReturn(processRows);
        when(taskVariableQuery.taskIds(anySet())).thenReturn(taskVariableQuery);
        when(taskVariableQuery.excludeVariableInitialization()).thenReturn(taskVariableQuery);
        when(taskVariableQuery.orderByVariableName()).thenReturn(taskVariableQuery);
        when(taskVariableQuery.asc()).thenReturn(taskVariableQuery);
        when(taskVariableQuery.listPage(0, WorkflowProcessDetailService.MAX_VARIABLE_ROWS + 1))
                .thenReturn(taskRows);
    }

    /**
     * 配置数据库层固定名称快照的两阶段元数据和正文查询。
     *
     * @param rows List&lt;WorkflowHistoricSubmissionRow&gt;，真实顺序返回的完整快照元数据行
     * @return 无返回值，详情服务只能在元数据门禁后按主键读取对应正文
     */
    private void stubSubmissionUpdates(List<WorkflowHistoricSubmissionRow> rows)
    {
        when(historicVariableMapper.selectSubmissionMetadata(
                eq("instance-1"), eq(WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME),
                eq(WorkflowProcessDetailService.MAX_VARIABLE_UPDATE_ROWS + 1)))
                .thenReturn(new java.util.ArrayList<>(rows));
        when(historicVariableMapper.selectSubmissionBodies(
                eq("instance-1"), eq(WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME),
                anyList())).thenAnswer(invocation ->
        {
            List<String> rowIds = invocation.getArgument(2);
            return rowIds.stream().map(submissionBodies::get)
                    .filter(java.util.Objects::nonNull).toList();
        });
    }

    /**
     * 配置活动 JSON 变量的两阶段元数据和正文查询，确保测试不会依赖 Flowable getValue。
     *
     * @param variable HistoricVariableInstance，禁止初始化查询返回的变量元数据 mock
     * @param taskId String，当前已授权活动任务主键
     * @param taskLocal boolean，true 表示任务局部变量，false 表示流程根变量
     * @param json String，数据库 TEXT_ 中保存的严格 JSON 正文
     * @return 无返回值，Mapper 只对绑定实例、作用域、白名单和主键返回该变量
     */
    private void stubCurrentJsonVariable(HistoricVariableInstance variable, String taskId,
            boolean taskLocal, String json)
    {
        byte[] jsonBytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        WorkflowCurrentVariableMetadataRow metadata = new WorkflowCurrentVariableMetadataRow(
                variable.getId(), "instance-1", "instance-1",
                taskLocal ? taskId : null, null, variable.getVariableName(),
                variable.getVariableTypeName(), null, 1, 0, (long) jsonBytes.length,
                0, 0, null);
        when(historicVariableMapper.selectCurrentVariableMetadata(
                eq("instance-1"), eq(taskId), eq(taskLocal), anyList(), anyInt()))
                .thenReturn(List.of(metadata));
        when(historicVariableMapper.selectCurrentVariableBodies(
                eq("instance-1"), eq(taskId), eq(taskLocal), anyList(), anyList()))
                .thenAnswer(invocation ->
                {
                    List<String> rowIds = invocation.getArgument(4);
                    return rowIds.contains(variable.getId())
                            ? List.of(new WorkflowHistoricVariableBodyRow(
                                    variable.getId(), json, null))
                            : List.of();
                });
    }

    /**
     * 配置活动 string Blob、longString 或 JSON 的受控元数据和第二阶段正文。
     *
     * @param variable HistoricVariableInstance，禁止初始化查询返回的变量元数据 mock
     * @param taskId String，当前已授权活动任务主键
     * @param taskLocal boolean，true 表示任务局部变量，false 表示流程根变量
     * @param storedText String，TEXT_ 正文；Blob 类型测试通常为空
     * @param storedBytes byte[]，真实第二阶段 Blob 正文；容量门禁测试允许为空
     * @param reportedBytes Long，第一阶段 SQL 统计的 Blob 字节数
     * @return 无返回值，Mapper 查询被完整绑定到实例、作用域、白名单和变量主键
     */
    private void stubCurrentRawVariable(HistoricVariableInstance variable, String taskId,
            boolean taskLocal, String storedText, byte[] storedBytes, Long reportedBytes)
    {
        boolean textPresent = storedText != null;
        WorkflowCurrentVariableMetadataRow metadata = new WorkflowCurrentVariableMetadataRow(
                variable.getId(), "instance-1", "instance-1",
                taskLocal ? taskId : null, null, variable.getVariableName(),
                variable.getVariableTypeName(), textPresent ? null : "bytes-" + variable.getId(),
                textPresent ? 1 : 0, 0,
                textPresent ? (long) storedText.getBytes(
                        java.nio.charset.StandardCharsets.UTF_8).length : null,
                textPresent ? 0 : 1, textPresent ? 0 : 1,
                textPresent ? null : reportedBytes);
        when(historicVariableMapper.selectCurrentVariableMetadata(
                eq("instance-1"), eq(taskId), eq(taskLocal), anyList(), anyInt()))
                .thenReturn(List.of(metadata));
        when(historicVariableMapper.selectCurrentVariableBodies(
                eq("instance-1"), eq(taskId), eq(taskLocal), anyList(), anyList()))
                .thenAnswer(invocation ->
                {
                    List<String> rowIds = invocation.getArgument(4);
                    return rowIds.contains(variable.getId())
                            ? List.of(new WorkflowHistoricVariableBodyRow(
                                    variable.getId(), storedText, storedBytes))
                            : List.of();
                });
    }

    /**
     * 创建一条各存储列关系合法、正文长度可控的活动 json Blob 元数据。
     *
     * @param variable HistoricVariableInstance，禁止初始化查询返回的变量元数据 mock
     * @param storedBytes long，第一阶段 SQL 统计的真实 Blob 字节数
     * @return WorkflowCurrentVariableMetadataRow，可参与累计物理字节门禁的合法元数据
     */
    private WorkflowCurrentVariableMetadataRow currentJsonBlobMetadata(
            HistoricVariableInstance variable, long storedBytes)
    {
        return new WorkflowCurrentVariableMetadataRow(
                variable.getId(), "instance-1", "instance-1", "task-active", null,
                variable.getVariableName(), variable.getVariableTypeName(),
                "bytes-" + variable.getId(), 0, 0, null, 1, 1, storedBytes);
    }

    /**
     * 创建关系字段和时间完整的历史活动 mock。
     * @param id String，历史活动实例主键
     * @param activityId String，BPMN 活动主键
     * @param name String，活动名称
     * @param type String，活动类型
     * @param taskId String，任务主键，非用户任务为空
     * @param start String，ISO-8601 开始时间
     * @param end String，ISO-8601 结束时间
     * @return HistoricActivityInstance，历史活动 mock
     */
    private HistoricActivityInstance activity(String id, String activityId, String name,
            String type, String taskId, String start, String end)
    {
        HistoricActivityInstance activity = mock(HistoricActivityInstance.class);
        when(activity.getId()).thenReturn(id);
        when(activity.getActivityId()).thenReturn(activityId);
        when(activity.getActivityName()).thenReturn(name);
        when(activity.getActivityType()).thenReturn(type);
        when(activity.getTaskId()).thenReturn(taskId);
        when(activity.getProcessInstanceId()).thenReturn("instance-1");
        when(activity.getProcessDefinitionId()).thenReturn("definition-1");
        when(activity.getStartTime()).thenReturn(Date.from(Instant.parse(start)));
        when(activity.getEndTime()).thenReturn(end == null
                ? null : Date.from(Instant.parse(end)));
        return activity;
    }

    /**
     * 创建与固定实例、定义关联的历史任务 mock。
     *
     * @param taskId String，历史任务主键
     * @param taskDefinitionKey String，BPMN 用户任务节点主键
     * @param end String，ISO-8601 结束时间；活动任务为空
     * @return HistoricTaskInstance，候选身份为空且状态可控的历史任务
     */
    private HistoricTaskInstance historicTask(String taskId, String taskDefinitionKey, String end)
    {
        HistoricTaskInstance task = mock(HistoricTaskInstance.class);
        when(task.getId()).thenReturn(taskId);
        when(task.getProcessInstanceId()).thenReturn("instance-1");
        when(task.getProcessDefinitionId()).thenReturn("definition-1");
        when(task.getTaskDefinitionKey()).thenReturn(taskDefinitionKey);
        when(task.getEndTime()).thenReturn(end == null ? null : Date.from(Instant.parse(end)));
        doReturn(List.of()).when(task).getIdentityLinks();
        return task;
    }

    /**
     * 创建服务端内部表单提交快照历史更新。
     *
     * @param id String，历史详情主键
     * @param time String，ISO-8601 快照写入时间
     * @param taskId String，任务局部关联主键；开始快照为空
     * @param activityInstanceId String，关联历史活动实例主键；允许为空
     * @param encoded String，受限快照 JSON
     * @return WorkflowHistoricSubmissionRow，可被详情服务严格解析的 string 存储行
     */
    private WorkflowHistoricSubmissionRow submissionUpdate(String id, String time, String taskId,
            String activityInstanceId, String encoded)
    {
        byte[] encodedBytes = encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        WorkflowHistoricSubmissionRow row = new WorkflowHistoricSubmissionRow(
                id, "instance-1", activityInstanceId,
                taskId, WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME, 1, "string",
                Date.from(Instant.parse(time)), "VariableUpdate", null,
                1, 0, (long) encodedBytes.length, 0, 0, null);
        submissionBodies.put(id, new WorkflowHistoricVariableBodyRow(id, encoded, null));
        return row;
    }

    /**
     * 创建关系、类型和时间完整的 Flowable longString 内部快照行。
     *
     * @param id String，历史详情主键
     * @param taskId String，任务局部关联主键；流程变量为空
     * @param activityInstanceId String，关联历史活动实例主键；允许为空
     * @param time String，ISO-8601 写入时间
     * @param encoded String，受限快照 JSON
     * @return WorkflowHistoricSubmissionRow，正文使用 Java String 序列化格式的 longString 行
     */
    private WorkflowHistoricSubmissionRow longSubmissionUpdate(String id, String time,
            String taskId, String activityInstanceId, String encoded)
    {
        byte[] serialized = serializeValue(encoded);
        WorkflowHistoricSubmissionRow row = new WorkflowHistoricSubmissionRow(
                id, "instance-1", activityInstanceId,
                taskId, WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME, 1, "longString",
                Date.from(Instant.parse(time)), "VariableUpdate", "bytes-" + id,
                0, 0, null, 1, 1, (long) serialized.length);
        submissionBodies.put(id, new WorkflowHistoricVariableBodyRow(id, null, serialized));
        return row;
    }

    /**
     * 创建 Flowable 8 实际出现的 string 类型加 Java 序列化 Blob 内部快照行。
     *
     * @param id String，历史详情主键
     * @param taskId String，任务局部关联主键；流程变量为空
     * @param activityInstanceId String，关联历史活动实例主键；允许为空
     * @param time String，ISO-8601 写入时间
     * @param encoded String，受限快照 JSON
     * @return WorkflowHistoricSubmissionRow，类型为 string 且正文位于 BYTEARRAY_ID_ 的历史行
     */
    private WorkflowHistoricSubmissionRow stringBlobSubmissionUpdate(String id, String time,
            String taskId, String activityInstanceId, String encoded)
    {
        byte[] serialized = serializeValue(encoded);
        WorkflowHistoricSubmissionRow row = new WorkflowHistoricSubmissionRow(
                id, "instance-1", activityInstanceId,
                taskId, WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME, 1, "string",
                Date.from(Instant.parse(time)), "VariableUpdate", "bytes-" + id,
                0, 0, null, 1, 1, (long) serialized.length);
        submissionBodies.put(id, new WorkflowHistoricVariableBodyRow(id, null, serialized));
        return row;
    }

    /**
     * 复制快照元数据并替换历史明细类型或 Blob 统计，用于损坏行和累计容量测试。
     *
     * @param source WorkflowHistoricSubmissionRow，关系和存储列完整的原元数据
     * @param detailType String，替换后的 ACT_HI_DETAIL.TYPE_
     * @param storedBytes Long，替换后的 Blob 长度统计
     * @return WorkflowHistoricSubmissionRow，除指定字段外保持原值的元数据副本
     */
    private WorkflowHistoricSubmissionRow copySubmissionMetadata(
            WorkflowHistoricSubmissionRow source, String detailType, Long storedBytes)
    {
        return new WorkflowHistoricSubmissionRow(source.detailId(), source.processInstanceId(),
                source.activityInstanceId(), source.taskId(), source.variableName(),
                source.revision(), source.variableTypeName(), source.submittedAt(), detailType,
                source.byteArrayId(), source.textPresent(), source.text2Present(),
                source.textBytes(), source.byteArrayPresent(), source.byteArrayBodyPresent(),
                storedBytes);
    }

    /**
     * 使用 Flowable 字符串 Blob 相同的 Java 对象流格式生成测试正文。
     *
     * @param value Object，待序列化的 String 或恶意非 String 测试对象
     * @return byte[]，Java ObjectOutputStream 生成的完整正文
     */
    private byte[] serializeValue(Object value)
    {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ObjectOutputStream objectOutput = new ObjectOutputStream(output))
        {
            objectOutput.writeObject(value);
            objectOutput.flush();
            return output.toByteArray();
        }
        catch (IOException exception)
        {
            throw new AssertionError("测试序列化正文生成失败", exception);
        }
    }

    /**
     * 创建指定作用域和类型的历史变量元数据 mock。
     * @param name String，变量名
     * @param type String，Flowable 变量类型名
     * @param value Object，仅安全类型允许读取的变量值
     * @param taskId String，任务局部变量主键；流程变量为空
     * @return HistoricVariableInstance，历史变量 mock
     */
    private HistoricVariableInstance variable(String name, String type, Object value,
            String taskId)
    {
        HistoricVariableInstance variable = mock(HistoricVariableInstance.class);
        when(variable.getId()).thenReturn("var-" + name + "-" + (taskId == null ? "process" : taskId));
        when(variable.getVariableName()).thenReturn(name);
        when(variable.getVariableTypeName()).thenReturn(type);
        when(variable.getValue()).thenReturn(value);
        when(variable.getProcessInstanceId()).thenReturn("instance-1");
        when(variable.getTaskId()).thenReturn(taskId);
        return variable;
    }

    /**
     * 创建开始节点部署表单快照。
     * @return WfDeployForm，只声明 applicant 和禁止回显的流程内部字段
     */
    private WfDeployForm startSnapshot()
    {
        return snapshot(1L, "key_1", "start", "申请表", "开始", """
                {"fields":[
                  {"__vModel__":"applicant","__config__":{"layout":"colFormItem","tag":"el-input"}},
                  {"__vModel__":"processStatus","__config__":{"layout":"colFormItem","tag":"el-input"}},
                  {"__vModel__":"attachment","__config__":{"layout":"colFormItem","tag":"el-upload"}},
                  {"__vModel__":"notANumber","__config__":{"layout":"colFormItem","tag":"el-input-number"}},
                  {"__vModel__":"infinity","__config__":{"layout":"colFormItem","tag":"el-input-number"}}
                ]}
                """);
    }

    /**
     * 创建用户任务部署表单快照。
     * @return WfDeployForm，只声明 decision 和不安全对象字段
     */
    private WfDeployForm taskSnapshot()
    {
        return snapshot(2L, "key_2", "approve", "审批表", "审批", """
                {"fields":[
                  {"__vModel__":"decision","__config__":{"layout":"colFormItem","tag":"el-switch"}},
                  {"__vModel__":"unsafe","__config__":{"layout":"colFormItem","tag":"el-input"}}
                ]}
                """);
    }

    /**
     * 创建只声明单个普通文本字段的任务部署快照。
     *
     * @param formId Long，来源表单主键
     * @param formKey String，BPMN 表单键
     * @param nodeKey String，BPMN 用户任务节点主键
     * @param formName String，部署时表单名称
     * @param variableName String，唯一允许回显的字段名
     * @return WfDeployForm，可用于同名字段隔离场景的部署快照
     */
    private WfDeployForm valueSnapshot(Long formId, String formKey, String nodeKey,
            String formName, String variableName)
    {
        return snapshot(formId, formKey, nodeKey, formName, nodeKey,
                "{\"fields\":[{\"__vModel__\":\"" + variableName
                        + "\",\"__config__\":{\"layout\":\"colFormItem\","
                        + "\"tag\":\"el-input\"}}]}");
    }

    /**
     * 创建关系字段完整的部署表单快照。
     * @param formId Long，来源表单主键
     * @param formKey String，BPMN 表单键
     * @param nodeKey String，BPMN 节点主键
     * @param formName String，部署时表单名称
     * @param nodeName String，部署时节点名称
     * @param content String，部署时固化 JSON
     * @return WfDeployForm，测试部署快照
     */
    private WfDeployForm snapshot(Long formId, String formKey, String nodeKey,
            String formName, String nodeName, String content)
    {
        WfDeployForm snapshot = new WfDeployForm();
        snapshot.setDeployId("deployment-1");
        snapshot.setFormId(formId);
        snapshot.setFormKey(formKey);
        snapshot.setNodeKey(nodeKey);
        snapshot.setFormName(formName);
        snapshot.setNodeName(nodeName);
        snapshot.setContent(content);
        snapshot.setCreateTime(Date.from(Instant.parse("2026-07-25T07:00:00Z")));
        return snapshot;
    }

    /**
     * 测试 BPMN 用户任务规格。
     *
     * @param nodeKey String，BPMN 节点主键
     * @param nodeName String，节点显示名称
     * @param formKey String，BPMN 表单键
     * @param localScope boolean，业务字段是否使用任务局部作用域
     */
    private record TestTaskSpec(String nodeKey, String nodeName, String formKey,
            boolean localScope)
    {
    }
}
