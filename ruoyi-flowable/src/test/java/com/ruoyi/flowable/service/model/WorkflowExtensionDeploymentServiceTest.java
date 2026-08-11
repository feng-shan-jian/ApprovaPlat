package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FieldExtension;
import org.flowable.bpmn.model.FlowableListener;
import org.flowable.bpmn.model.ImplementationType;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SendTask;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.UserTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;
import com.ruoyi.flowable.domain.vo.WorkflowExtensionOptionView;
import com.ruoyi.flowable.extension.WorkflowExtensionBpmnContract;
import com.ruoyi.flowable.extension.WorkflowJavaExtensionHandler;
import com.ruoyi.flowable.extension.WorkflowJavaExtensionHandlerRegistry;
import com.ruoyi.flowable.extension.WorkflowHttpConnector;
import tools.jackson.databind.JsonNode;

/**
 * 受控扩展部署编译、版本冻结和快照持久化测试。
 */
class WorkflowExtensionDeploymentServiceTest
{
    private WorkflowExtensionRegistryService registryService;
    private WorkflowJavaExtensionHandler handler;
    private WorkflowExtensionDeploymentService service;

    /**
     * 创建固定版本、处理器和 Mapper 替身。
     * @return 无返回值；初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        registryService = mock(WorkflowExtensionRegistryService.class);
        handler = mock(WorkflowJavaExtensionHandler.class);
        when(handler.implementationKey()).thenReturn("SET_VARIABLE");
        when(handler.displayName()).thenReturn("设置流程变量");
        when(handler.configSchema()).thenReturn("{\"type\":\"object\"}");
        WorkflowJavaExtensionHandlerRegistry handlerRegistry =
                new WorkflowJavaExtensionHandlerRegistry(List.of(handler));
        service = new WorkflowExtensionDeploymentService(
                registryService, handlerRegistry, mock(WorkflowHttpConnector.class),
                mock(com.ruoyi.flowable.extension.WorkflowSqlConnector.class));
        when(handler.validateAndNormalizeConfig(any(JsonNode.class)))
                .thenReturn("{\"targetVariable\":\"result\",\"value\":true}");
        when(registryService.lockLatestForDeployment("approva.set-variable"))
                .thenReturn(option(1L, 1, "version-checksum"));
    }

    /**
     * 验证编译资源移除作者字段、冻结精确版本，同时不修改作者 BPMN 模型。
     * @return 无返回值；作者模型、编译 XML 或快照协议漂移时测试失败
     */
    @Test
    void compilesControlledTaskWithoutMutatingAuthorModel()
    {
        ServiceTask authorTask = controlledTask("set-result");
        WorkflowBpmnDocument document = document(process("expense", authorTask));

        WorkflowPreparedExtensionDeployment prepared = service.prepare(document, "7");

        assertThat(authorTask.getFieldExtensions()).hasSize(2);
        assertThat(authorTask.getImplementation())
                .isEqualTo(WorkflowExtensionBpmnContract.DELEGATE_EXPRESSION);
        String compiledXml = new String(prepared.compiledBpmn(), StandardCharsets.UTF_8);
        assertThat(compiledXml)
                .contains("workflowExtensionDelegate")
                .doesNotContain(WorkflowExtensionBpmnContract.EXTENSION_KEY_FIELD)
                .doesNotContain(WorkflowExtensionBpmnContract.EXTENSION_CONFIG_FIELD);
        assertThat(prepared.snapshots()).singleElement().satisfies(snapshot ->
        {
            assertThat(snapshot.getProcessKey()).isEqualTo("expense");
            assertThat(snapshot.getElementId()).isEqualTo("set-result");
            assertThat(snapshot.getExtensionVersionId()).isEqualTo(1L);
            assertThat(snapshot.getVersionNo()).isEqualTo(1);
            assertThat(snapshot.getCreateBy()).isEqualTo("7");
        });
    }

    /**
     * 验证 SendTask 在执行副本中转换为固定委托 ServiceTask，同时作者类型和字段保持不变。
     * @return 无返回值；发送任务未冻结、作者模型被修改或执行 XML 仍含 sendTask 时测试失败
     */
    @Test
    void compilesControlledSendTaskIntoExecutableServiceTask()
    {
        SendTask authorTask = controlledSendTask("notify");
        WorkflowBpmnDocument document = document(process("expense", authorTask));

        WorkflowPreparedExtensionDeployment prepared = service.prepare(document, "7");

        assertThat(authorTask.getFieldExtensions()).hasSize(2);
        assertThat(document.bpmnModel().getMainProcess().getFlowElement("notify"))
                .isInstanceOf(SendTask.class);
        String compiledXml = new String(prepared.compiledBpmn(), StandardCharsets.UTF_8);
        assertThat(compiledXml)
                .contains("<serviceTask")
                .contains("id=\"notify\"")
                .contains("workflowExtensionDelegate")
                .doesNotContain("<sendTask")
                .doesNotContain(WorkflowExtensionBpmnContract.EXTENSION_KEY_FIELD);
        assertThat(prepared.snapshots()).singleElement().satisfies(snapshot ->
        {
            assertThat(snapshot.getProcessKey()).isEqualTo("expense");
            assertThat(snapshot.getElementId()).isEqualTo("notify");
            assertThat(snapshot.getExtensionKey()).isEqualTo("approva.set-variable");
        });
    }

    /**
     * 验证执行和任务业务监听器冻结同一 Java 注册表版本，编译资源不再携带作者字段。
     * @return 无返回值；监听器快照身份、处理器能力或编译 XML 漂移时测试失败
     */
    @Test
    void compilesControlledBusinessListenersIntoFrozenSnapshots()
    {
        when(handler.supportsBusinessListener()).thenReturn(true);
        UserTask task = new UserTask();
        task.setId("approve");
        task.setExecutionListeners(new java.util.ArrayList<>(List.of(
                businessListener("start"))));
        task.setTaskListeners(new java.util.ArrayList<>(List.of(
                businessListener("complete"))));

        WorkflowPreparedExtensionDeployment prepared = service.prepare(
                document(process("expense", task)), "7");

        assertThat(prepared.snapshots()).hasSize(2)
                .allSatisfy(snapshot ->
                {
                    assertThat(snapshot.getProcessKey()).isEqualTo("expense");
                    assertThat(snapshot.getElementId()).startsWith("listener_");
                    assertThat(snapshot.getExtensionKey()).isEqualTo("approva.set-variable");
                    assertThat(snapshot.getImplementationKey()).isEqualTo("SET_VARIABLE");
                });
        String compiledXml = new String(prepared.compiledBpmn(), StandardCharsets.UTF_8);
        assertThat(compiledXml)
                .contains("workflowBusinessListener")
                .doesNotContain(WorkflowExtensionBpmnContract.EXTENSION_KEY_FIELD)
                .doesNotContain(WorkflowExtensionBpmnContract.EXTENSION_CONFIG_FIELD);
        assertThat(task.getExecutionListeners()).singleElement()
                .satisfies(listener -> assertThat(listener.getFieldExtensions()).hasSize(2));
        assertThat(task.getTaskListeners()).singleElement()
                .satisfies(listener -> assertThat(listener.getFieldExtensions()).hasSize(2));
    }

    /**
     * 验证同一 BPMN 文档内多个可执行流程分别生成带 processKey 的独立快照。
     * @return 无返回值；跨流程快照身份丢失时测试失败
     */
    @Test
    void createsProcessScopedSnapshotsForMultipleProcesses()
    {
        BpmnModel model = new BpmnModel();
        model.addProcess(process("expense", controlledTask("expense-task")));
        model.addProcess(process("leave", controlledTask("leave-task")));

        WorkflowPreparedExtensionDeployment prepared = service.prepare(
                new WorkflowBpmnDocument(model, "author", List.of()), "7");

        assertThat(prepared.snapshots()).extracting(WfDeployExtensionSnapshot::getProcessKey)
                .containsExactlyInAnyOrder("expense", "leave");
    }

    /**
     * 验证 CEL 作者配置在部署前真实编译，并冻结精确版本和规范 JSON 快照。
     * @return 无返回值；CEL 配置未编译、版本未冻结或作者模型被修改时测试失败
     */
    @Test
    void compilesCelTaskAndFreezesNormalizedSnapshot()
    {
        ServiceTask authorTask = controlledTask("evaluate-eligibility");
        authorTask.getFieldExtensions().set(0, field(
                WorkflowExtensionBpmnContract.EXTENSION_KEY_FIELD,
                "approva.cel-expression"));
        authorTask.getFieldExtensions().set(1, field(
                WorkflowExtensionBpmnContract.EXTENSION_CONFIG_FIELD,
                "{\"variables\":[{\"type\":\"BOOL\",\"name\":\"approved\"},"
                + "{\"type\":\"DOUBLE\",\"name\":\"amount\"}],"
                + "\"resultType\":\"BOOL\",\"resultVariable\":\"eligible\","
                + "\"expression\":\"amount >= 1000.5 && approved\"}"));
        when(registryService.lockLatestForDeployment("approva.cel-expression"))
                .thenReturn(new WorkflowExtensionOptionView(
                        12L, "approva.cel-expression", "CEL 表达式", "CEL", 22L, 3,
                        "CEL_EXPRESSION_V1", "{\"type\":\"object\"}", "cel-version-checksum"));

        WorkflowPreparedExtensionDeployment prepared = service.prepare(
                document(process("expense", authorTask)), "7");

        assertThat(authorTask.getFieldExtensions()).hasSize(2);
        assertThat(prepared.snapshots()).singleElement().satisfies(snapshot ->
        {
            assertThat(snapshot.getExtensionKey()).isEqualTo("approva.cel-expression");
            assertThat(snapshot.getExtensionType()).isEqualTo("CEL");
            assertThat(snapshot.getImplementationKey()).isEqualTo("CEL_EXPRESSION_V1");
            assertThat(snapshot.getExtensionVersionId()).isEqualTo(22L);
            assertThat(snapshot.getVersionNo()).isEqualTo(3);
            assertThat(snapshot.getVersionChecksum()).isEqualTo("cel-version-checksum");
            assertThat(snapshot.getConfigJson()).isEqualTo(
                    "{\"expression\":\"amount >= 1000.5 && approved\","
                    + "\"resultType\":\"BOOL\",\"resultVariable\":\"eligible\","
                    + "\"variables\":[{\"name\":\"amount\",\"type\":\"DOUBLE\"},"
                    + "{\"name\":\"approved\",\"type\":\"BOOL\"}]}");
        });
        assertThat(new String(prepared.compiledBpmn(), StandardCharsets.UTF_8))
                .doesNotContain(WorkflowExtensionBpmnContract.EXTENSION_CONFIG_FIELD);
    }

    /**
     * 验证任意字段注入、表达式字段和非法 JSON 都在 Flowable 部署前以 400 拒绝且不落快照。
     * @return 无返回值；非法作者协议越过编译门禁时测试失败
     */
    @Test
    void rejectsUnregisteredFieldsAndInvalidConfigurationBeforePersistence()
    {
        ServiceTask injected = controlledTask("unsafe");
        injected.getFieldExtensions().add(field("arbitraryBean", "danger"));
        assertBadRequest(document(process("expense", injected)), "未注册的字段注入");

        ServiceTask invalidJson = controlledTask("invalid-json");
        invalidJson.getFieldExtensions().set(1,
                field(WorkflowExtensionBpmnContract.EXTENSION_CONFIG_FIELD, "{"));
        assertBadRequest(document(process("expense", invalidJson)), "不是合法 JSON");

    }

    /**
     * 验证持久化会绑定部署主键、计算完整摘要并严格检查批量写入行数。
     * @return 无返回值；快照绑定或行数门禁漂移时测试失败
     */
    @Test
    void bindsExactSnapshotRows()
    {
        WorkflowPreparedExtensionDeployment prepared = service.prepare(
                document(process("expense", controlledTask("set-result"))), "7");
        WfDeployExtensionSnapshot inserted = service
                .snapshotsForDeployment("deployment-1", prepared).get(0);
        assertThat(inserted.getDeployId()).isEqualTo("deployment-1");
        assertThat(inserted.getSnapshotChecksum())
                .isEqualTo(WorkflowExtensionDeploymentService.snapshotChecksum(inserted));
    }

    /**
     * 验证表单字段快照与服务任务快照在一次批量写入中绑定同一部署主键。
     * @return 无返回值；附加快照遗漏、摘要缺失或跨来源冲突未拒绝时测试失败
     */
    @Test
    void mergesFormFieldSnapshotsIntoDeploymentLedger()
    {
        WorkflowPreparedExtensionDeployment prepared = service.prepare(
                document(process("expense", controlledTask("set-result"))), "7");
        WfDeployExtensionSnapshot formField = new WfDeployExtensionSnapshot();
        formField.setProcessKey("expense");
        formField.setElementId("review#form#detail");
        formField.setExtensionKey("approva.form.textarea");
        formField.setExtensionVersionId(30L);
        formField.setVersionNo(1);
        formField.setExtensionType("FORM_FIELD");
        formField.setImplementationKey("FORM_FIELD_TEXTAREA_V1");
        formField.setConfigJson("{}");
        formField.setVersionChecksum("f".repeat(64));
        formField.setCreateBy("7");
        List<WfDeployExtensionSnapshot> snapshots = service.snapshotsForDeployment(
                "deployment-form", prepared, List.of(formField));
        assertThat(snapshots).hasSize(2)
                .allSatisfy(snapshot ->
                {
                    assertThat(snapshot.getDeployId()).isEqualTo("deployment-form");
                    assertThat(snapshot.getSnapshotChecksum()).matches("[0-9a-f]{64}");
                });

        formField.setElementId("set-result");
        assertThatThrownBy(() -> service.snapshotsForDeployment("deployment-conflict", prepared,
                List.of(formField)))
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("元素标识重复");
    }

    /**
     * 验证准备结果对编译字节和快照元素执行防御性复制。
     * @return 无返回值；调用方能够篡改准备结果时测试失败
     */
    @Test
    void protectsPreparedDeploymentFromCallerMutation()
    {
        byte[] bytes = "compiled".getBytes(StandardCharsets.UTF_8);
        WfDeployExtensionSnapshot source = new WfDeployExtensionSnapshot();
        source.setElementId("task-1");
        WorkflowPreparedExtensionDeployment prepared =
                new WorkflowPreparedExtensionDeployment(bytes, List.of(source));

        bytes[0] = 'X';
        source.setElementId("tampered");
        WfDeployExtensionSnapshot returned = prepared.snapshots().get(0);
        returned.setElementId("also-tampered");

        assertThat(new String(prepared.compiledBpmn(), StandardCharsets.UTF_8))
                .isEqualTo("compiled");
        assertThat(prepared.snapshots().get(0).getElementId()).isEqualTo("task-1");
    }

    /**
     * 创建一条作者受控服务任务。
     * @param elementId String，活动标识
     * @return ServiceTask，带固定调度器和作者字段的服务任务
     */
    private ServiceTask controlledTask(String elementId)
    {
        ServiceTask task = new ServiceTask();
        task.setId(elementId);
        task.setName("设置变量");
        task.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
        task.setImplementation(WorkflowExtensionBpmnContract.DELEGATE_EXPRESSION);
        task.getFieldExtensions().add(field(
                WorkflowExtensionBpmnContract.EXTENSION_KEY_FIELD, "approva.set-variable"));
        task.getFieldExtensions().add(field(
                WorkflowExtensionBpmnContract.EXTENSION_CONFIG_FIELD,
                "{\"value\":true,\"targetVariable\":\"result\"}"));
        return task;
    }

    /**
     * 创建一个引用固定业务监听 Bean 和受控 Java 扩展字段的作者监听器。
     * @param event String，执行或任务生命周期事件名
     * @return FlowableListener，可由部署编译器冻结的作者监听器
     */
    private FlowableListener businessListener(String event)
    {
        FlowableListener listener = new FlowableListener();
        listener.setEvent(event);
        listener.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
        listener.setImplementation(
                WorkflowExtensionBpmnContract.BUSINESS_LISTENER_DELEGATE_EXPRESSION);
        listener.setFieldExtensions(new java.util.ArrayList<>(List.of(
                field(WorkflowExtensionBpmnContract.EXTENSION_KEY_FIELD,
                        "approva.set-variable"),
                field(WorkflowExtensionBpmnContract.EXTENSION_CONFIG_FIELD,
                        "{\"targetVariable\":\"result\",\"value\":true}"))));
        return listener;
    }

    /**
     * 创建一条使用受控扩展字段的作者发送任务。
     * @param elementId String，活动标识
     * @return SendTask，保留 BPMN 发送任务类型的作者模型节点
     */
    private SendTask controlledSendTask(String elementId)
    {
        SendTask task = new SendTask();
        task.setId(elementId);
        task.setName("发送通知");
        task.getFieldExtensions().add(field(
                WorkflowExtensionBpmnContract.EXTENSION_KEY_FIELD, "approva.set-variable"));
        task.getFieldExtensions().add(field(
                WorkflowExtensionBpmnContract.EXTENSION_CONFIG_FIELD,
                "{\"value\":true,\"targetVariable\":\"result\"}"));
        return task;
    }

    /**
     * 创建 Flowable 字符串字段。
     * @param name String，字段名
     * @param value String，字段值
     * @return FieldExtension，作者扩展字段
     */
    private FieldExtension field(String name, String value)
    {
        FieldExtension field = new FieldExtension();
        field.setFieldName(name);
        field.setStringValue(value);
        return field;
    }

    /**
     * 创建包含指定流程元素的可执行流程。
     * @param processKey String，流程标识
     * @param task org.flowable.bpmn.model.FlowElement，受控任务
     * @return Process，可执行 BPMN 流程
     */
    private Process process(String processKey, org.flowable.bpmn.model.FlowElement task)
    {
        Process process = new Process();
        process.setId(processKey);
        process.setName(processKey);
        process.setExecutable(true);
        process.addFlowElement(task);
        return process;
    }

    /**
     * 创建单流程已校验文档。
     * @param process Process，可执行流程
     * @return WorkflowBpmnDocument，部署编译输入
     */
    private WorkflowBpmnDocument document(Process process)
    {
        BpmnModel model = new BpmnModel();
        model.addProcess(process);
        return new WorkflowBpmnDocument(model, "author", List.of());
    }

    /**
     * 创建数据库冻结版本视图。
     * @param versionId Long，版本主键
     * @param versionNo int，版本号
     * @param checksum String，版本摘要
     * @return WorkflowExtensionOptionView，部署选择视图
     */
    private WorkflowExtensionOptionView option(Long versionId, int versionNo, String checksum)
    {
        return new WorkflowExtensionOptionView(10L, "approva.set-variable", "设置流程变量",
                "JAVA", versionId, versionNo, "SET_VARIABLE",
                "{\"type\":\"object\"}", checksum);
    }

    /**
     * 断言文档在部署准备阶段以稳定 400 业务异常拒绝。
     * @param document WorkflowBpmnDocument，待编译文档
     * @param messagePart String，预期异常关键信息
     * @return 无返回值；异常边界不一致时测试失败
     */
    private void assertBadRequest(WorkflowBpmnDocument document, String messagePart)
    {
        assertThatThrownBy(() -> service.prepare(document, "7"))
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining(messagePart);
    }
}
