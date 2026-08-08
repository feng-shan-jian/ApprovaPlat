package com.ruoyi.flowable.service.identity;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.Execution;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.service.delegate.DelegateTask;
import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployParticipantRule;
import com.ruoyi.flowable.domain.WfParticipantResolutionAudit;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfDeployParticipantRuleMapper;
import com.ruoyi.flowable.mapper.WorkflowIdentityMapper;

/**
 * 按部署快照和实时有效组织数据解析发起范围及单实例任务参与者。
 */
@Service
public class WorkflowParticipantRuleRuntimeService
{
    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final WfDeployParticipantRuleMapper ruleMapper;
    private final WorkflowIdentityMapper identityMapper;
    private final WorkflowIdentityResolver identityResolver;
    private final WorkflowParticipantResolutionAuditService auditService;

    /**
     * 创建参与者规则运行服务。
     * @param repositoryService RepositoryService，流程定义与部署关系查询 API
     * @param runtimeService RuntimeService，根流程执行树与服务端发起人变量查询 API
     * @param ruleMapper WfDeployParticipantRuleMapper，不可变部署规则 Mapper
     * @param identityMapper WorkflowIdentityMapper，实时组织关系查询 Mapper
     * @param identityResolver WorkflowIdentityResolver，审批与认领资格解析器
     * @param auditService WorkflowParticipantResolutionAuditService，正式解析审计服务
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowParticipantRuleRuntimeService(RepositoryService repositoryService,
            RuntimeService runtimeService,
            WfDeployParticipantRuleMapper ruleMapper, WorkflowIdentityMapper identityMapper,
            WorkflowIdentityResolver identityResolver,
            WorkflowParticipantResolutionAuditService auditService)
    {
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.ruleMapper = ruleMapper;
        this.identityMapper = identityMapper;
        this.identityResolver = identityResolver;
        this.auditService = auditService;
    }

    /**
     * 在真实引擎发起前按部署快照校验当前用户是否命中发起范围。
     * @param actor WorkflowCurrentIdentity，事务内重新核验的当前用户和有效组
     * @param definition ProcessDefinition，服务端选定的激活流程定义
     * @return WfDeployParticipantRule，已命中的发起范围，供成功后写审计
     */
    public WfDeployParticipantRule assertCanStart(WorkflowCurrentIdentity actor,
            ProcessDefinition definition)
    {
        WfDeployParticipantRule rule = findStartRule(definition);
        // 历史部署没有本版本快照时返回空，由人工发起入口继续执行原 Flowable starter identity link 门禁。
        if (rule == null) return null;
        boolean allowed = matchesStartRule(actor, rule);
        if (!allowed)
        {
            auditService.recordRejected(rule, "START", definition.getId(), null, null,
                    actor.userId(), actor.userId(), "DENIED", "当前用户未命中流程发起范围");
            throw new ServiceException("当前用户不在流程发起范围内", HttpStatus.FORBIDDEN)
                    .setSubCode("PROCESS_START_SCOPE_DENIED");
        }
        return rule;
    }

    /**
     * 只读判断当前用户是否命中定义发起范围，供可发起列表和表单预览保持一致。
     * @param actor WorkflowCurrentIdentity，当前有效用户及候选组
     * @param definition ProcessDefinition，服务端查询的流程定义
     * @return boolean，命中不可变部署范围时返回 true
     */
    public Boolean canStartIfManaged(WorkflowCurrentIdentity actor, ProcessDefinition definition)
    {
        WfDeployParticipantRule rule = findStartRule(definition);
        return rule == null ? null : matchesStartRule(actor, rule);
    }

    /**
     * 按公开、用户、角色或部门类型执行纯内存范围匹配。
     * @param actor WorkflowCurrentIdentity，当前有效身份
     * @param rule WfDeployParticipantRule，发起范围快照
     * @return boolean，当前用户命中时返回 true
     */
    private boolean matchesStartRule(WorkflowCurrentIdentity actor,
            WfDeployParticipantRule rule)
    {
        return switch (rule.getRuleType())
        {
            case "PUBLIC" -> true;
            case "USERS" -> parsePositiveIds(rule.getTargetIds()).contains(actor.userId());
            case "ROLES" -> intersects(actor.candidateGroups(), groupTargets("ROLE", rule.getTargetIds()));
            case "DEPTS" -> intersects(new LinkedHashSet<>(toTexts(safe(identityMapper
                    .selectActiveScopeDeptIdsByUserId(Long.valueOf(actor.userId()))))),
                    new LinkedHashSet<>(parsePositiveIds(rule.getTargetIds())));
            default -> false;
        };
    }

    /**
     * 在实例成功创建后记录发起范围命中结果，审计与实例写入同事务提交。
     * @param rule WfDeployParticipantRule，发起前已命中的规则
     * @param definition ProcessDefinition，流程定义
     * @param processInstanceId String，新实例主键
     * @param actorUserId String，发起用户主键
     * @return void，审计失败时回滚流程实例
     */
    public void recordStartAllowed(WfDeployParticipantRule rule, ProcessDefinition definition,
            String processInstanceId, String actorUserId)
    {
        WfParticipantResolutionAudit audit = auditService.base(rule, "START",
                definition.getId(), processInstanceId, null, actorUserId, actorUserId,
                "ALLOWED", "当前用户命中已部署流程发起范围");
        audit.setResolvedUserIds(actorUserId);
        auditService.record(audit);
    }

    /**
     * 在 Flowable create 事件内按实时组织解析并写入单实例任务办理人或候选身份。
     * @param task DelegateTask，当前创建中的真实 Flowable 任务
     * @return void，无匹配、直接办理多人冲突或身份失效时抛出并回滚任务创建
     */
    public void resolveCreatedTask(DelegateTask task)
    {
        DefinitionContext context = definitionContext(task.getProcessDefinitionId());
        WfDeployParticipantRule rule = ruleMapper.selectTaskRule(context.deploymentId(),
                context.processKey(), task.getTaskDefinitionKey());
        if (rule == null) return;
        String initiator = resolveRootInitiator(task);
        Resolution resolution;
        try
        {
            // 目录查询、表单用户字段解析和身份资格过滤都属于同一次实时解析，失败必须留下稳定拒绝审计。
            resolution = resolveRule(rule, task, initiator);
        }
        catch (RuntimeException exception)
        {
            rejectResolutionFailure(rule, task, initiator, exception);
            return;
        }
        // 先移除作者 BPMN 的旧静态候选链接，再写入实时解析结果，避免组织变更后残留越权候选人。
        clearCandidateLinks(task);
        if ("ASSIGNEE".equals(rule.getAssignmentMode()))
        {
            if (resolution.userIds().size() != 1)
                rejectNoMatch(rule, task, initiator, "直接办理规则没有解析出唯一有效审批人");
            task.setAssignee(resolution.userIds().iterator().next());
        }
        else
        {
            if (resolution.userIds().isEmpty() && resolution.groupIds().isEmpty())
                rejectNoMatch(rule, task, initiator, "候选规则没有解析出有效认领身份");
            resolution.userIds().forEach(task::addCandidateUser);
            resolution.groupIds().forEach(task::addCandidateGroup);
        }
        WfParticipantResolutionAudit audit = auditService.base(rule, "TASK",
                task.getProcessDefinitionId(), task.getProcessInstanceId(), task.getId(),
                initiator, null, "RESOLVED", "任务参与者按实时组织数据解析成功");
        audit.setResolvedUserIds(String.join(",", resolution.userIds()));
        audit.setResolvedGroupIds(String.join(",", resolution.groupIds()));
        auditService.record(audit);
    }

    /**
     * 从 Flowable 执行树根实例读取由人工发起入口写入的可信发起人。
     * @param task DelegateTask，当前根流程或 CallActivity 子流程内正在创建的任务
     * @return String，规范化后的根流程发起用户主键
     */
    private String resolveRootInitiator(DelegateTask task)
    {
        String executionId = task.getExecutionId();
        if (executionId == null || executionId.isBlank())
            throw new ServiceException("流程执行树关系异常", HttpStatus.CONFLICT);
        Execution execution = runtimeService.createExecutionQuery()
                .executionId(executionId.trim()).singleResult();
        if (execution == null || execution.getProcessInstanceId() == null)
            throw new ServiceException("流程执行树关系异常", HttpStatus.CONFLICT);
        String declaredRootId = execution.getRootProcessInstanceId();
        String rootInstanceId = declaredRootId == null || declaredRootId.isBlank()
                ? execution.getProcessInstanceId().trim() : declaredRootId.trim();
        if (rootInstanceId.isEmpty())
            throw new ServiceException("流程执行树关系异常", HttpStatus.CONFLICT);
        // 子流程局部变量可能被输入映射覆盖；根实例变量只由受控人工发起服务写入，不能由子流程规则伪造。
        return requirePositiveIdText(runtimeService.getVariable(rootInstanceId, "initiator"),
                "流程发起人身份缺失");
    }

    /**
     * 删除当前任务已经由 BPMN 静态声明创建的全部 candidate 链接。
     * @param task DelegateTask，正在创建的任务
     * @return void，无返回值；复制后删除避免修改底层集合时并发遍历
     */
    private void clearCandidateLinks(DelegateTask task)
    {
        Set<IdentityLink> candidates = task.getCandidates();
        if (candidates == null || candidates.isEmpty()) return;
        for (IdentityLink link : new ArrayList<>(candidates))
        {
            if (link.getUserId() != null) task.deleteCandidateUser(link.getUserId());
            if (link.getGroupId() != null) task.deleteCandidateGroup(link.getGroupId());
        }
    }

    /**
     * 按规则类型解析实时用户或候选组并执行审批、认领资格过滤。
     * @param rule WfDeployParticipantRule，不可变部署规则
     * @param task DelegateTask，任务变量读取上下文
     * @param initiator String，规范发起人主键
     * @return Resolution，去重且顺序稳定的用户和候选组
     */
    private Resolution resolveRule(WfDeployParticipantRule rule, DelegateTask task,
            String initiator)
    {
        return switch (rule.getRuleType())
        {
            case "FIXED_USER" -> users(identityResolver.resolveApprovalEligibleUserIds(
                    parsePositiveIds(rule.getTargetIds())));
            case "CANDIDATE_USERS" -> users(identityResolver.resolveClaimEligibleUserIds(
                    parsePositiveIds(rule.getTargetIds())));
            case "CANDIDATE_GROUPS" -> groups(identityResolver.resolveClaimEligibleCandidateGroups(
                    parseCandidateGroups(rule.getTargetIds())));
            case "STARTER" -> users(identityResolver.resolveApprovalEligibleUserIds(List.of(initiator)));
            case "STARTER_MANAGER" -> users(toTexts(safe(identityMapper
                    .selectApprovalEligibleManagerUserIdsByUserId(Long.valueOf(initiator)))));
            case "DEPT_MANAGER" -> users(toTexts(safe(identityMapper
                    .selectApprovalEligibleDeptLeaderUserIds(parseLongIds(rule.getTargetIds())))));
            case "STARTER_DEPT_ROLE" -> users(toTexts(safe(identityMapper
                    .selectClaimEligibleUserIdsByStarterDeptAndRole(Long.valueOf(initiator),
                            parseLongIds(rule.getTargetIds()).get(0)))));
            case "FORM_USER" -> users(identityResolver.resolveApprovalEligibleUserIds(
                    readFormUserIds(task.getVariable(rule.getFormField()))));
            default -> new Resolution(Set.of(), Set.of());
        };
    }

    /**
     * 记录无匹配或直接办理多人冲突，并以固定失败策略回滚任务创建。
     * @param rule WfDeployParticipantRule，当前任务命中的不可变规则快照
     * @param task DelegateTask，正在创建且不能成为无人任务的任务
     * @param initiator String，已规范化的流程发起人主键
     * @param summary String，稳定且不包含敏感目录数据的拒绝摘要
     * @return void，独立写入 NO_MATCH 审计后始终抛出稳定业务异常
     */
    private void rejectNoMatch(WfDeployParticipantRule rule, DelegateTask task,
            String initiator, String summary)
    {
        auditService.recordRejected(rule, "TASK", task.getProcessDefinitionId(),
                task.getProcessInstanceId(), task.getId(), initiator, null, "NO_MATCH", summary);
        throw new ServiceException(summary, HttpStatus.CONFLICT)
                .setSubCode("TASK_PARTICIPANT_NO_MATCH");
    }

    /**
     * 将表单值非法、实时目录异常或资格解析失败统一转换为可审计的稳定任务失败。
     * @param rule WfDeployParticipantRule，当前任务命中的不可变规则快照
     * @param task DelegateTask，正在创建且会被主事务回滚的任务
     * @param initiator String，已规范化的流程发起人主键
     * @param cause RuntimeException，原始解析异常，仅作为服务端异常链保留
     * @return void，独立写入 NO_MATCH 审计后始终抛出稳定业务异常
     */
    private void rejectResolutionFailure(WfDeployParticipantRule rule, DelegateTask task,
            String initiator, RuntimeException cause)
    {
        String summary = "任务参与者规则实时解析失败";
        auditService.recordRejected(rule, "TASK", task.getProcessDefinitionId(),
                task.getProcessInstanceId(), task.getId(), initiator, null, "NO_MATCH", summary);
        ServiceException failure = new ServiceException(summary, HttpStatus.CONFLICT)
                .setSubCode("TASK_PARTICIPANT_RESOLUTION_FAILED");
        failure.initCause(cause);
        throw failure;
    }

    /**
     * 查询流程定义并读取对应部署的发起范围快照。
     * @param definition ProcessDefinition，服务端可信流程定义
     * @return WfDeployParticipantRule，版本受支持的快照；历史部署未托管时返回 null
     */
    private WfDeployParticipantRule findStartRule(ProcessDefinition definition)
    {
        if (definition == null || definition.getDeploymentId() == null)
            throw new ServiceException("流程定义部署关系异常", HttpStatus.ERROR);
        WfDeployParticipantRule rule = ruleMapper.selectStartRule(
                definition.getDeploymentId(), definition.getKey());
        if (rule == null) return null;
        if (rule.getRuleVersion() == null || rule.getRuleVersion() != 1)
            throw new ServiceException("流程发起范围部署快照缺失", HttpStatus.CONFLICT)
                    .setSubCode("PROCESS_START_SCOPE_SNAPSHOT_MISSING");
        return rule;
    }

    /**
     * 将流程定义主键解析为部署主键和流程 key，禁止任务携带伪造部署关系。
     * @param processDefinitionId String，任务所属 Flowable 流程定义主键
     * @return DefinitionContext，服务端查询得到的可信部署上下文
     */
    private DefinitionContext definitionContext(String processDefinitionId)
    {
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(processDefinitionId).singleResult();
        if (definition == null || definition.getDeploymentId() == null)
            throw new ServiceException("任务流程定义部署关系异常", HttpStatus.ERROR);
        return new DefinitionContext(definition.getDeploymentId(), definition.getKey());
    }

    /**
     * 从单值、集合或数组表单变量提取用户主键并稳定去重。
     * @param value Object，部署规则指定的表单用户字段运行值
     * @return Set&lt;String&gt;，规范化后的不可变正整数用户主键集合
     */
    private Set<String> readFormUserIds(Object value)
    {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (value instanceof Collection<?> collection)
            collection.forEach(item -> ids.add(requirePositiveIdText(item, "表单用户字段值不合法")));
        else if (value != null && value.getClass().isArray())
            for (int index = 0; index < Array.getLength(value); index++)
                ids.add(requirePositiveIdText(Array.get(value, index), "表单用户字段值不合法"));
        else ids.add(requirePositiveIdText(value, "表单用户字段值不合法"));
        return immutableOrderedSet(ids);
    }

    /**
     * 校验身份值为 Long 范围内的十进制正整数并规范前导零。
     * @param value Object，目录目标、流程变量或表单字段中的身份值
     * @param message String，校验失败时的稳定业务提示
     * @return String，规范化后的正整数文本
     */
    private String requirePositiveIdText(Object value, String message)
    {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (!text.matches("[1-9][0-9]{0,18}")) throw new ServiceException(message, HttpStatus.CONFLICT);
        try { return Long.toString(Long.parseLong(text)); }
        catch (NumberFormatException exception) { throw new ServiceException(message, HttpStatus.CONFLICT); }
    }

    /**
     * 将逗号分隔的目标主键解析为稳定去重的文本列表。
     * @param csv String，部署快照中的目标主键文本
     * @return List&lt;String&gt;，不可变的规范用户主键列表
     */
    private List<String> parsePositiveIds(String csv) { return toTexts(parseLongIds(csv)); }

    /**
     * 将逗号分隔的目标主键解析为稳定去重的 Long 列表。
     * @param csv String，部署快照中的目标主键文本
     * @return List&lt;Long&gt;，不可变的正整数主键列表
     */
    private List<Long> parseLongIds(String csv)
    {
        if (csv == null || csv.isBlank()) return List.of();
        List<Long> ids = new ArrayList<>();
        for (String value : csv.split(",")) ids.add(Long.valueOf(requirePositiveIdText(value, "规则目标身份不合法")));
        return List.copyOf(new LinkedHashSet<>(ids));
    }

    /**
     * 解析已冻结的 ROLE/DEPT 候选组编码并稳定去重。
     * @param csv String，部署快照中的候选组编码
     * @return List&lt;String&gt;，不可变候选组列表
     */
    private List<String> parseCandidateGroups(String csv)
    {
        if (csv == null || csv.isBlank()) return List.of();
        return List.copyOf(new LinkedHashSet<>(List.of(csv.split(","))));
    }

    /**
     * 将目录对象主键转换为 Flowable 统一候选组编码。
     * @param prefix String，ROLE 或 DEPT 前缀
     * @param ids String，逗号分隔的目录对象主键
     * @return Set&lt;String&gt;，不可变候选组集合
     */
    private Set<String> groupTargets(String prefix, String ids)
    {
        LinkedHashSet<String> groups = new LinkedHashSet<>();
        parseLongIds(ids).forEach(id -> groups.add(prefix + id));
        return immutableOrderedSet(groups);
    }

    /**
     * 判断两个身份集合是否至少存在一个共同值。
     * @param left Set&lt;String&gt;，当前用户实时身份集合
     * @param right Set&lt;String&gt;，规则目标身份集合
     * @return boolean，存在交集时返回 true
     */
    private boolean intersects(Set<String> left, Set<String> right)
    {
        return left.stream().anyMatch(right::contains);
    }

    /**
     * 拒绝 Mapper 返回空引用、空主键或非正主键，防止主数据异常被当作无匹配。
     * @param values List&lt;Long&gt;，正式目录查询结果
     * @return List&lt;Long&gt;，原始合法结果
     */
    private List<Long> safe(List<Long> values)
    {
        if (values == null || values.stream().anyMatch(value -> value == null || value <= 0))
            throw new ServiceException("参与者规则身份主数据异常", HttpStatus.ERROR);
        return values;
    }

    /**
     * 将 Long 主键集合转换为十进制文本列表。
     * @param values Collection&lt;Long&gt;，已校验的目录主键集合
     * @return List&lt;String&gt;，保持输入顺序的文本列表
     */
    private List<String> toTexts(Collection<Long> values) { return values.stream().map(String::valueOf).toList(); }

    /**
     * 按首次出现顺序去重并返回不可修改集合。
     * @param values Collection&lt;String&gt;，待规范的身份值
     * @return Set&lt;String&gt;，稳定有序且不可修改的集合
     */
    private Set<String> immutableOrderedSet(Collection<String> values)
    {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    /**
     * 构造仅包含候选用户或直接办理人的解析结果。
     * @param users Collection&lt;String&gt;，资格过滤后的用户主键
     * @return Resolution，不可变用户解析结果
     */
    private Resolution users(Collection<String> users) { return new Resolution(immutableOrderedSet(users), Set.of()); }

    /**
     * 构造仅包含候选组的解析结果。
     * @param groups Collection&lt;String&gt;，资格过滤后的候选组编码
     * @return Resolution，不可变候选组解析结果
     */
    private Resolution groups(Collection<String> groups) { return new Resolution(Set.of(), immutableOrderedSet(groups)); }

    private record DefinitionContext(String deploymentId, String processKey) { }
    private record Resolution(Set<String> userIds, Set<String> groupIds)
    {
        private Resolution
        {
            userIds = Collections.unmodifiableSet(new LinkedHashSet<>(userIds));
            groupIds = Collections.unmodifiableSet(new LinkedHashSet<>(groupIds));
        }
    }
}
