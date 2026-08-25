package com.ruoyi.flowable.service.process;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.authorization.WorkflowTaskAccessSnapshot;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.vo.WorkflowProcessFormSnapshotView;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.model.WorkflowFormSourceType;
import com.ruoyi.flowable.service.process.WorkflowFormSubmissionSnapshotCodec.SnapshotKind;
import com.ruoyi.flowable.service.process.WorkflowFormSubmissionSnapshotCodec.SubmissionSnapshot;
import com.ruoyi.flowable.service.process.WorkflowProcessVariableProjection.ProjectedValues;
import com.ruoyi.flowable.service.process.WorkflowProcessVariableProjection.StoredSubmission;
import com.ruoyi.flowable.service.process.WorkflowProcessVariableProjection.VariableStore;

/**
 * 已授权流程详情的部署表单 schema、历史提交表单和当前任务表单投影组件。
 *
 * 该组件只消费详情服务已核验的流程、任务、历史和变量上下文；它维护表单与变量响应预算，
 * 但不建立事务、不执行对象授权，也不绕过变量投影组件的存储安全边界。
 */
@Component
public class WorkflowProcessFormDetailProjection
{
    /** 单个详情允许读取的最大部署表单快照数。 */
    static final int MAX_FORM_SNAPSHOTS = 500;

    /** 单个详情重复序列化后的表单快照正文最大字节数。 */
    static final int MAX_TOTAL_FORM_BYTES = 4 * 1024 * 1024;

    /** 单个详情全部回显变量 JSON 最大字节数。 */
    static final int MAX_TOTAL_VARIABLE_BYTES = 1024 * 1024;

    private final WorkflowDeploymentArtifactRepository artifactRepository;

    private final WorkflowFormTemplateValidator formTemplateValidator;

    private final WorkflowProcessVariableProjection variableProjection;

    /**
     * 创建流程详情表单投影组件。
     *
     * @param artifactRepository WorkflowDeploymentArtifactRepository，不可变部署表单资源仓库
     * @param formTemplateValidator WorkflowFormTemplateValidator，表单 schema 安全解析器
     * @param variableProjection WorkflowProcessVariableProjection，变量存储解码与安全投影组件
     * @return 无返回值，构造后由 Spring 管理该组件
     */
    public WorkflowProcessFormDetailProjection(
            WorkflowDeploymentArtifactRepository artifactRepository,
            WorkflowFormTemplateValidator formTemplateValidator,
            WorkflowProcessVariableProjection variableProjection)
    {
        this.artifactRepository = artifactRepository;
        this.formTemplateValidator = formTemplateValidator;
        this.variableProjection = variableProjection;
    }

    /**
     * 一次性读取部署表单快照，验证 schema 并建立节点与表单键联合索引。
     *
     * @param deploymentId String，实例所属真实部署主键
     * @return FormSchemas，不可变快照及字段白名单索引
     */
    FormSchemas loadSchemas(String deploymentId)
    {
        List<WfDeployForm> rows = artifactRepository.selectForms(deploymentId);
        if (rows == null || rows.size() > MAX_FORM_SNAPSHOTS)
        {
            throw dataError("部署表单快照数量异常");
        }
        Map<NodeFormKey, SnapshotSchema> indexed = new LinkedHashMap<>();
        for (WfDeployForm row : rows)
        {
            if (row == null || !deploymentId.equals(row.getDeployId())
                    || !WorkflowFormSourceType.isConsistent(row.getSourceType(), row.getFormId())
                    || !StringUtils.hasText(row.getNodeKey())
                    || !StringUtils.hasText(row.getFormKey())
                    || !StringUtils.hasText(row.getContent()))
            {
                throw dataError("部署表单快照关联数据异常");
            }
            Set<String> variableNames = formTemplateValidator.extractVariableNames(row.getContent());
            Set<String> readableVariableNames =
                    formTemplateValidator.extractReadableVariableNames(row.getContent());
            NodeFormKey key = new NodeFormKey(row.getNodeKey(), row.getFormKey());
            if (indexed.putIfAbsent(key,
                    new SnapshotSchema(row, variableNames, readableVariableNames)) != null)
            {
                throw dataError("部署表单快照节点关系不唯一");
            }
        }
        return new FormSchemas(Collections.unmodifiableMap(indexed));
    }

    /**
     * 使用同一响应预算生成历史表单列表和请求任务表单。
     *
     * @param request FormProjectionRequest，已授权且完成定义、历史、变量关联门禁的投影上下文
     * @return FormProjection，历史表单列表与可选当前任务表单
     */
    FormProjection project(FormProjectionRequest request)
    {
        DetailResponseBudget budget = new DetailResponseBudget();
        List<WorkflowProcessFormSnapshotView> processForms = buildExecutedForms(
                request.history().activities(), request.history().tasksById(), request.process(),
                request.schemas().schemas(), request.variables(), request.deploymentId(), budget);
        requireAtMostOneApplicationForm(processForms);
        WorkflowProcessFormSnapshotView currentTaskForm = request.requestedTask() == null ? null
                : buildCurrentTaskForm(request.requestedTask(), request.history().tasksById(),
                        request.process(), request.schemas().schemas(), request.variables(),
                        request.deploymentId(), request.currentTaskControlledLoop(),
                        request.returnedApplication() ? processForms : null, budget);
        return new FormProjection(currentTaskForm, processForms);
    }

    /**
     * 按历史活动顺序构建具有合法不可变提交快照的开始节点和已完成任务表单。
     *
     * @param activities List&lt;HistoricActivityInstance&gt;，有界历史活动
     * @param tasksById Map&lt;String, HistoricTaskInstance&gt;，真实历史任务主键索引
     * @param process org.flowable.bpmn.model.Process，目标定义的 BPMN 流程
     * @param snapshots Map&lt;NodeFormKey, SnapshotSchema&gt;，部署表单快照索引
     * @param variables VariableStore，正式提交快照索引
     * @param deploymentId String，流程实例所属部署主键
     * @param budget DetailResponseBudget，详情累计正文大小预算
     * @return List&lt;WorkflowProcessFormSnapshotView&gt;，仅包含可证明提交值的历史表单
     */
    private List<WorkflowProcessFormSnapshotView> buildExecutedForms(
            List<HistoricActivityInstance> activities,
            Map<String, HistoricTaskInstance> tasksById,
            org.flowable.bpmn.model.Process process,
            Map<NodeFormKey, SnapshotSchema> snapshots, VariableStore variables,
            String deploymentId, DetailResponseBudget budget)
    {
        List<WorkflowProcessFormSnapshotView> forms = new ArrayList<>();
        Set<String> usedSubmissionIds = new HashSet<>();
        for (HistoricActivityInstance activity : activities)
        {
            FlowElement element = process.getFlowElement(activity.getActivityId(), true);
            if (!(element instanceof StartEvent) && !(element instanceof UserTask))
            {
                continue;
            }
            String formKey = formKey(element);
            if (!StringUtils.hasText(formKey))
            {
                continue;
            }

            StoredSubmission submission;
            if (element instanceof StartEvent)
            {
                if (activity.getEndTime() == null)
                {
                    throw dataError("开始节点历史状态异常");
                }
                submission = variables.startSubmission();
                if (submission == null)
                {
                    // 旧实例没有正式开始提交快照时不返回伪造的最终变量历史值。
                    continue;
                }
                if (!activity.getActivityId().equals(submission.snapshot().nodeKey()))
                {
                    throw dataError("流程开始表单提交快照与历史节点关系不一致");
                }
            }
            else
            {
                if (!StringUtils.hasText(activity.getTaskId()))
                {
                    throw dataError("用户任务活动缺少任务关联");
                }
                HistoricTaskInstance historicTask = tasksById.get(activity.getTaskId());
                if (historicTask == null)
                {
                    throw dataError("用户任务活动与历史任务关系异常");
                }
                boolean activityFinished = activity.getEndTime() != null;
                boolean taskFinished = historicTask.getEndTime() != null;
                if (activityFinished != taskFinished)
                {
                    throw dataError("用户任务活动与历史任务结束状态不一致");
                }
                if (!taskFinished)
                {
                    // 活动任务只允许通过 currentTaskForm 读取当前值，不能进入历史提交列表。
                    continue;
                }
                submission = variables.taskSubmissions().get(activity.getTaskId());
                if (submission == null)
                {
                    // 兼容升级前实例：没有正式提交快照就不声称拥有历史表单值。
                    continue;
                }
            }
            if (!usedSubmissionIds.add(submission.detailId()))
            {
                throw dataError("流程表单提交快照被多个历史活动复用");
            }
            forms.add(buildSubmittedFormView(activity.getActivityId(), activity.getId(),
                    activity.getTaskId(), element, formKey, snapshots, submission,
                    deploymentId, budget));
        }
        return List.copyOf(forms);
    }

    /**
     * 将原开始表单快照投影为退回修改任务的可编辑表单，字段仍只来自已审计的开始提交快照。
     *
     * @param processForms List&lt;WorkflowProcessFormSnapshotView&gt;，已授权并完成白名单过滤的历史表单
     * @param returnedTaskId String，发起人当前独占的退回修改任务主键
     * @return WorkflowProcessFormSnapshotView，绑定当前任务且 snapshotTime 为空的开始表单
     */
    private WorkflowProcessFormSnapshotView buildReturnedStartForm(
            List<WorkflowProcessFormSnapshotView> processForms, String returnedTaskId)
    {
        List<WorkflowProcessFormSnapshotView> starts = processForms.stream()
                .filter(form -> form != null && form.taskId() == null).toList();
        if (starts.size() != 1)
        {
            throw dataError("退回开始表单快照异常");
        }
        WorkflowProcessFormSnapshotView start = starts.get(0);
        return new WorkflowProcessFormSnapshotView(start.activityId(), returnedTaskId,
                start.sourceType(), start.formId(), start.formKey(), start.nodeKey(), start.formName(),
                start.nodeName(), start.content(), false, start.values(), null);
    }

    /**
     * 构建请求指定任务的活动表单或已提交不可变表单。
     *
     * @param task WorkflowTaskAccessSnapshot，已完成对象授权和实例关系核验的任务
     * @param tasksById Map&lt;String, HistoricTaskInstance&gt;，实例历史任务索引
     * @param process org.flowable.bpmn.model.Process，目标定义的 BPMN 流程
     * @param snapshots Map&lt;NodeFormKey, SnapshotSchema&gt;，部署表单快照索引
     * @param variables VariableStore，正式提交快照索引
     * @param deploymentId String，流程实例所属部署主键
     * @param controlledLoop boolean，活动任务是否为部署快照确认的受控循环节点
     * @param returnedProcessForms List&lt;WorkflowProcessFormSnapshotView&gt;，退回态使用的历史表单；普通任务为空
     * @param budget DetailResponseBudget，详情累计正文大小预算
     * @return WorkflowProcessFormSnapshotView，合法任务表单；无表单或旧实例无提交快照时返回 null
     */
    private WorkflowProcessFormSnapshotView buildCurrentTaskForm(
            WorkflowTaskAccessSnapshot task,
            Map<String, HistoricTaskInstance> tasksById,
            org.flowable.bpmn.model.Process process,
            Map<NodeFormKey, SnapshotSchema> snapshots, VariableStore variables,
            String deploymentId, boolean controlledLoop,
            List<WorkflowProcessFormSnapshotView> returnedProcessForms,
            DetailResponseBudget budget)
    {
        if (!StringUtils.hasText(task.taskDefinitionKey()))
        {
            throw dataError("任务缺少 BPMN 节点关联");
        }
        HistoricTaskInstance historicTask = tasksById.get(task.taskId());
        if (historicTask == null
                || !task.taskDefinitionKey().equals(historicTask.getTaskDefinitionKey()))
        {
            throw dataError("任务与历史任务关系不一致");
        }
        boolean historicFinished = historicTask.getEndTime() != null;
        if (task.active() == historicFinished || (task.endTime() != null) != historicFinished)
        {
            throw dataError("任务活动状态与历史状态不一致");
        }

        FlowElement element = process.getFlowElement(task.taskDefinitionKey(), true);
        if (!(element instanceof UserTask))
        {
            throw dataError("任务与 BPMN 用户节点关系不一致");
        }
        if (returnedProcessForms != null)
        {
            // 保留任务、历史任务和 BPMN 关系门禁，退回值只继承开始提交快照，不查询当前变量。
            return buildReturnedStartForm(returnedProcessForms, task.taskId());
        }
        String formKey = formKey(element);
        if (!StringUtils.hasText(formKey))
        {
            return null;
        }
        if (task.active())
        {
            return buildActiveFormView(task.processInstanceId(), task.taskDefinitionKey(),
                    task.taskId(), element, formKey, snapshots, variables,
                    deploymentId, controlledLoop, budget);
        }
        StoredSubmission submission = variables.taskSubmissions().get(task.taskId());
        return submission == null ? null
                : buildSubmittedFormView(task.taskDefinitionKey(), null, task.taskId(),
                        element, formKey, snapshots, submission, deploymentId, budget);
    }

    /**
     * 从变量投影组件构建活动任务表单，时间必须保持为空以表明尚未提交。
     *
     * @param instanceId String，已经完成对象授权的流程实例主键
     * @param activityId String，活动任务 BPMN 节点主键
     * @param taskId String，真实活动任务主键
     * @param element FlowElement，BPMN 用户任务元素
     * @param formKey String，BPMN 表单键
     * @param snapshots Map&lt;NodeFormKey, SnapshotSchema&gt;，部署表单快照索引
     * @param variables VariableStore，受控循环继承需要的正式提交快照索引
     * @param deploymentId String，流程实例所属部署主键
     * @param controlledLoop boolean，是否允许从同节点上一轮正式快照继承初始值
     * @param budget DetailResponseBudget，详情累计正文大小预算
     * @return WorkflowProcessFormSnapshotView，当前值回显且 snapshotTime 为 null 的任务表单
     */
    private WorkflowProcessFormSnapshotView buildActiveFormView(String instanceId,
            String activityId, String taskId, FlowElement element, String formKey,
            Map<NodeFormKey, SnapshotSchema> snapshots, VariableStore variables,
            String deploymentId, boolean controlledLoop, DetailResponseBudget budget)
    {
        SnapshotSchema schema = requireSnapshotSchema(element, formKey, snapshots);
        boolean taskLocal = isTaskLocal(element);
        // 只有授权后的活动有表单任务会进入变量投影；局部与根作用域由直接协作者分别失败关闭。
        ProjectedValues currentProjection = variableProjection.projectCurrentValues(
                instanceId, taskId, taskLocal, schema.readableVariableNames());
        for (int serializedBytes : currentProjection.serializedBytesByName().values())
        {
            budget.addVariableBytes(serializedBytes);
        }

        Map<String, JsonNode> currentValues = currentProjection.values();
        Map<String, JsonNode> values = currentValues;
        if (taskLocal && controlledLoop)
        {
            ProjectedValues inheritedProjection = inheritControlledLoopValues(schema, variables,
                    deploymentId, activityId, taskId);
            Map<String, JsonNode> inherited = inheritedProjection.values();
            if (!inherited.isEmpty())
            {
                LinkedHashMap<String, JsonNode> merged = new LinkedHashMap<>();
                for (String fieldName : schema.readableVariableNames())
                {
                    JsonNode current = currentValues.get(fieldName);
                    JsonNode value = current == null ? inherited.get(fieldName) : current;
                    if (value != null)
                    {
                        JsonNode copied = value.deepCopy();
                        if (current == null)
                        {
                            budget.addVariableBytes(
                                    inheritedProjection.serializedBytesByName().get(fieldName));
                        }
                        merged.put(fieldName, copied);
                    }
                }
                values = Collections.unmodifiableMap(merged);
            }
        }
        return toFormView(activityId, taskId, schema.snapshot(), taskLocal, values,
                null, budget);
    }

    /**
     * 校验详情列表至多包含一个 taskId 为空的正式开始提交快照。
     *
     * @param processForms List&lt;WorkflowProcessFormSnapshotView&gt;，已构建的正式提交快照
     * @return 无返回值，多个开始快照按数据损坏失败；零个由页面明确显示缺失且禁止变量伪造
     */
    private void requireAtMostOneApplicationForm(
            List<WorkflowProcessFormSnapshotView> processForms)
    {
        if (processForms == null || processForms.stream()
                .filter(Objects::nonNull).filter(form -> form.taskId() == null)
                .limit(2).count() > 1)
        {
            throw dataError("流程申请表单提交快照不唯一");
        }
    }

    /**
     * 读取同一受控循环节点最近一轮正式任务提交，并由变量投影组件生成安全继承候选值。
     *
     * @param schema SnapshotSchema，当前节点不可变部署表单 schema
     * @param variables VariableStore，已完成历史变量关联门禁的提交快照索引
     * @param deploymentId String，当前流程定义部署主键
     * @param activityId String，当前循环用户任务节点主键
     * @param activeTaskId String，本轮尚未提交的真实任务主键
     * @return ProjectedValues，仅包含当前 schema 可读字段的上一轮安全值及逐字段字节数
     */
    private ProjectedValues inheritControlledLoopValues(SnapshotSchema schema,
            VariableStore variables, String deploymentId, String activityId,
            String activeTaskId)
    {
        StoredSubmission previous = variables.taskSubmissions().values().stream()
                .filter(submission -> !activeTaskId.equals(submission.taskId()))
                .filter(submission -> submission.snapshot().kind() == SnapshotKind.TASK)
                .filter(submission -> activityId.equals(submission.snapshot().nodeKey()))
                .max((left, right) ->
                {
                    int timeComparison = left.submittedAt().compareTo(right.submittedAt());
                    return timeComparison != 0 ? timeComparison
                            : left.detailId().compareTo(right.detailId());
                })
                .orElse(null);
        if (previous == null)
        {
            return variableProjection.projectControlledLoopValues(
                    schema.variableNames(), schema.readableVariableNames(), Map.of());
        }
        SubmissionSnapshot submitted = previous.snapshot();
        if (!deploymentId.equals(submitted.deploymentId())
                || !Objects.equals(schema.snapshot().getSourceType(), submitted.sourceType())
                || !Objects.equals(schema.snapshot().getFormId(), submitted.formId())
                || !schema.snapshot().getFormKey().equals(submitted.formKey())
                || !activityId.equals(submitted.nodeKey())
                || !submitted.taskLocal()
                || !Objects.equals(previous.taskId(), submitted.taskId()))
        {
            throw dataError("受控循环上一轮表单快照关联异常");
        }
        return variableProjection.projectControlledLoopValues(
                schema.variableNames(), schema.readableVariableNames(), submitted.values());
    }

    /**
     * 校验提交快照与部署、节点、任务和历史更新的强关联后构建只读历史表单。
     *
     * @param activityId String，BPMN 节点主键
     * @param historicActivityInstanceId String，Flowable 历史活动实例主键；当前任务场景允许为空
     * @param taskId String，用户任务主键；开始节点为空
     * @param element FlowElement，开始节点或用户任务元素
     * @param formKey String，BPMN 表单键
     * @param snapshots Map&lt;NodeFormKey, SnapshotSchema&gt;，部署表单快照索引
     * @param submission StoredSubmission，历史变量中的不可变提交快照
     * @param deploymentId String，流程实例所属部署主键
     * @param budget DetailResponseBudget，详情累计正文大小预算
     * @return WorkflowProcessFormSnapshotView，提交当时的真实字段值和写入时间
     */
    private WorkflowProcessFormSnapshotView buildSubmittedFormView(String activityId,
            String historicActivityInstanceId, String taskId, FlowElement element,
            String formKey, Map<NodeFormKey, SnapshotSchema> snapshots,
            StoredSubmission submission, String deploymentId, DetailResponseBudget budget)
    {
        SnapshotSchema schema = requireSnapshotSchema(element, formKey, snapshots);
        SubmissionSnapshot submitted = submission.snapshot();
        boolean taskLocal = element instanceof UserTask && isTaskLocal(element);
        SnapshotKind expectedKind = element instanceof StartEvent
                ? SnapshotKind.START : SnapshotKind.TASK;
        if (submitted.kind() != expectedKind
                || !deploymentId.equals(submitted.deploymentId())
                || !Objects.equals(schema.snapshot().getSourceType(), submitted.sourceType())
                || !Objects.equals(schema.snapshot().getFormId(), submitted.formId())
                || !formKey.equals(submitted.formKey())
                || !element.getId().equals(submitted.nodeKey())
                || !Objects.equals(taskId, submitted.taskId())
                || taskLocal != submitted.taskLocal()
                || !Objects.equals(taskId, submission.taskId())
                || (StringUtils.hasText(submission.activityInstanceId())
                        && StringUtils.hasText(historicActivityInstanceId)
                        && !historicActivityInstanceId.equals(submission.activityInstanceId())))
        {
            throw dataError("流程表单提交快照与历史节点关系不一致");
        }
        ProjectedValues projectedValues = variableProjection.projectSubmittedValues(
                schema.variableNames(), schema.readableVariableNames(), submitted.values());
        for (int serializedBytes : projectedValues.serializedBytesByName().values())
        {
            budget.addVariableBytes(serializedBytes);
        }
        return toFormView(activityId, taskId, schema.snapshot(), taskLocal,
                projectedValues.values(), submission.submittedAt(), budget);
    }

    /**
     * 查询当前节点的不可变部署表单 schema。
     *
     * @param element FlowElement，开始节点或用户任务元素
     * @param formKey String，BPMN 表单键
     * @param snapshots Map&lt;NodeFormKey, SnapshotSchema&gt;，部署快照联合索引
     * @return SnapshotSchema，与节点和表单键完全匹配的部署快照
     */
    private SnapshotSchema requireSnapshotSchema(FlowElement element, String formKey,
            Map<NodeFormKey, SnapshotSchema> snapshots)
    {
        SnapshotSchema schema = snapshots.get(new NodeFormKey(element.getId(), formKey));
        if (schema == null)
        {
            throw dataError("流程节点缺少部署表单快照");
        }
        return schema;
    }

    /**
     * 统一组装表单视图并累计部署表单正文预算。
     *
     * @param activityId String，BPMN 节点主键
     * @param taskId String，用户任务主键；开始节点为空
     * @param snapshot WfDeployForm，不可变部署表单快照
     * @param taskLocal boolean，业务字段是否使用任务局部作用域
     * @param values Map&lt;String, JsonNode&gt;，当前值或正式提交值
     * @param snapshotTime Instant，正式提交写入时间；活动任务为空
     * @param budget DetailResponseBudget，详情累计正文大小预算
     * @return WorkflowProcessFormSnapshotView，防御复制后的表单视图
     */
    private WorkflowProcessFormSnapshotView toFormView(String activityId, String taskId,
            WfDeployForm snapshot, boolean taskLocal, Map<String, JsonNode> values,
            Instant snapshotTime, DetailResponseBudget budget)
    {
        budget.addFormBytes(snapshot.getContent());
        return new WorkflowProcessFormSnapshotView(activityId, taskId, snapshot.getSourceType(),
                snapshot.getFormId(), snapshot.getFormKey(), snapshot.getNodeKey(), snapshot.getFormName(),
                snapshot.getNodeName(), snapshot.getContent(), taskLocal, values, snapshotTime);
    }

    /**
     * 读取开始节点或用户任务的表单键。
     *
     * @param element FlowElement，BPMN 开始节点或用户任务
     * @return String，节点表单键；未配置时为空
     */
    private String formKey(FlowElement element)
    {
        if (element instanceof StartEvent startEvent)
        {
            return resolveFormKey(startEvent.getFormKey(), startEvent.getFormProperties());
        }
        if (element instanceof UserTask userTask)
        {
            return resolveFormKey(userTask.getFormKey(), userTask.getFormProperties());
        }
        return null;
    }

    /**
     * 将 BPMN 节点表单来源规范为部署快照键，并兼容正式模板的受控字段权限描述。
     *
     * @param configuredFormKey String，正式模板 formKey
     * @param formProperties List&lt;org.flowable.bpmn.model.FormProperty&gt;，内嵌 FormData 字段
     * @return String，模板键、内嵌稳定键或无表单时的 null
     */
    private String resolveFormKey(String configuredFormKey,
            List<org.flowable.bpmn.model.FormProperty> formProperties)
    {
        boolean hasTemplate = StringUtils.hasText(configuredFormKey);
        boolean hasEmbedded = formProperties != null && !formProperties.isEmpty();
        if (hasTemplate)
        {
            // 权限 FormProperty 已在模型保存和部署阶段通过白名单校验，详情只按不可变 formKey 快照取值。
            return configuredFormKey;
        }
        return hasEmbedded ? WorkflowFormSourceType.EMBEDDED_FORM_KEY : null;
    }

    /**
     * 判断用户任务是否要求从任务局部变量回显表单值。
     *
     * @param element FlowElement，BPMN 用户任务元素
     * @return boolean，localScope 属性为 true 或 1 时返回 true
     */
    private boolean isTaskLocal(FlowElement element)
    {
        Map<String, List<ExtensionAttribute>> attributes = element.getAttributes();
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
     * 创建表单 schema、快照关系或响应预算异常。
     *
     * @param message String，稳定业务提示
     * @return ServiceException，HTTP 500 业务异常
     */
    private ServiceException dataError(String message)
    {
        return new ServiceException(message, HttpStatus.ERROR);
    }

    /** 部署表单 schema 的不可变索引。 */
    record FormSchemas(Map<NodeFormKey, SnapshotSchema> schemas)
    {
    }

    /**
     * 表单投影所需的已授权上下文。
     *
     * @param schemas FormSchemas，部署表单 schema 索引
     * @param history WorkflowProcessHistoryProjection.HistoryData，有界历史活动和任务索引
     * @param process org.flowable.bpmn.model.Process，正式部署 BPMN 流程
     * @param variables VariableStore，完成存储安全门禁的正式提交快照
     * @param deploymentId String，流程实例所属部署主键
     * @param requestedTask WorkflowTaskAccessSnapshot，可选的已授权请求任务
     * @param currentTaskControlledLoop boolean，请求任务是否为当前受控循环节点
     * @param returnedApplication boolean，是否处于申请人退回修改阶段
     */
    record FormProjectionRequest(FormSchemas schemas,
            WorkflowProcessHistoryProjection.HistoryData history,
            org.flowable.bpmn.model.Process process, VariableStore variables,
            String deploymentId, WorkflowTaskAccessSnapshot requestedTask,
            boolean currentTaskControlledLoop, boolean returnedApplication)
    {
    }

    /** 当前任务表单和正式历史表单列表。 */
    record FormProjection(WorkflowProcessFormSnapshotView currentTaskForm,
            List<WorkflowProcessFormSnapshotView> processForms)
    {
    }

    /** 部署快照联合主键。 */
    private record NodeFormKey(String nodeKey, String formKey)
    {
    }

    /** 不可变部署快照及从 schema 提取的字段白名单。 */
    private record SnapshotSchema(WfDeployForm snapshot, Set<String> variableNames,
            Set<String> readableVariableNames)
    {
    }

    /** 单次详情响应的表单和变量累计正文预算。 */
    private final class DetailResponseBudget
    {
        /** 已计入响应的表单 JSON 字节数。 */
        private int formBytes;

        /** 已计入响应的变量 JSON 字节数。 */
        private int variableBytes;

        /**
         * 累加一次实际会进入响应的表单快照正文大小。
         *
         * @param content String，部署表单快照 JSON
         * @return 无返回值，累计超过上限时拒绝详情
         */
        private void addFormBytes(String content)
        {
            formBytes = checkedAdd(formBytes, content.getBytes(StandardCharsets.UTF_8).length,
                    MAX_TOTAL_FORM_BYTES, "流程详情表单正文总量超过安全上限");
        }

        /**
         * 累加一次实际会进入响应的变量 JSON 大小。
         *
         * @param bytes int，安全变量 JSON 字节数
         * @return 无返回值，累计超过上限时拒绝详情
         */
        private void addVariableBytes(int bytes)
        {
            variableBytes = checkedAdd(variableBytes, bytes, MAX_TOTAL_VARIABLE_BYTES,
                    "流程详情变量正文总量超过安全上限");
        }

        /**
         * 防溢出累加响应正文预算。
         *
         * @param current int，当前累计字节数
         * @param added int，本次新增字节数
         * @param limit int，允许的累计上限
         * @param message String，超限时稳定提示
         * @return int，新的累计字节数
         */
        private int checkedAdd(int current, int added, int limit, String message)
        {
            if (added < 0 || current > limit - added)
            {
                throw dataError(message);
            }
            return current + added;
        }
    }
}
