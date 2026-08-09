package com.ruoyi.flowable.service.model;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExclusiveGateway;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.InclusiveGateway;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SequenceFlow;
import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployConditionRule;
import com.ruoyi.flowable.extension.WorkflowCelSandbox;
import com.ruoyi.flowable.extension.WorkflowExtensionChecksum;
import com.ruoyi.flowable.extension.WorkflowExtensionJsonCanonicalizer;
import com.ruoyi.flowable.mapper.WfDeployConditionRuleMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 条件分支作者规则、正式表单字段、CEL 逻辑和 Flowable 执行表达式的部署编译服务。
 */
@Service
public class WorkflowConditionDeploymentService
{
    /** 受控分支允许的最大规则组数量。 */
    private static final int MAX_GROUPS = 8;
    /** 单个规则组允许的最大原子规则数量。 */
    private static final int MAX_RULES_PER_GROUP = 8;
    /** 单条分支允许的原子规则总量。 */
    private static final int MAX_RULES = 32;
    /** 分支名称最大长度，与 BPMN 名称展示边界保持一致。 */
    private static final int MAX_BRANCH_NAME_LENGTH = 100;
    /** 固定路由表达式只接收服务端摘要令牌，不拼接作者标识或业务值。 */
    private static final String ROUTER_EXPRESSION =
            "${workflowConditionRouter.matches(execution,'%s','%s')}";
    /** 条件规则的顶层组合方式。 */
    private static final Set<String> COMBINATORS = Set.of("AND", "OR");
    /** 所有标量字段都允许的相等运算符。 */
    private static final Set<String> EQUALITY_OPERATORS = Set.of("EQ", "NE");
    /** 数值字段允许的精确比较运算符。 */
    private static final Set<String> NUMBER_OPERATORS =
            Set.of("EQ", "NE", "GT", "GTE", "LT", "LTE");
    /** 普通文本允许的受控字符串运算符。 */
    private static final Set<String> TEXT_OPERATORS =
            Set.of("EQ", "NE", "CONTAINS", "STARTS_WITH", "ENDS_WITH");

    private final WfDeployConditionRuleMapper ruleMapper;
    private final ObjectMapper objectMapper = JsonMapper.shared();
    /** CEL 只组合后端已经精确计算出的布尔原子结果，不接触任意 Java 对象。 */
    private final WorkflowCelSandbox celSandbox = new WorkflowCelSandbox();

    /**
     * 创建条件分支部署服务。
     * @param ruleMapper WfDeployConditionRuleMapper，条件部署快照数据访问层
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowConditionDeploymentService(WfDeployConditionRuleMapper ruleMapper)
    {
        this.ruleMapper = ruleMapper;
    }

    /**
     * 保存或预校验阶段使用正式表单字段校验全部网关出线，不产生写副作用。
     * @param authorDocument WorkflowBpmnDocument，作者 BPMN 文档
     * @param formSchemas List&lt;WorkflowControlledLoopFormSchema&gt;，本次事务固定读取的表单字段
     * @return void，非法、遗漏或冲突配置会抛出稳定 400
     */
    public void validate(WorkflowBpmnDocument authorDocument,
            List<WorkflowControlledLoopFormSchema> formSchemas)
    {
        collectBranches(authorDocument.bpmnModel(), buildProcessFieldIndex(formSchemas));
    }

    /**
     * 把受控作者规则编译为固定 Flowable 表达式并生成不可变数据库快照。
     * @param authorDocument WorkflowBpmnDocument，已通过作者门禁的 BPMN
     * @param inputBpmn byte[]，前序编译阶段输出
     * @param formSchemas List&lt;WorkflowControlledLoopFormSchema&gt;，冻结表单字段集合
     * @param actorUserId String，真实部署操作人用户主键
     * @return WorkflowPreparedConditionDeployment，执行 BPMN 与待写快照
     */
    public WorkflowPreparedConditionDeployment prepare(WorkflowBpmnDocument authorDocument,
            byte[] inputBpmn, List<WorkflowControlledLoopFormSchema> formSchemas,
            String actorUserId)
    {
        if (authorDocument == null || inputBpmn == null || inputBpmn.length == 0)
        {
            throw new ServiceException("条件分支部署输入不完整", HttpStatus.ERROR);
        }
        List<CompiledBranch> branches = collectBranches(authorDocument.bpmnModel(),
                buildProcessFieldIndex(formSchemas));
        if (branches.isEmpty())
        {
            return new WorkflowPreparedConditionDeployment(inputBpmn, List.of());
        }
        BpmnXMLConverter converter = new BpmnXMLConverter();
        BpmnModel compiledModel = converter.convertToBpmnModel(
                () -> new ByteArrayInputStream(inputBpmn), true, true);
        List<WfDeployConditionRule> snapshots = new ArrayList<>(branches.size());
        for (CompiledBranch branch : branches)
        {
            compileBranch(compiledModel, branch);
            snapshots.add(toSnapshot(branch, actorUserId));
        }
        byte[] compiled = converter.convertToXML(compiledModel);
        if (compiled == null || compiled.length == 0)
        {
            throw new ServiceException("条件分支执行资源编译失败", HttpStatus.ERROR);
        }
        return new WorkflowPreparedConditionDeployment(compiled, snapshots);
    }

    /**
     * 在 Flowable 部署成功后批量持久化条件快照。
     * @param deploymentId String，新部署主键
     * @param prepared WorkflowPreparedConditionDeployment，部署前准备结果
     * @return void，写入不完整时抛出冲突并回滚外层部署事务
     */
    public void persist(String deploymentId, WorkflowPreparedConditionDeployment prepared)
    {
        List<WfDeployConditionRule> snapshots = prepared == null
                ? List.of() : prepared.snapshots();
        snapshots.forEach(snapshot -> snapshot.setDeployId(deploymentId));
        int inserted = snapshots.isEmpty() ? 0 : ruleMapper.insertBatch(snapshots);
        if (inserted != snapshots.size())
        {
            throw new ServiceException("条件分支部署快照保存不完整", HttpStatus.CONFLICT);
        }
    }

    /**
     * 计算运行时复核使用的完整快照摘要。
     * @param snapshot WfDeployConditionRule，条件分支快照
     * @return String，64 位小写 SHA-256
     */
    public static String snapshotChecksum(WfDeployConditionRule snapshot)
    {
        // MySQL JSON 会调整对象键顺序和空白，摘要必须基于结构而非 JDBC 原始文本。
        String canonicalRuleJson = canonicalSnapshotJson(snapshot.getRuleJson());
        String canonicalCelJson = canonicalSnapshotJson(snapshot.getCelConfigJson());
        return WorkflowExtensionChecksum.sha256(snapshot.getProcessKey(), snapshot.getGatewayId(),
                snapshot.getGatewayType(), snapshot.getGatewayToken(), snapshot.getFlowId(),
                snapshot.getFlowName(), snapshot.getFlowToken(),
                Boolean.TRUE.equals(snapshot.getDefaultFlow()) ? "1" : "0",
                canonicalRuleJson, canonicalCelJson);
    }

    /**
     * 将快照 JSON 规范为数据库写入前后都一致的结构文本，并保留可空 CEL 语义。
     * @param json String，规则 JSON 或默认分支允许为空的 CEL JSON
     * @return String，递归排序后的稳定 JSON；入参为空时返回 null
     */
    private static String canonicalSnapshotJson(String json)
    {
        return json == null ? null : WorkflowExtensionJsonCanonicalizer.canonicalize(json);
    }

    /**
     * 按流程合并所有冻结节点表单的可写标量字段，并拒绝同名异构定义。
     * @param formSchemas List&lt;WorkflowControlledLoopFormSchema&gt;，节点表单字段集合
     * @return Map&lt;String,Map&lt;String,WorkflowControlledLoopFormField&gt;&gt;，流程字段索引
     */
    private Map<String, Map<String, WorkflowControlledLoopFormField>> buildProcessFieldIndex(
            List<WorkflowControlledLoopFormSchema> formSchemas)
    {
        Map<String, Map<String, WorkflowControlledLoopFormField>> mutable = new LinkedHashMap<>();
        for (WorkflowControlledLoopFormSchema schema :
                formSchemas == null ? List.<WorkflowControlledLoopFormSchema>of() : formSchemas)
        {
            Map<String, WorkflowControlledLoopFormField> fields = mutable.computeIfAbsent(
                    schema.processKey(), ignored -> new LinkedHashMap<>());
            for (WorkflowControlledLoopFormField field : schema.fields().values())
            {
                WorkflowControlledLoopFormField existing = fields.putIfAbsent(field.name(), field);
                if (existing != null && !existing.equals(field))
                {
                    throw invalid("同一流程的正式表单包含同名但类型或约束不同的条件字段", null);
                }
            }
        }
        Map<String, Map<String, WorkflowControlledLoopFormField>> result = new LinkedHashMap<>();
        mutable.forEach((key, value) -> result.put(key, Map.copyOf(value)));
        return Map.copyOf(result);
    }

    /**
     * 收集排他和包容分流网关的完整出线，执行默认、名称、规则和冲突校验。
     * @param model BpmnModel，作者模型
     * @param fieldsByProcess Map&lt;String,Map&lt;String,WorkflowControlledLoopFormField&gt;&gt;，流程字段索引
     * @return List&lt;CompiledBranch&gt;，按 BPMN 顺序返回的规范分支
     */
    private List<CompiledBranch> collectBranches(BpmnModel model,
            Map<String, Map<String, WorkflowControlledLoopFormField>> fieldsByProcess)
    {
        List<CompiledBranch> result = new ArrayList<>();
        for (Process process : model.getProcesses())
        {
            if (!process.isExecutable())
            {
                continue;
            }
            Map<String, WorkflowControlledLoopFormField> fields = fieldsByProcess
                    .getOrDefault(process.getId(), Map.of());
            for (FlowElement element : process.findFlowElementsOfType(FlowElement.class, true))
            {
                if (!(element instanceof ExclusiveGateway)
                        && !(element instanceof InclusiveGateway))
                {
                    if (WorkflowConditionRuleBpmnContract.hasReservedProperty(element)
                            && (!(element instanceof SequenceFlow flow)
                            || (!(flow.getSourceFlowElement() instanceof ExclusiveGateway)
                            && !(flow.getSourceFlowElement() instanceof InclusiveGateway))))
                    {
                        throw invalid("条件分支规则只能配置在排他或包容网关出线上", null);
                    }
                    continue;
                }
                FlowNode gateway = (FlowNode) element;
                if (gateway.getOutgoingFlows() == null || gateway.getOutgoingFlows().size() <= 1)
                {
                    if (gateway.getOutgoingFlows() != null && gateway.getOutgoingFlows().stream()
                            .anyMatch(WorkflowConditionRuleBpmnContract::hasReservedProperty))
                    {
                        throw invalid("条件分支规则只能配置在具有多条出线的排他或包容网关上", null);
                    }
                    continue;
                }
                String gatewayType = gateway instanceof ExclusiveGateway
                        ? "EXCLUSIVE" : "INCLUSIVE";
                String defaultFlowId = gateway instanceof ExclusiveGateway exclusive
                        ? exclusive.getDefaultFlow()
                        : ((InclusiveGateway) gateway).getDefaultFlow();
                if (defaultFlowId == null || defaultFlowId.isBlank())
                {
                    throw invalid("分流网关必须配置唯一默认分支，确保无命中时可继续办理", null);
                }
                String gatewayToken = stableToken(process.getId(), gateway.getId());
            // 重复判定必须包含字段、运算符和值；CEL 配置只描述布尔拓扑，不能作为规则身份。
            Set<String> normalizedRules = new HashSet<>();
                int defaultCount = 0;
                for (SequenceFlow flow : gateway.getOutgoingFlows())
                {
                    boolean defaultFlow = defaultFlowId.equals(flow.getId());
                    defaultCount += defaultFlow ? 1 : 0;
                    if (flow.getName() == null || flow.getName().trim().isEmpty()
                            || flow.getName().trim().length() > MAX_BRANCH_NAME_LENGTH)
                    {
                        throw invalid("网关每条出线都必须配置不超过100字的分支名称", null);
                    }
                    if (flow.getConditionExpression() != null
                            && !flow.getConditionExpression().isBlank())
                    {
                        throw invalid("普通设计者不能在网关出线输入任意表达式，请使用条件规则编辑器", null);
                    }
                    String rawConfig;
                    try
                    {
                        rawConfig = WorkflowConditionRuleBpmnContract.readConfig(flow)
                                .orElseThrow(() -> new IllegalArgumentException("网关出线条件规则不完整"));
                    }
                    catch (IllegalArgumentException exception)
                    {
                        throw invalid(exception.getMessage(), exception);
                    }
                    NormalizedRule normalized = normalizeRule(rawConfig, defaultFlow, fields);
                if (!defaultFlow && !normalizedRules.add(normalized.ruleJson()))
                    {
                        throw invalid("同一网关存在完全相同的条件分支，请合并或调整规则", null);
                    }
                    result.add(new CompiledBranch(process.getId(), gateway.getId(), gatewayType,
                            gatewayToken, flow.getId(), flow.getName().trim(),
                            stableToken(process.getId(), gateway.getId(), flow.getId()),
                            defaultFlow, normalized.ruleJson(), normalized.celConfigJson()));
                }
                if (defaultCount != 1)
                {
                    throw invalid("分流网关默认分支必须且只能有一条", null);
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * 严格解析单条作者规则，按正式字段类型规范值并生成 CEL 布尔组合配置。
     * @param rawConfig String，作者扩展属性中的 JSON
     * @param expectedDefault boolean，BPMN 网关声明的真实默认分支标志
     * @param fields Map&lt;String,WorkflowControlledLoopFormField&gt;，当前流程字段目录
     * @return NormalizedRule，规范规则 JSON 和 CEL 配置
     */
    private NormalizedRule normalizeRule(String rawConfig, boolean expectedDefault,
            Map<String, WorkflowControlledLoopFormField> fields)
    {
        try
        {
            JsonNode root = objectMapper.readTree(rawConfig);
            if (root == null || !root.isObject())
            {
                throw invalid("条件分支规则必须是 JSON 对象", null);
            }
            requireFields(root, expectedDefault
                    ? Set.of("version", "default")
                    : Set.of("version", "default", "combinator", "groups"), "条件规则");
            if (root.path("version").asInt(-1) != 1
                    || root.path("default").asBoolean(!expectedDefault) != expectedDefault)
            {
                throw invalid("条件分支规则版本或默认标志与网关不一致", null);
            }
            ObjectNode normalized = objectMapper.createObjectNode();
            normalized.put("version", 1);
            normalized.put("default", expectedDefault);
            if (expectedDefault)
            {
                return new NormalizedRule(canonical(normalized), null);
            }

            String topCombinator = requireCombinator(root.get("combinator"));
            JsonNode groupsNode = root.get("groups");
            if (groupsNode == null || !groupsNode.isArray()
                    || groupsNode.isEmpty() || groupsNode.size() > MAX_GROUPS)
            {
                throw invalid("条件分支必须包含1至8个规则组", null);
            }
            normalized.put("combinator", topCombinator);
            ArrayNode normalizedGroups = normalized.putArray("groups");
            List<String> groupExpressions = new ArrayList<>();
            ArrayNode celVariables = objectMapper.createArrayNode();
            int ruleIndex = 0;
            for (JsonNode group : groupsNode)
            {
                requireFields(group, Set.of("combinator", "rules"), "规则组");
                String groupCombinator = requireCombinator(group.get("combinator"));
                JsonNode rulesNode = group.get("rules");
                if (rulesNode == null || !rulesNode.isArray() || rulesNode.isEmpty()
                        || rulesNode.size() > MAX_RULES_PER_GROUP)
                {
                    throw invalid("每个规则组必须包含1至8条规则", null);
                }
                ObjectNode normalizedGroup = normalizedGroups.addObject();
                normalizedGroup.put("combinator", groupCombinator);
                ArrayNode normalizedRules = normalizedGroup.putArray("rules");
                List<String> ruleVariables = new ArrayList<>();
                for (JsonNode rule : rulesNode)
                {
                    if (++ruleIndex > MAX_RULES)
                    {
                        throw invalid("单条分支最多允许32条原子规则", null);
                    }
                    String variableName = "rule" + ruleIndex;
                    normalizedRules.add(normalizeAtomicRule(rule, fields));
                    ObjectNode celVariable = celVariables.addObject();
                    celVariable.put("name", variableName);
                    celVariable.put("type", "BOOL");
                    ruleVariables.add(variableName);
                }
                groupExpressions.add("(" + String.join(
                        "AND".equals(groupCombinator) ? " && " : " || ", ruleVariables) + ")");
            }
            ObjectNode celConfig = objectMapper.createObjectNode();
            celConfig.put("expression", String.join(
                    "AND".equals(topCombinator) ? " && " : " || ", groupExpressions));
            celConfig.put("resultVariable", "conditionMatched");
            celConfig.put("resultType", "BOOL");
            celConfig.set("variables", celVariables);
            String normalizedCel = celSandbox.validateAndNormalizeConfig(celConfig);
            return new NormalizedRule(canonical(normalized), normalizedCel);
        }
        catch (JacksonException exception)
        {
            throw invalid("条件分支规则 JSON 无法解析", exception);
        }
    }

    /**
     * 校验并规范单条原子规则的字段、运算符和值。
     * @param rule JsonNode，作者原子规则
     * @param fields Map&lt;String,WorkflowControlledLoopFormField&gt;，正式字段目录
     * @return ObjectNode，可在运行时再次严格解析的规范规则
     */
    private ObjectNode normalizeAtomicRule(JsonNode rule,
            Map<String, WorkflowControlledLoopFormField> fields)
    {
        requireFields(rule, Set.of("field", "operator", "value"), "原子规则");
        String fieldName = requiredText(rule.get("field"), "条件字段不能为空", 128);
        WorkflowControlledLoopFormField field = fields.get(fieldName);
        if (field == null)
        {
            throw invalid("条件字段必须来自当前流程正式表单的可写标量字段: " + fieldName, null);
        }
        String operator = requiredText(rule.get("operator"), "条件运算符不能为空", 32);
        Set<String> allowedOperators = switch (field.kind())
        {
            case NUMBER -> NUMBER_OPERATORS;
            case BOOLEAN -> EQUALITY_OPERATORS;
            case TEXT -> TEXT_OPERATORS;
            case SCALAR -> field.enumValues().isEmpty() ? TEXT_OPERATORS : EQUALITY_OPERATORS;
        };
        if (!allowedOperators.contains(operator))
        {
            throw invalid("条件运算符与字段类型不匹配: " + fieldName, null);
        }

        ObjectNode normalized = objectMapper.createObjectNode();
        normalized.put("field", fieldName);
        normalized.put("fieldType", field.kind().name());
        normalized.put("operator", operator);
        JsonNode value = rule.get("value");
        switch (field.kind())
        {
            case BOOLEAN ->
            {
                if (value == null || !value.isBoolean())
                {
                    throw invalid("布尔条件值类型不合法: " + fieldName, null);
                }
                normalized.put("value", value.booleanValue());
            }
            case NUMBER -> normalized.put("value", normalizeNumber(value, field));
            case TEXT, SCALAR ->
            {
                String text = requiredText(value, "文本条件值不能为空", field.maxLength());
                if (text.length() < field.minLength()
                        || (!field.enumValues().isEmpty() && !field.enumValues().contains(text)))
                {
                    throw invalid("条件值不符合正式表单字段约束: " + fieldName, null);
                }
                normalized.put("value", text);
            }
        }
        return normalized;
    }

    /**
     * 规范有限十进制并复用正式表单的整数及上下界约束。
     * @param value JsonNode，作者数值节点
     * @param field WorkflowControlledLoopFormField，正式数值字段约束
     * @return String，稳定十进制文本
     */
    private String normalizeNumber(JsonNode value, WorkflowControlledLoopFormField field)
    {
        if (value == null || !value.isNumber())
        {
            throw invalid("数值条件值类型不合法: " + field.name(), null);
        }
        BigDecimal decimal = value.decimalValue();
        BigDecimal normalized = decimal.stripTrailingZeros();
        if (field.numericKind() != WorkflowControlledLoopFormField.NumericKind.DECIMAL
                && normalized.scale() > 0)
        {
            throw invalid("整数条件字段不允许小数值: " + field.name(), null);
        }
        if ((field.minimum() != null && decimal.compareTo(field.minimum()) < 0)
                || (field.maximum() != null && decimal.compareTo(field.maximum()) > 0))
        {
            throw invalid("数值条件超出正式表单范围: " + field.name(), null);
        }
        return normalized.toPlainString();
    }

    /**
     * 在编译模型中设置唯一默认分支或固定路由表达式，并剥离作者配置。
     * @param model BpmnModel，前序编译模型
     * @param branch CompiledBranch，规范分支配置
     * @return void，模型关联异常时抛出服务端错误
     */
    private void compileBranch(BpmnModel model, CompiledBranch branch)
    {
        Process process = model.getProcessById(branch.processKey());
        FlowElement element = process == null ? null : process.getFlowElement(branch.flowId(), true);
        if (!(element instanceof SequenceFlow flow))
        {
            throw new ServiceException("条件分支编译节点不存在", HttpStatus.ERROR);
        }
        FlowElement source = flow.getSourceFlowElement();
        if (!(source instanceof ExclusiveGateway) && !(source instanceof InclusiveGateway))
        {
            throw new ServiceException("条件分支编译拓扑已变化", HttpStatus.ERROR);
        }
        if (branch.defaultFlow())
        {
            flow.setConditionExpression(null);
            if (source instanceof ExclusiveGateway gateway)
            {
                gateway.setDefaultFlow(flow.getId());
            }
            else
            {
                ((InclusiveGateway) source).setDefaultFlow(flow.getId());
            }
        }
        else
        {
            flow.setConditionExpression(ROUTER_EXPRESSION.formatted(
                    branch.gatewayToken(), branch.flowToken()));
        }
        WorkflowConditionRuleBpmnContract.removeAuthorConfig(flow);
    }

    /**
     * 将规范分支转换为待持久化快照并生成完整性摘要。
     * @param branch CompiledBranch，规范分支
     * @param actorUserId String，部署操作人用户主键
     * @return WfDeployConditionRule，尚未设置 deployId 的快照
     */
    private WfDeployConditionRule toSnapshot(CompiledBranch branch, String actorUserId)
    {
        WfDeployConditionRule snapshot = new WfDeployConditionRule();
        snapshot.setProcessKey(branch.processKey());
        snapshot.setGatewayId(branch.gatewayId());
        snapshot.setGatewayType(branch.gatewayType());
        snapshot.setGatewayToken(branch.gatewayToken());
        snapshot.setFlowId(branch.flowId());
        snapshot.setFlowName(branch.flowName());
        snapshot.setFlowToken(branch.flowToken());
        snapshot.setDefaultFlow(branch.defaultFlow());
        snapshot.setRuleJson(branch.ruleJson());
        snapshot.setCelConfigJson(branch.celConfigJson());
        snapshot.setCreateBy(actorUserId);
        snapshot.setSnapshotChecksum(snapshotChecksum(snapshot));
        return snapshot;
    }

    /**
     * 严格校验对象字段集合，禁止前端夹带未参与执行的配置。
     * @param node JsonNode，待校验对象
     * @param allowed Set&lt;String&gt;，唯一允许字段集合
     * @param label String，业务对象名称
     * @return void，字段缺失或额外时抛出 400
     */
    private void requireFields(JsonNode node, Set<String> allowed, String label)
    {
        if (node == null || !node.isObject())
        {
            throw invalid(label + "必须是 JSON 对象", null);
        }
        Set<String> actual = new HashSet<>();
        node.propertyNames().forEach(actual::add);
        if (!actual.equals(allowed))
        {
            throw invalid(label + "字段不完整或包含未允许字段", null);
        }
    }

    /**
     * 读取 AND 或 OR 组合符。
     * @param node JsonNode，组合符字段
     * @return String，AND 或 OR
     */
    private String requireCombinator(JsonNode node)
    {
        String value = requiredText(node, "规则组合方式不能为空", 8);
        if (!COMBINATORS.contains(value))
        {
            throw invalid("规则组合方式只能是 AND 或 OR", null);
        }
        return value;
    }

    /**
     * 读取有界、无控制字符的必填文本。
     * @param node JsonNode，文本节点
     * @param message String，失败提示
     * @param maxLength int，最大 UTF-16 长度
     * @return String，去除首尾空白后的文本
     */
    private String requiredText(JsonNode node, String message, int maxLength)
    {
        if (node == null || !node.isTextual())
        {
            throw invalid(message, null);
        }
        String value = node.textValue().trim();
        if (value.isEmpty() || value.length() > maxLength
                || value.chars().anyMatch(Character::isISOControl))
        {
            throw invalid(message, null);
        }
        return value;
    }

    /**
     * 生成字段顺序稳定的规范 JSON。
     * @param node JsonNode，已按契约构造的 JSON
     * @return String，规范 JSON
     */
    private String canonical(JsonNode node)
    {
        try
        {
            return WorkflowExtensionJsonCanonicalizer.canonicalize(
                    objectMapper.writeValueAsString(node));
        }
        catch (JacksonException exception)
        {
            throw invalid("条件规则规范化失败", exception);
        }
    }

    /**
     * 使用长度前缀 SHA-256 生成不含作者标识正文的固定路由令牌。
     * @param values String[]，流程、网关和可选分支标识
     * @return String，24 位小写十六进制令牌
     */
    private String stableToken(String... values)
    {
        return WorkflowExtensionChecksum.sha256(values).substring(0, 24);
    }

    /**
     * 构造不回显业务值或解析器内部信息的参数异常。
     * @param message String，稳定业务提示
     * @param cause Throwable，内部原因，允许为空
     * @return ServiceException，HTTP 400 参数异常
     */
    private ServiceException invalid(String message, Throwable cause)
    {
        ServiceException exception = new ServiceException(message, HttpStatus.BAD_REQUEST);
        if (cause != null)
        {
            exception.initCause(cause);
        }
        return exception;
    }

    /** 规范作者规则与 CEL 配置。 */
    private record NormalizedRule(String ruleJson, String celConfigJson) { }

    /** 条件编译和快照持久化共用的不可变分支配置。 */
    private record CompiledBranch(String processKey, String gatewayId, String gatewayType,
            String gatewayToken, String flowId, String flowName, String flowToken,
            boolean defaultFlow, String ruleJson, String celConfigJson) { }
}
