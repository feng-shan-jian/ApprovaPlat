package com.ruoyi.flowable.service.impl;

import java.io.ByteArrayInputStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Model;
import org.flowable.engine.repository.ProcessDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.WorkflowReferenceChecker;

/**
 * 基于 Flowable 8 公共 RepositoryService 和业务制品子部署资源的真实引用检查器。
 */
@Service
public class FlowableWorkflowReferenceChecker implements WorkflowReferenceChecker
{
    private static final Logger LOGGER = LoggerFactory.getLogger(FlowableWorkflowReferenceChecker.class);

    /** 模型元数据中的表单主键字段。 */
    private static final String FORM_ID_FIELD = "formId";

    /** 正式 BPMN 表单键固定前缀。 */
    private static final String FORM_KEY_PREFIX = "key_";

    private final RepositoryService repositoryService;
    private final WorkflowDeploymentArtifactRepository artifactRepository;
    private final ObjectMapper objectMapper;

    /**
     * 创建引用检查器。
     * @return 构造函数，无返回值
     */
    public FlowableWorkflowReferenceChecker(RepositoryService repositoryService,
            WorkflowDeploymentArtifactRepository artifactRepository)
    {
        this.repositoryService = repositoryService;
        this.artifactRepository = artifactRepository;
        this.objectMapper = JsonMapper.shared();
    }

    /**
     * 判断分类编码是否被未部署模型或已部署流程定义引用。
     * @param categoryCode String，分类编码
     * @return boolean，存在引用或仓库查询失败时返回 true
     */
    @Override
    public boolean hasCategoryReference(String categoryCode)
    {
        if (categoryCode == null || categoryCode.isBlank())
        {
            return true;
        }

        try
        {
            // 未部署模型和已部署定义是两类独立引用，任一命中都必须阻止删除。
            long modelCount = repositoryService.createModelQuery()
                    .notDeployed()
                    .modelCategory(categoryCode)
                    .count();
            if (modelCount > 0)
            {
                return true;
            }
            return repositoryService.createProcessDefinitionQuery()
                    .processDefinitionCategory(categoryCode)
                    .count() > 0;
        }
        catch (RuntimeException exception)
        {
            // 引擎不可用时不能证明“无引用”，因此保守拒绝删除并保留内部日志。
            LOGGER.warn("工作流分类引用检查失败，已按存在引用处理，categoryCode={}",
                    categoryCode, exception);
            return true;
        }
    }

    /**
     * 判断任一表单是否被部署快照、任意模型或已部署流程定义引用。
     * @param formIds Collection&lt;Long&gt;，待检查表单主键集合
     * @return boolean，存在引用或任一资源无法可靠解析时返回 true
     */
    @Override
    public boolean hasFormReference(Collection<Long> formIds)
    {
        Set<Long> targetFormIds = normalizeFormIds(formIds);
        if (targetFormIds.isEmpty())
        {
            return true;
        }

        try
        {
            // 部署快照直接保留 form_id 溯源，优先用业务表索引完成低成本检查。
            if (artifactRepository.hasFormReference(targetFormIds))
            {
                return true;
            }
            if (hasModelReference(targetFormIds))
            {
                return true;
            }
            return hasProcessDefinitionReference(targetFormIds);
        }
        catch (RuntimeException exception)
        {
            // 查询、读取或解析失败均不能作为“无引用”证据，统一保守拒绝删除。
            LOGGER.warn("工作流表单引用检查失败，已按存在引用处理，formIds={}",
                    targetFormIds, exception);
            return true;
        }
    }

    /**
     * 规范化待检查表单主键，删除空值和非法值并去重。
     * @param formIds Collection&lt;Long&gt;，原始表单主键集合
     * @return Set&lt;Long&gt;，仅包含正数主键的去重集合
     */
    private Set<Long> normalizeFormIds(Collection<Long> formIds)
    {
        Set<Long> normalized = new HashSet<>();
        if (formIds == null)
        {
            return normalized;
        }
        for (Long formId : formIds)
        {
            if (formId != null && formId > 0)
            {
                normalized.add(formId);
            }
        }
        return normalized;
    }

    /**
     * 检查所有模型的元数据和 BPMN 编辑器资源，部署状态不改变作者引用。
     * @param targetFormIds Set&lt;Long&gt;，待检查表单主键集合
     * @return boolean，任一模型引用目标表单时返回 true
     */
    private boolean hasModelReference(Set<Long> targetFormIds)
    {
        List<Model> models = repositoryService.createModelQuery().list();
        for (Model model : models)
        {
            if (metadataReferencesForm(model.getMetaInfo(), targetFormIds))
            {
                return true;
            }
            if (!model.hasEditorSource())
            {
                continue;
            }

            byte[] source = repositoryService.getModelEditorSource(model.getId());
            if (source == null || source.length == 0)
            {
                throw new IllegalStateException("模型声明了 BPMN 资源但资源为空: " + model.getId());
            }
            BpmnModel bpmnModel = new BpmnXMLConverter().convertToBpmnModel(
                    () -> new ByteArrayInputStream(source), true, true);
            if (!isUsableBpmnModel(bpmnModel))
            {
                throw new IllegalStateException("模型 BPMN 资源没有可解析流程: " + model.getId());
            }
            if (bpmnModelReferencesForm(bpmnModel, targetFormIds))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查所有已部署流程定义的 BPMN 表单键。
     * @param targetFormIds Set&lt;Long&gt;，待检查表单主键集合
     * @return boolean，任一已部署定义引用目标表单时返回 true
     */
    private boolean hasProcessDefinitionReference(Set<Long> targetFormIds)
    {
        List<ProcessDefinition> definitions = repositoryService.createProcessDefinitionQuery().list();
        for (ProcessDefinition definition : definitions)
        {
            BpmnModel bpmnModel = repositoryService.getBpmnModel(definition.getId());
            if (!isUsableBpmnModel(bpmnModel))
            {
                throw new IllegalStateException("已部署流程定义 BPMN 无法解析: " + definition.getId());
            }
            if (bpmnModelReferencesForm(bpmnModel, targetFormIds))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * 按正式模型协议判断顶层 formId 是否引用目标表单。
     * @param metaInfo String，Flowable 模型元数据 JSON
     * @param targetFormIds Set&lt;Long&gt;，待检查表单主键集合
     * @return boolean，元数据引用目标表单时返回 true
     */
    private boolean metadataReferencesForm(String metaInfo, Set<Long> targetFormIds)
    {
        if (metaInfo == null || metaInfo.isBlank())
        {
            return false;
        }
        try
        {
            JsonNode root = objectMapper.readTree(metaInfo);
            if (root == null || !root.isObject())
            {
                throw new IllegalArgumentException("模型元数据根节点必须是对象");
            }
            JsonNode formId = root.get(FORM_ID_FIELD);
            if (formId == null || formId.isNull())
            {
                return false;
            }
            if (!formId.isIntegralNumber() || !formId.canConvertToLong() || formId.longValue() <= 0)
            {
                throw new IllegalArgumentException("模型元数据 formId 必须是正整数");
            }
            return targetFormIds.contains(formId.longValue());
        }
        catch (Exception exception)
        {
            throw new IllegalStateException("模型元数据无法解析", exception);
        }
    }

    /**
     * 判断 BPMN 模型的开始节点或用户任务表单键是否引用目标表单。
     * @param bpmnModel BpmnModel，已成功解析的 BPMN 模型
     * @param targetFormIds Set&lt;Long&gt;，待检查表单主键集合
     * @return boolean，模型引用目标表单时返回 true
     */
    private boolean bpmnModelReferencesForm(BpmnModel bpmnModel, Set<Long> targetFormIds)
    {
        for (org.flowable.bpmn.model.Process process : bpmnModel.getProcesses())
        {
            for (StartEvent startEvent : process.findFlowElementsOfType(StartEvent.class, true))
            {
                if (formKeyReferencesForm(startEvent.getFormKey(), targetFormIds))
                {
                    return true;
                }
            }
            for (UserTask userTask : process.findFlowElementsOfType(UserTask.class, true))
            {
                if (formKeyReferencesForm(userTask.getFormKey(), targetFormIds))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断正式 key_&lt;formId&gt; 表单键是否命中目标表单。
     * @param formKey String，BPMN 中的表单键
     * @param targetFormIds Set&lt;Long&gt;，待检查表单主键集合
     * @return boolean，表单键命中任一目标表单时返回 true
     */
    private boolean formKeyReferencesForm(String formKey, Set<Long> targetFormIds)
    {
        if (formKey == null || formKey.isBlank())
        {
            return false;
        }
        String normalized = formKey.trim();
        for (Long formId : targetFormIds)
        {
            if (normalized.equals(FORM_KEY_PREFIX + formId))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断 BPMN 模型是否包含至少一个可检查流程。
     * @param bpmnModel BpmnModel，待检查模型
     * @return boolean，模型非空且包含流程时返回 true
     */
    private boolean isUsableBpmnModel(BpmnModel bpmnModel)
    {
        return bpmnModel != null && bpmnModel.getProcesses() != null
                && !bpmnModel.getProcesses().isEmpty();
    }
}
