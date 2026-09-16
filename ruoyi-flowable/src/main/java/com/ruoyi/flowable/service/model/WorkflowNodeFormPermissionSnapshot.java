package com.ruoyi.flowable.service.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 将 BPMN 节点字段权限编译进不可变部署表单快照。
 */
public final class WorkflowNodeFormPermissionSnapshot
{
    /** 表单组件业务变量字段名。 */
    private static final String VARIABLE_FIELD = "vModel";

    /** 嵌套布局子字段名。 */
    private static final String CHILDREN_FIELD = "children";

    private WorkflowNodeFormPermissionSnapshot()
    {
    }

    /**
     * 把作者模型中的节点权限应用到本次部署读取的正式表单正文。
     *
     * @param template JsonNode，本次请求已解释的正式模板根节点
     * @param reference WorkflowBpmnFormReference，包含节点默认策略和字段覆盖策略的 BPMN 引用
     * @return JsonNode，只属于当前节点的权限投影
     */
    public static JsonNode apply(JsonNode template, WorkflowBpmnFormReference reference)
    {
        if (reference.defaultPermission() == null)
        {
            throw invalid("绑定正式模板的节点必须配置批量默认字段权限");
        }
        try
        {
            ObjectNode root = (ObjectNode) template.deepCopy();
            LinkedHashMap<String, ObjectNode> fields = new LinkedHashMap<>();
            collectFields(root.path("fields"), fields);
            Set<String> staleFields = new java.util.LinkedHashSet<>(
                    reference.fieldPermissions().keySet());
            staleFields.removeAll(fields.keySet());
            if (!staleFields.isEmpty())
            {
                throw invalid("节点字段权限引用了当前正式表单不存在的字段: "
                        + staleFields.iterator().next());
            }
            Set<String> missingFields = new java.util.LinkedHashSet<>(fields.keySet());
            missingFields.removeAll(reference.fieldPermissions().keySet());
            if (!missingFields.isEmpty())
            {
                throw invalid("节点字段权限配置不完整: " + missingFields.iterator().next());
            }
            for (Map.Entry<String, ObjectNode> entry : fields.entrySet())
            {
                WorkflowFormFieldPermissionMode mode = reference.fieldPermissions().get(entry.getKey());
                applyMode(entry.getValue(), mode);
            }
            return root;
        }
        catch (ServiceException exception)
        {
            throw exception;
        }
        catch (RuntimeException exception)
        {
            throw dataError("节点表单权限快照编译失败", exception);
        }
    }

    /**
     * 按模板顺序收集所有业务字段，布局容器本身不会成为权限字段。
     *
     * @param components JsonNode，当前层 fields 或 children 数组
     * @param fields Map&lt;String,ObjectNode&gt;，按变量名保存的字段配置
     * @return void，重复字段或结构损坏时抛出数据异常
     */
    private static void collectFields(JsonNode components, Map<String, ObjectNode> fields)
    {
        for (JsonNode componentNode : components)
        {
            if (!(componentNode instanceof ObjectNode component))
            {
                throw dataError("节点表单字段结构异常", null);
            }
            JsonNode variableNode = component.get(VARIABLE_FIELD);
            if (variableNode != null && variableNode.isTextual())
            {
                String variable = variableNode.textValue().trim();
                if (fields.putIfAbsent(variable, component) != null)
                {
                    throw dataError("节点表单字段重复", null);
                }
            }
            JsonNode children = component.get(CHILDREN_FIELD);
            if (children != null && children.isArray() && !children.isEmpty())
            {
                collectFields(children, fields);
            }
        }
    }

    /**
     * 将单个节点权限模式固化为渲染、回显和服务端变量校验共用的字段元数据。
     *
     * @param component ObjectNode，当前正式表单字段节点
     * @param mode WorkflowFormFieldPermissionMode，隐藏、只读、可编辑或必填
     * @return void，字段配置被原地更新到本次部署副本
     */
    private static void applyMode(ObjectNode component, WorkflowFormFieldPermissionMode mode)
    {
        if (component.path("workflowSystemWritable").asBoolean(false)
                && mode != WorkflowFormFieldPermissionMode.READONLY && mode != WorkflowFormFieldPermissionMode.HIDDEN)
            throw dataError("系统结果字段的节点权限必须为只读或隐藏: " + component.path("vModel").asText(""), null);
        if (mode == WorkflowFormFieldPermissionMode.HIDDEN && component.has("workflowSystemWritable"))
            component.put("workflowSystemWritable", false);
        boolean readable = mode != WorkflowFormFieldPermissionMode.HIDDEN;
        boolean writable = mode == WorkflowFormFieldPermissionMode.EDITABLE
                || mode == WorkflowFormFieldPermissionMode.REQUIRED;
        boolean required = mode == WorkflowFormFieldPermissionMode.REQUIRED;
        component.put("workflowHidden", mode == WorkflowFormFieldPermissionMode.HIDDEN);
        component.put("workflowReadable", readable);
        component.put("workflowWritable", writable);
        component.put("required", required);
        // disabled 只承担页面表现；真正的写权限仍由 workflowWritable 在服务端强制执行。
        component.put("disabled", !writable);
    }

    /**
     * 创建面向设计者的稳定权限参数异常。
     *
     * @param message String，不包含表单正文的业务提示
     * @return ServiceException，HTTP 400 参数异常
     */
    private static ServiceException invalid(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 创建部署快照数据异常并保留内部原因链。
     *
     * @param message String，稳定中文错误提示
     * @param cause Throwable，底层 JSON 或结构异常，允许为空
     * @return ServiceException，HTTP 500 数据一致性异常
     */
    private static ServiceException dataError(String message, Throwable cause)
    {
        ServiceException exception = new ServiceException(message, HttpStatus.ERROR);
        if (cause != null)
        {
            exception.initCause(cause);
        }
        return exception;
    }
}
