package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import com.ruoyi.flowable.engine.WorkflowCompensationLifecycle;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.delegate.JavaDelegate;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 真实引擎执行树/FK 回归；正式 API、MySQL 和 HTTP 副作用验收另见执行报告。 */
class WorkflowTransactionCompensationBusinessTest
{
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void cancelWaitsForEveryCompensationAndRetainsFailedJob(boolean asynchronous) throws Exception
    {
        AtomicInteger reserved = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger compensated = new AtomicInteger();
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:comp-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        configuration.setDatabaseSchemaUpdate("true");
        configuration.setHistory("full");
        configuration.setAsyncExecutorActivate(false);
        configuration.setBeans(Map.of("reserve", (JavaDelegate) execution -> reserved.incrementAndGet(),
                "compensate", (JavaDelegate) execution ->
                {
                    if (asynchronous && attempts.incrementAndGet() == 1) throw new IllegalStateException("temporary failure");
                    compensated.incrementAndGet();
                }));
        WorkflowCompensationLifecycle.configure(configuration);
        ProcessEngine engine = configuration.buildProcessEngine();
        try
        {
            String xml = """
                    <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="urn:compensation-regression">
                    <process id="cancel" isExecutable="true"><startEvent id="start"/>
                    <transaction id="tx"><startEvent id="txStart"/>
                    <serviceTask id="reserve" flowable:async="true" flowable:delegateExpression="${reserve}"/>
                    <boundaryEvent id="compBoundary" attachedToRef="reserve" cancelActivity="false"><compensateEventDefinition/></boundaryEvent>
                    <serviceTask id="compensate" isForCompensation="true" flowable:async="%s" flowable:delegateExpression="${compensate}"/>
                    <serviceTask id="reserveTwo" flowable:delegateExpression="${reserve}"/>
                    <boundaryEvent id="compBoundaryTwo" attachedToRef="reserveTwo" cancelActivity="false"><compensateEventDefinition/></boundaryEvent>
                    <serviceTask id="compensateTwo" isForCompensation="true" flowable:async="%s" flowable:delegateExpression="${compensate}"/>
                    <endEvent id="cancelEnd"><cancelEventDefinition/></endEvent>
                    <sequenceFlow id="t1" sourceRef="txStart" targetRef="reserve"/><sequenceFlow id="t2" sourceRef="reserve" targetRef="reserveTwo"/>
                    <sequenceFlow id="t3" sourceRef="reserveTwo" targetRef="cancelEnd"/>
                    <association id="compLink" sourceRef="compBoundary" targetRef="compensate" associationDirection="One"/>
                    <association id="compLinkTwo" sourceRef="compBoundaryTwo" targetRef="compensateTwo" associationDirection="One"/>
                    </transaction>
                    <boundaryEvent id="canceled" attachedToRef="tx"><cancelEventDefinition/></boundaryEvent>
                    <userTask id="review"/><endEvent id="end"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="tx"/><sequenceFlow id="f2" sourceRef="tx" targetRef="end"/>
                    <sequenceFlow id="f3" sourceRef="canceled" targetRef="review"/><sequenceFlow id="f4" sourceRef="review" targetRef="end"/>
                    </process></definitions>
                    """.formatted(asynchronous, asynchronous);
            engine.getRepositoryService().createDeployment().addString("cancel.bpmn20.xml", xml).deploy();
            String instance = engine.getRuntimeService().startProcessInstanceByKey("cancel").getId();
            var jobs = engine.getManagementService();
            jobs.executeJob(jobs.createJobQuery().processInstanceId(instance).singleResult().getId());
            assertThat(reserved).hasValue(2);
            if (asynchronous)
            {
                assertThat(engine.getTaskService().createTaskQuery().processInstanceId(instance).count()).isZero();
                var compensationJobs = jobs.createJobQuery().processInstanceId(instance).list();
                assertThat(compensationJobs).hasSize(2);
                String compensationJob = compensationJobs.get(0).getId();
                assertThatThrownBy(() -> jobs.executeJob(compensationJob)).hasRootCauseMessage("temporary failure");
                assertThat(engine.getTaskService().createTaskQuery().processInstanceId(instance).count()).isZero();
                assertThat(jobs.createTimerJobQuery().jobId(compensationJob).count()).isEqualTo(1);
                jobs.executeJob(compensationJobs.get(1).getId());
                assertThat(compensated).hasValue(1);
                assertThat(engine.getTaskService().createTaskQuery().processInstanceId(instance).count()).isZero();
                jobs.moveTimerToExecutableJob(compensationJob);
                jobs.executeJob(compensationJob);
            }
            assertThat(compensated).hasValue(2);
            var task = engine.getTaskService().createTaskQuery().processInstanceId(instance).singleResult();
            assertThat(task.getTaskDefinitionKey()).isEqualTo("review");
            assertThat(jobs.createJobQuery().processInstanceId(instance).count()).isZero();
            assertThat(engine.getHistoryService().createHistoricActivityInstanceQuery().processInstanceId(instance)
                    .activityId("compensate").finished().count()).isEqualTo(1);
            engine.getTaskService().complete(task.getId());
            assertThat(engine.getRuntimeService().createExecutionQuery().processInstanceId(instance).count()).isZero();
            assertThat(engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(instance)
                    .finished().count()).isEqualTo(1);
        }
        finally
        {
            engine.close();
            try (var connection = java.sql.DriverManager.getConnection(configuration.getJdbcUrl(), "sa", "");
                    var statement = connection.createStatement()) { statement.execute("SHUTDOWN"); }
        }
    }
}
