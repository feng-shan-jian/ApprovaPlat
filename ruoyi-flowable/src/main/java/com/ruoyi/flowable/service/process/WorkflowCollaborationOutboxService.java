package com.ruoyi.flowable.service.process;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.page.PageResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.config.WorkflowCollaborationProperties;
import com.ruoyi.flowable.domain.WfCollaborationOutbox;
import com.ruoyi.flowable.domain.dto.WorkflowOperationsQuery;
import com.ruoyi.flowable.domain.vo.WorkflowCollaborationOutboxView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.extension.WorkflowExtensionChecksum;
import com.ruoyi.flowable.extension.WorkflowExtensionJsonCanonicalizer;
import com.ruoyi.flowable.extension.WorkflowHttpConnector;
import com.ruoyi.flowable.extension.WorkflowHttpConnector.WorkflowHttpDeliveryResult;
import com.ruoyi.flowable.mapper.WfCollaborationOutboxMapper;
import com.ruoyi.flowable.service.support.WorkflowPageSupport;
import com.ruoyi.flowable.runtime.WorkflowCollaborationMetrics;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** SendTask 事务 outbox、严格顺序、租约重试、死信和人工补偿服务。 */
@Service
public class WorkflowCollaborationOutboxService
{
    private final WfCollaborationOutboxMapper mapper;
    private final WorkflowCollaborationChannelService channelService;
    private final WorkflowCollaborationAuditService auditService;
    private final WorkflowHttpConnector httpConnector;
    private final RepositoryService repositoryService;
    private final HistoryService historyService;
    private final WorkflowEngineOperations engineOperations;
    private final WorkflowCollaborationProperties properties;
    private final WorkflowCollaborationMetrics metrics;
    private final ObjectMapper objectMapper = JsonMapper.shared();

    /**
     * 创建协作 outbox 服务。
     * @param mapper WfCollaborationOutboxMapper，正式出站台账
     * @param channelService WorkflowCollaborationChannelService，通道顺序游标
     * @param auditService WorkflowCollaborationAuditService，逐次状态审计
     * @param httpConnector WorkflowHttpConnector，冻结端点和认证 HTTP 出口
     * @param repositoryService RepositoryService，流程定义到部署快照查询
     * @param historyService HistoryService，发送方自然完成与取消终态判定
     * @param engineOperations WorkflowEngineOperations，当前用户事务边界
     * @param properties WorkflowCollaborationProperties，批次、租约和退避上限
     * @param metrics WorkflowCollaborationMetrics，低基数运行指标
     * @return void，构造后由 Spring 管理
     */
    public WorkflowCollaborationOutboxService(WfCollaborationOutboxMapper mapper,
            WorkflowCollaborationChannelService channelService,
            WorkflowCollaborationAuditService auditService,
            WorkflowHttpConnector httpConnector, RepositoryService repositoryService,
            HistoryService historyService,
            WorkflowEngineOperations engineOperations, WorkflowCollaborationProperties properties,
            WorkflowCollaborationMetrics metrics)
    {
        this.mapper = mapper;
        this.channelService = channelService;
        this.auditService = auditService;
        this.httpConnector = httpConnector;
        this.repositoryService = repositoryService;
        this.historyService = historyService;
        this.engineOperations = engineOperations;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 在当前 Flowable 命令事务中冻结变量并登记唯一 outbox，禁止执行任何网络副作用。
     * @param execution DelegateExecution，当前 MessageFlow 源端 SendTask
     * @param config JsonNode，已复核部署快照配置
     * @return void，登记和流程推进原子提交
     */
    public void enqueue(DelegateExecution execution, JsonNode config)
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        {
            throw new ServiceException("协作消息 outbox 必须在 Flowable 写事务内登记", HttpStatus.ERROR);
        }
        ProcessDefinition definition = repositoryService.getProcessDefinition(
                execution.getProcessDefinitionId());
        if (definition == null || definition.getDeploymentId() == null)
        {
            throw new ServiceException("协作消息发送方流程定义不存在", HttpStatus.ERROR);
        }
        String targetKey = requiredText(config, "targetProcessDefinitionKey");
        String correlationKey = resolveCorrelation(execution, optionalText(config, "correlationVariable"));
        String messageId = UUID.nameUUIDFromBytes(String.join("\u0000", definition.getDeploymentId(),
                execution.getProcessInstanceId(), execution.getId(), execution.getCurrentActivityId())
                .getBytes(StandardCharsets.UTF_8)).toString();
        String variablesJson = variablesJson(execution, config.get("variableNames"));
        WfCollaborationOutbox existing = mapper.selectByIdForUpdate(messageId);
        if (existing != null)
        {
            String expectedHash = WorkflowExtensionChecksum.sha256(
                    requiredText(config, "messageName"), targetKey, correlationKey,
                    String.valueOf(existing.getSequenceNo()), variablesJson);
            if (!expectedHash.equals(existing.getPayloadSha256()))
            {
                throw new ServiceException("协作 outbox 幂等键已被不同载荷使用", HttpStatus.CONFLICT)
                        .setSubCode("COLLAB_OUTBOX_IDEMPOTENCY_CONFLICT");
            }
            return;
        }
        WorkflowCollaborationChannelService.Allocation allocation =
                channelService.allocateOutbound(targetKey, correlationKey);
        JsonNode httpEndpoint = config.get("httpEndpoint");
        JsonNode endpointSnapshot = httpEndpoint == null ? null : httpEndpoint.get("endpointSnapshot");
        if (endpointSnapshot == null || !endpointSnapshot.isObject())
        {
            throw new ServiceException("协作消息冻结 HTTP 端点不存在", HttpStatus.ERROR);
        }

        WfCollaborationOutbox row = new WfCollaborationOutbox();
        row.setMessageId(messageId);
        row.setChannelId(allocation.channelId());
        row.setSequenceNo(allocation.sequenceNo());
        row.setSourceProcessDefinitionKey(definition.getKey());
        row.setSourceProcessInstanceId(execution.getProcessInstanceId());
        row.setSourceExecutionId(execution.getId());
        row.setSourceElementId(execution.getCurrentActivityId());
        row.setMessageName(requiredText(config, "messageName"));
        row.setTargetProcessDefinitionKey(targetKey);
        row.setCorrelationKey(correlationKey);
        row.setEndpointId(requiredLong(endpointSnapshot, "endpointId"));
        row.setEndpointRevision(requiredInt(endpointSnapshot, "revisionNo"));
        row.setRequestPath(requiredText(config, "path"));
        row.setDeliveryConfigJson(canonical(httpEndpoint));
        row.setVariablesJson(variablesJson);
        row.setMaxAttempts(requiredInt(config, "maxAttempts"));
        row.setPayloadSha256(WorkflowExtensionChecksum.sha256(row.getMessageName(), targetKey,
                correlationKey, String.valueOf(allocation.sequenceNo()), variablesJson));

        if (mapper.insert(row) != 1)
        {
            throw new ServiceException("协作 outbox 登记不完整", HttpStatus.CONFLICT)
                    .setSubCode("COLLAB_OUTBOX_INSERT_FAILED");
        }
        auditService.record(messageId, "OUTBOUND", "ENQUEUE", "SYSTEM", "flowable",
                null, "PENDING", 0, null, "事务 outbox 已登记");
        metrics.record("enqueued");
    }

    /**
     * 在独立短事务中领取一条到期且没有未完成前序消息的 outbox。
     * @param workerId String，当前节点固定 worker 标识
     * @return WfCollaborationOutbox，领取成功行；无候选时为 null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public WfCollaborationOutbox claimNext(String workerId)
    {
        WfCollaborationOutbox candidate = mapper.selectNextDueForUpdate();
        if (candidate == null) return null;
        if (sourceInstanceWasCancelled(candidate.getSourceProcessInstanceId()))
        {
            // 自然结束的发送方仍应完成可靠投递；只有 Flowable 明确删除的取消/终止实例停止后续网络调用。
            if (mapper.cancel(candidate.getMessageId(), candidate.getRevisionNo()) != 1)
            {
                throw new ServiceException("协作 outbox 源实例取消状态提交冲突", HttpStatus.CONFLICT)
                        .setSubCode("COLLAB_OUTBOX_SOURCE_CANCEL_CONFLICT");
            }
            auditService.record(candidate.getMessageId(), "OUTBOUND", "CANCEL", "SYSTEM", workerId,
                    candidate.getStatus(), "CANCELLED", candidate.getAttemptCount(), null,
                    "发送方流程已取消，停止后续投递");
            metrics.record("cancelled");
            return null;
        }
        int leaseSeconds = Math.toIntExact(properties.getLeaseDuration().toSeconds());
        if (mapper.claim(candidate.getMessageId(), candidate.getRevisionNo(), workerId,
                leaseSeconds) != 1)
        {
            return null;
        }
        WfCollaborationOutbox claimed = mapper.selectByIdForUpdate(candidate.getMessageId());
        auditService.record(claimed.getMessageId(), "OUTBOUND", "CLAIM", "SYSTEM", workerId,
                candidate.getStatus(), "DELIVERING", claimed.getAttemptCount(), null,
                "后台 worker 已领取");
        return claimed;
    }

    /**
     * 判断发送方流程是否由取消、驳回或终止命令删除。
     * @param processInstanceId String，outbox 冻结的发送方流程实例主键
     * @return boolean，历史实例存在非空删除原因时返回 true；自然完成或仍运行返回 false
     */
    private boolean sourceInstanceWasCancelled(String processInstanceId)
    {
        HistoricProcessInstance source = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        return source != null && source.getDeleteReason() != null
                && !source.getDeleteReason().isBlank();
    }

    /**
     * 在事务外通过冻结端点发送一次 JSON；调用方随后必须提交成功或失败状态。
     * @param row WfCollaborationOutbox，已领取且持有租约的 outbox 快照
     * @return DeliveryOutcome，HTTP 与业务确认结果
     */
    public DeliveryOutcome deliver(WfCollaborationOutbox row)
    {
        byte[] body = requestBody(row);
        try
        {
            WorkflowHttpDeliveryResult result = httpConnector.postFrozenJson(
                    row.getDeliveryConfigJson(), row.getMessageId(), body);
            if (result.statusCode() < 200 || result.statusCode() >= 300)
            {
                return DeliveryOutcome.failure(result.statusCode(), "COLLAB_OUTBOX_HTTP_STATUS",
                        "接收端返回非成功 HTTP 状态");
            }
            JsonNode response = objectMapper.readTree(result.body());
            if (response == null || !response.isObject() || !response.has("code")
                    || response.get("code").intValue() != HttpStatus.SUCCESS)
            {
                return DeliveryOutcome.failure(result.statusCode(), "COLLAB_OUTBOX_REMOTE_REJECTED",
                        "接收端未返回持久化成功确认");
            }
            return DeliveryOutcome.success(result.statusCode());
        }
        catch (ServiceException exception)
        {
            return DeliveryOutcome.failure(null,
                    exception.getSubCode() == null ? "COLLAB_OUTBOX_DELIVERY_FAILED" : exception.getSubCode(),
                    exception.getMessage());
        }
        catch (JacksonException exception)
        {
            return DeliveryOutcome.failure(null, "COLLAB_OUTBOX_RESPONSE_INVALID",
                    "接收端确认正文不是合法 JSON");
        }
    }

    /**
     * 在独立事务中按租约和 revision 提交投递成功。
     * @param row WfCollaborationOutbox，领取时快照
     * @param workerId String，当前租约持有者
     * @param outcome DeliveryOutcome，成功 HTTP 结果
     * @return void，租约漂移时拒绝覆盖
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void completeSuccess(WfCollaborationOutbox row, String workerId, DeliveryOutcome outcome)
    {
        if (!outcome.success() || mapper.markProcessed(row.getMessageId(), workerId,
                row.getRevisionNo(), outcome.httpStatus()) != 1)
        {
            throw new ServiceException("协作 outbox 成功状态提交冲突", HttpStatus.CONFLICT)
                    .setSubCode("COLLAB_OUTBOX_LEASE_CONFLICT");
        }
        auditService.record(row.getMessageId(), "OUTBOUND", "DELIVER", "SYSTEM", workerId,
                "DELIVERING", "PROCESSED", row.getAttemptCount(), null, "接收端已持久化确认");
        metrics.record("processed");
    }

    /**
     * 在独立事务中按有界指数退避提交重试或死信。
     * @param row WfCollaborationOutbox，领取时快照
     * @param workerId String，当前租约持有者
     * @param outcome DeliveryOutcome，脱敏失败结果
     * @return void，租约漂移时拒绝覆盖
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void completeFailure(WfCollaborationOutbox row, String workerId, DeliveryOutcome outcome)
    {
        failClaim(row, workerId, outcome.httpStatus(), outcome.errorCode(), outcome.summary());
    }

    /**
     * 分页查询脱敏 outbox 管理记录。
     * @param query Collaboration，状态、关键字和创建时间范围
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数，最大 100
     * @return PageResult&lt;WorkflowCollaborationOutboxView&gt;，不含变量和端点认证字段
     */
    public PageResult<WorkflowCollaborationOutboxView> list(
            WorkflowOperationsQuery.Collaboration query, int pageNum, int pageSize)
    {
        WorkflowPageSupport.requireTimeRange(query.beginTime(), query.endTime());
        return engineOperations.read(() -> WorkflowPageSupport.query(pageNum, pageSize,
                () -> mapper.countList(query),
                (offset, size) -> mapper.selectList(query, offset, size).stream()
                        .map(this::toView).toList()));
    }

    /**
     * 管理员将死信重置为新的有界重试周期。
     * @param messageId String，outbox 消息主键
     * @return WorkflowCollaborationOutboxView，补偿后状态
     */
    public WorkflowCollaborationOutboxView compensate(String messageId)
    {
        return engineOperations.writeAsCurrentUser(identity ->
        {
            WfCollaborationOutbox row = requireLocked(messageId);
            if (!"DEAD_LETTER".equals(row.getStatus())
                    || mapper.compensate(row.getMessageId(), row.getRevisionNo(), identity.userId()) != 1)
            {
                throw new ServiceException("当前 outbox 状态不允许补偿", HttpStatus.CONFLICT);
            }
            auditService.record(row.getMessageId(), "OUTBOUND", "COMPENSATE", "USER",
                    identity.userId(), row.getStatus(), "RETRYING", 0, null, "管理员重新开启有界投递");
            metrics.record("compensated");
            return toView(mapper.selectById(row.getMessageId()));
        });
    }

    /**
     * 管理员取消尚未送达的 outbox，已送达消息不能撤销外部副作用。
     * @param messageId String，outbox 消息主键
     * @return WorkflowCollaborationOutboxView，取消后状态
     */
    public WorkflowCollaborationOutboxView cancel(String messageId)
    {
        return engineOperations.writeAsCurrentUser(identity ->
        {
            WfCollaborationOutbox row = requireLocked(messageId);
            if (mapper.cancel(row.getMessageId(), row.getRevisionNo()) != 1)
            {
                throw new ServiceException("当前 outbox 状态不允许取消", HttpStatus.CONFLICT);
            }
            auditService.record(row.getMessageId(), "OUTBOUND", "CANCEL", "USER",
                    identity.userId(), row.getStatus(), "CANCELLED", row.getAttemptCount(), null,
                    "管理员取消未送达消息");
            metrics.record("cancelled");
            return toView(mapper.selectById(row.getMessageId()));
        });
    }

    /**
     * 提交一次失败并根据当前次数选择 RETRYING 或 DEAD_LETTER。
     * @param row WfCollaborationOutbox，领取时快照
     * @param workerId String，租约持有者
     * @param httpStatus Integer，可空 HTTP 状态
     * @param errorCode String，稳定失败编码
     * @param summary String，脱敏摘要
     * @return void，更新失败时回滚审计
     */
    private void failClaim(WfCollaborationOutbox row, String workerId, Integer httpStatus,
            String errorCode, String summary)
    {
        boolean exhausted = row.getAttemptCount() >= row.getMaxAttempts();
        String target = exhausted ? "DEAD_LETTER" : "RETRYING";
        long delay = exhausted ? 0L : retryDelaySeconds(row.getAttemptCount());
        if (mapper.markFailed(row.getMessageId(), workerId, row.getRevisionNo(), target, delay,
                httpStatus, truncate(errorCode, 64), truncate(summary, 512)) != 1)
        {
            throw new ServiceException("协作 outbox 失败状态提交冲突", HttpStatus.CONFLICT)
                    .setSubCode("COLLAB_OUTBOX_LEASE_CONFLICT");
        }
        auditService.record(row.getMessageId(), "OUTBOUND",
                exhausted ? "DEAD_LETTER" : "RETRY", "SYSTEM", workerId,
                "DELIVERING", target, row.getAttemptCount(), truncate(errorCode, 64),
                exhausted ? "有界重试已耗尽" : "等待指数退避重试");
        metrics.record(exhausted ? "dead_letter" : "retry");
    }

    /**
     * 生成接收端正式请求 JSON，不包含端点密钥或内部租约字段。
     * @param row WfCollaborationOutbox，已领取消息
     * @return byte[]，UTF-8 JSON 正文
     */
    private byte[] requestBody(WfCollaborationOutbox row)
    {
        try
        {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("messageId", row.getMessageId());
            request.put("messageName", row.getMessageName());
            request.put("sourceProcessDefinitionKey", row.getSourceProcessDefinitionKey());
            request.put("targetProcessDefinitionKey", row.getTargetProcessDefinitionKey());
            request.put("correlationKey", row.getCorrelationKey());
            request.put("sequenceNo", row.getSequenceNo());
            request.set("variables", objectMapper.readTree(row.getVariablesJson()));
            request.put("maxAttempts", row.getMaxAttempts());
            return objectMapper.writeValueAsBytes(request);
        }
        catch (JacksonException exception)
        {
            throw new ServiceException("协作 outbox 请求正文损坏", HttpStatus.ERROR)
                    .setSubCode("COLLAB_OUTBOX_PAYLOAD_INVALID");
        }
    }

    /**
     * 从配置白名单冻结标量流程变量。
     * @param execution DelegateExecution，当前执行上下文
     * @param variablesNode JsonNode，规范变量名数组
     * @return String，键排序的规范 JSON
     */
    private String variablesJson(DelegateExecution execution, JsonNode variablesNode)
    {
        TreeMap<String, Object> variables = new TreeMap<>();
        if (variablesNode != null)
        {
            for (JsonNode node : variablesNode)
            {
                String name = node.textValue();
                if (!execution.hasVariable(name))
                {
                    throw new ServiceException("协作消息白名单流程变量不存在: " + name, HttpStatus.ERROR);
                }
                Object value = execution.getVariable(name);
                if (!(value == null || value instanceof String || value instanceof Number
                        || value instanceof Boolean))
                {
                    throw new ServiceException("协作消息流程变量不是受控标量: " + name, HttpStatus.ERROR);
                }
                variables.put(name, value);
            }
        }
        try
        {
            return WorkflowExtensionJsonCanonicalizer.canonicalize(
                    objectMapper.writeValueAsString(variables));
        }
        catch (JacksonException exception)
        {
            throw new ServiceException("协作消息流程变量无法序列化", HttpStatus.ERROR);
        }
    }

    /**
     * 读取配置的关联变量；未配置时使用发送实例业务键。
     * @param execution DelegateExecution，发送执行上下文
     * @param variable String，可空关联变量名
     * @return String，非空业务关联键
     */
    private String resolveCorrelation(DelegateExecution execution, String variable)
    {
        Object value = variable == null ? execution.getProcessInstanceBusinessKey()
                : execution.getVariable(variable);
        String correlation = value == null ? "" : String.valueOf(value).trim();
        if (correlation.isEmpty() || correlation.length() > 255
                || correlation.chars().anyMatch(Character::isISOControl))
        {
            throw new ServiceException("协作消息关联键不存在或不合法", HttpStatus.CONFLICT)
                    .setSubCode("COLLAB_OUTBOX_CORRELATION_INVALID");
        }
        return correlation;
    }

    /**
     * 计算从 2 秒开始且有上限的指数退避。
     * @param attempt int，已经开始的投递次数
     * @return long，下次领取前等待秒数
     */
    private long retryDelaySeconds(int attempt)
    {
        long exponential = 1L << Math.min(Math.max(attempt, 1), 20);
        return Math.min(properties.getMaxRetryDelay().toSeconds(), exponential);
    }

    /**
     * 锁定必须存在的 outbox。
     * @param messageId String，消息主键
     * @return WfCollaborationOutbox，事务内锁定行
     */
    private WfCollaborationOutbox requireLocked(String messageId)
    {
        if (messageId == null || messageId.isBlank())
        {
            throw new ServiceException("协作 outbox 主键不能为空", HttpStatus.BAD_REQUEST);
        }
        WfCollaborationOutbox row = mapper.selectByIdForUpdate(messageId.trim());
        if (row == null) throw new ServiceException("协作 outbox 不存在", HttpStatus.NOT_FOUND);
        return row;
    }

    /**
     * 转换为脱敏管理视图。
     * @param row WfCollaborationOutbox，正式台账行
     * @return WorkflowCollaborationOutboxView，不含变量和认证配置
     */
    private WorkflowCollaborationOutboxView toView(WfCollaborationOutbox row)
    {
        return new WorkflowCollaborationOutboxView(row.getMessageId(), row.getMessageName(),
                row.getSourceProcessDefinitionKey(), row.getSourceProcessInstanceId(),
                row.getTargetProcessDefinitionKey(), row.getCorrelationKey(), row.getSequenceNo(),
                row.getStatus(), row.getAttemptCount(), row.getMaxAttempts(),
                row.getCompensationCount(), row.getLastHttpStatus(), row.getLastErrorCode(),
                row.getLastErrorSummary(), row.getCreateTime(), row.getNextAttemptTime(),
                row.getCompleteTime());
    }

    /** @param node JsonNode，配置对象；@param name String，字段名；@return String，必填文本。 */
    private String requiredText(JsonNode node, String name)
    {
        String value = optionalText(node, name);
        if (value == null) throw new ServiceException("协作 outbox 配置字段缺失: " + name, HttpStatus.ERROR);
        return value;
    }

    /** @param node JsonNode，配置对象；@param name String，字段名；@return String，可空文本。 */
    private String optionalText(JsonNode node, String name)
    {
        JsonNode value = node == null ? null : node.get(name);
        return value != null && value.isTextual() && !value.textValue().isBlank()
                ? value.textValue().trim() : null;
    }

    /** @param node JsonNode，配置对象；@param name String，字段名；@return int，正整数。 */
    private int requiredInt(JsonNode node, String name)
    {
        JsonNode value = node == null ? null : node.get(name);
        if (value == null || !value.canConvertToInt() || value.intValue() <= 0)
            throw new ServiceException("协作 outbox 整数字段不合法: " + name, HttpStatus.ERROR);
        return value.intValue();
    }

    /** @param node JsonNode，配置对象；@param name String，字段名；@return long，正长整数。 */
    private long requiredLong(JsonNode node, String name)
    {
        JsonNode value = node == null ? null : node.get(name);
        if (value == null || !value.canConvertToLong() || value.longValue() <= 0)
            throw new ServiceException("协作 outbox 主键字段不合法: " + name, HttpStatus.ERROR);
        return value.longValue();
    }

    /** @param node JsonNode，JSON 节点；@return String，规范 JSON。 */
    private String canonical(JsonNode node)
    {
        try { return WorkflowExtensionJsonCanonicalizer.canonicalize(objectMapper.writeValueAsString(node)); }
        catch (JacksonException exception) { throw new ServiceException("协作 outbox 配置序列化失败", HttpStatus.ERROR); }
    }

    /** @param value String，可空文本；@param max int，最大长度；@return String，截断后的非空摘要。 */
    private String truncate(String value, int max)
    {
        String normalized = value == null || value.isBlank() ? "协作消息投递失败" : value;
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    /**
     * 单次出站投递结果。
     * @param success boolean，接收端是否确认持久化
     * @param httpStatus Integer，可空 HTTP 状态
     * @param errorCode String，可空稳定错误编码
     * @param summary String，可空脱敏摘要
     */
    public record DeliveryOutcome(boolean success, Integer httpStatus,
            String errorCode, String summary)
    {
        /** @param status int，成功 HTTP 状态；@return DeliveryOutcome，成功结果。 */
        public static DeliveryOutcome success(int status) { return new DeliveryOutcome(true, status, null, null); }
        /** @param status Integer，可空 HTTP 状态；@param code String，错误码；@param summary String，摘要；@return DeliveryOutcome，失败结果。 */
        public static DeliveryOutcome failure(Integer status, String code, String summary) { return new DeliveryOutcome(false, status, code, summary); }
    }
}
