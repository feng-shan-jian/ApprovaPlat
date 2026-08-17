package com.ruoyi.flowable.service.task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfCopy;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.flowable.mapper.WorkflowRuntimeTaskMapper;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.flowable.service.notification.WorkflowNotificationRegistrar;

/**
 * 为任务写动作准备并持久化抄送记录，确保身份校验、引擎动作和业务表写入共享事务。
 */
@Service
public class WorkflowTaskCopyService
{
    /** 抄送标题及流程名称快照的数据库字符上限。 */
    private static final int MAX_LONG_TEXT_LENGTH = 255;

    /** 抄送关系主键、分类和用户名称快照的数据库字符上限。 */
    private static final int MAX_ID_TEXT_LENGTH = 64;

    private final WorkflowUserSelectionValidator userSelectionValidator;

    private final WfCopyMapper copyMapper;

    private final WorkflowRuntimeTaskMapper runtimeTaskMapper;

    private final RepositoryService repositoryService;

    private final RuntimeService runtimeService;

    private final SysUserMapper sysUserMapper;

    /** 抄送创建通知服务，任务抄送事实与 outbox 必须同事务提交。 */
    private final WorkflowNotificationRegistrar notificationService;

    /**
     * 创建任务抄送服务。
     *
     * @param userSelectionValidator WorkflowUserSelectionValidator，正式有效用户严格校验器
     * @param copyMapper WfCopyMapper，正式抄送记录数据访问层
     * @param runtimeTaskMapper WorkflowRuntimeTaskMapper，Flowable 活动任务 revision 只读访问层
     * @param repositoryService RepositoryService，流程定义元数据查询服务
     * @param runtimeService RuntimeService，活动流程实例查询服务
     * @param sysUserMapper SysUserMapper，流程发起人名称快照查询 Mapper
     * @param notificationService WorkflowNotificationRegistrar，正式通知 outbox 服务
     * @return 无返回值，构造后由 Spring 管理该服务
     */
    public WorkflowTaskCopyService(WorkflowUserSelectionValidator userSelectionValidator,
            WfCopyMapper copyMapper, WorkflowRuntimeTaskMapper runtimeTaskMapper,
            RepositoryService repositoryService,
            RuntimeService runtimeService, SysUserMapper sysUserMapper,
            WorkflowNotificationRegistrar notificationService)
    {
        this.userSelectionValidator = userSelectionValidator;
        this.copyMapper = copyMapper;
        this.runtimeTaskMapper = runtimeTaskMapper;
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.sysUserMapper = sysUserMapper;
        this.notificationService = notificationService;
    }

    /**
     * 在任务状态变更前冻结抄送元数据和事件 revision，任何非法用户都会阻止后续引擎命令。
     *
     * @param action WorkflowTaskCopyAction，产生抄送的任务动作类型
     * @param task Task，已经通过活动态和对象权限校验的来源任务
     * @param actor WorkflowCurrentIdentity，事务内重新核验的当前操作人
     * @param requestedUserIds List&lt;Long&gt;，客户端选择的抄送接收用户主键
     * @return CopyPlan，不可修改的待写入抄送计划；未选择用户时返回空计划
     */
    public CopyPlan prepare(WorkflowTaskCopyAction action, Task task,
            WorkflowCurrentIdentity actor, List<Long> requestedUserIds)
    {
        List<String> recipientIds = userSelectionValidator.requireActiveUserIds(requestedUserIds);
        if (recipientIds.isEmpty())
        {
            return CopyPlan.empty();
        }
        if (action == null || task == null || actor == null)
        {
            throw dataError();
        }

        String taskId = requireRelationId(task.getId());
        String processInstanceId = requireRelationId(task.getProcessInstanceId());
        String processDefinitionId = requireRelationId(task.getProcessDefinitionId());
        String actorUserId = requireRelationId(actor.userId());
        // 独立只读 Mapper 再取持久化 revision，避免把 Flowable 表查询混入 wf_copy 业务 Mapper。
        Integer taskRevision = runtimeTaskMapper.selectActiveTaskRevision(taskId);
        if (taskRevision == null || taskRevision < 1)
        {
            throw conflict();
        }

        ProcessInstance processInstance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .active()
                .singleResult();
        if (processInstance == null || processInstance.isSuspended()
                || !processDefinitionId.equals(processInstance.getProcessDefinitionId()))
        {
            throw conflict();
        }
        ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(processDefinitionId)
                .singleResult();
        if (processDefinition == null)
        {
            throw dataError();
        }

        Long originatorId = requireCanonicalUserId(processInstance.getStartUserId());
        String originatorName = resolveOriginatorName(originatorId);
        String processName = snapshotText(processDefinition.getName(),
                processDefinition.getKey(), MAX_LONG_TEXT_LENGTH);
        String taskName = snapshotText(task.getName(), task.getTaskDefinitionKey(),
                MAX_LONG_TEXT_LENGTH);
        String title = truncate(processName + "-" + taskName, MAX_LONG_TEXT_LENGTH);
        String categoryId = truncate(defaultText(processDefinition.getCategory()),
                MAX_ID_TEXT_LENGTH);
        String deploymentId = requireRelationId(processDefinition.getDeploymentId());
        // 完成动作与节点完成自动规则共享业务事件键，同一接收人只保留一条正式记录。
        boolean lifecycleIdempotent = action == WorkflowTaskCopyAction.COMPLETE;
        String copyEventId = lifecycleIdempotent
                ? "TASK_COMPLETED:" + taskId
                : action.name() + ":" + taskId + ":r" + taskRevision;

        List<WfCopy> copies = new ArrayList<>(recipientIds.size());
        for (String recipientId : recipientIds)
        {
            WfCopy copy = new WfCopy();
            copy.setCopyEventId(copyEventId);
            copy.setTitle(title);
            copy.setProcessId(processDefinitionId);
            copy.setProcessName(processName);
            copy.setCategoryId(categoryId);
            copy.setDeploymentId(deploymentId);
            copy.setInstanceId(processInstanceId);
            copy.setTaskId(taskId);
            copy.setUserId(Long.valueOf(recipientId));
            copy.setOriginatorId(originatorId);
            copy.setOriginatorName(originatorName);
            copy.setSourceType("MANUAL");
            copy.setTriggerType("MANUAL_" + action.name());
            copy.setTriggerNodeId(truncate(defaultText(task.getTaskDefinitionKey()),
                    MAX_ID_TEXT_LENGTH));
            copy.setTriggerNodeName(taskName);
            // create_by 使用事务内可信用户主键，禁止客户端伪造账号审计字段。
            copy.setCreateBy(actorUserId);
            copy.setRemark("任务动作:" + action.name());
            copies.add(copy);
        }
        return new CopyPlan(copies, lifecycleIdempotent);
    }

    /**
     * 在引擎动作成功后批量写入抄送计划，写入数量不一致时抛错使整个事务回滚。
     *
     * @param plan CopyPlan，状态变更前冻结的抄送计划
     * @return 无返回值，空计划不访问数据库
     */
    public void persist(CopyPlan plan)
    {
        if (plan == null)
        {
            throw dataError();
        }
        if (plan.copies().isEmpty())
        {
            return;
        }
        int insertedRows = plan.idempotent()
                ? copyMapper.insertBatchIdempotent(plan.copies())
                : copyMapper.insertBatch(plan.copies());
        if ((!plan.idempotent() && insertedRows != plan.copies().size())
                || (plan.idempotent() && insertedRows < 0))
        {
            throw dataError();
        }
        // 仅在正式 wf_copy 写入成功后消费真实主键；通知失败会抛出并回滚任务动作和抄送事实。
        notificationService.onCopiesCreated(plan.copies());
    }

    /**
     * 校验 Flowable 关系主键满足 wf_copy 的必填及长度约束。
     *
     * @param value String，任务、实例、定义、部署或操作人主键
     * @return String，去除首尾空白后的关系主键
     */
    private String requireRelationId(String value)
    {
        if (!StringUtils.hasText(value))
        {
            throw dataError();
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_ID_TEXT_LENGTH)
        {
            throw dataError();
        }
        return normalized;
    }

    /**
     * 将流程发起人主键解析为规范正整数，防止不可关联身份进入正式抄送表。
     *
     * @param value String，Flowable 流程实例记录的发起人主键
     * @return Long，规范的若依用户主键
     */
    private Long requireCanonicalUserId(String value)
    {
        if (!StringUtils.hasText(value))
        {
            throw dataError();
        }
        try
        {
            Long userId = Long.valueOf(value.trim());
            if (userId <= 0 || !String.valueOf(userId).equals(value.trim()))
            {
                throw dataError();
            }
            return userId;
        }
        catch (NumberFormatException exception)
        {
            throw dataError();
        }
    }

    /**
     * 查询流程发起人名称快照；历史用户已删除时回退用户主键，仍保留可追踪性。
     *
     * @param originatorId Long，流程发起用户主键
     * @return String，长度受控的名称、账号或用户主键快照
     */
    private String resolveOriginatorName(Long originatorId)
    {
        SysUser originator = sysUserMapper.selectUserById(originatorId);
        if (originator == null)
        {
            return String.valueOf(originatorId);
        }
        return truncate(snapshotText(originator.getNickName(), originator.getUserName(),
                MAX_ID_TEXT_LENGTH), MAX_ID_TEXT_LENGTH);
    }

    /**
     * 选择首个非空快照文本并按数据库字符上限截断。
     *
     * @param preferred String，优先使用的业务名称
     * @param fallback String，优先名称为空时使用的稳定标识
     * @param maxLength int，数据库允许的最大字符数
     * @return String，非 null 且长度受控的快照文本
     */
    private String snapshotText(String preferred, String fallback, int maxLength)
    {
        String selected = StringUtils.hasText(preferred) ? preferred.trim()
                : defaultText(fallback);
        return truncate(selected, maxLength);
    }

    /**
     * 把可空文本转换为数据库允许的非 null 快照值。
     *
     * @param value String，可空业务文本
     * @return String，去除首尾空白后的文本或空字符串
     */
    private String defaultText(String value)
    {
        return value == null ? "" : value.trim();
    }

    /**
     * 按 Java 字符边界截断快照字段，避免非关键展示文本导致核心任务事务写库失败。
     *
     * @param value String，非 null 快照文本
     * @param maxLength int，数据库允许的最大字符数
     * @return String，不超过指定长度的文本
     */
    private String truncate(String value, int maxLength)
    {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /**
     * 创建稳定的并发或任务状态冲突异常。
     *
     * @return ServiceException，HTTP 409 状态异常
     */
    private ServiceException conflict()
    {
        return new ServiceException("工作流状态已发生变化，请刷新后重试", HttpStatus.CONFLICT);
    }

    /**
     * 创建稳定的工作流关联数据异常。
     *
     * @return ServiceException，HTTP 500 数据一致性异常
     */
    private ServiceException dataError()
    {
        return new ServiceException("工作流对象关联数据异常", HttpStatus.ERROR);
    }

    /**
     * 状态变更前冻结的不可变抄送写入计划。
     *
     * @param copies List&lt;WfCopy&gt;，同一动作事件下按接收用户生成的抄送记录
     */
    public record CopyPlan(List<WfCopy> copies, boolean idempotent)
    {
        /**
         * 创建抄送计划并复制记录集合，防止动作执行期间增删接收记录。
         *
         * @param copies List&lt;WfCopy&gt;，待写入抄送记录，不允许为 null
         * @return 无返回值，构造后 copies 为不可修改集合
         */
        public CopyPlan
        {
            if (copies == null)
            {
                throw new IllegalArgumentException("抄送计划不能为空");
            }
            copies = Collections.unmodifiableList(new ArrayList<>(copies));
        }

        /**
         * 保留既有测试和内部调用的一参数构造语义，默认使用严格插入。
         * @param copies List&lt;WfCopy&gt;，待写入抄送记录
         */
        public CopyPlan(List<WfCopy> copies)
        {
            this(copies, false);
        }

        /**
         * 创建无需写库的空抄送计划。
         *
         * @return CopyPlan，不包含抄送记录的不可变计划
         */
        public static CopyPlan empty()
        {
            return new CopyPlan(List.of(), false);
        }
    }
}
