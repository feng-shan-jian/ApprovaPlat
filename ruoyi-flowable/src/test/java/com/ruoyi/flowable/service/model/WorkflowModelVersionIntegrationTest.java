package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.domain.WfCategory;
import com.ruoyi.flowable.domain.dto.WorkflowModelDto;
import com.ruoyi.flowable.engine.*;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.mapper.WfCategoryMapper;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;

/** 元数据和版本命令仅替换制品边界，不装配任何领域编译器。 */
class WorkflowModelVersionIntegrationTest
{
    @Test
    void preservesMetadataRevisionAndHistoricalCopyWithoutCompilerAssembly()
    {
        try (var fixture = WorkflowFlowableEngineTestSupport.start("model-version", Map.of()))
        {
            var repository = fixture.processEngine().getRepositoryService();
            var identities = mock(WorkflowIdentityResolver.class);
            when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("100", Set.of()));
            var operations = fixture.transactionalProxy(new WorkflowEngineOperations(new WorkflowAuthenticationContext(
                    fixture.processEngine().getIdentityService(), new WorkflowIdentityCodec()), new WorkflowExceptionTranslator(), identities));
            var categories = mock(WfCategoryMapper.class);
            var category = new WfCategory(); category.setCode("test"); category.setDelFlag("0");
            when(categories.selectByCodeForShare("test")).thenReturn(category);
            var publication = mock(WorkflowPublicationArtifactsService.class);
            var authorFields = mock(WorkflowAuthorFieldCatalogService.class);
            var form = new com.ruoyi.flowable.domain.WfForm(); form.setFormId(7L); form.setFormName("Version form");
            form.setContent("{\"fields\":[{\"layout\":\"colFormItem\",\"tag\":\"el-input\",\"vModel\":\"note\",\"workflowHidden\":false,\"workflowReadable\":true,\"workflowWritable\":true}]}");
            when(authorFields.requireActiveForm(7L)).thenReturn(form);
            var models = new WorkflowModelService(operations, repository,
                    new WorkflowBpmnService(repository, null, null), categories,
                    authorFields, new WorkflowFormTemplateValidator(),
                    publication, mock(WorkflowCallActivityReferenceService.class));
            var create = new WorkflowModelDto(); create.setModelKey("versions"); create.setModelName("Version One"); create.setCategory("test"); create.setFormType(0); create.setFormId(7L);
            String id = models.createModel(create);
            var initial = models.getModel(id);
            assertThat(initial.version()).isEqualTo(1);
            var update = new WorkflowModelDto(); update.setModelId(id); update.setModelName("Metadata Name"); update.setDescription("Version metadata");
            models.updateModel(update);
            var current = models.getModel(id);
            assertThat(current.modelName()).isEqualTo("Metadata Name");
            assertThat(current.description()).isEqualTo("Version metadata");
            assertThat(current.revision()).isGreaterThan(initial.revision());
            String changed = current.bpmnXml().replace("Version One", "Version Two");
            var save = new WorkflowModelDto(); save.setModelId(id); save.setExpectedRevision(initial.revision()); save.setBpmnXml(changed);
            assertThatThrownBy(() -> models.saveModel(save)).isInstanceOfSatisfying(com.ruoyi.common.exception.ServiceException.class, failure -> {
                assertThat(failure.getCode()).isEqualTo(409);
                assertThat(failure.getSubCode()).isEqualTo(WorkflowModelService.MODEL_VERSION_CONFLICT_SUB_CODE);
            });
            save.setExpectedRevision(current.revision());
            var saved = models.saveModel(save);
            assertThat(saved.modelId()).isEqualTo(id);
            assertThat(saved.revision()).isGreaterThan(current.revision());
            verify(publication, times(1)).validateAuthorReferences(any(), any());
            // 另一个最新模型使原模型成为历史版本；历史保存必须复制，保留旧正文和元数据。
            var newer = repository.newModel(); newer.setKey("versions"); newer.setName("newer"); newer.setCategory("test");
            newer.setVersion(2); newer.setMetaInfo(repository.getModel(id).getMetaInfo()); repository.saveModel(newer);
            repository.addModelEditorSource(newer.getId(), changed.getBytes(StandardCharsets.UTF_8));
            String historicalBytes = new String(repository.getModelEditorSource(id), StandardCharsets.UTF_8);
            save.setExpectedRevision(saved.revision()); save.setBpmnXml(changed.replace("Version Two", "Version Three"));
            var copied = models.saveModel(save);
            assertThat(copied.modelId()).isNotEqualTo(id);
            assertThat(copied.version()).isEqualTo(3);
            assertThat(repository.getModelEditorSource(id)).isEqualTo(historicalBytes.getBytes(StandardCharsets.UTF_8));
            assertThat(models.getModel(copied.modelId()).description()).isEqualTo("Version metadata");
            var history = new WorkflowModelDto(); history.setModelKey("versions");
            assertThat(models.historyList(history, 1, 10).total()).isEqualTo(2);
            verify(publication, never()).prepare(any(), any());
            verify(publication, never()).freezeArtifacts(anyString(), any(), any());
        }
    }
}
