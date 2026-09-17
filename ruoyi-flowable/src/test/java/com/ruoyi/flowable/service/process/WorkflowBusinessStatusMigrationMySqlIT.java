package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.flowable.testsupport.*;

/** 旧存储夹具只用于迁移；新版本生命周期另由正式服务链测试证明。 */
class WorkflowBusinessStatusMigrationMySqlIT
{
    private static final Path MIGRATION = Path.of("../sql/flowable/migration/8.0.5__retire_process_status.sql");
    private WorkflowMySqlTestDatabase database;
    private WorkflowFlowableEngineTestSupport harness;
    private ProcessEngine engine;
    private JdbcTemplate jdbc;

    @BeforeEach void setUp() throws Exception
    {
        database = new WorkflowMySqlTestDatabase("C05 status migration");
        harness = WorkflowFlowableEngineTestSupport.startOnExistingDatabase(database.dataSource(), Map.of());
        engine = harness.processEngine();
        jdbc = harness.jdbcTemplate();
        engine.getRepositoryService().createDeployment().addString("legacy.bpmn20.xml", """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="test">
                  <process id="legacyStatus" isExecutable="true">
                    <startEvent id="start"/><sequenceFlow id="s1" sourceRef="start" targetRef="review"/>
                    <userTask id="review"/><sequenceFlow id="s2" sourceRef="review" targetRef="end"/><endEvent id="end"/>
                  </process>
                </definitions>""").deploy();
    }

    @AfterEach void close()
    {
        try { if (harness != null) harness.close(); }
        finally { if (database != null) database.close(); }
    }

    /** 迁移专用旧版夹具：通过真实 Flowable 命令生成旧根变量及官方运行/历史存储。 */
    private ProcessInstance legacy(String businessStatus)
    {
        return engine.getRuntimeService().createProcessInstanceBuilder().processDefinitionKey("legacyStatus")
                .businessStatus(businessStatus).variables(Map.of("processStatus", "running", "requestValue", "preserved")).start();
    }

    @Test void fillsMissingAuthorityRemovesOldStorageAndContinuesRealCompletion() throws Exception
    {
        ProcessInstance process = legacy(null);
        var before = backup();
        database.script(MIGRATION);
        assertThat(business(process.getId())).isEqualTo("running");
        assertNoLegacy();
        assertThat(engine.getRuntimeService().getVariable(process.getId(), "requestValue")).isEqualTo("preserved");
        // Restore the exact rows/columns modified by this migration to prove backup round-trip,
        // then cut over again. Production rollback uses the full offline database backup.
        restore(before);
        assertThat(backup()).isEqualTo(before);
        database.script(MIGRATION);
        var listeners = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        listeners.registerSingleton("copies", org.mockito.Mockito.mock(com.ruoyi.flowable.service.task.WorkflowAutomaticCopyService.class));
        listeners.registerSingleton("notifications", org.mockito.Mockito.mock(com.ruoyi.flowable.service.notification.WorkflowNotificationService.class));
        listeners.registerSingleton("outbox", org.mockito.Mockito.mock(com.ruoyi.flowable.service.notification.WorkflowNotificationOutboxService.class));
        engine.getRuntimeService().addEventListener(new WorkflowProcessCompletionStatusListener(
                listeners.getBeanProvider(com.ruoyi.flowable.service.task.WorkflowAutomaticCopyService.class),
                listeners.getBeanProvider(com.ruoyi.flowable.service.notification.WorkflowNotificationService.class),
                listeners.getBeanProvider(com.ruoyi.flowable.service.notification.WorkflowNotificationOutboxService.class)));
        engine.getTaskService().complete(engine.getTaskService().createTaskQuery().processInstanceId(process.getId()).singleResult().getId());
        assertThat(business(process.getId())).isEqualTo("completed");
        assertNoLegacy();
        database.script(MIGRATION);
        assertThat(business(process.getId())).isEqualTo("completed");
    }

    @Test void projectsOldNaturalCompletionAndPreservesExplicitTerminalPriority() throws Exception
    {
        ProcessInstance natural = legacy("running");
        engine.getTaskService().complete(engine.getTaskService().createTaskQuery().processInstanceId(natural.getId()).singleResult().getId());
        for (String terminal : List.of("canceled", "rejected", "terminated"))
        {
            ProcessInstance process = legacy(terminal);
            // This is an explicitly labelled old-version storage fixture, not lifecycle acceptance.
            engine.getRuntimeService().setVariable(process.getId(), "processStatus", terminal);
            engine.getRuntimeService().deleteProcessInstance(process.getId(), terminal);
        }
        database.script(MIGRATION);
        assertThat(business(natural.getId())).isEqualTo("completed");
        assertThat(jdbc.queryForList("select BUSINESS_STATUS_ from ACT_HI_PROCINST", String.class))
                .containsExactlyInAnyOrder("completed", "canceled", "rejected", "terminated");
        assertNoLegacy();
    }

    @ParameterizedTest @ValueSource(strings={"conflict","missing","wrongType","local","duplicate","engine"})
    void ambiguousLegacyDataFailsBeforeAnyWrite(String damage) throws Exception
    {
        ProcessInstance process = legacy("running");
        switch (damage)
        {
            case "conflict" -> jdbc.update("update ACT_HI_PROCINST set BUSINESS_STATUS_='returned' where ID_=?", process.getId());
            case "missing" -> {
                engine.getRuntimeService().removeVariable(process.getId(), "processStatus");
                jdbc.update("update ACT_RU_EXECUTION set BUSINESS_STATUS_=null where ID_=?", process.getId());
                jdbc.update("update ACT_HI_PROCINST set BUSINESS_STATUS_=null where ID_=?", process.getId());
            }
            case "wrongType" -> engine.getRuntimeService().setVariable(process.getId(), "processStatus", 42);
            case "local" -> engine.getTaskService().setVariableLocal(engine.getTaskService().createTaskQuery().singleResult().getId(), "processStatus", "running");
            case "duplicate" -> jdbc.update("insert into ACT_HI_VARINST(ID_,PROC_INST_ID_,EXECUTION_ID_,NAME_,VAR_TYPE_,TEXT_) values('duplicate',?,?,'processStatus','string','running')", process.getId(), process.getId());
            case "engine" -> {
                jdbc.update("update ACT_HI_PROCINST set BUSINESS_STATUS_='completed' where ID_=?", process.getId());
                jdbc.update("update ACT_RU_EXECUTION set BUSINESS_STATUS_='completed' where ID_=?", process.getId());
                engine.getRuntimeService().setVariable(process.getId(), "processStatus", "completed");
            }
            default -> throw new IllegalArgumentException(damage);
        }
        var before = backup();
        assertThatThrownBy(() -> database.script(MIGRATION)).hasMessageContaining("C05_");
        assertThat(backup()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"deployment","model","draft"})
    void storedExpressionDependenciesBlockCutover(String location) throws Exception
    {
        legacy("running");
        byte[] value = "<expression>processStatus</expression>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (location.equals("draft"))
        {
            jdbc.update("insert into wf_model_design_draft(model_id,owner_user_id,base_model_revision,revision,bpmn_xml) values('legacy-model','100',1,1,?)", new String(value, java.nio.charset.StandardCharsets.UTF_8));
        }
        else
        {
            String deployment = location.equals("deployment") ? engine.getRepositoryService().createDeploymentQuery().singleResult().getId() : null;
            jdbc.update("insert into ACT_GE_BYTEARRAY(ID_,REV_,NAME_,DEPLOYMENT_ID_,BYTES_) values('legacy-ref',1,'legacy-expression',?,?)", deployment, value);
            if (location.equals("model"))
                jdbc.update("insert into ACT_RE_MODEL(ID_,REV_,EDITOR_SOURCE_VALUE_ID_) values('legacy-model',1,'legacy-ref')");
        }
        var before = backup();
        assertThatThrownBy(() -> database.script(MIGRATION)).hasMessageContaining("C05_DEPENDENCY");
        assertThat(backup()).isEqualTo(before);
    }

    private String business(String id)
    {
        return engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(id).singleResult().getBusinessStatus();
    }

    private void assertNoLegacy()
    {
        for (String table : List.of("ACT_RU_VARIABLE","ACT_HI_VARINST","ACT_HI_DETAIL"))
            assertThat(jdbc.queryForObject("select count(*) from "+table+" where NAME_='processStatus'", Long.class)).isZero();
    }

    private Map<String,List<Map<String,Object>>> backup()
    {
        var result = new java.util.LinkedHashMap<String,List<Map<String,Object>>>();
        for (String table : List.of("ACT_RU_EXECUTION","ACT_HI_PROCINST","ACT_RU_VARIABLE","ACT_HI_VARINST","ACT_HI_DETAIL"))
            result.put(table,jdbc.queryForList("select * from "+table+" order by ID_"));
        return result;
    }

    private void restore(Map<String,List<Map<String,Object>>> backup)
    {
        for (String table : List.of("ACT_RU_EXECUTION","ACT_HI_PROCINST"))
            for (var row : backup.get(table))
            {
                jdbc.update("update "+table+" set BUSINESS_STATUS_=? where ID_=?",row.get("BUSINESS_STATUS_"),row.get("ID_"));
                if (table.equals("ACT_RU_EXECUTION")) jdbc.update("update "+table+" set REV_=? where ID_=?",row.get("REV_"),row.get("ID_"));
            }
        for (String table : List.of("ACT_RU_VARIABLE","ACT_HI_VARINST","ACT_HI_DETAIL"))
        {
            jdbc.update("delete from "+table);
            for (var row : backup.get(table))
                jdbc.update("insert into "+table+" ("+String.join(",",row.keySet())+") values ("+
                        String.join(",",java.util.Collections.nCopies(row.size(),"?"))+")",row.values().toArray());
        }
    }
}
