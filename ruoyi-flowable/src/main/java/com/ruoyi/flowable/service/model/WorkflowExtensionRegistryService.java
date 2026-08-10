package com.ruoyi.flowable.service.model;

import java.util.List;
import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfBpmnExtension;
import com.ruoyi.flowable.domain.WfBpmnExtensionVersion;
import com.ruoyi.flowable.domain.dto.WorkflowExtensionCreateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowExtensionVersionCreateRequest;
import com.ruoyi.flowable.domain.vo.WorkflowExtensionOptionView;
import com.ruoyi.flowable.domain.vo.WorkflowExtensionManagementView;
import com.ruoyi.flowable.domain.vo.WorkflowInstalledJavaHandlerView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.extension.WorkflowExtensionChecksum;
import com.ruoyi.flowable.extension.WorkflowExtensionJsonCanonicalizer;
import com.ruoyi.flowable.extension.WorkflowCelSandbox;
import com.ruoyi.flowable.extension.WorkflowJavaExtensionHandler;
import com.ruoyi.flowable.extension.WorkflowJavaExtensionHandlerRegistry;
import com.ruoyi.flowable.extension.WorkflowHttpConnector;
import com.ruoyi.flowable.extension.WorkflowSqlConnector;
import com.ruoyi.flowable.extension.WorkflowFormFieldExtension;
import com.ruoyi.flowable.mapper.WfBpmnExtensionMapper;

/**
 * BPMN 扩展目录、不可变版本和服务端已安装处理器的领域服务。
 */
@Service
public class WorkflowExtensionRegistryService
{
    /** 当前已完成真实执行闭环的扩展类型。 */
    public static final String JAVA_TYPE = "JAVA";

    /** CEL 受控表达式扩展类型，运行时只通过 WorkflowCelSandbox 执行。 */
    public static final String CEL_TYPE = "CEL";

    /** CEL 编译器和运行时的固定实现版本键。 */
    public static final String CEL_IMPLEMENTATION_KEY = "CEL_EXPRESSION_V1";

    /** HTTP 受控连接器扩展类型。 */
    public static final String HTTP_TYPE = "HTTP";

    /** SQL 受控连接器扩展类型。 */
    public static final String SQL_TYPE = "SQL";

    /** 服务端固定渲染实现的自定义表单字段扩展类型。 */
    public static final String FORM_FIELD_TYPE = "FORM_FIELD";

    /** 可供新设计和部署选择的目录状态。 */
    private static final String ENABLED_STATUS = "ENABLED";

    /** 停用后仅保留历史部署运行能力。 */
    private static final String DISABLED_STATUS = "DISABLED";

    private final WorkflowEngineOperations engineOperations;
    private final WfBpmnExtensionMapper extensionMapper;
    private final WorkflowJavaExtensionHandlerRegistry handlerRegistry;
    private final WorkflowHttpConnector httpConnector;
    private final WorkflowSqlConnector sqlConnector;
    /** CEL 沙箱使用固定代码契约，不允许由数据库替换实现。 */
    private final WorkflowCelSandbox celSandbox = new WorkflowCelSandbox();

    /**
     * 创建扩展注册表服务。
     * @param engineOperations WorkflowEngineOperations，正式事务和身份边界
     * @param extensionMapper WfBpmnExtensionMapper，扩展目录与版本数据访问层
     * @param handlerRegistry WorkflowJavaExtensionHandlerRegistry，代码安装处理器注册表
     * @param httpConnector WorkflowHttpConnector，固定 HTTP 实现与配置 Schema
     * @param sqlConnector WorkflowSqlConnector，固定 SQL 实现与配置 Schema
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowExtensionRegistryService(WorkflowEngineOperations engineOperations,
            WfBpmnExtensionMapper extensionMapper,
            WorkflowJavaExtensionHandlerRegistry handlerRegistry,
            WorkflowHttpConnector httpConnector,
            WorkflowSqlConnector sqlConnector)
    {
        this.engineOperations = engineOperations;
        this.extensionMapper = extensionMapper;
        this.handlerRegistry = handlerRegistry;
        this.httpConnector = httpConnector;
        this.sqlConnector = sqlConnector;
    }

    /**
     * 查询设计器可选择的已启用 Java 扩展最新版，并复核代码安装状态和版本校验和。
     * @return List&lt;WorkflowExtensionOptionView&gt;，真实数据库目录选项
     */
    public List<WorkflowExtensionOptionView> listJavaOptions()
    {
        return listOptions(JAVA_TYPE);
    }

    /**
     * 查询设计器可选择的已启用 CEL 扩展最新版。
     * @return List&lt;WorkflowExtensionOptionView&gt;，真实数据库目录选项
     */
    public List<WorkflowExtensionOptionView> listCelOptions()
    {
        return listOptions(CEL_TYPE);
    }

    /**
     * 查询设计器可选择的已启用 HTTP 扩展最新版。
     * @return List&lt;WorkflowExtensionOptionView&gt;，真实数据库目录选项
     */
    public List<WorkflowExtensionOptionView> listHttpOptions()
    {
        return listOptions(HTTP_TYPE);
    }

    /**
     * 查询设计器可选择的已启用 SQL 扩展最新版。
     * @return List&lt;WorkflowExtensionOptionView&gt;，真实数据库 SQL 扩展选项
     */
    public List<WorkflowExtensionOptionView> listSqlOptions()
    {
        return listOptions(SQL_TYPE);
    }

    /**
     * 查询设计器可选择的已启用自定义表单字段最新版。
     * @return List&lt;WorkflowExtensionOptionView&gt;，真实数据库字段目录选项
     */
    public List<WorkflowExtensionOptionView> listFormFieldOptions()
    {
        return listOptions(FORM_FIELD_TYPE);
    }

    /**
     * 查询管理页所需全部扩展目录，包括停用和尚未发布版本的目录。
     * @return List&lt;WorkflowExtensionManagementView&gt;，真实数据库管理清单
     */
    public List<WorkflowExtensionManagementView> listManagement()
    {
        return engineOperations.read(() -> List.copyOf(extensionMapper.selectManagementList()));
    }

    /**
     * 查询当前服务端代码实际安装的 Java 处理器。
     * @return List&lt;WorkflowInstalledJavaHandlerView&gt;，不可由数据库伪造的安装清单
     */
    public List<WorkflowInstalledJavaHandlerView> listInstalledJavaHandlers()
    {
        return handlerRegistry.list().stream()
                .map(handler -> new WorkflowInstalledJavaHandlerView(
                        handler.implementationKey(), handler.name(),
                        WorkflowExtensionJsonCanonicalizer.canonicalize(handler.configSchema())))
                .toList();
    }

    /**
     * 创建尚无版本的受控扩展目录。
     * @param request WorkflowExtensionCreateRequest，稳定键、名称、类型和说明
     * @return Long，数据库生成的扩展目录主键
     */
    public Long createExtension(WorkflowExtensionCreateRequest request)
    {
        return engineOperations.writeAsCurrentUser(identity ->
        {
            String extensionKey = request.extensionKey().trim();
            if (extensionMapper.selectByKey(extensionKey) != null)
            {
                throw new ServiceException("扩展标识已存在", HttpStatus.CONFLICT);
            }
            WfBpmnExtension extension = new WfBpmnExtension();
            extension.setExtensionKey(extensionKey);
            extension.setExtensionName(request.extensionName().trim());
            extension.setExtensionType(request.extensionType());
            extension.setStatus(ENABLED_STATUS);
            extension.setRemark(trimToNull(request.description()));
            extension.setCreateBy(identity.userId());
            if (extensionMapper.insertExtension(extension) != 1 || extension.getExtensionId() == null)
            {
                throw new ServiceException("扩展目录保存结果不完整", HttpStatus.ERROR);
            }
            return extension.getExtensionId();
        });
    }

    /**
     * 为扩展发布只增不改的新版本，配置 Schema 始终取自服务端已安装处理器。
     * @param extensionId Long，扩展目录主键
     * @param request WorkflowExtensionVersionCreateRequest，已安装处理器稳定键
     * @return Long，数据库生成的不可变版本主键
     */
    public Long createVersion(Long extensionId, WorkflowExtensionVersionCreateRequest request)
    {
        if (extensionId == null || extensionId <= 0)
        {
            throw new ServiceException("扩展主键不合法", HttpStatus.BAD_REQUEST);
        }
        return engineOperations.writeAsCurrentUser(identity ->
        {
            WfBpmnExtension extension = extensionMapper.selectByIdForUpdate(extensionId);
            if (extension == null)
            {
                throw new ServiceException("扩展目录不存在", HttpStatus.NOT_FOUND);
            }
            if (!JAVA_TYPE.equals(extension.getExtensionType())
                    && !CEL_TYPE.equals(extension.getExtensionType())
                    && !HTTP_TYPE.equals(extension.getExtensionType())
                    && !SQL_TYPE.equals(extension.getExtensionType())
                    && !FORM_FIELD_TYPE.equals(extension.getExtensionType()))
            {
                throw new ServiceException("当前扩展类型尚未建立执行闭环", HttpStatus.CONFLICT);
            }
            Integer currentMax = extensionMapper.selectMaxVersionNo(extensionId);
            int versionNo = Math.addExact(currentMax == null ? 0 : currentMax, 1);
            String implementationKey;
            String configSchema;
            if (JAVA_TYPE.equals(extension.getExtensionType()))
            {
                WorkflowJavaExtensionHandler handler = handlerRegistry.require(request.implementationKey());
                implementationKey = handler.implementationKey();
                configSchema = WorkflowExtensionJsonCanonicalizer.canonicalize(handler.configSchema());
            }
            else if (CEL_TYPE.equals(extension.getExtensionType()))
            {
                if (!CEL_IMPLEMENTATION_KEY.equals(request.implementationKey()))
                {
                    throw new ServiceException("CEL 只能使用服务端固定表达式实现", HttpStatus.CONFLICT);
                }
                implementationKey = CEL_IMPLEMENTATION_KEY;
                configSchema = celSandbox.configSchema();
            }
            else if (HTTP_TYPE.equals(extension.getExtensionType()))
            {
                if (!WorkflowHttpConnector.IMPLEMENTATION_KEY.equals(request.implementationKey()))
                {
                    throw new ServiceException("HTTP 只能使用服务端固定连接器实现", HttpStatus.CONFLICT);
                }
                implementationKey = WorkflowHttpConnector.IMPLEMENTATION_KEY;
                configSchema = httpConnector.configSchema();
            }
            else if (SQL_TYPE.equals(extension.getExtensionType()))
            {
                if (!WorkflowSqlConnector.IMPLEMENTATION_KEY.equals(request.implementationKey()))
                {
                    throw new ServiceException("SQL 只能使用服务端固定连接器实现", HttpStatus.CONFLICT);
                }
                implementationKey = WorkflowSqlConnector.IMPLEMENTATION_KEY;
                configSchema = sqlConnector.configSchema();
            }
            else
            {
                if (!WorkflowFormFieldExtension.TEXTAREA_IMPLEMENTATION_KEY
                        .equals(request.implementationKey()))
                {
                    throw new ServiceException("自定义表单字段只能使用服务端固定实现",
                            HttpStatus.CONFLICT);
                }
                implementationKey = WorkflowFormFieldExtension.TEXTAREA_IMPLEMENTATION_KEY;
                configSchema = WorkflowFormFieldExtension.configSchema();
            }
            String checksum = versionChecksum(extension.getExtensionKey(), extension.getExtensionType(),
                    versionNo, implementationKey, configSchema);

            WfBpmnExtensionVersion version = new WfBpmnExtensionVersion();
            version.setExtensionId(extensionId);
            version.setVersionNo(versionNo);
            version.setImplementationKey(implementationKey);
            version.setConfigSchema(configSchema);
            version.setChecksum(checksum);
            version.setCreateBy(identity.userId());
            if (extensionMapper.insertVersion(version) != 1 || version.getVersionId() == null)
            {
                throw new ServiceException("扩展版本保存结果不完整", HttpStatus.ERROR);
            }
            return version.getVersionId();
        });
    }

    /**
     * 启用或停用扩展目录，只影响后续设计和部署，不修改历史快照。
     * @param extensionId Long，扩展目录主键
     * @param enabled boolean，是否允许后续选择和部署
     * @return void，无返回值
     */
    public void changeStatus(Long extensionId, boolean enabled)
    {
        if (extensionId == null || extensionId <= 0)
        {
            throw new ServiceException("扩展主键不合法", HttpStatus.BAD_REQUEST);
        }
        engineOperations.writeAsCurrentUser(identity ->
        {
            WfBpmnExtension extension = extensionMapper.selectByIdForUpdate(extensionId);
            if (extension == null)
            {
                throw new ServiceException("扩展目录不存在", HttpStatus.NOT_FOUND);
            }
            String target = enabled ? ENABLED_STATUS : DISABLED_STATUS;
            if (target.equals(extension.getStatus()))
            {
                throw new ServiceException("扩展目录已经是目标状态", HttpStatus.CONFLICT);
            }
            if (extensionMapper.updateStatus(extensionId, target, identity.userId()) != 1)
            {
                throw new ServiceException("扩展目录状态已发生变化", HttpStatus.CONFLICT);
            }
            return null;
        });
    }

    /**
     * 删除已停用且从未固化到部署快照的非内置目录及其不可变版本。
     * @param extensionId Long，扩展目录主键
     * @return void，无返回值
     */
    public void removeExtension(Long extensionId)
    {
        if (extensionId == null || extensionId <= 0)
        {
            throw new ServiceException("扩展主键不合法", HttpStatus.BAD_REQUEST);
        }
        engineOperations.writeAsCurrentUser(identity ->
        {
            // 锁定目录后再检查状态和部署引用，防止删除与发布、启停或部署并发穿透。
            WfBpmnExtension extension = extensionMapper.selectByIdForUpdate(extensionId);
            if (extension == null)
            {
                throw new ServiceException("扩展目录不存在", HttpStatus.NOT_FOUND);
            }
            if ("system".equals(extension.getCreateBy()))
            {
                throw new ServiceException("系统内置扩展目录不能删除", HttpStatus.CONFLICT);
            }
            if (!DISABLED_STATUS.equals(extension.getStatus()))
            {
                throw new ServiceException("请先停用扩展目录再删除", HttpStatus.CONFLICT);
            }
            int deploymentSnapshotCount = extensionMapper.countDeploymentSnapshots(extensionId);
            if (deploymentSnapshotCount > 0)
            {
                throw new ServiceException("扩展目录已被部署快照引用，不能删除", HttpStatus.CONFLICT);
            }

            // 无部署引用时才允许按外键顺序删除版本与目录，事务失败会整体回滚。
            extensionMapper.deleteVersions(extensionId);
            if (extensionMapper.deleteExtension(extensionId) != 1)
            {
                throw new ServiceException("扩展目录删除状态已发生变化", HttpStatus.CONFLICT);
            }
            return null;
        });
    }

    /**
     * 部署事务内锁定扩展目录并返回已启用最新版。
     * @param extensionKey String，作者 BPMN 引用的稳定键
     * @return WorkflowExtensionOptionView，可冻结到部署快照的最新版
     */
    public WorkflowExtensionOptionView lockLatestForDeployment(String extensionKey)
    {
        WfBpmnExtension extension = extensionMapper.selectByKeyForUpdate(extensionKey);
        if (extension == null || !ENABLED_STATUS.equals(extension.getStatus()))
        {
            throw new ServiceException("服务任务引用的扩展不存在或已停用", HttpStatus.CONFLICT);
        }
        WorkflowExtensionOptionView option = extensionMapper.selectLatestEnabledByKey(extensionKey);
        if (option == null)
        {
            throw new ServiceException("服务任务引用的扩展不存在、已停用或尚未发布版本",
                    HttpStatus.CONFLICT);
        }
        return requireValidOption(option);
    }

    /**
     * 查询作者设计阶段引用的启用扩展最新版，不持有部署锁。
     * @param extensionKey String，作者 BPMN 引用的稳定扩展键
     * @param expectedType String，调用场景要求的扩展类型
     * @return WorkflowExtensionOptionView，通过实现、Schema 和校验和复核的最新版
     */
    public WorkflowExtensionOptionView requireLatest(String extensionKey, String expectedType)
    {
        if (extensionKey == null || extensionKey.isBlank())
        {
            throw new ServiceException("扩展标识不能为空", HttpStatus.BAD_REQUEST);
        }
        WorkflowExtensionOptionView option = engineOperations.read(() ->
                extensionMapper.selectLatestEnabledByKey(extensionKey.trim()));
        if (option == null)
        {
            throw new ServiceException("扩展不存在、已停用或尚未发布版本", HttpStatus.CONFLICT);
        }
        WorkflowExtensionOptionView validated = requireValidOption(option);
        if (!expectedType.equals(validated.extensionType()))
        {
            throw new ServiceException("扩展类型与使用位置不匹配", HttpStatus.CONFLICT);
        }
        return validated;
    }

    /**
     * 复核版本字段、服务端安装状态和摘要，数据库被越权修改时拒绝设计或部署。
     * @param option WorkflowExtensionOptionView，数据库读取的扩展最新版
     * @return WorkflowExtensionOptionView，复核通过的原视图
     */
    private WorkflowExtensionOptionView requireValidOption(WorkflowExtensionOptionView option)
    {
        if (option == null || (!JAVA_TYPE.equals(option.extensionType())
                && !CEL_TYPE.equals(option.extensionType())
                && !HTTP_TYPE.equals(option.extensionType())
                && !SQL_TYPE.equals(option.extensionType())
                && !FORM_FIELD_TYPE.equals(option.extensionType()))
                || option.versionNo() == null || option.versionNo() <= 0)
        {
            throw new ServiceException("扩展版本数据不完整", HttpStatus.ERROR);
        }
        String installedSchema;
        if (JAVA_TYPE.equals(option.extensionType()))
        {
            WorkflowJavaExtensionHandler handler = handlerRegistry.require(option.implementationKey());
            installedSchema = WorkflowExtensionJsonCanonicalizer.canonicalize(handler.configSchema());
        }
        else if (CEL_TYPE.equals(option.extensionType()))
        {
            if (!CEL_IMPLEMENTATION_KEY.equals(option.implementationKey()))
            {
                throw new ServiceException("CEL 扩展实现版本不受控", HttpStatus.CONFLICT);
            }
            installedSchema = celSandbox.configSchema();
        }
        else if (HTTP_TYPE.equals(option.extensionType()))
        {
            if (!WorkflowHttpConnector.IMPLEMENTATION_KEY.equals(option.implementationKey()))
            {
                throw new ServiceException("HTTP 扩展实现版本不受控", HttpStatus.CONFLICT);
            }
            installedSchema = httpConnector.configSchema();
        }
        else if (SQL_TYPE.equals(option.extensionType()))
        {
            if (!WorkflowSqlConnector.IMPLEMENTATION_KEY.equals(option.implementationKey()))
            {
                throw new ServiceException("SQL 扩展实现版本不受控", HttpStatus.CONFLICT);
            }
            installedSchema = sqlConnector.configSchema();
        }
        else
        {
            WorkflowFormFieldExtension.requireInstalled(option);
            installedSchema = WorkflowFormFieldExtension.configSchema();
        }
        String storedSchema = WorkflowExtensionJsonCanonicalizer
                .canonicalize(option.configSchema());
        if (!installedSchema.equals(storedSchema)
                || !versionChecksum(option.extensionKey(), option.extensionType(), option.versionNo(),
                        option.implementationKey(), storedSchema).equals(option.checksum()))
        {
            throw new ServiceException("扩展版本校验和不一致", HttpStatus.CONFLICT);
        }
        return new WorkflowExtensionOptionView(option.extensionId(), option.extensionKey(),
                option.extensionName(), option.extensionType(), option.versionId(),
                option.versionNo(), option.implementationKey(), storedSchema, option.checksum());
    }

    /**
     * 计算扩展不可变版本的稳定摘要。
     * @param extensionKey String，扩展稳定键
     * @param extensionType String，扩展类型
     * @param versionNo int，版本号
     * @param implementationKey String，已安装处理器键
     * @param configSchema String，服务端固定配置 Schema
     * @return String，版本定义 SHA-256
     */
    private String versionChecksum(String extensionKey, String extensionType, int versionNo,
            String implementationKey, String configSchema)
    {
        return WorkflowExtensionChecksum.sha256(extensionKey, extensionType,
                Integer.toString(versionNo), implementationKey, configSchema);
    }

    /**
     * 去除可选说明首尾空白并把空串转换为空值。
     * @param value String，可选文本
     * @return String，规范文本或 null
     */
    private String trimToNull(String value)
    {
        if (value == null || value.isBlank())
        {
            return null;
        }
        return value.trim();
    }

    /**
     * 查询指定扩展类型并复核代码实现、Schema 和版本摘要。
     * @param extensionType String，JAVA 或 CEL 扩展类型
     * @return List&lt;WorkflowExtensionOptionView&gt;，校验通过的最新版列表
     */
    private List<WorkflowExtensionOptionView> listOptions(String extensionType)
    {
        return engineOperations.read(() -> extensionMapper.selectLatestEnabledOptions(extensionType)
                .stream().map(this::requireValidOption).toList());
    }
}
