package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.runtime.Execution;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.ruoyi.RuoYiApplication;

/**
 * 使用真实 MySQL 和 Flowable 8 验证高级 BPMN 执行语义。
 */
@SpringBootTest(classes = RuoYiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "spring.datasource.druid.master.url=${FLOWABLE_IT_JDBC_URL}",
            "spring.datasource.druid.master.username=${FLOWABLE_IT_USERNAME}",
            "spring.datasource.druid.master.password=${FLOWABLE_IT_PASSWORD}",
            "spring.data.redis.database=${FLOWABLE_IT_REDIS_DATABASE:15}",
            "token.secret=d29ya2Zsb3ctYWR2YW5jZWQtYnBtbi1pdC10b2tlbi1zZWNyZXQtd29ya2Zsb3ctYWR2YW5jZWQtYnBtbi1pdC10b2tlbi1zZWNyZXQ=",
            "flowable.database-schema-update=false",
            "flowable.async-executor-activate=false",
            "flowable.async-history-executor-activate=false",
            "spring.quartz.auto-startup=false"
        })
class WorkflowAdvancedBpmnMySqlIT
{
    @Autowired
    private ProcessEngine processEngine;

    /**
     * 验证并行、包容网关、手工任务、嵌入子流程和事务均按真实执行树结束。
     * @return void，分支未执行、汇聚错误或容器活动缺失时测试失败
     */
    @Test
    void executesGatewaysManualTasksEmbeddedSubProcessAndTransaction()
    {
        String processKey = uniqueKey("advancedSync");
        Deployment deployment = deploy(processKey, synchronousAdvancedBpmn(processKey));
        try
        {
            var instance = processEngine.getRuntimeService().startProcessInstanceByKey(
                    processKey, Map.of("routeA", true, "routeB", true));
            assertFinished(instance.getId());
            assertHistoricActivities(instance.getId(), "parallelA", "parallelB", "inclusiveA",
                    "inclusiveB", "embeddedWork", "transactionWork");
        }
        finally
        {
            deleteDeployment(deployment);
        }
    }

    /**
     * 验证事件网关只消费先到消息分支，并取消同组定时器等待。
     * @return void，消息订阅、竞争分支或定时器清理不符合事件网关语义时测试失败
     */
    @Test
    void resolvesEventBasedGatewayByFirstMessage()
    {
        String runId = UUID.randomUUID().toString().replace("-", "");
        String processKey = "eventGateway" + runId;
        String messageName = "event-gateway-message-" + runId;
        Deployment deployment = deploy(processKey, eventGatewayBpmn(processKey, messageName));
        try
        {
            RuntimeService runtimeService = processEngine.getRuntimeService();
            var instance = runtimeService.startProcessInstanceByKey(processKey);
            Execution subscription = runtimeService.createExecutionQuery()
                    .processInstanceId(instance.getId())
                    .messageEventSubscriptionName(messageName).singleResult();
            assertThat(subscription).isNotNull();
            assertThat(processEngine.getManagementService().createTimerJobQuery()
                    .processInstanceId(instance.getId()).count()).isOne();

            runtimeService.messageEventReceived(messageName, subscription.getId());

            assertFinished(instance.getId());
            assertHistoricActivities(instance.getId(), "messagePath");
            assertThat(historicActivityIds(instance.getId())).doesNotContain("timerPath");
            assertThat(processEngine.getManagementService().createTimerJobQuery()
                    .processInstanceId(instance.getId()).count()).isZero();
        }
        finally
        {
            deleteDeployment(deployment);
        }
    }

    /**
     * 验证非中断信号事件子流程独立执行，主 ReceiveTask 保持等待并可继续完成。
     * @return void，事件子流程中断主路径、信号抛出缺失或接收任务无法继续时测试失败
     */
    @Test
    void executesNonInterruptingEventSubProcessAndSignalThrow()
    {
        String runId = UUID.randomUUID().toString().replace("-", "");
        String processKey = "eventSubProcess" + runId;
        String incomingSignal = "incoming-" + runId;
        String outgoingSignal = "outgoing-" + runId;
        Deployment deployment = deploy(processKey,
                eventSubProcessBpmn(processKey, incomingSignal, outgoingSignal));
        try
        {
            RuntimeService runtimeService = processEngine.getRuntimeService();
            var instance = runtimeService.startProcessInstanceByKey(processKey);
            Execution signalSubscription = runtimeService.createExecutionQuery()
                    .processInstanceId(instance.getId())
                    .signalEventSubscriptionName(incomingSignal).singleResult();
            assertThat(signalSubscription).isNotNull();

            runtimeService.signalEventReceived(incomingSignal, signalSubscription.getId());
            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceId(instance.getId()).count()).isOne();
            assertHistoricActivities(instance.getId(), "eventWork", "signalThrow");

            Execution receive = runtimeService.createExecutionQuery()
                    .processInstanceId(instance.getId()).activityId("receiveMain").singleResult();
            assertThat(receive).isNotNull();
            runtimeService.trigger(receive.getId());
            assertFinished(instance.getId());
        }
        finally
        {
            deleteDeployment(deployment);
        }
    }

    /**
     * 验证事务 Cancel End Event 触发已完成活动的补偿处理并沿取消边界结束。
     * @return void，补偿处理未执行、取消边界未捕获或实例未结束时测试失败
     */
    @Test
    void executesCompensationWhenTransactionCancels()
    {
        String processKey = uniqueKey("compensation");
        Deployment deployment = deploy(processKey, compensationBpmn(processKey));
        try
        {
            var instance = processEngine.getRuntimeService().startProcessInstanceByKey(processKey);
            assertFinished(instance.getId());
            assertHistoricActivities(instance.getId(), "book", "undoBooking", "cancelBoundary",
                    "afterCancel");
        }
        finally
        {
            deleteDeployment(deployment);
        }
    }

    /**
     * 部署一份本轮唯一的 UTF-8 BPMN 资源。
     * @param processKey String，唯一流程定义 key
     * @param xml String，完整 BPMN XML
     * @return Deployment，Flowable 真实部署结果
     */
    private Deployment deploy(String processKey, String xml)
    {
        return processEngine.getRepositoryService().createDeployment().name(processKey)
                .addBytes(processKey + ".bpmn20.xml", xml.getBytes(StandardCharsets.UTF_8))
                .deploy();
    }

    /**
     * 删除本测试部署并级联清理运行和历史数据。
     * @param deployment Deployment，待清理真实部署
     * @return void，部署不存在时不重复删除
     */
    private void deleteDeployment(Deployment deployment)
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        if (deployment != null && repositoryService.createDeploymentQuery()
                .deploymentId(deployment.getId()).count() == 1L)
        {
            repositoryService.deleteDeployment(deployment.getId(), true);
        }
    }

    /**
     * 断言流程实例已从运行时移除并在历史表记录完成时间。
     * @param processInstanceId String，真实流程实例主键
     * @return void，运行态或历史终态不一致时测试失败
     */
    private void assertFinished(String processInstanceId)
    {
        assertThat(processEngine.getRuntimeService().createProcessInstanceQuery()
                .processInstanceId(processInstanceId).count()).isZero();
        assertThat(processEngine.getHistoryService().createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).finished().count()).isOne();
    }

    /**
     * 断言指定活动均真实出现在 Flowable 历史活动表。
     * @param processInstanceId String，真实流程实例主键
     * @param activityIds String[]，必须出现的 BPMN 活动标识
     * @return void，任一活动没有执行证据时测试失败
     */
    private void assertHistoricActivities(String processInstanceId, String... activityIds)
    {
        assertThat(historicActivityIds(processInstanceId)).contains(activityIds);
    }

    /**
     * 查询实例全部历史活动标识，供分支与补偿语义对账。
     * @param processInstanceId String，真实流程实例主键
     * @return List&lt;String&gt;，按历史查询结果返回的活动标识
     */
    private List<String> historicActivityIds(String processInstanceId)
    {
        HistoryService historyService = processEngine.getHistoryService();
        return historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId).list().stream()
                .map(activity -> activity.getActivityId()).toList();
    }

    /**
     * 生成不与并行测试冲突的合法流程 key。
     * @param prefix String，场景前缀
     * @return String，前缀加无连字符 UUID
     */
    private String uniqueKey(String prefix)
    {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 构造同步高级元素流程。
     * @param processKey String，唯一流程 key
     * @return String，可部署 BPMN XML
     */
    private String synchronousAdvancedBpmn(String processKey)
    {
        return definitions("<process id=\"" + processKey + "\" isExecutable=\"true\">"
                + "<startEvent id=\"start\"/><sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"parallelSplit\"/>"
                + "<parallelGateway id=\"parallelSplit\"/><sequenceFlow id=\"f2\" sourceRef=\"parallelSplit\" targetRef=\"parallelA\"/>"
                + "<sequenceFlow id=\"f3\" sourceRef=\"parallelSplit\" targetRef=\"parallelB\"/><manualTask id=\"parallelA\"/>"
                + "<manualTask id=\"parallelB\"/><sequenceFlow id=\"f4\" sourceRef=\"parallelA\" targetRef=\"parallelJoin\"/>"
                + "<sequenceFlow id=\"f5\" sourceRef=\"parallelB\" targetRef=\"parallelJoin\"/><parallelGateway id=\"parallelJoin\"/>"
                + "<sequenceFlow id=\"f6\" sourceRef=\"parallelJoin\" targetRef=\"inclusiveSplit\"/><inclusiveGateway id=\"inclusiveSplit\"/>"
                + "<sequenceFlow id=\"f7\" sourceRef=\"inclusiveSplit\" targetRef=\"inclusiveA\"><conditionExpression xsi:type=\"tFormalExpression\"><![CDATA[${routeA}]]></conditionExpression></sequenceFlow>"
                + "<sequenceFlow id=\"f8\" sourceRef=\"inclusiveSplit\" targetRef=\"inclusiveB\"><conditionExpression xsi:type=\"tFormalExpression\"><![CDATA[${routeB}]]></conditionExpression></sequenceFlow>"
                + "<manualTask id=\"inclusiveA\"/><manualTask id=\"inclusiveB\"/><sequenceFlow id=\"f9\" sourceRef=\"inclusiveA\" targetRef=\"inclusiveJoin\"/>"
                + "<sequenceFlow id=\"f10\" sourceRef=\"inclusiveB\" targetRef=\"inclusiveJoin\"/><inclusiveGateway id=\"inclusiveJoin\"/>"
                + "<sequenceFlow id=\"f11\" sourceRef=\"inclusiveJoin\" targetRef=\"embedded\"/>"
                + "<subProcess id=\"embedded\"><startEvent id=\"embeddedStart\"/><sequenceFlow id=\"ef1\" sourceRef=\"embeddedStart\" targetRef=\"embeddedWork\"/>"
                + "<manualTask id=\"embeddedWork\"/><sequenceFlow id=\"ef2\" sourceRef=\"embeddedWork\" targetRef=\"embeddedEnd\"/><endEvent id=\"embeddedEnd\"/></subProcess>"
                + "<sequenceFlow id=\"f12\" sourceRef=\"embedded\" targetRef=\"transaction\"/>"
                + "<transaction id=\"transaction\"><startEvent id=\"transactionStart\"/><sequenceFlow id=\"tf1\" sourceRef=\"transactionStart\" targetRef=\"transactionWork\"/>"
                + "<manualTask id=\"transactionWork\"/><sequenceFlow id=\"tf2\" sourceRef=\"transactionWork\" targetRef=\"transactionEnd\"/><endEvent id=\"transactionEnd\"/></transaction>"
                + "<sequenceFlow id=\"f13\" sourceRef=\"transaction\" targetRef=\"end\"/><endEvent id=\"end\"/></process>");
    }

    /**
     * 构造消息与长定时器竞争的事件网关流程。
     * @param processKey String，唯一流程 key
     * @param messageName String，唯一消息事件名
     * @return String，可部署 BPMN XML
     */
    private String eventGatewayBpmn(String processKey, String messageName)
    {
        return definitions("<message id=\"eventMessage\" name=\"" + messageName + "\"/>"
                + "<process id=\"" + processKey + "\" isExecutable=\"true\"><startEvent id=\"start\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"gateway\"/><eventBasedGateway id=\"gateway\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"gateway\" targetRef=\"messageCatch\"/>"
                + "<intermediateCatchEvent id=\"messageCatch\"><messageEventDefinition messageRef=\"eventMessage\"/></intermediateCatchEvent>"
                + "<sequenceFlow id=\"f3\" sourceRef=\"messageCatch\" targetRef=\"messagePath\"/><manualTask id=\"messagePath\"/>"
                + "<sequenceFlow id=\"f4\" sourceRef=\"messagePath\" targetRef=\"end\"/>"
                + "<sequenceFlow id=\"f5\" sourceRef=\"gateway\" targetRef=\"timerCatch\"/>"
                + "<intermediateCatchEvent id=\"timerCatch\"><timerEventDefinition><timeDuration>PT1H</timeDuration></timerEventDefinition></intermediateCatchEvent>"
                + "<sequenceFlow id=\"f6\" sourceRef=\"timerCatch\" targetRef=\"timerPath\"/><manualTask id=\"timerPath\"/>"
                + "<sequenceFlow id=\"f7\" sourceRef=\"timerPath\" targetRef=\"end\"/><endEvent id=\"end\"/></process>");
    }

    /**
     * 构造带非中断信号事件子流程和信号抛出的 ReceiveTask 流程。
     * @param processKey String，唯一流程 key
     * @param incomingSignal String，启动事件子流程的信号名
     * @param outgoingSignal String，事件子流程抛出的信号名
     * @return String，可部署 BPMN XML
     */
    private String eventSubProcessBpmn(String processKey, String incomingSignal,
            String outgoingSignal)
    {
        return definitions("<signal id=\"incomingSignal\" name=\"" + incomingSignal + "\"/>"
                + "<signal id=\"outgoingSignal\" name=\"" + outgoingSignal + "\"/>"
                + "<process id=\"" + processKey + "\" isExecutable=\"true\"><startEvent id=\"start\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"receiveMain\"/><receiveTask id=\"receiveMain\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"receiveMain\" targetRef=\"end\"/><endEvent id=\"end\"/>"
                + "<subProcess id=\"eventSub\" triggeredByEvent=\"true\"><startEvent id=\"eventStart\" isInterrupting=\"false\">"
                + "<signalEventDefinition signalRef=\"incomingSignal\"/></startEvent><manualTask id=\"eventWork\"/>"
                + "<intermediateThrowEvent id=\"signalThrow\"><signalEventDefinition signalRef=\"outgoingSignal\"/></intermediateThrowEvent>"
                + "<endEvent id=\"eventEnd\"/><sequenceFlow id=\"ef1\" sourceRef=\"eventStart\" targetRef=\"eventWork\"/>"
                + "<sequenceFlow id=\"ef2\" sourceRef=\"eventWork\" targetRef=\"signalThrow\"/>"
                + "<sequenceFlow id=\"ef3\" sourceRef=\"signalThrow\" targetRef=\"eventEnd\"/></subProcess></process>");
    }

    /**
     * 构造事务取消后执行补偿处理器并沿取消边界结束的流程。
     * @param processKey String，唯一流程 key
     * @return String，可部署 BPMN XML
     */
    private String compensationBpmn(String processKey)
    {
        return definitions("<process id=\"" + processKey + "\" isExecutable=\"true\"><startEvent id=\"start\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"tx\"/><transaction id=\"tx\">"
                + "<startEvent id=\"txStart\"/><sequenceFlow id=\"tf1\" sourceRef=\"txStart\" targetRef=\"book\"/>"
                + "<manualTask id=\"book\"/><boundaryEvent id=\"bookCompensation\" attachedToRef=\"book\" cancelActivity=\"false\">"
                + "<compensateEventDefinition/></boundaryEvent><manualTask id=\"undoBooking\" isForCompensation=\"true\"/>"
                + "<sequenceFlow id=\"tf2\" sourceRef=\"book\" targetRef=\"cancelEnd\"/><endEvent id=\"cancelEnd\"><cancelEventDefinition/></endEvent>"
                + "<association id=\"compensationAssociation\" sourceRef=\"bookCompensation\" "
                + "targetRef=\"undoBooking\" associationDirection=\"One\"/></transaction>"
                + "<boundaryEvent id=\"cancelBoundary\" attachedToRef=\"tx\"><cancelEventDefinition/></boundaryEvent>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"cancelBoundary\" targetRef=\"afterCancel\"/><manualTask id=\"afterCancel\"/>"
                + "<sequenceFlow id=\"f3\" sourceRef=\"afterCancel\" targetRef=\"end\"/><endEvent id=\"end\"/></process>");
    }

    /**
     * 包装统一 Definitions 根节点并声明条件表达式所需 xsi 命名空间。
     * @param body String，definitions 内部 BPMN 正文
     * @return String，完整 BPMN XML
     */
    private String definitions(String body)
    {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                + "targetNamespace=\"urn:approvaplat:advanced-it\">" + body + "</definitions>";
    }
}
