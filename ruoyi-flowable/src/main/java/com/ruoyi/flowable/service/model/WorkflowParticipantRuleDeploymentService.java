package com.ruoyi.flowable.service.model;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.UserTask;
import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployParticipantRule;
import com.ruoyi.flowable.mapper.WorkflowIdentityMapper;
import com.ruoyi.flowable.service.model.WorkflowParticipantRuleBpmnContract.StartRule;
import com.ruoyi.flowable.service.model.WorkflowParticipantRuleBpmnContract.TaskRule;

/**
 * 编译流程发起范围和单实例用户任务参与者规则并生成不可变快照。
 */
@Service
public class WorkflowParticipantRuleDeploymentService
{
    private final WorkflowIdentityMapper identityMapper;

    /**
     * 创建参与者规则部署服务。
     * @param identityMapper WorkflowIdentityMapper，正式组织和审批资格 Mapper
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowParticipantRuleDeploymentService(WorkflowIdentityMapper identityMapper)
    {
        this.identityMapper = identityMapper;
    }

    /**
     * 解析作者规则、核验正式目录与表单字段，并从执行 BPMN 剥离作者属性。
     * @param authorDocument WorkflowBpmnDocument，已通过作者安全校验的 BPMN
     * @param inputBpmn byte[]，前序编译器生成的 BPMN
     * @param formFieldCatalog WorkflowAuthorFormFieldCatalog，本次事务冻结的正式用户主键字段目录
     * @param actorUserId String，部署设计者正式用户主键
     * @return WorkflowPreparedParticipantRuleDeployment，执行资源和待落库快照
     */
    public WorkflowPreparedParticipantRuleDeployment prepare(WorkflowBpmnDocument authorDocument,
            byte[] inputBpmn, WorkflowAuthorFormFieldCatalog formFieldCatalog,
            String actorUserId)
    {
        if (authorDocument == null || inputBpmn == null || inputBpmn.length == 0)
        {
            throw new ServiceException("参与者规则部署输入不完整", HttpStatus.ERROR);
        }
        List<WfDeployParticipantRule> snapshots = collectAndValidate(
                authorDocument.bpmnModel(), formFieldCatalog, actorUserId);

        BpmnXMLConverter converter = new BpmnXMLConverter();
        BpmnModel compiled = converter.convertToBpmnModel(
                () -> new ByteArrayInputStream(inputBpmn), true, true);
        for (Process process : compiled.getProcesses())
        {
            WorkflowParticipantRuleBpmnContract.removeAuthorProperties(process);
            for (UserTask task : process.findFlowElementsOfType(UserTask.class, true))
            {
                WorkflowParticipantRuleBpmnContract.removeAuthorProperties(task);
            }
        }
        byte[] compiledBytes = converter.convertToXML(compiled);
        if (compiledBytes == null || compiledBytes.length == 0)
        {
            throw new ServiceException("参与者规则执行资源编译失败", HttpStatus.ERROR);
        }
        return new WorkflowPreparedParticipantRuleDeployment(compiledBytes, snapshots);
    }

    /**
     * 无副作用核验作者参与者规则的正式身份目标和 FORM_USER 字段。
     *
     * 显式校验、模型保存和部署预检均调用该方法；它只查询正式身份目录并解析本次冻结的
     * 表单字段目录，不写规则快照或 Flowable 模型，也不介入模型摘要并发控制。
     *
     * @param authorDocument WorkflowBpmnDocument，已通过作者安全校验的 BPMN
     * @param formFieldCatalog WorkflowAuthorFormFieldCatalog，本次事务冻结的正式用户主键字段目录
     * @return void，任一目标失效或字段不合格时抛出稳定业务异常
     */
    public void validateAuthorRules(WorkflowBpmnDocument authorDocument,
            WorkflowAuthorFormFieldCatalog formFieldCatalog)
    {
        if (authorDocument == null || formFieldCatalog == null)
        {
            throw new ServiceException("参与者规则作者校验输入不完整", HttpStatus.ERROR);
        }
        collectAndValidate(authorDocument.bpmnModel(), formFieldCatalog, "");
    }

    /**
     * 收集每个可执行流程的一条发起范围及每个受控单实例任务的一条办理规则。
     * @param model BpmnModel，作者 BPMN 公共模型
     * @param formFieldCatalog WorkflowAuthorFormFieldCatalog，按流程和节点隔离的正式字段目录
     * @param actorUserId String，部署操作人主键
     * @return List&lt;WfDeployParticipantRule&gt;，字段完整且稳定排序的快照
     */
    private List<WfDeployParticipantRule> collectAndValidate(BpmnModel model,
            WorkflowAuthorFormFieldCatalog formFieldCatalog, String actorUserId)
    {
        List<WfDeployParticipantRule> snapshots = new ArrayList<>();
        for (Process process : model.getProcesses())
        {
            if (!process.isExecutable()) continue;
            StartRule startRule = readStartRule(process);
            validateStartTargets(startRule);
            snapshots.add(toStartSnapshot(startRule, actorUserId));
            for (UserTask task : process.findFlowElementsOfType(UserTask.class, true))
            {
                TaskRule rule = readTaskRule(process, task);
                if (rule == null) continue;
                if ("FORM_USER".equals(rule.type())
                        && !formFieldCatalog.containsTaskField(
                                process.getId(), task.getId(), rule.formField()))
                {
                    throw invalid(task, "表单用户字段必须来自当前任务正式表单中可见、可读的单值字段");
                }
                validateTaskTargets(rule, task);
                snapshots.add(toTaskSnapshot(rule, actorUserId));
            }
        }
        return List.copyOf(snapshots);
    }

    /**
     * 读取并校验流程级发起范围作者契约，将格式错误转换为稳定 API 子码。
     * @param process Process，可执行 BPMN 流程
     * @return StartRule，字段完整的规范发起范围
     */
    private StartRule readStartRule(Process process)
    {
        try { return WorkflowParticipantRuleBpmnContract.readStartRule(process); }
        catch (IllegalArgumentException exception)
        {
            throw new ServiceException(exception.getMessage(), HttpStatus.BAD_REQUEST)
                    .setSubCode("BPMN_START_SCOPE_INVALID");
        }
    }

    /**
     * 读取单实例用户任务规则；未配置规则时返回空，由历史兼容链处理。
     * @param process Process，任务所属可执行流程
     * @param task UserTask，待读取作者属性的用户任务
     * @return TaskRule，规范任务规则或 null
     */
    private TaskRule readTaskRule(Process process, UserTask task)
    {
        try
        {
            return WorkflowParticipantRuleBpmnContract.readTaskRule(process.getId(), task)
                    .orElse(null);
        }
        catch (IllegalArgumentException exception)
        {
            ServiceException failure = invalid(task, exception.getMessage());
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * 部署时验证发起范围引用仍为正式有效目录对象。
     * @param rule StartRule，规范发起范围
     * @return void，任一引用失效时拒绝部署
     */
    private void validateStartTargets(StartRule rule)
    {
        Set<Long> expected = Set.copyOf(rule.targetIds());
        List<Long> actual = switch (rule.type())
        {
            case "USERS" -> activeUsers(rule.targetIds());
            case "ROLES" -> activeRoles(rule.targetIds());
            case "DEPTS" -> activeDepts(rule.targetIds());
            default -> List.of();
        };
        if (!"PUBLIC".equals(rule.type())) requireExact(expected, actual,
                "流程发起范围引用的身份不存在、已停用或已删除");
    }

    /**
     * 部署时按任务输出模式核验静态目标及对应审批或认领资格。
     * @param rule TaskRule，规范任务规则
     * @param task UserTask，错误提示使用的任务节点
     * @return void，目录或资格不完整时拒绝部署
     */
    private void validateTaskTargets(TaskRule rule, UserTask task)
    {
        List<Long> targets = rule.targetIds();
        switch (rule.type())
        {
            case "FIXED_USER" -> requireExact(Set.copyOf(targets), approvalUsers(targets),
                    task.getName() + " 的固定办理人已失效或不具备办理资格");
            case "CANDIDATE_USERS" -> requireExact(Set.copyOf(targets), claimUsers(targets),
                    task.getName() + " 的候选用户不具备完整认领资格");
            case "CANDIDATE_GROUPS" -> validateCandidateGroups(targets, task);
            case "DEPT_MANAGER" ->
            {
                List<Long> deptIds = List.of(targets.get(0));
                requireExact(Set.copyOf(deptIds), activeDepts(deptIds), "指定部门已失效");
                List<Long> leaders = safe(identityMapper.selectApprovalEligibleDeptLeaderUserIds(deptIds));
                if (leaders.size() != 1) throw invalid(task, "指定部门必须存在唯一且具备办理资格的负责人");
            }
            case "STARTER_DEPT_ROLE" -> requireExact(Set.copyOf(targets), claimRoles(targets),
                    "指定角色必须至少包含一名具备完整认领资格的有效成员");
            default -> { }
        }
    }

    /**
     * 分离角色和部门哨兵主键，并核验每个候选组仍有合格认领成员。
     * @param targets List&lt;Long&gt;，正数为角色、负数为部门的规范目标
     * @param task UserTask，错误信息对应的任务节点
     * @return void，任一候选组失效时拒绝部署
     */
    private void validateCandidateGroups(List<Long> targets, UserTask task)
    {
        List<Long> roleIds = targets.stream().filter(id -> id > 0).toList();
        List<Long> deptIds = targets.stream().filter(id -> id < 0).map(Math::abs).toList();
        requireExact(Set.copyOf(roleIds), claimRoles(roleIds),
                task.getName() + " 的候选角色没有合格认领成员");
        requireExact(Set.copyOf(deptIds), claimDepts(deptIds),
                task.getName() + " 的候选部门没有合格认领成员");
    }

    /**
     * 查询仍启用且未删除的正式用户。
     * @param ids List&lt;Long&gt;，用户主键
     * @return List&lt;Long&gt;，有效用户主键
     */
    private List<Long> activeUsers(List<Long> ids) { return ids.isEmpty() ? List.of() : safe(identityMapper.selectActiveUserIdsByUserIds(ids)); }
    /**
     * 查询仍启用且未删除的正式角色。
     * @param ids List&lt;Long&gt;，角色主键
     * @return List&lt;Long&gt;，有效角色主键
     */
    private List<Long> activeRoles(List<Long> ids) { return ids.isEmpty() ? List.of() : safe(identityMapper.selectActiveRoleIdsByRoleIds(ids)); }
    /**
     * 查询仍启用且未删除的正式部门。
     * @param ids List&lt;Long&gt;，部门主键
     * @return List&lt;Long&gt;，有效部门主键
     */
    private List<Long> activeDepts(List<Long> ids) { return ids.isEmpty() ? List.of() : safe(identityMapper.selectActiveDeptIdsByDeptIds(ids)); }
    /**
     * 查询仍具备流程直接办理权限和数据范围的用户。
     * @param ids List&lt;Long&gt;，用户主键
     * @return List&lt;Long&gt;，具备直接办理资格的用户主键
     */
    private List<Long> approvalUsers(List<Long> ids) { return ids.isEmpty() ? List.of() : safe(identityMapper.selectApprovalEligibleUserIdsByUserIds(ids)); }
    /**
     * 查询仍具备候选任务认领闭环权限的用户。
     * @param ids List&lt;Long&gt;，用户主键
     * @return List&lt;Long&gt;，具备认领资格的用户主键
     */
    private List<Long> claimUsers(List<Long> ids) { return ids.isEmpty() ? List.of() : safe(identityMapper.selectClaimEligibleUserIdsByUserIds(ids)); }
    /**
     * 查询至少包含一名合格认领成员的有效角色。
     * @param ids List&lt;Long&gt;，角色主键
     * @return List&lt;Long&gt;，含合格认领成员的角色主键
     */
    private List<Long> claimRoles(List<Long> ids) { return ids.isEmpty() ? List.of() : safe(identityMapper.selectClaimEligibleRoleIdsByRoleIds(ids)); }
    /**
     * 查询至少包含一名合格认领成员的有效部门。
     * @param ids List&lt;Long&gt;，部门主键
     * @return List&lt;Long&gt;，含合格认领成员的部门主键
     */
    private List<Long> claimDepts(List<Long> ids) { return ids.isEmpty() ? List.of() : safe(identityMapper.selectClaimEligibleDeptIdsByDeptIds(ids)); }

    /**
     * 校验正式目录 Mapper 返回的主键集合没有空值或非法值。
     * @param values List&lt;Long&gt;，目录查询结果
     * @return List&lt;Long&gt;，原始合法查询结果
     */
    private List<Long> safe(List<Long> values)
    {
        if (values == null || values.stream().anyMatch(value -> value == null || value <= 0))
            throw new ServiceException("参与者规则身份主数据异常", HttpStatus.ERROR);
        return values;
    }

    /**
     * 要求目录查询结果与作者选择完全一致，阻止部分失效目标被静默忽略。
     * @param expected Set&lt;Long&gt;，作者规则引用的全部主键
     * @param actual List&lt;Long&gt;，实时目录中仍有效且合格的主键
     * @param message String，资格不完整时的稳定业务提示
     * @return void，集合不一致时抛出 409
     */
    private void requireExact(Set<Long> expected, List<Long> actual, String message)
    {
        if (!new HashSet<>(actual).equals(expected))
            throw new ServiceException(message, HttpStatus.CONFLICT)
                    .setSubCode("PARTICIPANT_IDENTITY_INELIGIBLE");
    }

    /**
     * 将规范发起范围转换为待绑定部署主键的持久化快照。
     * @param rule StartRule，已完成目录校验的发起规则
     * @param actorUserId String，部署操作人主键
     * @return WfDeployParticipantRule，字段完整的 START 快照
     */
    private WfDeployParticipantRule toStartSnapshot(StartRule rule, String actorUserId)
    {
        WfDeployParticipantRule snapshot = base(rule.processKey(), "", "", "START",
                "START", rule.type(), rule.ruleVersion(), rule.noMatchPolicy(),
                rule.checksum(), actorUserId);
        snapshot.setTargetIds(WorkflowParticipantRuleBpmnContract.join(rule.targetIds()));
        return snapshot;
    }

    /**
     * 将规范单实例任务规则转换为待绑定部署主键的持久化快照。
     * @param rule TaskRule，已完成目录和表单字段校验的任务规则
     * @param actorUserId String，部署操作人主键
     * @return WfDeployParticipantRule，字段完整的 TASK 快照
     */
    private WfDeployParticipantRule toTaskSnapshot(TaskRule rule, String actorUserId)
    {
        WfDeployParticipantRule snapshot = base(rule.processKey(), rule.activityId(),
                rule.activityName(), "TASK", rule.assignmentMode(), rule.type(),
                rule.ruleVersion(), rule.noMatchPolicy(), rule.checksum(), actorUserId);
        if ("CANDIDATE_GROUPS".equals(rule.type()))
        {
            snapshot.setTargetIds(rule.targetIds().stream()
                    .map(id -> id > 0 ? "ROLE" + id : "DEPT" + Math.abs(id))
                    .collect(java.util.stream.Collectors.joining(",")));
        }
        else snapshot.setTargetIds(WorkflowParticipantRuleBpmnContract.join(rule.targetIds()));
        snapshot.setFormField(rule.formField().isEmpty() ? null : rule.formField());
        return snapshot;
    }

    /**
     * 构造 START/TASK 共用的不可变规则快照字段。
     * @param processKey String，流程定义 key
     * @param activityId String，任务节点 key；START 为空串
     * @param activityName String，任务节点显示名；START 为空串
     * @param scope String，START 或 TASK
     * @param mode String，START、ASSIGNEE 或 CANDIDATE
     * @param type String，受控规则类型
     * @param version int，作者规则版本
     * @param noMatchPolicy String，无匹配策略，当前固定为 FAIL
     * @param checksum String，规范规则校验和
     * @param actorUserId String，部署操作人主键
     * @return WfDeployParticipantRule，未绑定 deploymentId 的基础快照
     */
    private WfDeployParticipantRule base(String processKey, String activityId,
            String activityName, String scope, String mode, String type, int version,
            String noMatchPolicy, String checksum, String actorUserId)
    {
        WfDeployParticipantRule snapshot = new WfDeployParticipantRule();
        snapshot.setProcessKey(processKey);
        snapshot.setActivityId(activityId);
        snapshot.setActivityName(activityName);
        snapshot.setRuleScope(scope);
        snapshot.setAssignmentMode(mode);
        snapshot.setRuleType(type);
        snapshot.setNoMatchPolicy(noMatchPolicy);
        snapshot.setRuleVersion(version);
        snapshot.setChecksum(checksum);
        snapshot.setCreateBy(actorUserId);
        return snapshot;
    }

    /**
     * 创建带节点名称和稳定子码的参与者规则格式异常。
     * @param element FlowElement，发生错误的 BPMN 元素
     * @param message String，具体业务约束提示
     * @return ServiceException，HTTP 400 的部署前校验异常
     */
    private ServiceException invalid(FlowElement element, String message)
    {
        String name = element == null ? "" : element.getName();
        String id = element == null ? "" : element.getId();
        return new ServiceException("用户任务 " + (name == null || name.isBlank() ? id : name)
                + "：" + message, HttpStatus.BAD_REQUEST)
                .setSubCode("BPMN_PARTICIPANT_RULE_INVALID");
    }
}
