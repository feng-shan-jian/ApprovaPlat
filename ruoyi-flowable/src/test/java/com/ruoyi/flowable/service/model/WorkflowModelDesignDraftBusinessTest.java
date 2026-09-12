package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowModelDesignDraftRequest;
import com.ruoyi.flowable.domain.vo.WorkflowModelView;
import com.ruoyi.flowable.engine.*;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;

/** 真实数据库 CAS、回滚与身份隔离；模型元数据使用目录替身，草稿不会触发部署。 */
class WorkflowModelDesignDraftBusinessTest
{
    private WorkflowFlowableEngineTestSupport support;
    private WorkflowModelDesignDraftService drafts;
    private WorkflowModelService models;
    private WorkflowIdentityResolver identities;
    private static final String XML = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"urn:test:draft\"><process id=\"draftProcess\" isExecutable=\"true\"><startEvent id=\"start\"/><intermediateThrowEvent id=\"message\"><messageEventDefinition/></intermediateThrowEvent></process></definitions>";

    @BeforeEach void open()
    {
        support = WorkflowFlowableEngineTestSupport.start("design-draft", Map.of());
        support.jdbcTemplate().execute("CREATE TABLE wf_model_design_draft (model_id VARCHAR(64), owner_user_id VARCHAR(64), base_model_revision INT NOT NULL, revision INT NOT NULL, bpmn_xml CLOB, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(model_id,owner_user_id))");
        models = mock(WorkflowModelService.class);
        identities = mock(WorkflowIdentityResolver.class);
        when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("100", Set.of()));
        when(models.getModel("model")).thenReturn(model(1));
        when(models.getModel("new-version")).thenReturn(model(1));
        var engine = support.processEngine();
        var operations = support.transactionalProxy(new WorkflowEngineOperations(new WorkflowAuthenticationContext(
                engine.getIdentityService(), new WorkflowIdentityCodec()), new WorkflowExceptionTranslator(), identities));
        drafts = new WorkflowModelDesignDraftService(support.dataSource(), models,
                new WorkflowBpmnService(engine.getRepositoryService(), new WorkflowFormFieldExtensionService(null), null), operations, identities);
    }

    @AfterEach void close() { support.close(); }

    @Test void incompleteBusinessConfigurationPersistsAndSurvivesReloadWithoutDeploying()
    {
        var saved = drafts.save("model", request(1, 0, XML));
        assertThat(saved.revision()).isEqualTo(1);
        assertThat(drafts.read("model").bpmnXml()).isEqualTo(XML);
        assertThat(drafts.read("new-version").bpmnXml()).isNull();
        assertThat(support.processEngine().getRepositoryService().createDeploymentQuery().count()).isZero();
        assertThat(support.processEngine().getRepositoryService().createModelQuery().count()).isZero();
    }

    @Test void staleTabAndStaleBaseFailWithoutOverwritingDraftAndDiscardDoesNotAllowAba()
    {
        drafts.save("model", request(1, 0, XML));
        assertConflict(() -> drafts.save("model", request(1, 0, XML.replace("message", "different"))));
        drafts.save("model", request(1, 1, XML));
        assertConflict(() -> drafts.save("model", request(1, 1, XML.replace("message", "different"))));
        assertThat(drafts.read("model").bpmnXml()).isEqualTo(XML);
        when(models.getModel("model")).thenReturn(model(2));
        assertConflict(() -> drafts.save("model", request(1, 2, XML)));
        assertThat(drafts.read("model").currentModelRevision()).isEqualTo(2);
        drafts.discard("model", 2);
        assertThat(drafts.read("model").bpmnXml()).isNull();
        assertConflict(() -> drafts.save("model", request(2, 0, XML)));
        drafts.save("model", request(2, 3, XML));
        assertThat(drafts.read("model").revision()).isEqualTo(4);
    }

    @Test void anotherIdentityCannotReadChangeOrDiscardOwnerDraft()
    {
        drafts.save("model", request(1, 0, XML));
        when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("101", Set.of()));
        assertThat(drafts.read("model").bpmnXml()).isNull();
        assertConflict(() -> drafts.save("model", request(1, 1, XML)));
        assertConflict(() -> drafts.discard("model", 1));
        assertThat(support.jdbcTemplate().queryForObject("SELECT bpmn_xml FROM wf_model_design_draft WHERE owner_user_id='100'", String.class)).isEqualTo(XML);
    }

    @Test void invalidXmlAndModelBindingRollBackBeforeStorage()
    {
        for (String xml : new String[] { XML.replace("draftProcess", "other"), "<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///missing'>]>" + XML, "<broken>", "x".repeat(2 * 1024 * 1024 + 1) })
            assertThatThrownBy(() -> drafts.save("model", request(1, 0, xml))).isInstanceOf(RuntimeException.class);
        assertThat(support.jdbcTemplate().queryForObject("SELECT COUNT(*) FROM wf_model_design_draft", Integer.class)).isZero();
    }

    @Test void modelDeletionClearsOnlyItsDraftsAfterFormalDeletionGate()
    {
        drafts.save("model", request(1, 0, XML));
        drafts.save("new-version", request(1, 0, XML));
        when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("101", Set.of()));
        drafts.save("model", request(1, 0, XML));
        var ids = java.util.List.of("model");
        doThrow(new ServiceException("已部署模型不能删除", 409)).when(models).deleteModels(ids);
        assertThatThrownBy(() -> drafts.deleteModelsAndDrafts(ids)).isInstanceOf(ServiceException.class);
        assertThat(support.jdbcTemplate().queryForObject("SELECT COUNT(*) FROM wf_model_design_draft", Integer.class)).isEqualTo(3);
        doNothing().when(models).deleteModels(ids);
        drafts.deleteModelsAndDrafts(ids);
        assertThat(support.jdbcTemplate().queryForList("SELECT model_id FROM wf_model_design_draft", String.class)).containsExactly("new-version");
    }

    private WorkflowModelDesignDraftRequest request(int base, int revision, String xml)
    { return new WorkflowModelDesignDraftRequest(base, revision, xml); }

    private void assertConflict(Runnable action)
    {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ServiceException.class,
                exception -> assertThat(exception.getSubCode()).isEqualTo("MODEL_DESIGN_DRAFT_CONFLICT"));
    }

    private WorkflowModelView model(int revision)
    {
        return new WorkflowModelView("model", "草稿模型", "draftProcess", "test", 1, revision, null, null, null,
                null, null, XML, null, false, true, true, true);
    }
}
