package com.ruoyi.flowable.extension;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.service.process.WorkflowCollaborationOutboxService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * MessageFlow 源端 SendTask 的固定处理器，只在 Flowable 事务内登记 outbox，不直接执行网络调用。
 */
@Component
public class WorkflowCollaborationOutboxHandler implements WorkflowJavaExtensionHandler
{
    /** 目标 ApprovaPlat 实例的正式协作消息入口。 */
    private static final String COLLABORATION_MESSAGE_PATH =
            "/workflow/runtime-event/collaboration/message";
    public static final String IMPLEMENTATION_KEY = "COLLABORATION_OUTBOX_V1";
    public static final String EXTENSION_KEY = "approva.collaboration-outbox";
    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,254}");
    private static final Pattern VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Set<String> AUTHOR_FIELDS = Set.of("endpointKey", "path", "messageName",
            "targetProcessDefinitionKey", "correlationVariable", "variableNames", "maxAttempts");
    private static final Set<String> FROZEN_FIELDS = Set.of("endpointKey", "path", "messageName",
            "targetProcessDefinitionKey", "correlationVariable", "variableNames", "maxAttempts",
            "httpEndpoint");
    private static final String CONFIG_SCHEMA = "{\"type\":\"object\",\"additionalProperties\":false,"
            + "\"required\":[\"endpointKey\",\"path\",\"messageName\",\"targetProcessDefinitionKey\",\"variableNames\",\"maxAttempts\"],"
            + "\"properties\":{\"endpointKey\":{\"type\":\"string\"},\"path\":{\"type\":\"string\"},"
            + "\"messageName\":{\"type\":\"string\"},\"targetProcessDefinitionKey\":{\"type\":\"string\"},"
            + "\"correlationVariable\":{\"type\":\"string\"},\"variableNames\":{\"type\":\"array\",\"maxItems\":128,\"items\":{\"type\":\"string\"}},"
            + "\"maxAttempts\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20}}}";

    private final WorkflowHttpConnector httpConnector;
    private final WorkflowCollaborationOutboxService outboxService;
    private final ObjectMapper objectMapper = JsonMapper.shared();

    /**
     * 创建协作 outbox 处理器。
     * @param httpConnector WorkflowHttpConnector，复用正式端点冻结和跨系统认证
     * @param outboxService WorkflowCollaborationOutboxService，事务 outbox 领域服务
     * @return void，构造后由 Spring 注册到固定 Java 处理器目录
     */
    public WorkflowCollaborationOutboxHandler(WorkflowHttpConnector httpConnector,
            WorkflowCollaborationOutboxService outboxService)
    {
        this.httpConnector = httpConnector;
        this.outboxService = outboxService;
    }

    /** @return String，固定实现键 COLLABORATION_OUTBOX_V1。 */
    @Override
    public String implementationKey()
    {
        return IMPLEMENTATION_KEY;
    }

    /** @return String，设计器用户可见名称。 */
    @Override
    public String displayName()
    {
        return "跨参与方可靠消息";
    }

    /** @return String，作者配置 JSON Schema。 */
    @Override
    public String configSchema()
    {
        return CONFIG_SCHEMA;
    }

    /**
     * 部署时校验作者配置并冻结 HTTP 端点修订，密钥正文不会进入 BPMN 或数据库。
     * @param config JsonNode，设计器保存的作者配置
     * @return String，含不变端点快照的规范配置
     */
    public String freezeConfig(JsonNode config)
    {
        CollaborationConfig author = parse(config, false);
        try
        {
            JsonNode endpoint = objectMapper.readTree(httpConnector.freezePostEndpoint(
                    author.endpointKey(), author.path()));
            ObjectNode normalized = normalizedAuthor(author);
            normalized.set("httpEndpoint", endpoint);
            return WorkflowExtensionJsonCanonicalizer.canonicalize(
                    objectMapper.writeValueAsString(normalized));
        }
        catch (JacksonException exception)
        {
            throw new ServiceException("协作消息端点快照无法序列化", HttpStatus.ERROR);
        }
    }

    /**
     * 运行前复核协作字段、变量白名单和冻结 HTTP 端点摘要。
     * @param config JsonNode，部署快照配置
     * @return String，可与部署快照逐字比较的规范 JSON
     */
    @Override
    public String validateAndNormalizeConfig(JsonNode config)
    {
        CollaborationConfig frozen = parse(config, true);
        JsonNode endpoint = config.get("httpEndpoint");
        String normalizedEndpoint = httpConnector.validateFrozenConfig(endpoint);
        try
        {
            ObjectNode normalized = normalizedAuthor(frozen);
            normalized.set("httpEndpoint", objectMapper.readTree(normalizedEndpoint));
            return WorkflowExtensionJsonCanonicalizer.canonicalize(
                    objectMapper.writeValueAsString(normalized));
        }
        catch (JacksonException exception)
        {
            throw new ServiceException("协作消息冻结配置无法规范化", HttpStatus.ERROR);
        }
    }

    /**
     * 在当前 Flowable 事务中登记唯一 outbox，网络投递由提交后的后台 worker 执行。
     * @param execution DelegateExecution，当前 SendTask 执行上下文
     * @param config JsonNode，已经通过摘要复核的冻结配置
     * @return void，登记失败时回滚整个 Flowable 命令
     */
    @Override
    public void execute(DelegateExecution execution, JsonNode config)
    {
        outboxService.enqueue(execution, config);
    }

    /**
     * 校验作者或冻结配置并生成稳定值对象。
     * @param config JsonNode，待校验 JSON 对象
     * @param frozen boolean，是否要求 httpEndpoint 冻结字段
     * @return CollaborationConfig，规范业务配置
     */
    private CollaborationConfig parse(JsonNode config, boolean frozen)
    {
        if (config == null || !config.isObject())
        {
            throw invalid("协作消息配置必须是 JSON 对象");
        }
        Set<String> allowed = frozen ? FROZEN_FIELDS : AUTHOR_FIELDS;
        for (String name : config.propertyNames())
        {
            if (!allowed.contains(name)) throw invalid("协作消息配置包含未知字段: " + name);
        }
        String endpointKey = text(config, "endpointKey", 128);
        String path = text(config, "path", 512);
        String messageName = text(config, "messageName", 255);
        String targetKey = text(config, "targetProcessDefinitionKey", 255);
        String correlationVariable = optionalText(config, "correlationVariable", 128);
        if (!KEY.matcher(endpointKey).matches() || !KEY.matcher(messageName).matches()
                || !KEY.matcher(targetKey).matches()
                || !path.startsWith("/") || path.contains("..") || path.contains("//"))
        {
            throw invalid("协作消息端点、路径、消息名或目标流程 key 不合法");
        }
        if (!COLLABORATION_MESSAGE_PATH.equals(path))
        {
            throw invalid("协作消息必须投递到正式 collaboration/message 入口");
        }
        if (correlationVariable != null && !VARIABLE.matcher(correlationVariable).matches())
        {
            throw invalid("协作消息关联变量名不合法");
        }
        JsonNode variablesNode = config.get("variableNames");
        if (variablesNode == null || !variablesNode.isArray() || variablesNode.size() > 128)
        {
            throw invalid("协作消息变量白名单必须是最多 128 项的数组");
        }
        List<String> variableNames = new ArrayList<>();
        for (JsonNode variable : variablesNode)
        {
            String name = variable.isTextual() ? variable.textValue().trim() : "";
            if (!VARIABLE.matcher(name).matches() || variableNames.contains(name))
            {
                throw invalid("协作消息变量白名单包含非法或重复名称");
            }
            variableNames.add(name);
        }
        variableNames.sort(String::compareTo);
        JsonNode attemptsNode = config.get("maxAttempts");
        int maxAttempts = attemptsNode != null && attemptsNode.canConvertToInt()
                ? attemptsNode.intValue() : 0;
        if (maxAttempts < 1 || maxAttempts > 20)
        {
            throw invalid("协作消息最大投递次数必须处于 1 至 20");
        }
        if (frozen && (config.get("httpEndpoint") == null
                || !config.get("httpEndpoint").isObject()))
        {
            throw new ServiceException("协作消息 HTTP 端点部署快照不存在", HttpStatus.ERROR);
        }
        return new CollaborationConfig(endpointKey, path, messageName, targetKey,
                correlationVariable, List.copyOf(variableNames), maxAttempts);
    }

    /**
     * 按固定字段顺序生成规范作者配置。
     * @param config CollaborationConfig，已校验业务配置
     * @return ObjectNode，字段顺序稳定的 JSON 对象
     */
    private ObjectNode normalizedAuthor(CollaborationConfig config)
    {
        ObjectNode normalized = objectMapper.createObjectNode();
        normalized.put("endpointKey", config.endpointKey());
        normalized.put("path", config.path());
        normalized.put("messageName", config.messageName());
        normalized.put("targetProcessDefinitionKey", config.targetProcessDefinitionKey());
        if (config.correlationVariable() != null)
        {
            normalized.put("correlationVariable", config.correlationVariable());
        }
        ArrayNode variables = normalized.putArray("variableNames");
        config.variableNames().forEach(variables::add);
        normalized.put("maxAttempts", config.maxAttempts());
        return normalized;
    }

    /**
     * 读取必填文本字段。
     * @param config JsonNode，配置对象
     * @param name String，字段名
     * @param max int，最大长度
     * @return String，规范非空文本
     */
    private String text(JsonNode config, String name, int max)
    {
        String value = optionalText(config, name, max);
        if (value == null) throw invalid("协作消息配置字段缺失: " + name);
        return value;
    }

    /**
     * 读取可选文本并拒绝控制字符。
     * @param config JsonNode，配置对象
     * @param name String，字段名
     * @param max int，最大长度
     * @return String，规范文本或 null
     */
    private String optionalText(JsonNode config, String name, int max)
    {
        JsonNode node = config.get(name);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw invalid("协作消息配置字段类型错误: " + name);
        String value = node.textValue().trim();
        if (value.isEmpty() || value.length() > max
                || value.chars().anyMatch(Character::isISOControl))
        {
            throw invalid("协作消息配置字段内容不合法: " + name);
        }
        return value;
    }

    /**
     * 创建稳定的作者配置异常。
     * @param message String，具体约束
     * @return ServiceException，HTTP 400
     */
    private ServiceException invalid(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST)
                .setSubCode("COLLAB_OUTBOX_CONFIG_INVALID");
    }

    /** 作者与冻结配置共用的规范业务字段。 */
    private record CollaborationConfig(String endpointKey, String path, String messageName,
            String targetProcessDefinitionKey, String correlationVariable,
            List<String> variableNames, int maxAttempts)
    {
    }
}
