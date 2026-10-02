package com.ruoyi.flowable.service.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.bpmn.model.BusinessRuleTask;
import org.flowable.bpmn.model.Process;
import org.flowable.dmn.api.DmnDecision;
import org.flowable.dmn.api.DmnDeployment;
import org.flowable.dmn.api.DmnRepositoryService;
import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployDmnSnapshot;
import com.ruoyi.flowable.domain.dto.WorkflowDmnDeploymentRequest;
import com.ruoyi.flowable.domain.vo.WorkflowDmnDecisionView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.extension.WorkflowExtensionChecksum;

/**
 * Flowable DMN 决策目录、BusinessRuleTask 编译、版本冻结和删除保护服务。
 */
@Service
public class WorkflowDmnDecisionService
{
    private static final int MAX_DMN_BYTES = 2 * 1024 * 1024;

    private final WorkflowEngineOperations engineOperations;
    private final DmnRepositoryService dmnRepositoryService;
    private final WorkflowDeploymentArtifactRepository artifactRepository;

    /**
     * 创建 DMN 决策领域服务。
     * 构造后由 Spring 管理
     */
    public WorkflowDmnDecisionService(WorkflowEngineOperations engineOperations,
            DmnRepositoryService dmnRepositoryService,
            WorkflowDeploymentArtifactRepository artifactRepository)
    {
        this.engineOperations = engineOperations;
        this.dmnRepositoryService = dmnRepositoryService;
        this.artifactRepository = artifactRepository;
    }

    /**
     * 查询 DMN 决策版本目录。
     * @param latestOnly boolean，true 时每个 key 只返回最新版本
     * @return List&lt;WorkflowDmnDecisionView&gt;，官方 DMN 表的稳定视图
     */
    public List<WorkflowDmnDecisionView> list(boolean latestOnly)
    {
        return engineOperations.read(() ->
        {
            List<DmnDecision> sourceDecisions = dmnRepositoryService.createDecisionQuery()
                    .orderByDecisionKey().asc().orderByDecisionVersion().desc().list().stream()
                    .filter(this::isSourceDecision)
                    .toList();
            if (!latestOnly)
            {
                return sourceDecisions.stream().map(this::toView).toList();
            }
            Map<String, DmnDecision> latestByKey = new LinkedHashMap<>();
            for (DmnDecision decision : sourceDecisions)
            {
                // 查询已按 key 升序、版本降序排列，每个 key 首行就是最新可选来源版本。
                latestByKey.putIfAbsent(decision.getKey(), decision);
            }
            return latestByKey.values().stream().map(this::toView).toList();
        });
    }

    /**
     * 部署一份受大小和 XML 安全边界保护的 DMN 资源。
     * @param request WorkflowDmnDeploymentRequest，资源名、分类和 DMN XML
     * @return String，Flowable 官方 DMN 部署主键
     */
    public String deploy(WorkflowDmnDeploymentRequest request)
    {
        if (request == null)
        {
            throw new ServiceException("DMN 部署请求不能为空", HttpStatus.BAD_REQUEST);
        }
        String resourceName = requireText(request.resourceName(), "DMN 资源名不能为空");
        if (!resourceName.toLowerCase(java.util.Locale.ROOT).endsWith(".dmn"))
        {
            throw new ServiceException("DMN 资源名必须以 .dmn 结尾", HttpStatus.BAD_REQUEST);
        }
        byte[] bytes = requireDmnBytes(request.dmnXml());
        String category = request.category() == null ? "" : request.category().trim();
        return engineOperations.writeAsCurrentUser(identity ->
        {
            DmnDeployment deployment = dmnRepositoryService.createDeployment()
                    .name(resourceName).category(category).addDmnBytes(resourceName, bytes).deploy();
            if (deployment == null || deployment.getId() == null
                    || dmnRepositoryService.createDecisionQuery()
                            .deploymentId(deployment.getId()).count() == 0)
            {
                throw new ServiceException("DMN 部署未产生可执行决策", HttpStatus.CONFLICT);
            }
            return deployment.getId();
        });
    }

    /**
     * 删除尚未被流程部署快照引用的 DMN 来源部署。
     * @param deploymentId String，Flowable DMN 部署主键
     * @return void，删除成功后官方决策和资源同时移除
     */
    public void delete(String deploymentId)
    {
        String id = requireText(deploymentId, "DMN 部署主键不能为空");
        engineOperations.writeAsCurrentUser(identity ->
        {
            DmnDeployment deployment = dmnRepositoryService.createDeploymentQuery()
                    .deploymentId(id).singleResult();
            if (deployment == null)
            {
                throw new ServiceException("DMN 部署不存在", HttpStatus.NOT_FOUND);
            }
            if (!isSourceDeployment(deployment))
            {
                throw new ServiceException("流程冻结 DMN 部署不允许独立删除", HttpStatus.CONFLICT);
            }
            if (artifactRepository.countDmnSourceReferences(id) > 0)
            {
                throw new ServiceException("DMN 部署已被流程版本冻结引用", HttpStatus.CONFLICT);
            }
            dmnRepositoryService.deleteDeployment(id);
            return null;
        });
    }

    /**
     * 保存和部署时确认 BusinessRuleTask 只引用一个已部署的精确 DMN 版本。
     * @param document WorkflowBpmnDocument，已通过 BPMN 安全解析的作者文档
     * @return void，引用缺失、任意类或不受控规则配置时抛出业务异常
     */
    public void validateReferences(WorkflowBpmnDocument document)
    {
        for (Process process : document.bpmnModel().getProcesses())
        {
            for (BusinessRuleTask task : process.findFlowElementsOfType(BusinessRuleTask.class, true))
            {
                requireSelectedDecision(task);
            }
        }
    }

    /**
     * 把 BusinessRuleTask 编译为 Flowable 原生 DMN ServiceTask，并固定所有来源资源。
     * @param model BpmnModel，本次部署独占的执行模型
     * @return List&lt;DecisionSource&gt;，精确决策来源
     */
    public List<DecisionSource> prepare(org.flowable.bpmn.model.BpmnModel model)
    {
        try
        {
            List<DecisionSource> sources = new ArrayList<>();
            for (Process process : model.getProcesses())
                for (BusinessRuleTask task : List.copyOf(process.findFlowElementsOfType(BusinessRuleTask.class, true)))
                    sources.add(compileTask(process, task));
            return List.copyOf(sources);
        }
        catch (ServiceException exception)
        {
            throw exception;
        }
        catch (Exception exception)
        {
            ServiceException failure = new ServiceException("DMN 业务规则任务编译失败", HttpStatus.BAD_REQUEST);
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * 创建绑定流程 deploymentId 的 DMN 子部署并返回不可变快照。
     * @param processDeploymentId String，新流程部署主键
     * @param sources List&lt;DecisionSource&gt;，部署前固定的决策来源
     * @param actorUserId String，可信部署操作人正式用户主键
     * @return List&lt;WfDeployDmnSnapshot&gt;，已绑定父部署和冻结决策的快照
     */
    public List<WfDeployDmnSnapshot> freezeSnapshots(String processDeploymentId,
            List<DecisionSource> sources,
            String actorUserId)
    {
        Map<String, DmnDeployment> frozenByResource = new LinkedHashMap<>();
        List<WfDeployDmnSnapshot> snapshots = new ArrayList<>();
        for (DecisionSource source : sources)
        {
            DmnDeployment frozen = frozenByResource.computeIfAbsent(source.resourceGroupKey(), key ->
                    dmnRepositoryService.createDeployment()
                            .name("流程部署 " + processDeploymentId + " 的 DMN 冻结资源")
                            .parentDeploymentId(processDeploymentId)
                            .addDmnBytes(source.resourceName(), source.resourceBytes())
                            .deploy());
            if (frozen == null || frozen.getId() == null)
            {
                throw new ServiceException("DMN 冻结部署结果不完整", HttpStatus.CONFLICT);
            }
            DmnDecision frozenDecision = dmnRepositoryService.createDecisionQuery()
                    .deploymentId(frozen.getId()).decisionKey(source.decisionKey()).singleResult();
            if (frozenDecision == null)
            {
                throw new ServiceException("DMN 冻结部署缺少目标决策", HttpStatus.CONFLICT);
            }
            snapshots.add(buildSnapshot(processDeploymentId, source, frozen,
                    frozenDecision, actorUserId));
        }
        return List.copyOf(snapshots);
    }

    /**
     * 删除流程部署拥有的全部 DMN 子部署。
     * @param snapshots List&lt;WfDeployDmnSnapshot&gt;，删除前读取的流程 DMN 快照
     * @return void，每个子部署只删除一次
     */
    public void deleteFrozenDeployments(List<WfDeployDmnSnapshot> snapshots)
    {
        Set<String> deploymentIds = new LinkedHashSet<>();
        for (WfDeployDmnSnapshot snapshot : snapshots)
        {
            deploymentIds.add(snapshot.getFrozenDeploymentId());
        }
        for (String deploymentId : deploymentIds)
        {
            // Process Engine Configurator 可能已随主部署删除子部署，因此这里必须保持幂等。
            if (hasText(deploymentId) && dmnRepositoryService.createDeploymentQuery()
                    .deploymentId(deploymentId).count() > 0)
            {
                dmnRepositoryService.deleteDeployment(deploymentId);
            }
        }
    }

    /**
     * 从官方决策表和部署表组装管理视图。
     * @param decision DmnDecision，官方精确版本
     * @return WorkflowDmnDecisionView，不暴露资源正文的目录视图
     */
    private WorkflowDmnDecisionView toView(DmnDecision decision)
    {
        DmnDeployment deployment = dmnRepositoryService.createDeploymentQuery()
                .deploymentId(decision.getDeploymentId()).singleResult();
        return new WorkflowDmnDecisionView(decision.getId(), decision.getKey(), decision.getName(),
                decision.getVersion(), decision.getCategory(), decision.getDecisionType(),
                decision.getDeploymentId(), decision.getResourceName(),
                deployment == null ? null : deployment.getDeploymentTime());
    }

    /**
     * 校验模型对象上的受控 DMN 精确引用。
     * @param task BusinessRuleTask，作者任务
     * @return DmnDecision，存在的精确版本
     */
    private DmnDecision requireSelectedDecision(BusinessRuleTask task)
    {
        if (task.getClassName() != null && !task.getClassName().isBlank()
                || task.getInputVariables() != null && !task.getInputVariables().isEmpty()
                || task.isExclude())
        {
            throw new ServiceException("业务规则任务不允许任意类或规则引擎配置", HttpStatus.BAD_REQUEST);
        }
        List<String> selected = task.getRuleNames();
        if (selected == null || selected.size() != 1 || selected.get(0) == null
                || selected.get(0).isBlank())
        {
            throw new ServiceException("业务规则任务必须选择一个精确 DMN 决策版本", HttpStatus.BAD_REQUEST);
        }
        DmnDecision decision = dmnRepositoryService.getDecision(selected.get(0).trim());
        if (decision == null)
        {
            throw new ServiceException("业务规则任务引用的 DMN 决策不存在", HttpStatus.CONFLICT);
        }
        requireSourceDecision(decision);
        return decision;
    }

    /**
     * 替换执行模型中的 BusinessRuleTask 并读取其精确 DMN 来源。
     * @param process Process，执行任务所属流程
     * @param task BusinessRuleTask，执行模型中的业务规则任务
     * @return DecisionSource，任务与精确决策资源的绑定
     * @throws IOException DMN 资源读取失败
     */
    private DecisionSource compileTask(Process process, BusinessRuleTask task) throws IOException
    {
        DmnDecision decision = requireSelectedDecision(task);
        byte[] resource;
        try (InputStream stream = dmnRepositoryService.getDmnResource(decision.getId()))
        {
            resource = readBounded(stream);
        }
        var compiled = new org.flowable.bpmn.model.ServiceTask();
        compiled.setValues(task);
        compiled.setIncomingFlows(task.getIncomingFlows());
        compiled.setOutgoingFlows(task.getOutgoingFlows());
        compiled.setResultVariableName(task.getResultVariableName());
        compiled.setType("dmn");
        compiled.getFieldExtensions().add(dmnField("decisionTableReferenceKey", decision.getKey()));
        compiled.getFieldExtensions().add(dmnField("sameDeployment", "true"));
        var container = process.findParent(task);
        container.removeFlowElement(task.getId());
        container.addFlowElement(compiled);
        // 对象引用需同步，后续编译器直接遍历本模型，不再依靠 XML 重读重建拓扑。
        compiled.getIncomingFlows().forEach(flow -> flow.setTargetFlowElement(compiled));
        compiled.getOutgoingFlows().forEach(flow -> flow.setSourceFlowElement(compiled));
        compiled.getBoundaryEvents().forEach(boundary -> boundary.setAttachedToRef(compiled));
        return new DecisionSource(process.getId(), task.getId(), decision.getId(), decision.getKey(),
                decision.getVersion(), decision.getDeploymentId(), decision.getResourceName(),
                WorkflowExtensionChecksum.sha256(new String(resource, StandardCharsets.UTF_8)), resource);
    }

    private org.flowable.bpmn.model.FieldExtension dmnField(String name, String value)
    {
        var field = new org.flowable.bpmn.model.FieldExtension();
        field.setFieldName(name);
        field.setStringValue(value);
        return field;
    }

    /**
     * 组装最终不可变 DMN 快照并计算覆盖全部关键字段的摘要。
     * @param processDeploymentId String，流程部署主键
     * @param source DecisionSource，部署前固定来源
     * @param frozen DmnDeployment，流程拥有的 DMN 子部署
     * @param frozenDecision DmnDecision，子部署中的目标决策
     * @param actorUserId String，可信部署操作人
     * @return WfDeployDmnSnapshot，可直接批量持久化的完整快照
     */
    private WfDeployDmnSnapshot buildSnapshot(String processDeploymentId, DecisionSource source,
            DmnDeployment frozen, DmnDecision frozenDecision, String actorUserId)
    {
        WfDeployDmnSnapshot snapshot = new WfDeployDmnSnapshot();
        snapshot.setDeployId(processDeploymentId);
        snapshot.setProcessKey(source.processKey());
        snapshot.setElementId(source.elementId());
        snapshot.setSourceDecisionId(source.sourceDecisionId());
        snapshot.setDecisionKey(source.decisionKey());
        snapshot.setDecisionVersion(source.decisionVersion());
        snapshot.setSourceDeploymentId(source.sourceDeploymentId());
        snapshot.setResourceName(source.resourceName());
        snapshot.setResourceChecksum(source.resourceChecksum());
        snapshot.setFrozenDeploymentId(frozen.getId());
        snapshot.setFrozenDecisionId(frozenDecision.getId());
        snapshot.setCreateBy(actorUserId);
        snapshot.setSnapshotChecksum(WorkflowExtensionChecksum.sha256(
                snapshot.getDeployId(), snapshot.getProcessKey(), snapshot.getElementId(),
                snapshot.getSourceDecisionId(), snapshot.getDecisionKey(),
                String.valueOf(snapshot.getDecisionVersion()), snapshot.getSourceDeploymentId(),
                snapshot.getResourceName(), snapshot.getResourceChecksum(),
                snapshot.getFrozenDeploymentId(), snapshot.getFrozenDecisionId()));
        return snapshot;
    }

    /**
     * 判断决策是否来自独立管理部署，而不是流程部署拥有的内部冻结副本。
     * @param decision DmnDecision，待判断的官方决策版本
     * @return boolean，来源部署没有 parentDeploymentId 时返回 true
     */
    private boolean isSourceDecision(DmnDecision decision)
    {
        DmnDeployment deployment = dmnRepositoryService.createDeploymentQuery()
                .deploymentId(decision.getDeploymentId()).singleResult();
        return deployment != null && isSourceDeployment(deployment);
    }

    /**
     * 判断 DMN 部署是否是官方根来源部署。
     * @param deployment DmnDeployment，待判断的官方部署
     * @return boolean，parentDeploymentId 为空或等于自身时返回 true
     */
    private boolean isSourceDeployment(DmnDeployment deployment)
    {
        return !hasText(deployment.getParentDeploymentId())
                || deployment.getId().equals(deployment.getParentDeploymentId());
    }

    /**
     * 拒绝设计器引用只供既有流程运行的内部冻结决策。
     * @param decision DmnDecision，设计阶段提交的精确决策版本
     * @return void，内部冻结版本或损坏部署关系会抛出业务异常
     */
    private void requireSourceDecision(DmnDecision decision)
    {
        if (!isSourceDecision(decision))
        {
            throw new ServiceException("业务规则任务不能引用流程内部冻结的 DMN 决策",
                    HttpStatus.CONFLICT);
        }
    }

    /**
     * 有界读取官方 DMN 资源流。
     * @param stream InputStream，官方仓储返回的 XML 流
     * @return byte[]，最大 2 MiB 的资源
     * @throws IOException 资源读取失败
     */
    private byte[] readBounded(InputStream stream) throws IOException
    {
        if (stream == null)
        {
            throw new ServiceException("DMN 决策资源不存在", HttpStatus.CONFLICT);
        }
        byte[] bytes = stream.readNBytes(MAX_DMN_BYTES + 1);
        if (bytes.length > MAX_DMN_BYTES)
        {
            throw new ServiceException("DMN XML 超过大小限制", HttpStatus.BAD_REQUEST);
        }
        return bytes;
    }

    /**
     * 校验外部 DMN XML 大小并阻断 DTD 和外部实体声明。
     * @param xml String，用户提交的 DMN XML
     * @return byte[]，严格 UTF-8 字节
     */
    private byte[] requireDmnBytes(String xml)
    {
        String value = requireText(xml, "DMN XML 不能为空");
        String upper = value.toUpperCase(java.util.Locale.ROOT);
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY"))
        {
            throw new ServiceException("DMN XML 禁止 DTD 或实体声明", HttpStatus.BAD_REQUEST);
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_DMN_BYTES)
        {
            throw new ServiceException("DMN XML 超过大小限制", HttpStatus.BAD_REQUEST);
        }
        return bytes;
    }

    /**
     * 校验文本非空并去除首尾空白。
     * @param value String，待校验文本
     * @param message String，稳定错误消息
     * @return String，规范化非空文本
     */
    private String requireText(String value, String message)
    {
        if (value == null || value.isBlank())
        {
            throw new ServiceException(message, HttpStatus.BAD_REQUEST);
        }
        return value.trim();
    }

    /**
     * 判断文本是否包含非空白内容。
     * @param value String，待判断文本
     * @return boolean，非空且非空白时返回 true
     */
    private boolean hasText(String value)
    {
        return value != null && !value.isBlank();
    }
    /**
     * 一个 BusinessRuleTask 的冻结输入。
     * @param processKey String，流程 key
     * @param elementId String，元素 id
     * @param sourceDecisionId String，设计选择的精确 decision id
     * @param decisionKey String，稳定决策 key
     * @param decisionVersion int，来源版本
     * @param sourceDeploymentId String，来源 DMN 部署
     * @param resourceName String，来源资源名
     * @param resourceChecksum String，来源 XML 摘要
     * @param resourceBytes byte[]，来源 XML 字节
     */
    public record DecisionSource(String processKey, String elementId,
            String sourceDecisionId, String decisionKey, int decisionVersion,
            String sourceDeploymentId, String resourceName, String resourceChecksum,
            byte[] resourceBytes)
    {
        /**
         * 防御性复制 DMN XML。
         * 构造后保存独立副本
         */
        public DecisionSource
        {
            resourceBytes = java.util.Objects.requireNonNull(resourceBytes, "DMN XML 不能为空").clone();
        }

        /** @return byte[]，DMN XML 的独立副本。 */
        @Override
        public byte[] resourceBytes() { return resourceBytes.clone(); }

        /** @return String，跨元素复用同一来源资源的稳定分组键。 */
        public String resourceGroupKey() { return sourceDeploymentId + "\u0000" + resourceName; }
    }
}
