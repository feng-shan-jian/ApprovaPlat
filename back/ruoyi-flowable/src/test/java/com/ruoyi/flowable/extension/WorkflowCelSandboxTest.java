package com.ruoyi.flowable.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * CEL 沙箱静态类型、变量白名单和确定性运行契约测试。
 */
class WorkflowCelSandboxTest
{
    private final WorkflowCelSandbox sandbox = new WorkflowCelSandbox();
    private final ObjectMapper objectMapper = JsonMapper.shared();

    /**
     * 验证白名单标量变量可编译、运行并只写入声明结果变量。
     * @return void，运行结果或规范配置不正确时测试失败
     */
    @Test
    void compilesAndExecutesTypedWhitelistedVariables()
    {
        JsonNode config = read("""
                {
                  "expression":"amount >= 1000.5 && approved",
                  "resultVariable":"eligible",
                  "resultType":"BOOL",
                  "variables":[
                    {"name":"amount","type":"DOUBLE"},
                    {"name":"approved","type":"BOOL"}
                  ]
                }
                """);
        String normalized = sandbox.validateAndNormalizeConfig(config);
        assertThat(normalized).isEqualTo(
                "{\"expression\":\"amount >= 1000.5 && approved\",\"resultType\":\"BOOL\","
                + "\"resultVariable\":\"eligible\",\"variables\":[{\"name\":\"amount\","
                + "\"type\":\"DOUBLE\"},{\"name\":\"approved\",\"type\":\"BOOL\"}]}");

        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.hasVariable("amount")).thenReturn(true);
        when(execution.getVariable("amount")).thenReturn(1200.25D);
        when(execution.hasVariable("approved")).thenReturn(true);
        when(execution.getVariable("approved")).thenReturn(true);
        sandbox.execute(execution, config);

        verify(execution).setVariable("eligible", true);
    }

    /**
     * 验证相同字符串输入不会读取外部状态，重复执行得到相同结果。
     * @return void，两次输出不一致时测试失败
     */
    @Test
    void producesDeterministicOutputForSameActivation()
    {
        JsonNode config = read("""
                {"expression":"prefix + '-' + code","resultVariable":"label",
                 "resultType":"STRING","variables":[
                   {"name":"prefix","type":"STRING"},{"name":"code","type":"STRING"}]}
                """);
        DelegateExecution first = stringExecution("A", "100");
        DelegateExecution second = stringExecution("A", "100");

        sandbox.execute(first, config);
        sandbox.execute(second, config);

        verify(first).setVariable("label", "A-100");
        verify(second).setVariable("label", "A-100");
    }

    /**
     * 验证未声明标识符、文件/进程式成员调用和错误返回类型均在部署编译阶段拒绝。
     * @return void，任一非法表达式被接受时测试失败
     */
    @Test
    void rejectsUnknownIdentifiersAndInvalidResultTypes()
    {
        assertRejected(config("missing + 1", "INT", "[]"), "类型检查失败");
        assertRejected(config("Runtime.getRuntime()", "STRING", "[]"), "类型检查失败");
        assertRejected(config("'yes'", "BOOL", "[]"), "类型检查失败");
    }

    /**
     * 验证保留变量、重复变量、额外执行字段和结果覆盖输入都失败关闭。
     * @return void，任一配置绕过白名单时测试失败
     */
    @Test
    void rejectsReservedDuplicateAndUnknownConfiguration()
    {
        assertRejected(read("""
                {"expression":"initiator","resultVariable":"out","resultType":"STRING",
                 "variables":[{"name":"initiator","type":"STRING"}]}
                """), "保留变量");
        assertRejected(read("""
                {"expression":"value","resultVariable":"out","resultType":"STRING",
                 "variables":[{"name":"value","type":"STRING"},{"name":"value","type":"STRING"}]}
                """), "重复");
        assertRejected(read("""
                {"expression":"value","resultVariable":"value","resultType":"STRING",
                 "variables":[{"name":"value","type":"STRING"}]}
                """), "不能覆盖");
        assertRejected(read("""
                {"expression":"true","resultVariable":"out","resultType":"BOOL",
                 "variables":[],"beanName":"unsafeBean"}
                """), "未允许字段");
    }

    /**
     * 验证运行时缺失变量或任意 Java 对象不能进入 CEL 激活。
     * @return void，非法运行值未阻止时测试失败
     */
    @Test
    void rejectsMissingAndArbitraryRuntimeValues()
    {
        JsonNode config = read("""
                {"expression":"value","resultVariable":"out","resultType":"STRING",
                 "variables":[{"name":"value","type":"STRING"}]}
                """);
        DelegateExecution missing = mock(DelegateExecution.class);
        assertThatThrownBy(() -> sandbox.execute(missing, config))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("变量不存在");

        DelegateExecution arbitrary = mock(DelegateExecution.class);
        when(arbitrary.hasVariable("value")).thenReturn(true);
        when(arbitrary.getVariable("value")).thenReturn(new Object());
        assertThatThrownBy(() -> sandbox.execute(arbitrary, config))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("声明类型 STRING 不一致");
    }

    /**
     * 创建两个字符串变量固定的 Flowable 执行模拟。
     * @param prefix String，前缀变量值
     * @param code String，编码变量值
     * @return DelegateExecution，只暴露两个白名单变量
     */
    private DelegateExecution stringExecution(String prefix, String code)
    {
        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.hasVariable("prefix")).thenReturn(true);
        when(execution.getVariable("prefix")).thenReturn(prefix);
        when(execution.hasVariable("code")).thenReturn(true);
        when(execution.getVariable("code")).thenReturn(code);
        return execution;
    }

    /**
     * 创建无输入变量的 CEL 测试配置。
     * @param expression String，受检表达式
     * @param resultType String，声明结果类型
     * @param variablesJson String，变量数组 JSON
     * @return JsonNode，可直接交给沙箱的配置对象
     */
    private JsonNode config(String expression, String resultType, String variablesJson)
    {
        return read("{\"expression\":\"" + expression.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\",\"resultVariable\":\"out\","
                + "\"resultType\":\"" + resultType + "\",\"variables\":" + variablesJson + "}");
    }

    /**
     * 断言配置被 CEL 部署门禁拒绝且包含稳定业务提示。
     * @param config JsonNode，非法配置
     * @param message String，期望错误摘要
     * @return void，配置被接受或提示不一致时测试失败
     */
    private void assertRejected(JsonNode config, String message)
    {
        assertThatThrownBy(() -> sandbox.validateAndNormalizeConfig(config))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(message);
    }

    /**
     * 使用 Jackson 3 读取测试 JSON。
     * @param json String，测试配置 JSON
     * @return JsonNode，结构化配置节点
     */
    private JsonNode read(String json)
    {
        try
        {
            return objectMapper.readTree(json);
        }
        catch (Exception exception)
        {
            throw new AssertionError("测试 JSON 必须合法", exception);
        }
    }
}
