package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.service.model.WorkflowCallActivityReferenceService;

/**
 * 使用真实 MySQL 和 Flowable 8 验证 CallActivity 精确版本冻结与删除保护。
 */
@SpringBootTest(classes = RuoYiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "spring.datasource.druid.master.url=${FLOWABLE_IT_JDBC_URL}",
            "spring.datasource.druid.master.username=${FLOWABLE_IT_USERNAME}",
            "spring.datasource.druid.master.password=${FLOWABLE_IT_PASSWORD}",
            "spring.data.redis.database=${FLOWABLE_IT_REDIS_DATABASE:15}",
            "token.secret=d29ya2Zsb3ctY2FsbC1hY3Rpdml0eS1pdC10b2tlbi1zZWNyZXQtd29ya2Zsb3ctY2FsbC1hY3Rpdml0eS1pdC10b2tlbi1zZWNyZXQ=",
            "flowable.database-schema-update=false",
            "flowable.async-executor-activate=false",
            "flowable.async-history-executor-activate=false",
            "spring.quartz.auto-startup=false"
        })
class WorkflowCallActivityMySqlIT
{
    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private WorkflowCallActivityReferenceService callActivityReferenceService;

    /**
     * 验证父流程发布后即使同 key 子流程出现新版本，运行仍进入冻结的旧定义。
     *
     * @return void，版本漂移、引用保护缺失或清理不完整时测试失败
     */
    @Test
    void freezesExactChildDefinitionAndProtectsReferencedDeployment()
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        TaskService taskService = processEngine.getTaskService();
        String runId = UUID.randomUUID().toString().replace("-", "");
        String childKey = "workflowCallChild" + runId;
        String parentKey = "workflowCallParent" + runId;

        Deployment childV1Deployment = deploy(repositoryService, childKey + "-v1",
                childBpmn(childKey, "child-v1-" + runId));
        ProcessDefinition childV1 = latestDefinition(repositoryService, childKey);
        Deployment parentDeployment = null;
        Deployment childV2Deployment = null;
        try
        {
            byte[] frozenParent = callActivityReferenceService.freezeReferences(
                    parentBpmn(parentKey, childKey));
            parentDeployment = repositoryService.createDeployment()
                    .name(parentKey)
                    .addBytes(parentKey + ".bpmn20.xml", frozenParent)
                    .deploy();

            childV2Deployment = deploy(repositoryService, childKey + "-v2",
                    childBpmn(childKey, "child-v2-" + runId));
            ProcessDefinition childV2 = latestDefinition(repositoryService, childKey);
            assertThat(childV2.getId()).isNotEqualTo(childV1.getId());

            ProcessInstance parentInstance = runtimeService.startProcessInstanceByKey(parentKey);
            Task frozenChildTask = taskService.createTaskQuery()
                    .taskName("child-v1-" + runId).singleResult();
            assertThat(frozenChildTask).as("父流程必须进入冻结的 V1 子流程任务").isNotNull();
            assertThat(frozenChildTask.getProcessDefinitionId()).isEqualTo(childV1.getId());
            assertThat(taskService.createTaskQuery().taskName("child-v2-" + runId).count()).isZero();

            Set<String> protectedIds = callActivityReferenceService.frozenTargetDefinitionIds();
            assertThat(protectedIds).contains(childV1.getId());
            assertThatThrownBy(() -> callActivityReferenceService
                    .assertDeploymentsNotReferenced(Set.of(childV1Deployment.getId())))
                    .isInstanceOfSatisfying(ServiceException.class, exception ->
                    {
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(exception.getMessage()).isEqualTo("部署仍被调用活动引用，不能删除");
                    });

            taskService.complete(frozenChildTask.getId());
            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceId(parentInstance.getId()).count()).isZero();
        }
        finally
        {
            deleteDeployment(repositoryService, parentDeployment);
            deleteDeployment(repositoryService, childV2Deployment);
            deleteDeployment(repositoryService, childV1Deployment);
        }
    }

    /**
     * 部署单流程 UTF-8 BPMN 资源。
     *
     * @param repositoryService RepositoryService，真实 Flowable 仓储 API
     * @param resourceName String，唯一资源名
     * @param bpmn byte[]，待部署 BPMN
     * @return Deployment，真实部署结果
     */
    private Deployment deploy(RepositoryService repositoryService, String resourceName, byte[] bpmn)
    {
        return repositoryService.createDeployment().name(resourceName)
                .addBytes(resourceName + ".bpmn20.xml", bpmn).deploy();
    }

    /**
     * 查询默认租户指定 key 的最新定义。
     *
     * @param repositoryService RepositoryService，真实 Flowable 仓储 API
     * @param processKey String，流程定义 key
     * @return ProcessDefinition，最新定义
     */
    private ProcessDefinition latestDefinition(RepositoryService repositoryService, String processKey)
    {
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(processKey).latestVersion().singleResult();
        assertThat(definition).isNotNull();
        return definition;
    }

    /**
     * 构造带唯一用户任务名称的子流程，用任务来源区分实际执行版本。
     *
     * @param processKey String，子流程 key
     * @param taskName String，版本唯一任务名称
     * @return byte[]，UTF-8 BPMN 资源
     */
    private byte[] childBpmn(String processKey, String taskName)
    {
        String xml = definitions("<process id=\"" + processKey + "\" isExecutable=\"true\">"
                + "<startEvent id=\"start\"/><sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"task\"/>"
                + "<userTask id=\"task\" name=\"" + taskName + "\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"task\" targetRef=\"end\"/>"
                + "<endEvent id=\"end\"/></process>");
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 构造按设计阶段子流程 key 引用的父流程。
     *
     * @param processKey String，父流程 key
     * @param childKey String，待冻结的子流程 key
     * @return byte[]，UTF-8 BPMN 资源
     */
    private byte[] parentBpmn(String processKey, String childKey)
    {
        String xml = definitions("<process id=\"" + processKey + "\" isExecutable=\"true\">"
                + "<startEvent id=\"start\"/><sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"call\"/>"
                + "<callActivity id=\"call\" calledElement=\"" + childKey + "\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"call\" targetRef=\"end\"/>"
                + "<endEvent id=\"end\"/></process>");
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 包装 BPMN Definitions 根节点并声明 Flowable 扩展命名空间。
     *
     * @param body String，流程 XML 正文
     * @return String，可部署 BPMN 文档
     */
    private String definitions(String body)
    {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                + "xmlns:flowable=\"http://flowable.org/bpmn\" targetNamespace=\"urn:approvaplat:call-it\">"
                + body + "</definitions>";
    }

    /**
     * 删除可空部署并级联清理本测试创建的运行与历史数据。
     *
     * @param repositoryService RepositoryService，真实 Flowable 仓储 API
     * @param deployment Deployment，允许为空的测试部署
     * @return void，部署已不存在时不重复删除
     */
    private void deleteDeployment(RepositoryService repositoryService, Deployment deployment)
    {
        if (deployment != null && repositoryService.createDeploymentQuery()
                .deploymentId(deployment.getId()).count() == 1L)
        {
            repositoryService.deleteDeployment(deployment.getId(), true);
        }
    }
}
