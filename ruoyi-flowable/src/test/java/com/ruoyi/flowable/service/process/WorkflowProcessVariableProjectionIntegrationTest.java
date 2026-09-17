package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WorkflowHistoricVariableMapper;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 同一组真实引擎写入/生产 Mapper/REPEATABLE_READ 判据同时在 H2 和专用 MySQL 运行。 */
class WorkflowProcessVariableProjectionIntegrationTest
{
    protected WorkflowFlowableEngineTestSupport support;
    private ProcessEngine engine;
    private WorkflowHistoricVariableMapper mapper;
    private WorkflowProcessVariableProjection projection;
    private WorkflowEngineOperations operations;
    private ProcessInstance instance;
    private List<Task> tasks;

    protected WorkflowFlowableEngineTestSupport startDatabase() throws Exception
    {
        return WorkflowFlowableEngineTestSupport.start("variable-projection", Map.of());
    }

    protected void closeDatabase() { }

    @BeforeEach
    void setUp() throws Exception
    {
        support = startDatabase();
        engine = support.processEngine();
        var configuration = new org.apache.ibatis.session.Configuration(new Environment("projection",
                new SpringManagedTransactionFactory(), support.dataSource()));
        String resource = "mapper/flowable/WorkflowHistoricVariableMapper.xml";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource))
        {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        mapper = spy(new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration))
                .getMapper(WorkflowHistoricVariableMapper.class));
        projection = new WorkflowProcessVariableProjection(mapper);
        operations = support.transactionalProxy(new WorkflowEngineOperations(
                mock(WorkflowAuthenticationContext.class), new WorkflowExceptionTranslator(), mock(WorkflowIdentityResolver.class)));
        engine.getRepositoryService().createDeployment().addString("projection.bpmn20.xml", """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="urn:projection">
                  <process id="projection" isExecutable="true"><startEvent id="start"/>
                    <parallelGateway id="split"/><userTask id="a"/><userTask id="b"/><endEvent id="end"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="split"/>
                    <sequenceFlow id="f2" sourceRef="split" targetRef="a"/>
                    <sequenceFlow id="f3" sourceRef="split" targetRef="b"/>
                    <sequenceFlow id="f4" sourceRef="a" targetRef="end"/>
                    <sequenceFlow id="f5" sourceRef="b" targetRef="end"/>
                  </process>
                </definitions>
                """).deploy();
        instance = engine.getRuntimeService().createProcessInstanceBuilder()
                .processDefinitionKey("projection").businessStatus("running").start();
        tasks = engine.getTaskService().createTaskQuery().processInstanceId(instance.getId())
                .orderByTaskDefinitionKey().asc().list();
    }

    @AfterEach
    void tearDown()
    {
        try { if (support != null) support.close(); }
        finally { closeDatabase(); }
    }

    @Test
    void interpretsEveryFlowableScalarExactlyLikeTheEngine()
    {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("nil", null);
        input.put("text", "行内中文\"\\\n");
        input.put("small", (short) -123);
        input.put("integer", Integer.MIN_VALUE);
        input.put("long", Long.MAX_VALUE);
        input.put("double", 1.23456789012345D);
        input.put("yes", true);
        input.put("no", false);
        input.put("date", new Date(1234567890123L));
        input.put("instant", Instant.ofEpochMilli(1234567890123L));
        input.put("uuid", UUID.fromString("bf5d5a4c-d8e0-40ed-9047-5bb24f420f52"));
        input.put("decimal", new BigDecimal("123456789012345678.123450"));
        input.put("bigint", new BigInteger("123456789012345678901234567890"));
        engine.getRuntimeService().setVariables(instance.getId(), input);
        var result = read(false, input.keySet());
        for (var entry : input.entrySet())
        {
            Object engineValue = engine.getHistoryService().createHistoricVariableInstanceQuery()
                    .processInstanceId(instance.getId()).variableName(entry.getKey()).singleResult().getValue();
            assertThat(engineValue).as(entry.getKey()).isEqualTo(entry.getValue());
            Object expected = engineValue instanceof Date date ? date.toInstant().toString()
                    : engineValue instanceof Instant || engineValue instanceof UUID ? engineValue.toString() : engineValue;
            assertThat(result.values().get(entry.getKey()).toString()).as(entry.getKey())
                    .isEqualTo(JsonMapper.shared().valueToTree(expected).toString());
        }
        assertByteCounts(result);
        verify(mapper, never()).selectCurrentVariableBodies(anyString(), anyString(), anyBoolean(), anyList(), anyList());
    }

    @Test
    void decodesActualLongStringInlineJsonAndLongJsonStorage()
    {
        var inline = JsonMapper.shared().readTree("{\"中文\":[1,true,null,\"value\"]}");
        var large = JsonMapper.shared().createArrayNode();
        for (int i = 0; i < 400; i++) large.add("多字节-".repeat(8) + i);
        String longString = "长字符串".repeat(1500);
        engine.getRuntimeService().setVariables(instance.getId(), Map.of("text", longString, "json", inline, "large", large));
        assertThat(typeOf("text")).isEqualTo("longString");
        assertThat(typeOf("json")).isEqualTo("json");
        assertThat(typeOf("large")).isEqualTo("json");
        // Flowable 8 默认 json 同时写行内与 Blob；longJson 是保留的历史类型。
        // 使用引擎自带类型真正写入旧格式，既不伪造映射也不手改其物理存储。
        var types = ((org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl)
                engine.getProcessEngineConfiguration()).getVariableTypes();
        var legacy = types.getVariableType("longJson");
        int index = types.getTypeIndex(legacy);
        types.removeType(legacy).addType(legacy, 0);
        try { engine.getRuntimeService().setVariable(instance.getId(), "legacy", large); }
        finally { types.removeType(legacy).addType(legacy, index); }
        assertThat(typeOf("legacy")).isEqualTo("longJson");
        var result = read(false, Set.of("text", "json", "large", "legacy"));
        assertThat(result.values().get("text").asString()).isEqualTo(longString);
        assertThat(result.values().get("json")).isEqualTo(inline);
        assertThat(result.values().get("large")).isEqualTo(large);
        assertThat(result.values().get("legacy")).isEqualTo(large);
        assertByteCounts(result);
        verify(mapper).selectCurrentVariableBodies(eq(instance.getId()), eq(tasks.get(0).getId()), eq(false), anyList(), anyList());
    }

    @Test
    void decodesLegacyStringTagOverAnEngineWrittenStringBlob()
    {
        String value = "历史字符串".repeat(1200);
        engine.getRuntimeService().setVariable(instance.getId(), "legacyText", value);
        assertThat(typeOf("legacyText")).isEqualTo("longString");
        // 旧 string 标签的 Blob 兼容分支：正文由引擎实际序列化，保留原字节，不手造映射。
        support.jdbcTemplate().update("update ACT_HI_VARINST set VAR_TYPE_='string' where PROC_INST_ID_=? and NAME_='legacyText'", instance.getId());
        var result = read(false, Set.of("legacyText"));
        assertThat(result.values().get("legacyText").asString()).isEqualTo(value);
        assertByteCounts(result);
    }

    @Test
    void restrictsActualRootTaskAndChildExecutionScopesAndSkipsEmptyReadableSet()
    {
        engine.getRuntimeService().setVariables(instance.getId(), Map.of("value", "root", "hidden", "x".repeat(300000)));
        engine.getTaskService().setVariableLocal(tasks.get(0).getId(), "value", "own-task");
        engine.getTaskService().setVariableLocal(tasks.get(1).getId(), "value", "parallel-task");
        engine.getRuntimeService().setVariableLocal(tasks.get(0).getExecutionId(), "childOnly", "child");
        engine.getRuntimeService().setVariableLocal(tasks.get(0).getExecutionId(), "value", "child-shadow");
        assertThat(read(false, Set.of("value", "childOnly")).values()).hasSize(1)
                .containsEntry("value", JsonMapper.shared().valueToTree("root"));
        assertThat(read(true, Set.of("value", "childOnly")).values()).hasSize(1)
                .containsEntry("value", JsonMapper.shared().valueToTree("own-task"));
        clearInvocations(mapper);
        assertThat(read(false, Set.of()).values()).isEmpty();
        assertThat(read(false, Set.of("initiator", "nrOfInstances")).values()).isEmpty();
        verifyNoInteractions(mapper);
        engine.getTaskService().complete(tasks.get(0).getId());
        assertThat(read(true, Set.of("value")).values().get("value").asString()).isEqualTo("own-task");
    }

    @Test
    void rejectsOversizedBodyBeforeItIsMaterialized()
    {
        engine.getRuntimeService().setVariable(instance.getId(), "oversized", "x".repeat(300000));
        assertThat(typeOf("oversized")).isEqualTo("longString");
        assertThatThrownBy(() -> read(false, Set.of("oversized"))).isInstanceOf(ServiceException.class);
        verify(mapper, never()).selectCurrentVariableBodies(anyString(), anyString(), anyBoolean(), anyList(), anyList());
        var rows = mapper.selectCurrentVariableMetadata(instance.getId(), tasks.get(0).getId(), false, List.of("oversized"), 2);
        assertThat(rows.get(0).storedBytes()).isGreaterThan(300000L);
        assertThat(rows.get(0).textValue()).isNull();
    }

    @Test
    void boundsCumulativeBodiesAndResponseTextSeparately()
    {
        var json = JsonMapper.shared().createObjectNode();
        for (int i = 0; i < 12; i++) json.put("part" + i, "x".repeat(60000));
        engine.getRuntimeService().setVariables(instance.getId(), Map.of("a", json, "b", json, "c", json));
        assertThatThrownBy(() -> read(false, Set.of("a", "b", "c"))).isInstanceOf(ServiceException.class);
        verify(mapper, never()).selectCurrentVariableBodies(anyString(), anyString(), anyBoolean(), anyList(), anyList());
        engine.getRuntimeService().setVariable(instance.getId(), "text", "x".repeat(65537));
        assertThatThrownBy(() -> read(false, Set.of("text"))).isInstanceOf(ServiceException.class);
        verify(mapper).selectCurrentVariableBodies(eq(instance.getId()), anyString(), eq(false), eq(List.of("text")), anyList());
    }

    @Test
    void readsAllowedFieldsWhenUnrelatedVariablesExceedTheOldScopeLimit()
    {
        Map<String, Object> variables = new LinkedHashMap<>();
        for (int i = 0; i <= WorkflowProcessVariableProjection.MAX_VARIABLE_ROWS; i++) variables.put("unreadable" + i, i);
        variables.put("visible", "only allowed value");
        engine.getRuntimeService().setVariables(instance.getId(), variables);
        assertThat(read(false, Set.of("visible")).values()).containsExactlyEntriesOf(
                Map.of("visible", JsonMapper.shared().valueToTree("only allowed value")));
        verify(mapper).selectCurrentVariableMetadata(eq(instance.getId()), anyString(), eq(false), eq(List.of("visible")), eq(2));
    }

    @Test
    void neverDeserializesArbitraryObjectsIncludingMislabeledStringBodies()
    {
        SerializedTrap.readCount = 0;
        engine.getRuntimeService().setVariable(instance.getId(), "unsafe", new SerializedTrap());
        assertThat(read(false, Set.of("unsafe")).values()).isEmpty();
        verify(mapper, never()).selectCurrentVariableBodies(anyString(), anyString(), anyBoolean(), anyList(), anyList());
        // 存储损坏反例只改变量类型，不改任务、生命周期、轮次或业务状态。
        support.jdbcTemplate().update("update ACT_HI_VARINST set VAR_TYPE_='longString' where PROC_INST_ID_=? and NAME_='unsafe'", instance.getId());
        assertThatThrownBy(() -> read(false, Set.of("unsafe"))).isInstanceOf(ServiceException.class);
        assertThat(SerializedTrap.readCount).isZero();
    }

    @Test
    void preservesRepeatableReadAndRealEngineRollback() throws Exception
    {
        engine.getRuntimeService().setVariable(instance.getId(), "value", "before");
        operations.read(() -> {
            Integer isolation = support.jdbcTemplate().execute(
                    (org.springframework.jdbc.core.ConnectionCallback<Integer>) connection -> connection.getTransactionIsolation());
            assertThat(isolation).isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            assertThat(read(false, Set.of("value")).values().get("value").asString()).isEqualTo("before");
            try
            {
                CompletableFuture.runAsync(() -> engine.getRuntimeService().setVariable(instance.getId(), "value", "after"))
                        .get(10, TimeUnit.SECONDS);
            }
            catch (Exception failure) { throw new AssertionError(failure); }
            // 直接 JDBC 读取排除 MyBatis 一级缓存对重复读判据的掩盖。
            assertThat(support.jdbcTemplate().queryForObject(
                    "select TEXT_ from ACT_HI_VARINST where PROC_INST_ID_=? and NAME_='value'",
                    String.class, instance.getId())).isEqualTo("before");
            assertThat(read(false, Set.of("value")).values().get("value").asString()).isEqualTo("before");
            return null;
        });
        assertThat(read(false, Set.of("value")).values().get("value").asString()).isEqualTo("after");
        support.transactionTemplate().executeWithoutResult(status -> {
            engine.getRuntimeService().setVariable(instance.getId(), "value", "rolled-back");
            status.setRollbackOnly();
        });
        assertThat(read(false, Set.of("value")).values().get("value").asString()).isEqualTo("after");
    }

    private WorkflowProcessVariableProjection.ProjectedValues read(boolean local, Set<String> names)
    {
        return operations.read(() -> projection.projectCurrentValues(instance.getId(), tasks.get(0).getId(), local, names));
    }

    private String typeOf(String name)
    {
        return support.jdbcTemplate().queryForObject("select VAR_TYPE_ from ACT_HI_VARINST where PROC_INST_ID_=? and NAME_=?", String.class, instance.getId(), name);
    }

    private void assertByteCounts(WorkflowProcessVariableProjection.ProjectedValues result)
    {
        result.values().forEach((name, value) -> assertThat(result.serializedBytesByName().get(name))
                .isEqualTo(JsonMapper.shared().writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length));
    }

    private static final class SerializedTrap implements Serializable
    {
        private static final long serialVersionUID = 1L;
        private static int readCount;
        private void readObject(ObjectInputStream input) throws IOException
        {
            readCount++;
            throw new IOException("Arbitrary object deserialization is forbidden");
        }
    }
}
