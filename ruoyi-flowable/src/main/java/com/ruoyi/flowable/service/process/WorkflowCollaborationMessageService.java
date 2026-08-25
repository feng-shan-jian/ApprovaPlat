package com.ruoyi.flowable.service.process;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.eventsubscription.api.EventSubscription;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.ReceiveTask;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.page.PageResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfCollaborationMessage;
import com.ruoyi.flowable.domain.dto.WorkflowOperationsQuery;
import com.ruoyi.flowable.domain.dto.WorkflowCollaborationMessageRequest;
import com.ruoyi.flowable.domain.vo.WorkflowCollaborationMessageView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.mapper.WfCollaborationMessageMapper;
import com.ruoyi.flowable.service.support.WorkflowPageSupport;
import com.ruoyi.flowable.runtime.WorkflowCollaborationMetrics;
import com.ruoyi.flowable.service.process.WorkflowIntegrationCredentialService.AuthenticatedCredential;

/**
 * Participant 间消息投递服务：在同一事务登记幂等台账、唯一关联实例并消费 Flowable Message Catch。
 * 外部系统只通过集成 Token 调用，失败会进入有界重试，超过次数转入死信而不丢失审计事实。
 */
@Service
public class WorkflowCollaborationMessageService
{
    private static final int DEFAULT_MAX_ATTEMPTS = 5;
    private final WorkflowEngineOperations engineOperations;
    private final WorkflowIntegrationCredentialService credentialService;
    private final WfCollaborationMessageMapper mapper;
    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final WorkflowCollaborationChannelService channelService;
    private final WorkflowCollaborationAuditService auditService;
    private final WorkflowCollaborationMetrics metrics;
    private final ObjectMapper objectMapper = JsonMapper.shared();

    /**
     * 创建协作消息服务。
     * @param engineOperations WorkflowEngineOperations，统一事务与可信操作人边界
     * @param credentialService WorkflowIntegrationCredentialService，MESSAGE 范围认证
     * @param mapper WfCollaborationMessageMapper，正式协作消息台账
     * @param runtimeService RuntimeService，Flowable 消息消费 API
     * @param repositoryService RepositoryService，部署 BPMN 快照查询 API
     * @param channelService WorkflowCollaborationChannelService，入站顺序游标
     * @param auditService WorkflowCollaborationAuditService，逐次状态审计
     */
    public WorkflowCollaborationMessageService(WorkflowEngineOperations engineOperations,
            WorkflowIntegrationCredentialService credentialService,
            WfCollaborationMessageMapper mapper, RuntimeService runtimeService,
            RepositoryService repositoryService,
            WorkflowCollaborationChannelService channelService,
            WorkflowCollaborationAuditService auditService,
            WorkflowCollaborationMetrics metrics)
    {
        this.engineOperations = engineOperations;
        this.credentialService = credentialService;
        this.mapper = mapper;
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
        this.channelService = channelService;
        this.auditService = auditService;
        this.metrics = metrics;
    }

    /**
     * 发布并可靠消费一条跨流程消息。
     * @param plaintextToken String，X-Integration-Token 正文
     * @param request WorkflowCollaborationMessageRequest，消息、目标和关联键
     * @return WorkflowCollaborationMessageView，脱敏投递结果
     */
    public WorkflowCollaborationMessageView publish(String plaintextToken,
            WorkflowCollaborationMessageRequest request)
    {
        AuthenticatedCredential credential = credentialService.authenticateAndConsume(
                plaintextToken, "MESSAGE");
        Normalized normalized = normalize(request, credential.allowedVariables());
        WfCollaborationMessage row = new WfCollaborationMessage();
        row.setMessageId(normalized.messageId());
        row.setCredentialId(credential.credentialId());
        row.setActorUserId(credential.actorUserId());
        row.setChannelId(WorkflowCollaborationChannelService.channelId(
                normalized.targetProcessDefinitionKey(), normalized.correlationType(),
                normalized.correlationValue()));
        row.setSequenceNo(normalized.sequenceNo());
        row.setMessageName(normalized.messageName());
        row.setSourceProcessDefinitionKey(normalized.sourceProcessDefinitionKey());
        row.setTargetProcessDefinitionKey(normalized.targetProcessDefinitionKey());
        row.setCorrelationKey(normalized.correlationKey().isEmpty() ? null : normalized.correlationKey());
        row.setTargetProcessInstanceId(normalized.targetProcessInstanceId().isEmpty() ? null : normalized.targetProcessInstanceId());
        row.setVariablesJson(normalized.variablesJson());
        row.setPayloadSha256(normalized.payloadSha256());
        // 与正式表默认值保持同一对象状态，保证乱序分支审计不会因 Java 空值自动拆箱失败。
        row.setAttemptCount(0);
        row.setStatus("RECEIVED");
        row.setMaxAttempts(normalized.maxAttempts());
        return engineOperations.writeAsUser(credential.actorUserId(),
                () -> deliver(row, normalized.variables()));
    }

    /**
     * 分页查询协作入站消息脱敏台账，供管理和审计页面使用。
     * @param query Collaboration，状态、关键字和创建时间范围
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数，最大 100
     * @return PageResult&lt;WorkflowCollaborationMessageView&gt;，不含变量正文和 Token
     */
    public PageResult<WorkflowCollaborationMessageView> list(
            WorkflowOperationsQuery.Collaboration query, int pageNum, int pageSize)
    {
        WorkflowPageSupport.requireTimeRange(query.beginTime(), query.endTime());
        return engineOperations.read(() -> WorkflowPageSupport.query(pageNum, pageSize,
                () -> mapper.countList(query),
                (offset, size) -> mapper.selectList(query, offset, size).stream()
                        .map(this::toView).toList()));
    }

    /**
     * 对 RETRYING/DEAD_LETTER 消息执行一次受控补偿。
     * @param messageId String，消息幂等键
     * @return WorkflowCollaborationMessageView，补偿后的正式状态
     */
    public WorkflowCollaborationMessageView retry(String messageId)
    {
        if (messageId == null || messageId.isBlank())
        {
            throw new ServiceException("协作消息主键不能为空", HttpStatus.BAD_REQUEST);
        }
        return engineOperations.writeAsCurrentUser(identity ->
        {
            WfCollaborationMessage row = mapper.selectByIdForUpdate(messageId.trim());
            if (row == null)
            {
                throw new ServiceException("协作消息不存在", HttpStatus.NOT_FOUND);
            }
            if (!Set.of("RETRYING", "DEAD_LETTER").contains(row.getStatus()))
            {
                throw new ServiceException("当前消息状态不允许补偿", HttpStatus.CONFLICT);
            }
            String from = row.getStatus();
            if ("DEAD_LETTER".equals(from)
                    && mapper.markRetrying(row) != 1)
            {
                throw conflict("死信补偿状态更新不完整", "COLLAB_MESSAGE_RETRY_STATE_FAILED");
            }
            auditService.record(row.getMessageId(), "INBOUND", "COMPENSATE", "USER",
                    identity.userId(), from, "RETRYING", 0, null, "管理员重新开启有界消费");
            metrics.record("inbound_compensated");
            WfCollaborationMessage retrying = mapper.selectByIdForUpdate(row.getMessageId());
            return deliver(retrying, readVariables(retrying.getVariablesJson()));
        });
    }

    /**
     * 由后台 worker 在独立可重复读事务中自动重试一条到期且顺序就绪的入站消息。
     * @return boolean，找到候选并完成一次尝试时为 true
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ,
            rollbackFor = Exception.class)
    public boolean retryNextDue()
    {
        WfCollaborationMessage row = mapper.selectNextDueForUpdate();
        if (row == null) return false;
        deliver(row, readVariables(row.getVariablesJson()));
        return true;
    }

    /**
     * 在锁定台账行后执行唯一实例/订阅匹配和 Flowable 官方消息消费。
     * @param row WfCollaborationMessage，已规范化的台账行
     * @param variables Map<String,Object>，已通过凭据白名单的标量变量
     * @return WorkflowCollaborationMessageView，处理结果
     */
    private WorkflowCollaborationMessageView deliver(WfCollaborationMessage row,
            Map<String, Object> variables)
    {
        WfCollaborationMessage existing = mapper.selectByIdForUpdate(row.getMessageId());
        if (existing != null)
        {
            if (!samePayload(existing, row))
            {
                throw conflict("messageId 已被不同协作消息载荷使用", "COLLAB_MESSAGE_IDEMPOTENCY_CONFLICT");
            }
            if ("PROCESSED".equals(existing.getStatus()))
            {
                return toView(existing);
            }
            if ("DEAD_LETTER".equals(existing.getStatus())) return toView(existing);
            row = existing;
        }
        else
        {
            // 先创建并锁定通道，唯一序号和消息登记从第一笔写入起就在同一事务内受约束。
            channelService.lockInbound(row.getTargetProcessDefinitionKey(),
                    correlationType(row), correlationValue(row));
            if (mapper.insertReceived(row) != 1)
            {
                throw conflict("协作消息登记不完整", "COLLAB_MESSAGE_AUDIT_INSERT_FAILED");
            }
            auditService.record(row.getMessageId(), "INBOUND", "RECEIVE", "INTEGRATION",
                    String.valueOf(row.getCredentialId()), null, "RECEIVED", 0, null,
                    "接收端已持久化消息");
            metrics.record("inbound_received");
        }
        var channel = channelService.lockInbound(row.getTargetProcessDefinitionKey(),
                correlationType(row), correlationValue(row));
        long expectedSequence = Math.addExact(channel.getInboundSequence(), 1L);
        if (row.getSequenceNo() > expectedSequence)
        {
            if ("RECEIVED".equals(row.getStatus())) mapper.advanceWaitingOrder(row);
            auditService.record(row.getMessageId(), "INBOUND", "RETRY", "SYSTEM", "worker",
                    row.getStatus(), "RETRYING", row.getAttemptCount(),
                    "COLLAB_MESSAGE_OUT_OF_ORDER", "等待前序协作消息");
            return toView(mapper.selectById(row.getMessageId()));
        }
        if (row.getSequenceNo() < expectedSequence)
        {
            throw conflict("协作消息序号已经被通道消费", "COLLAB_MESSAGE_SEQUENCE_DUPLICATE");
        }
        int attempts = row.getAttemptCount() == null ? 0 : row.getAttemptCount();
        row.setAttemptCount(attempts + 1);
        try
        {
            ProcessInstance instance = resolveInstance(row);
            DeclaredTarget target = requireDeclaredMessageFlow(row, instance);
            String executionId = consume(instance, target, row.getMessageName(), variables);
            row.setMatchedProcessInstanceId(instance.getId());
            row.setTargetExecutionId(executionId);
            row.setStatus("PROCESSED");
            row.setLastErrorCode(null);
            row.setLastErrorSummary(null);
            if (mapper.markProcessed(row) != 1)
            {
                throw conflict("协作消息成功台账更新不完整", "COLLAB_MESSAGE_AUDIT_UPDATE_FAILED");
            }
            channelService.advanceInbound(channel, row.getSequenceNo());
            auditService.record(row.getMessageId(), "INBOUND", "DELIVER", "SYSTEM", "worker",
                    "RETRYING", "PROCESSED", row.getAttemptCount(), null,
                    "Flowable 等待节点已唯一消费");
            metrics.record("inbound_processed");
        }
        catch (ServiceException exception)
        {
            fail(row, exception.getSubCode() == null ? "COLLAB_MESSAGE_DELIVERY_FAILED" : exception.getSubCode(), exception.getMessage());
        }
        catch (RuntimeException exception)
        {
            fail(row, "COLLAB_MESSAGE_DELIVERY_FAILED", "Flowable 消息消费失败");
        }
        WfCollaborationMessage completed = mapper.selectById(row.getMessageId());
        return toView(completed == null ? row : completed);
    }

    /** 解析目标流程并拒绝不存在、挂起或多实例歧义。 */
    private ProcessInstance resolveInstance(WfCollaborationMessage row)
    {
        var query = runtimeService.createProcessInstanceQuery().processDefinitionKey(row.getTargetProcessDefinitionKey());
        List<ProcessInstance> instances = row.getTargetProcessInstanceId() != null && !row.getTargetProcessInstanceId().isBlank()
                ? query.processInstanceId(row.getTargetProcessInstanceId()).list()
                : query.processInstanceBusinessKey(row.getCorrelationKey()).list();
        if (instances.size() != 1)
        {
            throw conflict("协作消息未唯一关联接收流程实例", instances.isEmpty() ? "COLLAB_MESSAGE_INSTANCE_NOT_FOUND" : "COLLAB_MESSAGE_INSTANCE_AMBIGUOUS");
        }
        if (instances.get(0).isSuspended())
        {
            throw conflict("接收流程实例已挂起", "COLLAB_MESSAGE_INSTANCE_SUSPENDED");
        }
        return instances.get(0);
    }

    /**
     * 从接收实例的已部署 BPMN 快照复核 source/target 流程定义和消息名称，防止 API 绕过协作图关系。
     * @param row WfCollaborationMessage，外部请求冻结的协作关系
     * @param instance ProcessInstance，唯一接收实例
     * @return void，找不到声明关系时抛出冲突
     */
    private DeclaredTarget requireDeclaredMessageFlow(WfCollaborationMessage row,
            ProcessInstance instance)
    {
        var model = repositoryService.getBpmnModel(instance.getProcessDefinitionId());
        Map<String, String> elementProcesses = new LinkedHashMap<>();
        for (var process : model.getProcesses())
        {
            for (FlowElement element : process.findFlowElementsOfType(FlowElement.class, true))
            {
                elementProcesses.put(element.getId(), process.getId());
            }
        }
        return model.getMessageFlows() == null ? failUndeclared() : model.getMessageFlows().values().stream()
                .filter(flow -> row.getMessageName().equals(trim(flow.getName())))
                .filter(flow -> row.getSourceProcessDefinitionKey().equals(elementProcesses.get(flow.getSourceRef()))
                        && row.getTargetProcessDefinitionKey().equals(elementProcesses.get(flow.getTargetRef())))
                .map(flow -> new DeclaredTarget(flow.getTargetRef(),
                        model.getFlowElement(flow.getTargetRef()) instanceof ReceiveTask))
                .findFirst().orElseGet(this::failUndeclared);
    }

    /**
     * 按部署声明消费 Message Catch 或 ReceiveTask，二者都必须唯一匹配 execution。
     * @param instance ProcessInstance，唯一目标实例
     * @param target DeclaredTarget，部署快照中的目标活动
     * @param messageName String，BPMN 消息名
     * @param variables Map&lt;String,Object&gt;，白名单标量变量
     * @return String，被消费 execution 主键
     */
    private String consume(ProcessInstance instance, DeclaredTarget target, String messageName,
            Map<String, Object> variables)
    {
        if (target.receiveTask())
        {
            var executions = runtimeService.createExecutionQuery()
                    .processInstanceId(instance.getId()).activityId(target.elementId()).list();
            if (executions.size() != 1)
            {
                throw conflict("接收流程没有唯一匹配的 ReceiveTask", "COLLAB_RECEIVE_TASK_INVALID");
            }
            runtimeService.trigger(executions.get(0).getId(), variables);
            return executions.get(0).getId();
        }
        List<EventSubscription> subscriptions = runtimeService.createEventSubscriptionQuery()
                .eventType("message").eventName(messageName)
                .processInstanceId(instance.getId()).list();
        if (subscriptions.size() != 1 || subscriptions.get(0).getExecutionId() == null)
        {
            throw conflict("接收流程没有唯一匹配的消息等待节点", "COLLAB_MESSAGE_SUBSCRIPTION_INVALID");
        }
        EventSubscription subscription = subscriptions.get(0);
        runtimeService.messageEventReceived(messageName, subscription.getExecutionId(), variables);
        return subscription.getExecutionId();
    }

    /** @return DeclaredTarget，不会正常返回，仅供 Optional.orElseGet 抛出稳定异常。 */
    private DeclaredTarget failUndeclared()
    {
        throw conflict("协作消息未在已部署协作图中声明 source/target 关系",
                "COLLAB_MESSAGE_FLOW_NOT_DECLARED");
    }

    /** 记录失败、计算退避并在超过上限时转入死信。 */
    private void fail(WfCollaborationMessage row, String code, String summary)
    {
        row.setLastErrorCode(code);
        row.setLastErrorSummary(summary == null ? "协作消息投递失败" : summary.substring(0, Math.min(512, summary.length())));
        int max = row.getMaxAttempts() == null ? DEFAULT_MAX_ATTEMPTS : row.getMaxAttempts();
        if ((row.getAttemptCount() == null ? 0 : row.getAttemptCount()) >= max)
        {
            row.setStatus("DEAD_LETTER");
            mapper.markDeadLetter(row);
            auditService.record(row.getMessageId(), "INBOUND", "DEAD_LETTER", "SYSTEM", "worker",
                    "RETRYING", "DEAD_LETTER", row.getAttemptCount(), row.getLastErrorCode(),
                    "有界消费重试已耗尽");
            metrics.record("inbound_dead_letter");
        }
        else
        {
            row.setStatus("RETRYING");
            mapper.markFailed(row);
            auditService.record(row.getMessageId(), "INBOUND", "RETRY", "SYSTEM", "worker",
                    "RECEIVED", "RETRYING", row.getAttemptCount(), row.getLastErrorCode(),
                    "等待指数退避重试");
            metrics.record("inbound_retry");
        }
    }

    /** 规范请求并校验凭据变量白名单和关联条件互斥性。 */
    private Normalized normalize(WorkflowCollaborationMessageRequest request, Set<String> allowed)
    {
        if (request == null || request.messageId() == null || request.messageName() == null
                || request.targetProcessDefinitionKey() == null)
        {
            throw new ServiceException("协作消息请求不完整", HttpStatus.BAD_REQUEST);
        }
        String instance = trim(request.targetProcessInstanceId());
        String key = trim(request.correlationKey());
        if (instance.isEmpty() == key.isEmpty())
        {
            throw new ServiceException("targetProcessInstanceId 和 correlationKey 必须且只能提供一个", HttpStatus.BAD_REQUEST);
        }
        TreeMap<String, Object> variables = new TreeMap<>();
        variables.putAll(request.variables() == null ? Map.of() : request.variables());
        for (Map.Entry<String, Object> entry : variables.entrySet())
        {
            if (!allowed.contains(entry.getKey()) || !(entry.getValue() == null || entry.getValue() instanceof String || entry.getValue() instanceof Number || entry.getValue() instanceof Boolean))
            {
                throw new ServiceException("协作消息变量未通过凭据白名单或标量约束", HttpStatus.FORBIDDEN);
            }
        }
        String json;
        try { json = objectMapper.writeValueAsString(variables); }
        catch (Exception exception) { throw new ServiceException("协作消息变量序列化失败", HttpStatus.BAD_REQUEST); }
        String targetKey = request.targetProcessDefinitionKey().trim();
        String correlationType = instance.isEmpty() ? "BUSINESS_KEY" : "PROCESS_INSTANCE";
        String correlationValue = instance.isEmpty() ? key : instance;
        long sequenceNo = request.sequenceNo();
        String payloadHash = sha256(String.join("\u0000", request.messageName().trim(),
                trim(request.sourceProcessDefinitionKey()), targetKey, correlationType,
                correlationValue, String.valueOf(sequenceNo), json));
        return new Normalized(request.messageId().trim(), request.messageName().trim(),
                trim(request.sourceProcessDefinitionKey()), targetKey, key, instance,
                correlationType, correlationValue, sequenceNo, variables, json, payloadHash,
                request.maxAttempts() == null ? DEFAULT_MAX_ATTEMPTS : request.maxAttempts());
    }

    private Map<String, Object> readVariables(String json)
    {
        try { return objectMapper.readValue(json, Map.class); }
        catch (Exception exception) { throw new ServiceException("协作消息变量台账损坏", HttpStatus.CONFLICT); }
    }
    private boolean samePayload(WfCollaborationMessage a, WfCollaborationMessage b)
    {
        return a.getPayloadSha256().equals(b.getPayloadSha256())
                && a.getSequenceNo().equals(b.getSequenceNo())
                && a.getMessageName().equals(b.getMessageName())
                && a.getTargetProcessDefinitionKey().equals(b.getTargetProcessDefinitionKey())
                && trim(a.getCorrelationKey()).equals(trim(b.getCorrelationKey()))
                && trim(a.getTargetProcessInstanceId()).equals(trim(b.getTargetProcessInstanceId()));
    }
    private WorkflowCollaborationMessageView toView(WfCollaborationMessage row)
    {
        if (row == null) return null;
        return new WorkflowCollaborationMessageView(row.getMessageId(), row.getMessageName(), row.getSourceProcessDefinitionKey(), row.getTargetProcessDefinitionKey(), row.getCorrelationKey(), row.getTargetProcessInstanceId(), row.getMatchedProcessInstanceId(), row.getTargetExecutionId(), row.getSequenceNo(), row.getStatus(), row.getAttemptCount(), row.getMaxAttempts(), row.getCompensationCount(), row.getLastErrorCode(), row.getLastErrorSummary(), row.getCreateTime(), row.getNextAttemptTime(), row.getCompleteTime());
    }
    private String sha256(String value)
    {
        try { byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder out = new StringBuilder(64); for (byte b : bytes) out.append(String.format("%02x", b)); return out.toString(); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private String trim(String value) { return value == null ? "" : value.trim(); }
    private ServiceException conflict(String message, String code) { return new ServiceException(message, HttpStatus.CONFLICT).setSubCode(code); }
    private String correlationType(WfCollaborationMessage row) { return row.getCorrelationKey() == null ? "PROCESS_INSTANCE" : "BUSINESS_KEY"; }
    private String correlationValue(WfCollaborationMessage row) { return row.getCorrelationKey() == null ? row.getTargetProcessInstanceId() : row.getCorrelationKey(); }
    private record DeclaredTarget(String elementId, boolean receiveTask) { }
    private record Normalized(String messageId, String messageName, String sourceProcessDefinitionKey, String targetProcessDefinitionKey, String correlationKey, String targetProcessInstanceId, String correlationType, String correlationValue, long sequenceNo, Map<String,Object> variables, String variablesJson, String payloadSha256, int maxAttempts) { }
}
