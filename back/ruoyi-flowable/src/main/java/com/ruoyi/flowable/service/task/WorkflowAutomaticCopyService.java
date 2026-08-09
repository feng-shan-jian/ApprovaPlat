package com.ruoyi.flowable.service.task;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.bpmn.model.BaseElement;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfCopy;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.identity.WorkflowUserIdValueParser;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.flowable.service.model.WorkflowAutoCopyRuleContract;
import com.ruoyi.flowable.service.model.WorkflowAutoCopyRuleContract.RecipientSource;
import com.ruoyi.flowable.service.model.WorkflowAutoCopyRuleContract.RecipientType;
import com.ruoyi.flowable.service.model.WorkflowAutoCopyRuleContract.Rule;
import com.ruoyi.flowable.service.model.WorkflowAutoCopyRuleContract.Trigger;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;

/**
 * 在 Flowable 生命周期事务内解析部署冻结规则并幂等写入正式自动抄送记录。
 */
@Service
public class WorkflowAutomaticCopyService
{
    private static final int MAX_RECIPIENTS = 1_000;
    private static final int MAX_ID_LENGTH = 64;
    private static final int MAX_TEXT_LENGTH = 255;

    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final WorkflowIdentityResolver identityResolver;
    private final WfCopyMapper copyMapper;
    private final SysUserMapper userMapper;

    /** 自动抄送创建通知服务；生产容器注入，旧直接构造单元测试可为空。 */
    private WorkflowNotificationService notificationService;

    /**
     * 创建自动抄送运行时服务。
     *
     * @param repositoryService RepositoryService，读取已部署 BPMN 不可变资源
     * @param runtimeService RuntimeService，读取活动实例发起人
     * @param historyService HistoryService，读取自然完成实例及历史变量
     * @param identityResolver WorkflowIdentityResolver，正式用户、角色和部门解析器
     * @param copyMapper WfCopyMapper，正式抄送记录 Mapper
     * @param userMapper SysUserMapper，发起人名称快照 Mapper
     */
    public WorkflowAutomaticCopyService(RepositoryService repositoryService,
            RuntimeService runtimeService, HistoryService historyService,
            WorkflowIdentityResolver identityResolver, WfCopyMapper copyMapper,
            SysUserMapper userMapper)
    {
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        this.identityResolver = identityResolver;
        this.copyMapper = copyMapper;
        this.userMapper = userMapper;
    }

    /**
     * 注入抄送事实通知服务，使自动 wf_copy 和 COPY_CREATED outbox 同事务提交。
     * @param notificationService WorkflowNotificationService，正式通知 outbox 服务
     * @return void，生产 Spring 容器完成注入
     */
    @Autowired
    public void setNotificationService(WorkflowNotificationService notificationService)
    {
        this.notificationService = notificationService;
    }

    /**
     * 处理用户任务到达或完成事件；assignment 事件不产生抄送。
     *
     * @param eventName String，create 或 complete
     * @param taskId String，当前 Flowable 任务主键
     * @param processInstanceId String，流程实例主键
     * @param processDefinitionId String，流程定义主键
     * @param taskDefinitionKey String，BPMN 用户任务主键
     * @param taskName String，任务名称快照
     * @param variables Map&lt;String,Object&gt;，当前流程变量快照
     * @return void，解析或写库失败时抛错回滚当前引擎命令
     */
    public void onTaskEvent(String eventName, String taskId, String processInstanceId,
            String processDefinitionId, String taskDefinitionKey, String taskName,
            Map<String, Object> variables)
    {
        Trigger trigger = "create".equals(eventName) ? Trigger.NODE_ARRIVED
                : "complete".equals(eventName) ? Trigger.NODE_COMPLETED : null;
        if (trigger == null)
        {
            return;
        }
        Process process = requireProcessModel(processDefinitionId);
        if (!hasTaskAutoCopyRules(process))
        {
            // 普通用户任务没有冻结自动抄送规则时，生命周期监听必须保持合法无副作用。
            return;
        }
        String normalizedTaskKey = requireId(taskDefinitionKey);
        FlowElement node = process.getFlowElement(normalizedTaskKey, true);
        if (node == null)
        {
            throw dataError("自动抄送触发节点不存在");
        }
        if (!(node instanceof UserTask userTask))
        {
            throw dataError("自动抄送触发节点不是用户任务");
        }
        String normalizedTaskId = requireId(taskId);
        RuntimeContext context = requireActiveContext(processInstanceId,
                processDefinitionId, variables);
        persistMatchingRules(userTask, trigger, normalizedTaskId, context,
                normalizedTaskKey, snapshot(taskName, normalizedTaskKey));
    }

    /**
     * 处理自然流程完成事件，变量从 Flowable 历史表读取以适配运行变量已清理阶段。
     *
     * @param processInstanceId String，已自然完成实例主键
     * @param processDefinitionId String，流程定义主键
     * @return void，规则或写库异常时回滚流程完成命令
     */
    public void onProcessCompleted(String processInstanceId, String processDefinitionId)
    {
        HistoricProcessInstance instance = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(requireId(processInstanceId)).singleResult();
        if (instance == null || !requireId(processDefinitionId)
                .equals(instance.getProcessDefinitionId()))
        {
            throw dataError("自动抄送流程历史关联异常");
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        List<HistoricVariableInstance> rows = historyService
                .createHistoricVariableInstanceQuery()
                .processInstanceId(processInstanceId).list();
        if (rows == null)
        {
            throw dataError("自动抄送历史变量查询异常");
        }
        for (HistoricVariableInstance row : rows)
        {
            if (row != null && StringUtils.hasText(row.getVariableName()))
            {
                variables.put(row.getVariableName(), row.getValue());
            }
        }
        RuntimeContext context = requireContext(processDefinitionId,
                processInstanceId, instance.getStartUserId(), variables);
        Process process = requireProcessModel(processDefinitionId);
        persistMatchingRules(process, Trigger.PROCESS_COMPLETED,
                "PROCESS_COMPLETED:" + processInstanceId, context, null, null);
    }

    /**
     * 解析匹配规则的全部来源，去重后按事件和用户唯一键幂等写库。
     *
     * @param element BaseElement，规则所属流程或任务
     * @param trigger Trigger，当前生命周期
     * @param eventSubjectId String，任务主键或稳定流程事件键
     * @param context RuntimeContext，已核验流程快照
     * @param nodeId String，可空触发节点主键
     * @param nodeName String，可空触发节点名称
     * @return void，无匹配规则或无有效组成员时不写库
     */
    private void persistMatchingRules(BaseElement element, Trigger trigger,
            String eventSubjectId, RuntimeContext context, String nodeId, String nodeName)
    {
        List<Rule> matched = WorkflowAutoCopyRuleContract.readRules(element).stream()
                .filter(rule -> rule.trigger() == trigger).toList();
        if (matched.isEmpty())
        {
            return;
        }
        LinkedHashSet<String> directUsers = new LinkedHashSet<>();
        LinkedHashSet<String> groups = new LinkedHashSet<>();
        for (Rule rule : matched)
        {
            for (RecipientSource source : rule.recipients())
            {
                collectSource(source, context, directUsers, groups);
            }
        }
        Set<String> resolved = identityResolver.resolveCopyEligibleUserIds(directUsers, groups);
        // 固定用户、发起人和表单字段均为强引用；停用或删除时阻止产生虚假成功状态。
        if (!resolved.containsAll(directUsers))
        {
            throw new ServiceException("自动抄送接收用户已失效或无对象可见权限", HttpStatus.CONFLICT);
        }
        if (resolved.size() > MAX_RECIPIENTS)
        {
            throw new ServiceException("自动抄送接收人数超过上限", HttpStatus.CONFLICT);
        }
        if (resolved.isEmpty())
        {
            return;
        }

        String copyEventId = trigger == Trigger.PROCESS_COMPLETED
                ? "PROCESS_COMPLETED:" + context.instanceId()
                : (trigger == Trigger.NODE_ARRIVED ? "TASK_ARRIVED:" : "TASK_COMPLETED:")
                        + eventSubjectId;
        String title = snapshot(context.processName() + "-"
                + (nodeName == null ? "流程完成" : nodeName), copyEventId);
        List<WfCopy> copies = new ArrayList<>(resolved.size());
        for (String userId : resolved)
        {
            WfCopy copy = new WfCopy();
            copy.setCopyEventId(copyEventId);
            copy.setTitle(title);
            copy.setProcessId(context.definitionId());
            copy.setProcessName(context.processName());
            copy.setCategoryId(context.categoryId());
            copy.setDeploymentId(context.deploymentId());
            copy.setInstanceId(context.instanceId());
            copy.setTaskId(trigger == Trigger.PROCESS_COMPLETED ? null : eventSubjectId);
            copy.setUserId(Long.valueOf(userId));
            copy.setOriginatorId(Long.valueOf(context.initiatorId()));
            copy.setOriginatorName(context.initiatorName());
            copy.setSourceType("AUTO");
            copy.setTriggerType(trigger.name());
            copy.setTriggerNodeId(nodeId);
            copy.setTriggerNodeName(nodeName);
            String actor = Authentication.getAuthenticatedUserId();
            copy.setCreateBy(StringUtils.hasText(actor) ? requireId(actor) : "SYSTEM");
            copy.setRemark("自动抄送:" + trigger.name());
            copies.add(copy);
        }
        int affected = copyMapper.insertBatchIdempotent(copies);
        if (affected < 0)
        {
            throw dataError("自动抄送写入结果异常");
        }
        if (notificationService != null)
        {
            // 幂等重放会读取同一 wf_copy 事实，COPY_CREATED 自身幂等键保证不会重复生成 outbox。
            notificationService.onCopiesCreated(copies);
        }
    }

    /**
     * 将一个受控来源解析到直接用户或候选组集合。
     *
     * @param source RecipientSource，规则来源
     * @param context RuntimeContext，流程变量和发起人快照
     * @param directUsers Set&lt;String&gt;，待严格校验直接用户
     * @param groups Set&lt;String&gt;，待动态展开角色或部门
     * @return void，表单非空非法值会中止事务
     */
    private void collectSource(RecipientSource source, RuntimeContext context,
            Set<String> directUsers, Set<String> groups)
    {
        if (source.type() == RecipientType.USER)
        {
            directUsers.addAll(source.values());
        }
        else if (source.type() == RecipientType.GROUP)
        {
            groups.addAll(source.values());
        }
        else if (source.type() == RecipientType.INITIATOR)
        {
            directUsers.add(context.initiatorId());
        }
        else
        {
            for (String variableName : source.values())
            {
                collectVariableUsers(context.variables().get(variableName), directUsers);
            }
        }
    }

    /**
     * 解析表单用户字段的正整数标量或数组；空字段表示当前没有接收人。
     *
     * @param value Object，Flowable 变量值
     * @param users Set&lt;String&gt;，接收用户集合
     * @return void，非数字对象或超量集合会回滚当前生命周期
     */
    private void collectVariableUsers(Object value, Set<String> users)
    {
        if (value == null)
        {
            return;
        }
        if (value instanceof Collection<?> values)
        {
            if (values.size() > MAX_RECIPIENTS)
            {
                throw new ServiceException("表单用户字段数量超过上限", HttpStatus.CONFLICT);
            }
            values.forEach(item -> users.add(requireUserValue(item)));
            return;
        }
        if (value.getClass().isArray())
        {
            int length = Array.getLength(value);
            if (length > MAX_RECIPIENTS)
            {
                throw new ServiceException("表单用户字段数量超过上限", HttpStatus.CONFLICT);
            }
            for (int index = 0; index < length; index++)
            {
                users.add(requireUserValue(Array.get(value, index)));
            }
            return;
        }
        users.add(requireUserValue(value));
    }

    /**
     * 把单个表单用户值规范为无前导零的正整数文本。
     *
     * @param value Object，字符串或整数用户主键
     * @return String，规范用户主键
     */
    private String requireUserValue(Object value)
    {
        return WorkflowUserIdValueParser.requirePositiveUserId(
                value, "表单用户字段值不合法");
    }

    /**
     * 查询活动实例并冻结运行时上下文。
     *
     * @param instanceId String，流程实例主键
     * @param definitionId String，流程定义主键
     * @param variables Map&lt;String,Object&gt;，变量快照
     * @return RuntimeContext，已核验上下文
     */
    private RuntimeContext requireActiveContext(String instanceId, String definitionId,
            Map<String, Object> variables)
    {
        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(requireId(instanceId)).active().singleResult();
        if (instance == null || !requireId(definitionId).equals(instance.getProcessDefinitionId()))
        {
            throw dataError("自动抄送活动实例关联异常");
        }
        return requireContext(definitionId, instanceId, instance.getStartUserId(), variables);
    }

    /**
     * 从定义和发起人构造不可变抄送上下文。
     *
     * @param definitionId String，流程定义主键
     * @param instanceId String，流程实例主键
     * @param initiatorId String，发起用户主键
     * @param variables Map&lt;String,Object&gt;，变量快照
     * @return RuntimeContext，正式元数据快照
     */
    private RuntimeContext requireContext(String definitionId, String instanceId,
            String initiatorId, Map<String, Object> variables)
    {
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(requireId(definitionId)).singleResult();
        if (definition == null)
        {
            throw dataError("自动抄送流程定义不存在");
        }
        String normalizedInitiator = requireUserValue(initiatorId);
        SysUser user = userMapper.selectUserById(Long.valueOf(normalizedInitiator));
        String initiatorName = user == null ? normalizedInitiator
                : snapshot(user.getNickName(), user.getUserName());
        return new RuntimeContext(requireId(definitionId), requireId(instanceId),
                snapshot(definition.getName(), definition.getKey()),
                snapshot(definition.getCategory(), ""), requireId(definition.getDeploymentId()),
                normalizedInitiator, initiatorName,
                variables == null ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(variables)));
    }

    /**
     * 严格读取当前流程定义对应的部署模型流程，避免多 process 资源误取主流程。
     *
     * @param definitionId String，流程定义主键
     * @return Process，定义 key 与 BPMN process id 精确匹配的部署冻结流程
     */
    private Process requireProcessModel(String definitionId)
    {
        String normalizedDefinitionId = requireId(definitionId);
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(normalizedDefinitionId).singleResult();
        if (definition == null)
        {
            throw dataError("自动抄送流程定义不存在");
        }
        String definitionKey = definition.getKey();
        if (!StringUtils.hasText(definitionKey))
        {
            throw dataError("自动抄送流程定义与部署模型关联异常");
        }
        BpmnModel model = repositoryService.getBpmnModel(normalizedDefinitionId);
        if (model == null)
        {
            throw dataError("自动抄送部署模型不存在");
        }
        Process process = model.getProcessById(definitionKey);
        if (process == null || !definitionKey.equals(process.getId()))
        {
            throw dataError("自动抄送流程定义与部署模型关联异常");
        }
        return process;
    }

    /**
     * 全量解析当前流程的用户任务自动抄送规则，确认任务事件是否需要进入运行时校验。
     *
     * @param process Process，已与流程定义 key 精确关联的部署冻结流程
     * @return boolean，任一用户任务存在规则时返回 true；所有任务无规则时返回 false
     */
    private boolean hasTaskAutoCopyRules(Process process)
    {
        // hasRules 表示当前流程至少配置过一条任务级规则；不能提前结束，以免漏过后续非法规则。
        boolean hasRules = false;
        for (UserTask task : process.findFlowElementsOfType(UserTask.class, true))
        {
            if (!WorkflowAutoCopyRuleContract.readRules(task).isEmpty())
            {
                hasRules = true;
            }
        }
        return hasRules;
    }

    /** @param value String，Flowable 关系主键；@return String，非空且长度受控的主键。 */
    private String requireId(String value)
    {
        if (!StringUtils.hasText(value) || value.trim().length() > MAX_ID_LENGTH)
        {
            throw dataError("自动抄送关系主键异常");
        }
        return value.trim();
    }

    /** @param preferred String，优先文本；@param fallback String，回退文本；@return String，长度受控快照。 */
    private String snapshot(String preferred, String fallback)
    {
        String value = StringUtils.hasText(preferred) ? preferred.trim()
                : fallback == null ? "" : fallback.trim();
        return value.length() <= MAX_TEXT_LENGTH ? value : value.substring(0, MAX_TEXT_LENGTH);
    }

    /** @param message String，内部一致性说明；@return ServiceException，HTTP 500 异常。 */
    private ServiceException dataError(String message)
    {
        return new ServiceException(message, HttpStatus.ERROR);
    }

    /**
     * 生命周期内使用的正式流程快照。
     *
     * @param definitionId String，定义主键
     * @param instanceId String，实例主键
     * @param processName String，流程名称
     * @param categoryId String，分类编码
     * @param deploymentId String，部署主键
     * @param initiatorId String，发起用户主键
     * @param initiatorName String，发起用户名称快照
     * @param variables Map&lt;String,Object&gt;，变量快照
     */
    private record RuntimeContext(String definitionId, String instanceId,
            String processName, String categoryId, String deploymentId,
            String initiatorId, String initiatorName, Map<String, Object> variables)
    {
    }
}
