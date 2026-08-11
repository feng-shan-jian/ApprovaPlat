package com.ruoyi.flowable.service.task;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfControlledLoopExecution;
import com.ruoyi.flowable.domain.WfDeployControlledLoop;
import com.ruoyi.flowable.domain.vo.WorkflowControlledLoopRoundView;
import com.ruoyi.flowable.domain.vo.WorkflowControlledLoopStateView;
import com.ruoyi.flowable.mapper.WfControlledLoopExecutionMapper;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;

/**
 * 受控重复审批循环的完成判断、最大轮次、运行变量、审计落库和详情投影服务。
 */
@Service
public class WorkflowControlledLoopService
{
    /** Flowable comment 中区分循环路由审计的固定类型。 */
    public static final String COMMENT_TYPE = "CONTROLLED_LOOP";
    /** 客户端提交未命中进入或退出条件时使用的稳定子码。 */
    public static final String DECISION_INVALID_SUB_CODE = "CONTROLLED_LOOP_DECISION_INVALID";
    /** 已达到最大轮次却继续要求整改时使用的稳定子码。 */
    public static final String LIMIT_REACHED_SUB_CODE = "CONTROLLED_LOOP_LIMIT_REACHED";

    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final WorkflowDeploymentArtifactRepository artifactRepository;
    private final WfControlledLoopExecutionMapper executionMapper;

    /**
     * 创建受控循环运行服务。
     * @param repositoryService RepositoryService，流程定义和 processKey 查询 API
     * @param runtimeService RuntimeService，写入编译网关保留变量的公共 API
     * @param taskService TaskService，写入同事务结构化循环 comment 的公共 API
     * @param artifactRepository WorkflowDeploymentArtifactRepository，循环部署资源仓库
     * @param executionMapper WfControlledLoopExecutionMapper，逐轮运行审计 Mapper
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowControlledLoopService(RepositoryService repositoryService,
            RuntimeService runtimeService, TaskService taskService,
            WorkflowDeploymentArtifactRepository artifactRepository,
            WfControlledLoopExecutionMapper executionMapper)
    {
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.artifactRepository = artifactRepository;
        this.executionMapper = executionMapper;
    }

    /**
     * 在真实任务完成前根据部署快照和已校验表单变量决定再次进入或退出，并同事务写入审计。
     *
     * @param task Task，已完成活动、办理人、权限和委派状态校验的真实任务
     * @param deploymentId String，任务定义所属 Flowable 部署主键
     * @param variables Map&lt;String,Object&gt;，已经过节点部署表单 schema 校验的业务变量
     * @param actorUserId String，当前真实完成人主键
     * @return void，普通任务不产生任何写入；循环任务失败时抛出稳定 400、409 或 500
     */
    public void prepareCompletion(Task task, String deploymentId,
            Map<String, Object> variables, String actorUserId)
    {
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(task.getProcessDefinitionId()).singleResult();
        if (definition == null || !StringUtils.hasText(definition.getKey()))
        {
            throw dataError("循环任务流程定义关系异常");
        }
        WfDeployControlledLoop config = artifactRepository.selectControlledLoop(
                deploymentId, definition.getKey(), task.getTaskDefinitionKey());
        if (config == null)
        {
            return;
        }

        String decisionValue = canonicalScalar(variables.get(config.getDecisionVariable()));
        boolean repeat = config.getRepeatValue().equals(decisionValue);
        boolean exit = config.getExitValue().equals(decisionValue);
        if (repeat == exit)
        {
            throw new ServiceException("循环判断字段必须明确选择再次整改或退出循环",
                    HttpStatus.BAD_REQUEST).setSubCode(DECISION_INVALID_SUB_CODE);
        }

        int previousIteration = safeIteration(executionMapper.selectMaxIteration(
                task.getProcessInstanceId(), task.getTaskDefinitionKey()));
        int iteration = previousIteration + 1;
        if (iteration > config.getMaxIterations())
        {
            throw dataError("循环完成轮次超过部署上限");
        }
        if (repeat && iteration >= config.getMaxIterations())
        {
            // 达到上限时拒绝当前完成而不是强行退出，避免未经明确审批结论绕过整改要求。
            throw new ServiceException("已达到最大整改轮次，请选择退出结果后再提交",
                    HttpStatus.CONFLICT).setSubCode(LIMIT_REACHED_SUB_CODE);
        }

        String outcome = repeat ? "REPEAT" : "EXIT";
        WfControlledLoopExecution execution = new WfControlledLoopExecution();
        execution.setDeployId(deploymentId);
        execution.setProcessDefinitionId(task.getProcessDefinitionId());
        execution.setProcessInstanceId(task.getProcessInstanceId());
        execution.setActivityId(task.getTaskDefinitionKey());
        execution.setTaskId(task.getId());
        execution.setIterationNo(iteration);
        execution.setActorUserId(actorUserId);
        execution.setDecisionValue(decisionValue);
        execution.setOutcome(outcome);
        final int inserted;
        try
        {
            inserted = executionMapper.insert(execution);
        }
        catch (DataIntegrityViolationException exception)
        {
            // taskId 与“实例+节点+轮次”双唯一约束把重复提交和并发完成收敛为稳定冲突。
            throw new ServiceException("循环任务已被处理，请刷新后重试",
                    HttpStatus.CONFLICT).setSubCode("CONTROLLED_LOOP_CONCURRENT_CONFLICT");
        }
        if (inserted != 1)
        {
            throw new ServiceException("循环轮次审计保存失败", HttpStatus.CONFLICT);
        }

        // 保留变量仅由服务端写入；任务完成后生成的固定网关只读取布尔路由值。
        runtimeService.setVariable(task.getProcessInstanceId(), config.getRouteVariable(), repeat);
        runtimeService.setVariable(task.getProcessInstanceId(),
                config.getIterationVariable(), iteration);
        taskService.addComment(task.getId(), task.getProcessInstanceId(), COMMENT_TYPE,
                buildAudit(config, task, actorUserId, decisionValue, outcome, iteration));
    }

    /**
     * 为已完成对象授权的流程详情构建受控循环配置、当前轮次和逐轮审计。
     *
     * @param deploymentId String，流程实例所属部署主键
     * @param processKey String，流程定义 key
     * @param processInstanceId String，已完成访问控制的流程实例主键
     * @param activeActivityId String，请求绑定的活动任务节点；没有活动任务时为空
     * @return List&lt;WorkflowControlledLoopStateView&gt;，按节点标识稳定排序的循环状态
     */
    public List<WorkflowControlledLoopStateView> buildStates(String deploymentId,
            String processKey, String processInstanceId, String activeActivityId)
    {
        List<WfDeployControlledLoop> configs = artifactRepository.selectControlledLoops(
                deploymentId, processKey);
        List<WfControlledLoopExecution> executions = executionMapper
                .selectByProcessInstanceId(processInstanceId);
        configs = configs == null ? List.of() : List.copyOf(configs);
        executions = executions == null ? List.of() : List.copyOf(executions);

        Map<String, WfDeployControlledLoop> configsByActivity = new HashMap<>();
        for (WfDeployControlledLoop config : configs)
        {
            if (configsByActivity.put(config.getActivityId(), config) != null)
            {
                throw dataError("循环部署快照节点关系不唯一");
            }
        }
        Map<String, List<WfControlledLoopExecution>> roundsByActivity = new HashMap<>();
        for (WfControlledLoopExecution execution : executions)
        {
            if (!configsByActivity.containsKey(execution.getActivityId()))
            {
                throw dataError("循环运行审计缺少部署快照");
            }
            roundsByActivity.computeIfAbsent(execution.getActivityId(), key -> new ArrayList<>())
                    .add(execution);
        }

        List<WorkflowControlledLoopStateView> result = new ArrayList<>(configs.size());
        for (WfDeployControlledLoop config : configs)
        {
            List<WfControlledLoopExecution> rounds = roundsByActivity
                    .getOrDefault(config.getActivityId(), List.of());
            List<WorkflowControlledLoopRoundView> roundViews = buildRoundViews(config, rounds);
            int completed = roundViews.size();
            boolean active = config.getActivityId().equals(activeActivityId);
            int current = active ? completed + 1 : completed;
            if (current > config.getMaxIterations())
            {
                throw dataError("循环当前轮次超过部署上限");
            }
            result.add(new WorkflowControlledLoopStateView(config.getActivityId(),
                    config.getActivityName(), config.getDecisionVariable(),
                    config.getRepeatValue(), config.getExitValue(), config.getMaxIterations(),
                    completed, current, active, roundViews));
        }
        return List.copyOf(result);
    }

    /**
     * 校验单节点运行审计轮次连续且最终退出后不再出现后续记录。
     * @param config WfDeployControlledLoop，循环部署快照
     * @param rounds List&lt;WfControlledLoopExecution&gt;，数据库按轮次排序的运行记录
     * @return List&lt;WorkflowControlledLoopRoundView&gt;，不可变详情视图
     */
    private List<WorkflowControlledLoopRoundView> buildRoundViews(
            WfDeployControlledLoop config, List<WfControlledLoopExecution> rounds)
    {
        List<WorkflowControlledLoopRoundView> result = new ArrayList<>(rounds.size());
        boolean exited = false;
        for (int index = 0; index < rounds.size(); index++)
        {
            WfControlledLoopExecution round = rounds.get(index);
            int expectedIteration = index + 1;
            if (exited || round.getIterationNo() == null
                    || round.getIterationNo() != expectedIteration
                    || expectedIteration > config.getMaxIterations()
                    || !("REPEAT".equals(round.getOutcome()) || "EXIT".equals(round.getOutcome()))
                    || round.getCreateTime() == null)
            {
                throw dataError("循环运行审计关系异常");
            }
            exited = "EXIT".equals(round.getOutcome());
            result.add(new WorkflowControlledLoopRoundView(round.getTaskId(), expectedIteration,
                    round.getActorUserId(), round.getDecisionValue(), round.getOutcome(),
                    round.getCreateTime().toInstant()));
        }
        return List.copyOf(result);
    }

    /**
     * 把表单 schema 已校验的标量值转换为稳定比较文本，禁止集合、对象和空值进入路由。
     * @param value Object，任务完成请求中的已规范业务值
     * @return String，可与部署条件执行精确比较的标量文本
     */
    private String canonicalScalar(Object value)
    {
        if (value instanceof String text && !text.isBlank() && text.length() <= 128)
        {
            return text;
        }
        if (value instanceof Boolean bool)
        {
            return Boolean.toString(bool);
        }
        if (value instanceof Number number)
        {
            String normalized = new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
            if (normalized.length() <= 128)
            {
                return normalized;
            }
        }
        throw new ServiceException("循环判断字段必须提交非空标量值",
                HttpStatus.BAD_REQUEST).setSubCode(DECISION_INVALID_SUB_CODE);
    }

    /**
     * 规范 Mapper 返回的最大轮次并拒绝数据库越界值。
     * @param value Integer，聚合查询返回值
     * @return int，0 至 50 的已完成最大轮次
     */
    private int safeIteration(Integer value)
    {
        int iteration = value == null ? 0 : value;
        if (iteration < 0 || iteration > 50)
        {
            throw dataError("循环轮次聚合结果异常");
        }
        return iteration;
    }

    /**
     * 构建只包含服务端可信字段的循环 comment JSON。
     * @param config WfDeployControlledLoop，部署循环快照
     * @param task Task，本轮真实任务
     * @param actorUserId String，本轮真实完成人主键
     * @param decisionValue String，经表单 schema 校验的判断值
     * @param outcome String，REPEAT 或 EXIT
     * @param iteration int，从 1 开始的完成轮次
     * @return String，固定 schema 的结构化审计 JSON
     */
    private String buildAudit(WfDeployControlledLoop config, Task task, String actorUserId,
            String decisionValue, String outcome, int iteration)
    {
        ObjectNode audit = JsonNodeFactory.instance.objectNode();
        audit.put("schemaVersion", 1);
        audit.put("action", "CONTROLLED_LOOP_" + outcome);
        audit.put("taskId", task.getId());
        audit.put("processInstanceId", task.getProcessInstanceId());
        audit.put("activityId", task.getTaskDefinitionKey());
        audit.put("actorUserId", actorUserId);
        audit.put("decisionVariable", config.getDecisionVariable());
        audit.put("decisionValue", decisionValue);
        audit.put("outcome", outcome);
        audit.put("iteration", iteration);
        audit.put("maxIterations", config.getMaxIterations());
        audit.put("recordedAt", Instant.now().toString());
        return audit.toString();
    }

    /**
     * 创建不暴露 SQL 或引擎内部结构的循环关联数据异常。
     * @param message String，服务端日志和测试使用的稳定业务提示
     * @return ServiceException，HTTP 500 业务异常
     */
    private ServiceException dataError(String message)
    {
        return new ServiceException(message, HttpStatus.ERROR);
    }
}
