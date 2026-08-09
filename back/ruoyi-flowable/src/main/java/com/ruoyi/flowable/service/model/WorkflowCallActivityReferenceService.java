package com.ruoyi.flowable.service.model;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.CallActivity;
import org.flowable.bpmn.model.IOParameter;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.identitylink.api.IdentityLink;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployCallActivitySnapshot;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.WfForm;
import com.ruoyi.flowable.domain.vo.WorkflowCallActivityOptionView;
import com.ruoyi.flowable.domain.vo.WorkflowCallActivityVariableView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfDeployCallActivityMapper;
import com.ruoyi.flowable.mapper.WfDeployFormMapper;
import com.ruoyi.flowable.mapper.WfFormMapper;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;

/**
 * 管理调用活动的授权目录、作者配置校验、精确版本编译和部署依赖快照。
 */
@Service
public class WorkflowCallActivityReferenceService
{
    /** Flowable 8 通过 calledElementType=id 按不可变定义主键解析调用目标。 */
    private static final String CALLED_ELEMENT_TYPE_ID = "id";

    /** 作者选择发布时最新激活版本时使用稳定流程 key。 */
    private static final String CALLED_ELEMENT_TYPE_KEY = "key";

    /** 当前平台只支持整棵父子执行树原子取消或终止。 */
    private static final String PROPAGATION_POLICY = "CASCADE_ROOT";

    /** 单个调用活动输入或输出映射上限。 */
    private static final int MAX_MAPPINGS = 64;

    /** 目录最大返回量，避免设计器一次加载无界定义与表单正文。 */
    private static final int MAX_CATALOG_DEFINITIONS = 500;

    /** 循环依赖扫描的定义上限。 */
    private static final int MAX_DEPENDENCY_SCAN = 1000;

    /** 变量名与 Flowable 表单变量白名单保持一致。 */
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    private final RepositoryService repositoryService;
    private final WorkflowEngineOperations engineOperations;
    private final WorkflowIdentityResolver identityResolver;
    private final WfDeployFormMapper deployFormMapper;
    private final WfFormMapper formMapper;
    private final WorkflowFormTemplateValidator formTemplateValidator;
    private final WfDeployCallActivityMapper snapshotMapper;
    private final ObjectMapper objectMapper = JsonMapper.shared();

    /**
     * 创建生产调用活动服务。
     *
     * @param repositoryService RepositoryService，Flowable 定义与 BPMN 公共 API
     * @param engineOperations WorkflowEngineOperations，统一事务和异常边界
     * @param identityResolver WorkflowIdentityResolver，当前用户及候选组解析器
     * @param deployFormMapper WfDeployFormMapper，子流程不可变表单快照 Mapper
     * @param formMapper WfFormMapper，父模型作者表单 Mapper
     * @param formTemplateValidator WorkflowFormTemplateValidator，表单结构安全校验器
     * @param snapshotMapper WfDeployCallActivityMapper，调用依赖快照 Mapper
     * @return 无返回值，构造后由 Spring 管理
     */
    @Autowired
    public WorkflowCallActivityReferenceService(RepositoryService repositoryService,
            WorkflowEngineOperations engineOperations, WorkflowIdentityResolver identityResolver,
            WfDeployFormMapper deployFormMapper, WfFormMapper formMapper,
            WorkflowFormTemplateValidator formTemplateValidator,
            WfDeployCallActivityMapper snapshotMapper)
    {
        this.repositoryService = repositoryService;
        this.engineOperations = engineOperations;
        this.identityResolver = identityResolver;
        this.deployFormMapper = deployFormMapper;
        this.formMapper = formMapper;
        this.formTemplateValidator = formTemplateValidator;
        this.snapshotMapper = snapshotMapper;
    }

    /**
     * 兼容只验证旧精确版本冻结行为的纯单元测试。
     *
     * @param repositoryService RepositoryService，Flowable 仓储 API
     * @return 无返回值，不提供目录、权限、字段和快照能力
     */
    public WorkflowCallActivityReferenceService(RepositoryService repositoryService)
    {
        this(repositoryService, null, null, null, null, null, null);
    }

    /**
     * 查询当前用户有权引用的全部已发布子流程版本及其变量字段目录。
     *
     * @param keyword String，可选流程名称或 key 模糊过滤
     * @return List&lt;WorkflowCallActivityOptionView&gt;，按 key、版本倒序的有界目录
     */
    public List<WorkflowCallActivityOptionView> listReferenceOptions(String keyword)
    {
        requireProductionDependencies();
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase();
        if (normalizedKeyword.length() > 255)
        {
            throw new ServiceException("调用活动目录查询条件过长", HttpStatus.BAD_REQUEST);
        }
        return engineOperations.read(() ->
        {
            WorkflowCurrentIdentity actor = identityResolver.resolveCurrentIdentity();
            List<ProcessDefinition> definitions = repositoryService.createProcessDefinitionQuery()
                    .orderByProcessDefinitionKey().asc()
                    .orderByProcessDefinitionVersion().desc().list();
            if (definitions == null)
            {
                throw dataError("流程定义目录查询异常");
            }
            List<WorkflowCallActivityOptionView> result = new ArrayList<>();
            for (ProcessDefinition definition : definitions)
            {
                if (result.size() >= MAX_CATALOG_DEFINITIONS)
                {
                    throw new ServiceException("可引用流程版本过多，请先按名称检索", HttpStatus.CONFLICT);
                }
                String searchable = (safeText(definition.getName()) + " "
                        + safeText(definition.getKey())).toLowerCase();
                if ((!normalizedKeyword.isEmpty() && !searchable.contains(normalizedKeyword))
                        || !isReferenceAllowed(definition, actor))
                {
                    continue;
                }
                TargetFieldCatalog fields = targetFieldCatalog(definition);
                result.add(new WorkflowCallActivityOptionView(definition.getId(), definition.getKey(),
                        definition.getName(), definition.getVersion(), definition.getCategory(),
                        definition.getDeploymentId(), definition.isSuspended() ? "SUSPENDED" : "ACTIVE",
                        fields.inputFields(), fields.outputFields()));
            }
            return List.copyOf(result);
        });
    }

    /**
     * 在保存与无副作用校验入口核验作者调用引用、权限、循环、字段与版本策略。
     *
     * @param document WorkflowBpmnDocument，经过 BPMN 安全解析的作者资源
     * @param actor WorkflowCurrentIdentity，当前保存操作人正式身份
     * @return void，任一调用配置非法时在模型写入前失败
     */
    public void validateAuthorReferences(WorkflowBpmnDocument document,
            WorkflowCurrentIdentity actor)
    {
        Objects.requireNonNull(document, "调用活动作者文档不能为空");
        Objects.requireNonNull(actor, "调用活动校验身份不能为空");
        Set<String> localProcessKeys = executableProcessKeys(document.bpmnModel());
        Map<String, Map<String, FieldSpec>> parentFields = parentFieldCatalogs(document);
        for (Process process : document.bpmnModel().getProcesses())
        {
            for (CallActivity callActivity : process.findFlowElementsOfType(CallActivity.class, true))
            {
                validateCallActivity(process.getId(), callActivity, localProcessKeys,
                        parentFields.getOrDefault(process.getId(), Map.of()), actor);
            }
        }
    }

    /**
     * 在当前只读事务中重新解析登录身份并校验作者调用引用。
     * @param document WorkflowBpmnDocument，经过 BPMN 安全解析的作者资源
     * @return void，越权、停用、循环或映射非法时抛出稳定业务异常
     */
    public void validateAuthorReferences(WorkflowBpmnDocument document)
    {
        requireProductionDependencies();
        validateAuthorReferences(document, identityResolver.resolveCurrentIdentity());
    }

    /**
     * 把已完成其他编译阶段的 BPMN 冻结为精确定义 ID，并生成正式依赖快照。
     *
     * @param compiledBpmn byte[]，扩展、循环和 DMN 已编译的 BPMN
     * @param authorDocument WorkflowBpmnDocument，保存阶段作者资源与表单引用
     * @param actor WorkflowCurrentIdentity，部署操作人正式身份
     * @return WorkflowPreparedCallActivityDeployment，可部署字节与未绑定父部署主键的快照
     */
    public WorkflowPreparedCallActivityDeployment prepare(byte[] compiledBpmn,
            WorkflowBpmnDocument authorDocument, WorkflowCurrentIdentity actor)
    {
        requireProductionDependencies();
        validateAuthorReferences(authorDocument, actor);
        org.flowable.bpmn.model.BpmnModel compiledModel = parse(compiledBpmn);
        Set<String> localProcessKeys = executableProcessKeys(authorDocument.bpmnModel());
        Map<String, Map<String, FieldSpec>> parentFields = parentFieldCatalogs(authorDocument);
        List<WfDeployCallActivitySnapshot> snapshots = new ArrayList<>();

        for (Process process : compiledModel.getProcesses())
        {
            for (CallActivity callActivity : process.findFlowElementsOfType(CallActivity.class, true))
            {
                ProcessDefinition target = validateCallActivity(process.getId(), callActivity,
                        localProcessKeys, parentFields.getOrDefault(process.getId(), Map.of()), actor);
                WfDeployCallActivitySnapshot snapshot = buildSnapshot(process.getId(), callActivity, target);
                snapshots.add(snapshot);
                // 可执行资源只保存不可变定义 ID；后续同 key 升级不会改变既有父流程语义。
                callActivity.setCalledElement(requireText(target.getId(), "调用活动目标定义状态异常"));
                callActivity.setCalledElementType(CALLED_ELEMENT_TYPE_ID);
                callActivity.setSameDeployment(false);
            }
        }
        return new WorkflowPreparedCallActivityDeployment(
                new BpmnXMLConverter().convertToXML(compiledModel), snapshots);
    }

    /**
     * 兼容旧测试和底层工具，把 key 或定义 ID 直接冻结为精确定义 ID。
     *
     * @param compiledBpmn byte[]，待冻结 BPMN
     * @return byte[]，所有 CallActivity 均引用精确定义 ID 的 BPMN
     */
    public byte[] freezeReferences(byte[] compiledBpmn)
    {
        org.flowable.bpmn.model.BpmnModel model = parse(compiledBpmn);
        Set<String> localProcessKeys = executableProcessKeys(model);
        for (Process process : model.getProcesses())
        {
            for (CallActivity callActivity : process.findFlowElementsOfType(CallActivity.class, true))
            {
                ProcessDefinition target = resolveTarget(callActivity);
                if (localProcessKeys.contains(target.getKey()))
                {
                    throw new ServiceException("调用活动不允许直接或间接循环调用", HttpStatus.BAD_REQUEST);
                }
                callActivity.setCalledElement(target.getId());
                callActivity.setCalledElementType(CALLED_ELEMENT_TYPE_ID);
                callActivity.setSameDeployment(false);
            }
        }
        return new BpmnXMLConverter().convertToXML(model);
    }

    /**
     * 在父 Flowable 部署成功后写入同事务调用依赖快照。
     *
     * @param deployId String，父流程部署主键
     * @param prepared WorkflowPreparedCallActivityDeployment，部署前准备结果
     * @param actorUserId String，部署操作人正式用户主键
     * @return void，写入数不一致时整体回滚
     */
    public void persist(String deployId, WorkflowPreparedCallActivityDeployment prepared,
            String actorUserId)
    {
        requireProductionDependencies();
        String normalizedDeployId = requireText(deployId, "调用活动父部署主键不能为空");
        String normalizedActor = requireText(actorUserId, "调用活动部署操作人不能为空");
        List<WfDeployCallActivitySnapshot> snapshots = prepared == null
                ? List.of() : prepared.snapshots();
        for (WfDeployCallActivitySnapshot snapshot : snapshots)
        {
            snapshot.setDeployId(normalizedDeployId);
            snapshot.setCreateBy(normalizedActor);
        }
        int inserted = snapshots.isEmpty() ? 0 : snapshotMapper.insertBatch(snapshots);
        if (inserted != snapshots.size())
        {
            throw new ServiceException("调用活动部署快照保存不完整", HttpStatus.CONFLICT);
        }
    }

    /**
     * 查询指定父部署的全部调用活动快照。
     * @param deploymentId String，父流程部署主键
     * @return List&lt;WfDeployCallActivitySnapshot&gt;，不可为空的快照集合
     */
    public List<WfDeployCallActivitySnapshot> snapshotsByDeploymentId(String deploymentId)
    {
        requireProductionDependencies();
        List<WfDeployCallActivitySnapshot> snapshots = snapshotMapper.selectByDeploymentId(
                requireText(deploymentId, "调用活动父部署主键不能为空"));
        return snapshots == null ? List.of() : List.copyOf(snapshots);
    }

    /**
     * 删除受控父部署对应的调用活动快照。
     * @param deploymentId String，父流程部署主键
     * @return int，实际删除行数
     */
    public int deleteSnapshots(String deploymentId)
    {
        requireProductionDependencies();
        return snapshotMapper.deleteByDeploymentId(
                requireText(deploymentId, "调用活动父部署主键不能为空"));
    }

    /**
     * 返回全部已发布 BPMN 中通过精确定义 ID 冻结的调用目标。
     * @return Set&lt;String&gt;，仍需保持可调用状态的流程定义主键集合
     */
    public Set<String> frozenTargetDefinitionIds()
    {
        List<ProcessDefinition> definitions = repositoryService.createProcessDefinitionQuery().list();
        if (definitions == null || definitions.isEmpty())
        {
            return Set.of();
        }
        Set<String> targetIds = new LinkedHashSet<>();
        for (ProcessDefinition definition : definitions)
        {
            collectFrozenTargets(definition, targetIds);
        }
        return Set.copyOf(targetIds);
    }

    /**
     * 删除部署前检查其流程定义是否仍被删除范围之外的调用活动引用。
     * @param deploymentIds Collection&lt;String&gt;，本次事务准备删除的部署主键
     * @return void，存在外部引用时抛出 409 且不产生删除副作用
     */
    public void assertDeploymentsNotReferenced(Collection<String> deploymentIds)
    {
        Set<String> deletingDeployments = deploymentIds == null ? Set.of() : Set.copyOf(deploymentIds);
        if (deletingDeployments.isEmpty())
        {
            return;
        }
        List<ProcessDefinition> definitions = repositoryService.createProcessDefinitionQuery().list();
        if (definitions == null || definitions.isEmpty())
        {
            return;
        }
        Set<String> deletingDefinitionIds = definitions.stream()
                .filter(definition -> deletingDeployments.contains(definition.getDeploymentId()))
                .map(ProcessDefinition::getId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (ProcessDefinition definition : definitions)
        {
            if (deletingDeployments.contains(definition.getDeploymentId()))
            {
                continue;
            }
            Set<String> referencedIds = new LinkedHashSet<>();
            collectFrozenTargets(definition, referencedIds);
            if (referencedIds.stream().anyMatch(deletingDefinitionIds::contains))
            {
                throw new ServiceException("部署仍被调用活动引用，不能删除", HttpStatus.CONFLICT);
            }
        }
    }

    /**
     * 校验一个作者调用活动并返回服务端重新解析的真实目标定义。
     *
     * @param processKey String，父流程 key
     * @param callActivity CallActivity，待校验调用活动
     * @param localProcessKeys Set&lt;String&gt;，本次资源可执行流程 key
     * @param parentFields Map&lt;String, FieldSpec&gt;，父流程可映射字段
     * @param actor WorkflowCurrentIdentity，当前作者身份
     * @return ProcessDefinition，存在、激活、授权且无循环的目标定义
     */
    private ProcessDefinition validateCallActivity(String processKey, CallActivity callActivity,
            Set<String> localProcessKeys, Map<String, FieldSpec> parentFields,
            WorkflowCurrentIdentity actor)
    {
        if (!hasText(callActivity.getId()))
        {
            throw new ServiceException("调用活动元素标识不能为空", HttpStatus.BAD_REQUEST);
        }
        ProcessDefinition target = resolveTarget(callActivity);
        if (target.isSuspended())
        {
            throw new ServiceException("调用活动目标流程已停用", HttpStatus.CONFLICT);
        }
        if (!isReferenceAllowed(target, actor))
        {
            throw new ServiceException("当前用户无权引用该子流程", HttpStatus.FORBIDDEN);
        }
        assertNoCallCycle(localProcessKeys, target);
        validateInstanceProperties(callActivity);
        TargetFieldCatalog targetFields = targetFieldCatalog(target);
        validateMappings(callActivity.getInParameters(), parentFields, targetFields.inputFieldMap(),
                "输入");
        validateMappings(callActivity.getOutParameters(), targetFields.outputFieldMap(), parentFields,
                "输出");
        return target;
    }

    /**
     * 按作者版本策略解析目标定义，拒绝动态表达式和未知绑定类型。
     * @param callActivity CallActivity，包含 calledElement 与 calledElementType 的作者配置
     * @return ProcessDefinition，服务端查询得到的真实定义
     */
    private ProcessDefinition resolveTarget(CallActivity callActivity)
    {
        String reference = requireText(callActivity.getCalledElement(), "调用活动必须选择被调用流程");
        if (containsExpression(reference))
        {
            throw new ServiceException("调用活动不允许使用动态流程表达式", HttpStatus.BAD_REQUEST);
        }
        String referenceType = hasText(callActivity.getCalledElementType())
                ? callActivity.getCalledElementType().trim() : CALLED_ELEMENT_TYPE_KEY;
        ProcessDefinition target;
        if (CALLED_ELEMENT_TYPE_ID.equals(referenceType))
        {
            target = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionId(reference).singleResult();
        }
        else if (CALLED_ELEMENT_TYPE_KEY.equals(referenceType))
        {
            target = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey(reference).processDefinitionWithoutTenantId()
                    .latestVersion().singleResult();
        }
        else
        {
            throw new ServiceException("调用活动版本绑定策略不合法", HttpStatus.BAD_REQUEST);
        }
        if (target == null)
        {
            throw new ServiceException("调用活动目标流程不存在", HttpStatus.CONFLICT);
        }
        return target;
    }

    /**
     * 校验业务键、实例名称和平台固定执行语义，禁止表达式与同部署猜测。
     * @param callActivity CallActivity，待校验调用活动
     * @return void，非法属性在保存或部署前失败
     */
    private void validateInstanceProperties(CallActivity callActivity)
    {
        if (hasText(callActivity.getBusinessKey()))
        {
            throw new ServiceException("调用活动业务键只能选择继承父流程或不设置", HttpStatus.BAD_REQUEST);
        }
        if (containsExpression(safeText(callActivity.getProcessInstanceName())))
        {
            throw new ServiceException("子流程实例名称不允许使用表达式", HttpStatus.BAD_REQUEST);
        }
        if (safeText(callActivity.getProcessInstanceName()).length() > 255)
        {
            throw new ServiceException("子流程实例名称过长", HttpStatus.BAD_REQUEST);
        }
        if (callActivity.isSameDeployment() || callActivity.isCompleteAsync())
        {
            throw new ServiceException("调用活动只支持跨部署同步调用与整树传播语义", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 校验输入或输出变量映射的字段、类型、重复和表达式约束。
     *
     * @param mappings List&lt;IOParameter&gt;，Flowable 原生 in 或 out 参数
     * @param sources Map&lt;String, FieldSpec&gt;，允许作为来源的字段目录
     * @param targets Map&lt;String, FieldSpec&gt;，允许作为目标的字段目录
     * @param direction String，输入或输出，用于稳定错误消息
     * @return void，任一映射不满足契约时抛出 400
     */
    private void validateMappings(List<IOParameter> mappings, Map<String, FieldSpec> sources,
            Map<String, FieldSpec> targets, String direction)
    {
        List<IOParameter> safeMappings = mappings == null ? List.of() : mappings;
        if (safeMappings.size() > MAX_MAPPINGS)
        {
            throw new ServiceException("调用活动" + direction + "变量映射不能超过64项", HttpStatus.BAD_REQUEST);
        }
        Set<String> targetNames = new LinkedHashSet<>();
        for (IOParameter mapping : safeMappings)
        {
            String sourceName = requireVariable(mapping == null ? null : mapping.getSource(), direction + "来源变量不合法");
            String targetName = requireVariable(mapping.getTarget(), direction + "目标变量不合法");
            if (hasText(mapping.getSourceExpression()) || hasText(mapping.getTargetExpression())
                    || mapping.isTransient())
            {
                throw new ServiceException("调用活动变量映射不允许表达式、临时变量或目标表达式", HttpStatus.BAD_REQUEST);
            }
            if (!targetNames.add(targetName))
            {
                throw new ServiceException("调用活动" + direction + "变量目标不能重复", HttpStatus.BAD_REQUEST);
            }
            FieldSpec source = sources.get(sourceName);
            FieldSpec target = targets.get(targetName);
            if (source == null || !source.readable() || target == null || !target.writable())
            {
                throw new ServiceException("调用活动" + direction + "变量不在可映射字段目录中", HttpStatus.BAD_REQUEST);
            }
            if (!compatibleTypes(source.type(), target.type()))
            {
                throw new ServiceException("调用活动" + direction + "变量字段类型不兼容", HttpStatus.BAD_REQUEST);
            }
        }
    }

    /**
     * 检查目标定义的完整调用依赖图是否回到本次父流程 key。
     * @param localProcessKeys Set&lt;String&gt;，本次资源可执行流程 key
     * @param target ProcessDefinition，本次直接目标定义
     * @return void，直接或间接循环时抛出 400
     */
    private void assertNoCallCycle(Set<String> localProcessKeys, ProcessDefinition target)
    {
        ArrayDeque<ProcessDefinition> queue = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        queue.add(target);
        while (!queue.isEmpty())
        {
            ProcessDefinition current = queue.removeFirst();
            if (!visited.add(current.getId()))
            {
                continue;
            }
            if (visited.size() > MAX_DEPENDENCY_SCAN)
            {
                throw new ServiceException("调用活动依赖层级过多", HttpStatus.CONFLICT);
            }
            if (localProcessKeys.contains(current.getKey()))
            {
                throw new ServiceException("调用活动不允许直接或间接循环调用", HttpStatus.BAD_REQUEST);
            }
            org.flowable.bpmn.model.BpmnModel model = repositoryService.getBpmnModel(current.getId());
            if (model == null)
            {
                throw dataError("已发布子流程 BPMN 资源不存在");
            }
            for (Process process : model.getProcesses())
            {
                for (CallActivity nested : process.findFlowElementsOfType(CallActivity.class, true))
                {
                    queue.add(resolveTarget(nested));
                }
            }
        }
    }

    /**
     * 判断流程定义对当前设计者是否公开或存在匹配的用户、角色、部门发起权限。
     * @param definition ProcessDefinition，待判定子流程定义
     * @param actor WorkflowCurrentIdentity，当前用户及候选组
     * @return boolean，无 starter 限制或身份命中时返回 true
     */
    private boolean isReferenceAllowed(ProcessDefinition definition, WorkflowCurrentIdentity actor)
    {
        List<IdentityLink> links = repositoryService.getIdentityLinksForProcessDefinition(definition.getId());
        if (links == null)
        {
            throw dataError("子流程引用权限数据异常");
        }
        if (links.isEmpty())
        {
            return true;
        }
        for (IdentityLink link : links)
        {
            if (link != null && (actor.userId().equals(link.getUserId())
                    || actor.candidateGroups().contains(link.getGroupId())))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * 从作者文档的正式模板或内嵌表单构建各父流程字段目录。
     * @param document WorkflowBpmnDocument，已解析作者文档
     * @return Map&lt;String, Map&lt;String, FieldSpec&gt;&gt;，按父流程 key 分组的字段目录
     */
    private Map<String, Map<String, FieldSpec>> parentFieldCatalogs(WorkflowBpmnDocument document)
    {
        if (formTemplateValidator == null || formMapper == null)
        {
            return Map.of();
        }
        Map<String, Map<String, FieldSpec>> catalogs = new LinkedHashMap<>();
        for (WorkflowBpmnFormReference reference : document.formReferences())
        {
            String content;
            if (reference.sourceType() == WorkflowFormSourceType.TEMPLATE)
            {
                WfForm form = formMapper.selectById(reference.formId());
                if (form == null || !hasText(form.getContent()))
                {
                    throw new ServiceException("调用活动父流程表单不存在或已停用", HttpStatus.CONFLICT);
                }
                content = form.getContent();
            }
            else
            {
                content = reference.embeddedContent();
            }
            mergeFields(catalogs.computeIfAbsent(reference.processKey(), key -> new LinkedHashMap<>()),
                    extractFieldSpecs(content), "父流程表单字段类型冲突");
        }
        return catalogs.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Map.copyOf(entry.getValue())));
    }

    /**
     * 从子流程不可变部署表单快照提取开始输入字段和全部可读输出字段。
     * @param definition ProcessDefinition，目标已发布定义
     * @return TargetFieldCatalog，输入和输出字段目录
     */
    private TargetFieldCatalog targetFieldCatalog(ProcessDefinition definition)
    {
        if (deployFormMapper == null || formTemplateValidator == null)
        {
            return TargetFieldCatalog.empty();
        }
        List<WfDeployForm> snapshots = deployFormMapper.selectByDeploymentId(definition.getDeploymentId());
        if (snapshots == null)
        {
            throw dataError("子流程部署表单快照查询异常");
        }
        org.flowable.bpmn.model.BpmnModel model = repositoryService.getBpmnModel(definition.getId());
        Process process = model == null ? null : model.getProcessById(definition.getKey());
        List<StartEvent> starts = process == null ? List.of()
                : process.getFlowElements().stream().filter(StartEvent.class::isInstance)
                        .map(StartEvent.class::cast).toList();
        if (starts.size() != 1)
        {
            throw dataError("子流程开始节点结构异常");
        }
        String startNodeId = starts.get(0).getId();
        Map<String, FieldSpec> inputs = new LinkedHashMap<>();
        Map<String, FieldSpec> outputs = new LinkedHashMap<>();
        for (WfDeployForm snapshot : snapshots)
        {
            if (snapshot == null || !hasText(snapshot.getContent()))
            {
                throw dataError("子流程部署表单快照结构异常");
            }
            Map<String, FieldSpec> fields = extractFieldSpecs(snapshot.getContent());
            mergeFields(outputs, fields, "子流程输出字段类型冲突");
            if (startNodeId.equals(snapshot.getNodeKey()))
            {
                mergeFields(inputs, fields, "子流程输入字段类型冲突");
            }
        }
        return new TargetFieldCatalog(Map.copyOf(inputs), Map.copyOf(outputs),
                toVariableViews(inputs), toVariableViews(outputs));
    }

    /**
     * 从已校验表单 JSON 提取可安全映射的标量字段。
     * @param content String，正式模板或部署快照 JSON
     * @return Map&lt;String, FieldSpec&gt;，按表单顺序去重的标量字段
     */
    private Map<String, FieldSpec> extractFieldSpecs(String content)
    {
        formTemplateValidator.validate(content);
        try
        {
            JsonNode root = objectMapper.readTree(content);
            Map<String, FieldSpec> fields = new LinkedHashMap<>();
            collectFieldSpecs(root == null ? null : root.get("fields"), fields);
            return Map.copyOf(fields);
        }
        catch (JacksonException exception)
        {
            ServiceException failure = dataError("流程表单字段目录解析失败");
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * 递归收集表单字段，只开放单值文本、数值、布尔和受控标量。
     * @param nodes JsonNode，当前 fields 或 children 数组
     * @param fields Map&lt;String, FieldSpec&gt;，调用方维护的字段目录
     * @return void，集合、附件、表格和范围字段不会进入映射目录
     */
    private void collectFieldSpecs(JsonNode nodes, Map<String, FieldSpec> fields)
    {
        if (nodes == null || !nodes.isArray())
        {
            return;
        }
        for (JsonNode field : nodes)
        {
            JsonNode config = field.get("__config__");
            String name = text(field, "__vModel__");
            String tag = text(config, "tag");
            String type = fieldType(tag, field);
            if (hasText(name) && VARIABLE_PATTERN.matcher(name).matches() && type != null)
            {
                String label = firstText(text(config, "label"), name);
                boolean required = booleanValue(config, "required", false);
                boolean readable = booleanValue(config, "workflowReadable", true);
                boolean writable = booleanValue(config, "workflowWritable", true)
                        && !booleanValue(field, "disabled", false);
                FieldSpec candidate = new FieldSpec(name, label, type, required, readable, writable);
                FieldSpec previous = fields.putIfAbsent(name, candidate);
                if (previous != null && !previous.type().equals(candidate.type()))
                {
                    throw dataError("流程表单包含同名异构字段");
                }
            }
            collectFieldSpecs(config == null ? null : config.get("children"), fields);
        }
    }

    /**
     * 将表单组件类型归一为调用活动允许的四类标量。
     * @param tag String，表单组件 tag
     * @param field JsonNode，完整字段节点
     * @return String，TEXT、NUMBER、BOOLEAN、SCALAR 或 null
     */
    private String fieldType(String tag, JsonNode field)
    {
        return switch (safeText(tag))
        {
            case "el-input", "tinymce", "el-color-picker", "el-date-picker", "el-time-picker" ->
                    text(field, "type").toLowerCase().contains("range") ? null : "TEXT";
            case "el-input-number", "el-rate" -> "NUMBER";
            case "el-slider" -> booleanValue(field, "range", false) ? null : "NUMBER";
            case "el-switch" -> "BOOLEAN";
            case "el-radio-group" -> "SCALAR";
            case "el-select" -> booleanValue(field, "multiple", false) ? null : "SCALAR";
            default -> null;
        };
    }

    /**
     * 构造单个冻结依赖快照并计算覆盖全部业务字段的摘要。
     * @param processKey String，父流程 key
     * @param callActivity CallActivity，已校验作者配置
     * @param target ProcessDefinition，冻结目标定义
     * @return WfDeployCallActivitySnapshot，尚未绑定父部署主键和操作人的快照
     */
    private WfDeployCallActivitySnapshot buildSnapshot(String processKey,
            CallActivity callActivity, ProcessDefinition target)
    {
        WfDeployCallActivitySnapshot snapshot = new WfDeployCallActivitySnapshot();
        snapshot.setProcessKey(processKey);
        snapshot.setElementId(callActivity.getId());
        snapshot.setVersionPolicy(CALLED_ELEMENT_TYPE_ID.equals(callActivity.getCalledElementType())
                ? "FIXED" : "LATEST_ACTIVE");
        snapshot.setTargetDefinitionId(target.getId());
        snapshot.setTargetProcessKey(target.getKey());
        snapshot.setTargetProcessName(firstText(target.getName(), target.getKey()));
        snapshot.setTargetVersion(target.getVersion());
        snapshot.setTargetDeploymentId(target.getDeploymentId());
        snapshot.setInheritVariables(callActivity.isInheritVariables());
        snapshot.setInheritBusinessKey(callActivity.isInheritBusinessKey());
        snapshot.setLocalScopeForOutput(callActivity.isUseLocalScopeForOutParameters());
        snapshot.setPropagationPolicy(PROPAGATION_POLICY);
        snapshot.setInputMappingsJson(mappingJson(callActivity.getInParameters()));
        snapshot.setOutputMappingsJson(mappingJson(callActivity.getOutParameters()));
        String checksumSource = String.join("\n", processKey, callActivity.getId(),
                snapshot.getVersionPolicy(), target.getId(), target.getKey(),
                String.valueOf(target.getVersion()), target.getDeploymentId(),
                String.valueOf(snapshot.getInheritVariables()),
                String.valueOf(snapshot.getInheritBusinessKey()),
                String.valueOf(snapshot.getLocalScopeForOutput()), PROPAGATION_POLICY,
                snapshot.getInputMappingsJson(), snapshot.getOutputMappingsJson());
        snapshot.setSnapshotChecksum(sha256(checksumSource));
        return snapshot;
    }

    /**
     * 把 Flowable IOParameter 转换为稳定有序 JSON，仅包含 source 和 target。
     * @param mappings List&lt;IOParameter&gt;，已完成表达式与字段校验的映射
     * @return String，规范 JSON 数组
     */
    private String mappingJson(List<IOParameter> mappings)
    {
        List<Map<String, String>> values = (mappings == null ? List.<IOParameter>of() : mappings)
                .stream().map(mapping -> Map.of("source", mapping.getSource(),
                        "target", mapping.getTarget())).toList();
        try
        {
            return objectMapper.writeValueAsString(values);
        }
        catch (JacksonException exception)
        {
            ServiceException failure = dataError("调用活动变量映射序列化失败");
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * 从一个已发布定义的编译 BPMN 中提取精确调用目标。
     * @param definition ProcessDefinition，待读取定义
     * @param targetIds Set&lt;String&gt;，调用方维护的目标定义集合
     * @return void，读取失败时 fail-closed
     */
    private void collectFrozenTargets(ProcessDefinition definition, Set<String> targetIds)
    {
        try
        {
            org.flowable.bpmn.model.BpmnModel model = repositoryService.getBpmnModel(definition.getId());
            if (model == null)
            {
                throw dataError("已发布流程 BPMN 资源不存在");
            }
            for (Process process : model.getProcesses())
            {
                process.findFlowElementsOfType(CallActivity.class, true).stream()
                        .filter(call -> CALLED_ELEMENT_TYPE_ID.equals(call.getCalledElementType()))
                        .map(CallActivity::getCalledElement).filter(WorkflowCallActivityReferenceService::hasText)
                        .map(String::trim).forEach(targetIds::add);
            }
        }
        catch (FlowableException exception)
        {
            ServiceException failure = new ServiceException("已发布流程调用引用读取失败", HttpStatus.CONFLICT);
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * 使用禁用 DTD、外部实体和实体替换的 StAX Reader 解析 BPMN。
     * @param bpmnBytes byte[]，待解析 BPMN
     * @return org.flowable.bpmn.model.BpmnModel，Flowable 结构模型
     */
    private org.flowable.bpmn.model.BpmnModel parse(byte[] bpmnBytes)
    {
        if (bpmnBytes == null || bpmnBytes.length == 0)
        {
            throw new ServiceException("调用活动编译资源不能为空", HttpStatus.BAD_REQUEST);
        }
        XMLStreamReader reader = null;
        try
        {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
            factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
            factory.setXMLResolver((publicId, systemId, baseUri, namespace) ->
            {
                throw new XMLStreamException("external resources disabled");
            });
            reader = factory.createXMLStreamReader(new ByteArrayInputStream(bpmnBytes));
            return new BpmnXMLConverter().convertToBpmnModel(reader);
        }
        catch (XMLStreamException | IllegalArgumentException exception)
        {
            ServiceException failure = new ServiceException("调用活动 BPMN 编译失败", HttpStatus.BAD_REQUEST);
            failure.initCause(exception);
            throw failure;
        }
        finally
        {
            close(reader);
        }
    }

    /**
     * 提取资源内全部可执行流程 key。
     * @param model org.flowable.bpmn.model.BpmnModel，待部署模型
     * @return Set&lt;String&gt;，非空可执行流程 key
     */
    private Set<String> executableProcessKeys(org.flowable.bpmn.model.BpmnModel model)
    {
        return model.getProcesses().stream().filter(Process::isExecutable)
                .map(Process::getId).filter(WorkflowCallActivityReferenceService::hasText)
                .map(String::trim).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * 合并字段目录并阻止同名异构字段静默覆盖。
     * @param target Map&lt;String, FieldSpec&gt;，目标目录
     * @param source Map&lt;String, FieldSpec&gt;，来源目录
     * @param conflictMessage String，类型冲突提示
     * @return void，类型一致时保留首次字段业务名称
     */
    private void mergeFields(Map<String, FieldSpec> target, Map<String, FieldSpec> source,
            String conflictMessage)
    {
        source.forEach((name, field) ->
        {
            FieldSpec previous = target.putIfAbsent(name, field);
            if (previous != null && !previous.type().equals(field.type()))
            {
                throw new ServiceException(conflictMessage, HttpStatus.CONFLICT);
            }
        });
    }

    /**
     * 将内部字段规则转换为不泄露表单正文的目录视图。
     * @param fields Map&lt;String, FieldSpec&gt;，字段规则
     * @return List&lt;WorkflowCallActivityVariableView&gt;，按变量名稳定排序的视图
     */
    private List<WorkflowCallActivityVariableView> toVariableViews(Map<String, FieldSpec> fields)
    {
        return fields.values().stream().sorted(Comparator.comparing(FieldSpec::name))
                .map(field -> new WorkflowCallActivityVariableView(field.name(), field.label(),
                        field.type(), field.required(), field.readable(), field.writable()))
                .toList();
    }

    /** @param left String，来源字段类型。@param right String，目标字段类型。@return boolean，同型或 SCALAR 兼容时返回 true。 */
    private boolean compatibleTypes(String left, String right)
    {
        return Objects.equals(left, right) || ("SCALAR".equals(left) && Set.of("TEXT", "NUMBER", "BOOLEAN").contains(right))
                || ("SCALAR".equals(right) && Set.of("TEXT", "NUMBER", "BOOLEAN").contains(left));
    }

    /** @param value String，变量名。@param message String，错误提示。@return String，合法变量名。 */
    private String requireVariable(String value, String message)
    {
        String normalized = requireText(value, message);
        if (!VARIABLE_PATTERN.matcher(normalized).matches())
        {
            throw new ServiceException(message, HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /** @param value String，摘要正文。@return String，64 位小写 SHA-256。 */
    private String sha256(String value)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    /** @param reader XMLStreamReader，可为空解析器。@return void，关闭失败不覆盖业务结果。 */
    private void close(XMLStreamReader reader)
    {
        if (reader == null) return;
        try { reader.close(); } catch (XMLStreamException ignored) { /* 关闭失败不改变编译结论。 */ }
    }

    /** @param value String，待检查文本。@return boolean，包含 EL 表达式标记时返回 true。 */
    private boolean containsExpression(String value)
    {
        return value != null && (value.contains("${") || value.contains("#{"));
    }

    /** @param value String，待校验文本。@param message String，空值提示。@return String，规范非空文本。 */
    private String requireText(String value, String message)
    {
        if (!hasText(value)) throw new ServiceException(message, HttpStatus.BAD_REQUEST);
        return value.trim();
    }

    /** @return void，生产依赖缺失时阻止假目录或假快照。 */
    private void requireProductionDependencies()
    {
        if (engineOperations == null || identityResolver == null || deployFormMapper == null
                || formMapper == null || formTemplateValidator == null || snapshotMapper == null)
        {
            throw new ServiceException("调用活动生产依赖未完整配置", HttpStatus.ERROR);
        }
    }

    /** @param message String，稳定数据错误提示。@return ServiceException，HTTP 500 数据异常。 */
    private ServiceException dataError(String message)
    {
        return new ServiceException(message, HttpStatus.ERROR);
    }

    /** @param node JsonNode，可空对象。@param field String，字段名。@return String，文本值或空串。 */
    private String text(JsonNode node, String field)
    {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : "";
    }

    /** @param node JsonNode，可空对象。@param field String，字段名。@param fallback boolean，默认值。@return boolean，布尔值。 */
    private boolean booleanValue(JsonNode node, String field, boolean fallback)
    {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isBoolean() ? value.asBoolean() : fallback;
    }

    /** @param preferred String，优先值。@param fallback String，后备值。@return String，第一个非空文本。 */
    private String firstText(String preferred, String fallback)
    {
        return hasText(preferred) ? preferred.trim() : safeText(fallback);
    }

    /** @param value String，可空文本。@return String，空值转换为空串。 */
    private String safeText(String value)
    {
        return value == null ? "" : value.trim();
    }

    /** @param value String，待判断文本。@return boolean，包含非空白字符时返回 true。 */
    private static boolean hasText(String value)
    {
        return value != null && !value.isBlank();
    }

    /** 调用活动映射可使用的表单字段规则。 */
    private record FieldSpec(String name, String label, String type, boolean required,
            boolean readable, boolean writable) { }

    /** 子流程输入和输出字段的内部目录与对外视图。 */
    private record TargetFieldCatalog(Map<String, FieldSpec> inputFieldMap,
            Map<String, FieldSpec> outputFieldMap,
            List<WorkflowCallActivityVariableView> inputFields,
            List<WorkflowCallActivityVariableView> outputFields)
    {
        /** @return TargetFieldCatalog，无生产表单依赖的空目录。 */
        private static TargetFieldCatalog empty()
        {
            return new TargetFieldCatalog(Map.of(), Map.of(), List.of(), List.of());
        }
    }
}
