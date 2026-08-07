package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flowable.dmn.api.DmnDecision;
import org.flowable.dmn.api.DmnDeployment;
import org.flowable.dmn.api.DmnRepositoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.flowable.domain.WfDeployDmnSnapshot;
import com.ruoyi.flowable.mapper.WfDeployDmnSnapshotMapper;
import com.ruoyi.flowable.service.model.WorkflowDmnDecisionService;
import com.ruoyi.flowable.service.model.WorkflowPreparedDmnDeployment;

/**
 * 真实 MySQL、Flowable BPMN Engine 与 DMN Engine 的决策冻结集成测试。
 */
@SpringBootTest(classes = RuoYiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "spring.datasource.druid.master.url=${FLOWABLE_IT_JDBC_URL}",
            "spring.datasource.druid.master.username=${FLOWABLE_IT_USERNAME}",
            "spring.datasource.druid.master.password=${FLOWABLE_IT_PASSWORD}",
            "spring.data.redis.database=${FLOWABLE_IT_REDIS_DATABASE:15}",
            "token.secret=d29ya2Zsb3ctZG1uLWl0LXRva2VuLXNlY3JldC13b3JrZmxvdy1kbW4taXQtdG9rZW4tc2VjcmV0LXdvcmtmbG93LWRtbi1pdC10b2tlbi1zZWNyZXQ=",
            "flowable.database-schema-update=false",
            "flowable.async-executor-activate=false",
            "flowable.async-history-executor-activate=false",
            "spring.quartz.auto-startup=false"
        })
class WorkflowDmnDecisionMySqlIT
{
    private static final String PREFIX = "workflow-dmn-it-";

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private DmnRepositoryService dmnRepositoryService;

    @Autowired
    private WorkflowDmnDecisionService dmnDecisionService;

    @Autowired
    private WfDeployDmnSnapshotMapper snapshotMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /** 本轮唯一标识，防止并行测试使用相同流程和决策 key。 */
    private final String runId = UUID.randomUUID().toString().replace("-", "");

    /** 本轮流程部署主键，用于按创建范围精确清理。 */
    private final Set<String> processDeploymentIds = new LinkedHashSet<>();

    /** 本轮来源 DMN 部署主键；冻结子部署从正式快照读取。 */
    private final Set<String> sourceDmnDeploymentIds = new LinkedHashSet<>();

    /**
     * 清理本轮流程实例、部署、冻结子部署、快照和来源 DMN，不影响其他测试数据。
     * @return void，清理后本轮前缀和部署主键不得残留
     */
    @AfterEach
    void tearDown()
    {
        for (String processDeploymentId : processDeploymentIds)
        {
            List<WfDeployDmnSnapshot> snapshots = snapshotMapper
                    .selectByDeploymentId(processDeploymentId);
            snapshotMapper.deleteByDeploymentId(processDeploymentId);
            processEngine.getRepositoryService().deleteDeployment(processDeploymentId, true);
            dmnDecisionService.deleteFrozenDeployments(snapshots == null ? List.of() : snapshots);
        }
        for (String sourceDeploymentId : sourceDmnDeploymentIds)
        {
            if (dmnRepositoryService.createDeploymentQuery()
                    .deploymentId(sourceDeploymentId).count() > 0)
            {
                dmnRepositoryService.deleteDeployment(sourceDeploymentId);
            }
        }
        assertThat(jdbc.queryForObject(
                "select count(*) from wf_deploy_dmn_snapshot where process_key like ?",
                Integer.class, PREFIX + "%")).isZero();
    }

    /**
     * 验证流程部署冻结精确 DMN v1；来源更新到 v2 后，旧流程仍执行 v1 结果。
     * @return void，决策输出、快照版本或旧定义执行任一漂移时测试失败
     */
    @Test
    void freezesExactDecisionVersionForOldProcessDefinition()
    {
        String decisionKey = PREFIX + "decision-" + runId;
        DmnDecision sourceV1 = deploySourceDecision(decisionKey, "v1-high", "v1-low");
        String processKey = PREFIX + "process-" + runId;
        ProcessDefinition frozenProcess = deployFrozenProcess(processKey, sourceV1.getId());

        assertTaskForAmount(frozenProcess.getId(), 150, "v1Task");
        DmnDecision sourceV2 = deploySourceDecision(decisionKey, "v2-high", "v2-low");
        // 冻结子部署使用同 key，官方版本号允许跳号，但来源新版本必须严格递增。
        assertThat(sourceV2.getVersion()).isGreaterThan(sourceV1.getVersion());

        // 明确按旧流程定义主键发起，验证来源目录升级不会改变既有部署和在途逻辑。
        assertTaskForAmount(frozenProcess.getId(), 150, "v1Task");
        List<WfDeployDmnSnapshot> snapshots = snapshotMapper
                .selectByDeploymentId(frozenProcess.getDeploymentId());
        assertThat(snapshots).singleElement().satisfies(snapshot ->
        {
            assertThat(snapshot.getSourceDecisionId()).isEqualTo(sourceV1.getId());
            assertThat(snapshot.getDecisionVersion()).isEqualTo(sourceV1.getVersion());
            assertThat(snapshot.getFrozenDecisionId()).isNotBlank();
            assertThat(snapshot.getSnapshotChecksum()).hasSize(64);
        });
    }

    /**
     * 部署一个具有高低两条规则的官方 DMN 决策版本。
     * @param decisionKey String，本轮唯一且跨版本稳定的决策 key
     * @param highResult String，amount 大于 100 时输出的 route
     * @param lowResult String，amount 不大于 100 时输出的 route
     * @return DmnDecision，Flowable 官方仓储中的精确版本
     */
    private DmnDecision deploySourceDecision(String decisionKey,
            String highResult, String lowResult)
    {
        String resourceName = decisionKey + ".dmn";
        DmnDeployment deployment = dmnRepositoryService.createDeployment()
                .name(resourceName)
                .category("integration-test")
                .addDmnBytes(resourceName,
                        dmnXml(decisionKey, highResult, lowResult)
                                .getBytes(StandardCharsets.UTF_8))
                .deploy();
        sourceDmnDeploymentIds.add(deployment.getId());
        DmnDecision decision = dmnRepositoryService.createDecisionQuery()
                .deploymentId(deployment.getId())
                .decisionKey(decisionKey)
                .singleResult();
        assertThat(decision).isNotNull();
        return decision;
    }

    /**
     * 编译作者 BusinessRuleTask，部署流程并创建属于该流程部署的 DMN 子部署和快照。
     * @param processKey String，本轮唯一流程 key
     * @param sourceDecisionId String，设计阶段选择的精确 DMN decisionId
     * @return ProcessDefinition，已绑定冻结 DMN 的流程定义
     */
    private ProcessDefinition deployFrozenProcess(String processKey, String sourceDecisionId)
    {
        WorkflowPreparedDmnDeployment prepared = dmnDecisionService.prepare(
                authorBpmn(processKey, sourceDecisionId).getBytes(StandardCharsets.UTF_8));
        Deployment deployment = processEngine.getRepositoryService().createDeployment()
                .name(processKey)
                .key(processKey)
                .addBytes(processKey + ".bpmn20.xml", prepared.compiledBpmn())
                .deploy();
        processDeploymentIds.add(deployment.getId());
        dmnDecisionService.persist(deployment.getId(), prepared, "flowable-it");
        ProcessDefinition definition = processEngine.getRepositoryService()
                .createProcessDefinitionQuery()
                .deploymentId(deployment.getId())
                .singleResult();
        assertThat(definition).isNotNull();
        return definition;
    }

    /**
     * 发起流程并断言 DMN 输出驱动到预期用户任务，然后删除本轮运行实例。
     * @param processDefinitionId String，必须执行的精确旧流程定义主键
     * @param amount int，DMN 输入金额
     * @param expectedTaskKey String，预期到达的用户任务 definition key
     * @return void，分支不匹配时测试失败
     */
    private void assertTaskForAmount(String processDefinitionId, int amount,
            String expectedTaskKey)
    {
        var instance = processEngine.getRuntimeService().startProcessInstanceById(
                processDefinitionId, Map.of("amount", amount));
        var task = processEngine.getTaskService().createTaskQuery()
                .processInstanceId(instance.getId()).singleResult();
        assertThat(task).isNotNull();
        assertThat(task.getTaskDefinitionKey()).isEqualTo(expectedTaskKey);
        processEngine.getRuntimeService().deleteProcessInstance(instance.getId(),
                "DMN 集成测试精确清理");
    }

    /**
     * 生成仅含标准 DMN 1.3 决策表的 XML。
     * @param decisionKey String，决策 key
     * @param highResult String，高金额分支字符串结果
     * @param lowResult String，低金额分支字符串结果
     * @return String，可由 Flowable 8 DMN Engine 部署的 XML
     */
    private String dmnXml(String decisionKey, String highResult, String lowResult)
    {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" "
                + "id=\"definitions_" + runId + "\" name=\"Route Decision\" "
                + "namespace=\"https://approvaplat.local/dmn/" + runId + "\">"
                + "<decision id=\"" + decisionKey + "\" name=\"Route Decision\">"
                + "<decisionTable id=\"table_" + runId + "\" hitPolicy=\"FIRST\">"
                + "<input id=\"input_amount\"><inputExpression id=\"expr_amount\" "
                + "typeRef=\"integer\"><text>amount</text></inputExpression></input>"
                + "<output id=\"output_route\" name=\"route\" typeRef=\"string\"/>"
                + "<rule id=\"rule_high\"><inputEntry id=\"high_input\"><text>&gt; 100</text>"
                + "</inputEntry><outputEntry id=\"high_output\"><text>\"" + highResult
                + "\"</text></outputEntry></rule>"
                + "<rule id=\"rule_low\"><inputEntry id=\"low_input\"><text>&lt;= 100</text>"
                + "</inputEntry><outputEntry id=\"low_output\"><text>\"" + lowResult
                + "\"</text></outputEntry></rule>"
                + "</decisionTable></decision></definitions>";
    }

    /**
     * 生成包含作者 BusinessRuleTask 和基于 route 的两个审批分支的 BPMN XML。
     * @param processKey String，流程 key
     * @param decisionId String，写入 flowable:rules 的精确 decisionId
     * @return String，供部署编译器处理的作者 BPMN
     */
    private String authorBpmn(String processKey, String decisionId)
    {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                + "xmlns:flowable=\"http://flowable.org/bpmn\" targetNamespace=\"ApprovaPlatIT\">"
                + "<process id=\"" + processKey + "\" isExecutable=\"true\">"
                + "<startEvent id=\"start\"/><businessRuleTask id=\"decisionTask\" "
                + "flowable:rules=\"" + decisionId + "\"/><exclusiveGateway id=\"routeGateway\"/>"
                + "<userTask id=\"v1Task\" name=\"Version One\"/>"
                + "<userTask id=\"otherTask\" name=\"Other Version\"/>"
                + "<endEvent id=\"end\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"decisionTask\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"decisionTask\" targetRef=\"routeGateway\"/>"
                + "<sequenceFlow id=\"f3\" sourceRef=\"routeGateway\" targetRef=\"v1Task\">"
                + "<conditionExpression xsi:type=\"tFormalExpression\"><![CDATA[${route == 'v1-high'}]]>"
                + "</conditionExpression></sequenceFlow>"
                + "<sequenceFlow id=\"f4\" sourceRef=\"routeGateway\" targetRef=\"otherTask\"/>"
                + "<sequenceFlow id=\"f5\" sourceRef=\"v1Task\" targetRef=\"end\"/>"
                + "<sequenceFlow id=\"f6\" sourceRef=\"otherTask\" targetRef=\"end\"/>"
                + "</process></definitions>";
    }
}
