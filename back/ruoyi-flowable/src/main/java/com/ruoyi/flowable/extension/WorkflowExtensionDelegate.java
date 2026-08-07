package com.ruoyi.flowable.extension;

import org.flowable.engine.RepositoryService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;
import com.ruoyi.flowable.mapper.WfDeployExtensionSnapshotMapper;
import com.ruoyi.flowable.service.model.WorkflowExtensionDeploymentService;
import com.ruoyi.flowable.service.model.WorkflowExtensionRegistryService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Flowable 受控 Java 扩展唯一运行入口。
 */
@Component("workflowExtensionDelegate")
public class WorkflowExtensionDelegate implements JavaDelegate
{
    private final RepositoryService repositoryService;
    private final WfDeployExtensionSnapshotMapper snapshotMapper;
    private final WorkflowJavaExtensionHandlerRegistry handlerRegistry;
    private final WorkflowHttpConnector httpConnector;
    private final WorkflowSqlConnector sqlConnector;
    private final ObjectMapper objectMapper = JsonMapper.shared();
    /** CEL 运行时不从 Spring 或数据库加载任意函数，只使用固定沙箱。 */
    private final WorkflowCelSandbox celSandbox = new WorkflowCelSandbox();

    /**
     * 创建固定扩展调度器。
     * @param repositoryService RepositoryService，流程定义到部署主键的官方查询 API
     * @param snapshotMapper WfDeployExtensionSnapshotMapper，运行快照数据访问层
     * @param handlerRegistry WorkflowJavaExtensionHandlerRegistry，服务端安装处理器注册表
     * @param httpConnector WorkflowHttpConnector，固定 HTTP 连接器执行器
     * @param sqlConnector WorkflowSqlConnector，固定 SQL 连接器执行器
     * @return 无返回值，构造后由 Spring 以固定 Bean 名注册
     */
    public WorkflowExtensionDelegate(RepositoryService repositoryService,
            WfDeployExtensionSnapshotMapper snapshotMapper,
            WorkflowJavaExtensionHandlerRegistry handlerRegistry,
            WorkflowHttpConnector httpConnector,
            WorkflowSqlConnector sqlConnector)
    {
        this.repositoryService = repositoryService;
        this.snapshotMapper = snapshotMapper;
        this.handlerRegistry = handlerRegistry;
        this.httpConnector = httpConnector;
        this.sqlConnector = sqlConnector;
    }

    /**
     * 按当前定义和活动读取冻结快照，复核摘要后调用唯一已安装处理器。
     * @param execution DelegateExecution，Flowable 当前 ServiceTask 执行上下文
     * @return void，无返回值；快照缺失、漂移或处理器移除时抛出并由 Flowable 记录失败
     */
    @Override
    public void execute(DelegateExecution execution)
    {
        ProcessDefinition definition = repositoryService
                .getProcessDefinition(execution.getProcessDefinitionId());
        if (definition == null || definition.getDeploymentId() == null)
        {
            throw new ServiceException("扩展执行对应的流程定义不存在", HttpStatus.ERROR);
        }
        WfDeployExtensionSnapshot snapshot = snapshotMapper.selectRuntimeSnapshot(
                definition.getDeploymentId(), definition.getKey(), execution.getCurrentActivityId());
        if (snapshot == null)
        {
            throw new ServiceException("扩展执行快照不存在", HttpStatus.ERROR);
        }
        String expectedChecksum = WorkflowExtensionDeploymentService.snapshotChecksum(snapshot);
        if (!expectedChecksum.equals(snapshot.getSnapshotChecksum()))
        {
            throw new ServiceException("扩展执行快照校验和不一致", HttpStatus.ERROR);
        }
        try
        {
            JsonNode config = objectMapper.readTree(snapshot.getConfigJson());
            String storedConfig = WorkflowExtensionJsonCanonicalizer
                    .canonicalize(snapshot.getConfigJson());
            if (WorkflowExtensionRegistryService.JAVA_TYPE.equals(snapshot.getExtensionType()))
            {
                WorkflowJavaExtensionHandler handler = handlerRegistry.require(snapshot.getImplementationKey());
                requireStableVersion(snapshot, WorkflowExtensionJsonCanonicalizer
                        .canonicalize(handler.configSchema()));
                String normalized = WorkflowExtensionJsonCanonicalizer.canonicalize(
                        handler.validateAndNormalizeConfig(config));
                requireStableConfig(normalized, storedConfig);
                handler.execute(execution, config);
                return;
            }
            if (WorkflowExtensionRegistryService.CEL_TYPE.equals(snapshot.getExtensionType())
                    && WorkflowExtensionRegistryService.CEL_IMPLEMENTATION_KEY
                            .equals(snapshot.getImplementationKey()))
            {
                requireStableVersion(snapshot, celSandbox.configSchema());
                requireStableConfig(celSandbox.validateAndNormalizeConfig(config), storedConfig);
                celSandbox.execute(execution, config);
                return;
            }
            if (WorkflowExtensionRegistryService.HTTP_TYPE.equals(snapshot.getExtensionType())
                    && WorkflowHttpConnector.IMPLEMENTATION_KEY.equals(snapshot.getImplementationKey()))
            {
                requireStableVersion(snapshot, httpConnector.configSchema());
                requireStableConfig(httpConnector.validateFrozenConfig(config), storedConfig);
                httpConnector.execute(execution, snapshot, config);
                return;
            }
            if (WorkflowExtensionRegistryService.SQL_TYPE.equals(snapshot.getExtensionType())
                    && WorkflowSqlConnector.IMPLEMENTATION_KEY.equals(snapshot.getImplementationKey()))
            {
                requireStableVersion(snapshot, sqlConnector.configSchema());
                requireStableConfig(sqlConnector.validateFrozenConfig(config), storedConfig);
                sqlConnector.execute(execution, snapshot, config);
                return;
            }
            throw new ServiceException("扩展快照类型或实现不可执行", HttpStatus.ERROR);
        }
        catch (JacksonException exception)
        {
            throw new ServiceException("扩展执行快照配置无法解析", HttpStatus.ERROR);
        }
    }

    /**
     * 复核部署时冻结的版本摘要仍与当前固定实现和 Schema 完全一致。
     * @param snapshot WfDeployExtensionSnapshot，当前活动的不可变部署快照
     * @param installedSchema String，当前代码安装实现提供的规范配置 Schema
     * @return void，实现键、版本号或 Schema 漂移时阻止任何业务执行
     */
    private void requireStableVersion(WfDeployExtensionSnapshot snapshot, String installedSchema)
    {
        String expected = WorkflowExtensionChecksum.sha256(
                snapshot.getExtensionKey(), snapshot.getExtensionType(),
                String.valueOf(snapshot.getVersionNo()), snapshot.getImplementationKey(),
                installedSchema);
        if (!expected.equals(snapshot.getVersionChecksum()))
        {
            throw new ServiceException("扩展执行版本校验和不一致", HttpStatus.ERROR);
        }
    }

    /**
     * 复核运行时重新规范化的配置与冻结快照完全一致。
     * @param normalized String，当前代码重新规范化结果
     * @param storedConfig String，部署快照中的规范配置
     * @return void，不一致时阻止运行
     */
    private void requireStableConfig(String normalized, String storedConfig)
    {
        if (!normalized.equals(storedConfig))
        {
            throw new ServiceException("扩展执行配置规范化结果已漂移", HttpStatus.ERROR);
        }
    }
}
