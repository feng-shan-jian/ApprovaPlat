package com.ruoyi.flowable.service.model;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.DeploymentBuilder;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployCallActivitySnapshot;
import com.ruoyi.flowable.domain.WfDeployConditionRule;
import com.ruoyi.flowable.domain.WfDeployControlledLoop;
import com.ruoyi.flowable.domain.WfDeployDmnSnapshot;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.WfDeployParticipantRule;
import com.ruoyi.flowable.domain.WfDeployTaskSla;

/**
 * 使用 Flowable 子部署资源持久化和读取不可变业务部署快照。
 *
 * 资源与父流程部署共享同一数据库事务和生命周期，运行代码只通过 RepositoryService
 * 访问官方表，不直接读写 ACT_GE_BYTEARRAY 等 Flowable 内部结构。
 */
@Repository
public class WorkflowDeploymentArtifactRepository
{
    /** 部署资源协议版本；协议变化必须新增资源名并提供正式迁移。 */
    private static final int SCHEMA_VERSION = 1;
    /** 业务资源子部署分类，用于与可执行流程部署隔离。 */
    private static final String ARTIFACT_CATEGORY = "APPROVAPLAT_WORKFLOW_ARTIFACTS";
    /** 业务资源子部署稳定 key 前缀。 */
    private static final String ARTIFACT_KEY_PREFIX = "approvaplat-artifacts:";
    /** 单个资源最大 32 MiB，防止损坏资源导致无界内存分配。 */
    private static final int MAX_RESOURCE_BYTES = 32 * 1024 * 1024;
    /** 一个部署全部业务资源最大 64 MiB。 */
    private static final int MAX_TOTAL_BYTES = 64 * 1024 * 1024;

    private static final String MANIFEST_RESOURCE = "approvaplat/manifest-v1.json";
    private static final String FORMS_RESOURCE = "approvaplat/forms-v1.json";
    private static final String CONDITIONS_RESOURCE = "approvaplat/conditions-v1.json";
    private static final String LOOPS_RESOURCE = "approvaplat/controlled-loops-v1.json";
    private static final String PARTICIPANTS_RESOURCE = "approvaplat/participants-v1.json";
    private static final String EXTENSIONS_RESOURCE = "approvaplat/extensions-v1.json";
    private static final String DMN_RESOURCE = "approvaplat/dmn-v1.json";
    private static final String CALL_ACTIVITIES_RESOURCE = "approvaplat/call-activities-v1.json";
    private static final String SLA_RESOURCE = "approvaplat/task-sla-v1.json";

    private static final TypeReference<List<WfDeployForm>> FORM_LIST = new TypeReference<>() { };
    private static final TypeReference<List<WfDeployConditionRule>> CONDITION_LIST =
            new TypeReference<>() { };
    private static final TypeReference<List<WfDeployControlledLoop>> LOOP_LIST =
            new TypeReference<>() { };
    private static final TypeReference<List<WfDeployParticipantRule>> PARTICIPANT_LIST =
            new TypeReference<>() { };
    private static final TypeReference<List<WfDeployExtensionSnapshot>> EXTENSION_LIST =
            new TypeReference<>() { };
    private static final TypeReference<List<WfDeployDmnSnapshot>> DMN_LIST =
            new TypeReference<>() { };
    private static final TypeReference<List<WfDeployCallActivitySnapshot>> CALL_ACTIVITY_LIST =
            new TypeReference<>() { };
    private static final TypeReference<List<WfDeployTaskSla>> SLA_LIST =
            new TypeReference<>() { };

    private final RepositoryService repositoryService;
    private final ObjectMapper objectMapper;

    /**
     * 创建部署业务资源仓库。
     *
     * @param repositoryService RepositoryService，Flowable 官方部署与资源访问 API
     * @return 无返回值，构造完成后由 Spring 管理
     */
    public WorkflowDeploymentArtifactRepository(RepositoryService repositoryService)
    {
        this.repositoryService = repositoryService;
        this.objectMapper = JsonMapper.shared();
    }

    /**
     * 在当前发布事务中创建唯一业务资源子部署。
     *
     * @param deploymentId String，父流程部署主键
     * @param artifacts WorkflowDeploymentArtifacts，已经完成业务校验的不可变资源
     * @return String，Flowable 业务资源子部署主键
     */
    public String persist(String deploymentId, WorkflowDeploymentArtifacts artifacts)
    {
        String normalizedDeploymentId = requireText(deploymentId, "流程部署主键不能为空");
        WorkflowDeploymentArtifacts normalized = bindAndValidate(normalizedDeploymentId,
                artifacts == null ? WorkflowDeploymentArtifacts.empty() : artifacts);
        if (findArtifactDeployment(normalizedDeploymentId) != null)
        {
            throw new ServiceException("流程部署业务资源已经存在", HttpStatus.CONFLICT);
        }

        List<ResourceBytes> resources = List.of(
                resource(MANIFEST_RESOURCE, new ArtifactManifest(SCHEMA_VERSION)),
                resource(FORMS_RESOURCE, normalized.forms()),
                resource(CONDITIONS_RESOURCE, normalized.conditionRules()),
                resource(LOOPS_RESOURCE, normalized.controlledLoops()),
                resource(PARTICIPANTS_RESOURCE, normalized.participantRules()),
                resource(EXTENSIONS_RESOURCE, normalized.extensionSnapshots()),
                resource(DMN_RESOURCE, normalized.dmnSnapshots()),
                resource(CALL_ACTIVITIES_RESOURCE, normalized.callActivitySnapshots()),
                resource(SLA_RESOURCE, normalized.taskSlaSnapshots()));
        long totalBytes = resources.stream().mapToLong(resource -> resource.bytes().length).sum();
        if (totalBytes > MAX_TOTAL_BYTES)
        {
            throw new ServiceException("流程部署业务资源总大小超过限制", HttpStatus.BAD_REQUEST);
        }

        DeploymentBuilder builder = repositoryService.createDeployment()
                .name("ApprovaPlat 流程部署业务资源 " + normalizedDeploymentId)
                .key(ARTIFACT_KEY_PREFIX + normalizedDeploymentId)
                .category(ARTIFACT_CATEGORY)
                .parentDeploymentId(normalizedDeploymentId);
        for (ResourceBytes resource : resources)
        {
            builder.addBytes(resource.name(), resource.bytes());
        }
        Deployment deployment = builder.deploy();
        if (deployment == null || !StringUtils.hasText(deployment.getId()))
        {
            throw new ServiceException("流程部署业务资源保存不完整", HttpStatus.CONFLICT);
        }
        if (repositoryService.createProcessDefinitionQuery().deploymentId(deployment.getId())
                .count() != 0)
        {
            throw new ServiceException("流程部署业务资源不得产生可执行流程定义", HttpStatus.ERROR);
        }
        return deployment.getId();
    }

    /**
     * 删除父流程部署拥有的唯一业务资源子部署。
     *
     * @param deploymentId String，父流程部署主键
     * @return int，实际删除的业务资源子部署数量，历史未托管部署返回 0
     */
    public int delete(String deploymentId)
    {
        Deployment artifact = findArtifactDeployment(requireText(deploymentId,
                "流程部署主键不能为空"));
        if (artifact == null)
        {
            return 0;
        }
        repositoryService.deleteDeployment(artifact.getId());
        return 1;
    }

    /**
     * 查询父流程部署的全部表单快照。
     *
     * @param deploymentId String，父流程部署主键
     * @return List&lt;WfDeployForm&gt;，历史未托管部署返回空列表
     */
    public List<WfDeployForm> selectForms(String deploymentId)
    {
        return sorted(readList(deploymentId, FORMS_RESOURCE, FORM_LIST),
                Comparator.comparing(WfDeployForm::getNodeKey)
                        .thenComparing(WfDeployForm::getFormKey));
    }

    /**
     * 查询父流程部署的全部条件分支快照。
     *
     * @param deploymentId String，父流程部署主键
     * @return List&lt;WfDeployConditionRule&gt;，不可变条件快照
     */
    public List<WfDeployConditionRule> selectConditionRules(String deploymentId)
    {
        return sorted(readList(deploymentId, CONDITIONS_RESOURCE, CONDITION_LIST),
                Comparator.comparing(WfDeployConditionRule::getProcessKey)
                        .thenComparing(WfDeployConditionRule::getGatewayId)
                        .thenComparing(WfDeployConditionRule::getFlowId));
    }

    /**
     * 按编译令牌读取一个网关的全部分支快照。
     *
     * @param deploymentId String，父流程部署主键
     * @param processKey String，BPMN 可执行流程标识
     * @param gatewayToken String，部署编译生成的网关摘要令牌
     * @return List&lt;WfDeployConditionRule&gt;，按分支标识排序的完整网关快照
     */
    public List<WfDeployConditionRule> selectRuntimeConditionRules(String deploymentId,
            String processKey, String gatewayToken)
    {
        String normalizedProcessKey = requireText(processKey, "流程标识不能为空");
        String normalizedGatewayToken = requireText(gatewayToken, "条件网关令牌不能为空");
        return selectConditionRules(deploymentId).stream()
                .filter(snapshot -> normalizedProcessKey.equals(snapshot.getProcessKey())
                        && normalizedGatewayToken.equals(snapshot.getGatewayToken()))
                .sorted(Comparator.comparing(WfDeployConditionRule::getFlowId))
                .toList();
    }

    /**
     * 查询父流程部署的全部受控循环快照。
     *
     * @param deploymentId String，父流程部署主键
     * @return List&lt;WfDeployControlledLoop&gt;，不可变循环快照
     */
    public List<WfDeployControlledLoop> selectControlledLoops(String deploymentId)
    {
        return sorted(readList(deploymentId, LOOPS_RESOURCE, LOOP_LIST),
                Comparator.comparing(WfDeployControlledLoop::getProcessKey)
                        .thenComparing(WfDeployControlledLoop::getActivityId));
    }

    /**
     * 查询一个流程中的全部受控循环快照。
     *
     * @param deploymentId String，父流程部署主键
     * @param processKey String，BPMN 可执行流程标识
     * @return List&lt;WfDeployControlledLoop&gt;，按活动标识排序的循环快照
     */
    public List<WfDeployControlledLoop> selectControlledLoops(String deploymentId,
            String processKey)
    {
        String normalizedProcessKey = requireText(processKey, "流程标识不能为空");
        return selectControlledLoops(deploymentId).stream()
                .filter(snapshot -> normalizedProcessKey.equals(snapshot.getProcessKey()))
                .toList();
    }

    /**
     * 精确查询一个审批节点的受控循环快照。
     *
     * @param deploymentId String，父流程部署主键
     * @param processKey String，BPMN 可执行流程标识
     * @param activityId String，用户任务节点标识
     * @return WfDeployControlledLoop，唯一快照；普通节点返回 null
     */
    public WfDeployControlledLoop selectControlledLoop(String deploymentId, String processKey,
            String activityId)
    {
        String normalizedActivityId = requireText(activityId, "循环活动标识不能为空");
        return uniqueOrNull(selectControlledLoops(deploymentId, processKey).stream()
                .filter(snapshot -> normalizedActivityId.equals(snapshot.getActivityId()))
                .toList(), "受控循环部署快照关系不唯一");
    }

    /**
     * 查询父流程部署的全部参与者规则快照。
     *
     * @param deploymentId String，父流程部署主键
     * @return List&lt;WfDeployParticipantRule&gt;，不可变参与者规则
     */
    public List<WfDeployParticipantRule> selectParticipantRules(String deploymentId)
    {
        return sorted(readList(deploymentId, PARTICIPANTS_RESOURCE, PARTICIPANT_LIST),
                Comparator.comparing(WfDeployParticipantRule::getProcessKey)
                        .thenComparing(WfDeployParticipantRule::getRuleScope)
                        .thenComparing(WfDeployParticipantRule::getActivityId));
    }

    /**
     * 查询流程级发起范围规则。
     *
     * @param deploymentId String，父流程部署主键
     * @param processKey String，BPMN 可执行流程标识
     * @return WfDeployParticipantRule，唯一发起范围；历史未托管部署返回 null
     */
    public WfDeployParticipantRule selectStartParticipantRule(String deploymentId,
            String processKey)
    {
        String normalizedProcessKey = requireText(processKey, "流程标识不能为空");
        return uniqueOrNull(selectParticipantRules(deploymentId).stream()
                .filter(rule -> normalizedProcessKey.equals(rule.getProcessKey())
                        && "START".equals(rule.getRuleScope()))
                .toList(), "流程发起范围部署快照关系不唯一");
    }

    /**
     * 查询一个用户任务的参与者规则。
     *
     * @param deploymentId String，父流程部署主键
     * @param processKey String，BPMN 可执行流程标识
     * @param activityId String，用户任务节点标识
     * @return WfDeployParticipantRule，唯一任务规则；没有配置时返回 null
     */
    public WfDeployParticipantRule selectTaskParticipantRule(String deploymentId,
            String processKey, String activityId)
    {
        String normalizedProcessKey = requireText(processKey, "流程标识不能为空");
        String normalizedActivityId = requireText(activityId, "任务节点标识不能为空");
        return uniqueOrNull(selectParticipantRules(deploymentId).stream()
                .filter(rule -> normalizedProcessKey.equals(rule.getProcessKey())
                        && normalizedActivityId.equals(rule.getActivityId())
                        && "TASK".equals(rule.getRuleScope()))
                .toList(), "任务参与者部署快照关系不唯一");
    }

    /**
     * 查询父流程部署的全部扩展执行快照。
     *
     * @param deploymentId String，父流程部署主键
     * @return List&lt;WfDeployExtensionSnapshot&gt;，不可变扩展快照
     */
    public List<WfDeployExtensionSnapshot> selectExtensionSnapshots(String deploymentId)
    {
        return sorted(readList(deploymentId, EXTENSIONS_RESOURCE, EXTENSION_LIST),
                Comparator.comparing(WfDeployExtensionSnapshot::getProcessKey)
                        .thenComparing(WfDeployExtensionSnapshot::getElementId));
    }

    /**
     * 精确查询一个 BPMN 元素的受控扩展快照。
     *
     * @param deploymentId String，父流程部署主键
     * @param processKey String，BPMN 可执行流程标识
     * @param elementId String，编译后稳定元素标识
     * @return WfDeployExtensionSnapshot，唯一扩展快照；不存在时返回 null
     */
    public WfDeployExtensionSnapshot selectExtensionSnapshot(String deploymentId,
            String processKey, String elementId)
    {
        String normalizedProcessKey = requireText(processKey, "流程标识不能为空");
        String normalizedElementId = requireText(elementId, "扩展元素标识不能为空");
        return uniqueOrNull(selectExtensionSnapshots(deploymentId).stream()
                .filter(snapshot -> normalizedProcessKey.equals(snapshot.getProcessKey())
                        && normalizedElementId.equals(snapshot.getElementId()))
                .toList(), "部署扩展快照关系不唯一");
    }

    /**
     * 查询父流程部署的全部冻结 DMN 快照。
     *
     * @param deploymentId String，父流程部署主键
     * @return List&lt;WfDeployDmnSnapshot&gt;，不可变 DMN 快照
     */
    public List<WfDeployDmnSnapshot> selectDmnSnapshots(String deploymentId)
    {
        return sorted(readList(deploymentId, DMN_RESOURCE, DMN_LIST),
                Comparator.comparing(WfDeployDmnSnapshot::getProcessKey)
                        .thenComparing(WfDeployDmnSnapshot::getElementId));
    }

    /**
     * 查询父流程部署的全部调用活动快照。
     *
     * @param deploymentId String，父流程部署主键
     * @return List&lt;WfDeployCallActivitySnapshot&gt;，不可变调用活动快照
     */
    public List<WfDeployCallActivitySnapshot> selectCallActivitySnapshots(String deploymentId)
    {
        return sorted(readList(deploymentId, CALL_ACTIVITIES_RESOURCE, CALL_ACTIVITY_LIST),
                Comparator.comparing(WfDeployCallActivitySnapshot::getProcessKey)
                        .thenComparing(WfDeployCallActivitySnapshot::getElementId));
    }

    /**
     * 查询父流程部署的全部审批 SLA 快照。
     *
     * @param deploymentId String，父流程部署主键
     * @return List&lt;WfDeployTaskSla&gt;，不可变 SLA 快照
     */
    public List<WfDeployTaskSla> selectTaskSlaSnapshots(String deploymentId)
    {
        return sorted(readList(deploymentId, SLA_RESOURCE, SLA_LIST),
                Comparator.comparing(WfDeployTaskSla::getProcessKey)
                        .thenComparing(WfDeployTaskSla::getTaskDefinitionKey));
    }

    /**
     * 精确查询一个审批任务的 SLA 不可变快照。
     *
     * @param deploymentId String，父流程部署主键
     * @param processKey String，BPMN 可执行流程标识
     * @param taskDefinitionKey String，审批任务节点标识
     * @return WfDeployTaskSla，唯一 SLA 快照；普通任务返回 null
     */
    public WfDeployTaskSla selectTaskSlaSnapshot(String deploymentId, String processKey,
            String taskDefinitionKey)
    {
        String normalizedProcessKey = requireText(processKey, "流程标识不能为空");
        String normalizedTaskKey = requireText(taskDefinitionKey, "审批任务节点标识不能为空");
        return uniqueOrNull(selectTaskSlaSnapshots(deploymentId).stream()
                .filter(snapshot -> normalizedProcessKey.equals(snapshot.getProcessKey())
                        && normalizedTaskKey.equals(snapshot.getTaskDefinitionKey()))
                .toList(), "审批 SLA 部署快照关系不唯一");
    }

    /**
     * 判断任一已发布部署资源是否引用目标表单。
     *
     * @param formIds Collection&lt;Long&gt;，待检查表单主键
     * @return boolean，任一表单快照命中时返回 true
     */
    public boolean hasFormReference(Collection<Long> formIds)
    {
        Set<Long> targets = formIds == null ? Set.of() : formIds.stream()
                .filter(id -> id != null && id > 0).collect(java.util.stream.Collectors.toSet());
        if (targets.isEmpty())
        {
            return false;
        }
        for (Deployment artifact : listArtifactDeployments())
        {
            if (readListFromArtifact(artifact, FORMS_RESOURCE, FORM_LIST).stream()
                    .map(WfDeployForm::getFormId).anyMatch(targets::contains))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * 统计已发布部署资源对指定扩展版本的引用数。
     *
     * @param versionIds Collection&lt;Long&gt;，一个扩展目录拥有的版本主键
     * @return int，扩展执行快照引用数量
     */
    public int countExtensionVersionReferences(Collection<Long> versionIds)
    {
        Set<Long> targets = versionIds == null ? Set.of() : versionIds.stream()
                .filter(id -> id != null && id > 0).collect(java.util.stream.Collectors.toSet());
        if (targets.isEmpty())
        {
            return 0;
        }
        int count = 0;
        for (Deployment artifact : listArtifactDeployments())
        {
            count += (int) readListFromArtifact(artifact, EXTENSIONS_RESOURCE, EXTENSION_LIST)
                    .stream().map(WfDeployExtensionSnapshot::getExtensionVersionId)
                    .filter(targets::contains).count();
        }
        return count;
    }

    /**
     * 统计已发布流程对指定来源 DMN 部署的冻结引用。
     *
     * @param sourceDeploymentId String，用户创建的 DMN 来源部署主键
     * @return long，冻结引用数量
     */
    public long countDmnSourceReferences(String sourceDeploymentId)
    {
        String normalized = requireText(sourceDeploymentId, "DMN 来源部署主键不能为空");
        long count = 0;
        for (Deployment artifact : listArtifactDeployments())
        {
            count += readListFromArtifact(artifact, DMN_RESOURCE, DMN_LIST).stream()
                    .filter(snapshot -> normalized.equals(snapshot.getSourceDeploymentId())).count();
        }
        return count;
    }

    /**
     * 读取并校验一个父部署的指定 JSON 列表资源。
     *
     * @param deploymentId String，父流程部署主键
     * @param resourceName String，受控资源名
     * @param type TypeReference&lt;List&lt;T&gt;&gt;，严格反序列化目标类型
     * @return List&lt;T&gt;，不存在资源时返回空列表
     */
    private <T> List<T> readList(String deploymentId, String resourceName,
            TypeReference<List<T>> type)
    {
        Deployment artifact = findArtifactDeployment(requireText(deploymentId,
                "流程部署主键不能为空"));
        return artifact == null ? List.of() : readListFromArtifact(artifact, resourceName, type);
    }

    /**
     * 从已经定位的业务资源子部署读取指定列表。
     *
     * @param artifact Deployment，业务资源子部署
     * @param resourceName String，资源名
     * @param type TypeReference&lt;List&lt;T&gt;&gt;，反序列化类型
     * @return List&lt;T&gt;，不可为空的独立对象列表
     */
    private <T> List<T> readListFromArtifact(Deployment artifact, String resourceName,
            TypeReference<List<T>> type)
    {
        verifyManifest(artifact);
        byte[] bytes = readResource(artifact.getId(), resourceName, true);
        try
        {
            List<T> values = objectMapper.readValue(bytes, type);
            return values == null ? List.of() : List.copyOf(values);
        }
        catch (JacksonException exception)
        {
            ServiceException failure = new ServiceException("流程部署业务资源无法解析", HttpStatus.ERROR);
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * 查找父部署唯一的业务资源子部署。
     *
     * @param parentDeploymentId String，父流程部署主键
     * @return Deployment，业务资源子部署；历史未托管部署返回 null
     */
    private Deployment findArtifactDeployment(String parentDeploymentId)
    {
        List<Deployment> deployments = repositoryService.createDeploymentQuery()
                .parentDeploymentId(parentDeploymentId)
                .deploymentCategory(ARTIFACT_CATEGORY)
                .list();
        if (deployments == null || deployments.isEmpty())
        {
            return null;
        }
        if (deployments.size() != 1)
        {
            throw new ServiceException("流程部署业务资源数量异常", HttpStatus.ERROR);
        }
        return deployments.get(0);
    }

    /**
     * 查询全部受控业务资源子部署，供低频删除保护使用。
     *
     * @return List&lt;Deployment&gt;，稳定按部署时间和主键排序的资源子部署
     */
    private List<Deployment> listArtifactDeployments()
    {
        List<Deployment> deployments = repositoryService.createDeploymentQuery()
                .deploymentCategory(ARTIFACT_CATEGORY)
                .orderByDeploymentTime().asc().orderByDeploymentId().asc().list();
        return deployments == null ? List.of() : List.copyOf(deployments);
    }

    /**
     * 校验资源协议清单，禁止未知版本被当前运行代码静默解释。
     *
     * @param artifact Deployment，业务资源子部署
     * @return void，清单缺失或版本不匹配时抛出数据错误
     */
    private void verifyManifest(Deployment artifact)
    {
        byte[] bytes = readResource(artifact.getId(), MANIFEST_RESOURCE, true);
        try
        {
            ArtifactManifest manifest = objectMapper.readValue(bytes, ArtifactManifest.class);
            if (manifest == null || manifest.schemaVersion() != SCHEMA_VERSION)
            {
                throw new ServiceException("流程部署业务资源协议版本不受支持", HttpStatus.ERROR);
            }
        }
        catch (JacksonException exception)
        {
            ServiceException failure = new ServiceException("流程部署业务资源清单无法解析", HttpStatus.ERROR);
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * 读取有大小上限的 Flowable 部署资源。
     *
     * @param artifactDeploymentId String，业务资源子部署主键
     * @param resourceName String，资源名
     * @param required boolean，缺失时是否按数据错误处理
     * @return byte[]，资源原始字节；非必需且缺失时返回空数组
     */
    private byte[] readResource(String artifactDeploymentId, String resourceName, boolean required)
    {
        try (InputStream stream = repositoryService.getResourceAsStream(
                artifactDeploymentId, resourceName))
        {
            if (stream == null)
            {
                if (required)
                {
                    throw new ServiceException("流程部署业务资源缺失", HttpStatus.ERROR);
                }
                return new byte[0];
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1)
            {
                if (read == 0)
                {
                    continue;
                }
                total += read;
                if (total > MAX_RESOURCE_BYTES)
                {
                    throw new ServiceException("流程部署业务资源大小超过限制", HttpStatus.ERROR);
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
        catch (IOException exception)
        {
            ServiceException failure = new ServiceException("流程部署业务资源读取失败", HttpStatus.ERROR);
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * 绑定父部署主键并复核各资源自然键唯一性。
     *
     * @param deploymentId String，父流程部署主键
     * @param artifacts WorkflowDeploymentArtifacts，待持久化资源
     * @return WorkflowDeploymentArtifacts，已经绑定部署主键的原资源集合
     */
    private WorkflowDeploymentArtifacts bindAndValidate(String deploymentId,
            WorkflowDeploymentArtifacts artifacts)
    {
        Date now = new Date();
        Set<String> formKeys = new HashSet<>();
        for (WfDeployForm snapshot : artifacts.forms())
        {
            requireSnapshot(snapshot, "部署表单快照不能为空");
            snapshot.setDeployId(deploymentId);
            if (snapshot.getCreateTime() == null) snapshot.setCreateTime(now);
            snapshot.setDelFlag("0");
            unique(formKeys, snapshot.getNodeKey() + "\u0000" + snapshot.getFormKey(),
                    "部署表单快照自然键重复");
        }

        Set<String> conditionKeys = new HashSet<>();
        for (WfDeployConditionRule snapshot : artifacts.conditionRules())
        {
            requireSnapshot(snapshot, "条件分支部署快照不能为空");
            snapshot.setDeployId(deploymentId);
            if (snapshot.getCreateTime() == null) snapshot.setCreateTime(now);
            unique(conditionKeys, snapshot.getProcessKey() + "\u0000" + snapshot.getGatewayId()
                    + "\u0000" + snapshot.getFlowId(), "条件分支部署快照自然键重复");
        }

        Set<String> loopKeys = new HashSet<>();
        for (WfDeployControlledLoop snapshot : artifacts.controlledLoops())
        {
            requireSnapshot(snapshot, "受控循环部署快照不能为空");
            snapshot.setDeployId(deploymentId);
            if (snapshot.getCreateTime() == null) snapshot.setCreateTime(now);
            unique(loopKeys, snapshot.getProcessKey() + "\u0000" + snapshot.getActivityId(),
                    "受控循环部署快照自然键重复");
        }

        Set<String> participantKeys = new HashSet<>();
        Set<Long> participantIds = new HashSet<>();
        for (WfDeployParticipantRule snapshot : artifacts.participantRules())
        {
            requireSnapshot(snapshot, "参与者规则部署快照不能为空");
            snapshot.setDeployId(deploymentId);
            if (snapshot.getCreateTime() == null) snapshot.setCreateTime(now);
            String naturalKey = snapshot.getProcessKey() + "\u0000" + snapshot.getRuleScope()
                    + "\u0000" + snapshot.getActivityId();
            unique(participantKeys, naturalKey, "参与者规则部署快照自然键重复");
            long ruleId = stablePositiveId(deploymentId, naturalKey, snapshot.getChecksum());
            if (!participantIds.add(ruleId))
            {
                throw new ServiceException("参与者规则稳定标识发生冲突", HttpStatus.ERROR);
            }
            snapshot.setRuleId(ruleId);
        }

        Set<String> extensionKeys = new HashSet<>();
        for (WfDeployExtensionSnapshot snapshot : artifacts.extensionSnapshots())
        {
            requireSnapshot(snapshot, "部署扩展快照不能为空");
            snapshot.setDeployId(deploymentId);
            if (snapshot.getCreateTime() == null) snapshot.setCreateTime(now);
            unique(extensionKeys, snapshot.getProcessKey() + "\u0000" + snapshot.getElementId(),
                    "部署扩展快照自然键重复");
        }

        Set<String> dmnKeys = new HashSet<>();
        for (WfDeployDmnSnapshot snapshot : artifacts.dmnSnapshots())
        {
            requireSnapshot(snapshot, "部署 DMN 快照不能为空");
            snapshot.setDeployId(deploymentId);
            if (snapshot.getCreateTime() == null) snapshot.setCreateTime(now);
            unique(dmnKeys, snapshot.getProcessKey() + "\u0000" + snapshot.getElementId(),
                    "部署 DMN 快照自然键重复");
        }

        Set<String> callKeys = new HashSet<>();
        for (WfDeployCallActivitySnapshot snapshot : artifacts.callActivitySnapshots())
        {
            requireSnapshot(snapshot, "调用活动部署快照不能为空");
            snapshot.setDeployId(deploymentId);
            if (snapshot.getCreateTime() == null) snapshot.setCreateTime(now);
            unique(callKeys, snapshot.getProcessKey() + "\u0000" + snapshot.getElementId(),
                    "调用活动部署快照自然键重复");
        }

        Set<String> slaKeys = new HashSet<>();
        for (WfDeployTaskSla snapshot : artifacts.taskSlaSnapshots())
        {
            requireSnapshot(snapshot, "审批 SLA 部署快照不能为空");
            snapshot.setDeploymentId(deploymentId);
            unique(slaKeys, snapshot.getProcessKey() + "\u0000" + snapshot.getTaskDefinitionKey(),
                    "审批 SLA 部署快照自然键重复");
        }
        return artifacts;
    }

    /**
     * 序列化一个受控部署资源并执行单资源大小校验。
     *
     * @param name String，固定资源名
     * @param value Object，待序列化不可变对象
     * @return ResourceBytes，资源名和独立字节数组
     */
    private ResourceBytes resource(String name, Object value)
    {
        try
        {
            byte[] bytes = objectMapper.writeValueAsBytes(value);
            if (bytes.length > MAX_RESOURCE_BYTES)
            {
                throw new ServiceException("流程部署业务资源大小超过限制", HttpStatus.BAD_REQUEST);
            }
            return new ResourceBytes(name, bytes);
        }
        catch (JacksonException exception)
        {
            ServiceException failure = new ServiceException("流程部署业务资源序列化失败", HttpStatus.ERROR);
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * 根据部署和规则自然键生成稳定正整数审计标识。
     *
     * @param deploymentId String，父流程部署主键
     * @param naturalKey String，规则自然键
     * @param checksum String，规则内容摘要
     * @return long，最高位清零且不为零的稳定规则标识
     */
    private long stablePositiveId(String deploymentId, String naturalKey, String checksum)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.join("\u0000", deploymentId, naturalKey,
                    checksum == null ? "" : checksum).getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int index = 0; index < Long.BYTES; index++)
            {
                value = (value << 8) | (hash[index] & 0xffL);
            }
            value &= Long.MAX_VALUE;
            return value == 0 ? 1 : value;
        }
        catch (NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("JVM 缺少 SHA-256", exception);
        }
    }

    /**
     * 校验资源对象不能为空。
     *
     * @param snapshot Object，资源对象
     * @param message String，稳定错误消息
     * @return void，对象为空时抛出数据错误
     */
    private void requireSnapshot(Object snapshot, String message)
    {
        if (snapshot == null)
        {
            throw new ServiceException(message, HttpStatus.ERROR);
        }
    }

    /**
     * 向自然键集合写入唯一值。
     *
     * @param keys Set&lt;String&gt;，已出现自然键
     * @param key String，本次自然键
     * @param message String，重复时稳定错误消息
     * @return void，重复时抛出冲突
     */
    private void unique(Set<String> keys, String key, String message)
    {
        if (!StringUtils.hasText(key) || !keys.add(key))
        {
            throw new ServiceException(message, HttpStatus.CONFLICT);
        }
    }

    /**
     * 对反序列化结果建立稳定只读顺序，保持原 Mapper 查询契约。
     *
     * @param values List&lt;T&gt;，部署资源中的对象列表
     * @param comparator Comparator&lt;T&gt;，原数据库查询对应的稳定排序规则
     * @return List&lt;T&gt;，排序后的不可变副本
     */
    private <T> List<T> sorted(List<T> values, Comparator<T> comparator)
    {
        return values.stream().sorted(comparator).toList();
    }

    /**
     * 将自然键查询结果收敛为零或一个对象，禁止损坏资源静默选择任意记录。
     *
     * @param values List&lt;T&gt;，精确自然键过滤结果
     * @param message String，关系重复时的稳定错误消息
     * @return T，唯一对象；不存在时返回 null
     */
    private <T> T uniqueOrNull(List<T> values, String message)
    {
        if (values.size() > 1)
        {
            throw new ServiceException(message, HttpStatus.ERROR);
        }
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * 规范化必填文本。
     *
     * @param value String，原始文本
     * @param message String，非法时稳定错误消息
     * @return String，去除首尾空白后的文本
     */
    private String requireText(String value, String message)
    {
        if (!StringUtils.hasText(value))
        {
            throw new ServiceException(message, HttpStatus.BAD_REQUEST);
        }
        return value.trim();
    }

    /**
     * 部署资源协议清单。
     *
     * @param schemaVersion int，当前资源结构版本
     */
    private record ArtifactManifest(int schemaVersion) { }

    /**
     * 已完成序列化的部署资源。
     *
     * @param name String，固定资源名
     * @param bytes byte[]，JSON 字节
     */
    private record ResourceBytes(String name, byte[] bytes) { }
}
