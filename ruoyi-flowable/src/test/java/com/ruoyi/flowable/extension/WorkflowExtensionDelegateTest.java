package com.ruoyi.flowable.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;
import com.ruoyi.flowable.mapper.WfDeployExtensionSnapshotMapper;
import com.ruoyi.flowable.service.model.WorkflowExtensionDeploymentService;
import tools.jackson.databind.JsonNode;

/**
 * Flowable 固定扩展调度器的快照定位、完整性复核和处理器调用测试。
 */
class WorkflowExtensionDelegateTest
{
    private RepositoryService repositoryService;
    private WfDeployExtensionSnapshotMapper snapshotMapper;
    private WorkflowJavaExtensionHandler handler;
    private DelegateExecution execution;
    private WorkflowExtensionDelegate delegate;
    private WfDeployExtensionSnapshot snapshot;

    /**
     * 创建一条校验和完整的运行快照和固定执行上下文。
     * @return 无返回值；初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        repositoryService = mock(RepositoryService.class);
        snapshotMapper = mock(WfDeployExtensionSnapshotMapper.class);
        handler = mock(WorkflowJavaExtensionHandler.class);
        when(handler.implementationKey()).thenReturn("SET_VARIABLE");
        when(handler.displayName()).thenReturn("设置流程变量");
        when(handler.configSchema()).thenReturn("{\"type\":\"object\"}");
        WorkflowJavaExtensionHandlerRegistry registry =
                new WorkflowJavaExtensionHandlerRegistry(List.of(handler));
        delegate = new WorkflowExtensionDelegate(repositoryService, snapshotMapper, registry,
                mock(WorkflowHttpConnector.class), mock(WorkflowSqlConnector.class));

        execution = mock(DelegateExecution.class);
        when(execution.getProcessDefinitionId()).thenReturn("definition-1");
        when(execution.getCurrentActivityId()).thenReturn("set-result");
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getDeploymentId()).thenReturn("deployment-1");
        when(definition.getKey()).thenReturn("expense");
        when(repositoryService.getProcessDefinition("definition-1")).thenReturn(definition);

        snapshot = snapshot();
        snapshot.setSnapshotChecksum(WorkflowExtensionDeploymentService.snapshotChecksum(snapshot));
        when(snapshotMapper.selectRuntimeSnapshot(
                "deployment-1", "expense", "set-result")).thenReturn(snapshot);
        when(handler.validateAndNormalizeConfig(any(JsonNode.class)))
                .thenReturn(snapshot.getConfigJson());
    }

    /**
     * 验证调度器使用部署、流程和活动三元组定位快照并执行唯一安装处理器。
     * @return 无返回值；快照定位或处理器调用漂移时测试失败
     */
    @Test
    void executesHandlerFromVerifiedProcessScopedSnapshot()
    {
        delegate.execute(execution);

        verify(snapshotMapper).selectRuntimeSnapshot(
                "deployment-1", "expense", "set-result");
        verify(handler).execute(any(DelegateExecution.class), any(JsonNode.class));
    }

    /**
     * 验证 MySQL JSON 仅重排对象键时，快照摘要和运行规范化仍保持一致。
     * @return 无返回值；数据库键顺序差异导致合法快照误拒绝时测试失败
     */
    @Test
    void acceptsMySqlJsonObjectKeyReordering()
    {
        snapshot.setConfigJson("{\"value\":true,\"targetVariable\":\"result\"}");
        snapshot.setSnapshotChecksum(WorkflowExtensionDeploymentService.snapshotChecksum(snapshot));
        when(handler.validateAndNormalizeConfig(any(JsonNode.class)))
                .thenReturn("{\"targetVariable\":\"result\",\"value\":true}");

        delegate.execute(execution);

        verify(handler).execute(any(DelegateExecution.class), any(JsonNode.class));
    }

    /**
     * 验证快照缺失或摘要被篡改时返回 500 且处理器零执行。
     * @return 无返回值；非法快照产生业务副作用时测试失败
     */
    @Test
    void rejectsMissingOrTamperedSnapshotWithoutHandlerExecution()
    {
        when(snapshotMapper.selectRuntimeSnapshot(
                "deployment-1", "expense", "set-result")).thenReturn(null);
        assertServerError(() -> delegate.execute(execution), "扩展执行快照不存在");

        snapshot.setSnapshotChecksum("0".repeat(64));
        when(snapshotMapper.selectRuntimeSnapshot(
                "deployment-1", "expense", "set-result")).thenReturn(snapshot);
        assertServerError(() -> delegate.execute(execution), "校验和不一致");

        verify(handler, never()).execute(any(), any());
    }

    /**
     * 验证运行时规范化结果与冻结 JSON 不一致时拒绝执行，防止代码升级改变旧部署语义。
     * @return 无返回值；配置漂移未被阻止时测试失败
     */
    @Test
    void rejectsRuntimeNormalizationDrift()
    {
        when(handler.validateAndNormalizeConfig(any(JsonNode.class)))
                .thenReturn("{\"targetVariable\":\"other\",\"value\":true}");

        assertServerError(() -> delegate.execute(execution), "规范化结果已漂移");

        verify(handler, never()).execute(any(), any());
    }

    /**
     * 验证已安装 Schema 与部署版本摘要漂移时，在处理器或 CEL 执行前失败关闭。
     * @return 无返回值；版本漂移产生任一运行副作用时测试失败
     */
    @Test
    void rejectsInstalledSchemaDriftBeforeExecution()
    {
        snapshot.setVersionChecksum("0".repeat(64));
        snapshot.setSnapshotChecksum(WorkflowExtensionDeploymentService.snapshotChecksum(snapshot));

        assertServerError(() -> delegate.execute(execution), "版本校验和不一致");

        verify(handler, never()).validateAndNormalizeConfig(any());
        verify(handler, never()).execute(any(), any());
    }

    /**
     * 验证 CEL 运行只使用冻结配置和白名单变量，运行类型错误时不写入结果变量。
     * @return 无返回值；合法结果未写入或非法输入产生写入副作用时测试失败
     */
    @Test
    void executesFrozenCelAndRejectsRuntimeTypeWithZeroWrites()
    {
        snapshot.setExtensionKey("approva.cel-expression");
        snapshot.setExtensionType("CEL");
        snapshot.setImplementationKey("CEL_EXPRESSION_V1");
        snapshot.setConfigJson("{\"expression\":\"amount >= 1000.5\","
                + "\"resultVariable\":\"eligible\",\"resultType\":\"BOOL\","
                + "\"variables\":[{\"name\":\"amount\",\"type\":\"DOUBLE\"}]}");
        WorkflowCelSandbox sandbox = new WorkflowCelSandbox();
        snapshot.setVersionChecksum(WorkflowExtensionChecksum.sha256(
                snapshot.getExtensionKey(), snapshot.getExtensionType(),
                String.valueOf(snapshot.getVersionNo()), snapshot.getImplementationKey(),
                sandbox.configSchema()));
        snapshot.setSnapshotChecksum(WorkflowExtensionDeploymentService.snapshotChecksum(snapshot));
        when(execution.hasVariable("amount")).thenReturn(true);
        when(execution.getVariable("amount")).thenReturn(1200.25D);

        delegate.execute(execution);

        verify(execution).setVariable("eligible", true);

        DelegateExecution invalidExecution = mock(DelegateExecution.class);
        when(invalidExecution.getProcessDefinitionId()).thenReturn("definition-1");
        when(invalidExecution.getCurrentActivityId()).thenReturn("set-result");
        when(invalidExecution.hasVariable("amount")).thenReturn(true);
        when(invalidExecution.getVariable("amount")).thenReturn(new Object());
        assertServerError(() -> delegate.execute(invalidExecution), "声明类型 DOUBLE 不一致");
        verify(invalidExecution, never()).setVariable(any(), any());
    }

    /**
     * 创建字段完整但尚未写入最终摘要的运行快照。
     * @return WfDeployExtensionSnapshot，固定测试快照
     */
    private WfDeployExtensionSnapshot snapshot()
    {
        WfDeployExtensionSnapshot value = new WfDeployExtensionSnapshot();
        value.setDeployId("deployment-1");
        value.setProcessKey("expense");
        value.setElementId("set-result");
        value.setExtensionKey("approva.set-variable");
        value.setExtensionVersionId(1L);
        value.setVersionNo(1);
        value.setExtensionType("JAVA");
        value.setImplementationKey("SET_VARIABLE");
        value.setConfigJson("{\"targetVariable\":\"result\",\"value\":true}");
        value.setVersionChecksum(WorkflowExtensionChecksum.sha256(
                "approva.set-variable", "JAVA", "1", "SET_VARIABLE",
                WorkflowExtensionJsonCanonicalizer.canonicalize(handler.configSchema())));
        return value;
    }

    /**
     * 断言调度命令以稳定 500 业务异常失败。
     * @param command Runnable，待执行调度命令
     * @param messagePart String，预期异常关键信息
     * @return 无返回值；异常边界不一致时测试失败
     */
    private void assertServerError(Runnable command, String messagePart)
    {
        assertThatThrownBy(command::run)
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(HttpStatus.ERROR))
                .hasMessageContaining(messagePart);
    }
}
