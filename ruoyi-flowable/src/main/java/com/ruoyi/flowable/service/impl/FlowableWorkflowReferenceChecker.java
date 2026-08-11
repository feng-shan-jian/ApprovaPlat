package com.ruoyi.flowable.service.impl;

import java.io.ByteArrayInputStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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

    /** 模型元数据中的表单键字段。 */
    private static final String FORM_KEY_FIELD = "formKey";

    /** 兼容旧 BPMN 表单键的固定前缀。 */
    private static final String LEGACY_FORM_KEY_PREFIX = "key_";

    private final RepositoryService repositoryService;
    private final WorkflowDeploymentArtifactRepository artifactRepository;
    private final ObjectMapper objectMapper;

    /**
     * 创建引用检查器。
     * @param repositoryService RepositoryService，Flowable 公共仓库服务
     * @param artifactRepository WorkflowDeploymentArtifactRepository，部署表单资源仓库
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
     * 判断任一表单是否被部署快照、未部署模型或已部署流程定义引用。
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
            if (hasUndeployedModelReference(targetFormIds))
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
     * 检查未部署模型的元数据和 BPMN 编辑器资源。
     * @param targetFormIds Set&lt;Long&gt;，待检查表单主键集合
     * @return boolean，任一模型引用目标表单时返回 true
     */
    private boolean hasUndeployedModelReference(Set<Long> targetFormIds)
    {
        List<Model> models = repositoryService.createModelQuery().notDeployed().list();
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
     * 判断模型元数据中的 formId 或 formKey 是否引用目标表单。
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
            if (root == null || (!root.isObject() && !root.isArray()))
            {
                throw new IllegalArgumentException("模型元数据根节点必须是对象或数组");
            }
            return metadataNodeReferencesForm(root, targetFormIds);
        }
        catch (Exception exception)
        {
            throw new IllegalStateException("模型元数据无法解析", exception);
        }
    }

    /**
     * 递归检查模型元数据节点，只将名为 formId/formKey 的字段解释为表单引用。
     * @param node JsonNode，当前元数据节点
     * @param targetFormIds Set&lt;Long&gt;，待检查表单主键集合
     * @return boolean，当前节点或子节点包含目标表单引用时返回 true
     */
    private boolean metadataNodeReferencesForm(JsonNode node, Set<Long> targetFormIds)
    {
        if (node.isArray())
        {
            for (JsonNode child : node)
            {
                if (metadataNodeReferencesForm(child, targetFormIds))
                {
                    return true;
                }
            }
            return false;
        }
        if (!node.isObject())
        {
            return false;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
        while (fields.hasNext())
        {
            Map.Entry<String, JsonNode> field = fields.next();
            String fieldName = field.getKey();
            JsonNode value = field.getValue();
            if (FORM_ID_FIELD.equalsIgnoreCase(fieldName)
                    && jsonValueReferencesFormId(value, targetFormIds))
            {
                return true;
            }
            if (FORM_KEY_FIELD.equalsIgnoreCase(fieldName)
                    && jsonValueReferencesFormKey(value, targetFormIds))
            {
                return true;
            }
            if ((value.isObject() || value.isArray())
                    && metadataNodeReferencesForm(value, targetFormIds))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断 JSON 值是否包含目标表单主键。
     * @param value JsonNode，formId 字段值
     * @param targetFormIds Set&lt;Long&gt;，待检查表单主键集合
     * @return boolean，值可解析且命中目标表单时返回 true
     */
    private boolean jsonValueReferencesFormId(JsonNode value, Set<Long> targetFormIds)
    {
        if (value.isArray())
        {
            for (JsonNode child : value)
            {
                if (jsonValueReferencesFormId(child, targetFormIds))
                {
                    return true;
                }
            }
            return false;
        }
        if (value.isIntegralNumber())
        {
            return targetFormIds.contains(value.longValue());
        }
        if (value.isTextual())
        {
            return formKeyReferencesForm(value.textValue(), targetFormIds);
        }
        return false;
    }

    /**
     * 判断 JSON 值是否包含目标表单键。
     * @param value JsonNode，formKey 字段值
     * @param targetFormIds Set&lt;Long&gt;，待检查表单主键集合
     * @return boolean，值命中目标表单键时返回 true
     */
    private boolean jsonValueReferencesFormKey(JsonNode value, Set<Long> targetFormIds)
    {
        if (value.isArray())
        {
            for (JsonNode child : value)
            {
                if (jsonValueReferencesFormKey(child, targetFormIds))
                {
                    return true;
                }
            }
            return false;
        }
        return value.isTextual() && formKeyReferencesForm(value.textValue(), targetFormIds);
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
     * 判断表单键是否命中目标表单，兼容纯数字和旧 key_&lt;formId&gt; 格式。
     * @param formKey String，模型元数据或 BPMN 中的表单键
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
            if (normalized.equals(formId.toString())
                    || normalized.equals(LEGACY_FORM_KEY_PREFIX + formId))
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
