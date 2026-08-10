package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.mapper.WfDeployExtensionSnapshotMapper;
import com.ruoyi.flowable.extension.WorkflowExtensionChecksum;
import com.ruoyi.flowable.service.model.WorkflowBpmnDocument;
import com.ruoyi.flowable.service.model.WorkflowBpmnService;
import com.ruoyi.flowable.service.model.WorkflowExtensionDeploymentService;
import com.ruoyi.flowable.service.model.WorkflowPreparedExtensionDeployment;
import com.ruoyi.flowable.service.process.WorkflowBpmnEventAuditService;

/**
 * 使用真实 MySQL 与 Flowable 8 验证 BPMN Error、Escalation、回滚、审计和通知闭环。
 */
@SpringBootTest(classes = RuoYiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "spring.datasource.druid.master.url=${FLOWABLE_IT_JDBC_URL}",
            "spring.datasource.druid.master.username=${FLOWABLE_IT_USERNAME}",
            "spring.datasource.druid.master.password=${FLOWABLE_IT_PASSWORD}",
            "spring.data.redis.database=${FLOWABLE_IT_REDIS_DATABASE:15}",
            "token.secret=YnBtbi1ldmVudC1pdC10b2tlbi1zZWNyZXQtYnBtbi1ldmVudC1pdC10b2tlbi1zZWNyZXQtYnBtbi1ldmVudC1pdA==",
            "flowable.database-schema-update=false",
            "flowable.async-executor-activate=false",
            "flowable.async-history-executor-activate=false",
            "spring.quartz.auto-startup=false"
        })
class WorkflowBpmnEventMySqlIT
{
    private static final String PREFIX = "workflow-bpmn-event-it-";

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private WorkflowBpmnService bpmnService;
    @Autowired
    private WorkflowExtensionDeploymentService extensionDeploymentService;
    @Autowired
    private WfDeployExtensionSnapshotMapper snapshotMapper;
    @Autowired
    private WorkflowBpmnEventAuditService auditService;
    @Autowired
    private JdbcTemplate jdbc;

    /** 本轮唯一标识，隔离并发运行和失败重跑。 */
    private final String runId = UUID.randomUUID().toString().replace("-", "");
    /** 本轮真实部署，按外键顺序精确清理。 */
    private final List<Deployment> deployments = new ArrayList<>();

    /**
     * 清理本轮通知、审计、扩展快照和 Flowable 部署，并断言无正式数据残留。
     * @return void，任何本轮数据未清理时测试失败
     */
    @AfterEach
    void tearDown()
    {
        for (Deployment deployment : deployments)
        {
            jdbc.update("delete n from wf_bpmn_event_notification n "
                    + "join wf_bpmn_event_audit a on a.audit_id=n.audit_id "
                    + "where a.deployment_id=?", deployment.getId());
            jdbc.update("delete from wf_bpmn_event_audit where deployment_id=?",
                    deployment.getId());
            snapshotMapper.deleteByDeploymentId(deployment.getId());
            RepositoryService repositoryService = processEngine.getRepositoryService();
            if (repositoryService.createDeploymentQuery()
                    .deploymentId(deployment.getId()).count() == 1L)
            {
                repositoryService.deleteDeployment(deployment.getId(), true);
            }
        }
        jdbc.update("delete n from wf_bpmn_event_notification n "
                + "join wf_bpmn_event_audit a on a.audit_id=n.audit_id "
                + "where a.deployment_id like ?", PREFIX + runId + "%");
        jdbc.update("delete from wf_bpmn_event_audit where deployment_id like ?",
                PREFIX + runId + "%");
        assertThat(jdbc.queryForObject("select count(*) from wf_bpmn_event_audit "
                + "where deployment_id like ?", Integer.class, PREFIX + runId + "%"))
                .isZero();
    }

    /**
     * 验证受控 Error 精确捕获后进入真实人工任务，并保持变量、历史、审计和发起人通知一致。
     * @return void，任一真实状态不一致时测试失败
     */
    @Test
    void capturesErrorAndRoutesToHumanHandlingPath()
    {
        String processKey = key("error-captured");
        Deployment deployment = deploy(processKey,
                authorBpmn(processKey, "ERROR", "APPROVAL_BUSINESS_ERROR", true));
        ProcessInstance instance = processEngine.getRuntimeService().startProcessInstanceByKey(
                processKey, Map.of("initiator", "1", "eventMessage", "库存不足"));

        Task task = singleTask(instance.getId());
        assertThat(task.getTaskDefinitionKey()).isEqualTo("eventHandler");
        assertThat(processEngine.getRuntimeService().getVariable(
                instance.getId(), "wfBpmnEventCode"))
                .isEqualTo("APPROVAL_BUSINESS_ERROR");
        assertThat(jdbc.queryForObject("select match_status from wf_bpmn_event_audit "
                + "where deployment_id=?", String.class, deployment.getId()))
                .isEqualTo("CAPTURED");
        assertThat(jdbc.queryForObject("select count(*) from wf_bpmn_event_notification n "
                + "join wf_bpmn_event_audit a on a.audit_id=n.audit_id "
                + "where a.deployment_id=? and n.recipient_user_id='1' "
                + "and n.read_status='UNREAD'", Integer.class, deployment.getId()))
                .isOne();
        assertThat(historicActivityIds(instance.getId()))
                .contains("raiseEvent", "eventBoundary", "eventHandler")
                .doesNotContain("mainHandler");

        complete(task);
        assertFinished(instance.getId());
    }

    /**
     * 验证升级边界的中断和非中断语义分别取消主路径或并行保留主路径。
     * @return void，任务数量、路径或历史活动不符合标准语义时测试失败
     */
    @Test
    void executesInterruptingAndNonInterruptingEscalation()
    {
        String interruptingKey = key("escalation-interrupting");
        deploy(interruptingKey,
                authorBpmn(interruptingKey, "ESCALATION", "APPROVAL_ESCALATION", true));
        ProcessInstance interrupting = processEngine.getRuntimeService()
                .startProcessInstanceByKey(interruptingKey, Map.of("initiator", "1"));
        assertThat(tasks(interrupting.getId())).singleElement()
                .satisfies(task -> assertThat(task.getTaskDefinitionKey())
                        .isEqualTo("eventHandler"));
        assertThat(historicActivityIds(interrupting.getId())).doesNotContain("mainHandler");
        complete(singleTask(interrupting.getId()));
        assertFinished(interrupting.getId());

        String nonInterruptingKey = key("escalation-non-interrupting");
        deploy(nonInterruptingKey,
                authorBpmn(nonInterruptingKey, "ESCALATION", "APPROVAL_ESCALATION", false));
        ProcessInstance nonInterrupting = processEngine.getRuntimeService()
                .startProcessInstanceByKey(nonInterruptingKey, Map.of("initiator", "1"));
        assertThat(tasks(nonInterrupting.getId())).extracting(Task::getTaskDefinitionKey)
                .containsExactlyInAnyOrder("eventHandler", "mainHandler");
        for (Task task : tasks(nonInterrupting.getId())) complete(task);
        assertFinished(nonInterrupting.getId());
        assertThat(historicActivityIds(nonInterrupting.getId()))
                .contains("eventBoundary", "eventHandler", "mainHandler");
    }

    /**
     * 验证受控产生节点缺少精确边界时在 Flowable 部署前失败且部署数量不变。
     * @return void，非法模型产生部署或快照副作用时测试失败
     */
    @Test
    void rejectsIllegalModelBeforeFlowableDeploymentSideEffect()
    {
        String processKey = key("illegal-boundary");
        String invalid = authorBpmn(processKey, "ERROR", "APPROVAL_BUSINESS_ERROR", true)
                .replace("\"eventType\":\"ERROR\"",
                        "\"eventType\":\"ESCALATION\"")
                .replace("\"eventCode\":\"APPROVAL_BUSINESS_ERROR\"",
                        "\"eventCode\":\"APPROVAL_ESCALATION\"");
        long before = processEngine.getRepositoryService().createDeploymentQuery().count();

        assertThatThrownBy(() ->
        {
            WorkflowBpmnDocument document = bpmnService.validateForSave(
                    invalid.getBytes(StandardCharsets.UTF_8));
            extensionDeploymentService.prepare(document, "1");
        }).isInstanceOf(ServiceException.class)
                .hasMessageContaining("唯一精确匹配边界");

        assertThat(processEngine.getRepositoryService().createDeploymentQuery().count())
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from wf_deploy_extension_snapshot "
                + "where process_key=?", Integer.class, processKey)).isZero();
    }

    /**
     * 验证部署资源被篡改造成未匹配 Error 时引擎事务完全回滚，仅保留独立诊断审计且不通知用户。
     * @return void，流程、历史、变量或通知出现部分提交时测试失败
     */
    @Test
    void rollsBackUnmatchedErrorWithDiagnosticAuditOnly()
    {
        String processKey = key("error-unmatched");
        WorkflowBpmnDocument document = bpmnService.validateForSave(
                authorBpmn(processKey, "ERROR", "APPROVAL_BUSINESS_ERROR", true)
                        .getBytes(StandardCharsets.UTF_8));
        WorkflowPreparedExtensionDeployment prepared =
                extensionDeploymentService.prepare(document, "1");
        // 保留合法 BPMN 拓扑，仅将边界引用改为另一正式根编码，模拟部署资源被篡改后的未匹配事件。
        String tampered = new String(prepared.compiledBpmn(), StandardCharsets.UTF_8)
                .replace("<errorEventDefinition errorRef=\"eventRoot\"",
                        "<errorEventDefinition errorRef=\"tamperedRoot\"")
                .replace("<process id=\"" + processKey + "\"",
                        "<error id=\"tamperedRoot\" name=\"篡改事件\" errorCode=\"TAMPERED_ERROR\"></error>"
                                + "<process id=\"" + processKey + "\"");
        assertThat(tampered).contains("errorRef=\"tamperedRoot\"");
        Deployment deployment = processEngine.getRepositoryService().createDeployment()
                .name(processKey).addBytes(processKey + ".bpmn20.xml",
                        tampered.getBytes(StandardCharsets.UTF_8)).deploy();
        deployments.add(deployment);
        extensionDeploymentService.persist(deployment.getId(), prepared);

        assertThatThrownBy(() -> processEngine.getRuntimeService().startProcessInstanceByKey(
                processKey, Map.of("initiator", "1")))
                .hasMessageContaining("APPROVAL_BUSINESS_ERROR");
        assertThat(processEngine.getRuntimeService().createProcessInstanceQuery()
                .processDefinitionKey(processKey).count()).isZero();
        assertThat(processEngine.getHistoryService().createHistoricProcessInstanceQuery()
                .processDefinitionKey(processKey).count()).isZero();
        assertThat(jdbc.queryForObject("select match_status from wf_bpmn_event_audit "
                + "where deployment_id=?", String.class, deployment.getId()))
                .isEqualTo("UNMATCHED");
        assertThat(jdbc.queryForObject("select count(*) from wf_bpmn_event_notification n "
                + "join wf_bpmn_event_audit a on a.audit_id=n.audit_id "
                + "where a.deployment_id=?", Integer.class, deployment.getId())).isZero();
    }

    /**
     * 验证重复和并发记录同一稳定幂等键时只生成一条审计与一条通知。
     * @return void，并发异常或重复正式数据出现时测试失败
     */
    @Test
    void deduplicatesConcurrentAuditAndNotificationWrites()
    {
        String idempotencyKey = WorkflowExtensionChecksum.sha256(
                PREFIX, runId, "concurrent");
        String concurrentDeploymentId = PREFIX + runId + "-concurrent";
        WorkflowBpmnEventAuditService.RuntimeEvent event =
                new WorkflowBpmnEventAuditService.RuntimeEvent(
                        idempotencyKey, concurrentDeploymentId, "process-concurrent",
                        "definition-concurrent", "execution-concurrent", "raiseEvent",
                        "HTTP", "ERROR", "APPROVAL_BUSINESS_ERROR", "审批业务校验失败",
                        "INITIATOR", "CAPTURED", "eventBoundary", true, "并发触发", "1");
        List<CompletableFuture<Long>> futures = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> CompletableFuture.supplyAsync(() -> auditService.record(event)))
                .toList();
        List<Long> auditIds = futures.stream().map(CompletableFuture::join).toList();

        assertThat(auditIds).allMatch(auditIds.get(0)::equals);
        assertThat(jdbc.queryForObject("select count(*) from wf_bpmn_event_audit "
                + "where idempotency_key=?", Integer.class, idempotencyKey)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from wf_bpmn_event_notification n "
                + "join wf_bpmn_event_audit a on a.audit_id=n.audit_id "
                + "where a.idempotency_key=?", Integer.class, idempotencyKey)).isOne();
    }

    /**
     * 通过正式编译器冻结配置、真实部署并持久化快照。
     * @param processKey String，本轮流程 key
     * @param authorXml String，作者 BPMN XML
     * @return Deployment，真实 Flowable 部署
     */
    private Deployment deploy(String processKey, String authorXml)
    {
        WorkflowBpmnDocument document = bpmnService.validateForSave(
                authorXml.getBytes(StandardCharsets.UTF_8));
        WorkflowPreparedExtensionDeployment prepared =
                extensionDeploymentService.prepare(document, "1");
        bpmnService.validateCompiledDeployment(prepared.compiledBpmn());
        Deployment deployment = processEngine.getRepositoryService().createDeployment()
                .name(processKey).addBytes(processKey + ".bpmn20.xml",
                        prepared.compiledBpmn()).deploy();
        deployments.add(deployment);
        extensionDeploymentService.persist(deployment.getId(), prepared);
        return deployment;
    }

    /**
     * 构造带受控产生器、同编码边界、主路径和真实用户任务处理路径的作者 BPMN。
     * @param processKey String，唯一流程 key
     * @param eventType String，ERROR 或 ESCALATION
     * @param eventCode String，正式目录编码
     * @param interrupting boolean，边界是否中断附着服务任务
     * @return String，可通过正式保存和部署门禁的 BPMN XML
     */
    private String authorBpmn(String processKey, String eventType, String eventCode,
            boolean interrupting)
    {
        String root = "ERROR".equals(eventType)
                ? "<error id=\"eventRoot\" name=\"审批业务校验失败\" errorCode=\""
                    + eventCode + "\"/>"
                : "<escalation id=\"eventRoot\" name=\"审批升级处理\" escalationCode=\""
                    + eventCode + "\"/>";
        String definition = "ERROR".equals(eventType)
                ? "<errorEventDefinition errorRef=\"eventRoot\"/>"
                : "<escalationEventDefinition escalationRef=\"eventRoot\"/>";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                + "xmlns:flowable=\"http://flowable.org/bpmn\" targetNamespace=\"ApprovaPlatIT\">"
                + root + "<process id=\"" + processKey + "\" isExecutable=\"true\">"
                + "<startEvent id=\"start\" flowable:formKey=\"key_1\"/>"
                + "<sequenceFlow id=\"toRaise\" sourceRef=\"start\" targetRef=\"raiseEvent\"/>"
                + "<serviceTask id=\"raiseEvent\" flowable:delegateExpression=\"${workflowExtensionDelegate}\">"
                + "<extensionElements><flowable:field name=\"approvaExtensionKey\" "
                + "stringValue=\"approva.raise-bpmn-event\"/><flowable:field "
                + "name=\"approvaExtensionConfig\"><flowable:string><![CDATA[{\"eventType\":\""
                + eventType + "\",\"eventCode\":\"" + eventCode
                + "\",\"sourceType\":\"SERVICE_TASK\",\"operator\":\"ALWAYS\","
                + "\"messageVariable\":\"eventMessage\"}]]></flowable:string></flowable:field>"
                + "</extensionElements></serviceTask>"
                + "<boundaryEvent id=\"eventBoundary\" attachedToRef=\"raiseEvent\" cancelActivity=\""
                + interrupting + "\">" + definition + "</boundaryEvent>"
                + "<sequenceFlow id=\"boundaryToHandler\" sourceRef=\"eventBoundary\" targetRef=\"eventHandler\"/>"
                + userTask("eventHandler", "异常人工处理")
                + "<sequenceFlow id=\"eventToEnd\" sourceRef=\"eventHandler\" targetRef=\"end\"/>"
                + "<sequenceFlow id=\"mainToHandler\" sourceRef=\"raiseEvent\" targetRef=\"mainHandler\"/>"
                + userTask("mainHandler", "主路径审批")
                + "<sequenceFlow id=\"mainToEnd\" sourceRef=\"mainHandler\" targetRef=\"end\"/>"
                + "<endEvent id=\"end\"/></process></definitions>";
    }

    /**
     * 构造带固定系统审计监听器的真实用户任务。
     * @param id String，任务 definition key
     * @param name String，用户可见任务名称
     * @return String，UserTask BPMN 片段
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

    /** @param suffix String，场景后缀；@return String，本轮唯一合法流程 key。 */
    private String key(String suffix)
    {
        return PREFIX + suffix + "-" + runId;
    }

    /** @param processInstanceId String，实例主键；@return List&lt;Task&gt;，当前全部任务。 */
    private List<Task> tasks(String processInstanceId)
    {
        return processEngine.getTaskService().createTaskQuery()
                .processInstanceId(processInstanceId).list();
    }

    /** @param processInstanceId String，实例主键；@return Task，唯一活动任务。 */
    private Task singleTask(String processInstanceId)
    {
        return processEngine.getTaskService().createTaskQuery()
                .processInstanceId(processInstanceId).singleResult();
    }

    /** @param task Task，待完成真实任务；@return void，认证上下文始终清理。 */
    private void complete(Task task)
    {
        processEngine.getIdentityService().setAuthenticatedUserId("1");
        try
        {
            processEngine.getTaskService().complete(task.getId());
        }
        finally
        {
            processEngine.getIdentityService().setAuthenticatedUserId(null);
        }
    }

    /** @param processInstanceId String，实例主键；@return void，运行态和历史终态必须一致。 */
    private void assertFinished(String processInstanceId)
    {
        assertThat(processEngine.getRuntimeService().createProcessInstanceQuery()
                .processInstanceId(processInstanceId).count()).isZero();
        assertThat(processEngine.getHistoryService().createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).finished().count()).isOne();
    }

    /** @param processInstanceId String，实例主键；@return List&lt;String&gt;，真实历史活动标识。 */
    private List<String> historicActivityIds(String processInstanceId)
    {
        return processEngine.getHistoryService().createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId).list().stream()
                .map(activity -> activity.getActivityId()).toList();
    }
}
