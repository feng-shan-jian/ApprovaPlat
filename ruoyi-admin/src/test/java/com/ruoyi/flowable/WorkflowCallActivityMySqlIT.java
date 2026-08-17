package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowManualUrgeRequest;
import com.ruoyi.flowable.service.model.WorkflowCallActivityReferenceService;
import com.ruoyi.flowable.service.notification.WorkflowManualUrgeService;

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
            "flowable.notification.worker-enabled=false",
            "spring.task.scheduling.enabled=false",
            "spring.quartz.auto-startup=false"
        })
class WorkflowCallActivityMySqlIT
{
    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private WorkflowCallActivityReferenceService callActivityReferenceService;

    @Autowired
    private WorkflowManualUrgeService manualUrgeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
     * 验证根流程发起人可以催办 CallActivity 子流程真实待办，通知定位子实例且审计归根实例。
     *
     * @return void，权限、流程树锁、子任务 outbox、终态零新增或清理任一契约失败时测试失败
     */
    @Test
    void urgesCallActivityChildTaskFromRootInstance()
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        TaskService taskService = processEngine.getTaskService();
        String runId = UUID.randomUUID().toString().replace("-", "");
        String childKey = "workflowUrgeChild" + runId;
        String parentKey = "workflowUrgeParent" + runId;
        List<Long> permissionActorUserIds = new ArrayList<>();
        Deployment childDeployment = null;
        Deployment parentDeployment = null;
        ProcessInstance parentInstance = null;
        ProcessInstance childInstance = null;
        try
        {
            // 每个用例独立创建真实角色账号，避免空库基线和测试执行顺序影响权限闭环。
            String starterUserId = String.valueOf(createPermissionActor(
                    runId, "starter", "workflow_starter", permissionActorUserIds));
            String approverUserId = String.valueOf(createPermissionActor(
                    runId, "approver", "workflow_approver", permissionActorUserIds));
            childDeployment = deploy(repositoryService, childKey,
                    notificationChildBpmn(childKey, approverUserId));
            parentDeployment = deploy(repositoryService, parentKey,
                    parentBpmn(parentKey, childKey));
            processEngine.getIdentityService().setAuthenticatedUserId(starterUserId);
            try
            {
                parentInstance = runtimeService.startProcessInstanceByKey(parentKey);
            }
            finally
            {
                processEngine.getIdentityService().setAuthenticatedUserId(null);
            }
            childInstance = runtimeService.createProcessInstanceQuery()
                    .superProcessInstanceId(parentInstance.getId()).singleResult();
            assertThat(childInstance).as("父流程必须创建真实 CallActivity 子实例").isNotNull();
            String rootProcessInstanceId = parentInstance.getId();
            String childProcessInstanceId = childInstance.getId();
            Task childTask = taskService.createTaskQuery()
                    .processInstanceId(childProcessInstanceId).singleResult();
            assertThat(childTask).as("子实例必须停留在真实人工审批任务").isNotNull();

            // 非发起人且没有 urge:any 的办理人必须在任何通知副作用前被拒绝。
            authenticate(approverUserId);
            assertThatThrownBy(() -> manualUrgeService.urge(new WorkflowManualUrgeRequest(
                    rootProcessInstanceId, "无权催办子流程")))
                    .isInstanceOfSatisfying(ServiceException.class,
                            exception -> assertThat(exception.getCode())
                                    .isEqualTo(HttpStatus.FORBIDDEN));
            assertThat(countManualUrgeOutboxes(rootProcessInstanceId, childProcessInstanceId))
                    .isZero();

            authenticate(starterUserId);
            var result = manualUrgeService.urge(
                    new WorkflowManualUrgeRequest(rootProcessInstanceId, "请处理子流程审批"));
            assertThat(result.recipientCount()).isEqualTo(1);
            assertThat(result.outboxCount()).isEqualTo(1);
            assertThat(result.urgeEventKey()).startsWith("URGE:");
            Map<String, Object> outbox = jdbcTemplate.queryForMap(
                    "select outbox_id,process_definition_key,process_instance_id,task_id," +
                    "task_definition_key,recipient_user_id,route_path from wf_notification_outbox " +
                    "where event_type='MANUAL_URGE' and task_id=?", childTask.getId());
            long manualUrgeOutboxId = ((Number) outbox.get("outbox_id")).longValue();
            assertThat(outbox.get("process_definition_key")).isEqualTo(childKey);
            assertThat(outbox.get("process_instance_id")).isEqualTo(childProcessInstanceId);
            assertThat(outbox.get("task_id")).isEqualTo(childTask.getId());
            assertThat(outbox.get("task_definition_key")).isEqualTo(childTask.getTaskDefinitionKey());
            assertThat(((Number) outbox.get("recipient_user_id")).longValue())
                    .isEqualTo(Long.parseLong(approverUserId));
            assertThat(String.valueOf(outbox.get("route_path")))
                    .contains(childProcessInstanceId, childTask.getId());

            SecurityContextHolder.clearContext();
            taskService.complete(childTask.getId());
            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceId(rootProcessInstanceId).count()).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "select status from wf_notification_outbox where outbox_id=?",
                    String.class, manualUrgeOutboxId)).isEqualTo("CANCELLED");
            int outboxCountAfterCompletion = countManualUrgeOutboxes(
                    rootProcessInstanceId, childProcessInstanceId);
            authenticate(starterUserId);
            assertThatThrownBy(() -> manualUrgeService.urge(new WorkflowManualUrgeRequest(
                    rootProcessInstanceId, "终态后不得催办")))
                    .isInstanceOfSatisfying(ServiceException.class,
                            exception -> assertThat(exception.getCode())
                                    .isEqualTo(HttpStatus.CONFLICT));
            assertThat(countManualUrgeOutboxes(rootProcessInstanceId, childProcessInstanceId))
                    .isEqualTo(outboxCountAfterCompletion);
        }
        finally
        {
            SecurityContextHolder.clearContext();
            processEngine.getIdentityService().setAuthenticatedUserId(null);
            if (parentInstance != null && childInstance != null)
            {
                deleteNotificationFacts(parentInstance.getId(), childInstance.getId());
            }
            try
            {
                deleteDeployment(repositoryService, parentDeployment);
                deleteDeployment(repositoryService, childDeployment);
            }
            finally
            {
                deletePermissionActors(runId, permissionActorUserIds);
            }
        }
    }

    /**
     * 验证根实例催办与 CallActivity 子任务完成竞争时串行化，终态不遗留可投递催办。
     *
     * @return void，竞争死锁、完成失败或遗留失效 outbox 时测试失败
     * @throws Exception 并发执行等待失败或线程未按时结束时抛出
     */
    @Test
    void serializesRootUrgeAgainstCallActivityChildCompletion() throws Exception
    {
        RepositoryService repositoryService = processEngine.getRepositoryService();
        RuntimeService runtimeService = processEngine.getRuntimeService();
        TaskService taskService = processEngine.getTaskService();
        String runId = UUID.randomUUID().toString().replace("-", "");
        String childKey = "workflowUrgeRaceChild" + runId;
        String parentKey = "workflowUrgeRaceParent" + runId;
        List<Long> permissionActorUserIds = new ArrayList<>();
        Deployment childDeployment = null;
        Deployment parentDeployment = null;
        ProcessInstance parentInstance = null;
        ProcessInstance childInstance = null;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try
        {
            // 并发用例也使用本轮独立账号，确保两条线程共享同一正式权限主数据而不依赖其他用例。
            String starterUserId = String.valueOf(createPermissionActor(
                    runId, "starter", "workflow_starter", permissionActorUserIds));
            String approverUserId = String.valueOf(createPermissionActor(
                    runId, "approver", "workflow_approver", permissionActorUserIds));
            childDeployment = deploy(repositoryService, childKey,
                    notificationChildBpmn(childKey, approverUserId));
            parentDeployment = deploy(repositoryService, parentKey,
                    parentBpmn(parentKey, childKey));
            processEngine.getIdentityService().setAuthenticatedUserId(starterUserId);
            try
            {
                parentInstance = runtimeService.startProcessInstanceByKey(parentKey);
            }
            finally
            {
                processEngine.getIdentityService().setAuthenticatedUserId(null);
            }
            childInstance = runtimeService.createProcessInstanceQuery()
                    .superProcessInstanceId(parentInstance.getId()).singleResult();
            assertThat(childInstance).isNotNull();
            String rootProcessInstanceId = parentInstance.getId();
            String childProcessInstanceId = childInstance.getId();
            Task childTask = taskService.createTaskQuery()
                    .processInstanceId(childProcessInstanceId).singleResult();
            assertThat(childTask).isNotNull();

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> urge = executor.submit(() ->
            {
                authenticate(starterUserId);
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("子流程催办并发起跑超时");
                try
                {
                    manualUrgeService.urge(new WorkflowManualUrgeRequest(
                            rootProcessInstanceId, "与子任务完成竞争"));
                    return 0;
                }
                catch (ServiceException exception)
                {
                    return exception.getCode();
                }
                finally
                {
                    SecurityContextHolder.clearContext();
                }
            });
            Future<Void> completion = executor.submit(() ->
            {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("子任务完成并发起跑超时");
                taskService.complete(childTask.getId());
                return null;
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            completion.get(20, TimeUnit.SECONDS);
            assertThat(urge.get(20, TimeUnit.SECONDS)).isIn(0, HttpStatus.CONFLICT);

            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceId(rootProcessInstanceId).count()).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from wf_notification_outbox where event_type='MANUAL_URGE' " +
                    "and process_instance_id in (?,?) and status in " +
                    "('PENDING','RETRYING','DELIVERING')", Integer.class,
                    rootProcessInstanceId, childProcessInstanceId)).isZero();
        }
        finally
        {
            executor.shutdownNow();
            SecurityContextHolder.clearContext();
            processEngine.getIdentityService().setAuthenticatedUserId(null);
            if (parentInstance != null && childInstance != null)
            {
                deleteNotificationFacts(parentInstance.getId(), childInstance.getId());
            }
            try
            {
                deleteDeployment(repositoryService, parentDeployment);
                deleteDeployment(repositoryService, childDeployment);
            }
            finally
            {
                deletePermissionActors(runId, permissionActorUserIds);
            }
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
     * 构造指向真实有效审批人的 CallActivity 子流程，供人工催办运行链路验收。
     *
     * @param processKey String，子流程定义 key
     * @param approverUserId String，正式 sys_user 审批人主键
     * @return byte[]，包含一个真实办理任务的 UTF-8 BPMN
     */
    private byte[] notificationChildBpmn(String processKey, String approverUserId)
    {
        String xml = definitions("<process id=\"" + processKey + "\" name=\"催办子流程\" "
                + "isExecutable=\"true\"><startEvent id=\"start\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"childApprove\"/>"
                + "<userTask id=\"childApprove\" name=\"子流程审批\" flowable:assignee=\""
                + approverUserId + "\"/><sequenceFlow id=\"f2\" sourceRef=\"childApprove\" "
                + "targetRef=\"end\"/><endEvent id=\"end\"/></process>");
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
     * 创建真实启用用户并绑定现有工作流角色，使权限校验基于正式角色和菜单主数据执行。
     *
     * @param runId String，当前测试的唯一运行标识
     * @param suffix String，当前测试用户的稳定业务后缀
     * @param roleKey String，必须存在且启用的正式角色标识
     * @param permissionActorUserIds List&lt;Long&gt;，记录本轮新建用户主键的清理清单
     * @return long，新建用户主键
     */
    private long createPermissionActor(String runId, String suffix, String roleKey,
            List<Long> permissionActorUserIds)
    {
        String userName = permissionActorPrefix(runId) + suffix;
        Long roleId = jdbcTemplate.queryForObject("select role_id from sys_role " +
                "where role_key=? and status='0' and del_flag='0'", Long.class, roleKey);
        assertThat(roleId).as(roleKey + " 正式角色").isNotNull();
        jdbcTemplate.update("insert into sys_user " +
                        "(user_name,nick_name,password,status,del_flag,create_by,create_time) " +
                        "values (?,?,'','0','0','workflow_call_activity_it',current_timestamp(3))",
                userName, "子流程催办集成测试" + suffix);
        Long userId = jdbcTemplate.queryForObject(
                "select user_id from sys_user where user_name=?", Long.class, userName);
        assertThat(userId).isNotNull().isPositive();
        jdbcTemplate.update("insert into sys_user_role (user_id,role_id) values (?,?)", userId, roleId);
        permissionActorUserIds.add(userId);
        return userId;
    }

    /**
     * 按通知偏好、角色关系、用户的外键顺序回收本轮临时权限账号。
     *
     * @param runId String，当前测试的唯一运行标识
     * @param permissionActorUserIds List&lt;Long&gt;，本轮创建的真实权限用户主键
     * @return void，任一测试账号残留时测试失败
     */
    private void deletePermissionActors(String runId, List<Long> permissionActorUserIds)
    {
        for (Long userId : permissionActorUserIds)
        {
            jdbcTemplate.update("delete from wf_notification_preference where user_id=?", userId);
            jdbcTemplate.update("delete from sys_user_role where user_id=?", userId);
            jdbcTemplate.update("delete from sys_user where user_id=?", userId);
        }
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_user where user_name like ?", Integer.class,
                permissionActorPrefix(runId) + "%")).isZero();
        permissionActorUserIds.clear();
    }

    /**
     * 生成满足 sys_user.user_name 长度约束的本轮临时账号前缀。
     *
     * @param runId String，当前测试的唯一运行标识
     * @return String，包含 runId 前十二位的隔离前缀
     */
    private String permissionActorPrefix(String runId)
    {
        return "wfcall_" + runId.substring(0, 12) + "_";
    }

    /**
     * 建立当前线程的真实登录用户上下文，领域服务仍会回查正式用户和权限主数据。
     *
     * @param userId String，已确认存在的数字用户主键
     * @return void，SecurityContext 建立完成后正常返回
     */
    private void authenticate(String userId)
    {
        long numericUserId = Long.parseLong(userId);
        SysUser user = new SysUser();
        user.setUserId(numericUserId);
        user.setUserName("workflow_call_urge_it_" + userId);
        user.setNickName("子流程催办集成测试用户");
        LoginUser loginUser = new LoginUser(numericUserId, null, user, Set.of());
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        loginUser, null, loginUser.getAuthorities());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    /**
     * 统计根与子实例当前人工催办 outbox 数量。
     *
     * @param rootProcessInstanceId String，根业务实例主键
     * @param childProcessInstanceId String，CallActivity 子实例主键
     * @return int，两实例范围内 MANUAL_URGE 正式记录数
     */
    private int countManualUrgeOutboxes(String rootProcessInstanceId,
            String childProcessInstanceId)
    {
        return jdbcTemplate.queryForObject(
                "select count(*) from wf_notification_outbox where event_type='MANUAL_URGE' " +
                "and process_instance_id in (?,?)", Integer.class,
                rootProcessInstanceId, childProcessInstanceId);
    }

    /**
     * 按外键顺序只删除当前根与子实例产生的通知事实，不影响共享验收库其他记录。
     *
     * @param rootProcessInstanceId String，本测试根业务实例主键
     * @param childProcessInstanceId String，本测试 CallActivity 子实例主键
     * @return void，目标通知 inbox 与 outbox 清理完成后正常返回
     */
    private void deleteNotificationFacts(String rootProcessInstanceId,
            String childProcessInstanceId)
    {
        List<Long> outboxIds = jdbcTemplate.queryForList(
                "select outbox_id from wf_notification_outbox where process_instance_id in (?,?)",
                Long.class, rootProcessInstanceId, childProcessInstanceId);
        for (Long outboxId : outboxIds)
        {
            jdbcTemplate.update("delete from wf_notification_inbox where outbox_id=?", outboxId);
            jdbcTemplate.update("delete from wf_notification_outbox where outbox_id=?", outboxId);
        }
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
