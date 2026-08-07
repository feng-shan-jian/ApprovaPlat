package com.ruoyi.flowable.service.model;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.Model;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployDmnSnapshot;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;
import com.ruoyi.flowable.domain.dto.WorkflowDeploymentQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowDeploymentView;
import com.ruoyi.flowable.domain.vo.WorkflowPageResult;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.mapper.WfDeployDmnSnapshotMapper;
import com.ruoyi.flowable.mapper.WfDeployFormMapper;
import com.ruoyi.flowable.mapper.WfDeployExtensionSnapshotMapper;

/**
 * 流程定义状态、部署版本和非级联删除的业务服务。
 */
@Service
public class WorkflowDeploymentService
{
    /** 单页允许返回的最大记录数。 */
    static final int MAX_PAGE_SIZE = 200;

    /** 部署 BPMN 读取上限与模型保存上限保持一致。 */
    private static final int MAX_BPMN_BYTES = 2 * 1024 * 1024;

    private final WorkflowEngineOperations engineOperations;

    private final RepositoryService repositoryService;

    private final RuntimeService runtimeService;

    private final HistoryService historyService;

    private final WfDeployFormMapper deployFormMapper;

    private final WfDeployExtensionSnapshotMapper deployExtensionSnapshotMapper;

    private final WfDeployDmnSnapshotMapper deployDmnSnapshotMapper;

    private final WorkflowDmnDecisionService dmnDecisionService;

    private final WorkflowBpmnService bpmnService;

    /** 删除部署前保护被调用活动精确引用的流程定义；旧构造测试可为空。 */
    private final WorkflowCallActivityReferenceService callActivityReferenceService;

    /**
     * 创建流程部署服务。
     *
     * @param engineOperations WorkflowEngineOperations，统一事务、身份和异常边界
     * @param repositoryService RepositoryService，Flowable 8 仓储公共 API
     * @param runtimeService RuntimeService，运行实例公共 API
     * @param historyService HistoryService，历史实例公共 API
     * @param deployFormMapper WfDeployFormMapper，部署表单快照数据访问层
     * @param deployExtensionSnapshotMapper WfDeployExtensionSnapshotMapper，部署扩展快照数据访问层
     * @param deployDmnSnapshotMapper WfDeployDmnSnapshotMapper，部署 DMN 快照数据访问层
     * @param dmnDecisionService WorkflowDmnDecisionService，冻结 DMN 子部署清理服务
     * @param bpmnService WorkflowBpmnService，BPMN 安全解析和校验组件
     * @param callActivityReferenceService WorkflowCallActivityReferenceService，调用活动目标删除保护服务
     * @return 无返回值，构造后由 Spring 管理该服务
     */
    @Autowired
    public WorkflowDeploymentService(WorkflowEngineOperations engineOperations,
            RepositoryService repositoryService, RuntimeService runtimeService,
            HistoryService historyService, WfDeployFormMapper deployFormMapper,
            WfDeployExtensionSnapshotMapper deployExtensionSnapshotMapper,
            WfDeployDmnSnapshotMapper deployDmnSnapshotMapper,
            WorkflowDmnDecisionService dmnDecisionService,
            WorkflowBpmnService bpmnService,
            WorkflowCallActivityReferenceService callActivityReferenceService)
    {
        this.engineOperations = engineOperations;
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        this.deployFormMapper = deployFormMapper;
        this.deployExtensionSnapshotMapper = deployExtensionSnapshotMapper;
        this.deployDmnSnapshotMapper = deployDmnSnapshotMapper;
        this.dmnDecisionService = dmnDecisionService;
        this.bpmnService = bpmnService;
        this.callActivityReferenceService = callActivityReferenceService;
    }

    /**
     * 兼容既有不涉及 CallActivity 删除保护的纯单元测试构造方式。
     *
     * @param engineOperations WorkflowEngineOperations，统一事务、身份和异常边界
     * @param repositoryService RepositoryService，Flowable 仓储 API
     * @param runtimeService RuntimeService，运行实例 API
     * @param historyService HistoryService，历史实例 API
     * @param deployFormMapper WfDeployFormMapper，部署表单快照 Mapper
     * @param deployExtensionSnapshotMapper WfDeployExtensionSnapshotMapper，部署扩展快照 Mapper
     * @param deployDmnSnapshotMapper WfDeployDmnSnapshotMapper，部署 DMN 快照 Mapper
     * @param dmnDecisionService WorkflowDmnDecisionService，冻结 DMN 清理服务
     * @param bpmnService WorkflowBpmnService，BPMN 安全读取服务
     * @return 无返回值，仅为既有测试保留
     */
    public WorkflowDeploymentService(WorkflowEngineOperations engineOperations,
            RepositoryService repositoryService, RuntimeService runtimeService,
            HistoryService historyService, WfDeployFormMapper deployFormMapper,
            WfDeployExtensionSnapshotMapper deployExtensionSnapshotMapper,
            WfDeployDmnSnapshotMapper deployDmnSnapshotMapper,
            WorkflowDmnDecisionService dmnDecisionService,
            WorkflowBpmnService bpmnService)
    {
        this(engineOperations, repositoryService, runtimeService, historyService,
                deployFormMapper, deployExtensionSnapshotMapper, deployDmnSnapshotMapper,
                dmnDecisionService, bpmnService, null);
    }

    /**
     * 查询每个流程 key 的最新定义和所属部署信息。
     *
     * @param filter WorkflowDeploymentQueryDto，流程 key、名称、分类和状态条件，允许为空
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数
     * @return WorkflowPageResult&lt;WorkflowDeploymentView&gt;，最新流程定义分页结果
     */
    public WorkflowPageResult<WorkflowDeploymentView> listLatest(
            WorkflowDeploymentQueryDto filter, int pageNum, int pageSize)
    {
        PageWindow page = requirePage(pageNum, pageSize);
        return engineOperations.read(() ->
        {
            ProcessDefinitionQuery query = buildDefinitionQuery(filter)
                    .latestVersion()
                    .orderByProcessDefinitionKey()
                    .asc();
            long total = query.count();
            if (total == 0)
            {
                return new WorkflowPageResult<>(List.of(), 0);
            }
            List<WorkflowDeploymentView> rows = query.listPage(page.offset(), page.pageSize()).stream()
                    .map(this::toView)
                    .toList();
            return new WorkflowPageResult<>(rows, total);
        });
    }

    /**
     * 查询指定流程 key 的全部已发布定义版本。
     *
     * @param processKey String，流程定义 key
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数
     * @return WorkflowPageResult&lt;WorkflowDeploymentView&gt;，按版本倒序的发布记录
     */
    public WorkflowPageResult<WorkflowDeploymentView> publishList(String processKey,
            int pageNum, int pageSize)
    {
        String normalizedKey = requireText(processKey, "流程标识不能为空");
        PageWindow page = requirePage(pageNum, pageSize);
        return engineOperations.read(() ->
        {
            ProcessDefinitionQuery query = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey(normalizedKey)
                    .orderByProcessDefinitionVersion()
                    .desc();
            long total = query.count();
            if (total == 0)
            {
                return new WorkflowPageResult<>(List.of(), 0);
            }
            List<WorkflowDeploymentView> rows = query.listPage(page.offset(), page.pageSize()).stream()
                    .map(this::toView)
                    .toList();
            return new WorkflowPageResult<>(rows, total);
        });
    }

    /**
     * 将流程定义切换到请求状态；相同状态视为冲突，避免重复命令产生假成功。
     *
     * @param definitionId String，Flowable 流程定义主键
     * @param stateCode String，active 或 suspended
     * @return 无返回值
     */
    public void changeState(String definitionId, String stateCode)
    {
        changeState(definitionId, WorkflowDefinitionState.fromCode(stateCode));
    }

    /**
     * 将流程定义切换到受控目标状态，并同步其运行实例状态。
     *
     * @param definitionId String，Flowable 流程定义主键
     * @param targetState WorkflowDefinitionState，受控目标状态
     * @return 无返回值
     */
    public void changeState(String definitionId, WorkflowDefinitionState targetState)
    {
        String normalizedId = requireText(definitionId, "流程定义主键不能为空");
        if (targetState == null)
        {
            throw new ServiceException("流程定义状态不能为空", HttpStatus.BAD_REQUEST);
        }
        engineOperations.writeAsCurrentUser(identity ->
        {
            ProcessDefinition definition = requireDefinition(normalizedId);
            if (targetState == WorkflowDefinitionState.ACTIVE)
            {
                if (!definition.isSuspended())
                {
                    throw new ServiceException("流程定义已经是激活状态", HttpStatus.CONFLICT);
                }
                repositoryService.activateProcessDefinitionById(normalizedId, true, null);
            }
            else
            {
                if (definition.isSuspended())
                {
                    throw new ServiceException("流程定义已经是挂起状态", HttpStatus.CONFLICT);
                }
                repositoryService.suspendProcessDefinitionById(normalizedId, true, null);
            }
            return null;
        });
    }

    /**
     * 读取并重新安全校验已部署流程定义的 BPMN XML。
     *
     * @param definitionId String，Flowable 流程定义主键
     * @return String，UTF-8 BPMN XML
     */
    public String getBpmnXml(String definitionId)
    {
        String normalizedId = requireText(definitionId, "流程定义主键不能为空");
        return engineOperations.read(() ->
        {
            requireDefinition(normalizedId);
            try (InputStream stream = repositoryService.getProcessModel(normalizedId))
            {
                if (stream == null)
                {
                    throw new ServiceException("流程定义 BPMN 不存在", HttpStatus.NOT_FOUND);
                }
                byte[] bytes = readBounded(stream);
                return bpmnService.validateCompiledDeployment(bytes).bpmnXml();
            }
            catch (IOException exception)
            {
                ServiceException failure = new ServiceException("流程定义 BPMN 读取失败", HttpStatus.ERROR);
                failure.initCause(exception);
                throw failure;
            }
        });
    }

    /**
     * 非级联删除没有运行或历史实例的部署，并同步清理其自有表单快照和模型关联。
     *
     * @param deploymentIds Collection&lt;String&gt;，待删除 Flowable 部署主键集合
     * @return 无返回值
     */
    public void deleteDeployments(Collection<String> deploymentIds)
    {
        List<String> normalizedIds = requireIds(deploymentIds, "部署主键不能为空");
        engineOperations.writeAsCurrentUser(identity ->
        {
            if (callActivityReferenceService != null)
            {
                // 全部部署先统一预检，避免逐个删除后才发现剩余父流程仍引用目标定义。
                callActivityReferenceService.assertDeploymentsNotReferenced(normalizedIds);
            }
            List<DeploymentDeletionPlan> plans = new ArrayList<>(normalizedIds.size());
            for (String deploymentId : normalizedIds)
            {
                Deployment deployment = requireDeployment(deploymentId);
                assertNoInstanceReferences(deploymentId);
                List<WfDeployForm> snapshots = safeSnapshots(deploymentId);
                List<WfDeployExtensionSnapshot> extensionSnapshots =
                        safeExtensionSnapshots(deploymentId);
                List<WfDeployDmnSnapshot> dmnSnapshots = safeDmnSnapshots(deploymentId);
                List<Model> linkedModels = repositoryService.createModelQuery()
                        .deploymentId(deploymentId)
                        .list();
                plans.add(new DeploymentDeletionPlan(
                        deployment, snapshots, extensionSnapshots, dmnSnapshots, linkedModels));
            }

            for (DeploymentDeletionPlan plan : plans)
            {
                String deploymentId = plan.deployment().getId();
                // 在真正写入前二次检查，缩小预检与删除之间的并发窗口。
                assertNoInstanceReferences(deploymentId);
                int deletedSnapshots = deployFormMapper.deleteByDeploymentId(deploymentId);
                if (deletedSnapshots != plan.snapshots().size())
                {
                    throw new ServiceException("部署表单快照状态已变化", HttpStatus.CONFLICT);
                }
                int deletedExtensionSnapshots = deployExtensionSnapshotMapper
                        .deleteByDeploymentId(deploymentId);
                if (deletedExtensionSnapshots != plan.extensionSnapshots().size())
                {
                    throw new ServiceException("部署扩展快照状态已变化", HttpStatus.CONFLICT);
                }
                int deletedDmnSnapshots = deployDmnSnapshotMapper.deleteByDeploymentId(deploymentId);
                if (deletedDmnSnapshots != plan.dmnSnapshots().size())
                {
                    throw new ServiceException("部署 DMN 快照状态已变化", HttpStatus.CONFLICT);
                }
                for (Model model : plan.linkedModels())
                {
                    model.setDeploymentId(null);
                    repositoryService.saveModel(model);
                }
                try
                {
                    // 禁止 cascade=true；运行和历史数据必须由显式状态门禁保护。
                    repositoryService.deleteDeployment(deploymentId);
                    // 主部署删除成功后再删除其冻结 DMN 子部署；任一失败由统一事务整体回滚。
                    dmnDecisionService.deleteFrozenDeployments(plan.dmnSnapshots());
                }
                catch (FlowableException exception)
                {
                    ServiceException conflict = new ServiceException(
                            "流程部署状态已变化，请刷新后重试", HttpStatus.CONFLICT);
                    conflict.initCause(exception);
                    throw conflict;
                }
            }
            return null;
        });
    }

    /**
     * 构造 Flowable 原生流程定义查询并应用可选条件。
     *
     * @param filter WorkflowDeploymentQueryDto，流程定义查询条件，允许为空
     * @return ProcessDefinitionQuery，尚未执行的原生查询
     */
    private ProcessDefinitionQuery buildDefinitionQuery(WorkflowDeploymentQueryDto filter)
    {
        ProcessDefinitionQuery query = repositoryService.createProcessDefinitionQuery();
        if (filter == null)
        {
            return query;
        }
        if (hasText(filter.getProcessKey()))
        {
            query.processDefinitionKey(filter.getProcessKey().trim());
        }
        if (hasText(filter.getProcessName()))
        {
            query.processDefinitionNameLike("%" + filter.getProcessName().trim() + "%");
        }
        if (hasText(filter.getCategory()))
        {
            query.processDefinitionCategory(filter.getCategory().trim());
        }
        if (hasText(filter.getState()))
        {
            WorkflowDefinitionState state = WorkflowDefinitionState.fromCode(filter.getState());
            if (state == WorkflowDefinitionState.ACTIVE)
            {
                query.active();
            }
            else
            {
                query.suspended();
            }
        }
        return query;
    }

    /**
     * 将流程定义、部署和已固化表单快照转换为不可变视图。
     *
     * @param definition ProcessDefinition，Flowable 流程定义
     * @return WorkflowDeploymentView，供 Controller 使用的模块视图
     */
    private WorkflowDeploymentView toView(ProcessDefinition definition)
    {
        Deployment deployment = repositoryService.createDeploymentQuery()
                .deploymentId(definition.getDeploymentId())
                .singleResult();
        if (deployment == null)
        {
            throw new ServiceException("流程部署不存在或数据不完整", HttpStatus.CONFLICT);
        }
        List<WfDeployForm> snapshots = safeSnapshots(definition.getDeploymentId());
        WfDeployForm primaryForm = snapshots.isEmpty() ? null : snapshots.get(0);
        String category = hasText(definition.getCategory())
                ? definition.getCategory() : deployment.getCategory();
        return new WorkflowDeploymentView(definition.getId(), definition.getName(),
                definition.getKey(), category, definition.getVersion(),
                primaryForm == null ? null : primaryForm.getFormId(),
                primaryForm == null ? null : primaryForm.getFormName(),
                definition.getDeploymentId(), definition.isSuspended(), deployment.getDeploymentTime());
    }

    /**
     * 查询必须存在的流程定义。
     *
     * @param definitionId String，Flowable 流程定义主键
     * @return ProcessDefinition，存在的流程定义
     */
    private ProcessDefinition requireDefinition(String definitionId)
    {
        ProcessDefinition definition = repositoryService.getProcessDefinition(definitionId);
        if (definition == null)
        {
            throw new ServiceException("流程定义不存在或已被删除", HttpStatus.NOT_FOUND);
        }
        return definition;
    }

    /**
     * 查询必须存在的部署。
     *
     * @param deploymentId String，Flowable 部署主键
     * @return Deployment，存在的 Flowable 部署
     */
    private Deployment requireDeployment(String deploymentId)
    {
        Deployment deployment = repositoryService.createDeploymentQuery()
                .deploymentId(deploymentId)
                .singleResult();
        if (deployment == null)
        {
            throw new ServiceException("流程部署不存在或已被删除", HttpStatus.NOT_FOUND);
        }
        return deployment;
    }

    /**
     * 拒绝仍被运行实例或任意历史实例引用的部署。
     *
     * @param deploymentId String，Flowable 部署主键
     * @return 无返回值
     */
    private void assertNoInstanceReferences(String deploymentId)
    {
        long runtimeCount = runtimeService.createProcessInstanceQuery()
                .deploymentId(deploymentId)
                .count();
        if (runtimeCount > 0)
        {
            throw new ServiceException("部署仍有运行中的流程实例", HttpStatus.CONFLICT);
        }
        long historyCount = historyService.createHistoricProcessInstanceQuery()
                .deploymentId(deploymentId)
                .count();
        if (historyCount > 0)
        {
            throw new ServiceException("部署仍有流程历史记录", HttpStatus.CONFLICT);
        }
    }

    /**
     * 查询部署自有表单快照并规范化 Mapper 空返回。
     *
     * @param deploymentId String，Flowable 部署主键
     * @return List&lt;WfDeployForm&gt;，不可变快照列表
     */
    private List<WfDeployForm> safeSnapshots(String deploymentId)
    {
        List<WfDeployForm> snapshots = deployFormMapper.selectByDeploymentId(deploymentId);
        return snapshots == null ? List.of() : List.copyOf(snapshots);
    }

    /**
     * 查询部署自有扩展执行快照并规范化 Mapper 空返回。
     *
     * @param deploymentId String，Flowable 部署主键
     * @return List&lt;WfDeployExtensionSnapshot&gt;，不可变扩展快照列表
     */
    private List<WfDeployExtensionSnapshot> safeExtensionSnapshots(String deploymentId)
    {
        List<WfDeployExtensionSnapshot> snapshots = deployExtensionSnapshotMapper
                .selectByDeploymentId(deploymentId);
        return snapshots == null ? List.of() : List.copyOf(snapshots);
    }

    /**
     * 查询部署自有 DMN 冻结快照并规范化 Mapper 空返回。
     *
     * @param deploymentId String，Flowable 流程部署主键
     * @return List&lt;WfDeployDmnSnapshot&gt;，不可变 DMN 快照列表
     */
    private List<WfDeployDmnSnapshot> safeDmnSnapshots(String deploymentId)
    {
        List<WfDeployDmnSnapshot> snapshots = deployDmnSnapshotMapper
                .selectByDeploymentId(deploymentId);
        return snapshots == null ? List.of() : List.copyOf(snapshots);
    }

    /**
     * 有界读取部署 BPMN，防止异常资源导致内存耗尽。
     *
     * @param stream InputStream，Flowable 返回的 BPMN 资源流
     * @return byte[]，不超过 2 MiB 的 BPMN 原始字节
     * @throws IOException 输入流读取失败时抛出
     */
    private byte[] readBounded(InputStream stream) throws IOException
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = stream.read(buffer)) != -1)
        {
            total += read;
            if (total > MAX_BPMN_BYTES)
            {
                throw new ServiceException("BPMN XML 超过大小限制", HttpStatus.BAD_REQUEST);
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    /**
     * 校验页码和页大小并计算安全 offset。
     *
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数
     * @return PageWindow，经过边界和溢出校验的分页窗口
     */
    private PageWindow requirePage(int pageNum, int pageSize)
    {
        if (pageNum <= 0 || pageSize <= 0 || pageSize > MAX_PAGE_SIZE)
        {
            throw new ServiceException("分页参数不合法", HttpStatus.BAD_REQUEST);
        }
        long offset = (long) (pageNum - 1) * pageSize;
        if (offset > Integer.MAX_VALUE)
        {
            throw new ServiceException("分页偏移量过大", HttpStatus.BAD_REQUEST);
        }
        return new PageWindow((int) offset, pageSize);
    }

    /**
     * 校验并去重业务主键集合。
     *
     * @param ids Collection&lt;String&gt;，请求主键集合
     * @param message String，空集合或空主键的稳定提示
     * @return List&lt;String&gt;，保持请求顺序的规范主键集合
     */
    private List<String> requireIds(Collection<String> ids, String message)
    {
        if (ids == null || ids.isEmpty())
        {
            throw new ServiceException(message, HttpStatus.BAD_REQUEST);
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String id : ids)
        {
            normalized.add(requireText(id, message));
        }
        return List.copyOf(normalized);
    }

    /**
     * 校验文本非空并去除首尾空白。
     *
     * @param value String，待校验文本
     * @param message String，校验失败的稳定提示
     * @return String，规范化后的非空文本
     */
    private String requireText(String value, String message)
    {
        if (!hasText(value))
        {
            throw new ServiceException(message, HttpStatus.BAD_REQUEST);
        }
        return value.trim();
    }

    /**
     * 判断文本是否包含非空白字符。
     *
     * @param value String，待判断文本
     * @return boolean，true 表示文本非空白
     */
    private static boolean hasText(String value)
    {
        return value != null && !value.isBlank();
    }

    /**
     * 安全分页窗口。
     *
     * @param offset int，Flowable listPage 起始偏移
     * @param pageSize int，Flowable listPage 最大记录数
     */
    private record PageWindow(int offset, int pageSize)
    {
    }

    /**
     * 部署删除前固定的引擎对象和业务快照视图。
     *
     * @param deployment Deployment，待删除 Flowable 部署
     * @param snapshots List&lt;WfDeployForm&gt;，部署当前拥有的表单快照
     * @param extensionSnapshots List&lt;WfDeployExtensionSnapshot&gt;，部署当前拥有的扩展执行快照
     * @param dmnSnapshots List&lt;WfDeployDmnSnapshot&gt;，部署当前拥有的 DMN 冻结快照
     * @param linkedModels List&lt;Model&gt;，当前关联该部署的模型
     */
    private record DeploymentDeletionPlan(Deployment deployment, List<WfDeployForm> snapshots,
            List<WfDeployExtensionSnapshot> extensionSnapshots,
            List<WfDeployDmnSnapshot> dmnSnapshots, List<Model> linkedModels)
    {
        /**
         * 创建不可变删除计划，防止预检结果在服务代码中被修改。
         *
         * @param deployment Deployment，待删除 Flowable 部署
         * @param snapshots List&lt;WfDeployForm&gt;，部署当前拥有的表单快照
         * @param extensionSnapshots List&lt;WfDeployExtensionSnapshot&gt;，部署当前拥有的扩展执行快照
         * @param dmnSnapshots List&lt;WfDeployDmnSnapshot&gt;，部署当前拥有的 DMN 冻结快照
         * @param linkedModels List&lt;Model&gt;，当前关联该部署的模型
         * @return 无返回值，构造后得到不可变删除计划
         */
        private DeploymentDeletionPlan
        {
            snapshots = List.copyOf(snapshots);
            extensionSnapshots = List.copyOf(extensionSnapshots);
            dmnSnapshots = List.copyOf(dmnSnapshots);
            linkedModels = List.copyOf(linkedModels);
        }
    }
}
