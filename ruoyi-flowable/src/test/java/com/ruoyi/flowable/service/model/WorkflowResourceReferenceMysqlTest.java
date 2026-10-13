package com.ruoyi.flowable.service.model;

import com.ruoyi.common.testsupport.WorkflowMysqlTargetGuard;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.ruoyi.flowable.domain.*;
import com.ruoyi.flowable.domain.dto.WorkflowModelDto;
import com.ruoyi.flowable.engine.*;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.mapper.*;
import com.ruoyi.flowable.service.*;
import com.ruoyi.flowable.service.impl.*;
import com.ruoyi.flowable.testsupport.*;

/** C01：真实模型写事务读取资源后暂停，与正式删除事务交错。只清理本用例主键。 */
class WorkflowResourceReferenceMysqlTest
{
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionCannotLeaveANewModelReferencingAnInactiveResource(boolean formCase) throws Exception
    {
        String url = System.getenv("WORKFLOW_MYSQL_TEST_URL");
        assumeTrue(url != null, "WORKFLOW_MYSQL_TEST_URL required");
        WorkflowMysqlTargetGuard.verify(url);
        var ds = new DriverManagerDataSource(url, System.getenv("WORKFLOW_MYSQL_TEST_USERNAME"),
                System.getenv("WORKFLOW_MYSQL_TEST_PASSWORD"));
        try (var fixture = WorkflowFlowableEngineTestSupport.start(ds, Map.of()))
        {
            var repository = fixture.processEngine().getRepositoryService();
            var categories = WorkflowH2SchemaMapperSupport.createSpringMapper(ds, "c01-category", WfCategoryMapper.class,
                    "mapper/flowable/WfCategoryMapper.xml");
            var forms = WorkflowH2SchemaMapperSupport.createSpringMapper(ds, "c01-form", WfFormMapper.class,
                    "mapper/flowable/WfFormMapper.xml");
            var validator = new WorkflowFormTemplateValidator();
            var checker = new FlowableWorkflowReferenceChecker(repository, new WorkflowDeploymentArtifactRepository(repository));
            IWfCategoryService categoryService = fixture.transactionalProxy(new WfCategoryServiceImpl(categories, checker));
            IWfFormService formService = fixture.transactionalProxy(new WfFormServiceImpl(forms, checker, validator));
            String key = "root_rule_" + UUID.randomUUID().toString().replace("-", "");
            var category = new WfCategory(); category.setCategoryName(key); category.setCode(key); category.setCreateBy("c01");
            categoryService.insertCategory(category);
            var form = new WfForm(); form.setFormName(key); form.setContent("{\"fields\":[]}"); form.setCreateBy("c01");
            formService.insertForm(form);
            var identities = mock(WorkflowIdentityResolver.class);
            when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("1", Set.of()));
            var operations = fixture.transactionalProxy(new WorkflowEngineOperations(new WorkflowAuthenticationContext(
                    fixture.processEngine().getIdentityService(), new WorkflowIdentityCodec()), new WorkflowExceptionTranslator(), identities));
            var read = new CountDownLatch(1);
            var resume = new CountDownLatch(1);
            var bpmn = spy(new WorkflowBpmnService(repository, null, null));
            doAnswer(call -> {
                Object validated = call.callRealMethod();
                read.countDown();
                if (!resume.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("C01 barrier timed out");
                return validated;
            }).when(bpmn).validateDraft(any(byte[].class));
            var catalog = new WorkflowAuthorFieldCatalogService(bpmn, forms, validator, mock(WorkflowConditionDeploymentService.class));
            var models = new WorkflowModelService(operations, repository, bpmn, categories, catalog, validator,
                    mock(WorkflowPublicationArtifactsService.class), mock(WorkflowCallActivityReferenceService.class));
            var request = new WorkflowModelDto(); request.setModelKey(key); request.setModelName(key);
            request.setCategory(key); request.setFormType(formCase ? 0 : 2); if (formCase) request.setFormId(form.getFormId());
            var pool = Executors.newFixedThreadPool(2);
            String modelId = null;
            try
            {
                var creation = pool.submit(() -> models.createModel(request));
                assertTrue(read.await(10, TimeUnit.SECONDS));
                var deletion = pool.submit(() -> {
                    try {
                        return formCase ? formService.deleteWithValidByIds(List.of(form.getFormId()), "c01")
                                : categoryService.deleteWithValidByIds(List.of(category.getCategoryId()), "c01");
                    } catch (com.ruoyi.common.exception.ServiceException conflict) {
                        assertEquals(409, conflict.getCode()); return 0;
                    }
                });
                // 修复前删除立即提交；修复后应被资源锁阻挡，待模型提交后拒绝删除。
                try { deletion.get(500, TimeUnit.MILLISECONDS); } catch (TimeoutException expectedLock) { }
                resume.countDown();
                modelId = creation.get(10, TimeUnit.SECONDS);
                int deleted = deletion.get(10, TimeUnit.SECONDS);
                assertEquals(0, deleted, "模型和资源删除均提交会留下悬空引用");
                assertNotNull(formCase ? forms.selectById(form.getFormId()) : categories.selectById(category.getCategoryId()));
                assertNotNull(repository.getModel(modelId));
                repository.deleteModel(modelId);
                assertEquals(1, formCase ? formService.deleteWithValidByIds(List.of(form.getFormId()), "c01")
                        : categoryService.deleteWithValidByIds(List.of(category.getCategoryId()), "c01"));
                assertEquals(404, assertThrows(com.ruoyi.common.exception.ServiceException.class,
                        () -> models.createModel(request)).getCode(), "删除先提交时，新引用必须拒绝");
            }
            finally
            {
                resume.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
                for (var model : repository.createModelQuery().modelKey(key).list()) repository.deleteModel(model.getId());
                fixture.jdbcTemplate().update("delete from wf_form where form_id=?", form.getFormId());
                fixture.jdbcTemplate().update("delete from wf_category where category_id=?", category.getCategoryId());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void promotionRechecksReferencesAfterItsSourceModelAndResourceAreDeleted(boolean formCase) throws Exception
    {
        assertVersionCreationRechecksReferences(formCase, true);
    }

    @Test
    void historicalSaveRechecksCopiedMetadataFormWhenIncomingXmlUsesAnotherForm() throws Exception
    {
        assertVersionCreationRechecksReferences(true, false);
    }

    private void assertVersionCreationRechecksReferences(boolean formCase, boolean promote) throws Exception
    {
        String url = System.getenv("WORKFLOW_MYSQL_TEST_URL");
        assumeTrue(url != null, "WORKFLOW_MYSQL_TEST_URL required");
        WorkflowMysqlTargetGuard.verify(url);
        var ds = new DriverManagerDataSource(url, System.getenv("WORKFLOW_MYSQL_TEST_USERNAME"),
                System.getenv("WORKFLOW_MYSQL_TEST_PASSWORD"));
        try (var fixture = WorkflowFlowableEngineTestSupport.start(ds, Map.of()))
        {
            var repository = fixture.processEngine().getRepositoryService();
            var categories = WorkflowH2SchemaMapperSupport.createSpringMapper(ds, "c01-promote-category", WfCategoryMapper.class,
                    "mapper/flowable/WfCategoryMapper.xml");
            var forms = WorkflowH2SchemaMapperSupport.createSpringMapper(ds, "c01-promote-form", WfFormMapper.class,
                    "mapper/flowable/WfFormMapper.xml");
            var validator = new WorkflowFormTemplateValidator();
            var checker = new FlowableWorkflowReferenceChecker(repository, new WorkflowDeploymentArtifactRepository(repository));
            IWfCategoryService categoryService = fixture.transactionalProxy(new WfCategoryServiceImpl(categories, checker));
            IWfFormService formService = fixture.transactionalProxy(new WfFormServiceImpl(forms, checker, validator));
            var identities = mock(WorkflowIdentityResolver.class);
            when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("1", Set.of()));
            var operations = fixture.transactionalProxy(new WorkflowEngineOperations(new WorkflowAuthenticationContext(
                    fixture.processEngine().getIdentityService(), new WorkflowIdentityCodec()), new WorkflowExceptionTranslator(), identities));
            var bpmn = spy(new WorkflowBpmnService(repository, null, null));
            var catalog = new WorkflowAuthorFieldCatalogService(bpmn, forms, validator, mock(WorkflowConditionDeploymentService.class));
            var publication = mock(WorkflowPublicationArtifactsService.class);
            // 使用实际作者目录锁定 XML 引用，隔离与本竞争无关的参与者、DMN 等校验。
            doAnswer(call -> {
                WorkflowBpmnDocument document = call.getArgument(0);
                catalog.resolveForms(document.formReferences());
                return null;
            }).when(publication).validateAuthorReferences(any(), any());
            var models = new WorkflowModelService(operations, repository, bpmn, categories, catalog, validator,
                    publication, mock(WorkflowCallActivityReferenceService.class));
            String key = "root_rule_" + UUID.randomUUID().toString().replace("-", "");
            var resources = new ArrayList<WfCategory>();
            var templates = new ArrayList<WfForm>();
            var read = new CountDownLatch(1);
            var resume = new CountDownLatch(1);
            var pool = Executors.newSingleThreadExecutor();
            try
            {
                for (String resourceKey : List.of(key, key + "_next"))
                {
                    var category = new WfCategory();
                    category.setCategoryName(resourceKey); category.setCode(resourceKey); category.setCreateBy("c01");
                    categoryService.insertCategory(category); resources.add(category);
                    var form = new WfForm();
                    form.setFormName(resourceKey); form.setContent("{\"fields\":[]}"); form.setCreateBy("c01");
                    formService.insertForm(form); templates.add(form);
                }
                var sourceRequest = new WorkflowModelDto();
                sourceRequest.setModelKey(key); sourceRequest.setModelName(key); sourceRequest.setCategory(key);
                sourceRequest.setFormType(0); sourceRequest.setFormId(templates.get(0).getFormId());
                String sourceId = models.createModel(sourceRequest);
                var latestRequest = new WorkflowModelDto();
                latestRequest.setModelKey(key + "_next"); latestRequest.setModelName(key + "_next");
                latestRequest.setCategory(key + "_next"); latestRequest.setFormType(0);
                latestRequest.setFormId(templates.get(1).getFormId());
                String latestId = models.createModel(latestRequest);
                // 第二个版本不引用旧版本独占的分类或表单，使旧模型删除后资源确实可删除。
                var latest = repository.getModel(latestId);
                latest.setKey(key); latest.setVersion(2); repository.saveModel(latest);
                var source = models.getModel(sourceId);
                var save = new WorkflowModelDto();
                save.setModelId(sourceId); save.setExpectedRevision(source.revision());
                save.setBpmnXml(source.bpmnXml().replace("key_" + templates.get(0).getFormId(),
                        "key_" + templates.get(1).getFormId()));
                Answer<Object> pauseAfterSourceRead = call -> {
                    Object validated = call.callRealMethod();
                    read.countDown();
                    if (!resume.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("C01 version creation barrier timed out");
                    return validated;
                };
                if (promote) doAnswer(pauseAfterSourceRead).when(bpmn).validate(any(byte[].class));
                else doAnswer(pauseAfterSourceRead).when(bpmn).validateForSave(any(byte[].class));

                var creation = pool.submit(() -> promote ? models.promoteToLatest(sourceId) : models.saveModel(save).modelId());
                assertTrue(read.await(10, TimeUnit.SECONDS), "另存事务必须先读取旧模型和源码");
                models.deleteModels(List.of(sourceId));
                assertNull(repository.getModel(sourceId));
                assertEquals(1, formCase
                        ? formService.deleteWithValidByIds(List.of(templates.get(0).getFormId()), "c01")
                        : categoryService.deleteWithValidByIds(List.of(resources.get(0).getCategoryId()), "c01"));
                resume.countDown();

                var failure = assertThrows(ExecutionException.class, () -> creation.get(10, TimeUnit.SECONDS));
                var rejected = assertInstanceOf(com.ruoyi.common.exception.ServiceException.class, failure.getCause());
                assertEquals(404, rejected.getCode(), "另存必须以锁定读取发现已提交的资源删除");
                assertNull(formCase ? forms.selectById(templates.get(0).getFormId())
                        : categories.selectById(resources.get(0).getCategoryId()));
                var remaining = repository.createModelQuery().modelKey(key).list();
                assertEquals(1, remaining.size(), "不可提交引用已删除资源的新版本");
                assertEquals(latestId, remaining.get(0).getId());
                assertEquals(2, remaining.get(0).getVersion());
            }
            finally
            {
                resume.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
                for (String modelKey : List.of(key, key + "_next"))
                    for (var model : repository.createModelQuery().modelKey(modelKey).list()) repository.deleteModel(model.getId());
                for (var form : templates)
                    fixture.jdbcTemplate().update("delete from wf_form where form_id=?", form.getFormId());
                for (var category : resources)
                    fixture.jdbcTemplate().update("delete from wf_category where category_id=?", category.getCategoryId());
            }
        }
    }
}
