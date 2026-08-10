package com.ruoyi.flowable.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.domain.WfDeployConditionRule;
import com.ruoyi.flowable.mapper.WfDeployConditionRuleMapper;
import com.ruoyi.flowable.service.model.WorkflowConditionDeploymentService;

/**
 * 条件分支运行时只信任部署快照和真实流程变量的执行契约测试。
 */
class WorkflowConditionRouterTest
{
    private static final String GATEWAY_TOKEN = "aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String FIRST_TOKEN = "111111111111111111111111";
    private static final String SECOND_TOKEN = "222222222222222222222222";
    private static final String DEFAULT_TOKEN = "333333333333333333333333";

    private RepositoryService repositoryService;
    private WfDeployConditionRuleMapper mapper;
    private DelegateExecution execution;
    private Map<String, Object> transientVariables;

    /**
     * 创建固定定义、部署和可真实缓存 transient 变量的执行上下文。
     * @return void，每个测试使用独立路由状态
     */
    @BeforeEach
    void setUp()
    {
        repositoryService = mock(RepositoryService.class);
        mapper = mock(WfDeployConditionRuleMapper.class);
        execution = mock(DelegateExecution.class);
        transientVariables = new HashMap<>();
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getDeploymentId()).thenReturn("deployment-1");
        when(definition.getKey()).thenReturn("expense");
        when(execution.getProcessDefinitionId()).thenReturn("expense:1:1");
        when(repositoryService.getProcessDefinition("expense:1:1")).thenReturn(definition);
        when(execution.getTransientVariable(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> transientVariables.get(invocation.getArgument(0)));
        org.mockito.Mockito.doAnswer(invocation ->
        {
            transientVariables.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(execution).setTransientVariable(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    /**
     * 验证排他网关只命中单条分支，并在同一命令内复用完整网关计算结果。
     * @return void，分支结果或 transient 缓存不正确时测试失败
     */
    @Test
    void routesExclusiveGatewayToOneMatchingBranch()
    {
        stubVariables(Map.of("amount", 8000));
        stubSnapshots("EXCLUSIVE", numberSnapshot(FIRST_TOKEN, "GT", "5000"),
                numberSnapshot(SECOND_TOKEN, "LT", "0"));
        WorkflowConditionRouter router = new WorkflowConditionRouter(repositoryService, mapper);

        assertThat(router.matches(execution, GATEWAY_TOKEN, FIRST_TOKEN)).isTrue();
        assertThat(router.matches(execution, GATEWAY_TOKEN, SECOND_TOKEN)).isFalse();
    }

    /**
     * 验证排他网关多条同时命中时拒绝 Flowable 的顺序择一行为。
     * @return void，歧义分支未抛冲突时测试失败
     */
    @Test
    void rejectsMultipleExclusiveMatches()
    {
        stubVariables(Map.of("amount", 8000));
        stubSnapshots("EXCLUSIVE", numberSnapshot(FIRST_TOKEN, "GT", "5000"),
                numberSnapshot(SECOND_TOKEN, "GTE", "8000"));

        assertThatThrownBy(() -> new WorkflowConditionRouter(repositoryService, mapper)
                .matches(execution, GATEWAY_TOKEN, FIRST_TOKEN))
                .isInstanceOf(WorkflowConditionRoutingException.class)
                .hasMessageContaining("多个条件同时命中");
    }

    /**
     * 验证包容网关允许多命中，且无命中时所有条件为 false 以交由 Flowable 默认分支处理。
     * @return void，包容或默认策略不确定时测试失败
     */
    @Test
    void supportsInclusiveMultipleMatchesAndNoMatchFallback()
    {
        stubVariables(Map.of("amount", 8000));
        stubSnapshots("INCLUSIVE", numberSnapshot(FIRST_TOKEN, "GT", "5000"),
                numberSnapshot(SECOND_TOKEN, "GTE", "8000"));
        WorkflowConditionRouter router = new WorkflowConditionRouter(repositoryService, mapper);
        assertThat(router.matches(execution, GATEWAY_TOKEN, FIRST_TOKEN)).isTrue();
        assertThat(router.matches(execution, GATEWAY_TOKEN, SECOND_TOKEN)).isTrue();

        setActualVariable("amount", 100);
        transientVariables.clear();
        assertThat(router.matches(execution, GATEWAY_TOKEN, FIRST_TOKEN)).isFalse();
        assertThat(router.matches(execution, GATEWAY_TOKEN, SECOND_TOKEN)).isFalse();
    }

    /**
     * 验证布尔、枚举和文本原子规则只接受严格运行类型并支持受控字符串比较。
     * @return void，类型转换或文本运算偏离快照时测试失败
     */
    @Test
    void evaluatesBooleanEnumAndTextWithoutExpressionInjection()
    {
        WfDeployConditionRule first = snapshot(FIRST_TOKEN,
                "{\"version\":1,\"default\":false,\"combinator\":\"AND\",\"groups\":["
                + "{\"combinator\":\"AND\",\"rules\":["
                + "{\"field\":\"urgent\",\"fieldType\":\"BOOLEAN\",\"operator\":\"EQ\",\"value\":true},"
                + "{\"field\":\"level\",\"fieldType\":\"SCALAR\",\"operator\":\"EQ\",\"value\":\"urgent\"},"
                + "{\"field\":\"description\",\"fieldType\":\"TEXT\",\"operator\":\"CONTAINS\",\"value\":\"合同\"}]}]}",
                cel("rule1 && rule2 && rule3", 3));
        WfDeployConditionRule second = numberSnapshot(SECOND_TOKEN, "LT", "0");
        stubVariables(Map.of("urgent", true, "level", "urgent", "description", "合同审批", "amount", 1));
        stubSnapshots("EXCLUSIVE", first, second);

        assertThat(new WorkflowConditionRouter(repositoryService, mapper)
                .matches(execution, GATEWAY_TOKEN, FIRST_TOKEN)).isTrue();
    }

    /**
     * 验证字段缺失、运行类型错误和快照校验和漂移均失败关闭。
     * @return void，异常输入被当作未命中或默认分支时测试失败
     */
    @Test
    void rejectsMissingInvalidTypedAndTamperedRuntimeInputs()
    {
        stubSnapshots("EXCLUSIVE", numberSnapshot(FIRST_TOKEN, "GT", "5000"),
                numberSnapshot(SECOND_TOKEN, "LT", "0"));
        WorkflowConditionRouter router = new WorkflowConditionRouter(repositoryService, mapper);
        assertThatThrownBy(() -> router.matches(execution, GATEWAY_TOKEN, FIRST_TOKEN))
                .isInstanceOf(WorkflowConditionRoutingException.class).hasMessageContaining("不存在");

        stubVariables(Map.of("amount", "8000"));
        assertThatThrownBy(() -> router.matches(execution, GATEWAY_TOKEN, FIRST_TOKEN))
                .isInstanceOf(WorkflowConditionRoutingException.class).hasMessageContaining("类型不合法");

        setActualVariable("amount", 8000);
        WfDeployConditionRule tampered = numberSnapshot(FIRST_TOKEN, "GT", "5000");
        stubSnapshots("EXCLUSIVE", tampered, numberSnapshot(SECOND_TOKEN, "LT", "0"));
        // 先按可信部署数据签名，再篡改规则正文，模拟数据库快照漂移。
        tampered.setRuleJson(tampered.getRuleJson().replace("5000", "1"));
        assertThatThrownBy(() -> router.matches(execution, GATEWAY_TOKEN, FIRST_TOKEN))
                .isInstanceOf(WorkflowConditionRoutingException.class).hasMessageContaining("校验和不一致");
    }

    /**
     * 绑定完整网关快照并为默认分支生成真实校验和。
     * @param gatewayType String，EXCLUSIVE 或 INCLUSIVE
     * @param first WfDeployConditionRule，第一条非默认规则
     * @param second WfDeployConditionRule，第二条非默认规则
     * @return void，Mapper 后续查询返回固定顺序快照
     */
    private void stubSnapshots(String gatewayType, WfDeployConditionRule first,
            WfDeployConditionRule second)
    {
        for (WfDeployConditionRule snapshot : List.of(first, second))
        {
            snapshot.setGatewayType(gatewayType);
            snapshot.setSnapshotChecksum(WorkflowConditionDeploymentService.snapshotChecksum(snapshot));
        }
        WfDeployConditionRule defaultFlow = defaultSnapshot(gatewayType);
        when(mapper.selectRuntimeGateway("deployment-1", "expense", GATEWAY_TOKEN))
                .thenReturn(List.of(first, second, defaultFlow));
    }

    /**
     * 创建单金额原子规则快照。
     * @param token String，分支令牌
     * @param operator String，数值运算符
     * @param value String，规范十进制值
     * @return WfDeployConditionRule，尚未按网关类型重签名的快照
     */
    private WfDeployConditionRule numberSnapshot(String token, String operator, String value)
    {
        String rule = "{\"version\":1,\"default\":false,\"combinator\":\"AND\",\"groups\":["
                + "{\"combinator\":\"AND\",\"rules\":[{\"field\":\"amount\","
                + "\"fieldType\":\"NUMBER\",\"operator\":\"" + operator
                + "\",\"value\":\"" + value + "\"}]}]}";
        return snapshot(token, rule, cel("rule1", 1));
    }

    /**
     * 创建具有稳定部署关联的非默认分支快照。
     * @param token String，分支令牌
     * @param ruleJson String，规范规则 JSON
     * @param celJson String，布尔组合 CEL 配置
     * @return WfDeployConditionRule，调用方需补充网关类型及校验和
     */
    private WfDeployConditionRule snapshot(String token, String ruleJson, String celJson)
    {
        WfDeployConditionRule snapshot = baseSnapshot(token);
        snapshot.setDefaultFlow(false);
        snapshot.setRuleJson(ruleJson);
        snapshot.setCelConfigJson(celJson);
        return snapshot;
    }

    /** @param gatewayType String，网关类型。 @return WfDeployConditionRule，唯一默认分支快照。 */
    private WfDeployConditionRule defaultSnapshot(String gatewayType)
    {
        WfDeployConditionRule snapshot = baseSnapshot(DEFAULT_TOKEN);
        snapshot.setGatewayType(gatewayType);
        snapshot.setDefaultFlow(true);
        snapshot.setRuleJson("{\"default\":true,\"version\":1}");
        snapshot.setCelConfigJson(null);
        snapshot.setSnapshotChecksum(WorkflowConditionDeploymentService.snapshotChecksum(snapshot));
        return snapshot;
    }

    /** @param token String，分支令牌。 @return WfDeployConditionRule，公共部署关联字段。 */
    private WfDeployConditionRule baseSnapshot(String token)
    {
        WfDeployConditionRule snapshot = new WfDeployConditionRule();
        snapshot.setDeployId("deployment-1");
        snapshot.setProcessKey("expense");
        snapshot.setGatewayId("gateway");
        snapshot.setGatewayToken(GATEWAY_TOKEN);
        snapshot.setFlowId("flow_" + token.charAt(0));
        snapshot.setFlowName("条件分支");
        snapshot.setFlowToken(token);
        snapshot.setCreateBy("7");
        return snapshot;
    }

    /**
     * 生成仅声明布尔原子的固定 CEL 配置。
     * @param expression String，AND/OR 组合表达式
     * @param count int，rule1 开始的变量数量
     * @return String，CEL 沙箱规范配置 JSON
     */
    private String cel(String expression, int count)
    {
        StringBuilder variables = new StringBuilder();
        for (int index = 1; index <= count; index++)
        {
            if (index > 1)
            {
                variables.append(',');
            }
            variables.append("{\"name\":\"rule").append(index)
                    .append("\",\"type\":\"BOOL\"}");
        }
        return "{\"expression\":\"" + expression + "\",\"resultType\":\"BOOL\","
                + "\"resultVariable\":\"conditionMatched\",\"variables\":[" + variables + "]}";
    }

    /**
     * 绑定一组真实流程变量。
     * @param variables Map&lt;String,Object&gt;，字段名到运行值
     * @return void，hasVariable/getVariable 使用相同数据源
     */
    private void stubVariables(Map<String, Object> variables)
    {
        variables.forEach(this::setActualVariable);
    }

    /**
     * 设置单个真实流程变量及存在标志。
     * @param name String，字段名
     * @param value Object，运行值
     * @return void，覆盖同名 Mockito 返回值
     */
    private void setActualVariable(String name, Object value)
    {
        when(execution.hasVariable(name)).thenReturn(true);
        when(execution.getVariable(name)).thenReturn(value);
    }
}
