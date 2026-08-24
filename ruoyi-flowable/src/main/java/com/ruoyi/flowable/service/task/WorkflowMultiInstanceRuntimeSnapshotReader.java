package com.ruoyi.flowable.service.task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.UserTask;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;

/**
 * 受控多实例部署定义、执行树、变量、计数和活动任务的唯一只读快照读取器。
 *
 * <p>本服务不写 Flowable、业务表、审计或通知。所有写服务必须复用这里的定义识别、根定位、
 * 成员/模式/revision/计数读取和 task-child 对账，避免同一运行时协议出现第二套实现。</p>
 */
@Service
public class WorkflowMultiInstanceRuntimeSnapshotReader
{
    /** Flowable 多实例根维护的总实例数变量。 */
    private static final String NUMBER_OF_INSTANCES = "nrOfInstances";

    /** Flowable 多实例根维护的活动实例数变量。 */
    private static final String NUMBER_OF_ACTIVE_INSTANCES = "nrOfActiveInstances";

    /** Flowable 多实例根维护的已完成实例数变量。 */
    private static final String NUMBER_OF_COMPLETED_INSTANCES =
            "nrOfCompletedInstances";

    /** 单次终止扫描允许读取的 execution 上限。 */
    private static final int MAX_RUNTIME_SNAPSHOT_SIZE = 2_000;

    private final RepositoryService repositoryService;

    private final RuntimeService runtimeService;

    private final TaskService taskService;

    /**
     * 创建唯一只读运行时快照读取器。
     *
     * @param repositoryService RepositoryService，部署定义和 BPMN 模型查询服务
     * @param runtimeService RuntimeService，流程实例、execution 和变量查询服务
     * @param taskService TaskService，活动成员任务查询服务
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowMultiInstanceRuntimeSnapshotReader(
            RepositoryService repositoryService, RuntimeService runtimeService,
            TaskService taskService)
    {
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
    }

    /**
     * 通过活动任务主键读取完整受控多实例快照。
     *
     * @param taskId String，活动任务主键
     * @return ControlledMultiInstanceSnapshot，受控任务完整快照；普通任务返回 null
     */
    public ControlledMultiInstanceSnapshot readTask(String taskId)
    {
        if (!StringUtils.hasText(taskId))
        {
            throw drift();
        }
        Task task = taskService.createTaskQuery().taskId(taskId).active().singleResult();
        if (task == null || task.isSuspended())
        {
            throw drift();
        }
        return readEvent(new WorkflowTaskEventSnapshot(task.getId(),
                task.getProcessInstanceId(), task.getProcessDefinitionId(),
                task.getTaskDefinitionKey(), task.getExecutionId(),
                task.getAssignee(), null));
    }

    /**
     * 使用监听事件冻结字段读取完整受控多实例快照。
     *
     * @param event WorkflowTaskEventSnapshot，create/complete 事件稳定字段
     * @return ControlledMultiInstanceSnapshot，受控任务完整快照；普通任务返回 null
     */
    public ControlledMultiInstanceSnapshot readEvent(WorkflowTaskEventSnapshot event)
    {
        if (event == null)
        {
            throw drift();
        }
        ControlledMultiInstanceRootSnapshot rootSnapshot = readEventRoot(event);
        if (rootSnapshot == null)
        {
            return null;
        }
        MultiInstanceEngineCounts counts = readCounts(rootSnapshot.rootExecutionId());
        List<Execution> children = requireChildExecutions(rootSnapshot, counts);
        List<MultiInstanceActiveTaskSnapshot> activeTasks = requireActiveTasks(
                event, rootSnapshot, counts, children,
                ActiveTaskMembershipRule.REQUIRE_FROZEN_MEMBER);
        return new ControlledMultiInstanceSnapshot(rootSnapshot.deployId(),
                rootSnapshot.processDefinitionId(), rootSnapshot.processInstanceId(),
                rootSnapshot.activityId(), rootSnapshot.rootExecutionId(),
                event.taskId(), event.executionId(), rootSnapshot.mode(),
                rootSnapshot.members(), rootSnapshot.revision(), counts, activeTasks,
                children.stream().map(Execution::getId).toList());
    }

    /**
     * 为 create 监听器先读取受控根身份和流程变量，不要求临时申请人根计数等于冻结成员数。
     *
     * @param event WorkflowTaskEventSnapshot，监听事件稳定事实
     * @return ControlledMultiInstanceRootSnapshot，普通任务返回 null
     */
    public ControlledMultiInstanceRootSnapshot readEventRoot(
            WorkflowTaskEventSnapshot event)
    {
        if (event == null)
        {
            throw drift();
        }
        ControlledNodeDefinition node = resolveControlledNode(
                event.processDefinitionId(), event.activityId());
        if (node == null)
        {
            return null;
        }
        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(event.processInstanceId()).singleResult();
        Execution taskExecution = runtimeService.createExecutionQuery()
                .executionId(event.executionId()).singleResult();
        if (instance == null || instance.isSuspended()
                || !Objects.equals(event.processDefinitionId(),
                        instance.getProcessDefinitionId())
                || taskExecution == null || taskExecution.isEnded()
                || taskExecution.isSuspended()
                || !Objects.equals(event.processInstanceId(),
                        taskExecution.getProcessInstanceId())
                || !Objects.equals(event.activityId(), taskExecution.getActivityId())
                || !StringUtils.hasText(taskExecution.getParentId()))
        {
            throw drift();
        }
        Execution root = runtimeService.createExecutionQuery()
                .executionId(taskExecution.getParentId()).singleResult();
        if (root == null || root.isEnded() || root.isSuspended()
                || !Objects.equals(event.processInstanceId(), root.getProcessInstanceId())
                || !Objects.equals(event.activityId(), root.getActivityId()))
        {
            throw drift();
        }
        return readRootVariables(node, event.processDefinitionId(),
                event.processInstanceId(), event.activityId(), root.getId());
    }

    /**
     * 在根取消事件的局部计数可能已被 Flowable 删除时读取仍稳定的定义和流程变量事实。
     *
     * @param processDefinitionId String，取消事件流程定义主键
     * @param processInstanceId String，取消事件流程实例主键
     * @param activityId String，被取消活动主键
     * @param rootExecutionId String，被取消多实例根主键
     * @return ControlledMultiInstanceRootSnapshot，普通多实例返回 null
     */
    public ControlledMultiInstanceRootSnapshot readCancelledRoot(
            String processDefinitionId, String processInstanceId, String activityId,
            String rootExecutionId)
    {
        ControlledNodeDefinition node = resolveControlledNode(processDefinitionId,
                activityId);
        if (node == null)
        {
            return null;
        }
        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (instance == null || instance.isSuspended()
                || !Objects.equals(processDefinitionId,
                        instance.getProcessDefinitionId())
                || !StringUtils.hasText(rootExecutionId))
        {
            throw drift();
        }
        return readRootVariables(node, processDefinitionId, processInstanceId,
                activityId, rootExecutionId);
    }

    /**
     * 扫描完整活动流程树并返回每个受控多实例根的完整快照。
     *
     * @param processInstanceIds Set&lt;String&gt;，根及活动 CallActivity 子实例主键
     * @return Map&lt;String,ControlledMultiInstanceSnapshot&gt;，按根 execution 主键索引
     */
    public Map<String, ActiveControlledMultiInstanceRootSnapshot> readActiveRoots(
            Set<String> processInstanceIds)
    {
        if (processInstanceIds == null || processInstanceIds.isEmpty()
                || processInstanceIds.size() > MAX_RUNTIME_SNAPSHOT_SIZE)
        {
            throw drift();
        }
        Map<String, ActiveControlledMultiInstanceRootSnapshot> snapshots =
                new LinkedHashMap<>();
        for (String processInstanceId : processInstanceIds)
        {
            ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(processInstanceId).singleResult();
            if (instance == null || instance.isSuspended()
                    || !StringUtils.hasText(instance.getProcessDefinitionId()))
            {
                throw drift();
            }
            List<Execution> executions = runtimeService.createExecutionQuery()
                    .processInstanceId(processInstanceId).list();
            if (executions == null || executions.size() > MAX_RUNTIME_SNAPSHOT_SIZE)
            {
                throw drift();
            }
            Map<String, List<Execution>> byActivity = groupExecutions(
                    processInstanceId, executions);
            for (Map.Entry<String, List<Execution>> entry : byActivity.entrySet())
            {
                ControlledNodeDefinition node = resolveControlledNode(
                        instance.getProcessDefinitionId(), entry.getKey());
                if (node == null)
                {
                    continue;
                }
                Execution root = requireUniqueRoot(entry.getValue());
                Task representative = requireRepresentativeTask(processInstanceId,
                        entry.getKey(), root.getId());
                WorkflowTaskEventSnapshot taskEvent = new WorkflowTaskEventSnapshot(
                        representative.getId(), representative.getProcessInstanceId(),
                        representative.getProcessDefinitionId(),
                        representative.getTaskDefinitionKey(),
                        representative.getExecutionId(), representative.getAssignee(), null);
                ControlledMultiInstanceRootSnapshot rootSnapshot = readEventRoot(taskEvent);
                MultiInstanceEngineCounts counts = readCounts(root.getId());
                List<Execution> children = requireChildExecutions(rootSnapshot, counts);
                List<MultiInstanceActiveTaskSnapshot> activeTasks = requireActiveTasks(
                        taskEvent, rootSnapshot, counts, children,
                        ActiveTaskMembershipRule.ALLOW_RETURNED_APPLICANT);
                ActiveControlledMultiInstanceRootSnapshot snapshot =
                        new ActiveControlledMultiInstanceRootSnapshot(rootSnapshot, counts,
                                activeTasks, children.stream().map(Execution::getId).toList());
                if (snapshot.childExecutionIds().size() != entry.getValue().size() - 1
                        || snapshots.putIfAbsent(root.getId(), snapshot) != null)
                {
                    throw drift();
                }
            }
        }
        return Map.copyOf(snapshots);
    }

    /**
     * 核对 RETURNED 期间流程变量仍与冻结轮次一致。
     *
     * @param processDefinitionId String，流程定义主键
     * @param processInstanceId String，流程实例主键
     * @param activityId String，原受控节点
     * @param rootExecutionId String，仅用于构造稳定身份的原轮次根主键
     * @param expectedMode WorkflowMultiInstanceMode，冻结模式
     * @param expectedMembers List&lt;String&gt;，冻结有序成员
     * @param expectedRevision int，冻结 revision
     * @return void，定义或流程变量漂移时抛出内部快照异常
     */
    public void requirePersistedSnapshot(String processDefinitionId,
            String processInstanceId, String activityId, String rootExecutionId,
            WorkflowMultiInstanceMode expectedMode, List<String> expectedMembers,
            int expectedRevision)
    {
        ControlledNodeDefinition node = resolveControlledNode(processDefinitionId,
                activityId);
        if (node == null)
        {
            throw drift();
        }
        ControlledMultiInstanceRootSnapshot persisted = readRootVariables(node,
                processDefinitionId, processInstanceId, activityId, rootExecutionId);
        if (persisted.mode() != expectedMode
                || persisted.revision() != expectedRevision
                || !persisted.members().equals(expectedMembers))
        {
            throw drift();
        }
    }

    /**
     * 只读解析部署 BPMN 中指定节点的受控多实例固定事实。
     *
     * @param processDefinitionId String，流程定义主键
     * @param activityId String，节点主键
     * @return ControlledMultiInstanceDefinitionSnapshot，普通节点返回 null
     */
    public ControlledMultiInstanceDefinitionSnapshot readDefinition(
            String processDefinitionId, String activityId)
    {
        ControlledNodeDefinition node = resolveControlledNode(processDefinitionId,
                activityId);
        return node == null ? null : new ControlledMultiInstanceDefinitionSnapshot(
                node.deployId(), processDefinitionId, activityId, node.mode());
    }

    /**
     * 读取部署定义并判断指定节点是否使用受控多实例 handler。
     *
     * @param processDefinitionId String，流程定义主键
     * @param activityId String，节点主键
     * @return ControlledNodeDefinition，普通节点返回 null
     */
    private ControlledNodeDefinition resolveControlledNode(
            String processDefinitionId, String activityId)
    {
        if (!StringUtils.hasText(processDefinitionId)
                || !StringUtils.hasText(activityId))
        {
            throw drift();
        }
        try
        {
            ProcessDefinition definition = repositoryService.getProcessDefinition(
                    processDefinitionId);
            BpmnModel model = repositoryService.getBpmnModel(processDefinitionId);
            if (definition == null || model == null
                    || !StringUtils.hasText(definition.getDeploymentId())
                    || !StringUtils.hasText(definition.getKey()))
            {
                throw drift();
            }
            org.flowable.bpmn.model.Process process = model.getProcessById(
                    definition.getKey());
            FlowElement element = process == null ? null
                    : process.getFlowElement(activityId, true);
        if (!(element instanceof UserTask userTask))
        {
            return null;
        }
            if (!WorkflowMultiInstanceModelContract.usesControlledHandler(
                    userTask.getLoopCharacteristics()))
            {
                return null;
            }
            return new ControlledNodeDefinition(definition.getDeploymentId(),
                    WorkflowMultiInstanceModelContract.requireMode(userTask));
        }
        catch (FlowableObjectNotFoundException | IllegalArgumentException exception)
        {
            throw drift(exception);
        }
    }

    /**
     * 读取流程作用域成员、模式和 revision，并与部署固定模式核对。
     *
     * @param node ControlledNodeDefinition，部署解析出的节点事实
     * @param processDefinitionId String，流程定义主键
     * @param processInstanceId String，流程实例主键
     * @param activityId String，受控节点
     * @param rootExecutionId String，根 execution 主键
     * @return ControlledMultiInstanceRootSnapshot，完整不可变根事实
     */
    private ControlledMultiInstanceRootSnapshot readRootVariables(
            ControlledNodeDefinition node, String processDefinitionId,
            String processInstanceId, String activityId, String rootExecutionId)
    {
        List<String> members = requireMembers(runtimeService.getVariable(
                processInstanceId,
                WorkflowMultiInstanceVariables.memberSnapshotName(activityId)));
        int revision = requireNonNegativeInteger(runtimeService.getVariable(
                processInstanceId,
                WorkflowMultiInstanceVariables.revisionName(activityId)));
        WorkflowMultiInstanceMode mode = requireMode(runtimeService.getVariable(
                processInstanceId, WorkflowMultiInstanceVariables.modeName(activityId)));
        if (mode != node.mode())
        {
            throw drift();
        }
        return new ControlledMultiInstanceRootSnapshot(node.deployId(),
                processDefinitionId, processInstanceId, activityId, rootExecutionId,
                mode, members, revision);
    }

    /**
     * 读取并校验多实例根三个局部计数。
     *
     * @param rootExecutionId String，根 execution 主键
     * @return MultiInstanceEngineCounts，总数与活动/完成数严格闭合
     */
    private MultiInstanceEngineCounts readCounts(String rootExecutionId)
    {
        try
        {
            return new MultiInstanceEngineCounts(
                    requireNonNegativeInteger(runtimeService.getVariableLocal(
                            rootExecutionId, NUMBER_OF_INSTANCES)),
                    requireNonNegativeInteger(runtimeService.getVariableLocal(
                            rootExecutionId, NUMBER_OF_ACTIVE_INSTANCES)),
                    requireNonNegativeInteger(runtimeService.getVariableLocal(
                            rootExecutionId, NUMBER_OF_COMPLETED_INSTANCES)));
        }
        catch (IllegalArgumentException exception)
        {
            throw drift(exception);
        }
    }

    /**
     * 对账根下全部 child execution 与根计数。
     *
     * @param root ControlledMultiInstanceRootSnapshot，受控根事实
     * @param counts MultiInstanceEngineCounts，根计数
     * @return List&lt;Execution&gt;，保持引擎查询顺序的全部 child execution
     */
    private List<Execution> requireChildExecutions(
            ControlledMultiInstanceRootSnapshot root,
            MultiInstanceEngineCounts counts)
    {
        List<Execution> children = runtimeService.createExecutionQuery()
                .parentId(root.rootExecutionId()).list();
        if (children == null || children.size() != counts.instances())
        {
            throw drift();
        }
        Set<String> ids = new LinkedHashSet<>();
        int activeChildren = 0;
        for (Execution child : children)
        {
            if (child == null || child.isEnded() || child.isSuspended()
                    || !StringUtils.hasText(child.getId())
                    || !ids.add(child.getId())
                    || !Objects.equals(root.rootExecutionId(), child.getParentId())
                    || !Objects.equals(root.processInstanceId(),
                            child.getProcessInstanceId())
                    || !Objects.equals(root.activityId(), child.getActivityId()))
            {
                throw drift();
            }
            List<String> activeActivityIds = runtimeService.getActiveActivityIds(
                    child.getId());
            if (activeActivityIds == null
                    || (activeActivityIds.size() > 1)
                    || (activeActivityIds.size() == 1
                            && !root.activityId().equals(activeActivityIds.get(0))))
            {
                throw drift();
            }
            if (activeActivityIds.size() == 1)
            {
                activeChildren++;
            }
        }
        if (activeChildren != counts.active()
                || children.size() - activeChildren != counts.completed())
        {
            throw drift();
        }
        return List.copyOf(children);
    }

    /**
     * 查询活动成员任务并与 child execution、成员和计数逐项核对。
     *
     * @param event WorkflowTaskEventSnapshot，来源任务事件事实
     * @param root ControlledMultiInstanceRootSnapshot，受控根事实
     * @param counts MultiInstanceEngineCounts，根计数
     * @param children List&lt;Execution&gt;，已核验 child execution
     * @param membershipRule ActiveTaskMembershipRule，正式审批根或 RETURNED 临时申请人根规则
     * @return List&lt;MultiInstanceActiveTaskSnapshot&gt;，按创建时间和主键稳定排序
     */
    private List<MultiInstanceActiveTaskSnapshot> requireActiveTasks(
            WorkflowTaskEventSnapshot event, ControlledMultiInstanceRootSnapshot root,
            MultiInstanceEngineCounts counts, List<Execution> children,
            ActiveTaskMembershipRule membershipRule)
    {
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(root.processInstanceId())
                .taskDefinitionKey(root.activityId()).active().list();
        if (tasks == null || tasks.size() != counts.active() || tasks.isEmpty())
        {
            throw drift();
        }
        Set<String> childIds = children.stream().map(Execution::getId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> taskIds = new LinkedHashSet<>();
        Set<String> executionIds = new LinkedHashSet<>();
        Set<String> assignees = new LinkedHashSet<>();
        for (Task task : tasks)
        {
            if (task == null || task.isSuspended()
                    || !StringUtils.hasText(task.getId())
                    || !StringUtils.hasText(task.getExecutionId())
                    || !StringUtils.hasText(task.getAssignee())
                    || !taskIds.add(task.getId())
                    || !executionIds.add(task.getExecutionId())
                    || !assignees.add(task.getAssignee())
                    || !childIds.contains(task.getExecutionId())
                    || (membershipRule == ActiveTaskMembershipRule.REQUIRE_FROZEN_MEMBER
                            && !root.members().contains(task.getAssignee()))
                    || !Objects.equals(root.processDefinitionId(),
                            task.getProcessDefinitionId())
                    || !Objects.equals(root.activityId(),
                            task.getTaskDefinitionKey()))
            {
                throw drift();
            }
        }
        if (!taskIds.contains(event.taskId())
                || !executionIds.contains(event.executionId()))
        {
            throw drift();
        }
        List<Task> ordered = new ArrayList<>(tasks);
        ordered.sort(Comparator.comparing(Task::getCreateTime,
                Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Task::getId));
        return ordered.stream().map(task -> new MultiInstanceActiveTaskSnapshot(
                task.getId(), task.getExecutionId(), task.getAssignee(),
                task.getOwner(), task.getDelegationState() != null)).toList();
    }

    /** 活动根扫描时对任务办理人与冻结审批成员关系的校验规则。 */
    private enum ActiveTaskMembershipRule
    {
        /** 正式 ACTIVE 审批根的每个办理人都必须属于冻结成员。 */
        REQUIRE_FROZEN_MEMBER,

        /** RETURNED 临时根允许唯一任务已被改派给不在原审批组中的申请人。 */
        ALLOW_RETURNED_APPLICANT
    }

    /**
     * 按活动分组流程实例全部 execution。
     *
     * @param processInstanceId String，当前流程实例主键
     * @param executions List&lt;Execution&gt;，Flowable 实时 execution 列表
     * @return Map&lt;String,List&lt;Execution&gt;&gt;，按 activityId 保持查询顺序分组
     */
    private Map<String, List<Execution>> groupExecutions(String processInstanceId,
            List<Execution> executions)
    {
        Map<String, List<Execution>> byActivity = new LinkedHashMap<>();
        for (Execution execution : executions)
        {
            if (execution == null || !StringUtils.hasText(execution.getId())
                    || !Objects.equals(processInstanceId,
                            execution.getProcessInstanceId()))
            {
                throw drift();
            }
            if (StringUtils.hasText(execution.getActivityId()))
            {
                byActivity.computeIfAbsent(execution.getActivityId(),
                        ignored -> new ArrayList<>()).add(execution);
            }
        }
        return byActivity;
    }

    /**
     * 从同 activity execution 图识别唯一顶层根并拒绝嵌套分叉。
     *
     * @param executions List&lt;Execution&gt;，同一受控节点的根和 child execution
     * @return Execution，唯一多实例根
     */
    private Execution requireUniqueRoot(List<Execution> executions)
    {
        Set<String> ids = executions.stream().map(Execution::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<Execution> roots = executions.stream()
                .filter(execution -> !ids.contains(execution.getParentId())).toList();
        if (roots.size() != 1)
        {
            throw drift();
        }
        Execution root = roots.get(0);
        for (Execution execution : executions)
        {
            if (!root.getId().equals(execution.getId())
                    && !root.getId().equals(execution.getParentId()))
            {
                throw drift();
            }
        }
        return root;
    }

    /**
     * 为活动根选择一个真实成员任务，后续统一读取器会核对完整任务组。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，受控节点
     * @param rootExecutionId String，多实例根主键
     * @return Task，直接挂在该根下的唯一候选集合中的第一个任务
     */
    private Task requireRepresentativeTask(String processInstanceId,
            String activityId, String rootExecutionId)
    {
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(activityId).active().list();
        if (tasks == null)
        {
            throw drift();
        }
        return tasks.stream().filter(Objects::nonNull).filter(task ->
        {
            Execution execution = runtimeService.createExecutionQuery()
                    .executionId(task.getExecutionId()).singleResult();
            return execution != null && rootExecutionId.equals(execution.getParentId());
        }).findFirst().orElseThrow(this::drift);
    }

    /**
     * 严格读取有序、唯一、规范数字用户主键成员列表。
     *
     * @param rawMembers Object，流程作用域成员变量原值
     * @return List&lt;String&gt;，不可修改成员列表
     */
    private List<String> requireMembers(Object rawMembers)
    {
        if (!(rawMembers instanceof List<?> members) || members.isEmpty()
                || members.size() > WorkflowUserSelectionValidator.MAX_SELECTED_USERS)
        {
            throw drift();
        }
        Set<String> canonical = new LinkedHashSet<>();
        for (Object member : members)
        {
            if (!(member instanceof String userId) || !StringUtils.hasText(userId))
            {
                throw drift();
            }
            try
            {
                long numeric = Long.parseLong(userId);
                if (numeric <= 0 || !String.valueOf(numeric).equals(userId)
                        || !canonical.add(userId))
                {
                    throw drift();
                }
            }
            catch (NumberFormatException exception)
            {
                throw drift(exception);
            }
        }
        return List.copyOf(canonical);
    }

    /**
     * 严格读取非负 int 引擎变量。
     *
     * @param value Object，revision 或 nrOf* 变量原值
     * @return int，未截断的非负整数
     */
    private int requireNonNegativeInteger(Object value)
    {
        if (!(value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long))
        {
            throw drift();
        }
        long numeric = ((Number) value).longValue();
        if (numeric < 0 || numeric > Integer.MAX_VALUE)
        {
            throw drift();
        }
        return (int) numeric;
    }

    /**
     * 严格读取 ALL/ANY 模式变量。
     *
     * @param value Object，流程作用域模式变量原值
     * @return WorkflowMultiInstanceMode，合法固定模式
     */
    private WorkflowMultiInstanceMode requireMode(Object value)
    {
        if (!(value instanceof String mode))
        {
            throw drift();
        }
        try
        {
            return WorkflowMultiInstanceMode.valueOf(mode);
        }
        catch (IllegalArgumentException exception)
        {
            throw drift(exception);
        }
    }

    /**
     * 创建内部快照漂移异常。
     *
     * @return WorkflowMultiInstanceSnapshotDriftException，由调用服务翻译稳定外部错误
     */
    private WorkflowMultiInstanceSnapshotDriftException drift()
    {
        return new WorkflowMultiInstanceSnapshotDriftException();
    }

    /**
     * 创建保留原始原因的内部快照漂移异常。
     *
     * @param cause Throwable，定义或变量解析原始异常
     * @return WorkflowMultiInstanceSnapshotDriftException，由调用服务翻译稳定外部错误
     */
    private WorkflowMultiInstanceSnapshotDriftException drift(Throwable cause)
    {
        return new WorkflowMultiInstanceSnapshotDriftException(cause);
    }

    /**
     * 部署模型解析出的受控节点固定事实。
     *
     * @param deployId String，部署主键
     * @param mode WorkflowMultiInstanceMode，固定 ALL/ANY 模式
     */
    private record ControlledNodeDefinition(String deployId,
            WorkflowMultiInstanceMode mode)
    {
    }
}
