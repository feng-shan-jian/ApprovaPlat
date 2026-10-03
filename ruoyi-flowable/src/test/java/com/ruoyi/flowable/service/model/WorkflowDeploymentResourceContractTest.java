package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import com.ruoyi.flowable.domain.dto.WorkflowDecisionTableRequest;
import org.flowable.dmn.engine.DmnEngine;
import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.ruoyi.flowable.config.WorkflowDecisionTableConfiguration;
import com.ruoyi.flowable.domain.*;
import com.ruoyi.flowable.engine.*;
import com.ruoyi.flowable.extension.*;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.listener.*;
import com.ruoyi.flowable.mapper.*;
import com.ruoyi.flowable.service.task.*;

/** 资源与 DMN 删除合同仅使用资源仓库、DMN 和事务，不经过模型发布链。 */
class WorkflowDeploymentResourceContractTest
{
    @Test
    void enforcesDmnDeletionContractsFromPersistedReferences()
    {
        new ApplicationContextRunner().withUserConfiguration(Database.class, WorkflowDecisionTableConfiguration.class)
                .withPropertyValues("flowable.database-schema-update=true", "flowable.async-executor-activate=false",
                        "flowable.idm.enabled=false", "flowable.eventregistry.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var engine = context.getBean(ProcessEngine.class);
                    var repository = engine.getRepositoryService();
                    var identities = mock(WorkflowIdentityResolver.class);
                    when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("100", Set.of()));
                    var proxy = new ProxyFactory(new WorkflowEngineOperations(new WorkflowAuthenticationContext(
                            engine.getIdentityService(), new WorkflowIdentityCodec()), new WorkflowExceptionTranslator(), identities));
                    proxy.setProxyTargetClass(true);
                    proxy.addAdvice(new TransactionInterceptor(context.getBean(PlatformTransactionManager.class), new AnnotationTransactionAttributeSource()));
                    var operations = (WorkflowEngineOperations) proxy.getProxy();
                    var artifacts = new WorkflowDeploymentArtifactRepository(repository);
                    var dmn = context.getBean(DmnEngine.class);
                    var decisions = new WorkflowDmnDecisionService(operations, dmn.getDmnRepositoryService(), artifacts);
                    var tables = new WorkflowDecisionTableService(decisions, dmn.getDmnDecisionService(), dmn.getDmnRepositoryService(), operations);
                    String source = tables.publish(decimalTable());
                    String decision = dmn.getDmnRepositoryService().createDecisionQuery().deploymentId(source).singleResult().getId();
                    String xml = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"resource\"><process id=\"resourceProcess\" isExecutable=\"true\"><startEvent id=\"start\"/></process></definitions>";
                    String deployment = repository.createDeployment().addString("resource.bpmn20.xml", xml).deploy().getId();
                    String definition = repository.createProcessDefinitionQuery().deploymentId(deployment).singleResult().getId();
                    var model = new org.flowable.bpmn.model.BpmnModel();
                    var process = new org.flowable.bpmn.model.Process(); process.setId("resourceProcess");
                    var task = new org.flowable.bpmn.model.BusinessRuleTask(); task.setId("decision"); task.setRuleNames(List.of(decision));
                    process.addFlowElement(task); model.addProcess(process);
                    var snapshots = operations.writeAsCurrentUser(actor -> decisions.freezeSnapshots(deployment, decisions.prepare(model), actor.userId()));
                    artifacts.persist(deployment, new WorkflowDeploymentArtifacts(null, null, null, null, null, snapshots, null, null));
                    assertDmnDeletionBoundaries(operations, dmn.getDmnRepositoryService(), artifacts, source, deployment, tables.publish(decimalTable()));
                });
    }

    @Test
    void boundsStreamsAndReadsOnlyRequestedResourcesWithoutDmnEngine()
    {
        try (var fixture = com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport.start("resource-read", Map.of()))
        {
            var repository = fixture.processEngine().getRepositoryService();
            var identities = mock(WorkflowIdentityResolver.class);
            var operations = fixture.transactionalProxy(new WorkflowEngineOperations(new WorkflowAuthenticationContext(
                    fixture.processEngine().getIdentityService(), new WorkflowIdentityCodec()), new WorkflowExceptionTranslator(), identities));
            String xml = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"resource\"><process id=\"resourceProcess\" isExecutable=\"true\"><startEvent id=\"start\"/></process></definitions>";
            String deployment = repository.createDeployment().addString("resource.bpmn20.xml", xml).deploy().getId();
            String definition = repository.createProcessDefinitionQuery().deploymentId(deployment).singleResult().getId();
            new WorkflowDeploymentArtifactRepository(repository).persist(deployment, WorkflowDeploymentArtifacts.empty());
            var decisions = mock(org.flowable.dmn.api.DmnRepositoryService.class);
            var decision = mock(org.flowable.dmn.api.DmnDecision.class);
            when(decision.getId()).thenReturn("decision"); when(decision.getKey()).thenReturn("decision");
            when(decision.getDeploymentId()).thenReturn("source"); when(decision.getResourceName()).thenReturn("source.dmn");
            when(decisions.getDecision("decision")).thenReturn(decision);
            var query = mock(org.flowable.dmn.api.DmnDeploymentQuery.class, RETURNS_SELF);
            var source = mock(org.flowable.dmn.api.DmnDeployment.class);
            when(source.getId()).thenReturn("source"); when(query.singleResult()).thenReturn(source);
            when(decisions.createDeploymentQuery()).thenReturn(query);
            assertResourceReadBoundaries(operations, repository, decisions, deployment, definition, "decision", xml);
            assertLazyDeploymentRead(repository, deployment, "resourceProcess");
        }
    }

    private static void assertDmnDeletionBoundaries(WorkflowEngineOperations operations,
            org.flowable.dmn.api.DmnRepositoryService repository,
            WorkflowDeploymentArtifactRepository artifacts, String referencedSource,
            String parentDeployment, String unreferencedSource)
    {
        var observed = spy(repository);
        var service = new WorkflowDmnDecisionService(operations, observed, artifacts);
        assertResourceFailure(() -> service.delete("missing-dmn"), "DMN 部署不存在", 404);
        verify(observed, times(1)).createDeploymentQuery();
        clearInvocations(observed);
        service.delete(unreferencedSource);
        verify(observed, times(1)).createDeploymentQuery();
        assertThat(repository.createDeploymentQuery().deploymentId(unreferencedSource).count()).isZero();
        assertResourceFailure(() -> service.delete(referencedSource), "DMN 部署已被流程版本冻结引用", 409);
        String frozen = artifacts.selectDmnSnapshots(parentDeployment).get(0).getFrozenDeploymentId();
        assertResourceFailure(() -> service.delete(frozen), "流程冻结 DMN 部署不允许独立删除", 409);
        assertThat(repository.createDeploymentQuery().deploymentId(referencedSource).count()).isOne();
        assertThat(repository.createDeploymentQuery().deploymentId(frozen).count()).isOne();
    }

    /** 同次详情共享两种实际资源，独立入口仍使用当前协议及清单门禁。 */
    private static void assertLazyDeploymentRead(org.flowable.engine.RepositoryService repository,
            String deploymentId, String processKey)
    {
        var observed = spy(repository);
        var artifacts = new WorkflowDeploymentArtifactRepository(observed);
        String artifactId = repository.createDeploymentQuery().parentDeploymentId(deploymentId)
                .deploymentCategory("APPROVAPLAT_WORKFLOW_ARTIFACTS").singleResult().getId();
        var read = artifacts.openRead(deploymentId);
        verifyNoInteractions(observed);
        var forms = read.forms();
        assertThat(read.forms()).isSameAs(forms);
        verify(observed, times(1)).createDeploymentQuery();
        verify(observed, times(1)).getResourceAsStream(artifactId, "approvaplat/manifest-v1.json");
        verify(observed, times(1)).getResourceAsStream(artifactId, "approvaplat/forms-v1.json");
        verifyNoMoreInteractions(observed);
        assertThat(read.controlledLoops(processKey)).isEqualTo(read.controlledLoops(processKey));
        verify(observed, times(1)).getResourceAsStream(artifactId, "approvaplat/controlled-loops-v1.json");
        verifyNoMoreInteractions(observed);
        clearInvocations(observed);
        assertResourceFailure(() -> artifacts.openRead("").controlledLoop("", "activity"), "流程标识不能为空", 400);
        verifyNoInteractions(observed);

        String currentParent = repository.createDeployment().addString("fixture.txt", "current").deploy().getId();
        repository.createDeployment().parentDeploymentId(currentParent).category("APPROVAPLAT_WORKFLOW_ARTIFACTS")
                .addString("approvaplat/manifest-v1.json", "{\"schemaVersion\":2}")
                .addString("approvaplat/forms-v1.json", "[]")
                .addString("approvaplat/controlled-loops-v1.json", "[]")
                .addString("approvaplat/conditions-v2.json", """
                        [{"processKey":"current","gatewayId":"g","gatewayToken":"token","flowId":"f"}]
                        """).deploy();
        var current = artifacts.openRead(currentParent);
        assertThat(current.forms()).isEmpty();
        assertThat(current.controlledLoops("current")).isEmpty();
        assertThat(artifacts.selectRuntimeConditionRules(currentParent, "current", "token"))
                .extracting(WfDeployConditionRule::getFlowId).containsExactly("f");
        assertThat(artifacts.selectRuntimeConditionRules(currentParent, "unrelated", "token")).isEmpty();
        assertThat(artifacts.openRead("absent-deployment").forms()).isEmpty();

        String invalidParent = repository.createDeployment().addString("fixture.txt", "invalid").deploy().getId();
        for (String manifest : List.of("{\"schemaVersion\":1}", "{\"schemaVersion\":3}", "broken-json"))
        {
            String id = repository.createDeployment().parentDeploymentId(invalidParent).category("APPROVAPLAT_WORKFLOW_ARTIFACTS")
                    .addString("approvaplat/manifest-v1.json", manifest).deploy().getId();
            assertResourceFailure(() -> artifacts.openRead(invalidParent).forms(),
                    manifest.startsWith("{") ? "流程部署业务资源协议版本不受支持" : "流程部署业务资源清单无法解析", 500);
            repository.deleteDeployment(id);
        }
        repository.createDeployment().parentDeploymentId(invalidParent).category("APPROVAPLAT_WORKFLOW_ARTIFACTS")
                .addString("fixture.txt", "missing-manifest").deploy();
        // 实际引擎的缺失资源抛 FlowableObjectNotFoundException；空流的 500 契约由前面的边界用例覆盖。
        assertThatThrownBy(() -> artifacts.openRead(invalidParent).forms())
                .isInstanceOf(org.flowable.common.engine.api.FlowableObjectNotFoundException.class);
        repository.createDeployment().parentDeploymentId(invalidParent).category("APPROVAPLAT_WORKFLOW_ARTIFACTS")
                .addString("fixture.txt", "duplicate").deploy();
        assertResourceFailure(() -> artifacts.openRead(invalidParent).forms(), "流程部署业务资源数量异常", 500);
    }

    /** 在真实部署、DMN 来源和制品定位之后注入资源流故障，覆盖公开读取/编译入口的契约。 */
    private static void assertResourceReadBoundaries(WorkflowEngineOperations operations,
            org.flowable.engine.RepositoryService repository,
            org.flowable.dmn.api.DmnRepositoryService dmnRepository,
            String deploymentId, String definitionId, String decisionId, String compiled)
    {
        var resources = spy(repository);
        var decisions = dmnRepository;
        var artifacts = new WorkflowDeploymentArtifactRepository(resources);
        var deployments = new WorkflowDeploymentService(operations, resources,
                artifacts, null, null, null, null, null);
        var dmn = new WorkflowDmnDecisionService(operations, decisions, artifacts);
        assertThat(deployments.getBpmnXml(definitionId)).isEqualTo(compiled);
        java.util.function.Supplier<WorkflowDmnDecisionService.DecisionSource> compile = () -> {
            var model = new org.flowable.bpmn.model.BpmnModel();
            var process = new org.flowable.bpmn.model.Process(); process.setId("readBoundary");
            var task = new org.flowable.bpmn.model.BusinessRuleTask();
            task.setId("decision"); task.setRuleNames(List.of(decisionId));
            process.addFlowElement(task); model.addProcess(process);
            return dmn.prepare(model).get(0);
        };
        int xmlLimit = 2 * 1024 * 1024;
        for (int size : new int[] {0, xmlLimit, xmlLimit + 1})
        {
            byte[] bytes = new byte[size]; java.util.Arrays.fill(bytes, (byte) ' ');
            var bpmnStream = spy(new java.io.ByteArrayInputStream(bytes));
            var dmnStream = spy(new java.io.ByteArrayInputStream(bytes));
            doReturn(bpmnStream).when(resources).getProcessModel(definitionId);
            doReturn(dmnStream).when(decisions).getDmnResource(decisionId);
            if (size <= xmlLimit)
            {
                assertThat(deployments.getBpmnXml(definitionId)).isEqualTo(new String(bytes, StandardCharsets.UTF_8));
                assertThat(compile.get().resourceBytes()).containsExactly(bytes);
            }
            else
            {
                assertResourceFailure(() -> deployments.getBpmnXml(definitionId), "BPMN XML 超过大小限制", 400);
                assertResourceFailure(compile::get, "DMN XML 超过大小限制", 400);
            }
            try { verify(bpmnStream).close(); verify(dmnStream).close(); }
            catch (java.io.IOException exception) { throw new AssertionError(exception); }
        }
        doReturn(null).when(resources).getProcessModel(definitionId);
        doReturn(null).when(decisions).getDmnResource(decisionId);
        assertResourceFailure(() -> deployments.getBpmnXml(definitionId), "流程定义 BPMN 不存在", 404);
        assertResourceFailure(compile::get, "DMN 决策资源不存在", 409);
        doReturn(failingResourceStream()).when(resources).getProcessModel(definitionId);
        doReturn(failingResourceStream()).when(decisions).getDmnResource(decisionId);
        assertResourceFailure(() -> deployments.getBpmnXml(definitionId), "流程定义 BPMN 读取失败", 500);
        assertResourceFailure(compile::get, "DMN 业务规则任务编译失败", 400);

        String artifactId = repository.createDeploymentQuery().parentDeploymentId(deploymentId)
                .deploymentCategory("APPROVAPLAT_WORKFLOW_ARTIFACTS").singleResult().getId();
        int artifactLimit = 32 * 1024 * 1024;
        for (int size : new int[] {0, artifactLimit, artifactLimit + 1})
        {
            byte[] bytes = new byte[size]; java.util.Arrays.fill(bytes, (byte) ' ');
            if (size > 0) { bytes[0] = '['; bytes[1] = ']'; }
            doReturn(new java.io.ByteArrayInputStream(bytes)).when(resources)
                    .getResourceAsStream(artifactId, "approvaplat/forms-v1.json");
            if (size == artifactLimit) assertThat(artifacts.openRead(deploymentId).forms()).isEmpty();
            else assertResourceFailure(() -> artifacts.openRead(deploymentId).forms(), size == 0
                    ? "流程部署业务资源无法解析" : "流程部署业务资源大小超过限制", 500);
        }
        doReturn(null).when(resources).getResourceAsStream(artifactId, "approvaplat/forms-v1.json");
        assertResourceFailure(() -> artifacts.openRead(deploymentId).forms(), "流程部署业务资源缺失", 500);
        doReturn(failingResourceStream()).when(resources).getResourceAsStream(artifactId, "approvaplat/forms-v1.json");
        assertResourceFailure(() -> artifacts.openRead(deploymentId).forms(), "流程部署业务资源读取失败", 500);
    }

    private static java.io.InputStream failingResourceStream()
    {
        return new java.io.InputStream() {
            @Override public int read() throws java.io.IOException { throw new java.io.IOException("resource fault"); }
        };
    }

    private static void assertResourceFailure(Runnable action, String message, int code)
    {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(com.ruoyi.common.exception.ServiceException.class,
                failure -> { assertThat(failure.getMessage()).isEqualTo(message); assertThat(failure.getCode()).isEqualTo(code); });
    }

    static WorkflowDecisionTableRequest decimalTable()
    {
        return JsonMapper.shared().readValue("""
            {"key":"preciseDecision","name":"精确数值","category":"","hitPolicy":"FIRST",
             "inputs":[{"key":"amount","label":"金额","type":"number"}],
             "outputs":[{"key":"result","label":"结果","type":"number"}],
             "rules":[{"conditions":[{"operator":"EQ","value":"9999999999.12345678"}],"results":["9007199254740993"]}],
             "defaultResult":["-9999999999.12345678"],"testValues":{"amount":"9999999999.12345678"}}
            """, WorkflowDecisionTableRequest.class);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(EngineAutoConfigurations.class)
    static class Database
    {
        @Bean DataSource dataSource()
        {
            var source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:decimal-config-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            source.setUser("sa");
            return source;
        }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
    }

    // 与 Boot 自动配置一样按类名读取元数据，避免反射不存在的可选 App Engine 类型。
    static class EngineAutoConfigurations implements ImportSelector
    {
        @Override public String[] selectImports(AnnotationMetadata metadata)
        {
            return new String[] { "org.flowable.spring.boot.ProcessEngineAutoConfiguration",
                    "org.flowable.spring.boot.dmn.DmnEngineAutoConfiguration",
                    "org.flowable.spring.boot.ProcessEngineServicesAutoConfiguration",
                    "org.flowable.spring.boot.dmn.DmnEngineServicesAutoConfiguration" };
        }
    }
}
