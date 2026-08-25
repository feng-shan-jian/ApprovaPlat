package com.ruoyi.flowable.service.task;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.flowable.domain.dto.WorkflowApplicationResubmitRequest;
import com.ruoyi.flowable.domain.dto.WorkflowInstanceTerminateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowMultiInstanceAdjustmentAction;
import com.ruoyi.flowable.domain.dto.WorkflowMultiInstanceAdjustmentRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskCompleteRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskReturnRequest;
import com.ruoyi.flowable.domain.vo.WorkflowInstanceTerminateView;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentService;
import com.ruoyi.flowable.service.process.WorkflowFormSubmissionSnapshotCodec;
import com.ruoyi.flowable.service.process.WorkflowProcessStartService;
import com.ruoyi.flowable.service.process.WorkflowProcessInstanceService;

/**
 * 只通过生产公开业务入口驱动启动、完成、调整、退回、重提和附件上传。
 */
final class WorkflowMultiInstanceBusinessDriver
{
    /** 默认轮次成员。 */
    static final List<String> MEMBERS = List.of("201", "202");

    /** 正式流程发起人。 */
    static final String APPLICANT_ID = "100";

    private final ProcessEngine processEngine;
    private final RuntimeService runtimeService;
    private final TransactionTemplate transactionTemplate;
    private final ThreadLocal<String> currentUserId;
    private final String deploymentId;
    private final WorkflowTaskLifecycleService lifecycleService;
    private final WorkflowMultiInstanceService multiInstanceService;
    private final WorkflowAttachmentService attachmentService;
    private final WorkflowProcessInstanceService processInstanceService;

    /**
     * 创建仅持有生产入口的业务驱动器。
     *
     * @param processEngine ProcessEngine，启动身份入口
     * @param runtimeService RuntimeService，真实流程启动入口
     * @param transactionTemplate TransactionTemplate，准备动作事务
     * @param currentUserId ThreadLocal&lt;String&gt;，并发隔离的当前用户
     * @param deploymentId String，当前测试部署主键
     * @param lifecycleService WorkflowTaskLifecycleService，正式任务业务入口
     * @param multiInstanceService WorkflowMultiInstanceService，正式调整入口
     * @param attachmentService WorkflowAttachmentService，正式附件入口
     * @param processInstanceService WorkflowProcessInstanceService，正式终止入口
     * @return 无返回值，构造后由测试配置管理
     */
    WorkflowMultiInstanceBusinessDriver(ProcessEngine processEngine,
            RuntimeService runtimeService, TransactionTemplate transactionTemplate,
            ThreadLocal<String> currentUserId, String deploymentId,
            WorkflowTaskLifecycleService lifecycleService,
            WorkflowMultiInstanceService multiInstanceService,
            WorkflowAttachmentService attachmentService,
            WorkflowProcessInstanceService processInstanceService)
    {
        this.processEngine = processEngine;
        this.runtimeService = runtimeService;
        this.transactionTemplate = transactionTemplate;
        this.currentUserId = currentUserId;
        this.deploymentId = deploymentId;
        this.lifecycleService = lifecycleService;
        this.multiInstanceService = multiInstanceService;
        this.attachmentService = attachmentService;
        this.processInstanceService = processInstanceService;
    }

    /**
     * 启动轮次核心流程。
     *
     * @param processKey String，流程定义 key
     * @param activityId String，受控节点 ID
     * @param members List&lt;String&gt;，有序成员
     * @param variables Map&lt;String,Object&gt;，附加启动变量
     * @return ProcessInstance，真实活动实例
     */
    ProcessInstance start(String processKey, String activityId,
            List<String> members, Map<String, Object> variables)
    {
        Map<String, Object> all = new LinkedHashMap<>(variables);
        all.put(WorkflowMultiInstanceVariables.userCollectionName(activityId),
                members.stream().map(Long::valueOf).toList());
        return runtimeService.startProcessInstanceByKey(processKey, all);
    }

    /**
     * 以正式申请人和开始表单快照启动组退回业务流程。
     *
     * @param processKey String，流程定义 key
     * @param startNodeId String，开始节点 ID
     * @param activityId String，受控节点 ID
     * @param members List&lt;String&gt;，有序成员
     * @return ProcessInstance，带运行双状态的真实实例
     */
    ProcessInstance startLifecycle(String processKey, String startNodeId,
            String activityId, List<String> members)
    {
        return startLifecycle(processKey, startNodeId,
                Map.of(activityId, members));
    }

    /**
     * 以正式申请人和开始表单快照启动包含多个连续受控节点的业务流程。
     *
     * @param processKey String，流程定义 key
     * @param startNodeId String，开始节点 ID
     * @param membersByActivity Map&lt;String,List&lt;String&gt;&gt;，各节点服务端成员来源变量
     * @return ProcessInstance，带运行双状态的真实实例
     */
    ProcessInstance startLifecycle(String processKey, String startNodeId,
            Map<String, List<String>> membersByActivity)
    {
        Map<String, Object> variables = new LinkedHashMap<>();
        membersByActivity.forEach((activityId, members) -> variables.put(
                WorkflowMultiInstanceVariables.userCollectionName(activityId),
                members.stream().map(Long::valueOf).toList()));
        variables.put("requestTitle", "原始申请");
        variables.put(WorkflowProcessStartService.PROCESS_STATUS_VARIABLE,
                WorkflowProcessStartService.RUNNING_STATUS);
        variables.put(WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME,
                WorkflowFormSubmissionSnapshotCodec.encodeStart(deploymentId,
                        "TEMPLATE", 1L, "startForm", startNodeId,
                        Map.of("requestTitle", "原始申请")));
        processEngine.getIdentityService().setAuthenticatedUserId(APPLICANT_ID);
        try
        {
            return transactionTemplate.execute(status ->
            {
                ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                        processKey, variables);
                runtimeService.updateBusinessStatus(instance.getId(),
                        WorkflowProcessStartService.RUNNING_STATUS);
                return runtimeService.createProcessInstanceQuery()
                        .processInstanceId(instance.getId()).singleResult();
            });
        }
        finally
        {
            processEngine.getIdentityService().setAuthenticatedUserId(null);
        }
    }

    /**
     * 切换当前生产命令身份。
     *
     * @param userId String，规范用户主键
     * @return void，无返回值
     */
    void asUser(String userId)
    {
        currentUserId.set(userId);
    }

    /**
     * 通过正式多实例完成入口完成成员任务。
     *
     * @param task Task，当前成员任务
     * @param revision int，客户端预期 revision
     * @return void，无返回值
     */
    void complete(Task task, int revision)
    {
        completeLifecycle(task, (long) revision);
    }

    /**
     * 通过正式生命周期入口完成任务。
     *
     * @param task Task，当前任务
     * @param revision Long，多实例预期 revision；普通任务为空
     * @return void，无返回值
     */
    void completeLifecycle(Task task, Long revision)
    {
        asUser(task.getAssignee());
        lifecycleService.completeTask(new WorkflowTaskCompleteRequest(task.getId(),
                "测试完成", Map.of(), List.of(), List.of(), revision));
    }

    /**
     * 完成准备阶段普通任务，使流程进入受控节点。
     *
     * @param task Task，普通活动任务
     * @return void，无返回值
     */
    void completeOrdinary(Task task)
    {
        completeLifecycle(task, null);
    }

    /**
     * 通过正式动态调整入口增加成员。
     *
     * @param task Task，当前办理人任务
     * @param revision int，预期 revision
     * @param userId long，新增成员主键
     * @return void，无返回值
     */
    void addMember(Task task, int revision, long userId)
    {
        asUser(task.getAssignee());
        transactionTemplate.execute(status -> multiInstanceService.adjust(
                new WorkflowMultiInstanceAdjustmentRequest(task.getId(),
                        WorkflowMultiInstanceAdjustmentAction.ADD,
                        (long) revision, "测试加签", List.of(userId), null)));
    }

    /**
     * 通过正式动态调整入口移除成员。
     *
     * @param task Task，当前办理人任务
     * @param target Task，待移除任务
     * @param revision int，预期 revision
     * @return void，无返回值
     */
    void removeMember(Task task, Task target, int revision)
    {
        asUser(task.getAssignee());
        transactionTemplate.execute(status -> multiInstanceService.adjust(
                new WorkflowMultiInstanceAdjustmentRequest(task.getId(),
                        WorkflowMultiInstanceAdjustmentAction.REMOVE,
                        (long) revision, "测试减签", List.of(), target.getId())));
    }

    /**
     * 通过正式任务生命周期入口执行整组退回。
     *
     * @param task Task，退回来源任务
     * @param actor String，真实办理人
     * @return void，无返回值
     */
    void returnGroup(Task task, String actor)
    {
        asUser(actor);
        lifecycleService.returnTask(new WorkflowTaskReturnRequest(
                task.getId(), "整组退回", List.of()));
    }

    /**
     * 使用与正式退回相同的准备链计算 returnAllowed。
     *
     * @param task Task，当前活动任务
     * @param actor String，真实办理人
     * @return boolean，正式业务链是否允许退回
     */
    boolean returnAllowed(Task task, String actor)
    {
        asUser(actor);
        return lifecycleService.isTaskReturnAllowed(task.getId());
    }

    /**
     * 通过正式任务生命周期入口重提申请。
     *
     * @param applicantTask Task，唯一申请人任务
     * @param variables Map&lt;String,Object&gt;，开始表单 patch
     * @return void，无返回值
     */
    void resubmit(Task applicantTask, Map<String, Object> variables)
    {
        asUser(APPLICANT_ID);
        lifecycleService.resubmitApplication(new WorkflowApplicationResubmitRequest(
                applicantTask.getId(), variables));
    }

    /**
     * 通过正式管理员入口终止流程实例。
     *
     * @param processInstanceId String，待终止实例主键
     * @return WorkflowInstanceTerminateView，正式终止结果
     */
    WorkflowInstanceTerminateView terminate(String processInstanceId)
    {
        asUser("999");
        return processInstanceService.terminate(new WorkflowInstanceTerminateRequest(
                processInstanceId, "终止退回待修改流程"));
    }

    /**
     * 使用正式附件服务上传本人临时附件。
     *
     * @return String，新附件主键
     */
    String uploadTemporaryAttachment()
    {
        asUser(APPLICANT_ID);
        return attachmentService.uploadTemporary("evidence", new MockMultipartFile(
                "file", "evidence.txt", "text/plain",
                "test-evidence".getBytes())).attachmentId();
    }

}
