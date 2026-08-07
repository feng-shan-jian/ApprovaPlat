package com.ruoyi.flowable.service.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;
import com.ruoyi.flowable.domain.vo.WorkflowExtensionOptionView;
import com.ruoyi.flowable.extension.WorkflowExtensionChecksum;
import com.ruoyi.flowable.extension.WorkflowFormFieldExtension;

/**
 * 自定义表单字段目录解析与部署版本冻结服务。
 */
@Service
public class WorkflowFormFieldExtensionService
{
    /** 表单快照中允许遍历的最大 JSON 节点数。 */
    private static final int MAX_JSON_NODES = 10000;

    private final WorkflowExtensionRegistryService registryService;
    private final ObjectMapper objectMapper = JsonMapper.shared();

    /**
     * 创建自定义表单字段服务。
     * @param registryService WorkflowExtensionRegistryService，正式扩展目录与版本服务
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowFormFieldExtensionService(WorkflowExtensionRegistryService registryService)
    {
        this.registryService = registryService;
    }

    /**
     * 解析设计阶段字段目录最新版，保存和显式校验均通过正式数据库完成。
     * @param extensionKey String，BPMN custom: 类型中的扩展稳定键
     * @return WorkflowExtensionOptionView，已校验的 FORM_FIELD 最新版
     */
    public WorkflowExtensionOptionView resolveForAuthor(String extensionKey)
    {
        return WorkflowFormFieldExtension.requireInstalled(registryService.requireLatest(
                extensionKey, WorkflowExtensionRegistryService.FORM_FIELD_TYPE));
    }

    /**
     * 部署事务内锁定表单字段精确版本，并重新验证类型和服务端安装状态。
     * @param extensionKey String，内嵌表单组件保存的扩展稳定键
     * @return WorkflowExtensionOptionView，可固化到部署表单快照的精确版本
     */
    public WorkflowExtensionOptionView lockForDeployment(String extensionKey)
    {
        WorkflowExtensionOptionView option = registryService.lockLatestForDeployment(extensionKey);
        if (!WorkflowExtensionRegistryService.FORM_FIELD_TYPE.equals(option.extensionType()))
        {
            throw new ServiceException("自定义表单字段引用了其他扩展类型", HttpStatus.CONFLICT);
        }
        return WorkflowFormFieldExtension.requireInstalled(option);
    }

    /**
     * 把内嵌表单中的自定义字段重新锁定到部署时精确版本。
     * @param content String，保存阶段生成且已通过表单验证的 JSON
     * @return String，包含精确版本、实现键和校验和的不可变部署内容
     */
    public String freezeEmbeddedContent(String content)
    {
        return freeze(content, null, null, false).content();
    }

    /**
     * 冻结内嵌表单正文并生成统一的字段级部署扩展快照。
     * @param content String，保存阶段生成且已通过表单验证的 JSON
     * @param processKey String，表单节点所属可执行流程标识
     * @param nodeKey String，表单所在开始事件或用户任务标识
     * @return WorkflowFrozenFormContent，冻结正文及可写入统一部署台账的精确版本快照
     */
    public WorkflowFrozenFormContent freezeEmbeddedContentWithSnapshots(String content,
            String processKey, String nodeKey)
    {
        String normalizedProcessKey = requireText(processKey, "自定义表单字段所属流程标识不能为空");
        String normalizedNodeKey = requireText(nodeKey, "自定义表单字段所属节点标识不能为空");
        return freeze(content, normalizedProcessKey, normalizedNodeKey, true);
    }

    /**
     * 遍历表单协议，覆盖作者版本元数据，并按需生成字段级部署快照。
     * @param content String，待冻结的正式表单 JSON
     * @param processKey String，流程标识；仅冻结正文时允许为空
     * @param nodeKey String，表单节点标识；仅冻结正文时允许为空
     * @param collectSnapshots boolean，是否生成统一扩展部署快照
     * @return WorkflowFrozenFormContent，冻结正文与字段级快照
     */
    private WorkflowFrozenFormContent freeze(String content, String processKey,
            String nodeKey, boolean collectSnapshots)
    {
        ObjectNode root = parseObject(content);
        Deque<FormJsonNode> pending = new ArrayDeque<>();
        pending.push(new FormJsonNode(root, null));
        List<WfDeployExtensionSnapshot> snapshots = new ArrayList<>();
        Set<String> elementIds = new HashSet<>();
        int visited = 0;
        while (!pending.isEmpty())
        {
            FormJsonNode current = pending.pop();
            JsonNode node = current.node();
            visited++;
            if (visited > MAX_JSON_NODES)
            {
                throw new ServiceException("内嵌表单节点数量过多", HttpStatus.BAD_REQUEST);
            }
            if (node.isArray())
            {
                node.forEach(child -> pending.push(new FormJsonNode(child, current.fieldId())));
                continue;
            }
            if (!node.isObject())
            {
                continue;
            }
            ObjectNode object = (ObjectNode) node;
            String fieldId = current.fieldId();
            JsonNode modelNode = object.get("__vModel__");
            if (modelNode != null)
            {
                if (!modelNode.isTextual() || modelNode.textValue().isBlank())
                {
                    throw new ServiceException("自定义表单字段变量标识不合法", HttpStatus.BAD_REQUEST);
                }
                fieldId = modelNode.textValue();
            }
            JsonNode extensionKeyNode = object.get(WorkflowFormFieldExtension.EXTENSION_KEY_FIELD);
            if (extensionKeyNode != null)
            {
                if (!extensionKeyNode.isTextual() || extensionKeyNode.textValue().isBlank())
                {
                    throw new ServiceException("自定义表单字段扩展标识不合法", HttpStatus.BAD_REQUEST);
                }
                WorkflowExtensionOptionView option = lockForDeployment(extensionKeyNode.textValue());
                // 覆盖作者阶段元数据，确保部署快照只反映事务内锁定的精确版本。
                object.put(WorkflowFormFieldExtension.EXTENSION_KEY_FIELD, option.extensionKey());
                object.put(WorkflowFormFieldExtension.VERSION_FIELD, option.versionNo());
                object.put(WorkflowFormFieldExtension.IMPLEMENTATION_FIELD, option.implementationKey());
                object.put(WorkflowFormFieldExtension.CHECKSUM_FIELD, option.checksum());
                if (collectSnapshots)
                {
                    String normalizedFieldId = requireText(fieldId,
                            "自定义表单字段缺少变量标识");
                    WfDeployExtensionSnapshot snapshot = formFieldSnapshot(processKey,
                            nodeKey, normalizedFieldId, option);
                    if (!elementIds.add(snapshot.getElementId()))
                    {
                        throw new ServiceException("自定义表单字段部署标识重复", HttpStatus.CONFLICT);
                    }
                    snapshots.add(snapshot);
                }
            }
            String inheritedFieldId = fieldId;
            object.properties().forEach(entry -> pending.push(
                    new FormJsonNode(entry.getValue(), inheritedFieldId)));
        }
        try
        {
            return new WorkflowFrozenFormContent(objectMapper.writeValueAsString(root), snapshots);
        }
        catch (JacksonException exception)
        {
            throw new ServiceException("自定义表单字段部署快照序列化失败", HttpStatus.ERROR);
        }
    }

    /**
     * 把一个已锁定字段版本转换为统一扩展部署快照。
     * @param processKey String，BPMN 可执行流程标识
     * @param nodeKey String，表单所在 BPMN 节点标识
     * @param fieldId String，正式表单变量标识
     * @param option WorkflowExtensionOptionView，已锁定且复核安装状态的精确版本
     * @return WfDeployExtensionSnapshot，尚未绑定 deploymentId 和操作人的字段快照
     */
    private WfDeployExtensionSnapshot formFieldSnapshot(String processKey, String nodeKey,
            String fieldId, WorkflowExtensionOptionView option)
    {
        String readableElementId = nodeKey + "#form#" + fieldId;
        String elementId = readableElementId.length() <= 255 ? readableElementId
                : "form#" + WorkflowExtensionChecksum.sha256(processKey, nodeKey, fieldId);
        WfDeployExtensionSnapshot snapshot = new WfDeployExtensionSnapshot();
        snapshot.setProcessKey(processKey);
        snapshot.setElementId(elementId);
        snapshot.setExtensionKey(option.extensionKey());
        snapshot.setExtensionVersionId(option.versionId());
        snapshot.setVersionNo(option.versionNo());
        snapshot.setExtensionType(option.extensionType());
        snapshot.setImplementationKey(option.implementationKey());
        snapshot.setConfigJson("{}");
        snapshot.setVersionChecksum(option.checksum());
        return snapshot;
    }

    /**
     * 规范化部署快照所需的稳定标识。
     * @param value String，待检查的流程、节点或字段标识
     * @param message String，校验失败时返回的业务提示
     * @return String，去除首尾空白后的非空标识
     */
    private String requireText(String value, String message)
    {
        if (value == null || value.isBlank())
        {
            throw new ServiceException(message, HttpStatus.BAD_REQUEST);
        }
        return value.trim();
    }

    /**
     * 严格解析表单 JSON 根对象。
     * @param content String，待冻结的内嵌表单 JSON
     * @return ObjectNode，可安全更新版本元数据的根对象
     */
    private ObjectNode parseObject(String content)
    {
        try
        {
            JsonNode root = objectMapper.readTree(content);
            if (root == null || !root.isObject())
            {
                throw new ServiceException("内嵌表单内容必须是 JSON 对象", HttpStatus.BAD_REQUEST);
            }
            return (ObjectNode) root;
        }
        catch (JacksonException exception)
        {
            throw new ServiceException("内嵌表单内容不是合法 JSON", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * JSON 遍历节点及其所属表单字段变量上下文。
     * @param node JsonNode，当前待处理节点
     * @param fieldId String，最近外层组件的 {@code __vModel__}，未知时为空
     */
    private record FormJsonNode(JsonNode node, String fieldId)
    {
    }
}
