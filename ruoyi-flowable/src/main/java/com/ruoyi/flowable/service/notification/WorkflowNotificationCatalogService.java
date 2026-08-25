package com.ruoyi.flowable.service.notification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.framework.web.service.PermissionService;

/**
 * 审批通知流程与节点目录服务。
 *
 * 当前权限模型把 {@code workflow:notification:manage} 定义为全部已部署流程的粗粒度
 * 管理权，不使用流程发起人 identity link 冒充管理授权。所有目录读取和策略作用域
 * 复核均重新检查该实时权限，并只信任 Flowable RepositoryService 的部署事实。
 */
@Service
public class WorkflowNotificationCatalogService
{
    /** 审批通知策略及其流程目录的统一实时管理权限。 */
    private static final String MANAGE_PERMISSION = "workflow:notification:manage";
    private static final int MAX_DEFINITION_KEY_LENGTH = 255;

    private final RepositoryService repositoryService;
    private final WorkflowEngineOperations engineOperations;
    private final PermissionService permissionService;

    /**
     * 创建审批通知流程与节点目录服务。
     *
     * @param repositoryService RepositoryService，真实部署定义和 BPMN 模型查询入口
     * @param engineOperations WorkflowEngineOperations，Flowable 只读事务和异常翻译边界
     * @param permissionService PermissionService，工作流权限实时主数据复核入口
     * @return 无返回值，构造后由 Spring 管理该服务
     */
    public WorkflowNotificationCatalogService(RepositoryService repositoryService,
            WorkflowEngineOperations engineOperations, PermissionService permissionService)
    {
        this.repositoryService = repositoryService;
        this.engineOperations = engineOperations;
        this.permissionService = permissionService;
    }

    /**
     * 查询当前管理员可管理的最新激活流程定义目录。
     *
     * @return List&lt;ProcessOption&gt;，按流程 key 排序的真实最新激活定义
     */
    public List<ProcessOption> processes()
    {
        return engineOperations.read(() ->
        {
            requireManagePermission();
            List<ProcessDefinition> definitions = repositoryService
                    .createProcessDefinitionQuery()
                    .latestVersion()
                    .active()
                    .orderByProcessDefinitionKey()
                    .asc()
                    .list();
            if (definitions == null)
            {
                throw dataError("审批通知流程目录查询异常");
            }

            // 当前没有流程级管理 ACL；同 key 的多租户定义会使后续策略作用域含义不唯一，必须失败关闭。
            Map<String, ProcessOption> unique = new LinkedHashMap<>();
            for (ProcessDefinition definition : definitions)
            {
                requireDefinitionIntegrity(definition);
                String processKey = definition.getKey().trim();
                ProcessOption previous = unique.putIfAbsent(processKey,
                        new ProcessOption(processKey, displayName(definition.getName(), processKey),
                                definition.getVersion()));
                if (previous != null)
                {
                    throw ambiguousProcessKey(processKey);
                }
            }
            return List.copyOf(unique.values());
        });
    }

    /**
     * 查询指定流程最新激活定义中的全部真实 UserTask 节点。
     *
     * @param processDefinitionKey String，客户端从流程目录选择的流程定义 key
     * @return List&lt;NodeOption&gt;，递归包含嵌套 SubProcess 且按节点 key 排序的用户任务目录
     */
    public List<NodeOption> nodes(String processDefinitionKey)
    {
        return engineOperations.read(() ->
        {
            requireManagePermission();
            ProcessDefinition definition = requireUniqueLatestActiveDefinition(
                    normalizedKey(processDefinitionKey, "流程标识不合法"));
            org.flowable.bpmn.model.Process process = requireDeployedProcess(definition);
            List<UserTask> tasks = process.findFlowElementsOfType(UserTask.class, true);
            if (tasks == null)
            {
                throw dataError("审批通知节点目录查询异常");
            }

            // BPMN 元素 ID 是策略持久化自然键；重复 ID 不能通过任意选一继续保存。
            Map<String, NodeOption> unique = new java.util.TreeMap<>();
            for (UserTask task : tasks)
            {
                if (task == null)
                {
                    throw dataError("审批通知节点目录数据不完整");
                }
                String taskKey = normalizedDeployedKey(task.getId(), "审批通知节点目录数据不完整");
                NodeOption previous = unique.putIfAbsent(taskKey,
                        new NodeOption(taskKey, displayName(task.getName(), taskKey)));
                if (previous != null)
                {
                    throw new ServiceException("流程包含重复的用户任务标识: " + taskKey,
                            HttpStatus.CONFLICT);
                }
            }
            return List.copyOf(unique.values());
        });
    }

    /**
     * 重新校验待保存通知策略的作用域与真实部署事实。
     *
     * @param scopeType String，DEFAULT、PROCESS 或 NODE
     * @param processDefinitionKey String，PROCESS/NODE 作用域选择的真实流程 key
     * @param taskDefinitionKey String，NODE 作用域选择的真实 UserTask key
     * @return void，权限、字段组合和部署事实均合法时正常返回
     */
    public void validateScope(String scopeType, String processDefinitionKey,
            String taskDefinitionKey)
    {
        engineOperations.read(() ->
        {
            requireManagePermission();
            String scope = scopeType == null ? ""
                    : scopeType.trim().toUpperCase(Locale.ROOT);
            switch (scope)
            {
                case "DEFAULT" ->
                {
                    if (StringUtils.hasText(processDefinitionKey)
                            || StringUtils.hasText(taskDefinitionKey))
                    {
                        throw invalid("全局通知策略不能指定流程或节点");
                    }
                }
                case "PROCESS" ->
                {
                    if (StringUtils.hasText(taskDefinitionKey))
                    {
                        throw invalid("流程通知策略不能指定节点");
                    }
                    requireUniqueLatestActiveDefinition(
                            normalizedKey(processDefinitionKey, "流程标识不合法"));
                }
                case "NODE" ->
                {
                    ProcessDefinition definition = requireUniqueLatestActiveDefinition(
                            normalizedKey(processDefinitionKey, "流程标识不合法"));
                    String taskKey = normalizedKey(taskDefinitionKey, "节点标识不合法");
                    FlowElement element = requireDeployedProcess(definition)
                            .getFlowElement(taskKey, true);
                    if (!(element instanceof UserTask))
                    {
                        throw invalid("通知策略节点不存在或不是用户任务");
                    }
                }
                default -> throw invalid("通知策略作用域不合法");
            }
            return null;
        });
    }

    /**
     * 按流程 key 唯一定位最新激活定义，拒绝不存在、停用或跨租户歧义。
     *
     * @param processDefinitionKey String，已经规范化的流程定义 key
     * @return ProcessDefinition，唯一最新激活真实定义
     */
    private ProcessDefinition requireUniqueLatestActiveDefinition(String processDefinitionKey)
    {
        List<ProcessDefinition> definitions = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(processDefinitionKey)
                .latestVersion()
                .active()
                .list();
        if (definitions == null)
        {
            throw dataError("审批通知流程目录查询异常");
        }
        if (definitions.isEmpty())
        {
            throw new ServiceException("流程不存在、未部署或未启用", HttpStatus.NOT_FOUND);
        }
        if (definitions.size() != 1)
        {
            throw ambiguousProcessKey(processDefinitionKey);
        }
        ProcessDefinition definition = definitions.get(0);
        requireDefinitionIntegrity(definition);
        if (!processDefinitionKey.equals(definition.getKey()))
        {
            throw dataError("审批通知流程目录关联异常");
        }
        return definition;
    }

    /**
     * 读取并核验指定定义对应的真实部署 BPMN Process。
     *
     * @param definition ProcessDefinition，已唯一定位的最新激活定义
     * @return org.flowable.bpmn.model.Process，与定义 key 对应的部署流程模型
     */
    private org.flowable.bpmn.model.Process requireDeployedProcess(ProcessDefinition definition)
    {
        BpmnModel model = repositoryService.getBpmnModel(definition.getId());
        if (model == null)
        {
            throw dataError("审批通知流程模型不存在");
        }
        org.flowable.bpmn.model.Process process = model.getProcessById(definition.getKey());
        if (process == null)
        {
            throw dataError("审批通知流程模型关联异常");
        }
        return process;
    }

    /**
     * 核验 Flowable 定义具备目录所需的稳定部署字段。
     *
     * @param definition ProcessDefinition，RepositoryService 返回的真实定义
     * @return void，定义 key、主键、部署主键和版本完整时正常返回
     */
    private void requireDefinitionIntegrity(ProcessDefinition definition)
    {
        if (definition == null || !StringUtils.hasText(definition.getId())
                || !StringUtils.hasText(definition.getDeploymentId())
                || definition.getVersion() < 1)
        {
            throw dataError("审批通知流程目录数据不完整");
        }
        normalizedDeployedKey(definition.getKey(), "审批通知流程目录数据不完整");
    }

    /**
     * 实时复核审批通知管理权限。
     *
     * @return void，当前登录人仍具备粗粒度全流程管理权时正常返回
     */
    private void requireManagePermission()
    {
        if (!permissionService.hasPermi(MANAGE_PERMISSION))
        {
            throw new ServiceException("无权管理审批通知策略", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 规范化客户端提交的流程或节点 key。
     *
     * @param value String，客户端目录选择结果
     * @param message String，非法值稳定提示
     * @return String，去除首尾空白且不含空白或控制字符的 key
     */
    private String normalizedKey(String value, String message)
    {
        if (!StringUtils.hasText(value))
        {
            throw invalid(message);
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_DEFINITION_KEY_LENGTH
                || normalized.codePoints().anyMatch(character ->
                        Character.isWhitespace(character) || Character.isISOControl(character)))
        {
            throw invalid(message);
        }
        return normalized;
    }

    /**
     * 规范化部署中读取的流程或节点 key，并把损坏事实归类为服务端数据错误。
     *
     * @param value String，Flowable 部署中保存的 key
     * @param message String，损坏事实稳定提示
     * @return String，可作为目录自然键的部署值
     */
    private String normalizedDeployedKey(String value, String message)
    {
        try
        {
            return normalizedKey(value, message);
        }
        catch (ServiceException exception)
        {
            throw dataError(message);
        }
    }

    /**
     * 生成目录展示名，部署未填写名称时回退到稳定 key。
     *
     * @param name String，Flowable 定义或 UserTask 名称
     * @param fallback String，稳定流程或节点 key
     * @return String，可直接展示且已去除首尾空白的名称
     */
    private String displayName(String name, String fallback)
    {
        return StringUtils.hasText(name) ? name.trim() : fallback;
    }

    /**
     * 创建跨租户同 key 歧义冲突。
     *
     * @param processDefinitionKey String，无法唯一定位的流程 key
     * @return ServiceException，稳定 HTTP 409 业务异常
     */
    private ServiceException ambiguousProcessKey(String processDefinitionKey)
    {
        return new ServiceException("流程标识存在多个租户部署，无法唯一管理: "
                + processDefinitionKey, HttpStatus.CONFLICT);
    }

    /**
     * 创建客户端目录选择或作用域字段错误。
     *
     * @param message String，可返回调用方的稳定提示
     * @return ServiceException，稳定 HTTP 400 业务异常
     */
    private ServiceException invalid(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 创建部署目录损坏或引擎返回异常的数据错误。
     *
     * @param message String，不包含 BPMN 正文的稳定提示
     * @return ServiceException，稳定 HTTP 500 业务异常
     */
    private ServiceException dataError(String message)
    {
        return new ServiceException(message, HttpStatus.ERROR);
    }

    /**
     * 流程目录条目。
     *
     * @param processDefinitionKey String，真实 Flowable 流程定义 key
     * @param processName String，流程名称；部署未命名时为流程 key
     * @param version int，最新激活定义版本
     */
    public record ProcessOption(String processDefinitionKey, String processName, int version)
    {
    }

    /**
     * 用户任务节点目录条目。
     *
     * @param taskDefinitionKey String，真实 UserTask 元素 ID
     * @param taskName String，节点名称；部署未命名时为节点 key
     */
    public record NodeOption(String taskDefinitionKey, String taskName)
    {
    }
}
