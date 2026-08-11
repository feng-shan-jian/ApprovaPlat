package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.flowable.dmn.api.DmnDecision;
import org.flowable.dmn.api.DmnDeployment;
import org.flowable.dmn.api.DmnRepositoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.job.api.Job;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.flowable.domain.WfConnectorEndpoint;
import com.ruoyi.flowable.domain.WfDeployDmnSnapshot;
import com.ruoyi.flowable.domain.WfSqlDataSource;
import com.ruoyi.flowable.mapper.WfConnectorEndpointMapper;
import com.ruoyi.flowable.mapper.WfSqlDataSourceMapper;
import com.ruoyi.flowable.service.model.WorkflowBpmnDocument;
import com.ruoyi.flowable.service.model.WorkflowBpmnService;
import com.ruoyi.flowable.service.model.WorkflowConnectorEndpointService;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifacts;
import com.ruoyi.flowable.service.model.WorkflowDmnDecisionService;
import com.ruoyi.flowable.service.model.WorkflowExtensionDeploymentService;
import com.ruoyi.flowable.service.model.WorkflowPreparedDmnDeployment;
import com.ruoyi.flowable.service.model.WorkflowPreparedExtensionDeployment;
import com.ruoyi.flowable.service.model.WorkflowSqlDataSourceService;

/**
 * 使用真实 MySQL、Flowable、HTTP、SQL 与 DMN 验证连接器输出驱动受控 BPMN Error 的完整链路。
 */
@SpringBootTest(classes = RuoYiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "spring.datasource.druid.master.url=${FLOWABLE_IT_JDBC_URL}",
            "spring.datasource.druid.master.username=${FLOWABLE_IT_USERNAME}",
            "spring.datasource.druid.master.password=${FLOWABLE_IT_PASSWORD}",
            "spring.data.redis.database=${FLOWABLE_IT_REDIS_DATABASE:15}",
            "token.secret=Y29ubmVjdG9yLWJwbW4tZXZlbnQtY2hhaW4taXQtdG9rZW4tc2VjcmV0LWNvbm5lY3Rvci1icG1uLWV2ZW50LWNoYWluLWl0LXRva2VuLXNlY3JldA==",
            "flowable.database-schema-update=false",
            "flowable.async-executor-activate=false",
            "flowable.async-history-executor-activate=false",
            "spring.quartz.auto-startup=false"
        })
class WorkflowConnectorBpmnEventChainMySqlIT
{
    private static final String PREFIX = "workflow-connector-event-it-";
    private static final String ORIGINAL_SQL_NAME = "连接器事件链原值";

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private DmnRepositoryService dmnRepositoryService;
    @Autowired
    private WorkflowBpmnService bpmnService;
    @Autowired
    private WorkflowExtensionDeploymentService extensionDeploymentService;
    @Autowired
    private WorkflowDmnDecisionService dmnDecisionService;
    @Autowired
    private WorkflowDeploymentArtifactRepository artifactRepository;
    @Autowired
    private WfConnectorEndpointMapper endpointMapper;
    @Autowired
    private WfSqlDataSourceMapper dataSourceMapper;
    @Autowired
    private JdbcTemplate jdbc;

    /** 本轮唯一标识，隔离并发执行和失败重跑。 */
    private final String runId = UUID.randomUUID().toString().replace("-", "");
    /** 本轮流程部署主键，清理时只处理本测试拥有的数据。 */
    private final Set<String> processDeploymentIds = new LinkedHashSet<>();
    /** 本轮 DMN 来源部署主键，不删除其他目录决策。 */
    private final Set<String> sourceDmnDeploymentIds = new LinkedHashSet<>();
    /** 本机 HTTP 服务接收到的真实请求正文。 */
    private final AtomicReference<String> httpRequestBody = new AtomicReference<>();

    private HttpServer httpServer;
    private String endpointKey;
    private Long endpointId;
    private String dataSourceKey;
    private Long dataSourceId;

    /**
     * 创建本轮本机 HTTP 服务、正式端点白名单和正式主库 SQL 数据源目录。
     * @return void，成功后可由部署编译器冻结两个真实连接器配置
     * @throws IOException 本机 HTTP 监听端口创建失败
     */
    @BeforeEach
    void setUp() throws IOException
    {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/event/status", this::handleHttpStatusRequest);
        httpServer.start();

        endpointKey = PREFIX + "http-" + runId;
        WfConnectorEndpoint endpoint = endpoint();
        assertThat(endpointMapper.insert(endpoint)).isEqualTo(1);
        endpointId = endpoint.getEndpointId();
        assertThat(endpointId).isPositive();

        dataSourceKey = PREFIX + "sql-" + runId;
        WfSqlDataSource dataSource = dataSource();
        assertThat(dataSourceMapper.insert(dataSource)).isEqualTo(1);
        dataSourceId = dataSource.getDataSourceId();
        assertThat(dataSourceId).isPositive();
    }

    /**
     * 按通知、审计、调用台账、快照、流程部署、DMN 子部署和目录的外键顺序精确清理。
     * @return void，任何本轮正式数据残留时测试失败
     */
    @AfterEach
    void tearDown()
    {
        if (httpServer != null)
        {
            httpServer.stop(0);
        }
        RepositoryService repositoryService = processEngine.getRepositoryService();
        for (String deploymentId : processDeploymentIds)
        {
            List<WfDeployDmnSnapshot> dmnSnapshots = artifactRepository
                    .selectDmnSnapshots(deploymentId);
            jdbc.update("delete d from wf_notification_delivery_audit d "
                    + "join wf_notification_outbox o on o.outbox_id=d.outbox_id "
                    + "join wf_bpmn_event_audit a on o.source_type='BPMN_EVENT' "
                    + "and o.source_id=cast(a.audit_id as char) "
                    + "where a.deployment_id=?", deploymentId);
            jdbc.update("delete n from wf_notification_inbox n "
                    + "join wf_notification_outbox o on o.outbox_id=n.outbox_id "
                    + "join wf_bpmn_event_audit a on o.source_type='BPMN_EVENT' "
                    + "and o.source_id=cast(a.audit_id as char) "
                    + "where a.deployment_id=?", deploymentId);
            jdbc.update("delete o from wf_notification_outbox o "
                    + "join wf_bpmn_event_audit a on o.source_type='BPMN_EVENT' "
                    + "and o.source_id=cast(a.audit_id as char) "
                    + "where a.deployment_id=?", deploymentId);
            jdbc.update("delete from wf_bpmn_event_audit where deployment_id=?", deploymentId);
            jdbc.update("delete from wf_connector_invocation where deployment_id=?", deploymentId);
            artifactRepository.delete(deploymentId);
            if (repositoryService.createDeploymentQuery().deploymentId(deploymentId).count() == 1L)
            {
                repositoryService.deleteDeployment(deploymentId, true);
            }
            dmnDecisionService.deleteFrozenDeployments(
                    dmnSnapshots == null ? List.of() : dmnSnapshots);
        }
        for (String sourceDeploymentId : sourceDmnDeploymentIds)
        {
            if (dmnRepositoryService.createDeploymentQuery()
                    .deploymentId(sourceDeploymentId).count() == 1L)
            {
                dmnRepositoryService.deleteDeployment(sourceDeploymentId);
            }
        }
        if (endpointId != null)
        {
            jdbc.update("delete from wf_connector_endpoint where endpoint_id=?", endpointId);
        }
        if (dataSourceId != null)
        {
            jdbc.update("delete from wf_sql_datasource where datasource_id=?", dataSourceId);
        }
        assertThat(jdbc.queryForObject(
                "select count(*) from wf_connector_endpoint where endpoint_key=?",
                Integer.class, endpointKey)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from wf_sql_datasource where datasource_key=?",
                Integer.class, dataSourceKey)).isZero();
    }

    /**
     * 验证本机 HTTP 真实响应状态写入流程变量，并由后续受控事件节点精确捕获到人工任务。
     * @return void，请求、变量、扩展快照、审计或 Flowable 路径任一不一致时测试失败
     */
    @Test
    void routesHttpStatusOutputThroughControlledErrorBoundary()
    {
        String processKey = key("http");
        Deployment deployment = deploy(processKey,
                connectorEventBpmn(processKey, httpTask(), "HTTP", "httpStatus", "202"));
        assertThat(artifactRepository.selectExtensionSnapshots(deployment.getId())).hasSize(2);

        ProcessInstance instance = processEngine.getRuntimeService().startProcessInstanceByKey(
                processKey, Map.of("initiator", "1", "httpPayload", "真实审批回调"));
        Job job = processEngine.getManagementService().createJobQuery()
                .processInstanceId(instance.getId()).singleResult();
        assertThat(job).isNotNull();
        processEngine.getManagementService().executeJob(job.getId());

        assertThat(httpRequestBody).hasValue("\"真实审批回调\"");
        assertThat(processEngine.getRuntimeService().getVariable(
                instance.getId(), "httpStatus")).isEqualTo(202L);
        assertCaptured(instance, deployment, "HTTP", "producerTask");
        assertThat(jdbc.queryForMap(
                "select connector_type, status, result_code from wf_connector_invocation "
                        + "where deployment_id=? and process_instance_id=?",
                deployment.getId(), instance.getId()))
                .containsEntry("connector_type", "HTTP")
                .containsEntry("status", "SUCCESS")
                .containsEntry("result_code", 202);
        completeAndAssertFinished(instance.getId());
    }

    /**
     * 验证正式主库 SQL 连接器影响行数驱动后续受控事件，业务写入和捕获状态共同提交。
     * @return void，SQL 结果变量、数据库行、审计来源或人工处理路径不一致时测试失败
     */
    @Test
    void routesSqlAffectedRowsThroughControlledErrorBoundary()
    {
        String processKey = key("sql");
        Deployment deployment = deploy(processKey,
                connectorEventBpmn(processKey, sqlTask(), "SQL", "sqlAffected", "1"));
        ProcessInstance instance = processEngine.getRuntimeService().startProcessInstanceByKey(
                processKey, Map.of("initiator", "1", "dataSourceKey", dataSourceKey,
                        "nextName", "连接器事件链已提交"));

        Object affected = processEngine.getRuntimeService().getVariable(
                instance.getId(), "sqlAffected");
        assertThat(affected).isInstanceOf(Number.class);
        assertThat(((Number) affected).intValue()).isOne();
        assertThat(jdbc.queryForObject(
                "select datasource_name from wf_sql_datasource where datasource_id=?",
                String.class, dataSourceId)).isEqualTo("连接器事件链已提交");
        assertCaptured(instance, deployment, "SQL", "producerTask");
        completeAndAssertFinished(instance.getId());
    }

    /**
     * 验证真实 DMN 决策表输出变量驱动后续受控事件，并进入精确 Error 边界人工处理任务。
     * @return void，DMN 冻结、输出变量、事件审计或真实执行路径不一致时测试失败
     */
    @Test
    void routesDmnDecisionOutputThroughControlledErrorBoundary()
    {
        String decisionKey = key("decision");
        DmnDecision decision = deploySourceDecision(decisionKey);
        String processKey = key("dmn");
        Deployment deployment = deploy(processKey,
                connectorEventBpmn(processKey, dmnTask(decision.getId()),
                        "DMN", "eventSignal", "RAISE"));
        assertThat(artifactRepository.selectDmnSnapshots(deployment.getId())).singleElement()
                .satisfies(snapshot ->
                {
                    assertThat(snapshot.getSourceDecisionId()).isEqualTo(decision.getId());
                    assertThat(snapshot.getFrozenDecisionId()).isNotBlank();
                });

        ProcessInstance instance = processEngine.getRuntimeService().startProcessInstanceByKey(
                processKey, Map.of("initiator", "1", "amount", 150));
        assertThat(processEngine.getRuntimeService().getVariable(
                instance.getId(), "eventSignal")).isEqualTo("RAISE");
        assertCaptured(instance, deployment, "DMN", "producerTask");
        completeAndAssertFinished(instance.getId());
    }

    /**
     * 验证 SQL 写入后受控 Error 未匹配时同一 Flowable 命令整体回滚，仅保留独立诊断审计。
     * @return void，SQL、流程、历史、通知任一出现部分提交时测试失败
     */
    @Test
    void rollsBackSqlWriteWhenControlledErrorIsUnmatched()
    {
        String processKey = key("sql-rollback");
        String author = connectorEventBpmn(
                processKey, sqlTask(), "SQL", "sqlAffected", "1");
        WorkflowBpmnDocument document = bpmnService.validateForSave(
                author.getBytes(StandardCharsets.UTF_8));
        WorkflowPreparedExtensionDeployment extensionPrepared =
                extensionDeploymentService.prepare(document, "1");
        String compiled = new String(extensionPrepared.compiledBpmn(), StandardCharsets.UTF_8);
        String tampered = compiled.replace(
                "errorCode=\"APPROVAL_BUSINESS_ERROR\"",
                "errorCode=\"TAMPERED_CONNECTOR_ERROR\"");
        assertThat(tampered).isNotEqualTo(compiled);
        Deployment deployment = processEngine.getRepositoryService().createDeployment()
                .name(processKey).key(processKey)
                .addBytes(processKey + ".bpmn20.xml",
                        tampered.getBytes(StandardCharsets.UTF_8)).deploy();
        processDeploymentIds.add(deployment.getId());
        artifactRepository.persist(deployment.getId(), new WorkflowDeploymentArtifacts(
                List.of(), List.of(), List.of(), List.of(),
                extensionDeploymentService.snapshotsForDeployment(
                        deployment.getId(), extensionPrepared),
                List.of(), List.of(), List.of()));

        assertThatThrownBy(() -> processEngine.getRuntimeService().startProcessInstanceByKey(
                processKey, Map.of("initiator", "1", "dataSourceKey", dataSourceKey,
                        "nextName", "不应泄漏")))
                .hasMessageContaining("APPROVAL_BUSINESS_ERROR");

        assertThat(jdbc.queryForObject(
                "select datasource_name from wf_sql_datasource where datasource_id=?",
                String.class, dataSourceId)).isEqualTo(ORIGINAL_SQL_NAME);
        assertThat(processEngine.getRuntimeService().createProcessInstanceQuery()
                .processDefinitionKey(processKey).count()).isZero();
        assertThat(processEngine.getHistoryService().createHistoricProcessInstanceQuery()
                .processDefinitionKey(processKey).count()).isZero();
        assertThat(jdbc.queryForMap(
                "select source_type, match_status, source_element_id "
                        + "from wf_bpmn_event_audit where deployment_id=?",
                deployment.getId()))
                .containsEntry("source_type", "SQL")
                .containsEntry("match_status", "UNMATCHED")
                .containsEntry("source_element_id", "raiseEvent");
        assertThat(jdbc.queryForObject(
                "select count(*) from wf_notification_inbox n "
                        + "join wf_notification_outbox o on o.outbox_id=n.outbox_id "
                        + "and o.source_type='BPMN_EVENT' "
                        + "join wf_bpmn_event_audit a "
                        + "on o.source_id=cast(a.audit_id as char) "
                        + "where a.deployment_id=?",
                Integer.class, deployment.getId())).isZero();
    }

    /**
     * 使用正式扩展与 DMN 编译器生成不可变快照，然后部署真实 Flowable 流程。
     * @param processKey String，本轮唯一流程 key
     * @param authorXml String，包含连接器、受控事件和边界的作者 BPMN
     * @return Deployment，已持久化扩展与 DMN 快照的真实流程部署
     */
    private Deployment deploy(String processKey, String authorXml)
    {
        WorkflowBpmnDocument document = bpmnService.validateForSave(
                authorXml.getBytes(StandardCharsets.UTF_8));
        WorkflowPreparedExtensionDeployment extensionPrepared =
                extensionDeploymentService.prepare(document, "1");
        WorkflowPreparedDmnDeployment dmnPrepared =
                dmnDecisionService.prepare(extensionPrepared.compiledBpmn());
        Deployment deployment = processEngine.getRepositoryService().createDeployment()
                .name(processKey).key(processKey)
                .addBytes(processKey + ".bpmn20.xml", dmnPrepared.compiledBpmn()).deploy();
        processDeploymentIds.add(deployment.getId());
        List<com.ruoyi.flowable.domain.WfDeployExtensionSnapshot> extensionSnapshots =
                extensionDeploymentService.snapshotsForDeployment(
                        deployment.getId(), extensionPrepared);
        List<WfDeployDmnSnapshot> dmnSnapshots = dmnDecisionService.freezeSnapshots(
                deployment.getId(), dmnPrepared, "1");
        artifactRepository.persist(deployment.getId(), new WorkflowDeploymentArtifacts(
                List.of(), List.of(), List.of(), List.of(), extensionSnapshots,
                dmnSnapshots, List.of(), List.of()));
        return deployment;
    }

    /**
     * 断言连接器输出后只进入异常人工任务，并核对审计来源、精确边界与历史活动。
     * @param instance ProcessInstance，当前真实流程实例
     * @param deployment Deployment，实例所属部署
     * @param sourceType String，HTTP、SQL 或 DMN
     * @param connectorElementId String，必须真实执行的连接器或决策节点标识
     * @return void，任一捕获证据缺失时测试失败
     */
    private void assertCaptured(ProcessInstance instance, Deployment deployment,
            String sourceType, String connectorElementId)
    {
        Task task = processEngine.getTaskService().createTaskQuery()
                .processInstanceId(instance.getId()).singleResult();
        assertThat(task).isNotNull();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("eventHandler");
        assertThat(jdbc.queryForMap(
                "select source_type, match_status, source_element_id, boundary_event_id "
                        + "from wf_bpmn_event_audit where deployment_id=? "
                        + "and process_instance_id=?",
                deployment.getId(), instance.getId()))
                .containsEntry("source_type", sourceType)
                .containsEntry("match_status", "CAPTURED")
                .containsEntry("source_element_id", "raiseEvent")
                .containsEntry("boundary_event_id", "eventBoundary");
        assertThat(historicActivityIds(instance.getId()))
                .contains(connectorElementId, "raiseEvent", "eventBoundary", "eventHandler")
                .doesNotContain("mainHandler");
    }

    /**
     * 完成唯一人工任务并断言运行态消失、历史终态完成。
     * @param processInstanceId String，待完成流程实例主键
     * @return void，任务或流程终态不唯一时测试失败
     */
    private void completeAndAssertFinished(String processInstanceId)
    {
        Task task = processEngine.getTaskService().createTaskQuery()
                .processInstanceId(processInstanceId).singleResult();
        processEngine.getIdentityService().setAuthenticatedUserId("1");
        try
        {
            processEngine.getTaskService().complete(task.getId());
        }
        finally
        {
            processEngine.getIdentityService().setAuthenticatedUserId(null);
        }
        assertThat(processEngine.getRuntimeService().createProcessInstanceQuery()
                .processInstanceId(processInstanceId).count()).isZero();
        assertThat(processEngine.getHistoryService().createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).finished().count()).isOne();
    }

    /**
     * 查询真实历史活动标识，供连接器、事件节点和边界路径对账。
     * @param processInstanceId String，流程实例主键
     * @return List&lt;String&gt;，该实例全部历史活动标识
     */
    private List<String> historicActivityIds(String processInstanceId)
    {
        return processEngine.getHistoryService().createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId).list().stream()
                .map(activity -> activity.getActivityId()).toList();
    }

    /**
     * 构造连接器或决策输出、后续受控事件节点、精确 Error 边界和人工处理路径。
     * @param processKey String，唯一流程 key
     * @param producerTask String，真实 HTTP、SQL 或 DMN 节点 XML
     * @param sourceType String，事件审计来源类型
     * @param conditionVariable String，前序节点写入的条件变量
     * @param expectedValue String，触发受控 Error 的精确标量值
     * @return String，可通过正式保存与部署门禁的作者 BPMN XML
     */
    private String connectorEventBpmn(String processKey, String producerTask,
            String sourceType, String conditionVariable, String expectedValue)
    {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                + "xmlns:flowable=\"http://flowable.org/bpmn\" targetNamespace=\"ApprovaPlatIT\">"
                + "<error id=\"eventRoot\" name=\"审批业务校验失败\" "
                + "errorCode=\"APPROVAL_BUSINESS_ERROR\"/>"
                + "<process id=\"" + processKey + "\" isExecutable=\"true\">"
                + "<startEvent id=\"start\" flowable:formKey=\"key_1\"/>"
                + "<sequenceFlow id=\"toProducer\" sourceRef=\"start\" targetRef=\"producerTask\"/>"
                + producerTask
                + "<sequenceFlow id=\"toRaise\" sourceRef=\"producerTask\" targetRef=\"raiseEvent\"/>"
                + raiseEventTask(sourceType, conditionVariable, expectedValue)
                + "<boundaryEvent id=\"eventBoundary\" attachedToRef=\"raiseEvent\" "
                + "cancelActivity=\"true\"><errorEventDefinition errorRef=\"eventRoot\"/>"
                + "</boundaryEvent>"
                + "<sequenceFlow id=\"boundaryToHandler\" sourceRef=\"eventBoundary\" "
                + "targetRef=\"eventHandler\"/>"
                + userTask("eventHandler", "连接器异常人工处理")
                + "<sequenceFlow id=\"eventToEnd\" sourceRef=\"eventHandler\" targetRef=\"end\"/>"
                + "<sequenceFlow id=\"raiseToMain\" sourceRef=\"raiseEvent\" targetRef=\"mainHandler\"/>"
                + userTask("mainHandler", "连接器正常审批")
                + "<sequenceFlow id=\"mainToEnd\" sourceRef=\"mainHandler\" targetRef=\"end\"/>"
                + "<endEvent id=\"end\"/></process></definitions>";
    }

    /**
     * 构造正式异步 HTTP 连接器节点，状态码写入 httpStatus 流程变量。
     * @return String，作者 BPMN 中的 HTTP ServiceTask XML
     */
    private String httpTask()
    {
        return "<serviceTask id=\"producerTask\" name=\"读取外部审批状态\" "
                + "flowable:async=\"true\" flowable:delegateExpression=\"${workflowExtensionDelegate}\">"
                + "<extensionElements><flowable:failedJobRetryTimeCycle>R1/PT1S</flowable:failedJobRetryTimeCycle>"
                + "<flowable:field name=\"approvaExtensionKey\" "
                + "stringValue=\"approva.http-connector\"/>"
                + "<flowable:field name=\"approvaExtensionConfig\"><flowable:string><![CDATA["
                + "{\"endpointKey\":\"" + endpointKey + "\",\"method\":\"POST\","
                + "\"path\":\"/event/status\",\"bodyVariable\":\"httpPayload\","
                + "\"statusVariable\":\"httpStatus\"}]]></flowable:string></flowable:field>"
                + "</extensionElements></serviceTask>";
    }

    /**
     * 构造正式主库 SQL 连接器节点，真实更新目录行并写回影响行数。
     * @return String，作者 BPMN 中的 SQL ServiceTask XML
     */
    private String sqlTask()
    {
        return "<serviceTask id=\"producerTask\" name=\"更新审批联动状态\" "
                + "flowable:delegateExpression=\"${workflowExtensionDelegate}\">"
                + "<extensionElements><flowable:field name=\"approvaExtensionKey\" "
                + "stringValue=\"approva.sql-connector\"/>"
                + "<flowable:field name=\"approvaExtensionConfig\"><flowable:string><![CDATA["
                + "{\"dataSourceKey\":\"" + dataSourceKey + "\","
                + "\"sql\":\"update wf_sql_datasource set datasource_name = :nextName "
                + "where datasource_key = :dataSourceKey\","
                + "\"parameters\":{\"nextName\":\"nextName\","
                + "\"dataSourceKey\":\"dataSourceKey\"},"
                + "\"resultVariable\":\"sqlAffected\",\"maxRows\":1}"
                + "]]></flowable:string></flowable:field></extensionElements></serviceTask>";
    }

    /**
     * 构造精确引用正式 DMN decisionId 的 BusinessRuleTask。
     * @param decisionId String，设计阶段选择的 DMN 精确版本主键
     * @return String，作者 BPMN 中的 BusinessRuleTask XML
     */
    private String dmnTask(String decisionId)
    {
        return "<businessRuleTask id=\"producerTask\" name=\"审批风险决策\" "
                + "flowable:rules=\"" + decisionId + "\"/>";
    }

    /**
     * 构造只在前序真实输出等于期望值时产生业务 Error 的受控扩展节点。
     * @param sourceType String，HTTP、SQL 或 DMN 审计来源
     * @param conditionVariable String，前序节点输出变量
     * @param expectedValue String，触发事件的精确值
     * @return String，作者 BPMN 中的受控 RAISE_BPMN_EVENT ServiceTask XML
     */
    private String raiseEventTask(String sourceType, String conditionVariable,
            String expectedValue)
    {
        return "<serviceTask id=\"raiseEvent\" name=\"产生受控审批错误\" "
                + "flowable:delegateExpression=\"${workflowExtensionDelegate}\">"
                + "<extensionElements><flowable:field name=\"approvaExtensionKey\" "
                + "stringValue=\"approva.raise-bpmn-event\"/>"
                + "<flowable:field name=\"approvaExtensionConfig\"><flowable:string><![CDATA["
                + "{\"eventType\":\"ERROR\",\"eventCode\":\"APPROVAL_BUSINESS_ERROR\","
                + "\"sourceType\":\"" + sourceType + "\",\"operator\":\"EQUALS\","
                + "\"conditionVariable\":\"" + conditionVariable + "\","
                + "\"expectedValue\":\"" + expectedValue + "\"}"
                + "]]></flowable:string></flowable:field></extensionElements></serviceTask>";
    }

    /**
     * 构造带固定审计监听器的真实人工任务。
     * @param id String，任务 definition key
     * @param name String，用户可见任务名称
     * @return String，UserTask BPMN XML
     */
    private String userTask(String id, String name)
    {
        return "<userTask id=\"" + id + "\" name=\"" + name
                + "\" flowable:assignee=\"1\" flowable:formKey=\"key_1\">"
                + "<extensionElements>"
                + "<flowable:taskListener event=\"create\" delegateExpression=\"${userTaskListener}\"/>"
                + "<flowable:taskListener event=\"assignment\" delegateExpression=\"${userTaskListener}\"/>"
                + "<flowable:taskListener event=\"complete\" delegateExpression=\"${userTaskListener}\"/>"
                + "</extensionElements></userTask>";
    }

    /**
     * 部署真实 DMN 1.3 决策表，高金额输出 RAISE，低金额输出 PASS。
     * @param decisionKey String，本轮唯一正式决策 key
     * @return DmnDecision，Flowable DMN Repository 中的来源精确版本
     */
    private DmnDecision deploySourceDecision(String decisionKey)
    {
        String resourceName = decisionKey + ".dmn";
        DmnDeployment deployment = dmnRepositoryService.createDeployment()
                .name(resourceName).category("integration-test")
                .addDmnBytes(resourceName,
                        dmnXml(decisionKey).getBytes(StandardCharsets.UTF_8)).deploy();
        sourceDmnDeploymentIds.add(deployment.getId());
        DmnDecision decision = dmnRepositoryService.createDecisionQuery()
                .deploymentId(deployment.getId()).decisionKey(decisionKey).singleResult();
        assertThat(decision).isNotNull();
        return decision;
    }

    /**
     * 生成由 amount 输入产生 eventSignal 输出的标准 DMN 决策表。
     * @param decisionKey String，决策稳定 key
     * @return String，可由 Flowable 8 DMN Engine 真实部署的 XML
     */
    private String dmnXml(String decisionKey)
    {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" "
                + "id=\"definitions_" + runId + "\" name=\"Event Decision\" "
                + "namespace=\"https://approvaplat.local/dmn/" + runId + "\">"
                + "<decision id=\"" + decisionKey + "\" name=\"Event Decision\">"
                + "<decisionTable id=\"table_" + runId + "\" hitPolicy=\"FIRST\">"
                + "<input id=\"input_amount\"><inputExpression id=\"expr_amount\" "
                + "typeRef=\"integer\"><text>amount</text></inputExpression></input>"
                + "<output id=\"output_event\" name=\"eventSignal\" typeRef=\"string\"/>"
                + "<rule id=\"rule_high\"><inputEntry id=\"high_input\"><text>&gt; 100</text>"
                + "</inputEntry><outputEntry id=\"high_output\"><text>\"RAISE\"</text>"
                + "</outputEntry></rule>"
                + "<rule id=\"rule_low\"><inputEntry id=\"low_input\"><text>&lt;= 100</text>"
                + "</inputEntry><outputEntry id=\"low_output\"><text>\"PASS\"</text>"
                + "</outputEntry></rule></decisionTable></decision></definitions>";
    }

    /**
     * 创建当前本机 HTTP 服务对应的正式端点目录实体及稳定摘要。
     * @return WfConnectorEndpoint，可由正式 Mapper 写入的启用修订 1
     */
    private WfConnectorEndpoint endpoint()
    {
        WfConnectorEndpoint endpoint = new WfConnectorEndpoint();
        endpoint.setEndpointKey(endpointKey);
        endpoint.setEndpointName("连接器事件链本机端点");
        endpoint.setBaseUrl("http://127.0.0.1:" + httpServer.getAddress().getPort());
        endpoint.setAllowedMethods("POST");
        endpoint.setPathPrefix("/event");
        endpoint.setAuthType("NONE");
        endpoint.setConnectTimeoutMs(1000);
        endpoint.setRequestTimeoutMs(5000);
        endpoint.setNetworkScope("PRIVATE");
        endpoint.setRevisionNo(1);
        endpoint.setStatus("ENABLED");
        endpoint.setCreateBy("flowable-it");
        endpoint.setChecksum(WorkflowConnectorEndpointService.endpointChecksum(endpoint));
        return endpoint;
    }

    /**
     * 创建只授权 wf_sql_datasource 的正式主库 SQL 数据源目录实体及稳定摘要。
     * @return WfSqlDataSource，可由正式 Mapper 写入的启用修订 1
     */
    private WfSqlDataSource dataSource()
    {
        WfSqlDataSource source = new WfSqlDataSource();
        source.setDataSourceKey(dataSourceKey);
        source.setDataSourceName(ORIGINAL_SQL_NAME);
        source.setConnectionType("PRIMARY");
        source.setAllowedTables("wf_sql_datasource");
        source.setConnectTimeoutMs(1000);
        source.setQueryTimeoutSeconds(10);
        source.setRevisionNo(1);
        source.setStatus("ENABLED");
        source.setCreateBy("flowable-it");
        source.setChecksum(WorkflowSqlDataSourceService.dataSourceChecksum(source));
        return source;
    }

    /**
     * 接收真实 HTTP 请求并返回 202 状态，供 HTTP 连接器写入状态变量。
     * @param exchange HttpExchange，本机服务收到的真实请求上下文
     * @return void，响应写入后关闭连接
     * @throws IOException 读取请求或写入响应失败
     */
    private void handleHttpStatusRequest(HttpExchange exchange) throws IOException
    {
        try
        {
            httpRequestBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] response = "{\"accepted\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, response.length);
            exchange.getResponseBody().write(response);
        }
        finally
        {
            exchange.close();
        }
    }

    /**
     * 生成本轮不与并发测试冲突的合法流程或决策 key。
     * @param suffix String，场景语义后缀
     * @return String，前缀、场景和无连字符 UUID 组成的稳定测试 key
     */
    private String key(String suffix)
    {
        return PREFIX + suffix + "-" + runId;
    }
}
