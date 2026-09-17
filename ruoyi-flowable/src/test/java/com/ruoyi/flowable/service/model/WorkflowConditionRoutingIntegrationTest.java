package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.UnaryOperator;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.impl.persistence.entity.ModelEntity;
import org.flowable.engine.repository.Deployment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.*;
import com.ruoyi.flowable.domain.dto.WorkflowModelDto;
import com.ruoyi.flowable.engine.*;
import com.ruoyi.flowable.extension.WorkflowConditionRouter;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.mapper.*;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/** 作者保存/预检/部署到真实任务路径；只隔离目录和与路由无关的任务通知外设。 */
class WorkflowConditionRoutingIntegrationTest
{
    protected WorkflowFlowableEngineTestSupport support;
    protected ProcessEngine engine;
    protected RepositoryService repository;
    protected WorkflowDeploymentArtifactRepository artifacts;
    private WorkflowModelService models;
    private WorkflowEngineOperations operations;
    private int sequence;

    @BeforeEach void setup() throws Exception
    {
        support = startDatabase(Map.of());
        engine = support.processEngine(); repository = engine.getRepositoryService();
        artifacts = new WorkflowDeploymentArtifactRepository(repository);
        var identities = mock(WorkflowIdentityResolver.class);
        when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("100", Set.of()));
        operations = support.transactionalProxy(new WorkflowEngineOperations(
                new WorkflowAuthenticationContext(engine.getIdentityService(), new WorkflowIdentityCodec()),
                new WorkflowExceptionTranslator(), identities));
        var categories = mock(WfCategoryMapper.class);
        var category = new WfCategory(); category.setCode("test"); category.setCategoryName("条件路由"); category.setDelFlag("0");
        when(categories.selectByCode("test")).thenReturn(category);
        var forms = mock(WfFormMapper.class);
        var form = new WfForm(); form.setFormId(7L); form.setFormName("路由字段"); form.setDelFlag("0"); form.setContent(FORM);
        when(forms.selectById(7L)).thenReturn(form);
        var identityMapper = mock(WorkflowIdentityMapper.class);
        var templates = new WorkflowFormTemplateValidator();
        var fields = new WorkflowFormFieldExtensionService(null);
        models = new WorkflowModelService(operations, repository,
                new WorkflowBpmnService(repository, fields, null), new WorkflowBpmnIdentityValidator(identityMapper),
                categories, forms, artifacts, templates, new WorkflowExtensionDeploymentService(null, null, null, null),
                new WorkflowControlledLoopDeploymentService(), new WorkflowConditionDeploymentService(),
                new WorkflowParticipantRuleDeploymentService(identityMapper), new WorkflowDmnDecisionService(operations, null, artifacts),
                fields, new WorkflowCallActivityReferenceService(repository, operations, identities, null, artifacts, templates),
                new WorkflowTaskSlaDeploymentService(null, null, null), new WorkflowRuntimeTaskListenerCompiler());
        ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration()).getExpressionManager().setBeans(Map.of(
                "workflowConditionRouter", new WorkflowConditionRouter(repository, artifacts),
                "userTaskListener", (org.flowable.engine.delegate.TaskListener) task -> { }));
    }

    protected WorkflowFlowableEngineTestSupport startDatabase(Map<Object, Object> beans) throws Exception
    { return WorkflowFlowableEngineTestSupport.start("c10", beans); }
    protected void closeDatabase() { }
    @AfterEach void teardown()
    {
        try { if (support != null) support.close(); }
        finally { closeDatabase(); }
    }

    @ParameterizedTest
    @CsvSource({"AND,AND", "AND,OR", "OR,AND", "OR,OR"})
    void evaluatesRootAndGroupTruthTablesThroughSavedDeployment(String root, String inner)
    {
        String id = deploy("exclusive", rule(root,
                group(inner, atomic("amount", "GT", "0"), atomic("flag", "EQ", "true")),
                group(inner, atomic("text", "EQ", "\"yes\""), atomic("date", "EQ", "\"2026-09-17\""))), null, false);
        for (int bits = 0; bits < 16; bits++)
        {
            boolean a = (bits & 1) != 0, b = (bits & 2) != 0, c = (bits & 4) != 0, d = (bits & 8) != 0;
            boolean expected = combine(root, combine(inner, a, b), combine(inner, c, d));
            String instance = start(id, Map.of("amount", a ? 1 : 0, "flag", b, "text", c ? "yes" : "no", "date", d ? "2026-09-17" : "2026-09-18"));
            assertTasks(instance, expected ? "accepted" : "defaultTask");
        }
        Deployment child = child(id);
        assertThat(resource(child, "approvaplat/manifest-v1.json")).isEqualTo("{\"schemaVersion\":2}");
        assertThat(repository.getDeploymentResourceNames(child.getId())).contains("approvaplat/conditions-v2.json").doesNotContain("approvaplat/conditions-v1.json");
        assertThat(resource(child, "approvaplat/conditions-v2.json")).doesNotContain("celConfigJson", "conditionMatched", "rule1");
    }

    @Test void shortCircuitsUnusedMissingFieldsWithinAndBetweenGroupsButRejectsRequiredFields()
    {
        for (boolean between : List.of(false, true))
        {
            for (String combinator : List.of("AND", "OR"))
            {
                String first = atomic("amount", "GT", "0");
                String missing = atomic("text", "EQ", "\"yes\"");
                String config = between ? rule(combinator, group("AND", first), group("AND", missing))
                        : rule("AND", group(combinator, first, missing));
                String id = deploy("exclusive", config, null, false);
                boolean or = "OR".equals(combinator);
                assertTasks(start(id, Map.of("amount", or ? 1 : 0)), or ? "accepted" : "defaultTask");
                assertStartRollback(id, Map.of("amount", or ? 0 : 1), 409, "条件字段在当前流程实例中不存在: text");
            }
        }
    }

    @Test void exactNumbersDefaultAndAmbiguityKeepTransactionsAtomic()
    {
        String id = deploy("exclusive", single("amount", "GTE", "9007199254740993"), single("amount", "GT", "9007199254740993"), false);
        assertTasks(start(id, Map.of("amount", new BigDecimal("9007199254740993.000"))), "accepted");
        assertTasks(start(id, Map.of("amount", new BigDecimal("9007199254740992.999"))), "defaultTask");
        assertStartRollback(id, Map.of("amount", new BigDecimal("9007199254740993.001")), 409, "多个条件同时命中");
        String decimal = deploy("exclusive", single("amount", "EQ", "0.12345678"), null, false);
        assertTasks(start(decimal, Map.of("amount", new BigDecimal("0.12345678"))), "accepted");
        assertTasks(start(decimal, Map.of("amount", new BigDecimal("0.12345679"))), "defaultTask");
    }

    @ParameterizedTest @CsvSource({"EQ,1,true", "NE,1,false", "GT,1,false", "GTE,1,true", "LT,1,false", "LTE,1,true", "GT,0,true", "LT,2,true"})
    void preservesNumberBoundaryOperators(String operator, String expected, boolean matched)
    {
        String id = deploy("exclusive", single("amount", operator, expected), null, false);
        assertTasks(start(id, Map.of("amount", new BigDecimal("1.00"))), matched ? "accepted" : "defaultTask");
    }

    @Test void preservesStringsDatesScalarsBooleansAndRuntimeTypeErrors()
    {
        String config = rule("AND", group("AND", atomic("text", "CONTAINS", "\"中\""), atomic("text", "STARTS_WITH", "\"前\""), atomic("text", "ENDS_WITH", "\"后\"")),
                group("AND", atomic("date", "EQ", "\"2026-09-17\""), atomic("flag", "NE", "false"), atomic("scalar", "EQ", "\"1\"")));
        String id = deploy("exclusive", config, null, false);
        assertTasks(start(id, Map.of("text", "前中后", "date", "2026-09-17", "flag", true, "scalar", new BigDecimal("1.00"))), "accepted");
        String numeric = deploy("exclusive", single("amount", "GT", "0"), null, false);
        var nullValue = new HashMap<String, Object>(); nullValue.put("amount", null);
        assertStartRollback(numeric, nullValue, 409, "条件字段值不能为空: amount");
        assertStartRollback(numeric, Map.of(), 409, "条件字段在当前流程实例中不存在: amount");
        for (Object value : List.of("1", Double.NaN, Double.POSITIVE_INFINITY))
            assertStartRollback(numeric, Map.of("amount", value), 409, "条件字段运行值类型不合法: amount");
        String bool = deploy("exclusive", single("flag", "EQ", "true"), null, false);
        assertStartRollback(bool, Map.of("flag", "true"), 409, "条件字段运行值类型不合法: flag");
    }

    @Test void inclusiveGatewayCreatesAllMatchesAndUsesDefaultOnlyWhenNoneMatch()
    {
        String id = deploy("inclusive", single("amount", "GTE", "10"), single("amount", "GT", "10"), false);
        assertTasks(start(id, Map.of("amount", 11)), "accepted", "other");
        assertTasks(start(id, Map.of("amount", 10)), "accepted");
        assertTasks(start(id, Map.of("amount", 9)), "defaultTask");
    }

    @Test void evaluatesEveryExclusiveBranchEvenAfterOneMatched()
    {
        String id = deploy("exclusive", single("amount", "GT", "0"), single("text", "EQ", "\"yes\""), false);
        assertStartRollback(id, Map.of("amount", 1), 409, "条件字段在当前流程实例中不存在: text");
    }

    @Test void newCommandAndRepeatedGatewayVisitRecomputeAndFailedCompletionRollsBack()
    {
        String id = deploy("exclusive", single("amount", "GT", "0"), null, true);
        String instance = start(id, Map.of("amount", 1));
        assertTasks(instance, "accepted");
        var task = engine.getTaskService().createTaskQuery().processInstanceId(instance).singleResult();
        assertThatThrownBy(() -> operations.writeAsCurrentUser(actor -> {
            engine.getTaskService().complete(task.getId(), Map.of("amount", "bad")); return null;
        })).isInstanceOf(ServiceException.class).hasMessageContaining("条件字段运行值类型不合法");
        assertTasks(instance, "accepted");
        assertThat(engine.getRuntimeService().getVariable(instance, "amount")).isEqualTo(1);
        engine.getTaskService().complete(task.getId(), Map.of("amount", 0));
        assertTasks(instance, "defaultTask");
        assertTasks(start(id, Map.of("amount", 1)), "accepted");
        assertThat(engine.getRuntimeService().getVariables(instance).keySet()).noneMatch(key -> key.startsWith("__ruoyi_workflow_condition_"));
        assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery().processInstanceId(instance).list())
                .noneMatch(variable -> variable.getVariableName().startsWith("__ruoyi_workflow_condition_"));
    }

    @Test void rejectsUnsupportedRecursionAndInvalidAuthorBeforeAnyDeployment()
    {
        String nested = "{\"combinator\":\"AND\",\"groups\":[" + group("AND", atomic("amount", "GT", "0")) + "]}";
        for (String invalid : List.of(rule("AND", nested), single("missing", "EQ", "1"), single("amount", "EQ", "null"), single("amount", "CONTAINS", "1")))
        {
            long count = repository.createDeploymentQuery().count();
            String key = "invalid" + (++sequence);
            String xml = author(key, "exclusive", invalid, null, false).trim();
            String model = saveAuthor(key, xml);
            assertThat(models.preflight(model, ((ModelEntity) repository.getModel(model)).getRevision(), xml).valid()).isFalse();
            assertThatThrownBy(() -> models.deployModel(model)).isInstanceOf(ServiceException.class);
            assertThat(repository.createDeploymentQuery().count()).isEqualTo(count);
        }
    }

    @Test void transientResultIsSharedOnlyInsideOneEngineCommand()
    {
        String id = deploy("exclusive", single("amount", "GT", "0"), null, false);
        String instance = start(id, Map.of("amount", 1));
        var task = engine.getTaskService().createTaskQuery().processInstanceId(instance).singleResult();
        var branch = artifacts.selectConditionRules(id).stream().filter(value -> !value.getDefaultFlow()).findFirst().orElseThrow();
        var router = new WorkflowConditionRouter(repository, artifacts);
        engine.getManagementService().executeCommand(context -> {
            var execution = org.flowable.engine.impl.util.CommandContextUtil.getExecutionEntityManager().findById(task.getExecutionId());
            assertThat(router.matches(execution, branch.getGatewayToken(), branch.getFlowToken())).isTrue();
            execution.setVariable("amount", 0);
            assertThat(router.matches(execution, branch.getGatewayToken(), branch.getFlowToken())).isTrue();
            return null;
        });
        engine.getManagementService().executeCommand(context -> {
            var execution = org.flowable.engine.impl.util.CommandContextUtil.getExecutionEntityManager().findById(task.getExecutionId());
            assertThat(router.matches(execution, branch.getGatewayToken(), branch.getFlowToken())).isFalse();
            return null;
        });
    }

    @Test void resourceJsonWhitespaceAndRuleKeyOrderDoNotInvalidateFrozenChecksum()
    {
        String id = deploy("exclusive", single("amount", "GTE", "9007199254740993"), null, false);
        replaceResource(id, "approvaplat/conditions-v2.json", json -> {
            ArrayNode nodes = (ArrayNode) JsonMapper.shared().readTree(json);
            for (var node : nodes)
            {
                var rule = JsonMapper.shared().readTree(node.get("ruleJson").asText());
                var reordered = JsonMapper.shared().createObjectNode();
                var keys = new ArrayList<>(rule.propertyNames()); Collections.reverse(keys);
                keys.forEach(key -> reordered.set(key, rule.get(key)));
                ((tools.jackson.databind.node.ObjectNode) node).put("ruleJson", reordered.toPrettyString());
            }
            return nodes.toPrettyString();
        });
        assertTasks(start(id, Map.of("amount", new BigDecimal("9007199254740993"))), "accepted");
    }

    @ParameterizedTest @CsvSource({"exclusive", "inclusive"})
    void executesUnmodifiedBaselineV1Resources(String type) throws Exception
    {
        String id = installLegacy(type);
        assertTasks(start(id, Map.of("amount", new BigDecimal("9007199254740993"))), "accepted");
        assertTasks(start(id, Map.of("amount", new BigDecimal("9007199254740992"))), "defaultTask");
        if (type.equals("exclusive"))
            assertStartRollback(id, Map.of("amount", new BigDecimal("9007199254740994")), 409, "多个条件同时命中");
        else assertTasks(start(id, Map.of("amount", new BigDecimal("9007199254740994"))), "accepted", "other");
        assertThat(resource(child(id), "approvaplat/conditions-v1.json")).contains("celConfigJson", "rule1");
    }

    @Test void corruptedLegacyCelStillFailsItsOriginalChecksum() throws Exception
    {
        String id = installLegacy("exclusive");
        replaceResource(id, "approvaplat/conditions-v1.json", json -> json.replace("rule1", "false"));
        assertStartRollback(id, Map.of("amount", 0), 500, "校验和不一致");
    }

    @Test void damagedNewRulesAndUnknownManifestFailClosed()
    {
        String id = deploy("exclusive", single("amount", "GT", "0"), null, false);
        replaceResource(id, "approvaplat/conditions-v2.json", json -> json.replace("GT", "LT"));
        assertStartRollback(id, Map.of("amount", 1), 500, "校验和不一致");
        String unknown = deploy("exclusive", single("amount", "GT", "0"), null, false);
        replaceResource(unknown, "approvaplat/manifest-v1.json", json -> "{\"schemaVersion\":999}");
        assertThatThrownBy(() -> start(unknown, Map.of("amount", 1))).isInstanceOf(ServiceException.class);
    }

    @Test void missingBranchAssociationFailsClosed()
    {
        String id = deploy("exclusive", single("amount", "GT", "0"), null, false);
        replaceResource(id, "approvaplat/conditions-v2.json", json -> {
            ArrayNode nodes = (ArrayNode) JsonMapper.shared().readTree(json); nodes.remove(0); return nodes.toString();
        });
        assertStartRollback(id, Map.of("amount", 1), 500, "不存在或不完整");
    }

    protected String deploy(String type, String first, String second, boolean repeat)
    {
        String key = "routing" + (++sequence);
        String xml = author(key, type, first, second, repeat).trim();
        String model = saveAuthor(key, xml);
        var report = models.preflight(model, ((ModelEntity) repository.getModel(model)).getRevision(), xml);
        assertThat(report.valid()).as(report.issues().toString()).isTrue();
        String id = models.deployModel(model);
        assertThat(repository.getModelEditorSource(model)).isEqualTo(xml.getBytes(StandardCharsets.UTF_8));
        return id;
    }

    private String saveAuthor(String key, String xml)
    {
        var model = repository.newModel(); model.setKey(key); model.setName(key); model.setCategory("test"); model.setMetaInfo("{}"); repository.saveModel(model);
        repository.addModelEditorSource(model.getId(), xml.getBytes(StandardCharsets.UTF_8));
        var request = new WorkflowModelDto(); request.setModelId(model.getId()); request.setExpectedRevision(((ModelEntity) repository.getModel(model.getId())).getRevision()); request.setBpmnXml(xml);
        models.saveModel(request);
        return model.getId();
    }

    protected String start(String deployment, Map<String, Object> variables)
    {
        String definition = repository.createProcessDefinitionQuery().deploymentId(deployment).singleResult().getId();
        return operations.writeAsCurrentUser(actor -> engine.getRuntimeService().startProcessInstanceById(definition, variables).getId());
    }
    protected void assertTasks(String instance, String... expected)
    { assertThat(engine.getTaskService().createTaskQuery().processInstanceId(instance).list()).extracting(org.flowable.task.api.Task::getTaskDefinitionKey).containsExactlyInAnyOrder(expected); }
    private void assertStartRollback(String deployment, Map<String, Object> variables, int code, String message)
    {
        long runtime = engine.getRuntimeService().createProcessInstanceQuery().count();
        long history = engine.getHistoryService().createHistoricProcessInstanceQuery().count();
        long tasks = engine.getTaskService().createTaskQuery().count();
        assertThatThrownBy(() -> start(deployment, variables)).isInstanceOfSatisfying(ServiceException.class, failure -> {
            assertThat(failure.getCode()).isEqualTo(code); assertThat(failure.getMessage()).contains(message);
        });
        assertThat(engine.getRuntimeService().createProcessInstanceQuery().count()).isEqualTo(runtime);
        assertThat(engine.getHistoryService().createHistoricProcessInstanceQuery().count()).isEqualTo(history);
        assertThat(engine.getTaskService().createTaskQuery().count()).isEqualTo(tasks);
    }
    private Deployment child(String parent)
    { return repository.createDeploymentQuery().parentDeploymentId(parent).deploymentCategory("APPROVAPLAT_WORKFLOW_ARTIFACTS").singleResult(); }
    private String resource(Deployment deployment, String name)
    {
        try (var stream = repository.getResourceAsStream(deployment.getId(), name))
        { return new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    private void replaceResource(String parent, String target, UnaryOperator<String> change)
    {
        var previous = child(parent); var resources = new LinkedHashMap<String, String>();
        for (String name : repository.getDeploymentResourceNames(previous.getId())) resources.put(name, resource(previous, name));
        artifacts.delete(parent);
        var builder = repository.createDeployment().parentDeploymentId(parent).category("APPROVAPLAT_WORKFLOW_ARTIFACTS");
        resources.forEach((name, value) -> builder.addString(name, name.equals(target) ? change.apply(value) : value));
        builder.deploy();
    }
    private String installLegacy(String type) throws Exception
    {
        String folder = "/conditions-legacy-v1/" + type + "/";
        var parent = repository.createDeployment().addBytes("legacy.bpmn20.xml", fixture(folder + "process.bpmn20.xml")).deploy();
        var builder = repository.createDeployment().parentDeploymentId(parent.getId()).category("APPROVAPLAT_WORKFLOW_ARTIFACTS");
        for (String name : List.of("manifest", "forms", "conditions", "controlled-loops", "participants", "extensions", "dmn", "call-activities", "task-sla"))
            builder.addBytes("approvaplat/" + name + "-v1.json", fixture(folder + name + "-v1.json"));
        builder.deploy(); return parent.getId();
    }
    private byte[] fixture(String name) throws Exception
    { try (var stream = getClass().getResourceAsStream(name)) { return Objects.requireNonNull(stream, name).readAllBytes(); } }
    private boolean combine(String op, boolean a, boolean b) { return "AND".equals(op) ? a && b : a || b; }
    protected static String single(String field, String op, String value) { return rule("AND", group("AND", atomic(field, op, value))); }
    private static String atomic(String field, String op, String value) { return "{\"field\":\"" + field + "\",\"operator\":\"" + op + "\",\"value\":" + value + "}"; }
    private static String group(String op, String... rules) { return "{\"combinator\":\"" + op + "\",\"rules\":[" + String.join(",", rules) + "]}"; }
    private static String rule(String op, String... groups) { return "{\"version\":1,\"default\":false,\"combinator\":\"" + op + "\",\"groups\":[" + String.join(",", groups) + "]}"; }
    private String author(String key, String type, String first, String second, boolean repeat)
    {
        String assignment = "<extensionElements>" + properties("assignment", "STARTER").replace("</flowable:properties>", "<flowable:property name=\"approva.assignment.formField\" value=\"\"/></flowable:properties>") + "</extensionElements>";
        return """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="urn:c10">
            <process id="%s" isExecutable="true"><extensionElements>%s</extensionElements>
            <startEvent id="start" flowable:formKey="key_7"><extensionElements>%s</extensionElements></startEvent><%sGateway id="choice" default="fallback"/>
            <userTask id="accepted" name="命中">%s</userTask><userTask id="defaultTask" name="默认">%s</userTask><endEvent id="end"/>
            <sequenceFlow id="enter" sourceRef="start" targetRef="choice"/>%s%s
            <sequenceFlow id="acceptedEnd" sourceRef="accepted" targetRef="%s"/><sequenceFlow id="defaultEnd" sourceRef="defaultTask" targetRef="end"/>
            %s</process></definitions>
            """.formatted(key, properties("startScope", "PUBLIC"), permissions(), type, assignment, assignment,
                    flow("a", "accepted", first), flow("fallback", "defaultTask", "{\"version\":1,\"default\":true}"), repeat ? "choice" : "end",
                    second == null ? "" : "<userTask id=\"other\" name=\"其他\">" + assignment + "</userTask>" + flow("b", "other", second) + "<sequenceFlow id=\"otherEnd\" sourceRef=\"other\" targetRef=\"end\"/>");
    }
    private String permissions()
    {
        String result = "<flowable:formProperty id=\"approva_permission_default\" type=\"string\" readable=\"true\" writable=\"true\"/>";
        for (String field : List.of("amount", "flag", "text", "date", "scalar"))
            result += "<flowable:formProperty id=\"approva_permission_field_" + field + "\" variable=\"" + field + "\" type=\"string\" readable=\"true\" writable=\"true\"/>";
        return result;
    }
    private String properties(String scope, String type)
    { return "<flowable:properties><flowable:property name=\"approva." + scope + ".ruleVersion\" value=\"1\"/><flowable:property name=\"approva." + scope + ".type\" value=\"" + type + "\"/><flowable:property name=\"approva." + scope + ".targetIds\" value=\"\"/><flowable:property name=\"approva." + scope + ".noMatchPolicy\" value=\"FAIL\"/></flowable:properties>"; }
    private String flow(String id, String target, String rule)
    { return "<sequenceFlow id=\"" + id + "\" name=\"" + id + "\" sourceRef=\"choice\" targetRef=\"" + target + "\"><extensionElements><flowable:properties><flowable:property name=\"approva.conditionRule.config\" value='" + rule + "'/></flowable:properties></extensionElements></sequenceFlow>"; }
    private static final String FORM = """
            {"fields":[
              {"layout":"colFormItem","tag":"el-input-number","vModel":"amount","workflowHidden":false,"workflowReadable":true,"workflowWritable":true},
              {"layout":"colFormItem","tag":"el-switch","vModel":"flag","workflowHidden":false,"workflowReadable":true,"workflowWritable":true},
              {"layout":"colFormItem","tag":"el-input","vModel":"text","workflowHidden":false,"workflowReadable":true,"workflowWritable":true},
              {"layout":"colFormItem","tag":"el-date-picker","vModel":"date","type":"date","workflowHidden":false,"workflowReadable":true,"workflowWritable":true},
              {"layout":"colFormItem","tag":"el-select","vModel":"scalar","workflowHidden":false,"workflowReadable":true,"workflowWritable":true}]}
            """;
}
