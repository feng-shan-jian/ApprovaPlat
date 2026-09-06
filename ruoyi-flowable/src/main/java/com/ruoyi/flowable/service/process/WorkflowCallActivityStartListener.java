package com.ruoyi.flowable.service.process;

import java.util.Map;
import java.util.Set;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.engine.delegate.event.AbstractFlowableEngineEventListener;
import org.flowable.engine.delegate.event.FlowableProcessStartedEvent;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;
import com.ruoyi.flowable.domain.vo.WorkflowProcessFormView;

/**
 * 在 CallActivity 创建子流程的同一 Flowable 命令内初始化其正式开始事实。
 */
public final class WorkflowCallActivityStartListener
        extends AbstractFlowableEngineEventListener
{
    /** 子流程定义和开始表单读取服务提供器，延迟到引擎命令发生后解析。 */
    private final ObjectProvider<WorkflowProcessDefinitionQueryService> definitionQueryServiceProvider;

    /** 调用映射值的正式开始表单校验器提供器，避免引擎初始化循环依赖。 */
    private final ObjectProvider<WorkflowStartVariableValidator> variableValidatorProvider;

    /** 子流程开始后的正式运行状态。 */
    private static final String RUNNING_STATUS = "running";

    /**
     * 创建只处理 CallActivity 子流程开始事件的监听器。
     *
     * @param definitionQueryServiceProvider ObjectProvider&lt;WorkflowProcessDefinitionQueryService&gt;，读取子定义开始表单
     * @param variableValidatorProvider ObjectProvider&lt;WorkflowStartVariableValidator&gt;，校验调用映射值
     * @return 无返回值，构造后由 Flowable 全局事件配置注册
     */
    public WorkflowCallActivityStartListener(
            ObjectProvider<WorkflowProcessDefinitionQueryService> definitionQueryServiceProvider,
            ObjectProvider<WorkflowStartVariableValidator> variableValidatorProvider)
    {
        super(Set.of(FlowableEngineEventType.PROCESS_STARTED));
        this.definitionQueryServiceProvider = definitionQueryServiceProvider;
        this.variableValidatorProvider = variableValidatorProvider;
    }

    /**
     * 校验子流程执行树关系，并从已建立的输入映射初始化双状态与 v2 开始提交快照。
     *
     * @param event FlowableProcessStartedEvent，携带新实例及 Flowable 已完成的输入映射变量
     * @return void，根流程由人工发起服务处理；子流程映射或表单事实非法时回滚整个创建命令
     */
    @Override
    protected void processStarted(FlowableProcessStartedEvent event)
    {
        Object eventEntity = event == null ? null : event.getEntity();
        if (!(eventEntity instanceof ExecutionEntity startedExecution))
        {
            throw new FlowableException("流程开始事件缺少有效的启动执行实体");
        }
        // PROCESS_STARTED 可能携带第一个活动执行，需要沿父执行定位新流程实例根。
        ExecutionEntity processInstance = startedExecution.isProcessInstanceType()
                ? startedExecution : startedExecution.getParent();
        if (processInstance == null || !processInstance.isProcessInstanceType()
                || !StringUtils.hasText(processInstance.getId()))
        {
            throw new FlowableException("流程开始事件缺少有效的流程实例实体");
        }
        if (!StringUtils.hasText(processInstance.getSuperExecutionId()))
        {
            requireRootRelation(processInstance);
            return;
        }
        requireCallActivityChildRelation(processInstance);

        WorkflowProcessFormView startForm = definitionQueryServiceProvider.getObject()
                .loadCallActivityStartForm(processInstance.getProcessDefinitionId());
        Map<String, Object> mappedVariables = processInstance.getVariables();
        Map<String, Object> submittedValues = variableValidatorProvider.getObject()
                .normalizeCallActivityStartValues(startForm.content(), mappedVariables);
        String submissionSnapshot = WorkflowFormSubmissionSnapshotCodec.encodeStart(
                startForm.deploymentId(), startForm.sourceType(), startForm.formId(),
                startForm.formKey(), startForm.nodeKey(), submittedValues);

        // 三项事实必须在同一引擎命令中原子写入，任一失败不能留下可见子流程或子任务。
        CommandContextUtil.getExecutionEntityManager()
                .updateProcessInstanceBusinessStatus(processInstance, RUNNING_STATUS);
        processInstance.setVariableLocal(WorkflowProcessStartService.PROCESS_STATUS_VARIABLE,
                RUNNING_STATUS);
        processInstance.setVariableLocal(WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME,
                submissionSnapshot);
    }

    /**
     * 校验人工发起的根流程没有伪造 CallActivity 树关系。
     *
     * @param processInstance ExecutionEntity，PROCESS_STARTED 对应的根流程实例
     * @return void，rootProcessInstanceId 与自身矛盾时拒绝当前命令
     */
    private void requireRootRelation(ExecutionEntity processInstance)
    {
        String rootProcessInstanceId = processInstance.getRootProcessInstanceId();
        if (StringUtils.hasText(rootProcessInstanceId)
                && !processInstance.getId().equals(rootProcessInstanceId))
        {
            throw new FlowableException("根流程实例执行树关系异常: " + processInstance.getId());
        }
    }

    /**
     * 校验 CallActivity 子流程具有独立且有效的根流程关系。
     *
     * @param processInstance ExecutionEntity，具有 super execution 的子流程实例
     * @return void，根关系缺失或错误时拒绝当前命令
     */
    private void requireCallActivityChildRelation(ExecutionEntity processInstance)
    {
        String rootProcessInstanceId = processInstance.getRootProcessInstanceId();
        if (!StringUtils.hasText(rootProcessInstanceId)
                || processInstance.getId().equals(rootProcessInstanceId))
        {
            throw new FlowableException(
                    "CallActivity 子流程执行树根关系异常: " + processInstance.getId());
        }
    }
}
