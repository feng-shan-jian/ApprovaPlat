package com.ruoyi.flowable.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 内置设置变量处理器的配置边界和真实 Flowable 变量写入测试。
 */
class WorkflowSetVariableJavaHandlerTest
{
    private final WorkflowSetVariableJavaHandler handler =
            new WorkflowSetVariableJavaHandler();

    /**
     * 验证字段顺序和空白被规范化，字符串常量写入指定流程变量。
     * @return 无返回值；规范化或写入行为漂移时测试失败
     * @throws Exception 测试 JSON 无法解析时抛出
     */
    @Test
    void normalizesAndWritesStringVariable() throws Exception
    {
        JsonNode config = JsonMapper.shared().readTree(
                "{\"value\":\"approved\",\"targetVariable\":\" result \"}");
        assertThat(handler.validateAndNormalizeConfig(config))
                .isEqualTo("{\"targetVariable\":\"result\",\"value\":\"approved\"}");

        DelegateExecution execution = mock(DelegateExecution.class);
        handler.execute(execution, config);

        verify(execution).setVariable("result", "approved");
    }

    /**
     * 验证整数、小数和布尔值分别使用稳定 Java 标量类型写入引擎。
     * @return 无返回值；数值或布尔类型转换漂移时测试失败
     * @throws Exception 测试 JSON 无法解析时抛出
     */
    @Test
    void writesTypedScalarValues() throws Exception
    {
        DelegateExecution execution = mock(DelegateExecution.class);

        handler.execute(execution, JsonMapper.shared().readTree(
                "{\"targetVariable\":\"count\",\"value\":12}"));
        handler.execute(execution, JsonMapper.shared().readTree(
                "{\"targetVariable\":\"ratio\",\"value\":1.5}"));
        handler.execute(execution, JsonMapper.shared().readTree(
                "{\"targetVariable\":\"enabled\",\"value\":true}"));

        verify(execution).setVariable("count", 12L);
        verify(execution).setVariable("ratio", 1.5D);
        verify(execution).setVariable("enabled", true);
    }

    /**
     * 验证额外字段、复合值、非法变量名和系统保留前缀均被 400 门禁拒绝且零写入。
     * @return 无返回值；任一非法配置未被拒绝时测试失败
     * @throws Exception 测试 JSON 无法解析时抛出
     */
    @Test
    void rejectsUnsafeConfigurationsWithoutSideEffects() throws Exception
    {
        DelegateExecution execution = mock(DelegateExecution.class);
        assertBadRequest("{\"targetVariable\":\"result\",\"value\":true,\"extra\":1}");
        assertBadRequest("{\"targetVariable\":\"result\",\"value\":{}}");
        assertBadRequest("{\"targetVariable\":\"1result\",\"value\":true}");
        assertBadRequest("{\"targetVariable\":\"WF_INTERNAL\",\"value\":true}");
        assertBadRequest("{\"targetVariable\":\"__secret\",\"value\":true}");

        verifyNoInteractions(execution);
    }

    /**
     * 断言 JSON 配置被处理器以稳定 400 业务异常拒绝。
     * @param json String，待验证配置 JSON
     * @return 无返回值；异常类型或状态码不一致时断言失败
     * @throws Exception 测试 JSON 无法解析时抛出
     */
    private void assertBadRequest(String json) throws Exception
    {
        assertThatThrownBy(() -> handler.validateAndNormalizeConfig(
                JsonMapper.shared().readTree(json)))
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
