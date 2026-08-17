package com.ruoyi.flowable.service.process;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.ReceiveTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.eventsubscription.api.EventSubscription;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfRuntimeEventRequest;
import com.ruoyi.flowable.domain.dto.WorkflowOperationsQuery;
import com.ruoyi.flowable.domain.dto.WorkflowRuntimeEventRequest;
import com.ruoyi.flowable.domain.vo.WorkflowRuntimeEventView;
import com.ruoyi.flowable.domain.vo.WorkflowPageResult;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.extension.WorkflowExtensionChecksum;
import com.ruoyi.flowable.mapper.WfRuntimeEventRequestMapper;
import com.ruoyi.flowable.service.support.WorkflowPageSupport;
import com.ruoyi.flowable.service.process.WorkflowIntegrationCredentialService.AuthenticatedCredential;

/**
 * 外部消息、信号和 ReceiveTask 的认证、唯一关联、幂等执行与审计服务。
 */
@Service
public class WorkflowRuntimeEventService
{
    private static final int MAX_STRING_VALUE_LENGTH = 4096;
    private static final int MAX_NUMBER_TEXT_LENGTH = 128;

    private final WorkflowEngineOperations engineOperations;
    private final WorkflowIntegrationCredentialService credentialService;
    private final WorkflowRuntimeEventAuditService auditService;
    private final WfRuntimeEventRequestMapper requestMapper;
    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;

    /**
     * 创建运行事件领域服务。
     * @param engineOperations WorkflowEngineOperations，Flowable 事务和可信操作人边界
     * @param credentialService WorkflowIntegrationCredentialService，Token、范围和限流认证
     * @param auditService WorkflowRuntimeEventAuditService，主事务失败后的独立审计
     * @param requestMapper WfRuntimeEventRequestMapper，正式幂等台账 Mapper
     * @param runtimeService RuntimeService，Flowable 8 运行时公共 API
     * @param repositoryService RepositoryService，ReceiveTask 模型类型复核
     * @return void，构造后由 Spring 管理
     */
    public WorkflowRuntimeEventService(WorkflowEngineOperations engineOperations,
            WorkflowIntegrationCredentialService credentialService,
            WorkflowRuntimeEventAuditService auditService,
            WfRuntimeEventRequestMapper requestMapper, RuntimeService runtimeService,
            RepositoryService repositoryService)
    {
        this.engineOperations = engineOperations;
        this.credentialService = credentialService;
        this.auditService = auditService;
        this.requestMapper = requestMapper;
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
    }

    /**
     * 分页查询正式运行事件台账。
     * @param query RuntimeEvent，状态、事件类型、关联类型、关键字和时间范围
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数，最大 100
     * @return WorkflowPageResult&lt;WorkflowRuntimeEventView&gt;，不含 Token 和变量正文
     */
    public WorkflowPageResult<WorkflowRuntimeEventView> list(
            WorkflowOperationsQuery.RuntimeEvent query, int pageNum, int pageSize)
    {
        WorkflowPageSupport.requireTimeRange(query.beginTime(), query.endTime());
        return engineOperations.read(() -> WorkflowPageSupport.query(pageNum, pageSize,
                () -> requestMapper.countList(query),
                (offset, size) -> requestMapper.selectList(query, offset, size).stream()
                        .map(this::toView).toList()));
    }

    /**
     * 发布一个受 Token 认证和幂等保护的运行事件。
     * @param plaintextToken String，X-Integration-Token 请求头正文
     * @param eventType String，MESSAGE、SIGNAL 或 RECEIVE
     * @param request WorkflowRuntimeEventRequest，事件、关联条件和白名单变量
     * @return WorkflowRuntimeEventView，唯一匹配和稳定处理结果
     */
    public WorkflowRuntimeEventView publish(String plaintextToken, String eventType,
            WorkflowRuntimeEventRequest request)
    {
        NormalizedEvent normalized = normalize(eventType, request);
        AuthenticatedCredential credential = credentialService.authenticateAndConsume(
                plaintextToken, normalized.eventType());
        WfRuntimeEventRequest row = buildRow(normalized, credential.credentialId());
        try
        {
            validateVariables(normalized.variables(), credential.allowedVariables());
            return engineOperations.writeAsUser(credential.actorUserId(), () -> process(row,
                    normalized.variables()));
        }
        catch (DuplicateKeyException exception)
        {
            // 并发相同 requestId 只有一个事务能插入；提交后重新读取并执行严格重放判断。
            return resolveConcurrentReplay(row);
        }
        catch (ServiceException exception)
        {
            auditFailure(row, exception);
            throw exception;
        }
        catch (RuntimeException exception)
        {
            ServiceException failure = new ServiceException("运行事件处理失败", HttpStatus.ERROR)
                    .setSubCode("RUNTIME_EVENT_INTERNAL_FAILURE");
            failure.initCause(exception);
            auditFailure(row, failure);
            throw failure;
        }
    }

    /**
     * 在同一 Flowable 事务内登记请求、唯一匹配、消费事件并写成功台账。
     * @param row WfRuntimeEventRequest，规范请求台账行
     * @param variables Map&lt;String,Object&gt;，已通过白名单和类型校验的变量
     * @return WorkflowRuntimeEventView，成功或已成功重放视图
     */
    private WorkflowRuntimeEventView process(WfRuntimeEventRequest row,
            Map<String, Object> variables)
    {
        WfRuntimeEventRequest existing = requestMapper.selectByIdForUpdate(row.getRequestId());
        if (existing != null)
        {
            return replay(existing, row);
        }
        if (requestMapper.insertReceived(row) != 1)
        {
            throw new ServiceException("运行事件请求登记不完整", HttpStatus.CONFLICT);
        }

        ProcessInstance instance = resolveUniqueInstance(row);
        Match match = switch (row.getEventType())
        {
            case "MESSAGE" -> consumeSubscription(instance, row, variables, "message");
            case "SIGNAL" -> consumeSubscription(instance, row, variables, "signal");
            case "RECEIVE" -> consumeReceiveTask(instance, row, variables);
            default -> throw new ServiceException("运行事件类型不受支持", HttpStatus.BAD_REQUEST);
        };
        row.setMatchedProcessInstanceId(instance.getId());
        row.setMatchedExecutionId(match.executionId());
        row.setStatus("PROCESSED");
        row.setResultCode("EVENT_PROCESSED");
        row.setResultSummary("运行事件已由唯一等待执行消费");
        if (requestMapper.markProcessed(row) != 1)
        {
            throw new ServiceException("运行事件成功台账更新不完整", HttpStatus.CONFLICT);
        }
        // MyBatis 更新不会回填数据库默认时间，重新读取确保首次响应和幂等重放返回同一正式审计事实。
        WfRuntimeEventRequest processed = requestMapper.selectById(row.getRequestId());
        if (processed == null)
        {
            throw new ServiceException("运行事件成功台账读取不完整", HttpStatus.CONFLICT);
        }
        return toView(processed);
    }

    /**
     * 解析流程实例主键或业务键，严格拒绝不存在和多实例歧义。
     * @param row WfRuntimeEventRequest，规范关联条件
     * @return ProcessInstance，唯一活动流程实例
     */
    private ProcessInstance resolveUniqueInstance(WfRuntimeEventRequest row)
    {
        List<ProcessInstance> instances = "PROCESS_INSTANCE".equals(row.getCorrelationType())
                ? runtimeService.createProcessInstanceQuery()
                        .processInstanceId(row.getCorrelationValue()).list()
                : runtimeService.createProcessInstanceQuery()
                        .processInstanceBusinessKey(row.getCorrelationValue()).list();
        if (instances.isEmpty())
        {
            throw conflict("未找到匹配的活动流程实例", "RUNTIME_EVENT_INSTANCE_NOT_FOUND");
        }
        if (instances.size() != 1)
        {
            throw conflict("运行事件关联到多个活动流程实例",
                    "RUNTIME_EVENT_INSTANCE_AMBIGUOUS");
        }
        ProcessInstance instance = instances.get(0);
        if (instance.isSuspended())
        {
            throw conflict("匹配的流程实例已挂起", "RUNTIME_EVENT_INSTANCE_SUSPENDED");
        }
        return instance;
    }

    /**
     * 唯一匹配消息或信号订阅并调用 Flowable 官方消费 API。
     * @param instance ProcessInstance，唯一关联实例
     * @param row WfRuntimeEventRequest，规范事件请求
     * @param variables Map&lt;String,Object&gt;，白名单变量
     * @param flowableType String，Flowable 订阅类型 message 或 signal
     * @return Match，被消费执行主键
     */
    private Match consumeSubscription(ProcessInstance instance, WfRuntimeEventRequest row,
            Map<String, Object> variables, String flowableType)
    {
        List<EventSubscription> subscriptions = runtimeService.createEventSubscriptionQuery()
                .eventType(flowableType).eventName(row.getEventName())
                .processInstanceId(instance.getId()).list();
        if (subscriptions.isEmpty())
        {
            throw conflict("流程实例没有匹配的等待事件订阅",
                    "RUNTIME_EVENT_SUBSCRIPTION_NOT_FOUND");
        }
        if (subscriptions.size() != 1)
        {
            throw conflict("流程实例存在多个同名等待事件订阅",
                    "RUNTIME_EVENT_SUBSCRIPTION_AMBIGUOUS");
        }
        EventSubscription subscription = subscriptions.get(0);
        if (subscription.getExecutionId() == null || subscription.getExecutionId().isBlank())
        {
            throw conflict("等待事件订阅缺少执行主键", "RUNTIME_EVENT_SUBSCRIPTION_INVALID");
        }
        if ("message".equals(flowableType))
        {
            runtimeService.messageEventReceived(row.getEventName(),
                    subscription.getExecutionId(), variables);
        }
        else
        {
            runtimeService.signalEventReceived(row.getEventName(),
                    subscription.getExecutionId(), variables);
        }
        return new Match(subscription.getExecutionId());
    }

    /**
     * 唯一匹配 activityId，复核 BPMN 类型为 ReceiveTask 后调用官方 trigger。
     * @param instance ProcessInstance，唯一关联实例
     * @param row WfRuntimeEventRequest，eventName 作为 ReceiveTask activityId
     * @param variables Map&lt;String,Object&gt;，白名单变量
     * @return Match，被触发执行主键
     */
    private Match consumeReceiveTask(ProcessInstance instance, WfRuntimeEventRequest row,
            Map<String, Object> variables)
    {
        FlowElement element = repositoryService.getBpmnModel(instance.getProcessDefinitionId())
                .getFlowElement(row.getEventName());
        if (!(element instanceof ReceiveTask))
        {
            throw conflict("目标元素不是 ReceiveTask", "RUNTIME_EVENT_RECEIVE_TYPE_MISMATCH");
        }
        List<Execution> executions = runtimeService.createExecutionQuery()
                .processInstanceId(instance.getId()).activityId(row.getEventName()).list();
        if (executions.isEmpty())
        {
            throw conflict("流程实例没有等待中的 ReceiveTask",
                    "RUNTIME_EVENT_RECEIVE_NOT_FOUND");
        }
        if (executions.size() != 1)
        {
            throw conflict("流程实例存在多个同 activityId 的 ReceiveTask 执行",
                    "RUNTIME_EVENT_RECEIVE_AMBIGUOUS");
        }
        Execution execution = executions.get(0);
        runtimeService.trigger(execution.getId(), variables);
        return new Match(execution.getId());
    }

    /**
     * 对顺序重放执行完整载荷比对，成功请求返回原结果，其他状态返回冲突。
     * @param existing WfRuntimeEventRequest，已锁定首次请求
     * @param incoming WfRuntimeEventRequest，本次请求摘要
     * @return WorkflowRuntimeEventView，首次已成功时的原结果
     */
    private WorkflowRuntimeEventView replay(WfRuntimeEventRequest existing,
            WfRuntimeEventRequest incoming)
    {
        if (!sameRequest(existing, incoming))
        {
            throw conflict("requestId 已被不同运行事件载荷使用",
                    "RUNTIME_EVENT_IDEMPOTENCY_CONFLICT");
        }
        if ("PROCESSED".equals(existing.getStatus()))
        {
            return toView(existing);
        }
        if ("FAILED".equals(existing.getStatus()))
        {
            throw conflict("该 requestId 的首次运行事件已经失败",
                    existing.getResultCode() == null ? "RUNTIME_EVENT_PREVIOUSLY_FAILED"
                            : existing.getResultCode());
        }
        throw conflict("该 requestId 正在处理中", "RUNTIME_EVENT_IN_PROGRESS");
    }

    /**
     * 并发唯一键碰撞后读取首次请求并复用顺序重放规则。
     * @param incoming WfRuntimeEventRequest，本次请求摘要
     * @return WorkflowRuntimeEventView，首次请求成功结果
     */
    private WorkflowRuntimeEventView resolveConcurrentReplay(WfRuntimeEventRequest incoming)
    {
        return engineOperations.read(() ->
        {
            WfRuntimeEventRequest existing = requestMapper.selectById(incoming.getRequestId());
            if (existing == null)
            {
                throw conflict("并发幂等请求尚未完成，请稍后重试",
                        "RUNTIME_EVENT_REPLAY_NOT_READY");
            }
            return replay(existing, incoming);
        });
    }

    /**
     * 规范事件类型、名称、互斥关联条件和变量顺序，并计算完整请求摘要。
     * @param eventType String，入口确定的事件类型
     * @param request WorkflowRuntimeEventRequest，外部请求
     * @return NormalizedEvent，不含 Token 的规范请求
     */
    private NormalizedEvent normalize(String eventType, WorkflowRuntimeEventRequest request)
    {
        if (request == null)
        {
            throw new ServiceException("运行事件请求不能为空", HttpStatus.BAD_REQUEST);
        }
        String type = eventType == null ? "" : eventType.trim().toUpperCase(Locale.ROOT);
        if (!List.of("MESSAGE", "SIGNAL", "RECEIVE").contains(type))
        {
            throw new ServiceException("运行事件类型不受支持", HttpStatus.BAD_REQUEST);
        }
        String name = trim(request.eventName());
        String instanceId = trim(request.processInstanceId());
        String businessKey = trim(request.businessKey());
        if (name.isEmpty() || name.length() > 255)
        {
            throw new ServiceException("运行事件名称不合法", HttpStatus.BAD_REQUEST);
        }
        if (instanceId.isEmpty() == businessKey.isEmpty())
        {
            throw new ServiceException("processInstanceId 和 businessKey 必须且只能提供一个",
                    HttpStatus.BAD_REQUEST);
        }
        String correlationType = instanceId.isEmpty() ? "BUSINESS_KEY" : "PROCESS_INSTANCE";
        String correlationValue = instanceId.isEmpty() ? businessKey : instanceId;
        TreeMap<String, Object> variables = new TreeMap<>();
        variables.putAll(request.variables() == null ? Map.of() : request.variables());
        String hash = requestHash(type, name, correlationType, correlationValue, variables);
        return new NormalizedEvent(request.requestId(), type, name, correlationType,
                correlationValue, Map.copyOf(variables), hash);
    }

    /**
     * 校验变量名必须在凭据白名单内，值只能是有界 JSON 标量。
     * @param variables Map&lt;String,Object&gt;，规范变量映射
     * @param allowed Set&lt;String&gt;，凭据冻结变量白名单
     * @return void，非法变量在任何 Flowable 查询和写入之前失败
     */
    private void validateVariables(Map<String, Object> variables, java.util.Set<String> allowed)
    {
        for (Map.Entry<String, Object> entry : variables.entrySet())
        {
            if (!allowed.contains(entry.getKey()))
            {
                throw new ServiceException("运行事件包含未授权变量", HttpStatus.FORBIDDEN)
                        .setSubCode("RUNTIME_EVENT_VARIABLE_DENIED");
            }
            scalarText(entry.getValue());
        }
    }

    /**
     * 把受控标量转换为带类型前缀的规范文本，同时执行大小和有限值校验。
     * @param value Object，Jackson 反序列化后的变量值
     * @return String，可参与稳定摘要的类型化文本
     */
    private String scalarText(Object value)
    {
        if (value instanceof String text)
        {
            if (text.length() > MAX_STRING_VALUE_LENGTH)
            {
                throw new ServiceException("运行事件字符串变量超过长度限制",
                        HttpStatus.BAD_REQUEST);
            }
            return "S:" + text;
        }
        if (value instanceof Boolean flag)
        {
            return "B:" + flag;
        }
        if (value instanceof Number number)
        {
            if (number instanceof Double doubleValue && !Double.isFinite(doubleValue)
                    || number instanceof Float floatValue && !Float.isFinite(floatValue))
            {
                throw new ServiceException("运行事件数值变量必须是有限值",
                        HttpStatus.BAD_REQUEST);
            }
            if (!(number instanceof Byte || number instanceof Short || number instanceof Integer
                    || number instanceof Long || number instanceof Float || number instanceof Double
                    || number instanceof BigInteger || number instanceof BigDecimal))
            {
                throw new ServiceException("运行事件数值变量类型不受支持",
                        HttpStatus.BAD_REQUEST);
            }
            String normalized = new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
            if (normalized.length() > MAX_NUMBER_TEXT_LENGTH)
            {
                throw new ServiceException("运行事件数值变量超过长度限制",
                        HttpStatus.BAD_REQUEST);
            }
            return "N:" + normalized;
        }
        throw new ServiceException("运行事件变量只允许字符串、布尔值和数值",
                HttpStatus.BAD_REQUEST).setSubCode("RUNTIME_EVENT_VARIABLE_TYPE_DENIED");
    }

    /**
     * 计算覆盖事件、关联条件和全部类型化变量的稳定摘要。
     * @param eventType String，事件类型
     * @param eventName String，事件名或 activityId
     * @param correlationType String，关联条件类型
     * @param correlationValue String，关联条件值
     * @param variables Map&lt;String,Object&gt;，按变量名排序的变量
     * @return String，64 位小写 SHA-256
     */
    private String requestHash(String eventType, String eventName, String correlationType,
            String correlationValue, Map<String, Object> variables)
    {
        List<String> values = new ArrayList<>();
        values.add(eventType);
        values.add(eventName);
        values.add(correlationType);
        values.add(correlationValue);
        for (Map.Entry<String, Object> entry : variables.entrySet())
        {
            values.add(entry.getKey());
            values.add(scalarText(entry.getValue()));
        }
        return WorkflowExtensionChecksum.sha256(values.toArray(String[]::new));
    }

    /**
     * 创建首次台账行，变量正文只用于引擎调用，不进入数据库。
     * @param event NormalizedEvent，规范外部请求
     * @param credentialId Long，认证凭据主键
     * @return WfRuntimeEventRequest，可直接登记的 RECEIVED 行
     */
    private WfRuntimeEventRequest buildRow(NormalizedEvent event, Long credentialId)
    {
        WfRuntimeEventRequest row = new WfRuntimeEventRequest();
        row.setRequestId(event.requestId());
        row.setCredentialId(credentialId);
        row.setEventType(event.eventType());
        row.setEventName(event.eventName());
        row.setCorrelationType(event.correlationType());
        row.setCorrelationValue(event.correlationValue());
        row.setVariablesSha256(event.requestHash());
        row.setStatus("RECEIVED");
        return row;
    }

    /**
     * 主事务回滚后写入不含变量正文的失败审计。
     * @param row WfRuntimeEventRequest，规范请求摘要
     * @param exception ServiceException，返回调用方的稳定业务错误
     * @return void，独立事务提交失败台账
     */
    private void auditFailure(WfRuntimeEventRequest row, ServiceException exception)
    {
        row.setStatus("FAILED");
        row.setResultCode(exception.getSubCode() == null ? "RUNTIME_EVENT_REJECTED"
                : exception.getSubCode());
        String message = exception.getMessage() == null ? "运行事件处理失败" : exception.getMessage();
        row.setResultSummary(message.length() <= 512 ? message : message.substring(0, 512));
        auditService.recordFailure(row);
    }

    /**
     * 比较首次请求和本次请求的完整签名。
     * @param left WfRuntimeEventRequest，首次请求
     * @param right WfRuntimeEventRequest，本次请求
     * @return boolean，凭据和全部请求摘要字段一致时为 true
     */
    private boolean sameRequest(WfRuntimeEventRequest left, WfRuntimeEventRequest right)
    {
        return Objects.equals(left.getCredentialId(), right.getCredentialId())
                && Objects.equals(left.getEventType(), right.getEventType())
                && Objects.equals(left.getEventName(), right.getEventName())
                && Objects.equals(left.getCorrelationType(), right.getCorrelationType())
                && Objects.equals(left.getCorrelationValue(), right.getCorrelationValue())
                && Objects.equals(left.getVariablesSha256(), right.getVariablesSha256());
    }

    /**
     * 创建带稳定子码的 409 业务异常。
     * @param message String，用户可见错误说明
     * @param subCode String，机器可读稳定子码
     * @return ServiceException，可直接抛出的冲突异常
     */
    private ServiceException conflict(String message, String subCode)
    {
        return new ServiceException(message, HttpStatus.CONFLICT).setSubCode(subCode);
    }

    /**
     * 转换为不含变量正文的运行事件视图。
     * @param row WfRuntimeEventRequest，正式台账实体
     * @return WorkflowRuntimeEventView，管理和外部调用共同使用的脱敏结果
     */
    private WorkflowRuntimeEventView toView(WfRuntimeEventRequest row)
    {
        return new WorkflowRuntimeEventView(row.getRequestId(), row.getCredentialId(),
                row.getEventType(), row.getEventName(), row.getCorrelationType(),
                row.getCorrelationValue(), row.getMatchedProcessInstanceId(),
                row.getMatchedExecutionId(), row.getStatus(), row.getResultCode(),
                row.getResultSummary(), row.getCreateTime(), row.getCompleteTime());
    }

    /**
     * 去除可空文本首尾空白。
     * @param value String，外部文本
     * @return String，非空规范文本或空串
     */
    private String trim(String value)
    {
        return value == null ? "" : value.trim();
    }

    /** 规范运行事件请求。 */
    private record NormalizedEvent(String requestId, String eventType, String eventName,
            String correlationType, String correlationValue, Map<String, Object> variables,
            String requestHash) { }

    /** 唯一匹配执行。 */
    private record Match(String executionId) { }
}
