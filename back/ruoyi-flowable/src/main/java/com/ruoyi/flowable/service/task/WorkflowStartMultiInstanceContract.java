package com.ruoyi.flowable.service.task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.UserTask;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.vo.WorkflowStartMultiInstanceAssignmentView;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;

/**
 * 发起时会签或或签成员来源契约，统一页面投影、请求校验和保留变量生成。
 */
public final class WorkflowStartMultiInstanceContract
{
    /** 发起页面单个受控节点至少选择一名成员。 */
    public static final int MIN_USERS = 1;

    /**
     * 阻止工具类被实例化。
     *
     * @return 无返回值；任何实例化尝试都抛出 AssertionError。
     */
    private WorkflowStartMultiInstanceContract()
    {
        throw new AssertionError("发起多实例契约类不能实例化");
    }

    /**
     * 从指定可执行流程提取全部发起时成员字段。
     *
     * @param model BpmnModel，Flowable 已保存或已部署模型。
     * @param processKey String，流程定义 key，与 BPMN process id 一致。
     * @return List<WorkflowStartMultiInstanceAssignmentView> 按 BPMN 节点顺序返回的只读字段。
     */
    public static List<WorkflowStartMultiInstanceAssignmentView> describe(
            BpmnModel model, String processKey)
    {
        if (model == null || !StringUtils.hasText(processKey))
        {
            throw dataError();
        }
        org.flowable.bpmn.model.Process process = model.getProcessById(processKey);
        if (process == null || !process.isExecutable())
        {
            throw dataError();
        }
        List<WorkflowStartMultiInstanceAssignmentView> result = new ArrayList<>();
        LinkedHashSet<String> activityIds = new LinkedHashSet<>();
        for (UserTask task : process.findFlowElementsOfType(UserTask.class, false))
        {
            if (!WorkflowMultiInstanceModelContract.usesStartHandler(
                    task.getLoopCharacteristics()))
            {
                continue;
            }
            WorkflowMultiInstanceMode mode;
            try
            {
                mode = WorkflowMultiInstanceModelContract.requireMode(task);
            }
            catch (IllegalArgumentException exception)
            {
                throw dataError();
            }
            if (!activityIds.add(task.getId()))
            {
                throw dataError();
            }
            String activityName = StringUtils.hasText(task.getName())
                    ? task.getName().trim() : task.getId();
            result.add(new WorkflowStartMultiInstanceAssignmentView(task.getId(),
                    activityName, mode.name(), MIN_USERS,
                    WorkflowUserSelectionValidator.MAX_SELECTED_USERS));
        }
        return List.copyOf(result);
    }

    /**
     * 校验发起请求与部署模型要求完全一致，并生成只能由服务端写入的活动专属变量。
     *
     * @param model BpmnModel，当前激活流程定义的已部署模型。
     * @param processKey String，当前流程定义 key。
     * @param selections Map<String,List<Long>>，客户端按活动提交的用户主键集合。
     * @param validator WorkflowUserSelectionValidator，正式审批资格校验器。
     * @return Map<String,Object> 可合并到 Flowable start 命令的保留变量。
     */
    public static Map<String, Object> prepareVariables(BpmnModel model, String processKey,
            Map<String, List<Long>> selections, WorkflowUserSelectionValidator validator)
    {
        if (validator == null)
        {
            throw dataError();
        }
        List<WorkflowStartMultiInstanceAssignmentView> assignments = describe(model, processKey);
        Map<String, List<Long>> source = selections == null ? Map.of() : selections;
        LinkedHashSet<String> expectedActivityIds = new LinkedHashSet<>();
        assignments.forEach(assignment -> expectedActivityIds.add(assignment.activityId()));
        if (!expectedActivityIds.equals(new LinkedHashSet<>(source.keySet())))
        {
            throw invalidSelection();
        }

        LinkedHashMap<String, Object> variables = new LinkedHashMap<>();
        for (WorkflowStartMultiInstanceAssignmentView assignment : assignments)
        {
            List<Long> requestedUserIds = source.get(assignment.activityId());
            List<String> eligibleUserIds = validator.requireApprovalEligibleUserIds(
                    requestedUserIds);
            if (eligibleUserIds.size() < assignment.minUsers()
                    || eligibleUserIds.size() > assignment.maxUsers())
            {
                throw invalidSelection();
            }
            List<Long> canonicalUserIds = eligibleUserIds.stream().map(Long::valueOf).toList();
            variables.put(WorkflowMultiInstanceVariables.userCollectionName(
                    assignment.activityId()), canonicalUserIds);
        }
        return Map.copyOf(variables);
    }

    /**
     * 创建发起成员缺失、额外或为空时的稳定参数异常。
     *
     * @return ServiceException，HTTP 400 且不会进入 Flowable 写命令。
     */
    private static ServiceException invalidSelection()
    {
        return new ServiceException("发起时会签或或签成员配置不完整", HttpStatus.BAD_REQUEST);
    }

    /**
     * 创建部署模型读取异常，避免把不完整 BPMN 当作无选人要求。
     *
     * @return ServiceException，HTTP 500 模型数据异常。
     */
    private static ServiceException dataError()
    {
        return new ServiceException("工作流发起多实例模型数据异常", HttpStatus.ERROR);
    }
}
