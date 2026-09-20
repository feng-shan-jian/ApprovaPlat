package com.ruoyi.flowable.service.model;

import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowModelDesignDraftRequest;
import com.ruoyi.flowable.domain.vo.WorkflowModelDesignDraftView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;

/** 未完成配置仅进入独立草稿表，永不进入 Flowable 模型源码、部署或快照。 */
@Service
public class WorkflowModelDesignDraftService
{
    private final JdbcTemplate jdbc;
    private final WorkflowModelService models;
    private final WorkflowBpmnService bpmn;
    private final WorkflowEngineOperations operations;
    private final WorkflowIdentityResolver identities;

    public WorkflowModelDesignDraftService(DataSource dataSource, WorkflowModelService models,
            WorkflowBpmnService bpmn, WorkflowEngineOperations operations, WorkflowIdentityResolver identities)
    {
        this.jdbc = new JdbcTemplate(dataSource);
        this.models = models;
        this.bpmn = bpmn;
        this.operations = operations;
        this.identities = identities;
    }

    public WorkflowModelDesignDraftView read(String modelId)
    {
        return operations.read(() -> {
            var model = models.getModelMetadata(modelId);
            return readOwned(modelId, identities.resolveCurrentIdentity().userId(), model.revision());
        });
    }

    /** 正式模型删除门禁成功后，在同一事务中清理该模型所有作者的设计草稿。 */
    public void deleteModelsAndDrafts(java.util.Collection<String> modelIds)
    {
        operations.writeAsCurrentUser(actor -> {
            models.deleteModels(modelIds);
            for (String modelId : modelIds) jdbc.update("DELETE FROM wf_model_design_draft WHERE model_id=?", modelId);
            return null;
        });
    }

    public WorkflowModelDesignDraftView save(String modelId, WorkflowModelDesignDraftRequest request)
    {
        return operations.writeAsCurrentUser(actor -> {
            var model = models.getModelMetadata(modelId);
            if (!model.revision().equals(request.baseModelRevision())) throw conflict();
            String xml = bpmn.validateDesignDraft(request.bpmnXml().getBytes(StandardCharsets.UTF_8), model.modelKey());
            int expected = request.expectedRevision();
            if (expected < 0 || expected == Integer.MAX_VALUE) throw conflict();
            if (expected == 0)
            {
                try
                {
                    jdbc.update("INSERT INTO wf_model_design_draft (model_id,owner_user_id,base_model_revision,revision,bpmn_xml) VALUES (?,?,?,1,?)",
                            modelId, actor.userId(), model.revision(), xml);
                }
                catch (DuplicateKeyException exception) { throw conflict(); }
            }
            else if (jdbc.update("UPDATE wf_model_design_draft SET base_model_revision=?,revision=revision+1,bpmn_xml=?,updated_at=CURRENT_TIMESTAMP WHERE model_id=? AND owner_user_id=? AND revision=?",
                    model.revision(), xml, modelId, actor.userId(), expected) != 1) throw conflict();
            return new WorkflowModelDesignDraftView(modelId, model.revision(), expected + 1, xml, model.revision());
        });
    }

    public WorkflowModelDesignDraftView discard(String modelId, int expectedRevision)
    {
        return operations.writeAsCurrentUser(actor -> {
            var model = models.getModelMetadata(modelId);
            if (expectedRevision <= 0 || expectedRevision == Integer.MAX_VALUE || jdbc.update("UPDATE wf_model_design_draft SET bpmn_xml=NULL,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE model_id=? AND owner_user_id=? AND revision=?",
                    modelId, actor.userId(), expectedRevision) != 1) throw conflict();
            return new WorkflowModelDesignDraftView(modelId, model.revision(), expectedRevision + 1, null, model.revision());
        });
    }

    private WorkflowModelDesignDraftView readOwned(String modelId, String owner, int currentRevision)
    {
        var rows = jdbc.query("SELECT base_model_revision,revision,bpmn_xml FROM wf_model_design_draft WHERE model_id=? AND owner_user_id=?",
                (rs, row) -> new WorkflowModelDesignDraftView(modelId, rs.getInt(1), rs.getInt(2), rs.getString(3), currentRevision), modelId, owner);
        return rows.isEmpty() ? new WorkflowModelDesignDraftView(modelId, currentRevision, 0, null, currentRevision) : rows.get(0);
    }

    private ServiceException conflict()
    {
        return new ServiceException("模型或草稿已被更新，请比较远端版本后重试；当前编辑内容仍保留", 409)
                .setSubCode("MODEL_DESIGN_DRAFT_CONFLICT");
    }
}
