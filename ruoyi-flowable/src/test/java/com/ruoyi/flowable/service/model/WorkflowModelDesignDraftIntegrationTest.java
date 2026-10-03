package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.flowable.engine.impl.persistence.entity.ModelEntity;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.domain.*;
import com.ruoyi.flowable.engine.*;
import com.ruoyi.flowable.extension.*;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.listener.*;
import com.ruoyi.flowable.mapper.*;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.task.*;

/** 草稿合同使用最小模型、H2 及真实事务，不装配发布编译器。 */
class WorkflowModelDesignDraftIntegrationTest
{
    @Test
    void isolatesOwnersAndEnforcesBothRevisionsWithoutReadingPublishedSource()
    {
        try (var fixture = com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport.start("model-draft", Map.of()))
        {
        var identities = mock(WorkflowIdentityResolver.class);
        when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("100", Set.of()));
        var operations = fixture.transactionalProxy(new WorkflowEngineOperations(new WorkflowAuthenticationContext(
                fixture.processEngine().getIdentityService(), new WorkflowIdentityCodec()), new WorkflowExceptionTranslator(), identities));
        var repository = spy(fixture.processEngine().getRepositoryService());
        var forms = mock(WfFormMapper.class);
        var form = new WfForm(); form.setFormId(7L); form.setDelFlag("0"); form.setFormName("draft"); form.setContent("{\"fields\":[]}");
        when(forms.selectById(7L)).thenReturn(form);
        var bpmn = new WorkflowBpmnService(repository, new WorkflowFormFieldExtensionService(null), null);
        var models = new WorkflowModelService(operations, repository, bpmn, null,
                new WorkflowAuthorFieldCatalogService(bpmn, forms, new WorkflowFormTemplateValidator(), new WorkflowConditionDeploymentService()),
                new WorkflowFormTemplateValidator(), null, null);
        String xml = """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="draft"><process id="compiledParent" isExecutable="true"><extensionElements><flowable:properties><flowable:property name="approva.startScope.ruleVersion" value="1"/><flowable:property name="approva.startScope.type" value="PUBLIC"/><flowable:property name="approva.startScope.targetIds" value=""/><flowable:property name="approva.startScope.noMatchPolicy" value="FAIL"/></flowable:properties></extensionElements><startEvent id="start"/><endEvent id="end"/><sequenceFlow id="flow" sourceRef="start" targetRef="end"/></process></definitions>
                """.trim();
        var model = repository.newModel(); model.setKey("compiledParent"); model.setName("draft");
        model.setMetaInfo("{\"formType\":0,\"formId\":7}"); repository.saveModel(model);
        repository.addModelEditorSource(model.getId(), xml.getBytes(StandardCharsets.UTF_8));
        verifyDesignDraftIsolation(fixture.dataSource(), models, bpmn, operations, identities,
                repository, forms, model.getId(), ((ModelEntity) repository.getModel(model.getId())).getRevision(), xml);
        }
    }

    private void verifyDesignDraftIsolation(DataSource dataSource, WorkflowModelService models,
            WorkflowBpmnService bpmn, WorkflowEngineOperations operations, WorkflowIdentityResolver identities,
            org.flowable.engine.RepositoryService repository, WfFormMapper forms,
            String modelId, int revision, String xml)
    {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("""
                create table wf_model_design_draft (model_id varchar(64), owner_user_id varchar(64),
                    base_model_revision int not null, revision int not null, bpmn_xml clob,
                    updated_at timestamp default current_timestamp, primary key (model_id, owner_user_id))
                """);
        var drafts = new WorkflowModelDesignDraftService(dataSource, models, bpmn, operations, identities);
        clearInvocations(repository, forms);
        assertThat(drafts.read(modelId).revision()).isZero();
        verify(repository, times(1)).getModel(modelId);
        verify(repository, never()).getModelEditorSource(anyString());
        verifyNoInteractions(forms);
        var saved = drafts.save(modelId, new com.ruoyi.flowable.domain.dto.WorkflowModelDesignDraftRequest(revision, 0, xml));
        assertThat(saved.revision()).isEqualTo(1);
        assertThat(drafts.read(modelId).bpmnXml()).isEqualTo(xml);
        when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("200", Set.of()));
        assertThat(drafts.read(modelId).bpmnXml()).isNull();
        assertThatThrownBy(() -> drafts.discard(modelId, 1)).hasMessageContaining("已被更新");
        drafts.save(modelId, new com.ruoyi.flowable.domain.dto.WorkflowModelDesignDraftRequest(revision, 0, xml));
        when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("100", Set.of()));
        assertThatThrownBy(() -> drafts.save(modelId,
                new com.ruoyi.flowable.domain.dto.WorkflowModelDesignDraftRequest(revision, 0, xml)))
                .hasMessageContaining("已被更新");
        assertThatThrownBy(() -> drafts.save(modelId,
                new com.ruoyi.flowable.domain.dto.WorkflowModelDesignDraftRequest(revision + 1, 1, xml)))
                .hasMessageContaining("已被更新");
        assertThatThrownBy(() -> drafts.save(modelId,
                new com.ruoyi.flowable.domain.dto.WorkflowModelDesignDraftRequest(revision, 1, xml.replace("compiledParent", "wrongKey"))))
                .hasMessageContaining("当前模型所属流程");
        assertThatThrownBy(() -> drafts.save(modelId,
                new com.ruoyi.flowable.domain.dto.WorkflowModelDesignDraftRequest(revision, 1, "<!DOCTYPE x>" + xml)))
                .hasMessageContaining("DTD");
        assertThat(drafts.save(modelId,
                new com.ruoyi.flowable.domain.dto.WorkflowModelDesignDraftRequest(revision, 1, xml)).revision()).isEqualTo(2);
        assertThatThrownBy(() -> drafts.discard(modelId, 1)).hasMessageContaining("已被更新");
        assertThat(drafts.discard(modelId, 2).revision()).isEqualTo(3);
        assertThat(drafts.read(modelId).bpmnXml()).isNull();
        assertThatThrownBy(() -> drafts.save(modelId,
                new com.ruoyi.flowable.domain.dto.WorkflowModelDesignDraftRequest(revision, 0, xml)))
                .hasMessageContaining("已被更新");
        assertThat(drafts.save(modelId,
                new com.ruoyi.flowable.domain.dto.WorkflowModelDesignDraftRequest(revision, 3, xml)).revision()).isEqualTo(4);
        assertThatThrownBy(() -> drafts.read("missing-model")).hasMessageContaining("不存在");
        verify(repository, never()).getModelEditorSource(anyString());
        verifyNoInteractions(forms);
        assertThat(repository.getModelEditorSource(modelId)).isEqualTo(xml.getBytes(StandardCharsets.UTF_8));
        assertThat(models.getModel(modelId).bpmnXml()).isEqualTo(xml);
        verify(forms, times(1)).selectById(7L);
        when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("200", Set.of()));
        assertThat(drafts.read(modelId).revision()).isEqualTo(1);
        when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("100", Set.of()));
    }

}
