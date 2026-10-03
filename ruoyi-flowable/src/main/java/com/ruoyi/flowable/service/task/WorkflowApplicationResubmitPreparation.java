package com.ruoyi.flowable.service.task;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.model.WorkflowFormSourceType;
import com.ruoyi.flowable.service.process.WorkflowFormSubmissionSnapshotCodec;

/** 申请人重提资格、原办理配置和原表单关联；不处理本次补丁和附件写入。 */
@Service
public class WorkflowApplicationResubmitPreparation
{
    /** 能力投影可安全归一为 false 的既有业务状态码。 */
    private static final Set<Integer> RESUBMIT_INELIGIBLE_STATUS_CODES = Set.of(
            HttpStatus.BAD_REQUEST, HttpStatus.FORBIDDEN,
            HttpStatus.NOT_FOUND, HttpStatus.CONFLICT);

    private final WorkflowEngineOperations engineOperations;
    private final WorkflowTaskRuntimeReader runtimeReader;
    private final WorkflowReturnedTaskStateService returnedTaskStateService;
    private final WorkflowMultiInstanceGroupTransitionService groupTransitionService;
    private final WorkflowDeploymentArtifactRepository artifactRepository;
    private final RuntimeService runtimeService;

    public WorkflowApplicationResubmitPreparation(
            WorkflowEngineOperations engineOperations,
            WorkflowTaskRuntimeReader runtimeReader,
            WorkflowReturnedTaskStateService returnedTaskStateService,
            WorkflowMultiInstanceGroupTransitionService groupTransitionService,
            WorkflowDeploymentArtifactRepository artifactRepository,
            RuntimeService runtimeService)
    {
        this.engineOperations = engineOperations;
        this.runtimeReader = runtimeReader;
        this.returnedTaskStateService = returnedTaskStateService;
        this.groupTransitionService = groupTransitionService;
        this.artifactRepository = artifactRepository;
        this.runtimeService = runtimeService;
    }

    /** 同次详情事务复用已读取对象，正式写入入口仍自行获取当前任务和实例。 */
    public boolean isApplicationResubmitAllowed(
            WorkflowReturnedTaskStateService.ReturnedApplicant applicant,
            WorkflowCurrentIdentity actor, WorkflowTaskBpmnSnapshot bpmn, List<Task> activeTasks,
            Supplier<List<WfDeployForm>> forms, Supplier<List<HistoricActivityInstance>> activities)
    {
        if (applicant == null || applicant.task().isSuspended() || applicant.instance().isSuspended())
        {
            return false;
        }
        return engineOperations.readWithServiceExceptionHandler(() ->
        {
            Task task = applicant.task();
            ProcessInstance instance = applicant.instance();
            runtimeReader.requireCurrentAssignee(task, actor);
            prepareResubmit(task, instance,
                    groupTransitionService.prepareGroupReopen(task, instance, actor.userId(), bpmn, activeTasks),
                    () -> requireStartFormSnapshot(runtimeReader.requireStartActivity(activities.get()), forms.get()));
            return true;
        }, exception ->
        {
            if (RESUBMIT_INELIGIBLE_STATUS_CODES.contains(exception.getCode()))
            {
                return false;
            }
            throw exception;
        });
    }

    /**
     * 在当前事务快照内冻结写命令与能力查询共用的唯一无副作用重提准备结果。
     * @param taskId String，已经过格式校验的申请人待修改任务主键
     * @param actor WorkflowCurrentIdentity，事务内重新核验的正式当前身份
     * @return ResubmitPreparation，任务、实例、办理配置、重开计划、部署表单和原提交快照
     */
    ResubmitPreparation requireResubmitPreparation(String taskId,
            WorkflowCurrentIdentity actor)
    {
        Task task = runtimeReader.requireActiveTask(taskId);
        ProcessInstance instance = runtimeReader.requireActiveProcessInstance(
                task.getProcessInstanceId());
        runtimeReader.requireCurrentAssignee(task, actor);
        returnedTaskStateService.requireReturnedApplicant(task, instance, actor.userId());
        return prepareResubmit(task, instance,
                groupTransitionService.prepareGroupReopen(task, instance, actor.userId()),
                () -> requireStartFormSnapshot(runtimeReader.requireStartActivity(task.getProcessInstanceId()),
                        artifactRepository.openRead(instance.getDeploymentId()).forms()));
    }

    private ResubmitPreparation prepareResubmit(Task task, ProcessInstance instance,
            MultiInstanceGroupReopenPlan groupPlan, Supplier<WfDeployForm> form)
    {
        // 普通退回和整组退回都会冻结迁移后首审批任务配置；重提前必须先验证该快照存在。
        ReturnedAssignmentSnapshot assignment =
                returnedTaskStateService.requireOrdinaryAssignment(task.getId());
        WfDeployForm startForm = form.get();
        WorkflowFormSubmissionSnapshotCodec.SubmissionSnapshot previous =
                requireStartSubmissionSnapshot(instance, startForm);
        return new ResubmitPreparation(task, instance, assignment, groupPlan,
                startForm, previous);
    }

    /**
     * 查询实例真实执行的开始节点对应部署表单快照。
     * @param start HistoricActivityInstance，真实开始活动
     * @param snapshots List&lt;WfDeployForm&gt;，本次部署表单
     * @return WfDeployForm，原部署开始表单快照
     */
    private WfDeployForm requireStartFormSnapshot(HistoricActivityInstance start, List<WfDeployForm> snapshots)
    {
        if (snapshots == null)
        {
            throw dataError();
        }
        List<WfDeployForm> matches = snapshots.stream()
                .filter(snapshot -> snapshot != null
                        && start.getActivityId().equals(snapshot.getNodeKey()))
                .toList();
        if (matches.size() != 1
                || !WorkflowFormSourceType.isConsistent(
                        matches.get(0).getSourceType(), matches.get(0).getFormId())
                || !StringUtils.hasText(matches.get(0).getContent()))
        {
            throw conflict();
        }
        return matches.get(0);
    }

    /**
     * 读取并核验实例上一份正式开始提交快照。
     * @param instance ProcessInstance，当前退回修改实例
     * @param startForm WfDeployForm，原部署开始表单快照
     * @return SubmissionSnapshot，与部署和节点强关联的旧提交快照
     */
    private WorkflowFormSubmissionSnapshotCodec.SubmissionSnapshot
            requireStartSubmissionSnapshot(ProcessInstance instance,
                    WfDeployForm startForm)
    {
        Object rawSnapshot = runtimeService.getVariable(instance.getId(),
                WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME);
        if (!(rawSnapshot instanceof String encoded)
                || !StringUtils.hasText(encoded))
        {
            throw dataError();
        }
        WorkflowFormSubmissionSnapshotCodec.SubmissionSnapshot previous =
                WorkflowFormSubmissionSnapshotCodec.decode(encoded);
        if (previous.kind() != WorkflowFormSubmissionSnapshotCodec.SnapshotKind.START
                || !Objects.equals(instance.getDeploymentId(), previous.deploymentId())
                || !Objects.equals(startForm.getSourceType(), previous.sourceType())
                || !Objects.equals(startForm.getFormId(), previous.formId())
                || !Objects.equals(startForm.getFormKey(), previous.formKey())
                || !Objects.equals(startForm.getNodeKey(), previous.nodeKey()))
        {
            throw dataError();
        }
        return previous;
    }

    /** @return ServiceException，稳定 HTTP 409。 */
    private ServiceException conflict()
    {
        return new ServiceException("工作流状态已发生变化，请刷新后重试",
                HttpStatus.CONFLICT);
    }

    /** @return ServiceException，稳定 HTTP 500。 */
    private ServiceException dataError()
    {
        return new ServiceException("工作流对象关联数据异常", HttpStatus.ERROR);
    }

    /** 写命令和 capability 共用的无副作用正式重提准备快照。 */
    record ResubmitPreparation(Task task, ProcessInstance instance,
            ReturnedAssignmentSnapshot assignment,
            MultiInstanceGroupReopenPlan groupPlan,
            WfDeployForm startForm,
            WorkflowFormSubmissionSnapshotCodec.SubmissionSnapshot previousSubmission)
    {
        /** @return 无返回值，构造时拒绝缺失的正式重提事实。 */
        ResubmitPreparation
        {
            Objects.requireNonNull(task, "重提任务不能为空");
            Objects.requireNonNull(instance, "重提流程实例不能为空");
            Objects.requireNonNull(assignment, "退回办理配置不能为空");
            Objects.requireNonNull(startForm, "部署开始表单不能为空");
            Objects.requireNonNull(previousSubmission, "原开始提交快照不能为空");
        }
    }
}
