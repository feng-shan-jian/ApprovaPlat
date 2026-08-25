package com.ruoyi.flowable.service.process;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.flowable.engine.HistoryService;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.flowable.variable.api.history.HistoricVariableInstanceQuery;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.BigIntegerNode;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.DecimalNode;
import tools.jackson.databind.node.DoubleNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.LongNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WorkflowCurrentVariableMetadataRow;
import com.ruoyi.flowable.domain.WorkflowHistoricSubmissionRow;
import com.ruoyi.flowable.domain.WorkflowHistoricVariableBodyRow;
import com.ruoyi.flowable.mapper.WorkflowHistoricVariableMapper;
import com.ruoyi.flowable.service.process.WorkflowFormSubmissionSnapshotCodec.SnapshotKind;
import com.ruoyi.flowable.service.process.WorkflowFormSubmissionSnapshotCodec.SubmissionSnapshot;

/**
 * 流程详情变量安全投影组件，集中维护历史快照、活动变量和 JSON 响应的存储安全协议。
 *
 * 该组件只接受详情服务在对象授权后提供的实例、任务和部署 schema 边界；调用仍处于详情入口
 * 已建立的同一只读事务中。任何存储关联、正文大小、反序列化或 JSON 结构异常都失败关闭。
 */
@Component
public class WorkflowProcessVariableProjection
{
    /** 单个活动表单允许读取的最大历史变量行数。 */
    static final int MAX_VARIABLE_ROWS = 2000;

    /** 单个详情允许扫描的最大历史变量更新数，超过时拒绝截断审计数据。 */
    static final int MAX_VARIABLE_UPDATE_ROWS = 10_000;

    /** 单份内部提交快照允许的最大 UTF-8 字节数，与固定快照编码契约保持一致。 */
    static final int MAX_SUBMISSION_TEXT_BYTES = 2 * 1024 * 1024;

    /** Flowable 字符串 Blob 的最大 Java 序列化字节数，覆盖 modified UTF-8 最坏膨胀。 */
    static final int MAX_SUBMISSION_SERIALIZED_BYTES =
            MAX_SUBMISSION_TEXT_BYTES * 3 / 2 + 1024;

    /** 单次详情允许从历史快照存储读取的最大累计正文大小。 */
    static final int MAX_TOTAL_SUBMISSION_STORED_BYTES = 4 * 1024 * 1024;

    /** 单个变量安全 JSON 的最大递归深度。 */
    private static final int MAX_VARIABLE_DEPTH = 10;

    /** 单个变量安全 JSON 的最大节点数。 */
    private static final int MAX_VARIABLE_NODES = 2000;

    /** 单个 JSON 容器允许的最大成员数。 */
    private static final int MAX_VARIABLE_CONTAINER_SIZE = 500;

    /** 单个变量文本允许的最大 UTF-8 字节数。 */
    private static final int MAX_VARIABLE_TEXT_BYTES = 64 * 1024;

    /** 活动表单单个 JSON 正文允许读取的最大存储字节数。 */
    static final int MAX_CURRENT_VARIABLE_BODY_BYTES = 1024 * 1024;

    /** 活动表单单个字符串 Blob 允许的最大 Java 序列化字节数。 */
    static final int MAX_CURRENT_VARIABLE_SERIALIZED_BYTES =
            MAX_VARIABLE_TEXT_BYTES * 3 + 1024;

    /** 单次活动表单允许从变量存储读取的最大累计正文大小。 */
    static final int MAX_TOTAL_CURRENT_VARIABLE_STORED_BYTES = 2 * 1024 * 1024;

    /** 单次正文 SQL 的最大主键数量，防止过长 IN 列表。 */
    private static final int VARIABLE_BODY_QUERY_BATCH_SIZE = 200;

    /** 引擎变量及数据库关联主键的最大字符数。 */
    private static final int MAX_ID_LENGTH = 255;

    /** 无论表单 schema 是否声明都不得对外回显的引擎内部变量。 */
    private static final Set<String> INTERNAL_VARIABLE_NAMES = Set.of(
            "initiator", "processStatus", "nrOfInstances", "nrOfActiveInstances",
            "nrOfCompletedInstances", "loopCounter", "_FLOWABLE_SKIP_EXPRESSION_ENABLED");

    /** 可以安全读取 getValue 的 Flowable 标量或 JSON 变量类型。 */
    private static final Set<String> SAFE_VARIABLE_TYPES = Set.of(
            "null", "string", "integer", "long", "short", "double", "boolean",
            "date", "instant", "json", "longjson", "longstring", "uuid",
            "bigdecimal", "biginteger");

    /** 必须无条件绕开 Flowable getValue 并从受控存储正文自行解码的变量类型。 */
    private static final Set<String> RAW_BODY_VARIABLE_TYPES = Set.of(
            "json", "longjson", "longstring");

    /** 递归禁止进入详情响应的原型污染键。 */
    private static final Set<String> FORBIDDEN_JSON_KEYS = Set.of(
            "__proto__", "prototype", "constructor");

    /** 字符串 Blob 只允许恢复单个 String，拒绝数组、自定义类、深层引用和超限流。 */
    private static final ObjectInputFilter STORED_STRING_FILTER = filterInfo ->
    {
        if (filterInfo.depth() > 1 || filterInfo.references() > 2
                || filterInfo.streamBytes() > MAX_SUBMISSION_SERIALIZED_BYTES
                || filterInfo.arrayLength() >= 0)
        {
            return ObjectInputFilter.Status.REJECTED;
        }
        Class<?> serializedClass = filterInfo.serialClass();
        return serializedClass == null || serializedClass == String.class
                ? ObjectInputFilter.Status.UNDECIDED
                : ObjectInputFilter.Status.REJECTED;
    };

    private final HistoryService historyService;

    private final WorkflowHistoricVariableMapper historicVariableMapper;

    /** 严格拒绝重复字段和根节点后尾随内容的变量 JSON 解析器。 */
    private final ObjectMapper safeJsonMapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /**
     * 创建流程详情变量安全投影组件。
     *
     * @param historyService HistoryService，禁止初始化正文的历史变量公共查询入口
     * @param historicVariableMapper WorkflowHistoricVariableMapper，物理元数据和正文两阶段读取入口
     * @return 无返回值，构造后由 Spring 管理该组件
     */
    public WorkflowProcessVariableProjection(HistoryService historyService,
            WorkflowHistoricVariableMapper historicVariableMapper)
    {
        this.historyService = historyService;
        this.historicVariableMapper = historicVariableMapper;
    }

    /**
     * 以两阶段方式读取 FULL 历史中的固定内部提交快照。
     *
     * 第一阶段必须看见固定变量名的全部行并完成类型、存储列、Blob 关联和累计容量校验；
     * 只有结果总行数为零时才按升级前旧实例处理。全部元数据合法后才允许第二阶段读取正文。
     *
     * @param instanceId String，快照必须所属的已授权流程实例主键
     * @param deploymentId String，当前实例流程定义所属部署主键
     * @param ancestorDeploymentIds Set&lt;String&gt;，CallActivity 执行树中祖先实例的部署主键
     * @return VariableStore，唯一开始快照及按真实 taskId 建立的任务快照索引
     */
    VariableStore loadSubmissionSnapshots(String instanceId,
            String deploymentId, Set<String> ancestorDeploymentIds)
    {
        List<WorkflowHistoricSubmissionRow> rows = historicVariableMapper
                .selectSubmissionMetadata(instanceId,
                        WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME,
                        MAX_VARIABLE_UPDATE_ROWS + 1);
        if (rows == null || rows.size() > MAX_VARIABLE_UPDATE_ROWS)
        {
            throw dataError("流程历史变量更新数量超过安全上限");
        }
        if (rows.isEmpty())
        {
            return new VariableStore(null, Map.of());
        }

        Map<String, WorkflowHistoricSubmissionRow> metadataById = new LinkedHashMap<>();
        long totalStoredBytes = 0L;
        for (WorkflowHistoricSubmissionRow row : rows)
        {
            validateSubmissionMetadata(row, instanceId);
            long rowStoredBytes = validateSubmissionStorage(row);
            totalStoredBytes = addBoundedStorageBytes(totalStoredBytes, rowStoredBytes,
                    MAX_TOTAL_SUBMISSION_STORED_BYTES,
                    "流程表单提交快照累计正文超过安全上限");
            if (metadataById.putIfAbsent(row.detailId(), row) != null)
            {
                throw dataError("流程历史变量更新主键不唯一");
            }
        }

        // 所有固定名行均完成统计校验后，才按已验证主键批量物化正文。
        Map<String, WorkflowHistoricVariableBodyRow> bodies = loadSubmissionBodies(
                instanceId, new ArrayList<>(metadataById.keySet()));
        StoredSubmission startSubmission = null;
        Map<String, StoredSubmission> taskSubmissions = new LinkedHashMap<>();
        for (WorkflowHistoricSubmissionRow row : rows)
        {
            String encoded = readSubmissionValue(row, bodies.get(row.detailId()));
            SubmissionSnapshot snapshot = WorkflowFormSubmissionSnapshotCodec.decode(encoded);
            if (!deploymentId.equals(snapshot.deploymentId()))
            {
                // inheritVariables 会把父实例内部提交变量一并复制到子实例；仅跳过可由执行树证明的祖先快照。
                if (!ancestorDeploymentIds.contains(snapshot.deploymentId()))
                {
                    throw dataError("流程表单提交快照所属部署异常");
                }
                continue;
            }
            StoredSubmission stored = new StoredSubmission(snapshot,
                    row.submittedAt().toInstant(), row.detailId(), row.activityInstanceId(),
                    row.taskId());
            if (snapshot.kind() == SnapshotKind.START)
            {
                if (StringUtils.hasText(row.taskId()))
                {
                    throw dataError("流程开始表单提交快照任务关联异常");
                }
                if (startSubmission != null)
                {
                    SubmissionSnapshot previous = startSubmission.snapshot();
                    if (!Objects.equals(previous.deploymentId(), snapshot.deploymentId())
                            || !Objects.equals(previous.sourceType(), snapshot.sourceType())
                            || !Objects.equals(previous.formId(), snapshot.formId())
                            || !Objects.equals(previous.formKey(), snapshot.formKey())
                            || !Objects.equals(previous.nodeKey(), snapshot.nodeKey()))
                    {
                        throw dataError("流程开始表单提交快照版本关联不一致");
                    }
                }
                // SQL 已按写入时间、revision 和主键稳定升序排列，最后一条就是用户重新提交后的覆盖版本。
                startSubmission = stored;
            }
            else
            {
                if (!StringUtils.hasText(row.taskId())
                        || !row.taskId().equals(snapshot.taskId())
                        || taskSubmissions.putIfAbsent(row.taskId(), stored) != null)
                {
                    throw dataError("流程任务表单提交快照关联数据异常");
                }
            }
        }
        return new VariableStore(startSubmission,
                Collections.unmodifiableMap(taskSubmissions));
    }

    /**
     * 按活动表单真实作用域读取当前变量，并投影为部署 schema 允许的安全 JSON。
     *
     * @param instanceId String，已经完成对象授权的流程实例主键
     * @param taskId String，真实活动任务主键
     * @param taskLocal boolean，true 只读取当前任务局部变量，false 只读取流程根变量
     * @param readableNames Set&lt;String&gt;，部署表单允许向当前用户回显的字段名
     * @return ProjectedValues，安全字段值及逐字段真实 JSON 字节数
     */
    ProjectedValues projectCurrentValues(String instanceId, String taskId,
            boolean taskLocal, Set<String> readableNames)
    {
        Map<String, HistoricVariableInstance> source = loadCurrentVariables(
                instanceId, taskId, taskLocal);
        return buildSafeValues(instanceId, taskId, taskLocal, readableNames, source);
    }

    /**
     * 按部署 schema 复制正式提交快照值，并拒绝未声明字段或内部字段。
     *
     * @param allowedNames Set&lt;String&gt;，部署表单声明的全部字段名
     * @param readableNames Set&lt;String&gt;，允许向当前用户响应回显的字段名
     * @param submittedValues Map&lt;String, JsonNode&gt;，已由固定快照协议严格解码的字段值
     * @return ProjectedValues，防御复制后的字段值及逐字段真实 JSON 字节数
     */
    ProjectedValues projectSubmittedValues(Set<String> allowedNames,
            Set<String> readableNames, Map<String, JsonNode> submittedValues)
    {
        return projectSnapshotValues(allowedNames, readableNames, submittedValues,
                "流程表单提交快照包含未声明字段");
    }

    /**
     * 按当前部署 schema 复制受控循环上一轮快照值，保持循环专用的失败关闭语义。
     *
     * @param allowedNames Set&lt;String&gt;，当前部署表单声明的全部字段名
     * @param readableNames Set&lt;String&gt;，当前用户允许回显的字段名
     * @param submittedValues Map&lt;String, JsonNode&gt;，上一轮正式提交快照字段值
     * @return ProjectedValues，防御复制后的继承候选值及逐字段真实 JSON 字节数
     */
    ProjectedValues projectControlledLoopValues(Set<String> allowedNames,
            Set<String> readableNames, Map<String, JsonNode> submittedValues)
    {
        return projectSnapshotValues(allowedNames, readableNames, submittedValues,
                "受控循环上一轮表单快照包含未声明字段");
    }

    /**
     * 按同一内部字段和响应字节规则投影服务端固定提交快照。
     *
     * @param allowedNames Set&lt;String&gt;，部署 schema 声明字段
     * @param readableNames Set&lt;String&gt;，当前用户可读字段
     * @param submittedValues Map&lt;String, JsonNode&gt;，固定快照协议解码结果
     * @param undeclaredMessage String，检测到未声明字段时的稳定错误提示
     * @return ProjectedValues，不可变安全值和对应序列化字节数
     */
    private ProjectedValues projectSnapshotValues(Set<String> allowedNames,
            Set<String> readableNames, Map<String, JsonNode> submittedValues,
            String undeclaredMessage)
    {
        for (String submittedName : submittedValues.keySet())
        {
            if (!allowedNames.contains(submittedName) || isInternalVariableName(submittedName))
            {
                throw dataError(undeclaredMessage);
            }
        }
        Map<String, JsonNode> values = new LinkedHashMap<>();
        Map<String, Integer> serializedBytesByName = new LinkedHashMap<>();
        for (String readableName : readableNames)
        {
            JsonNode value = submittedValues.get(readableName);
            if (value != null && !isInternalVariableName(readableName))
            {
                JsonNode copied = value.deepCopy();
                values.put(readableName, copied);
                serializedBytesByName.put(readableName, serializedSize(copied));
            }
        }
        return new ProjectedValues(values, serializedBytesByName);
    }

    /**
     * 校验固定内部快照历史行的身份、类型和审计关联字段。
     *
     * @param row WorkflowHistoricSubmissionRow，不包含正文的历史快照元数据
     * @param instanceId String，已经完成对象授权的流程实例主键
     * @return 无返回值，任一固定名坏行都会抛出 HTTP 500 数据异常
     */
    private void validateSubmissionMetadata(WorkflowHistoricSubmissionRow row, String instanceId)
    {
        if (row == null || !instanceId.equals(row.processInstanceId())
                || !WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME.equals(row.variableName())
                || !StringUtils.hasText(row.detailId()) || row.detailId().length() > MAX_ID_LENGTH
                || row.submittedAt() == null || row.revision() == null || row.revision() < 0
                || !"VariableUpdate".equals(row.detailType())
                || !isSnapshotVariableType(row.variableTypeName()))
        {
            throw dataError("流程历史变量更新关联数据异常");
        }
    }

    /**
     * 校验快照元数据中的互斥存储列、Blob 关系和单行正文大小。
     *
     * Flowable 8 的历史更新类型名可能保留为 string，但正文已经按 longString 写入 Blob，
     * 因此字符串类型只约束值语义，实际解码方式必须以 BYTEARRAY_ID_ 等物理元数据为准。
     *
     * @param row WorkflowHistoricSubmissionRow，已通过身份关联校验的快照元数据
     * @return long，后续正文查询将物化的真实存储字节数
     */
    private long validateSubmissionStorage(WorkflowHistoricSubmissionRow row)
    {
        boolean textPresent = requireStorageFlag(row.textPresent());
        boolean text2Present = requireStorageFlag(row.text2Present());
        boolean byteArrayPresent = requireStorageFlag(row.byteArrayPresent());
        boolean byteArrayBodyPresent = requireStorageFlag(row.byteArrayBodyPresent());
        if (text2Present)
        {
            throw dataError("流程表单提交快照历史存储结构异常");
        }

        boolean textStorageValid = textPresent && row.textBytes() != null
                && row.textBytes() >= 1 && row.textBytes() <= MAX_SUBMISSION_TEXT_BYTES
                && row.byteArrayId() == null && !byteArrayPresent
                && !byteArrayBodyPresent && row.storedBytes() == null;
        boolean blobStorageValid = !textPresent && row.textBytes() == null
                && StringUtils.hasText(row.byteArrayId())
                && row.byteArrayId().length() <= MAX_ID_LENGTH
                && byteArrayPresent && byteArrayBodyPresent
                && row.storedBytes() != null && row.storedBytes() >= 1
                && row.storedBytes() <= MAX_SUBMISSION_SERIALIZED_BYTES;
        String normalizedType = normalizeVariableType(row.variableTypeName());
        boolean validStorage = "string".equals(normalizedType)
                ? textStorageValid != blobStorageValid
                : "longstring".equals(normalizedType) && blobStorageValid;
        if (!validStorage)
        {
            throw dataError("流程表单提交快照历史存储结构异常");
        }
        return textStorageValid ? row.textBytes() : row.storedBytes();
    }

    /**
     * 按已验证历史详情主键分批读取快照正文并建立唯一索引。
     *
     * @param instanceId String，已经完成对象授权的流程实例主键
     * @param rowIds List&lt;String&gt;，全部通过元数据和累计容量门禁的历史详情主键
     * @return Map&lt;String, WorkflowHistoricVariableBodyRow&gt;，与输入主键一一对应的正文索引
     */
    private Map<String, WorkflowHistoricVariableBodyRow> loadSubmissionBodies(
            String instanceId, List<String> rowIds)
    {
        Map<String, WorkflowHistoricVariableBodyRow> bodies = new LinkedHashMap<>();
        for (int offset = 0; offset < rowIds.size(); offset += VARIABLE_BODY_QUERY_BATCH_SIZE)
        {
            int end = Math.min(offset + VARIABLE_BODY_QUERY_BATCH_SIZE, rowIds.size());
            List<String> batch = List.copyOf(rowIds.subList(offset, end));
            List<WorkflowHistoricVariableBodyRow> batchRows = historicVariableMapper
                    .selectSubmissionBodies(instanceId,
                            WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME, batch);
            indexBodyRows(batchRows, new LinkedHashSet<>(batch), bodies,
                    "流程表单提交快照历史正文关联异常");
        }
        if (bodies.size() != rowIds.size())
        {
            throw dataError("流程表单提交快照历史正文关联异常");
        }
        return Collections.unmodifiableMap(bodies);
    }

    /**
     * 按已验证的物理存储位置读取内部快照正文，并核验正文与第一阶段统计完全一致。
     *
     * @param row WorkflowHistoricSubmissionRow，第一阶段已通过全部门禁的元数据
     * @param body WorkflowHistoricVariableBodyRow，第二阶段按同一主键读取的正文
     * @return String，尚未经过快照 JSON 结构解码的受限正文
     */
    private String readSubmissionValue(WorkflowHistoricSubmissionRow row,
            WorkflowHistoricVariableBodyRow body)
    {
        if (body == null || !row.detailId().equals(body.rowId()))
        {
            throw dataError("流程表单提交快照历史正文关联异常");
        }
        if (row.byteArrayId() == null)
        {
            String value = body.storedText();
            if (value == null || body.storedBytes() != null
                    || value.getBytes(StandardCharsets.UTF_8).length != row.textBytes())
            {
                throw dataError("流程表单提交快照历史正文异常");
            }
            return value;
        }
        byte[] serialized = body.storedBytes();
        if (body.storedText() != null || serialized == null
                || serialized.length != row.storedBytes())
        {
            throw dataError("流程表单提交快照历史正文异常");
        }
        return deserializeStoredString(serialized);
    }

    /**
     * 以只允许单个 String 的对象过滤器读取 Flowable 字符串 Blob，拒绝任意对象反序列化。
     *
     * @param serialized byte[]，经数据库和服务双重长度门禁的 Java 序列化正文
     * @return String，Flowable 写入的原始字符串
     */
    private String deserializeStoredString(byte[] serialized)
    {
        try (ByteArrayInputStream byteInput = new ByteArrayInputStream(serialized);
                ObjectInputStream objectInput = new ObjectInputStream(byteInput))
        {
            objectInput.setObjectInputFilter(STORED_STRING_FILTER);
            Object value = objectInput.readObject();
            if (!(value instanceof String text) || objectInput.read() != -1)
            {
                throw dataError("流程字符串 Blob 正文异常");
            }
            return text;
        }
        catch (IOException | ClassNotFoundException exception)
        {
            ServiceException failure = dataError("流程字符串 Blob 正文异常");
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * 判断内部提交快照是否使用可安全读取的 Flowable 字符串类型。
     *
     * @param variableTypeName String，HistoricVariableUpdate 暴露的类型名
     * @return boolean，仅 string 与 longString 返回 true
     */
    private boolean isSnapshotVariableType(String variableTypeName)
    {
        if (!StringUtils.hasText(variableTypeName))
        {
            return false;
        }
        String normalized = variableTypeName.trim().toLowerCase(Locale.ROOT);
        return "string".equals(normalized) || "longstring".equals(normalized);
    }

    /**
     * 按活动表单真实作用域有界加载当前变量元数据，禁止跨任务或子执行读取。
     *
     * @param instanceId String，已经完成对象授权的流程实例主键
     * @param taskId String，真实活动任务主键
     * @param taskLocal boolean，true 只查询当前任务局部变量，false 只查询流程根变量
     * @return Map&lt;String, HistoricVariableInstance&gt;，按变量名唯一索引的当前作用域元数据
     */
    private Map<String, HistoricVariableInstance> loadCurrentVariables(String instanceId,
            String taskId, boolean taskLocal)
    {
        HistoricVariableInstanceQuery query = historyService
                .createHistoricVariableInstanceQuery();
        if (taskLocal)
        {
            // 单元素任务集合是查询边界，历史任务以及其他并行活动任务均不得进入当前表单。
            query.processInstanceId(instanceId).taskIds(Set.of(taskId));
        }
        else
        {
            // 非局部表单只允许根执行变量，排除任务局部和子执行 local 变量。
            query.processInstanceId(instanceId).excludeTaskVariables().excludeLocalVariables();
        }
        List<HistoricVariableInstance> rows = query.excludeVariableInitialization()
                .orderByVariableName().asc()
                .listPage(0, MAX_VARIABLE_ROWS + 1);
        if (rows == null || rows.size() > MAX_VARIABLE_ROWS)
        {
            throw dataError(taskLocal ? "任务局部变量数量超过安全上限" : "流程变量数量超过安全上限");
        }
        return Collections.unmodifiableMap(indexVariables(rows, instanceId,
                taskLocal ? taskId : null));
    }

    /**
     * 校验流程变量元数据并按变量名建立唯一索引。
     *
     * @param rows List&lt;HistoricVariableInstance&gt;，Flowable 历史变量结果
     * @param instanceId String，变量必须所属的流程实例主键
     * @param taskId String，期望任务主键；流程变量场景为空
     * @return Map&lt;String, HistoricVariableInstance&gt;，变量名唯一索引
     */
    private Map<String, HistoricVariableInstance> indexVariables(
            List<HistoricVariableInstance> rows, String instanceId, String taskId)
    {
        Map<String, HistoricVariableInstance> indexed = new LinkedHashMap<>();
        for (HistoricVariableInstance variable : rows)
        {
            if (variable == null || !instanceId.equals(variable.getProcessInstanceId())
                    || !Objects.equals(taskId, variable.getTaskId())
                    || !StringUtils.hasText(variable.getVariableName()))
            {
                throw dataError("流程变量关联数据异常");
            }
            if (indexed.putIfAbsent(variable.getVariableName(), variable) != null)
            {
                throw dataError("流程变量名称不唯一");
            }
        }
        return indexed;
    }

    /**
     * 按授权作用域和部署 schema 白名单两阶段读取活动表单当前值。
     *
     * longString、JSON 及实际使用 Blob 的 string 永不调用 Flowable getValue；普通标量也必须先
     * 与 ACT_HI_VARINST 元数据核对并确认没有字节数组关联，才允许由 Flowable 初始化值。
     *
     * @param instanceId String，已经完成对象授权的流程实例主键
     * @param taskId String，真实活动任务主键
     * @param taskLocal boolean，true 使用任务局部作用域，false 使用流程根作用域
     * @param allowedNames Set&lt;String&gt;，部署表单快照允许回显的字段名
     * @param source Map&lt;String, HistoricVariableInstance&gt;，对应变量作用域的 Flowable 元数据
     * @return ProjectedValues，按 schema 顺序返回的安全字段值和逐字段真实 JSON 字节数
     */
    private ProjectedValues buildSafeValues(String instanceId, String taskId,
            boolean taskLocal, Set<String> allowedNames,
            Map<String, HistoricVariableInstance> source)
    {
        Map<String, HistoricVariableInstance> safeVariables = new LinkedHashMap<>();
        for (String variableName : allowedNames)
        {
            if (isInternalVariableName(variableName))
            {
                continue;
            }
            requireSafeJsonKey(variableName);
            HistoricVariableInstance variable = source.get(variableName);
            if (variable == null || !isSafeVariableType(variable.getVariableTypeName()))
            {
                // 不支持的类型只保留元数据且永不初始化，避免 serializable 或自定义类型执行反序列化。
                continue;
            }
            if (!StringUtils.hasText(variable.getId()) || variable.getId().length() > MAX_ID_LENGTH)
            {
                throw dataError("活动表单变量主键异常");
            }
            safeVariables.put(variableName, variable);
        }
        if (safeVariables.isEmpty())
        {
            return new ProjectedValues(Map.of(), Map.of());
        }

        List<String> safeNames = List.copyOf(safeVariables.keySet());
        List<WorkflowCurrentVariableMetadataRow> metadataRows = historicVariableMapper
                .selectCurrentVariableMetadata(instanceId, taskId, taskLocal, safeNames,
                        safeNames.size() + 1);
        if (metadataRows == null || metadataRows.size() > safeNames.size())
        {
            throw dataError("活动表单变量元数据数量异常");
        }

        Map<String, WorkflowCurrentVariableMetadataRow> metadataByName = new LinkedHashMap<>();
        List<String> rawBodyIds = new ArrayList<>();
        long totalStoredBytes = 0L;
        for (WorkflowCurrentVariableMetadataRow metadata : metadataRows)
        {
            HistoricVariableInstance variable = metadata == null
                    ? null : safeVariables.get(metadata.variableName());
            validateCurrentVariableMetadata(metadata, variable, instanceId, taskId, taskLocal);
            String normalizedType = normalizeVariableType(metadata.variableTypeName());
            if (requiresControlledRawBody(metadata, normalizedType))
            {
                long storedBytes = validateCurrentRawStorage(metadata, normalizedType);
                totalStoredBytes = addBoundedStorageBytes(totalStoredBytes, storedBytes,
                        MAX_TOTAL_CURRENT_VARIABLE_STORED_BYTES,
                        "活动表单变量累计正文超过安全上限");
                rawBodyIds.add(metadata.variableId());
            }
            else
            {
                validateCurrentScalarStorage(metadata);
            }
            if (metadataByName.putIfAbsent(metadata.variableName(), metadata) != null)
            {
                throw dataError("活动表单变量名称不唯一");
            }
        }
        if (metadataByName.size() != safeVariables.size())
        {
            throw dataError("活动表单变量元数据不完整");
        }

        Map<String, WorkflowHistoricVariableBodyRow> rawBodies = rawBodyIds.isEmpty()
                ? Map.of() : loadCurrentVariableBodies(instanceId, taskId, taskLocal,
                        safeNames, rawBodyIds);
        Map<String, JsonNode> values = new LinkedHashMap<>();
        Map<String, Integer> serializedBytesByName = new LinkedHashMap<>();
        for (String variableName : allowedNames)
        {
            HistoricVariableInstance variable = safeVariables.get(variableName);
            if (variable == null)
            {
                continue;
            }
            WorkflowCurrentVariableMetadataRow metadata = metadataByName.get(variableName);
            String normalizedType = normalizeVariableType(metadata.variableTypeName());
            JsonNode safeValue = requiresControlledRawBody(metadata, normalizedType)
                    ? decodeCurrentRawValue(metadata, rawBodies.get(metadata.variableId()),
                            normalizedType)
                    : toSafeJson(variable.getValue(), 0, new SafeJsonCounter());
            if (safeValue != null)
            {
                values.put(variableName, safeValue);
                serializedBytesByName.put(variableName, serializedSize(safeValue));
            }
        }
        return new ProjectedValues(values, serializedBytesByName);
    }

    /**
     * 核对数据库元数据与 Flowable 禁止初始化查询返回的变量身份和作用域。
     *
     * @param metadata WorkflowCurrentVariableMetadataRow，数据库第一阶段元数据
     * @param variable HistoricVariableInstance，同名 Flowable 变量元数据
     * @param instanceId String，已经完成对象授权的流程实例主键
     * @param taskId String，真实活动任务主键
     * @param taskLocal boolean，期望的变量作用域
     * @return 无返回值，身份、类型或作用域不一致时抛出 HTTP 500
     */
    private void validateCurrentVariableMetadata(WorkflowCurrentVariableMetadataRow metadata,
            HistoricVariableInstance variable, String instanceId, String taskId,
            boolean taskLocal)
    {
        if (metadata == null || variable == null
                || !StringUtils.hasText(metadata.variableId())
                || metadata.variableId().length() > MAX_ID_LENGTH
                || !metadata.variableId().equals(variable.getId())
                || !instanceId.equals(metadata.processInstanceId())
                || !Objects.equals(metadata.variableName(), variable.getVariableName())
                || !normalizeVariableType(metadata.variableTypeName()).equals(
                        normalizeVariableType(variable.getVariableTypeName())))
        {
            throw dataError("活动表单变量元数据关联异常");
        }
        if (taskLocal)
        {
            if (!taskId.equals(metadata.taskId()))
            {
                throw dataError("活动表单任务局部变量作用域异常");
            }
        }
        else if (metadata.taskId() != null || !instanceId.equals(metadata.executionId())
                || metadata.subScopeId() != null)
        {
            throw dataError("活动表单流程变量作用域异常");
        }
    }

    /**
     * 判断活动变量是否必须绕开 Flowable getValue 并读取受控物理正文。
     *
     * string 正常行内存储可安全由 StringType 返回；一旦出现 BYTEARRAY_ID_ 或任一 Blob
     * 统计字段，就必须按 Flowable 8 的字符串 Blob 形态处理，不能让类型名掩盖物理存储。
     *
     * @param metadata WorkflowCurrentVariableMetadataRow，数据库第一阶段存储元数据
     * @param normalizedType String，已规范化的 Flowable 变量类型名
     * @return boolean，必须执行两阶段受控正文读取时返回 true
     */
    private boolean requiresControlledRawBody(WorkflowCurrentVariableMetadataRow metadata,
            String normalizedType)
    {
        if (RAW_BODY_VARIABLE_TYPES.contains(normalizedType))
        {
            return true;
        }
        return "string".equals(normalizedType)
                && (metadata.byteArrayId() != null
                        || Integer.valueOf(1).equals(metadata.byteArrayPresent())
                        || Integer.valueOf(1).equals(metadata.byteArrayBodyPresent())
                        || metadata.storedBytes() != null);
    }

    /**
     * 校验普通标量变量没有 Blob 关联或异常辅助正文后，才允许调用 Flowable getValue。
     *
     * @param metadata WorkflowCurrentVariableMetadataRow，活动变量存储元数据
     * @return 无返回值，检测到字节正文、超限文本或列状态矛盾时抛出 HTTP 500
     */
    private void validateCurrentScalarStorage(WorkflowCurrentVariableMetadataRow metadata)
    {
        boolean textPresent = requireStorageFlag(metadata.textPresent());
        boolean text2Present = requireStorageFlag(metadata.text2Present());
        boolean byteArrayPresent = requireStorageFlag(metadata.byteArrayPresent());
        boolean byteArrayBodyPresent = requireStorageFlag(metadata.byteArrayBodyPresent());
        if (text2Present || metadata.byteArrayId() != null || byteArrayPresent
                || byteArrayBodyPresent || metadata.storedBytes() != null
                || (textPresent && (metadata.textBytes() == null || metadata.textBytes() < 0
                        || metadata.textBytes() > MAX_VARIABLE_TEXT_BYTES))
                || (!textPresent && metadata.textBytes() != null))
        {
            throw dataError("活动表单标量变量存储结构异常");
        }
    }

    /**
     * 校验 string Blob、longString、json 或 longJson 的互斥正文列、Blob 关系和单项大小。
     *
     * @param metadata WorkflowCurrentVariableMetadataRow，活动变量存储元数据
     * @param normalizedType String，已规范化的 Flowable 变量类型名
     * @return long，第二阶段将实际物化的正文存储字节数
     */
    private long validateCurrentRawStorage(WorkflowCurrentVariableMetadataRow metadata,
            String normalizedType)
    {
        boolean textPresent = requireStorageFlag(metadata.textPresent());
        boolean text2Present = requireStorageFlag(metadata.text2Present());
        boolean byteArrayPresent = requireStorageFlag(metadata.byteArrayPresent());
        boolean byteArrayBodyPresent = requireStorageFlag(metadata.byteArrayBodyPresent());
        if (text2Present)
        {
            throw dataError("活动表单变量正文存储结构异常");
        }
        if ("string".equals(normalizedType) || "longstring".equals(normalizedType))
        {
            if (textPresent || metadata.textBytes() != null
                    || !StringUtils.hasText(metadata.byteArrayId())
                    || metadata.byteArrayId().length() > MAX_ID_LENGTH
                    || !byteArrayPresent || !byteArrayBodyPresent
                    || metadata.storedBytes() == null || metadata.storedBytes() < 1
                    || metadata.storedBytes() > MAX_CURRENT_VARIABLE_SERIALIZED_BYTES)
            {
                throw dataError("活动表单字符串 Blob 存储结构异常");
            }
            return metadata.storedBytes();
        }

        boolean textStorageValid = textPresent && metadata.textBytes() != null
                && metadata.textBytes() >= 1
                && metadata.textBytes() <= MAX_CURRENT_VARIABLE_BODY_BYTES
                && metadata.byteArrayId() == null && !byteArrayPresent
                && !byteArrayBodyPresent && metadata.storedBytes() == null;
        boolean blobStorageValid = !textPresent && metadata.textBytes() == null
                && StringUtils.hasText(metadata.byteArrayId())
                && metadata.byteArrayId().length() <= MAX_ID_LENGTH
                && byteArrayPresent && byteArrayBodyPresent
                && metadata.storedBytes() != null && metadata.storedBytes() >= 1
                && metadata.storedBytes() <= MAX_CURRENT_VARIABLE_BODY_BYTES;
        if (textStorageValid == blobStorageValid)
        {
            throw dataError("活动表单 JSON 变量存储结构异常");
        }
        return textStorageValid ? metadata.textBytes() : metadata.storedBytes();
    }

    /**
     * 按授权作用域、schema 白名单和已验证变量主键分批读取活动变量正文。
     *
     * @param instanceId String，已经完成对象授权的流程实例主键
     * @param taskId String，真实活动任务主键
     * @param taskLocal boolean，期望的变量作用域
     * @param variableNames List&lt;String&gt;，部署表单 schema 白名单内的安全变量名
     * @param rowIds List&lt;String&gt;，通过第一阶段元数据和容量门禁的变量主键
     * @return Map&lt;String, WorkflowHistoricVariableBodyRow&gt;，与输入主键一一对应的正文索引
     */
    private Map<String, WorkflowHistoricVariableBodyRow> loadCurrentVariableBodies(
            String instanceId, String taskId, boolean taskLocal, List<String> variableNames,
            List<String> rowIds)
    {
        Map<String, WorkflowHistoricVariableBodyRow> bodies = new LinkedHashMap<>();
        for (int offset = 0; offset < rowIds.size(); offset += VARIABLE_BODY_QUERY_BATCH_SIZE)
        {
            int end = Math.min(offset + VARIABLE_BODY_QUERY_BATCH_SIZE, rowIds.size());
            List<String> batch = List.copyOf(rowIds.subList(offset, end));
            List<WorkflowHistoricVariableBodyRow> batchRows = historicVariableMapper
                    .selectCurrentVariableBodies(instanceId, taskId, taskLocal,
                            variableNames, batch);
            indexBodyRows(batchRows, new LinkedHashSet<>(batch), bodies,
                    "活动表单变量正文关联异常");
        }
        if (bodies.size() != rowIds.size())
        {
            throw dataError("活动表单变量正文关联异常");
        }
        return Collections.unmodifiableMap(bodies);
    }

    /**
     * 解码并再次核验活动变量正文，字符串 Blob 只允许单一 String，JSON 使用严格解析器。
     *
     * @param metadata WorkflowCurrentVariableMetadataRow，第一阶段已校验元数据
     * @param body WorkflowHistoricVariableBodyRow，第二阶段受控正文
     * @param normalizedType String，已规范化的 Flowable 变量类型名
     * @return JsonNode，完成大小、结构和危险键门禁的当前变量值
     */
    private JsonNode decodeCurrentRawValue(WorkflowCurrentVariableMetadataRow metadata,
            WorkflowHistoricVariableBodyRow body, String normalizedType)
    {
        if (body == null || !metadata.variableId().equals(body.rowId()))
        {
            throw dataError("活动表单变量正文关联异常");
        }
        if ("string".equals(normalizedType) || "longstring".equals(normalizedType))
        {
            byte[] serialized = body.storedBytes();
            if (body.storedText() != null || serialized == null
                    || serialized.length != metadata.storedBytes())
            {
                throw dataError("活动表单字符串 Blob 正文异常");
            }
            return toSafeJson(deserializeStoredString(serialized), 0,
                    new SafeJsonCounter());
        }

        JsonNode parsed;
        try
        {
            if (metadata.textPresent() == 1)
            {
                String json = body.storedText();
                if (json == null || body.storedBytes() != null
                        || json.getBytes(StandardCharsets.UTF_8).length != metadata.textBytes())
                {
                    throw dataError("活动表单 JSON 变量正文异常");
                }
                parsed = safeJsonMapper.readTree(json);
            }
            else
            {
                byte[] json = body.storedBytes();
                if (body.storedText() != null || json == null
                        || json.length != metadata.storedBytes())
                {
                    throw dataError("活动表单 JSON 变量正文异常");
                }
                parsed = safeJsonMapper.readTree(json);
            }
        }
        catch (JacksonException exception)
        {
            ServiceException failure = dataError("活动表单 JSON 变量正文损坏");
            failure.initCause(exception);
            throw failure;
        }
        if (parsed == null)
        {
            throw dataError("活动表单 JSON 变量正文损坏");
        }
        JsonNode safe = toSafeJson(parsed, 0, new SafeJsonCounter());
        if (safe == null)
        {
            throw dataError("活动表单 JSON 变量包含不支持的节点");
        }
        return safe;
    }

    /**
     * 判断变量是否属于引擎固定字段或工作流服务端保留命名空间。
     *
     * @param variableName String，部署 schema 或历史变量中的字段名
     * @return boolean，不得向详情响应暴露时返回 true
     */
    private boolean isInternalVariableName(String variableName)
    {
        return INTERNAL_VARIABLE_NAMES.contains(variableName)
                || WorkflowFormSubmissionSnapshotCodec.isReservedVariableName(variableName);
    }

    /**
     * 判断 Flowable 变量类型是否允许安全读取值。
     *
     * @param variableTypeName String，Flowable 变量类型名
     * @return boolean，仅标量、时间和 JSON 类型返回 true
     */
    private boolean isSafeVariableType(String variableTypeName)
    {
        return SAFE_VARIABLE_TYPES.contains(normalizeVariableType(variableTypeName));
    }

    /**
     * 将 Flowable 变量类型名规范化为稳定的小写比较值。
     *
     * @param variableTypeName String，数据库或 Flowable API 返回的变量类型名
     * @return String，去除首尾空白并使用 ROOT locale 转小写；空值返回空字符串
     */
    private String normalizeVariableType(String variableTypeName)
    {
        return StringUtils.hasText(variableTypeName)
                ? variableTypeName.trim().toLowerCase(Locale.ROOT) : "";
    }

    /**
     * 把数据库 0/1 存储状态转换为布尔值，并拒绝空值或其他统计结果。
     *
     * @param flag Integer，SQL CASE 返回的存储状态
     * @return boolean，1 返回 true，0 返回 false
     */
    private boolean requireStorageFlag(Integer flag)
    {
        if (flag == null || (flag != 0 && flag != 1))
        {
            throw dataError("流程变量存储统计异常");
        }
        return flag == 1;
    }

    /**
     * 在不发生 long 溢出的前提下累计正文大小并执行固定上限门禁。
     *
     * @param current long，已经累计的正文存储字节数
     * @param addition long，本行将新增的正文存储字节数
     * @param maximum long，当前读取场景允许的最大累计字节数
     * @param message String，超过上限时返回的稳定数据异常提示
     * @return long，完成上限校验后的新累计值
     */
    private long addBoundedStorageBytes(long current, long addition, long maximum,
            String message)
    {
        if (current < 0 || addition < 0 || maximum < 0 || addition > maximum - current)
        {
            throw dataError(message);
        }
        return current + addition;
    }

    /**
     * 校验一批正文查询结果只包含期望主键，且每个主键最多出现一次。
     *
     * @param rows List&lt;WorkflowHistoricVariableBodyRow&gt;，Mapper 返回的正文行
     * @param expectedIds Set&lt;String&gt;，本批第一阶段已验证主键集合
     * @param target Map&lt;String, WorkflowHistoricVariableBodyRow&gt;，跨批次正文唯一索引
     * @param message String，关联异常时返回的稳定提示
     * @return 无返回值，空结果、越界主键或重复主键均抛出 HTTP 500
     */
    private void indexBodyRows(List<WorkflowHistoricVariableBodyRow> rows,
            Set<String> expectedIds, Map<String, WorkflowHistoricVariableBodyRow> target,
            String message)
    {
        if (rows == null || rows.size() != expectedIds.size())
        {
            throw dataError(message);
        }
        for (WorkflowHistoricVariableBodyRow row : rows)
        {
            if (row == null || !StringUtils.hasText(row.rowId())
                    || !expectedIds.contains(row.rowId())
                    || target.putIfAbsent(row.rowId(), row) != null)
            {
                throw dataError(message);
            }
        }
    }

    /**
     * 将已通过 Flowable 类型门禁的值递归转换为有深度和规模限制的 JSON 节点。
     *
     * @param value Object，标量、时间、JsonNode、Map 或 Collection 值
     * @param depth int，当前递归深度
     * @param counter SafeJsonCounter，单个变量共享的 JSON 节点计数器
     * @return JsonNode，安全 JSON；不支持的运行时类型返回 null
     */
    private JsonNode toSafeJson(Object value, int depth, SafeJsonCounter counter)
    {
        if (depth > MAX_VARIABLE_DEPTH)
        {
            throw dataError("表单变量 JSON 深度超过安全上限");
        }
        counter.increment();
        if (counter.value() > MAX_VARIABLE_NODES)
        {
            throw dataError("表单变量 JSON 节点数超过安全上限");
        }
        if (value == null)
        {
            return NullNode.getInstance();
        }
        if (value instanceof JsonNode node)
        {
            return sanitizeJsonNode(node, depth, counter);
        }
        if (value instanceof CharSequence text)
        {
            requireSafeText(text.toString());
            return StringNode.valueOf(text.toString());
        }
        if (value instanceof Boolean bool)
        {
            return BooleanNode.valueOf(bool);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long)
        {
            return LongNode.valueOf(((Number) value).longValue());
        }
        if (value instanceof BigInteger integer)
        {
            return BigIntegerNode.valueOf(integer);
        }
        if (value instanceof BigDecimal decimal)
        {
            return DecimalNode.valueOf(decimal);
        }
        if (value instanceof Float || value instanceof Double)
        {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number))
            {
                return null;
            }
            return DoubleNode.valueOf(number);
        }
        if (value instanceof Date date)
        {
            return StringNode.valueOf(date.toInstant().toString());
        }
        if (value instanceof Instant || value instanceof LocalDate
                || value instanceof LocalDateTime || value instanceof OffsetDateTime
                || value instanceof ZonedDateTime || value instanceof UUID)
        {
            return StringNode.valueOf(value.toString());
        }
        if (value instanceof Map<?, ?> map)
        {
            if (map.size() > MAX_VARIABLE_CONTAINER_SIZE)
            {
                throw dataError("表单变量 JSON 对象成员过多");
            }
            ObjectNode object = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<?, ?> entry : map.entrySet())
            {
                if (!(entry.getKey() instanceof String key))
                {
                    return null;
                }
                requireSafeJsonKey(key);
                JsonNode child = toSafeJson(entry.getValue(), depth + 1, counter);
                if (child == null)
                {
                    return null;
                }
                object.set(key, child);
            }
            return object;
        }
        if (value instanceof Collection<?> collection)
        {
            if (collection.size() > MAX_VARIABLE_CONTAINER_SIZE)
            {
                throw dataError("表单变量 JSON 数组成员过多");
            }
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            for (Object item : collection)
            {
                JsonNode child = toSafeJson(item, depth + 1, counter);
                if (child == null)
                {
                    return null;
                }
                array.add(child);
            }
            return array;
        }
        return null;
    }

    /**
     * 深复制并限制 Flowable JSON 变量返回的原生 JsonNode。
     *
     * @param node JsonNode，待安全复制的 JSON 节点
     * @param depth int，当前递归深度
     * @param counter SafeJsonCounter，单个变量共享的节点计数器
     * @return JsonNode，完成深度、成员数和文本大小门禁的副本
     */
    private JsonNode sanitizeJsonNode(JsonNode node, int depth, SafeJsonCounter counter)
    {
        if (node.isFloatingPointNumber())
        {
            // Jackson 节点可以承载非标准 JSON 浮点值，必须在详情序列化前拒绝 NaN 和 Infinity。
            return Double.isFinite(node.doubleValue()) ? node.deepCopy() : null;
        }
        if (node.isNull() || node.isBoolean() || node.isIntegralNumber())
        {
            return node.deepCopy();
        }
        if (node.isTextual())
        {
            requireSafeText(node.textValue());
            return StringNode.valueOf(node.textValue());
        }
        if (node.isBinary() || node.isPojo() || node.isMissingNode())
        {
            return null;
        }
        if (node.isArray())
        {
            if (node.size() > MAX_VARIABLE_CONTAINER_SIZE)
            {
                throw dataError("表单变量 JSON 数组成员过多");
            }
            ArrayNode copied = JsonNodeFactory.instance.arrayNode();
            for (JsonNode child : node)
            {
                JsonNode safeChild = toSafeJson(child, depth + 1, counter);
                if (safeChild == null)
                {
                    return null;
                }
                copied.add(safeChild);
            }
            return copied;
        }
        if (node.isObject())
        {
            if (node.size() > MAX_VARIABLE_CONTAINER_SIZE)
            {
                throw dataError("表单变量 JSON 对象成员过多");
            }
            ObjectNode copied = JsonNodeFactory.instance.objectNode();
            node.properties().forEach(entry ->
            {
                requireSafeJsonKey(entry.getKey());
                JsonNode safeChild = toSafeJson(entry.getValue(), depth + 1, counter);
                if (safeChild == null)
                {
                    throw dataError("表单变量包含不支持的 JSON 节点");
                }
                copied.set(entry.getKey(), safeChild);
            });
            return copied;
        }
        return null;
    }

    /**
     * 校验变量文本或 JSON 键的 UTF-8 大小。
     *
     * @param value String，待校验文本
     * @return 无返回值，超过单值上限时拒绝整个详情
     */
    private void requireSafeText(String value)
    {
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_VARIABLE_TEXT_BYTES)
        {
            throw dataError("表单变量文本超过安全上限");
        }
    }

    /**
     * 校验 JSON 对象键的大小并递归拒绝可改变前端对象原型语义的危险名称。
     *
     * @param key String，Map 或 JsonNode 任意层级的对象键
     * @return 无返回值，空键、超限键或原型污染键会拒绝整个详情
     */
    private void requireSafeJsonKey(String key)
    {
        if (!StringUtils.hasText(key)
                || FORBIDDEN_JSON_KEYS.contains(key.toLowerCase(Locale.ROOT)))
        {
            throw dataError("表单变量 JSON 对象字段名异常");
        }
        requireSafeText(key);
    }

    /**
     * 计算安全 JsonNode 的真实 UTF-8 序列化大小。
     *
     * @param node JsonNode，已经过结构门禁的变量值
     * @return int，JSON UTF-8 字节数
     */
    private int serializedSize(JsonNode node)
    {
        try
        {
            return safeJsonMapper.writeValueAsBytes(node).length;
        }
        catch (JacksonException exception)
        {
            throw dataError("表单变量 JSON 序列化失败");
        }
    }

    /**
     * 创建稳定的内部数据异常，保持详情接口原有 HTTP 500 与错误正文语义。
     *
     * @param message String，面向接口调用方的稳定错误提示
     * @return ServiceException，HTTP 500 数据异常
     */
    private ServiceException dataError(String message)
    {
        return new ServiceException(message, HttpStatus.ERROR);
    }

    /** 正式提交快照索引，调用方只能消费已完成存储与解码门禁的数据。 */
    record VariableStore(StoredSubmission startSubmission,
            Map<String, StoredSubmission> taskSubmissions)
    {
    }

    /** 固定快照正文及其 Flowable 历史关联身份。 */
    record StoredSubmission(SubmissionSnapshot snapshot, Instant submittedAt,
            String detailId, String activityInstanceId, String taskId)
    {
    }

    /** 安全 JSON 投影结果，同时携带逐字段真实响应字节数供整页预算累计。 */
    record ProjectedValues(Map<String, JsonNode> values,
            Map<String, Integer> serializedBytesByName)
    {
        /**
         * 防御复制变量投影，避免详情编排层修改安全值或字节计量。
         *
         * @param values Map&lt;String, JsonNode&gt;，完成安全门禁的有序字段值
         * @param serializedBytesByName Map&lt;String, Integer&gt;，逐字段真实 JSON 字节数
         * @return 无返回值，构造后两个映射均不可变
         */
        ProjectedValues
        {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            serializedBytesByName = Collections.unmodifiableMap(
                    new LinkedHashMap<>(serializedBytesByName));
        }
    }

    /** 单个变量递归投影期间共享的 JSON 节点计数器。 */
    private static final class SafeJsonCounter
    {
        private int value;

        /**
         * 记录本变量已访问一个 JSON 节点。
         *
         * @return 无返回值，调用方随后检查固定节点上限
         */
        private void increment()
        {
            value++;
        }

        /**
         * 返回本变量当前已经访问的 JSON 节点数量。
         *
         * @return int，非负节点数量
         */
        private int value()
        {
            return value;
        }
    }
}
