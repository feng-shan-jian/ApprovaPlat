package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.List;
import java.util.Set;
import org.flowable.engine.IdentityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfBpmnExtension;
import com.ruoyi.flowable.domain.WfBpmnExtensionVersion;
import com.ruoyi.flowable.domain.dto.WorkflowExtensionCreateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowExtensionVersionCreateRequest;
import com.ruoyi.flowable.domain.vo.WorkflowExtensionOptionView;
import com.ruoyi.flowable.domain.vo.WorkflowExtensionManagementView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.extension.WorkflowExtensionChecksum;
import com.ruoyi.flowable.extension.WorkflowExtensionJsonCanonicalizer;
import com.ruoyi.flowable.extension.WorkflowCelSandbox;
import com.ruoyi.flowable.extension.WorkflowJavaExtensionHandlerRegistry;
import com.ruoyi.flowable.extension.WorkflowSetVariableJavaHandler;
import com.ruoyi.flowable.extension.WorkflowHttpConnector;
import com.ruoyi.flowable.extension.WorkflowFormFieldExtension;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfBpmnExtensionMapper;

/**
 * 扩展目录、不可变版本、状态变更和部署锁定的领域服务测试。
 */
class WorkflowExtensionRegistryServiceTest
{
    private WfBpmnExtensionMapper mapper;
    private WorkflowDeploymentArtifactRepository artifactRepository;
    private WorkflowSetVariableJavaHandler handler;
    private WorkflowHttpConnector httpConnector;
    private WorkflowExtensionRegistryService service;

    /**
     * 建立真实事务与当前身份边界，并安装唯一内置处理器。
     * @return 无返回值；初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                Connection.TRANSACTION_REPEATABLE_READ);
        mapper = mock(WfBpmnExtensionMapper.class);
        artifactRepository = mock(WorkflowDeploymentArtifactRepository.class);
        handler = new WorkflowSetVariableJavaHandler();
        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveCurrentIdentity())
                .thenReturn(new WorkflowCurrentIdentity("7", Set.of()));
        WorkflowEngineOperations operations = new WorkflowEngineOperations(
                new WorkflowAuthenticationContext(mock(IdentityService.class),
                        new WorkflowIdentityCodec()),
                new WorkflowExceptionTranslator(), identityResolver);
        httpConnector = mock(WorkflowHttpConnector.class);
        when(httpConnector.configSchema()).thenReturn(
                new WorkflowHttpConnector(null, null, null).configSchema());
        service = new WorkflowExtensionRegistryService(operations, mapper, artifactRepository,
                new WorkflowJavaExtensionHandlerRegistry(List.of(handler)),
                httpConnector, mock(com.ruoyi.flowable.extension.WorkflowSqlConnector.class));
    }

    /**
     * 清理测试线程事务特征。
     * @return 无返回值；清理后线程不携带模拟事务
     */
    @AfterEach
    void clearTransaction()
    {
        TransactionSynchronizationManager.clear();
    }

    /**
     * 验证创建目录会规范化字段、绑定正式用户并严格要求数据库生成主键。
     * @return 无返回值；目录持久化协议漂移时测试失败
     */
    @Test
    void createsControlledExtensionDirectory()
    {
        when(mapper.selectByKey("approva.route-marker")).thenReturn(null);
        when(mapper.insertExtension(any())).thenAnswer(invocation ->
        {
            WfBpmnExtension value = invocation.getArgument(0);
            value.setExtensionId(11L);
            return 1;
        });

        Long extensionId = service.createExtension(new WorkflowExtensionCreateRequest(
                " approva.route-marker ", " 路由标记 ", "JAVA", " 说明 "));

        assertThat(extensionId).isEqualTo(11L);
        ArgumentCaptor<WfBpmnExtension> captor = ArgumentCaptor.forClass(WfBpmnExtension.class);
        verify(mapper).insertExtension(captor.capture());
        assertThat(captor.getValue().getExtensionKey()).isEqualTo("approva.route-marker");
        assertThat(captor.getValue().getExtensionName()).isEqualTo("路由标记");
        assertThat(captor.getValue().getStatus()).isEqualTo("ENABLED");
        assertThat(captor.getValue().getCreateBy()).isEqualTo("7");
    }

    /**
     * 验证版本号在目录行锁内连续递增，Schema 只能来自代码安装处理器且摘要可复算。
     * @return 无返回值；版本冻结协议漂移时测试失败
     */
    @Test
    void publishesNextImmutableVersionFromInstalledHandler()
    {
        WfBpmnExtension extension = extension(11L, "approva.route-marker", "ENABLED");
        when(mapper.selectByIdForUpdate(11L)).thenReturn(extension);
        when(mapper.selectMaxVersionNo(11L)).thenReturn(2);
        when(mapper.insertVersion(any())).thenAnswer(invocation ->
        {
            WfBpmnExtensionVersion value = invocation.getArgument(0);
            value.setVersionId(31L);
            return 1;
        });

        Long versionId = service.createVersion(11L,
                new WorkflowExtensionVersionCreateRequest("SET_VARIABLE"));

        assertThat(versionId).isEqualTo(31L);
        ArgumentCaptor<WfBpmnExtensionVersion> captor =
                ArgumentCaptor.forClass(WfBpmnExtensionVersion.class);
        verify(mapper).insertVersion(captor.capture());
        WfBpmnExtensionVersion version = captor.getValue();
        assertThat(version.getVersionNo()).isEqualTo(3);
        assertThat(version.getImplementationKey()).isEqualTo("SET_VARIABLE");
        String canonicalSchema = WorkflowExtensionJsonCanonicalizer
                .canonicalize(handler.configSchema());
        assertThat(version.getConfigSchema()).isEqualTo(canonicalSchema);
        assertThat(version.getChecksum()).isEqualTo(WorkflowExtensionChecksum.sha256(
                "approva.route-marker", "JAVA", "3", "SET_VARIABLE",
                canonicalSchema));
        assertThat(version.getCreateBy()).isEqualTo("7");
    }

    /**
     * 验证 CEL 目录只能发布固定实现键，并冻结服务端沙箱 Schema 与可复算摘要。
     * @return 无返回值；CEL 版本允许任意实现或摘要不一致时测试失败
     */
    @Test
    void publishesFixedCelSandboxVersion()
    {
        WfBpmnExtension extension = extension(12L, "approva.cel-expression", "ENABLED");
        extension.setExtensionType("CEL");
        when(mapper.selectByIdForUpdate(12L)).thenReturn(extension);
        when(mapper.selectMaxVersionNo(12L)).thenReturn(1);
        when(mapper.insertVersion(any())).thenAnswer(invocation ->
        {
            WfBpmnExtensionVersion value = invocation.getArgument(0);
            value.setVersionId(32L);
            return 1;
        });

        Long versionId = service.createVersion(12L,
                new WorkflowExtensionVersionCreateRequest("CEL_EXPRESSION_V1"));

        assertThat(versionId).isEqualTo(32L);
        ArgumentCaptor<WfBpmnExtensionVersion> captor =
                ArgumentCaptor.forClass(WfBpmnExtensionVersion.class);
        verify(mapper).insertVersion(captor.capture());
        WfBpmnExtensionVersion version = captor.getValue();
        String schema = new WorkflowCelSandbox().configSchema();
        assertThat(version.getVersionNo()).isEqualTo(2);
        assertThat(version.getImplementationKey()).isEqualTo("CEL_EXPRESSION_V1");
        assertThat(version.getConfigSchema()).isEqualTo(schema);
        assertThat(version.getChecksum()).isEqualTo(WorkflowExtensionChecksum.sha256(
                "approva.cel-expression", "CEL", "2", "CEL_EXPRESSION_V1", schema));

        assertConflict(() -> service.createVersion(12L,
                new WorkflowExtensionVersionCreateRequest("SET_VARIABLE")), "固定表达式实现");
    }

    /**
     * 验证 HTTP 目录只能发布固定连接器实现，并冻结当前代码 Schema 与可复算摘要。
     * @return void，HTTP 版本允许任意实现或摘要漂移时测试失败
     */
    @Test
    void publishesFixedHttpConnectorVersion()
    {
        WfBpmnExtension extension = extension(13L, "approva.http-connector", "ENABLED");
        extension.setExtensionType("HTTP");
        when(mapper.selectByIdForUpdate(13L)).thenReturn(extension);
        when(mapper.selectMaxVersionNo(13L)).thenReturn(1);
        when(mapper.insertVersion(any())).thenAnswer(invocation ->
        {
            WfBpmnExtensionVersion value = invocation.getArgument(0);
            value.setVersionId(33L);
            return 1;
        });

        Long versionId = service.createVersion(13L,
                new WorkflowExtensionVersionCreateRequest("HTTP_CONNECTOR_V1"));

        assertThat(versionId).isEqualTo(33L);
        ArgumentCaptor<WfBpmnExtensionVersion> captor =
                ArgumentCaptor.forClass(WfBpmnExtensionVersion.class);
        verify(mapper).insertVersion(captor.capture());
        WfBpmnExtensionVersion version = captor.getValue();
        String schema = httpConnector.configSchema();
        assertThat(version.getVersionNo()).isEqualTo(2);
        assertThat(version.getImplementationKey()).isEqualTo("HTTP_CONNECTOR_V1");
        assertThat(version.getConfigSchema()).isEqualTo(schema);
        assertThat(version.getChecksum()).isEqualTo(WorkflowExtensionChecksum.sha256(
                "approva.http-connector", "HTTP", "2", "HTTP_CONNECTOR_V1", schema));

        assertConflict(() -> service.createVersion(13L,
                new WorkflowExtensionVersionCreateRequest("ARBITRARY_HTTP")), "固定连接器实现");
    }

    /**
     * 验证自定义表单字段目录只能发布服务端安装的固定多行文本实现。
     * @return void，任意组件实现可发布或版本摘要不稳定时测试失败
     */
    @Test
    void publishesFixedFormFieldVersion()
    {
        WfBpmnExtension extension = extension(14L, "approva.form.textarea", "ENABLED");
        extension.setExtensionType("FORM_FIELD");
        when(mapper.selectByIdForUpdate(14L)).thenReturn(extension);
        when(mapper.selectMaxVersionNo(14L)).thenReturn(1);
        when(mapper.insertVersion(any())).thenAnswer(invocation ->
        {
            WfBpmnExtensionVersion value = invocation.getArgument(0);
            value.setVersionId(34L);
            return 1;
        });

        Long versionId = service.createVersion(14L,
                new WorkflowExtensionVersionCreateRequest(
                        WorkflowFormFieldExtension.TEXTAREA_IMPLEMENTATION_KEY));

        assertThat(versionId).isEqualTo(34L);
        ArgumentCaptor<WfBpmnExtensionVersion> captor =
                ArgumentCaptor.forClass(WfBpmnExtensionVersion.class);
        verify(mapper).insertVersion(captor.capture());
        WfBpmnExtensionVersion version = captor.getValue();
        assertThat(version.getConfigSchema()).isEqualTo(
                WorkflowFormFieldExtension.configSchema());
        assertThat(version.getChecksum()).isEqualTo(WorkflowExtensionChecksum.sha256(
                "approva.form.textarea", "FORM_FIELD", "2",
                WorkflowFormFieldExtension.TEXTAREA_IMPLEMENTATION_KEY,
                WorkflowFormFieldExtension.configSchema()));

        assertConflict(() -> service.createVersion(14L,
                new WorkflowExtensionVersionCreateRequest("ARBITRARY_COMPONENT")),
                "服务端固定实现");
    }

    /**
     * 验证停用状态变更需锁定目录且重复命令返回 409、零更新。
     * @return 无返回值；状态机或副作用门禁漂移时测试失败
     */
    @Test
    void changesStatusOnceAndRejectsRepeatedCommand()
    {
        when(mapper.selectByIdForUpdate(11L))
                .thenReturn(extension(11L, "approva.route-marker", "ENABLED"));
        when(mapper.updateStatus(11L, "DISABLED", "7")).thenReturn(1);

        service.changeStatus(11L, false);

        verify(mapper).updateStatus(11L, "DISABLED", "7");

        when(mapper.selectByIdForUpdate(12L))
                .thenReturn(extension(12L, "approva.disabled", "DISABLED"));
        assertConflict(() -> service.changeStatus(12L, false), "已经是目标状态");
        verify(mapper, never()).updateStatus(12L, "DISABLED", "7");
    }

    /**
     * 验证已停用且无部署引用的非内置目录会在同一事务中按外键顺序删除。
     * @return 无返回值；删除顺序或条件漂移时测试失败
     */
    @Test
    void removesDisabledDeploymentFreeDirectory()
    {
        when(mapper.selectByIdForUpdate(11L))
                .thenReturn(extension(11L, "approva.removable", "DISABLED"));
        when(mapper.selectVersionIds(11L)).thenReturn(List.of(21L, 22L));
        when(artifactRepository.countExtensionVersionReferences(List.of(21L, 22L)))
                .thenReturn(0);
        when(mapper.deleteVersions(11L)).thenReturn(2);
        when(mapper.deleteExtension(11L)).thenReturn(1);

        service.removeExtension(11L);

        var order = org.mockito.Mockito.inOrder(mapper, artifactRepository);
        order.verify(mapper).selectByIdForUpdate(11L);
        order.verify(mapper).selectVersionIds(11L);
        order.verify(artifactRepository).countExtensionVersionReferences(List.of(21L, 22L));
        order.verify(mapper).deleteVersions(11L);
        order.verify(mapper).deleteExtension(11L);
    }

    /**
     * 验证启用目录、系统内置目录和已有部署快照引用的目录均零删除。
     * @return 无返回值；任一受保护目录产生删除副作用时测试失败
     */
    @Test
    void rejectsProtectedDirectoryDeletionWithZeroSideEffects()
    {
        when(mapper.selectByIdForUpdate(11L))
                .thenReturn(extension(11L, "approva.enabled", "ENABLED"));
        assertConflict(() -> service.removeExtension(11L), "先停用");

        WfBpmnExtension builtIn = extension(12L, "approva.set-variable", "DISABLED");
        builtIn.setCreateBy("system");
        when(mapper.selectByIdForUpdate(12L)).thenReturn(builtIn);
        assertConflict(() -> service.removeExtension(12L), "系统内置");

        when(mapper.selectByIdForUpdate(13L))
                .thenReturn(extension(13L, "approva.deployed", "DISABLED"));
        when(mapper.selectVersionIds(13L)).thenReturn(List.of(31L));
        when(artifactRepository.countExtensionVersionReferences(List.of(31L))).thenReturn(1);
        assertConflict(() -> service.removeExtension(13L), "部署快照引用");

        verify(mapper, never()).deleteVersions(any());
        verify(mapper, never()).deleteExtension(any());
    }

    /**
     * 验证设计选项和部署锁定都会复核代码安装状态、Schema 和版本摘要。
     * @return 无返回值；数据库篡改未被拒绝时测试失败
     */
    @Test
    void verifiesChecksumsForDesignAndDeployment()
    {
        WorkflowExtensionOptionView valid = option("approva.set-variable", 1,
                WorkflowExtensionChecksum.sha256("approva.set-variable", "JAVA", "1",
                        "SET_VARIABLE", WorkflowExtensionJsonCanonicalizer
                                .canonicalize(handler.configSchema())));
        when(mapper.selectLatestEnabledOptions("JAVA")).thenReturn(List.of(valid));
        assertThat(service.listJavaOptions()).singleElement().satisfies(option ->
                assertThat(option.configSchema()).isEqualTo(
                        WorkflowExtensionJsonCanonicalizer.canonicalize(handler.configSchema())));

        when(mapper.selectByKeyForUpdate("approva.set-variable"))
                .thenReturn(extension(1L, "approva.set-variable", "ENABLED"));
        when(mapper.selectLatestEnabledByKey("approva.set-variable")).thenReturn(valid);
        assertThat(service.lockLatestForDeployment("approva.set-variable").checksum())
                .isEqualTo(valid.checksum());

        // 模拟 MySQL JSON 回读后的键重排，语义未变时不能误报数据库篡改。
        String mysqlOrderedSchema = "{\"type\":\"object\",\"required\":[\"targetVariable\",\"value\"],"
                + "\"properties\":{\"value\":{\"type\":[\"string\",\"number\",\"boolean\"]},"
                + "\"targetVariable\":{\"type\":\"string\","
                + "\"pattern\":\"^[A-Za-z_][A-Za-z0-9_]{0,127}$\"}},"
                + "\"additionalProperties\":false}";
        WorkflowExtensionOptionView reordered = new WorkflowExtensionOptionView(
                valid.extensionId(), valid.extensionKey(), valid.extensionName(),
                valid.extensionType(), valid.versionId(), valid.versionNo(),
                valid.implementationKey(), mysqlOrderedSchema, valid.checksum());
        when(mapper.selectLatestEnabledOptions("JAVA")).thenReturn(List.of(reordered));
        assertThat(service.listJavaOptions()).hasSize(1);

        WorkflowExtensionOptionView tampered = option(
                "approva.set-variable", 1, "0".repeat(64));
        when(mapper.selectLatestEnabledOptions("JAVA")).thenReturn(List.of(tampered));
        assertConflict(service::listJavaOptions, "校验和不一致");
    }

    /**
     * 验证 CEL 设计和部署选项同时拒绝实现键、Schema 或摘要漂移。
     * @return 无返回值；任一被篡改 CEL 版本仍可选择时测试失败
     */
    @Test
    void verifiesCelImplementationSchemaAndChecksum()
    {
        String schema = new WorkflowCelSandbox().configSchema();
        String checksum = WorkflowExtensionChecksum.sha256(
                "approva.cel-expression", "CEL", "1", "CEL_EXPRESSION_V1", schema);
        WorkflowExtensionOptionView valid = new WorkflowExtensionOptionView(
                12L, "approva.cel-expression", "CEL 表达式", "CEL", 22L, 1,
                "CEL_EXPRESSION_V1", schema, checksum);
        when(mapper.selectLatestEnabledOptions("CEL")).thenReturn(List.of(valid));

        assertThat(service.listCelOptions()).containsExactly(valid);

        WorkflowExtensionOptionView driftedSchema = new WorkflowExtensionOptionView(
                12L, "approva.cel-expression", "CEL 表达式", "CEL", 22L, 1,
                "CEL_EXPRESSION_V1", "{\"type\":\"object\"}", checksum);
        when(mapper.selectLatestEnabledOptions("CEL")).thenReturn(List.of(driftedSchema));
        assertConflict(service::listCelOptions, "校验和不一致");

        WorkflowExtensionOptionView arbitraryImplementation = new WorkflowExtensionOptionView(
                12L, "approva.cel-expression", "CEL 表达式", "CEL", 22L, 1,
                "ARBITRARY_CEL", schema, checksum);
        when(mapper.selectLatestEnabledOptions("CEL")).thenReturn(List.of(arbitraryImplementation));
        assertConflict(service::listCelOptions, "实现版本不受控");
    }

    /**
     * 验证 HTTP 设计和部署选项拒绝实现键、Schema 或摘要漂移。
     * @return void，被篡改 HTTP 版本仍可选择时测试失败
     */
    @Test
    void verifiesHttpImplementationSchemaAndChecksum()
    {
        String schema = httpConnector.configSchema();
        String checksum = WorkflowExtensionChecksum.sha256(
                "approva.http-connector", "HTTP", "1", "HTTP_CONNECTOR_V1", schema);
        WorkflowExtensionOptionView valid = new WorkflowExtensionOptionView(
                13L, "approva.http-connector", "HTTP 连接器", "HTTP", 23L, 1,
                "HTTP_CONNECTOR_V1", schema, checksum);
        when(mapper.selectLatestEnabledOptions("HTTP")).thenReturn(List.of(valid));

        assertThat(service.listHttpOptions()).containsExactly(valid);

        WorkflowExtensionOptionView arbitraryImplementation = new WorkflowExtensionOptionView(
                13L, "approva.http-connector", "HTTP 连接器", "HTTP", 23L, 1,
                "ARBITRARY_HTTP", schema, checksum);
        when(mapper.selectLatestEnabledOptions("HTTP")).thenReturn(List.of(arbitraryImplementation));
        assertConflict(service::listHttpOptions, "实现版本不受控");
    }

    /**
     * 验证管理清单原样包含停用和尚无版本目录，不套用设计选项过滤。
     * @return 无返回值；管理页丢失目录时测试失败
     */
    @Test
    void listsAllDirectoriesForManagement()
    {
        WorkflowExtensionManagementView disabled = new WorkflowExtensionManagementView(
                12L, "approva.disabled", "已停用扩展", "JAVA", "DISABLED", null,
                null, null, null, null, new java.util.Date(0L));
        when(mapper.selectManagementList()).thenReturn(List.of(disabled));

        assertThat(service.listManagement()).containsExactly(disabled);
    }

    /**
     * 创建固定 Java 扩展目录实体。
     * @param id Long，目录主键
     * @param key String，扩展稳定键
     * @param status String，ENABLED 或 DISABLED
     * @return WfBpmnExtension，测试目录实体
     */
    private WfBpmnExtension extension(Long id, String key, String status)
    {
        WfBpmnExtension extension = new WfBpmnExtension();
        extension.setExtensionId(id);
        extension.setExtensionKey(key);
        extension.setExtensionName(key);
        extension.setExtensionType("JAVA");
        extension.setStatus(status);
        extension.setCreateBy("7");
        return extension;
    }

    /**
     * 创建设计器和部署使用的扩展最新版视图。
     * @param key String，扩展稳定键
     * @param versionNo int，版本号
     * @param checksum String，版本摘要
     * @return WorkflowExtensionOptionView，测试版本视图
     */
    private WorkflowExtensionOptionView option(String key, int versionNo, String checksum)
    {
        return new WorkflowExtensionOptionView(1L, key, "设置流程变量", "JAVA", 2L,
                versionNo, "SET_VARIABLE", handler.configSchema(), checksum);
    }

    /**
     * 断言命令返回稳定 409 业务冲突。
     * @param command Runnable，待执行命令
     * @param messagePart String，预期异常关键信息
     * @return 无返回值；异常边界不一致时测试失败
     */
    private void assertConflict(Runnable command, String messagePart)
    {
        assertThatThrownBy(command::run)
                .isInstanceOfSatisfying(ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining(messagePart);
    }
}
