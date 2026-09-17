package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.*;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessException;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfCopy;
import com.ruoyi.flowable.domain.dto.*;
import com.ruoyi.flowable.domain.vo.*;
import com.ruoyi.flowable.engine.*;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.mapper.*;
import com.ruoyi.flowable.service.task.*;
import com.ruoyi.flowable.testsupport.*;
import tools.jackson.databind.json.JsonMapper;

/** 真实 Flowable、正式姓名/抄送 SQL 和读取事务；同一判据在 H2 与 MySQL 运行。 */
class WorkflowWorkbenchProjectionIntegrationTest
{
    protected WorkflowFlowableEngineTestSupport support;
    private ProcessEngine engine;
    private WorkflowMultiInstanceUserMapper users;
    private WfCopyMapper copies;
    private WorkflowIdentityResolver identities;
    private WorkflowProcessInstanceQueryService instances;
    private WorkflowProcessTaskQueryService tasks;
    private String deploymentId;
    private String definitionId;

    protected WorkflowFlowableEngineTestSupport startDatabase() throws Exception
    {
        var db = WorkflowFlowableEngineTestSupport.start("workbench", Map.of());
        db.jdbcTemplate().execute("create table sys_user(user_id bigint primary key, user_name varchar(30), nick_name varchar(30), status char(1), del_flag char(1))");
        WorkflowH2SchemaMapperSupport.executeSchema(db.dataSource(), "db/h2/wf_copy.sql");
        return db;
    }

    protected void closeDatabase() { }

    @BeforeEach
    void setUp() throws Exception
    {
        support = startDatabase();
        engine = support.processEngine();
        users = spy(WorkflowH2SchemaMapperSupport.createSpringMapper(support.dataSource(), "names",
                WorkflowMultiInstanceUserMapper.class, "mapper/flowable/WorkflowMultiInstanceUserMapper.xml"));
        copies = WorkflowH2SchemaMapperSupport.createSpringMapper(support.dataSource(), "copies",
                WfCopyMapper.class, "mapper/flowable/WfCopyMapper.xml");
        identities = mock(WorkflowIdentityResolver.class);
        when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("700", Set.of("ROLE9")));
        when(identities.resolveClaimEligibleUserIds(any())).thenReturn(Set.of("700"));
        var operations = support.transactionalProxy(new WorkflowEngineOperations(
                mock(WorkflowAuthenticationContext.class), new WorkflowExceptionTranslator(), identities));
        instances = new WorkflowProcessInstanceQueryService(operations, engine.getRepositoryService(),
                engine.getHistoryService(), engine.getRuntimeService(), engine.getTaskService(), identities, copies, users);
        tasks = new WorkflowProcessTaskQueryService(operations, engine.getRepositoryService(), engine.getHistoryService(),
                engine.getTaskService(), identities, users, mock(WorkflowTaskRevokeApplicationService.class), new WorkflowTaskUnclaimPolicy());
        deploymentId = engine.getRepositoryService().createDeployment().category("finance").addString("workbench.bpmn20.xml", """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="urn:different">
                  <process id="workbench" name="工作台回归" isExecutable="true">
                    <startEvent id="start"/><userTask id="review" name="审阅" flowable:candidateUsers="700"/><endEvent id="end"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="review"/><sequenceFlow id="f2" sourceRef="review" targetRef="end"/>
                  </process>
                </definitions>
                """).deploy().getId();
        definitionId = engine.getRepositoryService().createProcessDefinitionQuery().deploymentId(deploymentId).singleResult().getId();
        support.jdbcTemplate().update("insert into sys_user(user_id,user_name,nick_name,status,del_flag) values (700,'actor',' 当前昵称 ','0','0'), (701,'empty-account','   ','0','0'), (702,'deleted-account','已删除昵称','1','2'), (703,'unused','非本页用户','0','0')");
    }

    @AfterEach
    void tearDown()
    {
        try { if (support != null) support.close(); }
        finally { closeDatabase(); }
    }

    @Test
    void batchesOnlyCurrentPageNamesAcrossManagedCandidateAssignedCompletedAndExport()
    {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("700", " 当前昵称 "); labels.put("0700", " 当前昵称 "); labels.put("+700", " 当前昵称 ");
        labels.put("701", "701"); labels.put("702", "已删除昵称"); labels.put("799", "799");
        labels.put("old-user", "old-user"); labels.put(" 700", " 700"); labels.put("9223372036854775808", "9223372036854775808");
        labels.put(" ", null); labels.put("-1", "-1");
        labels.forEach((id, label) -> start(id));
        for (int i = 0; i < 4; i++) start("700");
        clearInvocations(users);
        var managed = instances.listManaged(null, 1, 200);
        assertThat(managed.total()).isEqualTo(labels.size() + 4);
        managed.rows().forEach(row -> assertThat(row.startUserName()).isEqualTo(labels.get(row.startUserId())));
        assertOneNameBatch(Set.of(700L, 701L, 702L, 799L, -1L));
        var claimable = tasks.listClaimable(null, 1, 200);
        assertThat(claimable.total()).isEqualTo(managed.total());
        claimable.rows().forEach(row -> assertThat(row.startUserName()).isEqualTo(labels.get(row.startUserId())));
        for (var row : claimable.rows()) engine.getTaskService().claim(row.taskId(), "700");
        var assigned = tasks.listAssigned(null, 1, 200);
        assigned.rows().forEach(row -> { assertThat(row.startUserName()).isEqualTo(labels.get(row.startUserId())); assertThat(row.unclaimAllowed()).isTrue(); });
        for (var row : assigned.rows()) engine.getTaskService().complete(row.taskId(), "700");
        var completed = tasks.listCompleted(null, 1, 200);
        assertThat(completed.total()).isEqualTo(managed.total());
        completed.rows().forEach(row -> assertThat(row.startUserName()).isEqualTo(labels.get(row.startUserId())));
        var exported = tasks.listCompletedForExport(null, 1, 200);
        assertThat(exported.rows()).extracting(WorkflowCompletedTaskExportView::startUserName)
                .containsExactlyElementsOf(completed.rows().stream().map(WorkflowCompletedTaskView::startUserName).toList());
        // Each of these five real pages needs one <=200-ID name batch, irrespective of distinct users.
        verify(users, times(5)).selectRawNickNamesByIds(anyList());
        verify(users, never()).selectUserNamesByIds(anyList());
    }

    @Test
    void preservesAllJsonFieldsThroughCandidateClaimCompleteAndInstanceViews()
    {
        var instance = start("700");
        Task task = taskOf(instance);
        Map<String, Object> common = fields("taskId", task.getId(), "taskName", "审阅", "taskDefinitionKey", "review",
                "definitionId", definitionId, "processKey", "workbench", "processName", "工作台回归", "version", 1,
                "category", "finance", "deploymentId", deploymentId, "processInstanceId", instance.getId(),
                "businessKey", "business-700", "startUserId", "700", "startUserName", " 当前昵称 ",
                "createTime", task.getCreateTime().toInstant(), "dueTime", null);
        assertJson(tasks.listClaimable(null, 1, 20).rows().get(0), common);
        engine.getTaskService().claim(task.getId(), "700");
        Task claimed = taskOf(instance);
        var assigned = new LinkedHashMap<>(common);
        assigned.putAll(fields("assigneeId", "700", "ownerId", null, "claimedById", "700", "claimTime", claimed.getClaimTime().toInstant(), "unclaimAllowed", true));
        assertJson(tasks.listAssigned(null, 1, 20).rows().get(0), assigned);
        var expectedInstance = fields("processInstanceId", instance.getId(), "definitionId", definitionId, "processKey", "workbench",
                "processName", "工作台回归", "version", 1, "category", "finance", "deploymentId", deploymentId,
                "businessKey", "business-700", "startUserId", "700", "startTime", instance.getStartTime().toInstant(),
                "endTime", null, "durationMillis", null, "processStatus", "running", "currentTaskNames", List.of("审阅"),
                "cancelAllowed", true, "terminateAllowed", false, "stateAction", null, "historyDeleteAllowed", false);
        assertJson(instances.listOwned(null, 1, 20).rows().get(0), expectedInstance);
        expectedInstance.putAll(fields("startUserName", " 当前昵称 ", "cancelAllowed", false, "terminateAllowed", true, "stateAction", "SUSPEND"));
        assertJson(instances.listManaged(null, 1, 20).rows().get(0), expectedInstance);
        engine.getTaskService().complete(task.getId(), "700");
        var history = engine.getHistoryService().createHistoricTaskInstanceQuery().taskId(task.getId()).singleResult();
        var completed = new LinkedHashMap<>(common); completed.remove("dueTime");
        completed.putAll(fields("assigneeId", "700", "completedBy", "700", "finishTime", history.getEndTime().toInstant(), "durationMillis", history.getDurationInMillis(), "revocable", false));
        assertJson(tasks.listCompleted(null, 1, 20).rows().get(0), completed);
        assertJson(tasks.listCompletedForExport(null, 1, 20).rows().get(0), fields("taskId", task.getId(), "processName", "工作台回归", "taskName", "审阅",
                "version", 1, "startUserName", " 当前昵称 ", "completedBy", "700", "createTime", task.getCreateTime().toInstant(),
                "finishTime", history.getEndTime().toInstant(), "durationMillis", history.getDurationInMillis()));
    }

    @Test
    void keepsPaginationFiltersSuspensionAndPageScope()
    {
        var a = start("700"); var b = start("701"); var c = start("702");
        var all = instances.listManaged(null, 1, 200);
        clearInvocations(users);
        var page = instances.listManaged(null, 2, 1);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.rows()).containsExactly(all.rows().get(1));
        assertOneNameBatch(Set.of(Long.valueOf(page.rows().get(0).startUserId())));
        clearInvocations(users);
        assertThat(instances.listManaged(null, 5, 1).total()).isEqualTo(3);
        assertThat(tasks.listAssigned(null, 1, 20).rows()).isEmpty();
        verifyNoInteractions(users);
        assertThat(instances.listOwned(null, 1, 20).rows()).extracting(WorkflowOwnedProcessView::processInstanceId).containsExactly(a.getId());
        var filter = new WorkflowManagedProcessQueryDto(b.getId(), "workbench", "工作台", "finance", "business-701", "701", Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        assertThat(instances.listManaged(filter, 1, 20).rows()).extracting(WorkflowManagedProcessView::processInstanceId).containsExactly(b.getId());
        engine.getRuntimeService().suspendProcessInstanceById(c.getId());
        assertThat(tasks.listClaimable(null, 1, 20).total()).isEqualTo(2);
        assertThat(instances.listManaged(null, 1, 20).rows()).filteredOn(row -> row.processInstanceId().equals(c.getId()))
                .singleElement().satisfies(row -> { assertThat(row.processStatus()).isEqualTo("suspended"); assertThat(row.currentTaskNames()).containsExactly("审阅"); });
        engine.getRuntimeService().activateProcessInstanceById(c.getId());
        var taskFilter = new WorkflowClaimableTaskQueryDto("work", "工作台", "finance", "审阅", Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        var candidates = tasks.listClaimable(taskFilter, 1, 20);
        assertThat(candidates.total()).isEqualTo(3);
        assertThat(tasks.listClaimable(taskFilter, 2, 1).rows()).containsExactly(candidates.rows().get(1));
        assertThat(tasks.listClaimable(new WorkflowClaimableTaskQueryDto(null,null,"missing",null,null,null), 1, 20).total()).isZero();
        assertThat(tasks.listClaimable(new WorkflowClaimableTaskQueryDto(null,null,null,null,Instant.now().plusSeconds(60),null), 1, 20).rows()).isEmpty();
    }

    @Test
    void keepsRealTaskCapacityLimitsWhenBatchingInstancePages()
    {
        engine.getRepositoryService().createDeployment().addString("capacity.bpmn20.xml", """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="urn:capacity">
                  <process id="capacity" isExecutable="true"><startEvent id="start"/>
                    <userTask id="many" name="并行审阅"><multiInstanceLoopCharacteristics isSequential="false"><loopCardinality>201</loopCardinality></multiInstanceLoopCharacteristics></userTask>
                    <endEvent id="end"/><sequenceFlow id="f1" sourceRef="start" targetRef="many"/><sequenceFlow id="f2" sourceRef="many" targetRef="end"/>
                  </process>
                </definitions>
                """).deploy();
        var crowded = engine.getRuntimeService().createProcessInstanceBuilder().processDefinitionKey("capacity").businessStatus("running").start();
        assertThat(engine.getTaskService().createTaskQuery().processInstanceId(crowded.getId()).count()).isEqualTo(201);
        assertServiceFailure(() -> instances.listManaged(null,1,20),500,"当前页流程实例的当前任务总数超过安全上限");
        start("700");
        assertServiceFailure(() -> instances.listManaged(null,1,20),500,"单个流程实例的当前任务数量超过安全上限");
    }

    @Test
    void refreshesNickNamesAndDoesNotChangeMultiInstanceDisplay()
    {
        start("700"); start("701");
        assertThat(users.selectUserNamesByIds(List.of(700L,701L))).extracting(row -> row.name()).containsExactly("当前昵称", "empty-account");
        assertThat(instances.listManaged(null, 1, 20).rows()).extracting(WorkflowManagedProcessView::startUserName).containsExactlyInAnyOrder(" 当前昵称 ","701");
        support.jdbcTemplate().update("update sys_user set nick_name=' 新昵称 ' where user_id=700");
        assertThat(tasks.listClaimable(null, 1, 20).rows()).extracting(WorkflowClaimableTaskView::startUserName).containsExactlyInAnyOrder(" 新昵称 ","701");
        support.jdbcTemplate().update("delete from sys_user where user_id=700");
        assertThat(instances.listManaged(null, 1, 20).rows()).extracting(WorkflowManagedProcessView::startUserName).containsExactlyInAnyOrder("700","701");
    }

    @Test
    void preservesInputBoundariesAndCurrentRequestAuthorization()
    {
        for (int[] page : List.of(new int[]{0,1}, new int[]{1,0}, new int[]{1,201}, new int[]{Integer.MAX_VALUE,200}))
        {
            assertBadRequest(() -> instances.listOwned(null,page[0],page[1]));
            assertBadRequest(() -> instances.listManaged(null,page[0],page[1]));
            assertBadRequest(() -> instances.listCopies(null,page[0],page[1]));
            assertBadRequest(() -> tasks.listAssigned(null,page[0],page[1]));
            assertBadRequest(() -> tasks.listClaimable(null,page[0],page[1]));
            assertBadRequest(() -> tasks.listCompleted(null,page[0],page[1]));
            assertBadRequest(() -> tasks.listCompletedForExport(null,page[0],page[1]));
        }
        Instant lower=Instant.parse("2026-09-02T00:00:00Z"), upper=lower.minusSeconds(1);
        assertBadRequest(() -> instances.listOwned(new WorkflowOwnedProcessQueryDto(null,null,null,null,lower,upper),1,20));
        assertBadRequest(() -> instances.listManaged(new WorkflowManagedProcessQueryDto(null,null,null,null,null,null,lower,upper),1,20));
        assertBadRequest(() -> tasks.listAssigned(new WorkflowAssignedTaskQueryDto(null,null,null,null,lower,upper),1,20));
        assertBadRequest(() -> tasks.listClaimable(new WorkflowClaimableTaskQueryDto(null,null,null,null,lower,upper),1,20));
        assertBadRequest(() -> tasks.listCompleted(new WorkflowCompletedTaskQueryDto(null,null,null,null,lower,upper),1,20));
        start("700");
        assertThat(tasks.listClaimable(null,1,20).total()).isEqualTo(1);
        when(identities.resolveClaimEligibleUserIds(any())).thenReturn(Set.of());
        assertThat(tasks.listClaimable(null,1,20).total()).isZero();
        when(identities.resolveCurrentIdentity()).thenThrow(new ServiceException("当前用户不可参与工作流",403));
        assertThatThrownBy(() -> instances.listManaged(null,1,20)).isInstanceOf(ServiceException.class).hasMessage("当前用户不可参与工作流");
        assertThatThrownBy(() -> tasks.listAssigned(null,1,20)).isInstanceOf(ServiceException.class);
    }

    @Test
    void retainsMissingAssociationsAndCrossObjectRelationshipFailures()
    {
        var instance = start("700"); var task = taskOf(instance);
        engine.getTaskService().claim(task.getId(),"700"); engine.getTaskService().complete(task.getId(),"700");
        support.jdbcTemplate().update("update ACT_HI_TASKINST set PROC_DEF_ID_='missing' where ID_=?",task.getId());
        assertServiceFailure(() -> tasks.listCompleted(null,1,20),404,"流程定义不存在或已被删除");
        support.jdbcTemplate().update("update ACT_HI_TASKINST set PROC_DEF_ID_=? where ID_=?",definitionId,task.getId());
        support.jdbcTemplate().update("update ACT_HI_PROCINST set PROC_DEF_ID_='other' where PROC_INST_ID_=?",instance.getId());
        assertServiceFailure(() -> tasks.listCompleted(null,1,20),500,"任务与流程实例定义关系不一致");
        support.jdbcTemplate().update("delete from ACT_HI_PROCINST where PROC_INST_ID_=?",instance.getId());
        assertServiceFailure(() -> tasks.listCompleted(null,1,20),500,"任务缺少所属历史流程实例");
    }

    @Test
    void retainsDifferentMissingDeploymentSemanticsForInstancesAndTasks()
    {
        var instance = start("700");
        // Genuine missing deployment association; no mocked duplicate PK or impossible query result.
        support.jdbcTemplate().update("delete from ACT_GE_BYTEARRAY where DEPLOYMENT_ID_=?",deploymentId);
        support.jdbcTemplate().update("delete from ACT_RE_DEPLOYMENT where ID_=?",deploymentId);
        assertThat(instances.listManaged(null,1,20).rows()).singleElement().satisfies(row -> assertThat(row.category()).isNull());
        assertServiceFailure(() -> tasks.listClaimable(null,1,20),500,"任务缺少所属流程部署");
    }

    @Test
    void propagatesRealDatabaseFailureAndReadsNamesInTheListTransaction()
    {
        start("700");
        doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            return call.callRealMethod();
        }).when(users).selectRawNickNamesByIds(anyList());
        assertThat(instances.listManaged(null,1,20).rows().get(0).startUserName()).isEqualTo(" 当前昵称 ");
        support.jdbcTemplate().execute("alter table sys_user rename column nick_name to unavailable_nick_name");
        assertThatThrownBy(() -> tasks.listClaimable(null,1,20)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void copyPagePreservesSnapshotJsonAndRecipientFilterWithoutNameReads()
    {
        var instance = start("700");
        for (long receiver : List.of(700L,701L,700L))
        {
            WfCopy copy = new WfCopy(); copy.setCopyEventId(UUID.randomUUID().toString()); copy.setTitle("历史抄送");
            copy.setProcessId(definitionId); copy.setProcessName("工作台回归"); copy.setCategoryId("finance"); copy.setDeploymentId(deploymentId);
            copy.setInstanceId(instance.getId()); copy.setTaskId(taskOf(instance).getId()); copy.setUserId(receiver); copy.setOriginatorId(700L);
            copy.setOriginatorName("事件时昵称"); copy.setSourceType("MANUAL"); copy.setTriggerType("MANUAL_COMPLETE"); copy.setCreateBy("700");
            copies.insertBatch(List.of(copy));
        }
        var page = instances.listCopies(new WorkflowCopyQueryDto("历史","工作台","事件","finance","0"),1,20);
        assertThat(page.total()).isEqualTo(2);
        for (var row : page.rows())
            assertJson(row, fields("copyId",row.copyId(),"title","历史抄送","processId",definitionId,"processName","工作台回归","categoryId","finance",
                    "deploymentId",deploymentId,"instanceId",instance.getId(),"taskId",taskOf(instance).getId(),"userId",700L,"originatorId",700L,
                    "originatorName","事件时昵称","sourceType","MANUAL","triggerType","MANUAL_COMPLETE","triggerNodeId",null,"triggerNodeName",null,
                    "readStatus","0","readTime",null,"createTime",copies.selectByIdAndUserId(row.copyId(),700L).getCreateTime().toInstant()));
        assertThat(instances.listCopies(null,2,1).rows()).containsExactly(page.rows().get(1));
        assertThat(instances.listCopies(null,3,1).rows()).isEmpty();
        assertThat(instances.listCopies(new WorkflowCopyQueryDto(null,null,null,null,"1"),1,20).total()).isZero();
        verifyNoInteractions(users);
    }

    private ProcessInstance start(String userId)
    {
        engine.getIdentityService().setAuthenticatedUserId(userId);
        try { return engine.getRuntimeService().createProcessInstanceBuilder().processDefinitionKey("workbench").businessKey("business-"+userId).businessStatus("running").start(); }
        finally { engine.getIdentityService().setAuthenticatedUserId(null); }
    }

    private Task taskOf(ProcessInstance instance) { return engine.getTaskService().createTaskQuery().processInstanceId(instance.getId()).singleResult(); }

    @SuppressWarnings({"rawtypes","unchecked"})
    private void assertOneNameBatch(Set<Long> expected)
    {
        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        verify(users).selectRawNickNamesByIds(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrderElementsOf(expected);
    }

    private void assertBadRequest(Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(400)); }
    private void assertServiceFailure(Runnable action, int code, String message) { assertThatThrownBy(action::run).isInstanceOfSatisfying(ServiceException.class, e -> { assertThat(e.getCode()).isEqualTo(code); assertThat(e.getMessage()).isEqualTo(message); }); }
    private static Map<String,Object> fields(Object... pairs) { Map<String,Object> result=new LinkedHashMap<>(); for (int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]); return result; }
    private void assertJson(Object actual, Map<String,Object> expected)
    {
        tools.jackson.databind.JsonNode actualJson = JsonMapper.shared().valueToTree(actual);
        tools.jackson.databind.JsonNode expectedJson = JsonMapper.shared().valueToTree(expected);
        assertThat(actualJson).isEqualTo(expectedJson);
    }
}
