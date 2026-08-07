package com.ruoyi.flowable.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.ruoyi.flowable.domain.WfBpmnEventCode;
import com.ruoyi.flowable.service.model.WorkflowBpmnEventCodeService;
import com.ruoyi.flowable.service.process.WorkflowBpmnEventRuntimeService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 受控 BPMN 事件处理器配置冻结、条件和异常边界测试。
 */
class WorkflowRaiseBpmnEventHandlerTest
{
    private WorkflowBpmnEventCodeService codeService;
    private WorkflowBpmnEventRuntimeService runtimeService;
    private WorkflowRaiseBpmnEventHandler handler;
    private DelegateExecution execution;

    /** @return void，每个用例创建独立依赖。 */
    @BeforeEach
    void setUp()
    {
        codeService = mock(WorkflowBpmnEventCodeService.class);
        runtimeService = mock(WorkflowBpmnEventRuntimeService.class);
        handler = new WorkflowRaiseBpmnEventHandler(codeService, runtimeService);
        execution = mock(DelegateExecution.class);
    }

    /**
     * 验证作者编码只能从启用目录解析，并把名称与通知策略冻结进快照。
     * @return void，规范字段或目录调用漂移时失败
     * @throws Exception JSON 解析失败时测试失败
     */
    @Test
    void freezesEnabledCatalogMetadata() throws Exception
    {
        WfBpmnEventCode code = new WfBpmnEventCode();
        code.setEventName("库存校验失败");
        code.setNotificationPolicy("INITIATOR");
        when(codeService.requireEnabled("ERROR", "INVENTORY_INVALID")).thenReturn(code);

        String normalized = handler.validateAndNormalizeConfig(JsonMapper.shared().readTree("""
                {"eventType":"ERROR","eventCode":"INVENTORY_INVALID",
                 "sourceType":"SQL","operator":"EQUALS",
                 "conditionVariable":"inventoryState","expectedValue":"INVALID"}
                """));
        JsonNode frozen = JsonMapper.shared().readTree(normalized);

        assertThat(frozen.path("eventName").asText()).isEqualTo("库存校验失败");
        assertThat(frozen.path("notificationPolicy").asText()).isEqualTo("INITIATOR");
        assertThat(frozen.path("sourceType").asText()).isEqualTo("SQL");
        verify(codeService).requireEnabled("ERROR", "INVENTORY_INVALID");
    }

    /**
     * 验证条件不满足时零副作用，满足时只调用显式运行产生器。
     * @return void，条件读取或调用次数漂移时失败
     * @throws Exception JSON 解析失败时测试失败
     */
    @Test
    void raisesOnlyWhenControlledConditionMatches() throws Exception
    {
        JsonNode frozen = JsonMapper.shared().readTree("""
                {"eventType":"ESCALATION","eventCode":"APPROVAL_ESCALATION",
                 "eventName":"审批升级处理","notificationPolicy":"NONE",
                 "sourceType":"DMN","operator":"TRUE","conditionVariable":"needsEscalation"}
                """);
        when(execution.getVariable("needsEscalation")).thenReturn(false, true);

        handler.execute(execution, frozen);
        verify(runtimeService, never()).raise(any(), any());

        handler.execute(execution, frozen);
        ArgumentCaptor<WorkflowBpmnEventRuntimeService.FrozenEvent> captor =
                ArgumentCaptor.forClass(WorkflowBpmnEventRuntimeService.FrozenEvent.class);
        verify(runtimeService).raise(org.mockito.ArgumentMatchers.eq(execution), captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("ESCALATION");
        assertThat(captor.getValue().sourceType()).isEqualTo("DMN");
    }

    /**
     * 验证技术异常不会被转换为 BPMN Error，仍以原异常失败并交给 Flowable 作业重试。
     * @return void，异常类型被吞掉或替换时失败
     * @throws Exception JSON 解析失败时测试失败
     */
    @Test
    void neverConvertsOrdinaryJavaException() throws Exception
    {
        JsonNode frozen = JsonMapper.shared().readTree("""
                {"eventType":"ERROR","eventCode":"APPROVAL_BUSINESS_ERROR",
                 "eventName":"审批业务校验失败","notificationPolicy":"NONE",
                 "sourceType":"SERVICE_TASK","operator":"ALWAYS"}
                """);
        IllegalStateException technicalFailure = new IllegalStateException("database unavailable");
        org.mockito.Mockito.doThrow(technicalFailure).when(runtimeService).raise(any(), any());

        assertThatThrownBy(() -> handler.execute(execution, frozen)).isSameAs(technicalFailure);
    }

    /**
     * 验证作者配置和部署快照都拒绝未知字段，防止绕过 Schema 注入未受控语义。
     * @return void，未知字段被接受时测试失败
     * @throws Exception JSON 解析失败时测试失败
     */
    @Test
    void rejectsUnknownAuthorAndFrozenFields() throws Exception
    {
        assertThatThrownBy(() -> handler.validateAndNormalizeConfig(
                JsonMapper.shared().readTree("""
                        {"eventType":"ERROR","eventCode":"APPROVAL_BUSINESS_ERROR",
                         "sourceType":"SERVICE_TASK","operator":"ALWAYS","retry":true}
                        """)))
                .isInstanceOf(com.ruoyi.common.exception.ServiceException.class)
                .hasMessageContaining("不受支持的字段");

        assertThatThrownBy(() -> handler.validateAndNormalizeConfig(
                JsonMapper.shared().readTree("""
                        {"eventType":"ERROR","eventCode":"APPROVAL_BUSINESS_ERROR",
                         "eventName":"审批业务校验失败","notificationPolicy":"NONE",
                         "sourceType":"SERVICE_TASK","operator":"ALWAYS","stackTrace":"x"}
                        """)))
                .isInstanceOf(com.ruoyi.common.exception.ServiceException.class)
                .hasMessageContaining("不受支持的字段");
    }
}
