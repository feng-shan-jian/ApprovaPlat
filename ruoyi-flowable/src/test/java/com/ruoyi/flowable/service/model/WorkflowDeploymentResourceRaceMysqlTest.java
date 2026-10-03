package com.ruoyi.flowable.service.model;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfCategory;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.WfForm;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfCategoryMapper;
import com.ruoyi.flowable.mapper.WfFormMapper;
import com.ruoyi.flowable.mapper.WfProcessDraftMapper;
import com.ruoyi.flowable.mapper.WorkflowProcessDefinitionLockMapper;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.impl.FlowableWorkflowReferenceChecker;
import com.ruoyi.flowable.service.impl.WfCategoryServiceImpl;
import com.ruoyi.flowable.service.impl.WfFormServiceImpl;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real deployment removal must not move a reference between a deletion check's two reads. */
class WorkflowDeploymentResourceRaceMysqlTest
{
    @Test
    void restoringHistoryReadsTemplateIdsWithoutRevalidatingEmbeddedAuthorFields()
    {
        var bpmn = new WorkflowBpmnService(null, null, null);
        String xml = """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:flowable="http://flowable.org/bpmn" targetNamespace="test">
                  <process id="historical" isExecutable="true">
                    <startEvent id="start"><extensionElements>
                      <flowable:formProperty id="initiator" name="Legacy" type="retiredCustomType"/>
                    </extensionElements></startEvent>
                    <subProcess id="nested">
                      <startEvent id="nestedStart" flowable:formKey="key_42"/>
                      <userTask id="task" flowable:formKey="key_43"/>
                    </subProcess>
                  </process>
                </definitions>
                """;
        assertEquals(Set.of(42L, 43L), bpmn.readTemplateReferenceIds(xml.getBytes(StandardCharsets.UTF_8)));
        assertThrows(ServiceException.class, () -> bpmn.readTemplateReferenceIds(
                ("<!DOCTYPE definitions [<!ENTITY external SYSTEM 'file:///unavailable'>]>" + xml)
                        .getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @CsvSource({"CATEGORY,false", "METADATA,false", "CATEGORY,true", "METADATA,true", "XML,true",
            "REFERENCE,false", "LEGACY,false", "LEGACY_ROLLBACK,false", "MISSING_XML,false", "MISSING_CATEGORY,false"})
    void restoringDeployedModelCannotRaceResourceDeletion(String resource, boolean restorationFirst) throws Exception
    {
        boolean categoryCase = "CATEGORY".equals(resource);
        boolean race = List.of("CATEGORY", "METADATA", "XML").contains(resource);
        String url = System.getenv("WORKFLOW_MYSQL_TEST_URL");
        assumeTrue(url != null && url.matches(".*[/][^/?]+_it(?:[?].*)?"));
        var ds = new DriverManagerDataSource(url, System.getenv("WORKFLOW_MYSQL_TEST_USERNAME"),
                System.getenv("WORKFLOW_MYSQL_TEST_PASSWORD"));
        try (var fixture = WorkflowFlowableEngineTestSupport.start(ds, Map.of()))
        {
            var repository = fixture.processEngine().getRepositoryService();
            var categories = WorkflowH2SchemaMapperSupport.createSpringMapper(ds, "c01-restore-category", WfCategoryMapper.class,
                    "mapper/flowable/WfCategoryMapper.xml");
            var forms = WorkflowH2SchemaMapperSupport.createSpringMapper(ds, "c01-restore-form", WfFormMapper.class,
                    "mapper/flowable/WfFormMapper.xml");
            var drafts = WorkflowH2SchemaMapperSupport.createSpringMapper(ds, "c01-restore-draft", WfProcessDraftMapper.class,
                    "mapper/flowable/WfProcessDraftMapper.xml");
            var locks = WorkflowH2SchemaMapperSupport.createSpringMapper(ds, "c01-restore-deployment", WorkflowProcessDefinitionLockMapper.class,
                    "mapper/flowable/WorkflowProcessDefinitionLockMapper.xml");
            var artifacts = spy(new WorkflowDeploymentArtifactRepository(repository));
            var firstRead = new CountDownLatch(1);
            var resumeCheck = new CountDownLatch(1);
            var checkerRepository = spy(repository);
            doAnswer(call -> {
                var query = spy(repository.createModelQuery());
                if (race && !restorationFirst && !categoryCase)
                {
                    doAnswer(read -> {
                        Object result = read.callRealMethod();
                        firstRead.countDown();
                        assertTrue(resumeCheck.await(15, TimeUnit.SECONDS));
                        return result;
                    }).when(query).list();
                }
                else if (race && !restorationFirst)
                {
                    doAnswer(read -> {
                        Object result = read.callRealMethod();
                        assertEquals(0L, result);
                        firstRead.countDown();
                        assertTrue(resumeCheck.await(15, TimeUnit.SECONDS));
                        return result;
                    }).when(query).count();
                }
                return query;
            }).when(checkerRepository).createModelQuery();
            var checker = new FlowableWorkflowReferenceChecker(checkerRepository, artifacts);
            var validator = new WorkflowFormTemplateValidator();
            var categoryService = fixture.transactionalProxy(new WfCategoryServiceImpl(categories, checker));
            var formService = fixture.transactionalProxy(new WfFormServiceImpl(forms, checker, validator));
            var identities = mock(WorkflowIdentityResolver.class);
            when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("1", Set.of()));
            var operations = fixture.transactionalProxy(new WorkflowEngineOperations(new WorkflowAuthenticationContext(
                    fixture.processEngine().getIdentityService(), new WorkflowIdentityCodec()), new WorkflowExceptionTranslator(), identities));
            var bpmn = new WorkflowBpmnService(repository, null, null);
            var catalog = new WorkflowAuthorFieldCatalogService(bpmn, forms, validator, mock(WorkflowConditionDeploymentService.class));
            var models = new WorkflowModelService(operations, repository, bpmn, categories, catalog, validator,
                    mock(WorkflowPublicationArtifactsService.class), mock(WorkflowCallActivityReferenceService.class));
            var deploymentRepository = spy(repository);
            var deployments = new WorkflowDeploymentService(operations, deploymentRepository, artifacts, drafts, locks,
                    mock(WorkflowDmnDecisionService.class), mock(WorkflowCallActivityReferenceService.class), models);
            String key = "restore_rule_" + UUID.randomUUID().toString().replace("-", "");
            var category = new WfCategory();
            category.setCategoryName(key); category.setCode(key); category.setCreateBy("c01");
            categoryService.insertCategory(category);
            var form = new WfForm(); form.setFormName(key); form.setContent("{\"fields\":[]}"); form.setCreateBy("c01");
            formService.insertForm(form);
            var xmlForm = new WfForm(); xmlForm.setFormName(key + "_xml"); xmlForm.setContent("{\"fields\":[]}"); xmlForm.setCreateBy("c01");
            formService.insertForm(xmlForm);
            String deploymentId = null;
            String modelId = null;
            var pool = Executors.newFixedThreadPool(2);
            var resumeRestore = new CountDownLatch(1);
            try
            {
                String xml = """
                        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                            xmlns:flowable="http://flowable.org/bpmn" targetNamespace="test">
                          <process id="%s" isExecutable="true">
                            <extensionElements><flowable:properties>
                              <flowable:property name="approva.startScope.ruleVersion" value="1"/>
                              <flowable:property name="approva.startScope.type" value="PUBLIC"/>
                              <flowable:property name="approva.startScope.targetIds" value=""/>
                              <flowable:property name="approva.startScope.noMatchPolicy" value="FAIL"/>
                            </flowable:properties></extensionElements>
                            <startEvent id="start" flowable:formKey="key_%s"><extensionElements>
                              <flowable:formProperty id="approva_permission_default" type="string"
                                  readable="true" writable="true"/>
                            </extensionElements></startEvent>
                            <sequenceFlow id="flow" sourceRef="start" targetRef="end"/>
                            <endEvent id="end"/>
                          </process>
                        </definitions>
                        """.formatted(key, xmlForm.getFormId());
                bpmn.validateDraft(xml.getBytes(StandardCharsets.UTF_8));
                deploymentId = repository.createDeployment().category(key).addString(key + ".bpmn20.xml", xml).deploy().getId();
                var definition = repository.createProcessDefinitionQuery().deploymentId(deploymentId).singleResult();
                repository.setProcessDefinitionCategory(definition.getId(), key);
                var snapshot = new WfDeployForm();
                snapshot.setSourceType("TEMPLATE"); snapshot.setFormId(xmlForm.getFormId());
                snapshot.setFormKey("key_" + xmlForm.getFormId()); snapshot.setNodeKey("start");
                snapshot.setFormName(key); snapshot.setNodeName("start"); snapshot.setContent("{\"fields\":[]}");
                artifacts.persist(deploymentId, new WorkflowDeploymentArtifacts(List.of(snapshot), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of(), List.of()));
                var model = repository.newModel();
                model.setKey(key); model.setName(key); model.setCategory(key); model.setDeploymentId(deploymentId);
                // A saved model can retain the original metadata form after its XML selects a different template.
                model.setMetaInfo("{\"formType\":0,\"formId\":" + form.getFormId() + "}");
                repository.saveModel(model); modelId = model.getId();
                String originalMetadata = model.getMetaInfo();
                repository.addModelEditorSource(modelId, xml.getBytes(StandardCharsets.UTF_8));
                assertFalse(artifacts.hasFormReference(List.of(form.getFormId())));
                if (resource.startsWith("MISSING_"))
                {
                    if ("MISSING_XML".equals(resource))
                        forms.logicalDelete(List.of(xmlForm.getFormId()), "invalid_legacy_fixture");
                    else
                        categories.logicalDelete(List.of(category.getCategoryId()), "invalid_legacy_fixture");
                    String target = deploymentId;
                    assertEquals(404, assertThrows(ServiceException.class,
                            () -> deployments.deleteDeployments(List.of(target))).getCode());
                    assertEquals(originalMetadata, repository.getModel(modelId).getMetaInfo());
                    assertEquals(target, repository.getModel(modelId).getDeploymentId());
                    assertEquals(1, artifacts.openRead(target).forms().size());
                    assertEquals(1, repository.createDeploymentQuery().deploymentId(target).count());
                    return;
                }
                if ("REFERENCE".equals(resource))
                {
                    assertEquals(409, assertThrows(ServiceException.class,
                            () -> formService.deleteWithValidByIds(List.of(form.getFormId()), "c01")).getCode(),
                            "deployed metadata must protect the same form reference as restoration");
                    deployments.deleteDeployments(List.of(deploymentId));
                    assertEquals(originalMetadata, repository.getModel(modelId).getMetaInfo(),
                            "valid metadata references must remain unchanged");
                    models.deleteModels(List.of(modelId));
                    assertEquals(1, formService.deleteWithValidByIds(List.of(form.getFormId()), "c01"));
                    return;
                }
                if (resource.startsWith("LEGACY"))
                {
                    // Stored state allowed by the prior public form deletion contract, before this repair.
                    assertEquals(1, forms.logicalDelete(List.of(form.getFormId()), "prior_release_fixture"));
                    String target = deploymentId;
                    if ("LEGACY_ROLLBACK".equals(resource))
                    {
                        doThrow(new org.flowable.common.engine.api.FlowableException("controlled deletion failure"))
                                .when(deploymentRepository).deleteDeployment(target);
                        assertEquals(409, assertThrows(ServiceException.class,
                                () -> deployments.deleteDeployments(List.of(target))).getCode());
                        assertEquals(originalMetadata, repository.getModel(modelId).getMetaInfo(),
                                "failed deletion must roll back metadata repair");
                        assertEquals(target, repository.getModel(modelId).getDeploymentId());
                        assertEquals(1, artifacts.openRead(target).forms().size());
                        assertEquals(1, repository.createDeploymentQuery().deploymentId(target).count());
                        doCallRealMethod().when(deploymentRepository).deleteDeployment(target);
                    }
                    assertDoesNotThrow(() -> deployments.deleteDeployments(List.of(target)),
                            "a legacy missing metadata-only form must not trap deployment and model cleanup");
                    var restored = models.getModel(modelId);
                    assertEquals(2, restored.formType());
                    assertNull(restored.formId());
                    assertEquals(xml, restored.bpmnXml(), "repair must preserve the actual author XML");
                    assertFalse(repository.getModel(modelId).getMetaInfo().contains("\"formId\""),
                            "restoration must remove the stale reference, not merely bypass its check");
                    var edit = new com.ruoyi.flowable.domain.dto.WorkflowModelDto();
                    edit.setModelId(modelId); edit.setModelName(key + "_recovered"); edit.setFormType(2);
                    models.updateModel(edit);
                    assertEquals(key + "_recovered", models.getModel(modelId).modelName());
                    models.deleteModels(List.of(modelId));
                    assertNull(repository.getModel(modelId));
                    assertEquals(0, repository.createDeploymentQuery().deploymentId(deploymentId).count());
                    return;
                }
                String targetDeployment = deploymentId;
                long targetForm = "XML".equals(resource) ? xmlForm.getFormId() : form.getFormId();
                var deletionStarted = new CountDownLatch(1);
                java.util.concurrent.Callable<Integer> deleteResource = () -> {
                    deletionStarted.countDown();
                    try
                    {
                        return categoryCase ? categoryService.deleteWithValidByIds(List.of(category.getCategoryId()), "c01")
                                : formService.deleteWithValidByIds(List.of(targetForm), "c01");
                    }
                    catch (ServiceException conflict)
                    {
                        assertEquals(409, conflict.getCode());
                        return 0;
                    }
                };
                java.util.concurrent.Future<Integer> deletion;
                java.util.concurrent.Future<?> removal;
                if (restorationFirst)
                {
                    var locked = new CountDownLatch(1);
                    doAnswer(call -> {
                        locked.countDown();
                        assertTrue(resumeRestore.await(15, TimeUnit.SECONDS));
                        return call.callRealMethod();
                    }).when(artifacts).delete(targetDeployment);
                    removal = pool.submit(() -> deployments.deleteDeployments(List.of(targetDeployment)));
                    assertTrue(locked.await(10, TimeUnit.SECONDS));
                    deletion = pool.submit(deleteResource);
                    assertTrue(deletionStarted.await(10, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> deletion.get(500, TimeUnit.MILLISECONDS));
                    resumeRestore.countDown();
                }
                else
                {
                    deletion = pool.submit(deleteResource);
                    assertTrue(firstRead.await(10, TimeUnit.SECONDS));
                    removal = pool.submit(() -> deployments.deleteDeployments(List.of(targetDeployment)));
                    // Before the fix removal commits in this gap; afterwards the resource lock makes it wait.
                    try { removal.get(500, TimeUnit.MILLISECONDS); } catch (TimeoutException expectedLock) { }
                    resumeCheck.countDown();
                }
                int deleted = deletion.get(10, TimeUnit.SECONDS);
                assertEquals(0, deleted, "all model states must protect their active metadata and XML references");
                removal.get(10, TimeUnit.SECONDS);
                assertNotNull(categoryCase ? categories.selectById(category.getCategoryId()) : forms.selectById(targetForm));
                assertNull(repository.getModel(modelId).getDeploymentId());
                assertEquals(0, repository.createDeploymentQuery().deploymentId(deploymentId).count());
            }
            finally
            {
                resumeCheck.countDown(); resumeRestore.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
                if (modelId != null && repository.getModel(modelId) != null) repository.deleteModel(modelId);
                if (deploymentId != null)
                {
                    artifacts.delete(deploymentId);
                    if (repository.createDeploymentQuery().deploymentId(deploymentId).count() > 0) repository.deleteDeployment(deploymentId);
                }
                fixture.jdbcTemplate().update("delete from wf_form where form_id in (?,?)", form.getFormId(), xmlForm.getFormId());
                fixture.jdbcTemplate().update("delete from wf_category where category_id=?", category.getCategoryId());
            }
        }
    }
}
