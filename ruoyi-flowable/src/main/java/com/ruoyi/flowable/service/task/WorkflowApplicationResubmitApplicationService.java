package com.ruoyi.flowable.service.task;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.dto.WorkflowApplicationResubmitRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentService;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;
import com.ruoyi.flowable.service.process.WorkflowFormSubmissionSnapshotCodec;
import com.ruoyi.flowable.service.process.WorkflowStartVariableValidator;
import com.ruoyi.flowable.service.process.WorkflowValidatedStartVariables;

import com.ruoyi.flowable.service.task.WorkflowApplicationResubmitPreparation.ResubmitPreparation;

/** 普通申请重提与受控 ALL/ANY 完整审批组重建的唯一应用命令服务。 */
@Service
public class WorkflowApplicationResubmitApplicationService
{
    /** 重新提交由系统生成的稳定审计说明。 */
    private static final String RESUBMIT_AUDIT_OPINION =
            "申请人修改原表单后重新提交";

    private final WorkflowApplicationResubmitPreparation preparation;
    private final WorkflowEngineOperations engineOperations;
    private final WorkflowTaskRuntimeReader runtimeReader;
    private final WorkflowReturnedTaskStateService returnedTaskStateService;
    private final WorkflowTaskActionAuditWriter auditWriter;
    private final WorkflowTaskConcurrencyExecutor concurrencyExecutor;
    private final WorkflowMultiInstanceGroupTransitionService groupTransitionService;
    private final WorkflowStartVariableValidator variableValidator;
    private final WorkflowAttachmentService attachmentService;
    private final WorkflowNotificationService notificationService;
    private final RuntimeService runtimeService;

    /**
     * 创建独立申请重提应用服务。
     * 构造后由 Spring 管理
     */
    public WorkflowApplicationResubmitApplicationService(
            WorkflowEngineOperations engineOperations,
            WorkflowApplicationResubmitPreparation preparation,
            WorkflowTaskRuntimeReader runtimeReader,
            WorkflowReturnedTaskStateService returnedTaskStateService,
            WorkflowTaskActionAuditWriter auditWriter,
            WorkflowTaskConcurrencyExecutor concurrencyExecutor,
            WorkflowMultiInstanceGroupTransitionService groupTransitionService,
            WorkflowStartVariableValidator variableValidator,
            WorkflowAttachmentService attachmentService,
            WorkflowNotificationService notificationService,
            RuntimeService runtimeService)
    {
        this.engineOperations = engineOperations;
        this.preparation = preparation;
        this.runtimeReader = runtimeReader;
        this.returnedTaskStateService = returnedTaskStateService;
        this.auditWriter = auditWriter;
        this.concurrencyExecutor = concurrencyExecutor;
        this.groupTransitionService = groupTransitionService;
        this.variableValidator = variableValidator;
        this.attachmentService = attachmentService;
        this.notificationService = notificationService;
        this.runtimeService = runtimeService;
    }

    /**
     * 保存修改后的原申请表并恢复普通办理配置或重建完整多实例审批组。
     * @param request WorkflowApplicationResubmitRequest，已通过 DTO Bean Validation 的退回任务和开始表单补丁
     * @return 无返回值，表单、附件、状态、审计和重建在同一事务提交
     */
    public void resubmitApplication(WorkflowApplicationResubmitRequest request)
    {
        String taskId = request.taskId().trim();
        engineOperations.writeAsCurrentUser(actor ->
        {
            ResubmitPreparation preparation = this.preparation.requireResubmitPreparation(taskId, actor);
            ResubmitFormPlan formPlan = prepareFormPlan(
                    request, preparation, actor.userId());
            concurrencyExecutor.execute(() -> executeResubmit(
                    preparation.task(), preparation.instance(), actor.userId(),
                    preparation.assignment(), preparation.groupPlan(), formPlan));
            return null;
        });
    }

    /**
     * 校验开始表单快照、补丁和附件引用并冻结完整写计划。
     * @param request WorkflowApplicationResubmitRequest，已通过 DTO Bean Validation 的重提请求
     * @param preparation ResubmitPreparation，无副作用正式准备结果
     * @param applicantUserId String，流程正式发起人主键
     * @return ResubmitFormPlan，部署表单、补丁、附件和完整提交快照
     */
    private ResubmitFormPlan prepareFormPlan(WorkflowApplicationResubmitRequest request,
            ResubmitPreparation preparation, String applicantUserId)
    {
        WfDeployForm startForm = preparation.startForm();
        WorkflowFormSubmissionSnapshotCodec.SubmissionSnapshot previous =
                preparation.previousSubmission();
        WorkflowValidatedStartVariables validated = variableValidator.validatePatch(
                startForm.getContent(), request.variables(), previous.values());
        Map<String, Object> projected = attachmentService.prepareTaskVariables(
                applicantUserId, preparation.instance().getId(), validated.variables(),
                validated.attachmentIdsByField());
        return new ResubmitFormPlan(startForm, validated, projected,
                mergeStartSubmissionValues(previous.values(), projected));
    }

    /**
     * 按冻结副作用顺序写入附件、表单、审计并执行普通或整组重提。
     * @param task Task，申请人待修改任务
     * @param instance ProcessInstance，真实活动实例
     * @param applicantUserId String，流程正式发起人主键
     * @param assignment ReturnedAssignmentSnapshot，普通退回办理配置；整组时为空
     * @param groupPlan MultiInstanceGroupReopenPlan，整组重建计划；普通时为空
     * @param formPlan ResubmitFormPlan，已校验表单和附件写计划
     * @return 无返回值，任一步失败由外层事务回滚
     */
    private void executeResubmit(Task task, ProcessInstance instance,
            String applicantUserId, ReturnedAssignmentSnapshot assignment,
            MultiInstanceGroupReopenPlan groupPlan, ResubmitFormPlan formPlan)
    {
        bindFormAndAttachments(task, instance, applicantUserId, formPlan);
        auditWriter.write(task, "RESUBMIT",
                applicantUserId, RESUBMIT_AUDIT_OPINION,
                task.getTaskDefinitionKey(), null);
        returnedTaskStateService.markTransition(instance.getId(),
                WorkflowReturnedApplicationProtocol.RESUBMIT_TRANSITION_MARKER);
        if (groupPlan == null)
        {
            returnedTaskStateService.restoreOrdinary(task.getId(), instance.getId(),
                    Objects.requireNonNull(assignment));
            notificationService.onStableTaskEvent("TASK_RESUBMITTED", task);
        }
        else if (groupPlan.application().sourceKind()
                == ReturnedApplicationSnapshot.SourceKind.ORDINARY_EXECUTION)
        {
            // 来源轮次只作为退回审计关闭；当前任务就是首审批任务，严禁再跳回来源多实例节点。
            groupTransitionService.reopenAtOrdinaryFirst(groupPlan,
                    Objects.requireNonNull(assignment), applicantUserId);
            notificationService.onStableTaskEvent("TASK_RESUBMITTED", task);
        }
        else
        {
            executeGroupResubmit(task, instance, applicantUserId, groupPlan);
        }
        returnedTaskStateService.clearTransition(instance.getId());
    }

    /**
     * 保持受控重提的撤销通知、旧轮 CAS、状态、Flowable、SLA 和写后对账顺序。
     * @param task Task，唯一申请人待修改任务
     * @param instance ProcessInstance，真实活动实例
     * @param applicantUserId String，流程正式发起人主键
     * @param groupPlan MultiInstanceGroupReopenPlan，冻结轮次和迁移来源
     * @return 无返回值，任一步失败由外层事务回滚
     */
    private void executeGroupResubmit(Task task, ProcessInstance instance,
            String applicantUserId, MultiInstanceGroupReopenPlan groupPlan)
    {
        notificationService.onTasksWithdrawn(instance.getId(), List.of(task.getId()));
        WorkflowMultiInstanceGroupTransitionService.GroupReopenResult result =
                groupTransitionService.reopenGroup(groupPlan, applicantUserId);
        for (String activeTaskId : result.activeTaskIds())
        {
            notificationService.onStableTaskEvent("TASK_RESUBMITTED",
                    runtimeReader.requireActiveTask(activeTaskId));
        }
    }

    /**
     * 按冻结顺序绑定附件并写入流程变量和完整开始提交快照。
     * @param task Task，申请人待修改任务
     * @param instance ProcessInstance，真实活动实例
     * @param applicantUserId String，流程正式发起人主键
     * @param formPlan ResubmitFormPlan，已校验表单计划
     * @return 无返回值，任一步失败由外层事务回滚
     */
    private void bindFormAndAttachments(Task task, ProcessInstance instance,
            String applicantUserId, ResubmitFormPlan formPlan)
    {
        WfDeployForm form = formPlan.startForm();
        attachmentService.bindTaskAttachments(applicantUserId, instance.getId(),
                task.getId(), form.getNodeKey(),
                formPlan.validated().attachmentIdsByField());
        if (!formPlan.projectedPatch().isEmpty())
        {
            runtimeService.setVariables(instance.getId(), formPlan.projectedPatch());
        }
        runtimeService.setVariable(instance.getId(),
                WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME,
                WorkflowFormSubmissionSnapshotCodec.encodeStart(
                        instance.getDeploymentId(), form.getSourceType(),
                        form.getFormId(), form.getFormKey(), form.getNodeKey(),
                        formPlan.mergedValues()));
    }

    /**
     * 合并旧开始快照与本次合法字段补丁。
     * @param previousValues Map&lt;String,JsonNode&gt;，旧提交快照字段
     * @param projectedPatch Map&lt;String,Object&gt;，本次合法字段补丁
     * @return Map&lt;String,Object&gt;，不可修改完整合并值
     */
    private Map<String, Object> mergeStartSubmissionValues(
            Map<String, JsonNode> previousValues,
            Map<String, Object> projectedPatch)
    {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        previousValues.forEach((fieldName, value) -> merged.put(fieldName,
                value == null ? null : value.deepCopy()));
        merged.putAll(projectedPatch);
        return Collections.unmodifiableMap(merged);
    }

    /** 重提命令写入前冻结的表单、附件和完整提交快照。 */
    private record ResubmitFormPlan(WfDeployForm startForm,
            WorkflowValidatedStartVariables validated,
            Map<String, Object> projectedPatch,
            Map<String, Object> mergedValues)
    {
        /** @return 无返回值，构造时冻结两个变量映射。 */
        private ResubmitFormPlan
        {
            projectedPatch = Map.copyOf(projectedPatch);
            mergedValues = Collections.unmodifiableMap(
                    new LinkedHashMap<>(mergedValues));
        }
    }
}
