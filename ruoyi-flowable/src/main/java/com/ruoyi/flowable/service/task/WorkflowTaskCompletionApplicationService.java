package com.ruoyi.flowable.service.task;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.DelegationState;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.dto.WorkflowTaskCompleteRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentService;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.model.WorkflowFormSourceType;
import com.ruoyi.flowable.service.process.WorkflowFormSubmissionSnapshotCodec;
import com.ruoyi.flowable.service.process.WorkflowStartVariableValidator;
import com.ruoyi.flowable.service.process.WorkflowValidatedStartVariables;

/**
 * 普通和受控多实例任务完成、表单、附件、抄送、审计及写后对账应用服务。
 */
@Service
public class WorkflowTaskCompletionApplicationService
{
    private final WorkflowEngineOperations engineOperations;
    private final WorkflowTaskRequestValidator requestValidator;
    private final WorkflowTaskRuntimeReader runtimeReader;
    private final WorkflowTaskBpmnReader bpmnReader;
    private final WorkflowDeploymentArtifactRepository artifactRepository;
    private final WorkflowStartVariableValidator variableValidator;
    private final WorkflowAttachmentService attachmentService;
    private final WorkflowTaskCopyService taskCopyService;
    private final WorkflowNextTaskAssignmentService nextTaskAssignmentService;
    private final WorkflowMultiInstanceService multiInstanceService;
    private final WorkflowControlledLoopService controlledLoopService;
    private final WorkflowTaskActionAuditWriter auditWriter;
    private final WorkflowTaskConcurrencyExecutor concurrencyExecutor;
    private final RuntimeService runtimeService;
    private final TaskService taskService;

    /**
     * 创建任务完成应用服务。
     *
     * @param engineOperations WorkflowEngineOperations，正式事务和异常翻译入口
     * @param requestValidator WorkflowTaskRequestValidator，请求字段门禁
     * @param runtimeReader WorkflowTaskRuntimeReader，活动任务和实例事实
     * @param bpmnReader WorkflowTaskBpmnReader，部署 BPMN 事实
     * @param artifactRepository WorkflowDeploymentArtifactRepository，部署表单快照仓库
     * @param variableValidator WorkflowStartVariableValidator，部署 schema 变量门禁
     * @param attachmentService WorkflowAttachmentService，附件投影和绑定服务
     * @param taskCopyService WorkflowTaskCopyService，完成抄送计划和持久化服务
     * @param nextTaskAssignmentService WorkflowNextTaskAssignmentService，后继办理人计划服务
     * @param multiInstanceService WorkflowMultiInstanceService，多实例 revision 和写后对账服务
     * @param controlledLoopService WorkflowControlledLoopService，受控循环完成决策服务
     * @param auditWriter WorkflowTaskActionAuditWriter，结构化完成审计写入器
     * @param concurrencyExecutor WorkflowTaskConcurrencyExecutor，并发对象消失翻译器
     * @param runtimeService RuntimeService，流程变量读写服务
     * @param taskService TaskService，任务变量、完成和历史归档服务
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowTaskCompletionApplicationService(
            WorkflowEngineOperations engineOperations,
            WorkflowTaskRequestValidator requestValidator,
            WorkflowTaskRuntimeReader runtimeReader,
            WorkflowTaskBpmnReader bpmnReader,
            WorkflowDeploymentArtifactRepository artifactRepository,
            WorkflowStartVariableValidator variableValidator,
            WorkflowAttachmentService attachmentService,
            WorkflowTaskCopyService taskCopyService,
            WorkflowNextTaskAssignmentService nextTaskAssignmentService,
            WorkflowMultiInstanceService multiInstanceService,
            WorkflowControlledLoopService controlledLoopService,
            WorkflowTaskActionAuditWriter auditWriter,
            WorkflowTaskConcurrencyExecutor concurrencyExecutor,
            RuntimeService runtimeService, TaskService taskService)
    {
        this.engineOperations = engineOperations;
        this.requestValidator = requestValidator;
        this.runtimeReader = runtimeReader;
        this.bpmnReader = bpmnReader;
        this.artifactRepository = artifactRepository;
        this.variableValidator = variableValidator;
        this.attachmentService = attachmentService;
        this.taskCopyService = taskCopyService;
        this.nextTaskAssignmentService = nextTaskAssignmentService;
        this.multiInstanceService = multiInstanceService;
        this.controlledLoopService = controlledLoopService;
        this.auditWriter = auditWriter;
        this.concurrencyExecutor = concurrencyExecutor;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
    }

    /**
     * 由当前办理人使用不可变部署表单 schema 校验变量后完成活动任务。
     *
     * @param request WorkflowTaskCompleteRequest，任务、审批意见和表单变量
     * @return 无返回值，全部任务、副作用和写后对账在同一事务提交
     */
    public void completeTask(WorkflowTaskCompleteRequest request)
    {
        if (request == null)
        {
            throw requestValidator.invalidArgument();
        }
        String taskId = requestValidator.requireId(request.taskId());
        String opinion = requestValidator.requireOpinion(request.comment());
        try
        {
            engineOperations.writeAsCurrentUser(actor ->
            {
                Task task = runtimeReader.requireActiveTask(taskId);
                runtimeReader.requireActiveProcessInstance(task.getProcessInstanceId());
                runtimeReader.requireCurrentAssignee(task, actor);
                if (task.getDelegationState() == DelegationState.PENDING)
                {
                    throw conflict();
                }
                CompletionPreparation preparation = requireCompletionPreparation(
                        task, request.variables());
                Map<String, Object> projectedVariables =
                        attachmentService.prepareTaskVariables(actor.userId(),
                                task.getProcessInstanceId(), preparation.values(),
                                preparation.attachmentIdsByField());
                WorkflowTaskCopyService.CopyPlan copyPlan = taskCopyService.prepare(
                        WorkflowTaskCopyAction.COMPLETE, task, actor,
                        request.copyUserIds());
                WorkflowNextTaskAssignmentService.AssignmentPlan assignmentPlan =
                        nextTaskAssignmentService.prepare(task,
                                preparation.bpmnContext().process(),
                                preparation.currentUserTask(), request.nextUserIds());
                concurrencyExecutor.execute(() ->
                {
                    // Flowable 根 revision 必须先占用，随后才执行审计、附件和 task complete。
                    WorkflowMultiInstanceService.CompletionRevision completionRevision =
                            multiInstanceService.reserveCompletionRevision(task,
                                    preparation.currentUserTask(),
                                    request.expectedRevision());
                    controlledLoopService.prepareCompletion(task,
                            preparation.bpmnContext().definition().getKey(),
                            preparation.bpmnContext().definition().getDeploymentId(),
                            projectedVariables, actor.userId());
                    auditWriter.writeCompletion(task, actor.userId(), opinion,
                            completionRevision);
                    attachmentService.bindTaskAttachments(actor.userId(),
                            task.getProcessInstanceId(), taskId,
                            task.getTaskDefinitionKey(),
                            preparation.attachmentIdsByField());
                    if (preparation.formSnapshot() != null)
                    {
                        Map<String, Object> submissionValues = buildTaskSubmissionValues(
                                task, preparation, projectedVariables);
                        String submissionSnapshot =
                                WorkflowFormSubmissionSnapshotCodec.encodeTask(
                                        preparation.bpmnContext().definition()
                                                .getDeploymentId(),
                                        preparation.formSnapshot().getSourceType(),
                                        preparation.formSnapshot().getFormId(),
                                        preparation.formSnapshot().getFormKey(),
                                        preparation.formSnapshot().getNodeKey(), taskId,
                                        preparation.localScope(), submissionValues);
                        taskService.setVariableLocal(taskId,
                                WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME,
                                submissionSnapshot);
                    }
                    taskService.complete(taskId, actor.userId(), projectedVariables,
                            preparation.localScope());
                    multiInstanceService.verifyCompletionResult(
                            task.getProcessInstanceId(), completionRevision);
                    nextTaskAssignmentService.apply(assignmentPlan);
                    taskCopyService.persist(copyPlan);
                });
                return null;
            });
        }
        catch (RuntimeException exception)
        {
            if (request.expectedRevision() == null)
            {
                throw exception;
            }
            throw engineOperations.withConcurrencyConflictSubCode(exception,
                    WorkflowMultiInstanceService.REVISION_CONFLICT_SUB_CODE);
        }
    }

    /**
     * 合成任务完成时完整可读值，拒绝把隐藏字段写入内部快照。
     *
     * @param task Task，当前真实活动任务
     * @param preparation CompletionPreparation，部署表单、作用域和合法写字段计划
     * @param projectedVariables Map&lt;String,Object&gt;，附件投影后的合法补丁
     * @return Map&lt;String,Object&gt;，按部署表单顺序合并的不可修改映射
     */
    private Map<String, Object> buildTaskSubmissionValues(Task task,
            CompletionPreparation preparation,
            Map<String, Object> projectedVariables)
    {
        Set<String> readableNames = variableValidator.readableFieldNames(
                preparation.formSnapshot().getContent());
        Map<String, Object> currentValues = preparation.localScope()
                ? taskService.getVariablesLocal(task.getId(), readableNames)
                : runtimeService.getVariables(task.getProcessInstanceId(), readableNames);
        if (currentValues == null)
        {
            throw dataError();
        }
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        for (String fieldName : readableNames)
        {
            if (currentValues.containsKey(fieldName))
            {
                merged.put(fieldName, currentValues.get(fieldName));
            }
        }
        merged.putAll(projectedVariables);
        return Collections.unmodifiableMap(merged);
    }

    /**
     * 一次装载部署 BPMN 和当前节点，并按部署表单 schema 校验完成变量。
     *
     * @param task Task，已通过活动态和办理人校验的任务
     * @param requestedVariables Map&lt;String,Object&gt;，客户端表单变量
     * @return CompletionPreparation，不可变 BPMN、表单和变量准备结果
     */
    private CompletionPreparation requireCompletionPreparation(Task task,
            Map<String, Object> requestedVariables)
    {
        WorkflowTaskBpmnSnapshot context = bpmnReader.require(
                task.getProcessDefinitionId());
        FlowElement element = context.process().getFlowElement(
                task.getTaskDefinitionKey(), true);
        if (!(element instanceof UserTask userTask))
        {
            throw dataError();
        }
        Map<String, Object> variables = requestedVariables == null
                ? Map.of() : requestedVariables;
        String formKey = resolveFormKey(userTask);
        if (formKey == null)
        {
            if (!variables.isEmpty())
            {
                throw requestValidator.invalidArgument();
            }
            return new CompletionPreparation(context, userTask, Map.of(), Map.of(),
                    isTaskLocal(userTask), null);
        }
        List<WfDeployForm> snapshots = artifactRepository.selectForms(
                context.definition().getDeploymentId());
        if (snapshots == null)
        {
            throw dataError();
        }
        List<WfDeployForm> matched = snapshots.stream()
                .filter(snapshot -> snapshot != null
                        && task.getTaskDefinitionKey().equals(snapshot.getNodeKey())
                        && formKey.equals(snapshot.getFormKey())).toList();
        if (matched.size() != 1
                || !WorkflowFormSourceType.isConsistent(
                        matched.get(0).getSourceType(), matched.get(0).getFormId())
                || !StringUtils.hasText(matched.get(0).getContent()))
        {
            throw dataError();
        }
        WorkflowValidatedStartVariables validated = variableValidator.validateForStart(
                matched.get(0).getContent(), variables);
        return new CompletionPreparation(context, userTask, validated.variables(),
                validated.attachmentIdsByField(), isTaskLocal(userTask), matched.get(0));
    }

    /**
     * 解析用户任务正式模板或 BPMN 内嵌表单键。
     *
     * @param userTask UserTask，当前 BPMN 用户任务
     * @return String，正式 formKey、内嵌稳定键或无表单时的 null
     */
    private String resolveFormKey(UserTask userTask)
    {
        if (StringUtils.hasText(userTask.getFormKey()))
        {
            return userTask.getFormKey();
        }
        boolean hasEmbedded = userTask.getFormProperties() != null
                && !userTask.getFormProperties().isEmpty();
        return hasEmbedded ? WorkflowFormSourceType.EMBEDDED_FORM_KEY : null;
    }

    /**
     * 读取用户任务 Flowable localScope 扩展属性。
     *
     * @param userTask UserTask，当前 BPMN 用户任务
     * @return boolean，属性值为 true 或 1 时返回 true
     */
    private boolean isTaskLocal(UserTask userTask)
    {
        Map<String, List<ExtensionAttribute>> attributes = userTask.getAttributes();
        if (attributes == null)
        {
            return false;
        }
        List<ExtensionAttribute> localScope = attributes.get("localScope");
        if (localScope == null || localScope.isEmpty() || localScope.get(0) == null)
        {
            return false;
        }
        String value = localScope.get(0).getValue();
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    /**
     * 创建稳定状态冲突错误。
     *
     * @return ServiceException，既有 HTTP 409 错误
     */
    private ServiceException conflict()
    {
        return new ServiceException("工作流状态已发生变化，请刷新后重试", HttpStatus.CONFLICT);
    }

    /**
     * 创建稳定关联数据错误。
     *
     * @return ServiceException，既有 HTTP 500 错误
     */
    private ServiceException dataError()
    {
        return new ServiceException("工作流对象关联数据异常", HttpStatus.ERROR);
    }

    /** 完成写链一次装载并冻结的部署、表单和变量事实。 */
    private record CompletionPreparation(WorkflowTaskBpmnSnapshot bpmnContext,
            UserTask currentUserTask, Map<String, Object> values,
            Map<String, List<String>> attachmentIdsByField,
            boolean localScope, WfDeployForm formSnapshot)
    {
        /**
         * 复制变量和两层附件集合，禁止后续步骤替换已校验事实。
         *
         * @param bpmnContext WorkflowTaskBpmnSnapshot，部署 BPMN 事实
         * @param currentUserTask UserTask，当前 BPMN 节点
         * @param values Map&lt;String,Object&gt;，schema 校验后的变量
         * @param attachmentIdsByField Map&lt;String,List&lt;String&gt;&gt;，字段附件引用
         * @param localScope boolean，任务变量是否使用局部作用域
         * @param formSnapshot WfDeployForm，部署表单快照；无表单时为空
         * @return 无返回值，构造后集合不可修改
         */
        private CompletionPreparation
        {
            Objects.requireNonNull(bpmnContext);
            Objects.requireNonNull(currentUserTask);
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            LinkedHashMap<String, List<String>> copied = new LinkedHashMap<>();
            attachmentIdsByField.forEach((field, ids) ->
                    copied.put(field, List.copyOf(ids)));
            attachmentIdsByField = Collections.unmodifiableMap(copied);
        }
    }
}
