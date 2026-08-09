package com.ruoyi.flowable.service.task;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;

/**
 * Flowable 表达式白名单 Bean，从受控变量解析动态多实例正式成员并初始化服务端快照。
 */
@Component("multiInstanceHandler")
public class WorkflowMultiInstanceHandler
{
    /** 执行树向上查找流程实例作用域时允许的最大深度。 */
    private static final int MAX_EXECUTION_DEPTH = 64;

    private final WorkflowUserSelectionValidator userSelectionValidator;

    /**
     * 创建动态多实例集合处理器。
     *
     * @param userSelectionValidator WorkflowUserSelectionValidator，正式启用用户严格校验器
     * @return 无返回值，构造后以 multiInstanceHandler 名称注册到 Spring
     */
    public WorkflowMultiInstanceHandler(WorkflowUserSelectionValidator userSelectionValidator)
    {
        this.userSelectionValidator = userSelectionValidator;
    }

    /**
     * 从当前活动专属变量解析 1 至 100 名正式用户，并在同一引擎事务初始化成员、模式和 revision。
     *
     * @param execution DelegateExecution，Flowable 正在创建多实例根的执行上下文
     * @return List&lt;String&gt;，保持首次出现顺序、去重且全部启用的数字用户 ID
     */
    public List<String> getUserIds(DelegateExecution execution)
    {
        if (execution == null)
        {
            throw invalidArgument();
        }

        String activityId;
        WorkflowMultiInstanceMode mode;
        try
        {
            activityId = WorkflowMultiInstanceVariables.requireActivityId(
                    execution.getCurrentActivityId());
            mode = WorkflowMultiInstanceModelContract.requireMode(
                    execution.getCurrentFlowElement());
        }
        catch (IllegalArgumentException exception)
        {
            throw invalidArgument();
        }

        Object rawUsers = execution.getVariable(
                WorkflowMultiInstanceVariables.userCollectionName(activityId));
        List<Long> requestedUserIds = requireBoundedPositiveUserIds(rawUsers);
        List<String> activeUserIds = userSelectionValidator.requireApprovalEligibleUserIds(
                requestedUserIds);
        if (activeUserIds.isEmpty())
        {
            throw invalidArgument();
        }

        return initializeMemberState(execution, activityId, mode, activeUserIds);
    }

    /**
     * 从 BPMN 固定集合参数解析成员并在进入节点时重新核验正式审批资格。
     *
     * @param execution DelegateExecution，Flowable 正在创建固定多实例根的执行上下文。
     * @param fixedUserIdsText String，设计器按受控 BPMN 集合表达式传入的逗号分隔用户主键。
     * @return List<String> 保持配置顺序、全部有效且具备审批资格的用户主键。
     */
    public List<String> getFixedUserIds(DelegateExecution execution, String fixedUserIdsText)
    {
        if (execution == null || fixedUserIdsText == null || fixedUserIdsText.isBlank())
        {
            throw invalidArgument();
        }
        List<Long> requestedUserIds = requireFixedUserIds(fixedUserIdsText);
        String activityId;
        WorkflowMultiInstanceMode mode;
        try
        {
            activityId = WorkflowMultiInstanceVariables.requireActivityId(
                    execution.getCurrentActivityId());
            mode = WorkflowMultiInstanceModelContract.requireMode(
                    execution.getCurrentFlowElement());
        }
        catch (IllegalArgumentException exception)
        {
            throw invalidArgument();
        }
        List<String> activeUserIds = userSelectionValidator.requireApprovalEligibleUserIds(
                requestedUserIds);
        if (activeUserIds.isEmpty())
        {
            throw invalidArgument();
        }
        return initializeMemberState(execution, activityId, mode, activeUserIds);
    }

    /**
     * 从发起服务写入的活动专属保留变量解析正式成员并初始化统一多实例状态。
     *
     * @param execution DelegateExecution，Flowable 正在创建多实例根的执行上下文。
     * @return List<String> 保持发起人选择顺序且仍具备审批资格的正式用户主键。
     */
    public List<String> getStartUserIds(DelegateExecution execution)
    {
        // 发起来源与办理时来源共用同一服务端变量协议，区别只用于部署拓扑和页面入口控制。
        return getUserIds(execution);
    }

    /**
     * 在 Flowable 创建多实例根的同一命令中初始化或复用正式成员状态。
     *
     * @param execution DelegateExecution，当前多实例活动执行上下文。
     * @param activityId String，当前受控多实例用户任务节点标识。
     * @param mode WorkflowMultiInstanceMode，部署 BPMN 固定的 ALL 或 ANY 完成模式。
     * @param eligibleUserIds List<String>，已重新验证审批资格的有序成员主键。
     * @return List<String> 引擎用于创建实例的不可修改正式成员集合。
     */
    private List<String> initializeMemberState(DelegateExecution execution,
            String activityId, WorkflowMultiInstanceMode mode,
            List<String> eligibleUserIds)
    {
        DelegateExecution processScope = requireProcessScope(execution);
        List<String> existingMembers = requireExistingState(processScope, activityId,
                mode, eligibleUserIds);
        if (existingMembers != null)
        {
            // 引擎重求值只能复用现有正式快照，绝不把已调整 revision 回退到零。
            return existingMembers;
        }
        // 固定和动态来源必须写入同一流程实例状态，任务详情、CAS 调整和完成审计才有唯一事实来源。
        processScope.setVariables(Map.of(
                WorkflowMultiInstanceVariables.memberSnapshotName(activityId),
                new ArrayList<>(eligibleUserIds),
                WorkflowMultiInstanceVariables.revisionName(activityId), 0,
                WorkflowMultiInstanceVariables.modeName(activityId), mode.name()));
        return List.copyOf(eligibleUserIds);
    }

    /**
     * 检查 handler 重求值时的三项服务端变量，保证全空才初始化、部分存在报错、完整状态只读复用。
     *
     * @param processScope DelegateExecution，流程实例根变量作用域
     * @param activityId String，当前动态多实例活动 ID
     * @param mode WorkflowMultiInstanceMode，部署 BPMN 解析出的固定完成模式
     * @param activeUserIds List&lt;String&gt;，当前受控集合重新通过正式主数据校验后的用户
     * @return List&lt;String&gt;，完整状态存在时返回不可修改正式快照；全空时返回 null
     */
    private List<String> requireExistingState(DelegateExecution processScope,
            String activityId, WorkflowMultiInstanceMode mode, List<String> activeUserIds)
    {
        Object rawMembers = processScope.getVariable(
                WorkflowMultiInstanceVariables.memberSnapshotName(activityId));
        Object rawRevision = processScope.getVariable(
                WorkflowMultiInstanceVariables.revisionName(activityId));
        Object rawMode = processScope.getVariable(
                WorkflowMultiInstanceVariables.modeName(activityId));
        int presentCount = (rawMembers == null ? 0 : 1)
                + (rawRevision == null ? 0 : 1) + (rawMode == null ? 0 : 1);
        if (presentCount == 0)
        {
            return null;
        }
        if (presentCount != 3)
        {
            throw dataError();
        }

        List<String> existingMembers = requireCanonicalMemberSnapshot(rawMembers);
        int revision = requireNonNegativeRevision(rawRevision);
        if (!(rawMode instanceof String existingMode)
                || !mode.name().equals(existingMode)
                || !existingMembers.equals(activeUserIds))
        {
            throw dataError();
        }
        // revision 只验证非负和类型，不执行任何写入；大于零代表成员已经经过正式调整。
        if (revision < 0)
        {
            throw dataError();
        }
        return existingMembers;
    }

    /**
     * 严格读取 handler 已初始化的服务端成员快照，拒绝类型漂移、重复和非规范用户主键。
     *
     * @param rawMembers Object，流程实例中的 _wfMiMembers_* 变量值
     * @return List&lt;String&gt;，1 至 100 名有序且唯一的规范数字用户主键
     */
    private List<String> requireCanonicalMemberSnapshot(Object rawMembers)
    {
        if (!(rawMembers instanceof List<?> members) || members.isEmpty()
                || members.size() > WorkflowUserSelectionValidator.MAX_SELECTED_USERS)
        {
            throw dataError();
        }
        LinkedHashSet<String> canonicalIds = new LinkedHashSet<>();
        for (Object member : members)
        {
            if (!(member instanceof String userId))
            {
                throw dataError();
            }
            try
            {
                long numericUserId = Long.parseLong(userId);
                if (numericUserId <= 0 || !String.valueOf(numericUserId).equals(userId)
                        || !canonicalIds.add(userId))
                {
                    throw dataError();
                }
            }
            catch (NumberFormatException exception)
            {
                throw dataError();
            }
        }
        return List.copyOf(canonicalIds);
    }

    /**
     * 严格读取已持久化 revision，避免浮点、负数或溢出状态被当作可重试版本。
     *
     * @param rawRevision Object，流程实例中的 _wfMiRevision_* 变量值
     * @return int，当前非负 revision
     */
    private int requireNonNegativeRevision(Object rawRevision)
    {
        if (!(rawRevision instanceof Byte || rawRevision instanceof Short
                || rawRevision instanceof Integer || rawRevision instanceof Long))
        {
            throw dataError();
        }
        long revision = ((Number) rawRevision).longValue();
        if (revision < 0 || revision > Integer.MAX_VALUE)
        {
            throw dataError();
        }
        return (int) revision;
    }

    /**
     * 把原始集合转换为有序去重的正整数用户主键，并以显式计数阻断超限或异常集合。
     *
     * @param rawUsers Object，流程变量中未经信任的集合值
     * @return List&lt;Long&gt;，最多 100 个、按首次出现顺序去重的用户主键
     */
    private List<Long> requireBoundedPositiveUserIds(Object rawUsers)
    {
        if (!(rawUsers instanceof Collection<?> collection))
        {
            throw invalidArgument();
        }
        int reportedSize;
        try
        {
            reportedSize = collection.size();
        }
        catch (RuntimeException exception)
        {
            throw invalidArgument();
        }
        if (reportedSize < 1 || reportedSize > WorkflowUserSelectionValidator.MAX_SELECTED_USERS)
        {
            throw invalidArgument();
        }

        LinkedHashSet<Long> orderedUserIds = new LinkedHashSet<>();
        int visited = 0;
        try
        {
            Iterator<?> iterator = collection.iterator();
            while (iterator.hasNext())
            {
                visited++;
                if (visited > WorkflowUserSelectionValidator.MAX_SELECTED_USERS)
                {
                    throw invalidArgument();
                }
                orderedUserIds.add(requirePositiveUserId(iterator.next()));
            }
        }
        catch (ServiceException exception)
        {
            throw exception;
        }
        catch (RuntimeException exception)
        {
            throw invalidArgument();
        }
        if (visited != reportedSize || orderedUserIds.isEmpty())
        {
            throw invalidArgument();
        }
        return new ArrayList<>(orderedUserIds);
    }

    /**
     * 严格解析固定集合表达式传入的逗号分隔用户主键，拒绝空值、前导零和重复成员。
     *
     * @param fixedUserIdsText String，BPMN 固定集合表达式中的原始成员主键文本。
     * @return List<Long> 1 至 100 名保持作者顺序的规范正整数用户主键。
     */
    private List<Long> requireFixedUserIds(String fixedUserIdsText)
    {
        String[] rawUserIds = fixedUserIdsText.split(",", -1);
        if (rawUserIds.length == 0
                || rawUserIds.length > WorkflowUserSelectionValidator.MAX_SELECTED_USERS)
        {
            throw invalidArgument();
        }
        LinkedHashSet<Long> orderedUserIds = new LinkedHashSet<>();
        for (String rawUserId : rawUserIds)
        {
            long userId = requirePositiveUserId(rawUserId);
            if (!orderedUserIds.add(userId))
            {
                throw invalidArgument();
            }
        }
        return new ArrayList<>(orderedUserIds);
    }

    /**
     * 解析单个固定成员主键，确保运行时输入保持与部署时完全一致的规范格式。
     *
     * @param rawUserId String，固定集合中的单个用户主键文本。
     * @return long 规范正整数用户主键。
     */
    private long requirePositiveUserId(String rawUserId)
    {
        try
        {
            long userId = Long.parseLong(rawUserId);
            if (userId <= 0 || !Long.toString(userId).equals(rawUserId))
            {
                throw invalidArgument();
            }
            return userId;
        }
        catch (NumberFormatException exception)
        {
            throw invalidArgument();
        }
    }

    /**
     * 将受支持的整数 Number 精确转换为正 Long，拒绝浮点、字符串、溢出和零值。
     *
     * @param rawUserId Object，集合中的单个用户主键值
     * @return Long，未发生截断的正整数用户主键
     */
    private Long requirePositiveUserId(Object rawUserId)
    {
        long userId;
        if (rawUserId instanceof Byte || rawUserId instanceof Short
                || rawUserId instanceof Integer || rawUserId instanceof Long)
        {
            userId = ((Number) rawUserId).longValue();
        }
        else if (rawUserId instanceof BigInteger bigInteger)
        {
            try
            {
                userId = bigInteger.longValueExact();
            }
            catch (ArithmeticException exception)
            {
                throw invalidArgument();
            }
        }
        else
        {
            throw invalidArgument();
        }
        if (userId <= 0)
        {
            throw invalidArgument();
        }
        return userId;
    }

    /**
     * 沿父执行向上定位流程实例作用域，确保服务端快照不落在临时并行分支上。
     *
     * @param execution DelegateExecution，当前多实例活动执行
     * @return DelegateExecution，与 processInstanceId 对应的流程实例根作用域
     */
    private DelegateExecution requireProcessScope(DelegateExecution execution)
    {
        DelegateExecution current = execution;
        for (int depth = 0; depth <= MAX_EXECUTION_DEPTH; depth++)
        {
            if (current.isProcessInstanceType())
            {
                if (!Objects.equals(execution.getProcessInstanceId(), current.getId()))
                {
                    throw dataError();
                }
                return current;
            }
            current = current.getParent();
            if (current == null)
            {
                break;
            }
        }
        throw dataError();
    }

    /**
     * 创建不暴露变量值或模型细节的稳定参数异常。
     *
     * @return ServiceException，HTTP 400 多实例集合或模型错误
     */
    private ServiceException invalidArgument()
    {
        return new ServiceException("工作流多实例用户集合不合法", HttpStatus.BAD_REQUEST);
    }

    /**
     * 创建执行树作用域不一致的稳定服务端异常。
     *
     * @return ServiceException，HTTP 500 引擎执行上下文异常
     */
    private ServiceException dataError()
    {
        return new ServiceException("工作流多实例执行上下文异常", HttpStatus.ERROR);
    }
}
