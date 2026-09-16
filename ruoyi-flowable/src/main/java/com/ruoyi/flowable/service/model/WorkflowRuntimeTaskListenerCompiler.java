package com.ruoyi.flowable.service.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowableListener;
import org.flowable.bpmn.model.ImplementationType;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.UserTask;
import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 在所有业务编译器完成任务生成后，为最终执行 BPMN 注入固定运行时任务监听器。
 */
@Service
public class WorkflowRuntimeTaskListenerCompiler
{
    /** 最终执行资源唯一允许的内部任务监听器 Bean 表达式。 */
    static final String SYSTEM_LISTENER_EXPRESSION = "${userTaskListener}";

    /** 身份核验与审计必须覆盖的完整任务生命周期事件。 */
    static final Set<String> SYSTEM_LISTENER_EVENTS =
            Set.of("create", "assignment", "complete");

    /**
     * 为前序阶段生成的每个可执行 UserTask 追加唯一固定监听器集合。
     *
     * @param model BpmnModel，全部任务生成完成后的执行模型
     * @return void，修改本次执行模型
     */
    public void compile(BpmnModel model)
    {
        for (Process process : model.getProcesses())
        {
            if (!process.isExecutable())
            {
                continue;
            }
            // 此阶段位于所有任务生成器之后，普通任务和编译器生成任务必须接受同一运行合同。
            for (UserTask task : process.findFlowElementsOfType(UserTask.class, true))
            {
                injectSystemListeners(task);
            }
        }
    }

    /**
     * 保留已经编译的业务任务监听器，并追加固定身份核验与审计监听器。
     *
     * @param task UserTask，所有上游业务编译器已经处理完成的任务
     * @return void，上游泄漏内部监听器时失败关闭，否则原位写入完整固定集合
     */
    private void injectSystemListeners(UserTask task)
    {
        List<FlowableListener> businessListeners = task.getTaskListeners() == null
                ? List.of() : List.copyOf(task.getTaskListeners());
        if (businessListeners.stream().anyMatch(listener -> listener != null
                && SYSTEM_LISTENER_EXPRESSION.equals(listener.getImplementation())))
        {
            throw new ServiceException("运行时任务监听器在最终编译前已被注入", HttpStatus.ERROR);
        }
        List<FlowableListener> compiledListeners =
                new ArrayList<>(businessListeners.size() + SYSTEM_LISTENER_EVENTS.size());
        compiledListeners.addAll(businessListeners);
        for (String event : List.of("create", "assignment", "complete"))
        {
            compiledListeners.add(systemListener(event));
        }
        task.setTaskListeners(compiledListeners);
    }

    /**
     * 创建单个固定任务生命周期监听器。
     *
     * @param event String，create、assignment 或 complete 固定事件
     * @return FlowableListener，引用平台内部 userTaskListener 的委托表达式监听器
     */
    private FlowableListener systemListener(String event)
    {
        FlowableListener listener = new FlowableListener();
        listener.setEvent(event);
        listener.setImplementationType(
                ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
        listener.setImplementation(SYSTEM_LISTENER_EXPRESSION);
        return listener;
    }
}
