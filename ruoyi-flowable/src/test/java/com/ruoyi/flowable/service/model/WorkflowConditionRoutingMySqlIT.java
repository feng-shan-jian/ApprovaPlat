package com.ruoyi.flowable.service.model;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import com.ruoyi.flowable.extension.WorkflowConditionRouter;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import com.ruoyi.flowable.testsupport.WorkflowMySqlTestDatabase;

/** 在正式十四份基线的独占 schema 上复用全部作者、持久资源、任务路由和回滚判据。 */
class WorkflowConditionRoutingMySqlIT extends WorkflowConditionRoutingIntegrationTest
{
    private WorkflowMySqlTestDatabase database;
    @Override protected WorkflowFlowableEngineTestSupport startDatabase(Map<Object, Object> beans) throws Exception
    {
        database = new WorkflowMySqlTestDatabase("C10 structured condition routing");
        return WorkflowFlowableEngineTestSupport.startOnExistingDatabase(database.dataSource(), beans);
    }
    @Override protected void closeDatabase() { if (database != null) database.close(); }

    @Test void persistedResourcesSurviveEngineRestartAndFollowDeploymentDeletion()
    {
        String id = deploy("exclusive", single("amount", "GT", "0"), null, true);
        String instance = start(id, Map.of("amount", 1));
        support.close();
        support = WorkflowFlowableEngineTestSupport.startOnExistingDatabase(database.dataSource(), Map.of());
        engine = support.processEngine(); repository = engine.getRepositoryService();
        artifacts = new WorkflowDeploymentArtifactRepository(repository);
        ((org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
                .getExpressionManager().setBeans(Map.of("workflowConditionRouter", new WorkflowConditionRouter(repository, artifacts),
                        "userTaskListener", (org.flowable.engine.delegate.TaskListener) task -> { }));
        var task = engine.getTaskService().createTaskQuery().processInstanceId(instance).singleResult();
        engine.getTaskService().complete(task.getId(), Map.of("amount", 0));
        assertTasks(instance, "defaultTask");
        engine.getTaskService().complete(engine.getTaskService().createTaskQuery().processInstanceId(instance).singleResult().getId());
        assertThat(engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(instance).finished().count()).isOne();
        assertThat(artifacts.delete(id)).isOne();
        repository.deleteDeployment(id, true);
        assertThat(artifacts.selectConditionRules(id)).isEmpty();
        assertThat(repository.createDeploymentQuery().parentDeploymentId(id).count()).isZero();
    }
}
