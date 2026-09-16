package com.ruoyi.flowable.service.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;
import com.ruoyi.flowable.domain.dto.WorkflowExtensionCreateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowExtensionVersionCreateRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.extension.WorkflowJavaExtensionHandlerRegistry;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfBpmnExtensionMapper;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.DeploymentQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真实 Spring 事务、目录 Mapper 与 Flowable 持久资源；只替换当前登录身份并记录官方仓库访问。 */
class WorkflowExtensionDeletionBusinessTest
{
    private WorkflowFlowableEngineTestSupport support;
    private RepositoryService repository;
    private WorkflowDeploymentArtifactRepository artifacts;
    private WorkflowExtensionRegistryService registry;
    private WfBpmnExtensionMapper mapper;
    private final List<Integer> pageOffsets = new ArrayList<>();
    private final List<Integer> pageSizes = new ArrayList<>();
    private int listCalls;
    private int resourceReads;

    @BeforeEach
    void open() throws Exception
    {
        support = WorkflowFlowableEngineTestSupport.start("extension-deletion", Map.of());
        new ResourceDatabasePopulator(new ClassPathResource("db/h2/wf_bpmn_extension.sql"))
                .execute(support.dataSource());
        mapper = WorkflowH2SchemaMapperSupport.createSpringMapper(support.dataSource(), "extension-deletion",
                WfBpmnExtensionMapper.class, "mapper/flowable/WfBpmnExtensionMapper.xml");
        RepositoryService real = support.processEngine().getRepositoryService();
        repository = mock(RepositoryService.class, delegatesTo(real));
        doAnswer(call ->
        {
            DeploymentQuery query = real.createDeploymentQuery();
            return mock(DeploymentQuery.class, invocation ->
            {
                if (invocation.getMethod().getName().equals("listPage"))
                {
                    pageOffsets.add(invocation.getArgument(0));
                    pageSizes.add(invocation.getArgument(1));
                }
                if (invocation.getMethod().getName().equals("list")) listCalls++;
                Object result = invocation.getMethod().invoke(query, invocation.getArguments());
                return result == query ? invocation.getMock() : result;
            });
        }).when(repository).createDeploymentQuery();
        doAnswer(call ->
        {
            resourceReads++;
            return real.getResourceAsStream(call.getArgument(0), call.getArgument(1));
        }).when(repository).getResourceAsStream(anyString(), anyString());
        artifacts = new WorkflowDeploymentArtifactRepository(repository);
        var identity = mock(WorkflowIdentityResolver.class);
        when(identity.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("100", Set.of()));
        var operations = support.transactionalProxy(new WorkflowEngineOperations(
                new WorkflowAuthenticationContext(support.processEngine().getIdentityService(), new WorkflowIdentityCodec()),
                new WorkflowExceptionTranslator(), identity));
        registry = new WorkflowExtensionRegistryService(operations, mapper, artifacts,
                new WorkflowJavaExtensionHandlerRegistry(List.of()), null, null);
    }

    @AfterEach
    void close()
    {
        support.close();
    }

    @Test
    void catalogRemainsUsableWithHealthyAndBrokenHistoricalResources()
    {
        long id = create(true);
        persist(null);
        corrupt("{broken", "[]");
        clearReads();
        assertThat(registry.listManagement()).singleElement().satisfies(row ->
        {
            assertThat(row.extensionId()).isEqualTo(id);
            assertThat(row.deleteAllowed()).isFalse();
            assertThat(row.deleteCheckAllowed()).isTrue();
            assertThat(row.implementationKey()).isNull();
        });
        assertThat(resourceReads).isZero();
        assertThat(listCalls).isZero();
        assertThat(pageOffsets).isEmpty();
        assertThat(mapper.selectManagementList()).singleElement().satisfies(row -> assertThat(row.implementationKey()).isNull());
        assertThatThrownBy(() -> registry.checkDeletion(id)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> registry.removeExtension(id)).isInstanceOf(ServiceException.class);
        assertThat(mapper.selectById(id)).isNotNull();
    }

    @ParameterizedTest
    @CsvSource({"false,false,false,true,true", "true,false,false,false,true",
            "false,true,false,false,false", "true,true,false,false,false",
            "false,false,true,false,false", "true,false,true,false,false"})
    void catalogAndDeletionCapabilityMatrix(boolean versioned, boolean enabled, boolean builtin,
            boolean deleteAllowed, boolean checkAllowed)
    {
        long id = create(versioned);
        if (enabled) registry.changeStatus(id, true);
        if (builtin) support.jdbcTemplate().update("update wf_bpmn_extension set create_by='system' where extension_id=?", id);
        assertThat(registry.listManagement()).singleElement().satisfies(row ->
        {
            assertThat(row.deleteAllowed()).isEqualTo(deleteAllowed);
            assertThat(row.deleteCheckAllowed()).isEqualTo(checkAllowed);
        });
        if (checkAllowed)
        {
            assertThatCode(() -> registry.checkDeletion(id)).doesNotThrowAnyException();
            registry.removeExtension(id);
            assertThat(mapper.selectById(id)).isNull();
            assertThat(mapper.selectVersionIds(id)).isEmpty();
        }
        else
        {
            assertThatThrownBy(() -> registry.checkDeletion(id)).isInstanceOf(ServiceException.class);
            assertThatThrownBy(() -> registry.removeExtension(id)).isInstanceOf(ServiceException.class);
            assertThat(mapper.selectById(id)).isNotNull();
        }
    }

    @Test
    void versionlessDirectoryDoesNotDependOnCorruptHistory()
    {
        long id = create(false);
        corrupt("{broken", "[]");
        clearReads();
        registry.checkDeletion(id);
        registry.removeExtension(id);
        assertThat(mapper.selectById(id)).isNull();
        assertThat(resourceReads).isZero();
    }

    @Test
    void referencedVersionIsRejectedImmediatelyWithoutReadingLaterCorruptResource()
    {
        long id = create(true);
        var clock = support.processEngine().getProcessEngineConfiguration().getClock();
        clock.setCurrentTime(new java.util.Date(1000));
        persist(mapper.selectVersionIds(id).get(0));
        // “较晚”由真实部署时间确定，避免同毫秒时随机部署 ID 改变资源顺序。
        clock.setCurrentTime(new java.util.Date(2000));
        corrupt("{broken", "[]");
        clearReads();
        assertThatThrownBy(() -> registry.checkDeletion(id)).isInstanceOf(ServiceException.class)
                .hasMessageContaining("已被部署快照引用");
        assertThat(resourceReads).isEqualTo(2);
        assertThat(pageOffsets).containsExactly(0);
        assertThat(pageSizes).containsExactly(100);
    }

    @ParameterizedTest
    @ValueSource(strings = {"manifest", "unknownVersion", "missing", "null", "badJson", "missingVersionId", "nonPositiveVersionId", "missingKey"})
    void unknownOrBrokenResourcesCannotBeTreatedAsUnreferenced(String damage)
    {
        long id = create(true);
        String manifest = damage.equals("manifest") ? "{broken" : damage.equals("unknownVersion")
                ? "{\"schemaVersion\":999}" : "{\"schemaVersion\":1}";
        String extensions = switch (damage)
        {
            case "missing" -> null;
            case "null" -> "null";
            case "badJson" -> "{broken";
            case "missingVersionId" -> "[{}]";
            case "nonPositiveVersionId" -> "[{\"extensionVersionId\":0,\"extensionKey\":\"qa\"}]";
            case "missingKey" -> "[{\"extensionVersionId\":999999}]";
            default -> "[]";
        };
        corrupt(manifest, extensions);
        assertThatThrownBy(() -> registry.checkDeletion(id)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> registry.removeExtension(id)).isInstanceOf(ServiceException.class);
        assertThat(mapper.selectVersionIds(id)).hasSize(1);
        assertThat(registry.listManagement()).hasSize(1);
    }

    @Test
    void historyIsPagedOnlyWhenCheckingOneDirectory()
    {
        long id = create(true);
        support.transactionTemplate().executeWithoutResult(tx ->
        {
            for (int i = 0; i < 205; i++) persist(null);
        });
        clearReads();
        assertThat(registry.listManagement()).hasSize(1);
        assertThat(resourceReads).isZero();
        assertThat(listCalls).isZero();
        registry.checkDeletion(id);
        assertThat(pageOffsets).containsExactly(0, 100, 200);
        assertThat(pageSizes).containsExactly(100, 100, 100);
        assertThat(listCalls).isZero();
        assertThat(resourceReads).isEqualTo(410);
    }

    @ParameterizedTest
    @ValueSource(strings = {"enabled", "referenced"})
    void finalDeleteRevalidatesChangesAfterSuccessfulCheck(String change)
    {
        long id = create(true);
        registry.checkDeletion(id);
        registry.changeStatus(id, true);
        if (change.equals("referenced"))
        {
            support.transactionTemplate().executeWithoutResult(tx ->
            {
                var version = registry.lockLatestForDeployment(mapper.selectById(id).getExtensionKey());
                persist(version.versionId());
            });
            registry.changeStatus(id, false);
        }
        assertThatThrownBy(() -> registry.removeExtension(id)).isInstanceOf(ServiceException.class)
                .hasMessageContaining(change.equals("enabled") ? "请先停用" : "已被部署快照引用");
        assertThat(mapper.selectById(id)).isNotNull();
        assertThat(mapper.selectVersionIds(id)).hasSize(1);
    }

    private long create(boolean versioned)
    {
        long id = registry.createExtension(new WorkflowExtensionCreateRequest(
                "qa_" + UUID.randomUUID().toString().replace("-", ""), "测试扩展", "CEL", null));
        if (versioned) registry.createVersion(id, new WorkflowExtensionVersionCreateRequest(null));
        registry.changeStatus(id, false);
        return id;
    }

    @Test
    void matchingKeyWithForeignVersionCannotBypassDeletionProtection()
    {
        long id = create(true);
        corrupt("{\"schemaVersion\":1}", "[{\"extensionVersionId\":999999,\"extensionKey\":\""
                + mapper.selectById(id).getExtensionKey() + "\"}]");
        assertThatThrownBy(() -> registry.checkDeletion(id)).isInstanceOf(ServiceException.class)
                .hasMessageContaining("已被部署快照引用");
        assertThatThrownBy(() -> registry.removeExtension(id)).isInstanceOf(ServiceException.class);
        assertThat(mapper.selectById(id)).isNotNull();
    }

    private void persist(Long versionId)
    {
        String parent = repository.createDeployment().addString("parent.txt", "fixture").deploy().getId();
        List<WfDeployExtensionSnapshot> snapshots = new ArrayList<>();
        if (versionId != null)
        {
            var snapshot = new WfDeployExtensionSnapshot();
            snapshot.setProcessKey("process");
            snapshot.setElementId("extensionTask");
            snapshot.setExtensionVersionId(versionId);
            snapshot.setExtensionKey("qa");
            snapshots.add(snapshot);
        }
        artifacts.persist(parent, new WorkflowDeploymentArtifacts(List.of(), List.of(), List.of(), List.of(),
                snapshots, List.of(), List.of(), List.of()));
    }

    // Use public RepositoryService to reproduce imported/corrupt historical artifacts, never ACT_GE_BYTEARRAY writes.
    private void corrupt(String manifest, String extensions)
    {
        var builder = repository.createDeployment().category("APPROVAPLAT_WORKFLOW_ARTIFACTS")
                .addString("approvaplat/manifest-v1.json", manifest);
        if (extensions != null) builder.addString("approvaplat/extensions-v1.json", extensions);
        builder.deploy();
    }

    private void clearReads()
    {
        pageOffsets.clear();
        pageSizes.clear();
        resourceReads = 0;
        listCalls = 0;
    }
}
