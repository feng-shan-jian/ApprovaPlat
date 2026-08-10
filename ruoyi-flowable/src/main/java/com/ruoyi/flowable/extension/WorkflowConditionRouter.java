package com.ruoyi.flowable.extension;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.flowable.domain.WfDeployConditionRule;
import com.ruoyi.flowable.mapper.WfDeployConditionRuleMapper;
import com.ruoyi.flowable.service.model.WorkflowConditionDeploymentService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Flowable 条件表达式唯一固定入口，按部署快照一次性计算网关全部分支。
 */
@Component("workflowConditionRouter")
public class WorkflowConditionRouter
{
    /** 编译表达式只允许传递固定长度小写十六进制令牌。 */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[0-9a-f]{24}");
    /** 同一执行内的 transient 缓存前缀，不持久化为业务流程变量。 */
    private static final String CACHE_PREFIX = "__ruoyi_workflow_condition_";

    private final RepositoryService repositoryService;
    private final WfDeployConditionRuleMapper ruleMapper;
    private final WorkflowCelSandbox celSandbox = new WorkflowCelSandbox();
    private final ObjectMapper objectMapper = JsonMapper.shared();

    /**
     * 创建固定条件路由器。
     * @param repositoryService RepositoryService，定义到部署主键的可信查询 API
     * @param ruleMapper WfDeployConditionRuleMapper，运行时不可变快照数据访问层
     * @return 无返回值，构造后以固定 Bean 名注册
     */
    public WorkflowConditionRouter(RepositoryService repositoryService,
            WfDeployConditionRuleMapper ruleMapper)
    {
        this.repositoryService = repositoryService;
        this.ruleMapper = ruleMapper;
    }

    /**
     * 判断当前固定分支是否命中；首次调用会计算并校验同网关全部分支。
     * @param execution DelegateExecution，Flowable 当前网关执行上下文
     * @param gatewayToken String，部署编译生成的网关摘要令牌
     * @param flowToken String，部署编译生成的分支摘要令牌
     * @return boolean，当前分支在完整计算结果中命中时返回 true
     */
    public boolean matches(DelegateExecution execution, String gatewayToken, String flowToken)
    {
        requireToken(gatewayToken, "条件网关令牌不合法");
        requireToken(flowToken, "条件分支令牌不合法");
        String cacheKey = CACHE_PREFIX + gatewayToken;
        Object cached = execution.getTransientVariable(cacheKey);
        Set<String> matchedTokens;
        if (cached == null)
        {
            matchedTokens = evaluateGateway(execution, gatewayToken);
            // transient 结果只覆盖本次引擎命令，不污染正式业务变量和历史详情。
            execution.setTransientVariable(cacheKey, matchedTokens);
        }
        else if (cached instanceof Set<?> cachedSet
                && cachedSet.stream().allMatch(String.class::isInstance))
        {
            matchedTokens = cachedSet.stream().map(String.class::cast)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        else
        {
            throw new WorkflowConditionRoutingException("条件路由运行缓存类型异常", HttpStatus.ERROR);
        }
        return matchedTokens.contains(flowToken);
    }

    /**
     * 从真实部署快照计算网关全部非默认分支并执行排他歧义策略。
     * @param execution DelegateExecution，当前执行上下文
     * @param gatewayToken String，固定网关令牌
     * @return Set&lt;String&gt;，命中的非默认分支令牌集合
     */
    private Set<String> evaluateGateway(DelegateExecution execution, String gatewayToken)
    {
        ProcessDefinition definition = repositoryService
                .getProcessDefinition(execution.getProcessDefinitionId());
        if (definition == null || definition.getDeploymentId() == null
                || definition.getKey() == null)
        {
            throw new WorkflowConditionRoutingException("条件路由对应的流程定义不存在", HttpStatus.ERROR);
        }
        List<WfDeployConditionRule> snapshots = ruleMapper.selectRuntimeGateway(
                definition.getDeploymentId(), definition.getKey(), gatewayToken);
        if (snapshots == null || snapshots.size() < 2)
        {
            throw new WorkflowConditionRoutingException("条件路由部署快照不存在或不完整", HttpStatus.ERROR);
        }
        int defaultCount = 0;
        String gatewayType = null;
        Set<String> flowTokens = new HashSet<>();
        Set<String> matched = new LinkedHashSet<>();
        for (WfDeployConditionRule snapshot : snapshots)
        {
            if (!WorkflowConditionDeploymentService.snapshotChecksum(snapshot)
                    .equals(snapshot.getSnapshotChecksum()))
            {
                throw new WorkflowConditionRoutingException("条件路由部署快照校验和不一致", HttpStatus.ERROR);
            }
            if (!gatewayToken.equals(snapshot.getGatewayToken())
                    || !flowTokens.add(snapshot.getFlowToken()))
            {
                throw new WorkflowConditionRoutingException("条件路由部署快照关联异常", HttpStatus.ERROR);
            }
            gatewayType = gatewayType == null ? snapshot.getGatewayType() : gatewayType;
            if (!gatewayType.equals(snapshot.getGatewayType()))
            {
                throw new WorkflowConditionRoutingException("条件网关类型快照不一致", HttpStatus.ERROR);
            }
            if (Boolean.TRUE.equals(snapshot.getDefaultFlow()))
            {
                defaultCount++;
                continue;
            }
            if (evaluateBranch(execution, snapshot))
            {
                matched.add(snapshot.getFlowToken());
            }
        }
        if (defaultCount != 1 || !("EXCLUSIVE".equals(gatewayType)
                || "INCLUSIVE".equals(gatewayType)))
        {
            throw new WorkflowConditionRoutingException("条件网关默认策略快照异常", HttpStatus.ERROR);
        }
        if ("EXCLUSIVE".equals(gatewayType) && matched.size() > 1)
        {
            // Flowable 排他网关默认选择第一条命中线；平台拒绝这种顺序依赖，事务整体回滚。
            throw new WorkflowConditionRoutingException("排他网关有多个条件同时命中，请联系流程设计者修正规则",
                    HttpStatus.CONFLICT);
        }
        return Set.copyOf(matched);
    }

    /**
     * 精确执行单条分支的原子类型比较，再由 CEL 组合 AND/OR 结果。
     * @param execution DelegateExecution，真实流程变量来源
     * @param snapshot WfDeployConditionRule，已复核完整性的分支快照
     * @return boolean，分支规则命中结果
     */
    private boolean evaluateBranch(DelegateExecution execution,
            WfDeployConditionRule snapshot)
    {
        try
        {
            JsonNode root = objectMapper.readTree(snapshot.getRuleJson());
            Map<String, Object> activation = new LinkedHashMap<>();
            int ruleIndex = 0;
            for (JsonNode group : root.path("groups"))
            {
                for (JsonNode rule : group.path("rules"))
                {
                    String fieldName = rule.path("field").asText();
                    if (!execution.hasVariable(fieldName))
                    {
                        throw new WorkflowConditionRoutingException("条件字段在当前流程实例中不存在: " + fieldName,
                                HttpStatus.CONFLICT);
                    }
                    Object actual = execution.getVariable(fieldName);
                    activation.put("rule" + (++ruleIndex), evaluateAtomic(actual, rule, fieldName));
                }
            }
            return celSandbox.evaluateBoolean(activation, snapshot.getCelConfigJson());
        }
        catch (JacksonException exception)
        {
            WorkflowConditionRoutingException failure = new WorkflowConditionRoutingException(
                    "条件路由规则快照无法解析", HttpStatus.ERROR);
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * 按冻结字段类型执行不可注入的原子比较，数值使用 BigDecimal 精确语义。
     * @param actual Object，真实流程变量值
     * @param rule JsonNode，规范化原子规则
     * @param fieldName String，稳定错误提示使用的字段名
     * @return boolean，单条原子规则结果
     */
    private boolean evaluateAtomic(Object actual, JsonNode rule, String fieldName)
    {
        if (actual == null)
        {
            throw new WorkflowConditionRoutingException("条件字段值不能为空: " + fieldName,
                    HttpStatus.CONFLICT);
        }
        String fieldType = rule.path("fieldType").asText();
        String operator = rule.path("operator").asText();
        return switch (fieldType)
        {
            case "BOOLEAN" -> compareBoolean(actual, rule.path("value").booleanValue(),
                    operator, fieldName);
            case "NUMBER" -> compareNumber(actual, new BigDecimal(rule.path("value").asText()),
                    operator, fieldName);
            case "TEXT", "SCALAR" -> compareText(canonicalScalar(actual, fieldName),
                    rule.path("value").asText(), operator);
            default -> throw new WorkflowConditionRoutingException("条件字段类型快照不受支持",
                    HttpStatus.ERROR);
        };
    }

    /**
     * 执行布尔相等或不等比较。
     * @param actual Object，真实变量值
     * @param expected boolean，冻结条件值
     * @param operator String，EQ 或 NE
     * @param fieldName String，错误提示字段名
     * @return boolean，比较结果
     */
    private boolean compareBoolean(Object actual, boolean expected, String operator,
            String fieldName)
    {
        if (!(actual instanceof Boolean booleanValue))
        {
            throw invalidRuntimeType(fieldName);
        }
        boolean equal = booleanValue == expected;
        return "EQ".equals(operator) ? equal : "NE".equals(operator) ? !equal
                : invalidOperator();
    }

    /**
     * 使用 BigDecimal 执行精确数值比较并拒绝非有限或非数值变量。
     * @param actual Object，真实变量值
     * @param expected BigDecimal，冻结条件值
     * @param operator String，受控比较运算符
     * @param fieldName String，错误提示字段名
     * @return boolean，比较结果
     */
    private boolean compareNumber(Object actual, BigDecimal expected, String operator,
            String fieldName)
    {
        if (!(actual instanceof Number number)
                || (number instanceof Double value && !Double.isFinite(value))
                || (number instanceof Float value && !Float.isFinite(value)))
        {
            throw invalidRuntimeType(fieldName);
        }
        int compared;
        try
        {
            compared = new BigDecimal(number.toString()).compareTo(expected);
        }
        catch (NumberFormatException exception)
        {
            throw invalidRuntimeType(fieldName);
        }
        return switch (operator)
        {
            case "EQ" -> compared == 0;
            case "NE" -> compared != 0;
            case "GT" -> compared > 0;
            case "GTE" -> compared >= 0;
            case "LT" -> compared < 0;
            case "LTE" -> compared <= 0;
            default -> invalidOperator();
        };
    }

    /**
     * 执行受控文本比较，不解析正则或表达式。
     * @param actual String，规范化真实标量
     * @param expected String，冻结条件值
     * @param operator String，受控文本运算符
     * @return boolean，比较结果
     */
    private boolean compareText(String actual, String expected, String operator)
    {
        return switch (operator)
        {
            case "EQ" -> actual.equals(expected);
            case "NE" -> !actual.equals(expected);
            case "CONTAINS" -> actual.contains(expected);
            case "STARTS_WITH" -> actual.startsWith(expected);
            case "ENDS_WITH" -> actual.endsWith(expected);
            default -> invalidOperator();
        };
    }

    /**
     * 将文本、布尔或有限数值规范化为稳定标量文本。
     * @param value Object，真实流程变量
     * @param fieldName String，错误提示字段名
     * @return String，稳定标量文本
     */
    private String canonicalScalar(Object value, String fieldName)
    {
        if (value instanceof String text)
        {
            return text;
        }
        if (value instanceof Boolean booleanValue)
        {
            return Boolean.toString(booleanValue);
        }
        if (value instanceof Number number)
        {
            try
            {
                return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
            }
            catch (NumberFormatException exception)
            {
                throw invalidRuntimeType(fieldName);
            }
        }
        throw invalidRuntimeType(fieldName);
    }

    /**
     * 校验编译摘要令牌格式。
     * @param token String，表达式传入令牌
     * @param message String，失败提示
     * @return void，格式非法时抛出服务端错误
     */
    private void requireToken(String token, String message)
    {
        if (token == null || !TOKEN_PATTERN.matcher(token).matches())
        {
            throw new WorkflowConditionRoutingException(message, HttpStatus.ERROR);
        }
    }

    /** @return boolean，此函数始终抛出未知运算符异常。 */
    private boolean invalidOperator()
    {
        throw new WorkflowConditionRoutingException("条件运算符快照不受支持", HttpStatus.ERROR);
    }

    /**
     * 创建不回显客户端值的运行时类型冲突。
     * @param fieldName String，正式字段名
     * @return ServiceException，HTTP 409 类型冲突
     */
    private WorkflowConditionRoutingException invalidRuntimeType(String fieldName)
    {
        return new WorkflowConditionRoutingException("条件字段运行值类型不合法: " + fieldName,
                HttpStatus.CONFLICT);
    }
}
