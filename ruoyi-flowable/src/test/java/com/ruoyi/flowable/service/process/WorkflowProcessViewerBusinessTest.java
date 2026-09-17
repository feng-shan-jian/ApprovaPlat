package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessSnapshot;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import com.ruoyi.system.service.ISysUserService;

class WorkflowProcessViewerBusinessTest
{
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void eventRaceShowsOnlyWinnerAsCompleted(boolean messageWins)
    {
        try (var support = WorkflowFlowableEngineTestSupport.start("viewer-race", Map.of()))
        {
            var engine = support.processEngine();
            var deployment = engine.getRepositoryService().createDeployment().addString("race.bpmn20.xml", """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="urn:viewer">
                <message id="notice" name="notice"/><signal id="go" name="go"/>
                <process id="viewerRace" isExecutable="true"><startEvent id="start"/><eventBasedGateway id="race"/>
                  <intermediateCatchEvent id="message"><messageEventDefinition messageRef="notice"/></intermediateCatchEvent>
                  <intermediateCatchEvent id="signal"><signalEventDefinition signalRef="go"/></intermediateCatchEvent><endEvent id="end"/>
                  <sequenceFlow id="a" sourceRef="start" targetRef="race"/><sequenceFlow id="b" sourceRef="race" targetRef="message"/>
                  <sequenceFlow id="c" sourceRef="race" targetRef="signal"/><sequenceFlow id="messageWin" sourceRef="message" targetRef="end"/>
                  <sequenceFlow id="signalWin" sourceRef="signal" targetRef="end"/></process></definitions>
                """).deploy();
            var instance = engine.getRuntimeService().startProcessInstanceByKey("viewerRace");
            if (messageWins)
                engine.getRuntimeService().messageEventReceived("notice", engine.getRuntimeService().createExecutionQuery()
                        .processInstanceId(instance.getId()).messageEventSubscriptionName("notice").singleResult().getId());
            else engine.getRuntimeService().signalEventReceived("go");
            var projection = new WorkflowProcessHistoryProjection(engine.getHistoryService(), engine.getTaskService(),
                    engine.getRepositoryService(), mock(ISysUserService.class));
            var access = new WorkflowProcessAccessSnapshot(instance.getId(), instance.getProcessDefinitionId(), deployment.getId(),
                    null, "100", Instant.now(), Instant.now(), null, "COMPLETED", "completed", null);
            var viewer = projection.projectPresentation(projection.loadHistory(access), access, false).viewer();
            assertThat(viewer.finishedActivityIds()).contains(messageWins ? "message" : "signal")
                    .doesNotContain(messageWins ? "signal" : "message");
            assertThat(viewer.finishedSequenceFlowIds()).contains(messageWins ? "messageWin" : "signalWin")
                    .doesNotContain(messageWins ? "signalWin" : "messageWin");
            assertThat(viewer.cancelledActivityIds()).containsExactly(messageWins ? "signal" : "message");
            assertThat(viewer.activityInstances()).anySatisfy(activity -> {
                assertThat(activity.activityId()).isEqualTo(messageWins ? "signal" : "message");
                assertThat(activity.state()).isEqualTo("RACE_CANCELLED");
            });
        }
    }
}
