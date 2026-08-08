package com.ruoyi.flowable.service.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;

/**
 * 将 BPMN 节点字段权限编译进不可变部署表单快照。
 */
public final class WorkflowNodeFormPermissionSnapshot
{
    /** 表单组件业务变量字段名。 */
    private static final String VARIABLE_FIELD = "__vModel__";

    /** 表单组件配置字段名。 */
    private static final String CONFIG_FIELD = "__config__";

    /** 嵌套布局子字段名。 */
    private static final String CHILDREN_FIELD = "children";

    private WorkflowNodeFormPermissionSnapshot()
    {
    }

    /**
     * 把作者模型中的节点权限应用到本次部署读取的正式表单正文。
     *
     * @param content String，当前部署事务读取并已校验的正式表单 JSON
     * @param reference WorkflowBpmnFormReference，包含节点默认策略和字段覆盖策略的 BPMN 引用
     * @param templateValidator WorkflowFormTemplateValidator，正式表单结构与安全验证器
     * @return String，只属于当前部署和当前节点的不可变权限表单 JSON
     */
    public static String apply(String content, WorkflowBpmnFormReference reference,
            WorkflowFormTemplateValidator templateValidator)
    {
        if (reference == null || templateValidator == null)
        {
            throw dataError("节点表单权限快照参数不完整", null);
        }
        boolean explicitPolicy = reference.defaultPermission() != null
                || !reference.fieldPermissions().isEmpty();
        if (!explicitPolicy)
        {
            // 调用方已在同一部署事务校验正式模板；旧模型无权限描述时直接保留原语义。
            return content;
        }
        try
        {
            JsonNode parsed = JsonMapper.shared().readTree(content);
            if (!(parsed instanceof ObjectNode root))
            {
                throw dataError("节点表单权限快照根结构异常", null);
            }
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
            for (Map.Entry<String, ObjectNode> entry : fields.entrySet())
            {
                WorkflowFormFieldPermissionMode mode = reference.fieldPermissions()
                        .getOrDefault(entry.getKey(), reference.defaultPermission());
                if (mode == null)
                {
                    throw invalid("节点字段权限配置不完整: " + entry.getKey());
                }
                applyMode(entry.getValue(), mode);
            }
            String frozen = JsonMapper.shared().writeValueAsString(root);
            templateValidator.validate(frozen);
            return frozen;
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
            if (!(componentNode instanceof ObjectNode component)
                    || !(component.path(CONFIG_FIELD) instanceof ObjectNode config))
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
            JsonNode children = config.get(CHILDREN_FIELD);
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
        ObjectNode config = (ObjectNode) component.path(CONFIG_FIELD);
        boolean readable = mode != WorkflowFormFieldPermissionMode.HIDDEN;
        boolean writable = mode == WorkflowFormFieldPermissionMode.EDITABLE
                || mode == WorkflowFormFieldPermissionMode.REQUIRED;
        boolean required = mode == WorkflowFormFieldPermissionMode.REQUIRED;
        config.put("workflowHidden", mode == WorkflowFormFieldPermissionMode.HIDDEN);
        config.put("workflowReadable", readable);
        config.put("workflowWritable", writable);
        config.put("required", required);
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
