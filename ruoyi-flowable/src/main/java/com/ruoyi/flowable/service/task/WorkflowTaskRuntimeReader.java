package com.ruoyi.flowable.service.task;

import java.util.List;
import java.util.Date;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;

/**
 * 活动任务、流程实例、当前办理人与唯一任务等只读运行时事实读取器。
 */
@Component
public class WorkflowTaskRuntimeReader
{
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;

    /**
     * 创建任务运行时事实读取器。
     *
     * @param runtimeService RuntimeService，流程实例只读查询服务
     * @param taskService TaskService，活动任务只读查询服务
     * @param historyService HistoryService，重复动作历史判定服务
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowTaskRuntimeReader(RuntimeService runtimeService,
            TaskService taskService, HistoryService historyService)
    {
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.historyService = historyService;
    }

    /**
     * 查询未挂起活动任务并区分重复操作与不存在。
     *
     * @param taskId String，已规范化任务主键
     * @return Task，真实未挂起活动任务
     */
    public Task requireActiveTask(String taskId)
    {
        Task activeTask = taskService.createTaskQuery().taskId(taskId).active().singleResult();
        if (activeTask != null && !activeTask.isSuspended())
        {
            return activeTask;
        }
        Task existingTask = activeTask != null ? activeTask
                : taskService.createTaskQuery().taskId(taskId).singleResult();
        if (existingTask != null)
        {
            throw conflict();
        }
        HistoricTaskInstance historicTask = historyService.createHistoricTaskInstanceQuery()
                .taskId(taskId).singleResult();
        if (historicTask != null)
        {
            throw conflict();
        }
        throw notFound();
    }

    /**
     * 查询未挂起活动流程实例并区分重复操作与不存在。
     *
     * @param processInstanceId String，流程实例主键
     * @return ProcessInstance，真实未挂起活动实例
     */
    public ProcessInstance requireActiveProcessInstance(String processInstanceId)
    {
        if (!StringUtils.hasText(processInstanceId))
        {
            throw conflict();
        }
        ProcessInstance activeInstance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).active().singleResult();
        if (activeInstance != null && !activeInstance.isSuspended())
        {
            return activeInstance;
        }
        ProcessInstance existingInstance = activeInstance != null ? activeInstance
                : runtimeService.createProcessInstanceQuery()
                        .processInstanceId(processInstanceId).singleResult();
        if (existingInstance != null)
        {
            throw conflict();
        }
        HistoricProcessInstance historicInstance = historyService
                .createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (historicInstance != null)
        {
            throw conflict();
        }
        throw notFound();
    }

    /**
     * 查询取消目标的未结束实例，同时允许 active 和 suspended。
     *
     * @param processInstanceId String，流程实例主键
     * @return ProcessInstance，仍存在于运行时表的实例
     */
    public ProcessInstance requireRunningProcessInstanceForCancellation(
            String processInstanceId)
    {
        if (!StringUtils.hasText(processInstanceId))
        {
            throw conflict();
        }
        ProcessInstance running = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (running != null)
        {
            return running;
        }
        HistoricProcessInstance historic = historyService
                .createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (historic != null)
        {
            throw conflict();
        }
        throw notFound();
    }

    /**
     * 校验任务当前办理人与事务内正式身份一致。
     *
     * @param task Task，真实活动任务
     * @param actor WorkflowCurrentIdentity，事务内正式身份
     * @return 无返回值，不一致时抛出既有 HTTP 403
     */
    public void requireCurrentAssignee(Task task, WorkflowCurrentIdentity actor)
    {
        if (!StringUtils.hasText(task.getAssignee())
                || !actor.userId().equals(task.getAssignee()))
        {
            throw forbidden();
        }
    }

    /**
     * 校验迁移来源任务没有 owner 或委派状态。
     *
     * @param task Task，待退回或驳回的真实活动任务
     * @return 无返回值，委派状态存在时抛出既有 HTTP 409
     */
    public void requireUnownedTask(Task task)
    {
        if (task.getOwner() != null || task.getDelegationState() != null)
        {
            throw conflict();
        }
    }

    /**
     * 查询指定实例位于目标节点的唯一活动任务。
     *
     * @param processInstanceId String，流程实例主键
     * @param targetKey String，服务端确定的目标节点 key
     * @return Task，唯一活动任务
     */
    public Task requireSingleActiveTask(String processInstanceId, String targetKey)
    {
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).active().list();
        if (tasks == null || tasks.size() != 1 || tasks.get(0) == null
                || !targetKey.equals(tasks.get(0).getTaskDefinitionKey()))
        {
            throw conflict();
        }
        return tasks.get(0);
    }

    /**
     * 核验指定任务是流程实例唯一活动任务并带有完整执行事实。
     *
     * @param expected Task，前序查询冻结的预期活动任务
     * @return String，唯一普通 execution 主键
     */
    public String requireOnlyActiveExecution(Task expected)
    {
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(expected.getProcessInstanceId()).active().list();
        if (tasks == null || tasks.size() != 1 || tasks.get(0) == null
                || !expected.getId().equals(tasks.get(0).getId())
                || !StringUtils.hasText(tasks.get(0).getExecutionId())
                || !expected.getProcessDefinitionId().equals(
                        tasks.get(0).getProcessDefinitionId()))
        {
            throw conflict();
        }
        return tasks.get(0).getExecutionId();
    }

    /**
     * 按创建时间读取实例最早的有限历史任务集合。
     *
     * @param processInstanceId String，流程实例主键
     * @param limit int，防御性最大返回数量
     * @return List&lt;HistoricTaskInstance&gt;，按开始时间升序的历史任务
     */
    public List<HistoricTaskInstance> readHistoricTasksAscending(
            String processInstanceId, int limit)
    {
        if (limit < 1)
        {
            throw new IllegalArgumentException("历史任务读取上限必须大于零");
        }
        List<HistoricTaskInstance> tasks = historyService
                .createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .orderByHistoricTaskInstanceStartTime().asc().listPage(0, limit);
        if (tasks == null)
        {
            throw dataError();
        }
        return List.copyOf(tasks);
    }

    /**
     * 读取实例唯一真实开始活动。
     *
     * @param processInstanceId String，流程实例主键
     * @return HistoricActivityInstance，唯一最早开始节点历史
     */
    public HistoricActivityInstance requireStartActivity(String processInstanceId)
    {
        List<HistoricActivityInstance> starts = historyService
                .createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId).activityType("startEvent")
                .orderByHistoricActivityInstanceStartTime().asc().listPage(0, 2);
        if (starts == null || starts.size() != 1
                || !StringUtils.hasText(starts.get(0).getActivityId()))
        {
            throw dataError();
        }
        return starts.get(0);
    }

    /**
     * 查询真实已完成历史任务并区分活动、已删除和完全不存在。
     *
     * @param taskId String，历史任务主键
     * @return HistoricTaskInstance，真实已完成任务
     */
    public HistoricTaskInstance requireCompletedTask(String taskId)
    {
        HistoricTaskInstance historicTask = historyService
                .createHistoricTaskInstanceQuery().taskId(taskId).finished()
                .singleResult();
        if (historicTask != null)
        {
            return historicTask;
        }
        if (taskService.createTaskQuery().taskId(taskId).singleResult() != null
                || historyService.createHistoricTaskInstanceQuery()
                        .taskId(taskId).singleResult() != null)
        {
            throw conflict();
        }
        throw notFound();
    }

    /**
     * 判断来源任务结束后是否已有其他任务完成。
     *
     * @param completedTask HistoricTaskInstance，撤回来源任务
     * @return boolean，来源结束后存在已处理后继时返回 true
     */
    public boolean hasFinishedSuccessor(HistoricTaskInstance completedTask)
    {
        List<HistoricTaskInstance> finishedTasks = historyService
                .createHistoricTaskInstanceQuery()
                .processInstanceId(completedTask.getProcessInstanceId())
                .finished().list();
        if (finishedTasks == null)
        {
            throw dataError();
        }
        Date sourceEndTime = completedTask.getEndTime();
        return finishedTasks.stream().anyMatch(task -> task != null
                && !completedTask.getId().equals(task.getId())
                && task.getEndTime() != null
                && !task.getEndTime().before(sourceEndTime));
    }

    /**
     * 创建稳定对象不存在错误。
     *
     * @return ServiceException，既有 HTTP 404 错误
     */
    private ServiceException notFound()
    {
        return new ServiceException("工作流对象不存在或已被删除", HttpStatus.NOT_FOUND);
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
     * 创建稳定对象级权限错误。
     *
     * @return ServiceException，既有 HTTP 403 错误
     */
    private ServiceException forbidden()
    {
        return new ServiceException("无权执行当前工作流操作", HttpStatus.FORBIDDEN);
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
}
