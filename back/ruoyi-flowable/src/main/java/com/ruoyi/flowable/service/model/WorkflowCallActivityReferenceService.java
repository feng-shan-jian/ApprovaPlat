package com.ruoyi.flowable.service.model;

import java.io.ByteArrayInputStream;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.CallActivity;
import org.flowable.bpmn.model.Process;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 将调用活动编译为精确流程定义引用，并保护仍被已发布流程使用的目标部署。
 */
@Service
public class WorkflowCallActivityReferenceService
{
    /** Flowable 8 通过 calledElementType=id 按不可变定义主键解析调用目标。 */
    private static final String CALLED_ELEMENT_TYPE_ID = "id";

    private final RepositoryService repositoryService;

    /**
     * 创建调用活动部署引用服务。
     *
     * @param repositoryService RepositoryService，Flowable 流程定义与部署资源公共 API
     * @return 无返回值，构造后由 Spring 管理该服务
     */
    public WorkflowCallActivityReferenceService(RepositoryService repositoryService)
    {
        this.repositoryService = repositoryService;
    }

    /**
     * 把作者资源中的固定流程 key 解析为当前最新激活定义 ID，并生成不可漂移的部署 XML。
     *
     * @param compiledBpmn byte[]，已经完成扩展和 DMN 编译的 UTF-8 BPMN 资源
     * @return byte[]，所有 CallActivity 均引用精确定义 ID 的 Flowable 可执行资源
     */
    public byte[] freezeReferences(byte[] compiledBpmn)
    {
        org.flowable.bpmn.model.BpmnModel model = parse(compiledBpmn);
        Set<String> localProcessKeys = executableProcessKeys(model);
        for (Process process : model.getProcesses())
        {
            for (CallActivity callActivity : process.findFlowElementsOfType(CallActivity.class, true))
            {
                freezeReference(callActivity, localProcessKeys);
            }
        }
        return new BpmnXMLConverter().convertToXML(model);
    }

    /**
     * 返回全部已发布 BPMN 中通过精确定义 ID 冻结的调用目标。
     *
     * @return Set&lt;String&gt;，仍需保持可调用状态的流程定义主键集合
     */
    public Set<String> frozenTargetDefinitionIds()
    {
        List<ProcessDefinition> definitions = repositoryService
                .createProcessDefinitionQuery().list();
        if (definitions == null || definitions.isEmpty())
        {
            return Set.of();
        }
        Set<String> targetIds = new LinkedHashSet<>();
        for (ProcessDefinition definition : definitions)
        {
            collectFrozenTargets(definition, targetIds);
        }
        return Set.copyOf(targetIds);
    }

    /**
     * 删除部署前检查其流程定义是否仍被删除范围之外的调用活动引用。
     *
     * @param deploymentIds Collection&lt;String&gt;，本次事务准备删除的部署主键
     * @return void，存在外部引用时抛出 409 且不产生删除副作用
     */
    public void assertDeploymentsNotReferenced(Collection<String> deploymentIds)
    {
        Set<String> deletingDeployments = deploymentIds == null
                ? Set.of() : Set.copyOf(deploymentIds);
        if (deletingDeployments.isEmpty())
        {
            return;
        }

        List<ProcessDefinition> definitions = repositoryService
                .createProcessDefinitionQuery().list();
        if (definitions == null || definitions.isEmpty())
        {
            return;
        }
        Set<String> deletingDefinitionIds = definitions.stream()
                .filter(definition -> deletingDeployments.contains(definition.getDeploymentId()))
                .map(ProcessDefinition::getId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (deletingDefinitionIds.isEmpty())
        {
            return;
        }

        for (ProcessDefinition definition : definitions)
        {
            if (deletingDeployments.contains(definition.getDeploymentId()))
            {
                continue;
            }
            Set<String> referencedIds = new LinkedHashSet<>();
            collectFrozenTargets(definition, referencedIds);
            if (referencedIds.stream().anyMatch(deletingDefinitionIds::contains))
            {
                throw new ServiceException("部署仍被调用活动引用，不能删除", HttpStatus.CONFLICT);
            }
        }
    }

    /**
     * 冻结单个调用活动；动态表达式和同资源递归引用无法形成部署前精确版本，因此拒绝部署。
     *
     * @param callActivity CallActivity，待编译的调用活动
     * @param localProcessKeys Set&lt;String&gt;，本次资源内可执行流程 key 集合
     * @return void，成功时原地写入 calledElementType=id 与精确定义主键
     */
    private void freezeReference(CallActivity callActivity, Set<String> localProcessKeys)
    {
        String calledElement = requireText(callActivity.getCalledElement(), "调用活动必须配置被调用流程");
        if (containsExpression(calledElement))
        {
            throw new ServiceException("调用活动不允许使用动态流程表达式", HttpStatus.BAD_REQUEST);
        }
        if (localProcessKeys.contains(calledElement)
                && !CALLED_ELEMENT_TYPE_ID.equals(callActivity.getCalledElementType()))
        {
            throw new ServiceException("调用活动目标必须先独立部署，不能引用当前资源内流程", HttpStatus.BAD_REQUEST);
        }

        ProcessDefinition target;
        if (CALLED_ELEMENT_TYPE_ID.equals(callActivity.getCalledElementType()))
        {
            target = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionId(calledElement).singleResult();
        }
        else
        {
            target = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey(calledElement)
                    .processDefinitionWithoutTenantId()
                    .latestVersion()
                    .singleResult();
        }
        if (target == null || target.isSuspended())
        {
            throw new ServiceException("调用活动目标流程不存在或未启用", HttpStatus.CONFLICT);
        }

        // 部署资源只保存不可变定义 ID；后续同 key 新版本不会改变既有父流程的运行语义。
        callActivity.setCalledElement(requireText(target.getId(), "调用活动目标定义状态异常"));
        callActivity.setCalledElementType(CALLED_ELEMENT_TYPE_ID);
        callActivity.setSameDeployment(false);
    }

    /**
     * 从一个已发布定义的编译 BPMN 中提取精确调用目标。
     *
     * @param definition ProcessDefinition，待读取的已发布流程定义
     * @param targetIds Set&lt;String&gt;，调用方维护的目标定义去重集合
     * @return void，读取失败时 fail-closed，避免挂起或删除仍在使用的目标
     */
    private void collectFrozenTargets(ProcessDefinition definition, Set<String> targetIds)
    {
        try
        {
            org.flowable.bpmn.model.BpmnModel model = repositoryService.getBpmnModel(definition.getId());
            if (model == null)
            {
                throw new ServiceException("已发布流程 BPMN 资源不存在", HttpStatus.CONFLICT);
            }
            for (Process process : model.getProcesses())
            {
                process.findFlowElementsOfType(CallActivity.class, true).stream()
                        .filter(call -> CALLED_ELEMENT_TYPE_ID.equals(call.getCalledElementType()))
                        .map(CallActivity::getCalledElement)
                        .filter(WorkflowCallActivityReferenceService::hasText)
                        .map(String::trim)
                        .forEach(targetIds::add);
            }
        }
        catch (FlowableException exception)
        {
            ServiceException failure = new ServiceException(
                    "已发布流程调用引用读取失败", HttpStatus.CONFLICT);
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * 使用禁用 DTD、外部实体和实体替换的 StAX Reader 解析待部署 BPMN。
     *
     * @param bpmnBytes byte[]，待解析的完整 BPMN 资源
     * @return org.flowable.bpmn.model.BpmnModel，保留标准与 Flowable 扩展的结构模型
     */
    private org.flowable.bpmn.model.BpmnModel parse(byte[] bpmnBytes)
    {
        if (bpmnBytes == null || bpmnBytes.length == 0)
        {
            throw new ServiceException("调用活动编译资源不能为空", HttpStatus.BAD_REQUEST);
        }
        XMLStreamReader reader = null;
        try
        {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
            factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
            factory.setXMLResolver((publicId, systemId, baseUri, namespace) ->
            {
                throw new XMLStreamException("external resources disabled");
            });
            reader = factory.createXMLStreamReader(new ByteArrayInputStream(bpmnBytes));
            return new BpmnXMLConverter().convertToBpmnModel(reader);
        }
        catch (XMLStreamException | IllegalArgumentException exception)
        {
            ServiceException failure = new ServiceException(
                    "调用活动 BPMN 编译失败", HttpStatus.BAD_REQUEST);
            failure.initCause(exception);
            throw failure;
        }
        finally
        {
            close(reader);
        }
    }

    /**
     * 提取资源内全部可执行流程 key，阻止尚无定义 ID 的同资源调用伪装成已冻结引用。
     *
     * @param model org.flowable.bpmn.model.BpmnModel，待部署 BPMN 模型
     * @return Set&lt;String&gt;，非空可执行流程 key 集合
     */
    private Set<String> executableProcessKeys(org.flowable.bpmn.model.BpmnModel model)
    {
        return model.getProcesses().stream()
                .filter(Process::isExecutable)
                .map(Process::getId)
                .filter(WorkflowCallActivityReferenceService::hasText)
                .map(String::trim)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * 关闭 XML Reader，关闭异常不得覆盖已经形成的业务校验结果。
     *
     * @param reader XMLStreamReader，可为空的解析器
     * @return void，关闭失败时静默结束
     */
    private void close(XMLStreamReader reader)
    {
        if (reader == null)
        {
            return;
        }
        try
        {
            reader.close();
        }
        catch (XMLStreamException ignored)
        {
            // Reader 关闭失败不改变部署编译结论。
        }
    }

    /**
     * 判断调用目标是否包含运行时表达式标记。
     *
     * @param value String，待检查调用目标
     * @return boolean，包含 ${...} 或 #{...} 时返回 true
     */
    private boolean containsExpression(String value)
    {
        return value.contains("${") || value.contains("#{");
    }

    /**
     * 规范化必填文本并返回稳定业务异常。
     *
     * @param value String，待校验文本
     * @param message String，空值时对外提示
     * @return String，去除首尾空白后的非空文本
     */
    private String requireText(String value, String message)
    {
        if (!hasText(value))
        {
            throw new ServiceException(message, HttpStatus.BAD_REQUEST);
        }
        return value.trim();
    }

    /**
     * 判断文本是否包含非空白字符。
     *
     * @param value String，待判断文本
     * @return boolean，非空白时返回 true
     */
    private static boolean hasText(String value)
    {
        return value != null && !value.isBlank();
    }
}
