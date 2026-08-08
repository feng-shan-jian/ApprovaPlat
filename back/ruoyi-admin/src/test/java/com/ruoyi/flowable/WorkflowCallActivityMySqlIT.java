package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.HistoryService;
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
     * 验证部署期授权后的内部调用不重复套用人工 starter，并完成父子变量和实例关系闭环。
     *
     * @return void，human starter 误拦截内部调用、映射丢失或父子历史关系不一致时测试失败
     */
    @Test
    void executesInternalChildWithMappingsAndRuntimeHistoryRelations()
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        TaskService taskService = processEngine.getTaskService();
        HistoryService historyService = processEngine.getHistoryService();
        String runId = UUID.randomUUID().toString().replace("-", "");
        String childKey = "workflowMappedChild" + runId;
        String parentKey = "workflowMappedParent" + runId;
        Deployment childDeployment = deploy(repositoryService, childKey,
                childBpmn(childKey, "child-review-" + runId));
        ProcessDefinition childDefinition = latestDefinition(repositoryService, childKey);
        Deployment parentDeployment = null;
        try
        {
            // 此 starter 与实际父流程发起人故意不匹配；它只限制人工入口，不限制已验证 CallActivity 内部调用。
            repositoryService.addCandidateStarterUser(childDefinition.getId(), "human-only-user");
            byte[] frozenParent = callActivityReferenceService.freezeReferences(
                    mappedParentBpmn(parentKey, childKey, "parent-result-" + runId));
            parentDeployment = repositoryService.createDeployment().name(parentKey)
                    .addBytes(parentKey + ".bpmn20.xml", frozenParent).deploy();

            processEngine.getIdentityService().setAuthenticatedUserId("parent-initiator");
            ProcessInstance parent;
            try
            {
                parent = runtimeService.startProcessInstanceByKey(parentKey,
                        "business-" + runId, Map.of("amount", 128L));
            }
            finally
            {
                processEngine.getIdentityService().setAuthenticatedUserId(null);
            }

            ProcessInstance child = runtimeService.createProcessInstanceQuery()
                    .superProcessInstanceId(parent.getId()).singleResult();
            assertThat(child).as("内部调用必须创建真实子流程实例").isNotNull();
            assertThat(child.getProcessDefinitionId()).isEqualTo(childDefinition.getId());
            assertThat(child.getRootProcessInstanceId()).isEqualTo(parent.getId());
            assertThat(child.getBusinessKey()).isEqualTo(parent.getBusinessKey());
            assertThat(child.getName()).isEqualTo("费用复核子流程");
            assertThat(child.getStartUserId()).isEqualTo("parent-initiator");
            assertThat(runtimeService.getVariable(child.getId(), "requestAmount")).isEqualTo(128L);

            Task childTask = taskService.createTaskQuery()
                    .processInstanceId(child.getId()).singleResult();
            assertThat(childTask).isNotNull();
            taskService.complete(childTask.getId(), Map.of("reviewResult", "APPROVED"));

            Task parentResultTask = taskService.createTaskQuery()
                    .processInstanceId(parent.getId()).singleResult();
            assertThat(parentResultTask).isNotNull();
            assertThat(runtimeService.getVariable(parent.getId(), "childResult"))
                    .isEqualTo("APPROVED");
            assertThat(historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(child.getId()).singleResult().getSuperProcessInstanceId())
                    .isEqualTo(parent.getId());

            taskService.complete(parentResultTask.getId());
            assertThat(historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(parent.getId()).finished().count()).isOne();
            assertThat(historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(child.getId()).finished().count()).isOne();
        }
        finally
        {
            deleteDeployment(repositoryService, parentDeployment);
            deleteDeployment(repositoryService, childDeployment);
        }
    }

    /**
     * 验证子流程同步执行异常时父子启动命令整体回滚，不遗留运行或历史实例。
     *
     * @return void，异常启动产生任何父子实例副作用时测试失败
     */
    @Test
    void rollsBackParentAndChildWhenChildExecutionFails()
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        HistoryService historyService = processEngine.getHistoryService();
        String runId = UUID.randomUUID().toString().replace("-", "");
        String childKey = "workflowFailingChild" + runId;
        String parentKey = "workflowFailingParent" + runId;
        String businessKey = "failing-business-" + runId;
        Deployment childDeployment = deploy(repositoryService, childKey,
                failingChildBpmn(childKey));
        Deployment parentDeployment = null;
        try
        {
            byte[] frozenParent = callActivityReferenceService.freezeReferences(
                    parentBpmn(parentKey, childKey));
            parentDeployment = repositoryService.createDeployment().name(parentKey)
                    .addBytes(parentKey + ".bpmn20.xml", frozenParent).deploy();

            assertThatThrownBy(() -> runtimeService.startProcessInstanceByKey(
                    parentKey, businessKey))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("受控子流程异常");
            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceBusinessKey(businessKey).count()).isZero();
            assertThat(historyService.createHistoricProcessInstanceQuery()
                    .processInstanceBusinessKey(businessKey).count()).isZero();
        }
        finally
        {
            deleteDeployment(repositoryService, parentDeployment);
            deleteDeployment(repositoryService, childDeployment);
        }
    }

    /**
     * 验证两个父流程并发启动时各自创建唯一子实例，业务键和变量不会串写。
     *
     * @return void，并发实例交叉关联、变量污染或清理不完整时测试失败
     * @throws Exception 并发执行等待失败或线程未按时结束时抛出
     */
    @Test
    void isolatesConcurrentParentAndChildExecutions() throws Exception
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        TaskService taskService = processEngine.getTaskService();
        String runId = UUID.randomUUID().toString().replace("-", "");
        String childKey = "workflowConcurrentChild" + runId;
        String parentKey = "workflowConcurrentParent" + runId;
        Deployment childDeployment = deploy(repositoryService, childKey,
                childBpmn(childKey, "concurrent-review-" + runId));
        Deployment parentDeployment = null;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try
        {
            byte[] frozenParent = callActivityReferenceService.freezeReferences(
                    parentBpmn(parentKey, childKey));
            parentDeployment = repositoryService.createDeployment().name(parentKey)
                    .addBytes(parentKey + ".bpmn20.xml", frozenParent).deploy();

            CompletableFuture<ProcessInstance> first = CompletableFuture.supplyAsync(() ->
                    runtimeService.startProcessInstanceByKey(
                            parentKey, "concurrent-a-" + runId, Map.of("marker", "A")), executor);
            CompletableFuture<ProcessInstance> second = CompletableFuture.supplyAsync(() ->
                    runtimeService.startProcessInstanceByKey(
                            parentKey, "concurrent-b-" + runId, Map.of("marker", "B")), executor);
            List<ProcessInstance> parents = List.of(first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS));

            assertThat(parents).extracting(ProcessInstance::getId).doesNotHaveDuplicates();
            for (ProcessInstance parent : parents)
            {
                ProcessInstance child = runtimeService.createProcessInstanceQuery()
                        .superProcessInstanceId(parent.getId()).singleResult();
                assertThat(child).isNotNull();
                assertThat(child.getRootProcessInstanceId()).isEqualTo(parent.getId());
                assertThat(runtimeService.getVariable(parent.getId(), "marker"))
                        .isEqualTo(parent.getBusinessKey().contains("concurrent-a-") ? "A" : "B");
                Task childTask = taskService.createTaskQuery()
                        .processInstanceId(child.getId()).singleResult();
                assertThat(childTask).isNotNull();
                taskService.complete(childTask.getId());
            }
            assertThat(runtimeService.createProcessInstanceQuery()
                    .processDefinitionKey(parentKey).count()).isZero();
            assertThat(runtimeService.createProcessInstanceQuery()
                    .processDefinitionKey(childKey).count()).isZero();
        }
        finally
        {
            executor.shutdownNow();
            deleteDeployment(repositoryService, parentDeployment);
            deleteDeployment(repositoryService, childDeployment);
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
     * 构造包含原生输入输出映射、业务键继承、子实例名称和父结果任务的父流程。
     * @param processKey String，父流程 key
     * @param childKey String，待部署期冻结的子流程 key
     * @param parentTaskName String，变量回传后停留的父流程任务名称
     * @return byte[]，UTF-8 BPMN 资源
     */
    private byte[] mappedParentBpmn(String processKey, String childKey, String parentTaskName)
    {
        String xml = definitions("<process id=\"" + processKey + "\" isExecutable=\"true\">"
                + "<startEvent id=\"start\"/><sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"call\"/>"
                + "<callActivity id=\"call\" calledElement=\"" + childKey + "\" "
                + "flowable:inheritBusinessKey=\"true\" flowable:inheritVariables=\"false\" "
                + "flowable:processInstanceName=\"费用复核子流程\"><extensionElements>"
                + "<flowable:in source=\"amount\" target=\"requestAmount\"/>"
                + "<flowable:out source=\"reviewResult\" target=\"childResult\"/>"
                + "</extensionElements></callActivity>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"call\" targetRef=\"parentTask\"/>"
                + "<userTask id=\"parentTask\" name=\"" + parentTaskName + "\"/>"
                + "<sequenceFlow id=\"f3\" sourceRef=\"parentTask\" targetRef=\"end\"/>"
                + "<endEvent id=\"end\"/></process>");
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 构造启动后立即执行受控失败 JavaDelegate 的子流程。
     *
     * @param processKey String，子流程 key
     * @return byte[]，UTF-8 BPMN 资源
     */
    private byte[] failingChildBpmn(String processKey)
    {
        String xml = definitions("<process id=\"" + processKey + "\" isExecutable=\"true\">"
                + "<startEvent id=\"start\"/><sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"fail\"/>"
                + "<serviceTask id=\"fail\" flowable:class=\""
                + AlwaysFailDelegate.class.getName() + "\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"fail\" targetRef=\"end\"/>"
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

    /**
     * 子流程异常事务测试使用的受控 JavaDelegate。
     */
    public static final class AlwaysFailDelegate implements JavaDelegate
    {
        /**
         * 固定抛出异常，验证 Flowable 同步 CallActivity 命令的事务回滚。
         *
         * @param execution DelegateExecution，当前子流程执行上下文
         * @return void，始终抛出异常
         */
        @Override
        public void execute(DelegateExecution execution)
        {
            throw new IllegalStateException("受控子流程异常");
        }
    }
}
