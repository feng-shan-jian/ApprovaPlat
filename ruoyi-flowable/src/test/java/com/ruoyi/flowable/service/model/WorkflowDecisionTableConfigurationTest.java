package com.ruoyi.flowable.service.model;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import com.ruoyi.flowable.config.WorkflowDecisionTableConfiguration;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.*;
import org.flowable.dmn.engine.DmnEngine;
import org.flowable.engine.ProcessEngine;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WorkflowDecisionTableConfigurationTest
{
    @Test void productionAutoConfigurationPublishesFreezesAndPersistsExactProcessResults()
    {
        new ApplicationContextRunner().withUserConfiguration(Database.class, WorkflowDecisionTableConfiguration.class)
                .withPropertyValues("flowable.database-schema-update=true", "flowable.async-executor-activate=false",
                        "flowable.idm.enabled=false", "flowable.eventregistry.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var process = context.getBean(ProcessEngine.class);
                    var dmn = context.getBean(DmnEngine.class);
                    assertThat(dmn.getDmnEngineConfiguration().getRuleEngineExecutor()).isInstanceOf(WorkflowDecisionTableExecutor.class);
                    var tx = context.getBean(PlatformTransactionManager.class);
                    var identity = mock(WorkflowIdentityResolver.class);
                    when(identity.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("100", Set.of()));
                    var target = new WorkflowEngineOperations(new WorkflowAuthenticationContext(process.getIdentityService(),
                            new WorkflowIdentityCodec()), new WorkflowExceptionTranslator(), identity);
                    var proxy = new ProxyFactory(target);
                    proxy.setProxyTargetClass(true);
                    proxy.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
                    var operations = (WorkflowEngineOperations) proxy.getProxy();
                    var artifacts = new WorkflowDeploymentArtifactRepository(process.getRepositoryService());
                    var decisions = new WorkflowDmnDecisionService(operations, dmn.getDmnRepositoryService(), artifacts);
                    var tables = new WorkflowDecisionTableService(decisions, dmn.getDmnDecisionService(), dmn.getDmnRepositoryService(), operations);
                    var request = WorkflowDecisionTableBusinessTest.decimalTable();
                    String source = tables.publish(request);
                    var decision = dmn.getDmnRepositoryService().createDecisionQuery().deploymentId(source).singleResult();
                    String bpmn = """
                        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="urn:precision">
                          <process id="preciseProcess" isExecutable="true"><startEvent id="start"/><businessRuleTask id="decide" flowable:rules="%s"/><userTask id="hold"/><endEvent id="end"/>
                            <sequenceFlow id="a" sourceRef="start" targetRef="decide"/><sequenceFlow id="b" sourceRef="decide" targetRef="hold"/><sequenceFlow id="c" sourceRef="hold" targetRef="end"/>
                          </process>
                        </definitions>
                        """.formatted(decision.getId());
                    var executable = new org.flowable.bpmn.converter.BpmnXMLConverter().convertToBpmnModel(
                            () -> new java.io.ByteArrayInputStream(bpmn.getBytes(java.nio.charset.StandardCharsets.UTF_8)), true, true);
                    var prepared = decisions.prepare(executable);
                    var deployment = new TransactionTemplate(tx).execute(status -> {
                        var parent = process.getRepositoryService().createDeployment().addBytes("precision.bpmn20.xml", WorkflowBpmnXmlPreserver.write(executable, bpmn)).deploy();
                        var snapshots = decisions.freezeSnapshots(parent.getId(), prepared, "100");
                        artifacts.persist(parent.getId(), new WorkflowDeploymentArtifacts(List.of(), List.of(), List.of(), List.of(),
                                List.of(), snapshots, List.of(), List.of()));
                        return parent;
                    });
                    try
                    {
                        var instance = process.getRuntimeService().startProcessInstanceByKey("preciseProcess", Map.of("amount", new BigDecimal("9999999999.12345678")));
                        assertThat(process.getRuntimeService().getVariable(instance.getId(), "result")).isEqualTo(new BigDecimal("9007199254740993"));
                        var fallback = process.getRuntimeService().startProcessInstanceByKey("preciseProcess", Map.of("amount", BigDecimal.ZERO));
                        assertThat(process.getRuntimeService().getVariable(fallback.getId(), "result")).isEqualTo(new BigDecimal("-9999999999.12345678"));
                        assertThat(dmn.getDmnHistoryService().createHistoricDecisionExecutionQuery().count()).isEqualTo(2);
                    }
                    finally
                    {
                        artifacts.delete(deployment.getId());
                        process.getRepositoryService().deleteDeployment(deployment.getId(), true);
                        decisions.delete(source);
                    }
                });
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
