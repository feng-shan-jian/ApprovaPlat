package com.ruoyi.flowable.service.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.UserTask;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;
import com.ruoyi.flowable.mapper.WorkflowIdentityMapper;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceModelContract;

/**
 * 部署前批量核验 BPMN 中的静态办理人、候选角色和候选部门主数据。
 */
@Component
public class WorkflowBpmnIdentityValidator
{
    /** 新框架候选组使用的规范角色编码。 */
    private static final Pattern ROLE_PATTERN = Pattern.compile("ROLE([1-9][0-9]*)");

    /** 新框架候选组使用的规范部门编码。 */
    private static final Pattern DEPT_PATTERN = Pattern.compile("DEPT([1-9][0-9]*)");

    private final WorkflowIdentityMapper identityMapper;

    /**
     * 创建 BPMN 静态身份校验器。
     *
     * @param identityMapper WorkflowIdentityMapper，若依正式用户、角色和部门数据访问层
     * @return 无返回值，构造后由 Spring 管理该校验器
     */
    public WorkflowBpmnIdentityValidator(WorkflowIdentityMapper identityMapper)
    {
        this.identityMapper = identityMapper;
    }

    /**
     * 批量核验文档中的静态身份；表达式由运行时变量解析，不在部署时伪造解析结果。
     *
     * @param document WorkflowBpmnDocument，已经通过安全和 Flowable 规则校验的文档
     * @return 无返回值；编码非法或主数据无效时抛出 HTTP 409 业务异常
     */
    public void validate(WorkflowBpmnDocument document)
    {
        if (document == null)
        {
            throw conflict("BPMN 身份校验上下文不能为空");
        }
        // approvalUserIds 保存直接办理人/owner，claimUserIds 保存候选用户；两类用户先合并核验存在性，再分别核验 RBAC。
        LinkedHashSet<Long> approvalUserIds = new LinkedHashSet<>();
        LinkedHashSet<Long> claimUserIds = new LinkedHashSet<>();
        // roleIds/deptIds 只保存任务候选组，避免自动抄送组被错误套用认领资格语义。
        LinkedHashSet<Long> roleIds = new LinkedHashSet<>();
        LinkedHashSet<Long> deptIds = new LinkedHashSet<>();
        // configured*Ids 只保存会签/或签指定角色和部门，直接分配任务必须使用 approval 而非 claim 资格。
        LinkedHashSet<Long> configuredRoleIds = new LinkedHashSet<>();
        LinkedHashSet<Long> configuredDeptIds = new LinkedHashSet<>();
        // autoCopy*Ids 保存部署后冻结的抄送身份，必须独立核验抄送列表和详情可见性。
        LinkedHashSet<Long> autoCopyUserIds = new LinkedHashSet<>();
        LinkedHashSet<Long> autoCopyRoleIds = new LinkedHashSet<>();
        LinkedHashSet<Long> autoCopyDeptIds = new LinkedHashSet<>();

        for (Process process : document.bpmnModel().getProcesses())
        {
            collectAutoCopyIdentities(process, autoCopyUserIds,
                    autoCopyRoleIds, autoCopyDeptIds);
            for (UserTask task : process.findFlowElementsOfType(UserTask.class, true))
            {
                if (!hasAssignment(task))
                {
                    throw taskConflict(task, "必须配置办理人、候选用户或候选组");
                }
                collectStaticUser(task.getAssignee(), task, "办理人", approvalUserIds);
                collectStaticUser(task.getOwner(), task, "任务所有人", approvalUserIds);
                collectStaticUsers(task.getCandidateUsers(), task, claimUserIds);
                collectStaticGroups(task.getCandidateGroups(), task, roleIds, deptIds);
                collectFixedMultiInstanceUsers(task, approvalUserIds);
                collectConfiguredMultiInstanceIdentities(task, approvalUserIds,
                        configuredRoleIds, configuredDeptIds);
                collectAutoCopyIdentities(task, autoCopyUserIds,
                        autoCopyRoleIds, autoCopyDeptIds);
            }
        }

        LinkedHashSet<Long> userIds = new LinkedHashSet<>(approvalUserIds);
        userIds.addAll(claimUserIds);
        userIds.addAll(autoCopyUserIds);
        LinkedHashSet<Long> allRoleIds = new LinkedHashSet<>(roleIds);
        allRoleIds.addAll(configuredRoleIds);
        allRoleIds.addAll(autoCopyRoleIds);
        LinkedHashSet<Long> allDeptIds = new LinkedHashSet<>(deptIds);
        allDeptIds.addAll(configuredDeptIds);
        allDeptIds.addAll(autoCopyDeptIds);
        requireAllActive(userIds, activeUsers(userIds), "用户");
        requireAllActive(allRoleIds, activeRoles(allRoleIds), "角色");
        requireAllActive(allDeptIds, activeDepts(allDeptIds), "部门");

        // 身份存在不代表用户能走通真实办理入口；部署前必须按 assignment 类型复核实时 RBAC。
        requireAllEligible(approvalUserIds, approvalEligibleUsers(approvalUserIds),
                "办理用户不具备流程办理资格");
        requireAllEligible(claimUserIds, claimEligibleUsers(claimUserIds),
                "候选用户不具备完整认领资格");
        requireAllEligible(roleIds, claimEligibleRoles(roleIds),
                "候选角色没有具备完整认领资格的有效成员");
        requireAllEligible(deptIds, claimEligibleDepts(deptIds),
                "候选部门没有具备完整认领资格的有效成员");
        requireAllEligible(configuredRoleIds,
                approvalEligibleRoles(configuredRoleIds),
                "指定会签或或签角色没有具备流程办理资格的有效成员");
        requireAllEligible(configuredDeptIds,
                approvalEligibleDepts(configuredDeptIds),
                "指定会签或或签部门没有具备流程办理资格的有效成员");
        // 自动抄送对象会直接获得实例可读授权，因此部署时必须证明接收人能进入抄送列表和流程详情。
        requireAllEligible(autoCopyUserIds, copyEligibleUsers(autoCopyUserIds),
                "自动抄送用户不具备抄送列表和流程详情权限");
        requireEachAutoCopyGroupEligible(autoCopyRoleIds, true);
        requireEachAutoCopyGroupEligible(autoCopyDeptIds, false);
    }

    /**
     * 收集自动抄送规则中的固定用户、角色和部门，发起人及表单字段留到运行时解析。
     *
     * @param element org.flowable.bpmn.model.BaseElement，流程或用户任务元素
     * @param userIds Set&lt;Long&gt;，待核验固定用户主键
     * @param roleIds Set&lt;Long&gt;，待核验固定角色主键
     * @param deptIds Set&lt;Long&gt;，待核验固定部门主键
     * @return void，规则结构已由 BPMN 门禁校验，此处只冻结正式主数据引用
     */
    private void collectAutoCopyIdentities(org.flowable.bpmn.model.BaseElement element,
            Set<Long> userIds, Set<Long> roleIds, Set<Long> deptIds)
    {
        for (WorkflowAutoCopyRuleContract.Rule rule
                : WorkflowAutoCopyRuleContract.readRules(element))
        {
            for (WorkflowAutoCopyRuleContract.RecipientSource source : rule.recipients())
            {
                if (source.type() == WorkflowAutoCopyRuleContract.RecipientType.USER)
                {
                    source.values().forEach(value -> userIds.add(Long.valueOf(value)));
                }
                else if (source.type() == WorkflowAutoCopyRuleContract.RecipientType.GROUP)
                {
                    for (String value : source.values())
                    {
                        if (value.startsWith("ROLE"))
                        {
                            roleIds.add(Long.valueOf(value.substring(4)));
                        }
                        else
                        {
                            deptIds.add(Long.valueOf(value.substring(4)));
                        }
                    }
                }
            }
        }
    }

    /**
     * 判断用户任务是否至少配置一种能被当前任务查询和认领链路消费的办理身份。
     *
     * @param task UserTask，待部署的用户任务
     * @return boolean，存在办理人、候选用户或候选组时返回 true
     */
    private boolean hasAssignment(UserTask task)
    {
        return hasText(task.getAssignee())
                || hasAnyText(task.getCandidateUsers())
                || hasAnyText(task.getCandidateGroups())
                || WorkflowParticipantRuleBpmnContract.hasTaskProperties(task);
    }

    /**
     * 判断身份列表中是否至少存在一个非空配置。
     *
     * @param values List&lt;String&gt;，候选用户或候选组配置，允许为空
     * @return boolean，列表中存在非空身份时返回 true
     */
    private boolean hasAnyText(List<String> values)
    {
        return values != null && values.stream().anyMatch(this::hasText);
    }

    /**
     * 收集单个静态用户身份，表达式留给 Flowable 在运行时解析。
     *
     * @param value String，办理人或 owner 配置
     * @param task UserTask，身份所属用户任务
     * @param fieldName String，对外错误中的字段名称
     * @param userIds Set&lt;Long&gt;，待批量核验的用户主键集合
     * @return 无返回值；静态编码不是正整数时抛出冲突异常
     */
    private void collectStaticUser(String value, UserTask task, String fieldName,
            Set<Long> userIds)
    {
        if (!hasText(value) || isExpression(value))
        {
            return;
        }
        userIds.add(parsePositiveId(value, task, fieldName));
    }

    /**
     * 收集用户任务候选用户中的全部静态用户主键。
     *
     * @param values List&lt;String&gt;，候选用户配置，允许为空
     * @param task UserTask，身份所属用户任务
     * @param userIds Set&lt;Long&gt;，待批量核验的用户主键集合
     * @return 无返回值；非法静态编码会立即拒绝部署
     */
    private void collectStaticUsers(List<String> values, UserTask task, Set<Long> userIds)
    {
        if (values == null)
        {
            return;
        }
        for (String value : values)
        {
            collectStaticUser(value, task, "候选用户", userIds);
        }
    }

    /**
     * 收集固定会签或或签节点中由受控集合表达式声明的直接办理用户。
     *
     * @param task UserTask，可能携带固定多实例集合的用户任务。
     * @param approvalUserIds Set<Long>，待按直接办理权限批量核验的用户主键集合。
     * @return 无返回值；结构已由 BPMN 校验门禁确认，此处只合并正式身份引用。
     */
    private void collectFixedMultiInstanceUsers(UserTask task, Set<Long> approvalUserIds)
    {
        if (!WorkflowMultiInstanceModelContract.usesFixedHandler(task.getLoopCharacteristics()))
        {
            return;
        }
        approvalUserIds.addAll(WorkflowMultiInstanceModelContract.requireFixedUserIds(
                task.getLoopCharacteristics()));
    }

    /**
     * 收集指定多实例用户、角色和部门，并按单节点核验角色或部门展开后的最终人数。
     *
     * @param task UserTask，可能使用指定身份集合的会签或或签任务
     * @param approvalUserIds Set&lt;Long&gt;，待核验的指定用户主键
     * @param configuredRoleIds Set&lt;Long&gt;，待核验的指定角色主键
     * @param configuredDeptIds Set&lt;Long&gt;，待核验的指定部门主键
     * @return 无返回值；配置身份无法展开为 1 至 100 名审批用户时拒绝部署
     */
    private void collectConfiguredMultiInstanceIdentities(UserTask task,
            Set<Long> approvalUserIds, Set<Long> configuredRoleIds,
            Set<Long> configuredDeptIds)
    {
        if (!WorkflowMultiInstanceModelContract.usesConfiguredHandler(
                task.getLoopCharacteristics()))
        {
            return;
        }
        WorkflowMultiInstanceModelContract.ConfiguredIdentity identity;
        try
        {
            identity = WorkflowMultiInstanceModelContract.requireConfiguredIdentity(task);
        }
        catch (IllegalArgumentException exception)
        {
            throw taskConflict(task, exception.getMessage());
        }
        switch (identity.type())
        {
            case USER -> approvalUserIds.addAll(identity.targetIds());
            case ROLE ->
            {
                configuredRoleIds.addAll(identity.targetIds());
                requireConfiguredExpansion(task, safeList(
                        identityMapper.selectApprovalEligibleUserIdsByRoleIds(
                                identity.targetIds())), "角色");
            }
            case DEPT ->
            {
                configuredDeptIds.addAll(identity.targetIds());
                requireConfiguredExpansion(task, safeList(
                        identityMapper.selectApprovalEligibleUserIdsByDeptIds(
                                identity.targetIds())), "部门");
            }
        }
    }

    /**
     * 核验单个多实例节点展开后的用户集合，防止空组、异常主键和超量实例进入部署。
     *
     * @param task UserTask，指定身份所属会签或或签任务
     * @param userIds List&lt;Long&gt;，Mapper 按审批资格展开的用户主键
     * @param groupName String，角色或部门业务名称
     * @return 无返回值；最终成员不是 1 至 100 名规范唯一用户时抛出部署冲突
     */
    private void requireConfiguredExpansion(UserTask task, List<Long> userIds,
            String groupName)
    {
        LinkedHashSet<Long> uniqueUserIds = new LinkedHashSet<>();
        for (Long userId : userIds)
        {
            if (userId == null || userId <= 0 || !uniqueUserIds.add(userId))
            {
                throw taskConflict(task, "指定" + groupName + "成员主数据异常");
            }
        }
        if (uniqueUserIds.isEmpty()
                || uniqueUserIds.size() > WorkflowUserSelectionValidator.MAX_SELECTED_USERS)
        {
            throw taskConflict(task, "指定" + groupName
                    + "必须展开为 1 至 100 名具备流程办理资格的用户");
        }
    }

    /**
     * 收集规范 ROLE/DEPT 候选组并拒绝无法由新身份桥解析的静态编码。
     *
     * @param values List&lt;String&gt;，候选组配置，允许为空
     * @param task UserTask，身份所属用户任务
     * @param roleIds Set&lt;Long&gt;，待核验角色主键集合
     * @param deptIds Set&lt;Long&gt;，待核验部门主键集合
     * @return 无返回值；未知候选组编码会拒绝部署
     */
    private void collectStaticGroups(List<String> values, UserTask task,
            Set<Long> roleIds, Set<Long> deptIds)
    {
        if (values == null)
        {
            return;
        }
        for (String rawValue : values)
        {
            if (!hasText(rawValue) || isExpression(rawValue))
            {
                continue;
            }
            String value = rawValue;
            Matcher roleMatcher = ROLE_PATTERN.matcher(value);
            Matcher deptMatcher = DEPT_PATTERN.matcher(value);
            if (roleMatcher.matches())
            {
                roleIds.add(parsePositiveId(roleMatcher.group(1), task, "候选角色"));
            }
            else if (deptMatcher.matches())
            {
                deptIds.add(parsePositiveId(deptMatcher.group(1), task, "候选部门"));
            }
            else
            {
                throw taskConflict(task, "候选组必须使用 ROLE<id> 或 DEPT<id> 编码");
            }
        }
    }

    /**
     * 解析规范正整数身份主键并把前导零、空白、符号及溢出统一映射为部署冲突。
     *
     * @param value String，待解析数字文本
     * @param task UserTask，身份所属用户任务
     * @param fieldName String，身份字段名称
     * @return long，正数身份主键
     */
    private long parsePositiveId(String value, UserTask task, String fieldName)
    {
        try
        {
            long id = Long.parseLong(value);
            if (id <= 0 || !Long.toString(id).equals(value))
            {
                throw taskConflict(task, fieldName + "必须使用正整数身份主键");
            }
            return id;
        }
        catch (NumberFormatException exception)
        {
            ServiceException failure = taskConflict(task,
                    fieldName + "必须使用正整数身份主键");
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * 查询有效用户主键，空集合时避免生成非法 IN SQL。
     *
     * @param ids Set&lt;Long&gt;，静态用户主键
     * @return List&lt;Long&gt;，当前有效用户主键
     */
    private List<Long> activeUsers(Set<Long> ids)
    {
        return ids.isEmpty() ? List.of()
                : safeList(identityMapper.selectActiveUserIdsByUserIds(new ArrayList<>(ids)));
    }

    /**
     * 查询有效角色主键，空集合时避免生成非法 IN SQL。
     *
     * @param ids Set&lt;Long&gt;，静态角色主键
     * @return List&lt;Long&gt;，当前有效角色主键
     */
    private List<Long> activeRoles(Set<Long> ids)
    {
        return ids.isEmpty() ? List.of()
                : safeList(identityMapper.selectActiveRoleIdsByRoleIds(new ArrayList<>(ids)));
    }

    /**
     * 查询有效部门主键，空集合时避免生成非法 IN SQL。
     *
     * @param ids Set&lt;Long&gt;，静态部门主键
     * @return List&lt;Long&gt;，当前有效部门主键
     */
    private List<Long> activeDepts(Set<Long> ids)
    {
        return ids.isEmpty() ? List.of()
                : safeList(identityMapper.selectActiveDeptIdsByDeptIds(new ArrayList<>(ids)));
    }

    /**
     * 查询静态办理人和任务所有人中的实时合格办理用户。
     *
     * @param ids Set&lt;Long&gt;，办理人和任务所有人主键集合
     * @return List&lt;Long&gt;，具备直接办理完整权限的有效用户主键
     */
    private List<Long> approvalEligibleUsers(Set<Long> ids)
    {
        return ids.isEmpty() ? List.of()
                : safeList(identityMapper.selectApprovalEligibleUserIdsByUserIds(
                        new ArrayList<>(ids)));
    }

    /**
     * 查询静态候选用户中的实时合格认领用户。
     *
     * @param ids Set&lt;Long&gt;，候选用户主键集合
     * @return List&lt;Long&gt;，具备待签、认领和后续办理完整权限的用户主键
     */
    private List<Long> claimEligibleUsers(Set<Long> ids)
    {
        return ids.isEmpty() ? List.of()
                : safeList(identityMapper.selectClaimEligibleUserIdsByUserIds(
                        new ArrayList<>(ids)));
    }

    /**
     * 查询至少包含一名合格认领成员的静态候选角色。
     *
     * @param ids Set&lt;Long&gt;，候选角色主键集合
     * @return List&lt;Long&gt;，成员可走通完整认领办理路径的角色主键
     */
    private List<Long> claimEligibleRoles(Set<Long> ids)
    {
        return ids.isEmpty() ? List.of()
                : safeList(identityMapper.selectClaimEligibleRoleIdsByRoleIds(
                        new ArrayList<>(ids)));
    }

    /**
     * 查询至少包含一名合格认领成员的静态候选部门。
     *
     * @param ids Set&lt;Long&gt;，候选部门主键集合
     * @return List&lt;Long&gt;，成员可走通完整认领办理路径的部门主键
     */
    private List<Long> claimEligibleDepts(Set<Long> ids)
    {
        return ids.isEmpty() ? List.of()
                : safeList(identityMapper.selectClaimEligibleDeptIdsByDeptIds(
                        new ArrayList<>(ids)));
    }

    /**
     * 查询每个指定多实例角色是否至少包含一名实时合格办理用户。
     *
     * @param ids Set&lt;Long&gt;，设计时指定角色主键集合
     * @return List&lt;Long&gt;，可展开真实直接办理任务的角色主键
     */
    private List<Long> approvalEligibleRoles(Set<Long> ids)
    {
        return ids.isEmpty() ? List.of()
                : safeList(identityMapper.selectApprovalEligibleRoleIdsByRoleIds(
                        new ArrayList<>(ids)));
    }

    /**
     * 查询每个指定多实例部门是否至少包含一名实时合格办理用户。
     *
     * @param ids Set&lt;Long&gt;，设计时指定部门主键集合
     * @return List&lt;Long&gt;，可展开真实直接办理任务的部门主键
     */
    private List<Long> approvalEligibleDepts(Set<Long> ids)
    {
        return ids.isEmpty() ? List.of()
                : safeList(identityMapper.selectApprovalEligibleDeptIdsByDeptIds(
                        new ArrayList<>(ids)));
    }

    /**
     * 查询自动抄送固定用户中的实时可见用户。
     *
     * @param ids Set&lt;Long&gt;，自动抄送规则冻结的固定用户主键
     * @return List&lt;Long&gt;，同时具备抄送列表和流程详情权限的有效用户主键
     */
    private List<Long> copyEligibleUsers(Set<Long> ids)
    {
        return ids.isEmpty() ? List.of()
                : safeList(identityMapper.selectCopyEligibleUserIdsByUserIds(
                        new ArrayList<>(ids)));
    }

    /**
     * 逐个核验自动抄送角色或部门至少包含一名具备对象可见性的有效用户。
     *
     * @param ids Set&lt;Long&gt;，自动抄送规则冻结的角色或部门主键
     * @param roleGroup boolean，true 表示角色，false 表示部门
     * @return 无返回值；任一组无法解析出可读用户时拒绝部署
     */
    private void requireEachAutoCopyGroupEligible(Set<Long> ids, boolean roleGroup)
    {
        for (Long id : ids)
        {
            // Mapper 现有接口返回成员用户而非组主键，必须按组逐个查询，避免一个有效组掩盖同批无效组。
            List<Long> eligibleUsers = roleGroup
                    ? safeList(identityMapper.selectCopyEligibleUserIdsByRoleIds(List.of(id)))
                    : safeList(identityMapper.selectCopyEligibleUserIdsByDeptIds(List.of(id)));
            if (eligibleUsers.isEmpty())
            {
                String groupName = roleGroup ? "角色" : "部门";
                throw conflict("流程引用的自动抄送" + groupName
                        + "没有具备抄送列表和流程详情权限的有效成员: [" + id + "]");
            }
        }
    }

    /**
     * 断言所有 BPMN 静态引用仍存在且启用。
     *
     * @param expected Set&lt;Long&gt;，BPMN 引用的主键集合
     * @param active Collection&lt;Long&gt;，数据库返回的有效主键
     * @param typeName String，用户、角色或部门类型名称
     * @return 无返回值；存在缺失主键时拒绝部署
     */
    private void requireAllActive(Set<Long> expected, Collection<Long> active, String typeName)
    {
        if (expected.isEmpty())
        {
            return;
        }
        LinkedHashSet<Long> missing = new LinkedHashSet<>(expected);
        missing.removeAll(active);
        if (!missing.isEmpty())
        {
            throw conflict("流程引用的" + typeName + "不存在、已停用或已删除: " + missing);
        }
    }

    /**
     * 断言每个静态办理身份都能走通对应的真实页面和 API 主路径。
     *
     * @param expected Set&lt;Long&gt;，BPMN 中声明且已经确认有效的身份主键
     * @param eligible Collection&lt;Long&gt;，数据库按实时 RBAC 返回的合格身份主键
     * @param message String，向设计者说明具体资格缺口的业务错误
     * @return 无返回值；任一身份不具备对应能力时拒绝部署
     */
    private void requireAllEligible(Set<Long> expected, Collection<Long> eligible,
            String message)
    {
        if (expected.isEmpty())
        {
            return;
        }
        LinkedHashSet<Long> missing = new LinkedHashSet<>(expected);
        missing.removeAll(eligible);
        if (!missing.isEmpty())
        {
            throw conflict("流程引用的" + message + ": " + missing);
        }
    }

    /**
     * 将 Mapper 空返回归一为空列表，避免错误地绕过缺失引用检查。
     *
     * @param values List&lt;Long&gt;，Mapper 查询结果，允许为空
     * @return List&lt;Long&gt;，非空只读语义列表
     */
    private List<Long> safeList(List<Long> values)
    {
        return values == null ? List.of() : values;
    }

    /**
     * 判断配置是否为 Flowable 表达式，表达式只做安全校验并留到运行时解析。
     *
     * @param value String，身份配置文本
     * @return boolean，包含 ${...} 或 #{...} 时为 true
     */
    private boolean isExpression(String value)
    {
        return value.contains("${") || value.contains("#{");
    }

    /**
     * 判断文本是否包含非空白字符。
     *
     * @param value String，待判断文本
     * @return boolean，非空白时为 true
     */
    private boolean hasText(String value)
    {
        return value != null && !value.isBlank();
    }

    /**
     * 构造带任务定位信息的部署冲突异常。
     *
     * @param task UserTask，身份配置所属任务
     * @param message String，具体错误原因
     * @return ServiceException，HTTP 409 业务异常
     */
    private ServiceException taskConflict(UserTask task, String message)
    {
        String taskName = hasText(task.getName()) ? task.getName().trim() : task.getId();
        return conflict("用户任务“" + taskName + "”" + message);
    }

    /**
     * 构造统一部署身份冲突异常。
     *
     * @param message String，对外可定位的业务错误
     * @return ServiceException，HTTP 409 业务异常
     */
    private ServiceException conflict(String message)
    {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }
}
