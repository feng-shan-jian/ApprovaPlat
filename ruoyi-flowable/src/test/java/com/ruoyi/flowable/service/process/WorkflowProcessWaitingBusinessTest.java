package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessSnapshot;
import com.ruoyi.flowable.config.WorkflowRuntimeProperties;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.runtime.WorkflowRuntimeHealthSnapshot;
import com.ruoyi.flowable.runtime.WorkflowRuntimeMetrics;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import org.junit.jupiter.api.Test;

class WorkflowProcessWaitingBusinessTest
{
    @Test void fiveSecondTimerSurvivesExecutorDowntimeAndResumesWithoutManualTrigger() throws Exception
    {
        try (var support = WorkflowFlowableEngineTestSupport.start("timer-recovery", Map.of()))
        {
            var engine = support.processEngine();
            var deployment = engine.getRepositoryService().createDeployment().addString("timer.bpmn20.xml", """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="urn:timer-recovery">
                  <process id="timerRecovery" isExecutable="true"><startEvent id="start"/>
                    <intermediateCatchEvent id="timer"><timerEventDefinition><timeDuration>PT5S</timeDuration></timerEventDefinition></intermediateCatchEvent>
                    <userTask id="downstream"/><endEvent id="end"/>
                    <sequenceFlow id="a" sourceRef="start" targetRef="timer"/><sequenceFlow id="b" sourceRef="timer" targetRef="downstream"/><sequenceFlow id="c" sourceRef="downstream" targetRef="end"/>
                  </process>
                </definitions>
                """).deploy();
            try
            {
                var instance = engine.getRuntimeService().startProcessInstanceByKey("timerRecovery");
                var job = engine.getManagementService().createTimerJobQuery().processInstanceId(instance.getId()).singleResult();
                assertThat(job).isNotNull();
                Thread.sleep(5300);
                assertThat(engine.getTaskService().createTaskQuery().processInstanceId(instance.getId()).count()).isZero();
                var configuration = (org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
                configuration.getAsyncExecutor().start();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                while (engine.getTaskService().createTaskQuery().processInstanceId(instance.getId()).count() == 0 && System.nanoTime() < deadline)
                    Thread.sleep(100);
                var task = engine.getTaskService().createTaskQuery().processInstanceId(instance.getId()).singleResult();
                assertThat(task).isNotNull();
                assertThat(task.getTaskDefinitionKey()).isEqualTo("downstream");
                assertThat(engine.getManagementService().createTimerJobQuery().jobId(job.getId()).count()).isZero();
                assertThat(engine.getHistoryService().createHistoricActivityInstanceQuery().processInstanceId(instance.getId())
                        .activityId("timer").finished().count()).isOne();
                engine.getTaskService().complete(task.getId());
                assertThat(engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(instance.getId()).finished().count()).isOne();
            }
            finally { engine.getRepositoryService().deleteDeployment(deployment.getId(), true); }
        }
    }

    @Test void readsActualSubscriptionsReceivesAndTimerJobsThenReflectsConsumption()
    {
        try (var support = WorkflowFlowableEngineTestSupport.start("waiting-facts", Map.of()))
        {
            var engine = support.processEngine();
            var identity = mock(WorkflowIdentityResolver.class);
            when(identity.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("100", Set.of()));
            var operations = support.transactionalProxy(new WorkflowEngineOperations(new WorkflowAuthenticationContext(
                    engine.getIdentityService(), new WorkflowIdentityCodec()), new WorkflowExceptionTranslator(), identity));
            var access = mock(WorkflowProcessAccessService.class);
            var metrics = mock(WorkflowRuntimeMetrics.class);
            when(metrics.readHealthSnapshot()).thenReturn(new WorkflowRuntimeHealthSnapshot(true, 0, 0, false, false));
            var service = new WorkflowProcessWaitingService(access, operations, engine.getRuntimeService(),
                    engine.getRepositoryService(), engine.getManagementService(), metrics, new WorkflowRuntimeProperties());
            String xml = """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="urn:waiting">
                  <message id="notice" name="approved"/>
                  <process id="waiting" isExecutable="true">
                    <startEvent id="start"/><parallelGateway id="split"/>
                    <intermediateCatchEvent id="message"><messageEventDefinition messageRef="notice"/></intermediateCatchEvent>
                    <receiveTask id="receive"/><intermediateCatchEvent id="timer"><timerEventDefinition><timeDuration>PT1H</timeDuration></timerEventDefinition></intermediateCatchEvent>
                    <endEvent id="end"/>
                    <sequenceFlow id="a" sourceRef="start" targetRef="split"/>
                    <sequenceFlow id="b" sourceRef="split" targetRef="message"/>
                    <sequenceFlow id="c" sourceRef="split" targetRef="receive"/>
                    <sequenceFlow id="d" sourceRef="split" targetRef="timer"/>
                    <sequenceFlow id="e" sourceRef="message" targetRef="end"/>
                    <sequenceFlow id="f" sourceRef="receive" targetRef="end"/>
                    <sequenceFlow id="g" sourceRef="timer" targetRef="end"/>
                  </process>
                </definitions>
                """;
            var deploy = engine.getRepositoryService().createDeployment().addString("waiting.bpmn20.xml", xml).deploy();
            try
            {
                var instance = engine.getRuntimeService().startProcessInstanceByKey("waiting");
                when(access.requireReadableInstance(instance.getId())).thenReturn(new WorkflowProcessAccessSnapshot(instance.getId(),
                        instance.getProcessDefinitionId(), deploy.getId(), null, "100", Instant.now(), null, null, "RUNNING", "running"));
                var before = service.query(instance.getId());
                assertThat(before.localTimerExecutionReady()).isFalse();
                assertThat(before.waits()).extracting(WorkflowProcessWaitingService.Waiting::type).containsExactly("MESSAGE", "RECEIVE", "TIMER");
                var message = before.waits().get(0);
                assertThat(message.eventName()).isEqualTo("approved");
                engine.getRuntimeService().messageEventReceived("approved", message.executionId());
                engine.getRuntimeService().trigger(before.waits().get(1).executionId());
                assertThat(service.query(instance.getId()).waits()).singleElement().satisfies(wait -> {
                    assertThat(wait.type()).isEqualTo("TIMER");
                    assertThat(wait.dueTime()).isNotNull();
                    assertThat(wait.retries()).isEqualTo(3);
                });
                when(access.requireReadableInstance(instance.getId())).thenThrow(new ServiceException("无权查看", 403));
                assertThatThrownBy(() -> service.query(instance.getId())).isInstanceOf(ServiceException.class);
            }
            finally { engine.getRepositoryService().deleteDeployment(deploy.getId(), true); }
        }
    }
}
