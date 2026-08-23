package com.ruoyi.flowable.domain;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Flowable 多实例根执行对应的单轮次快照和审计对象。
 *
 * 实时任务、execution 和 nrOf* 计数仍以 Flowable 为准；本对象只固化该根执行的
 * 模式、有序成员、修订号和生命周期关联。
 */
public class WfMultiInstanceRound
{
    /** 单轮允许固化的最大成员数。 */
    public static final int MAX_MEMBER_COUNT = 100;

    /** 与 Java Integer 和 Flowable revision 协议一致的最大修订号。 */
    public static final int MAX_REVISION = Integer.MAX_VALUE;

    /** 规范用户主键是无前导零的 Long 正整数文本。 */
    private static final Pattern CANONICAL_USER_ID = Pattern.compile("[1-9][0-9]{0,18}");

    /** 用户主键上界，防止 19 位数字溢出正 Long。 */
    private static final BigInteger MAX_USER_ID = BigInteger.valueOf(Long.MAX_VALUE);

    /** 成员快照只允许这两种受控多实例模式。 */
    private static final Set<String> MODES = Set.of("ALL", "ANY");

    /** 严格拒绝重复字段和合法 JSON 后的尾随内容。 */
    private static final ObjectMapper MEMBER_MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private Long roundId;
    private String deployId;
    private String processDefinitionId;
    private String processInstanceId;
    private String activityId;
    private String rootExecutionId;
    private Integer roundNo;
    private String mode;
    private String membersJson;
    private Integer revisionNo;
    private WorkflowMultiInstanceRoundStatus roundStatus;
    private String returnSourceTaskId;
    private String returnActorUserId;
    private String applicantTaskId;
    private LocalDateTime createTime;
    private LocalDateTime returnTime;
    private LocalDateTime reopenTime;
    private LocalDateTime completeTime;
    private LocalDateTime terminateTime;

    /**
     * 将有序成员集合编码为数据库快照 JSON，不排序也不去重。
     *
     * @param members List&lt;String&gt;，按 Flowable 执行顺序给出的规范用户主键
     * @return String，紧凑且保持原始顺序的 JSON 数组
     */
    public static String encodeMembers(List<String> members)
    {
        List<String> validated = requireMembers(members);
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        validated.forEach(array::add);
        try
        {
            return MEMBER_MAPPER.writeValueAsString(array);
        }
        catch (JacksonException exception)
        {
            throw new IllegalStateException("多实例轮次成员快照编码失败", exception);
        }
    }

    /**
     * 严格解码持久化成员快照并保留数组顺序。
     *
     * @param encoded String，从业务表读取的成员 JSON
     * @return List&lt;String&gt;，不可变的有序规范用户主键
     */
    public static List<String> decodeMembers(String encoded)
    {
        if (encoded == null || encoded.isBlank())
        {
            throw new IllegalArgumentException("多实例轮次成员快照不能为空");
        }
        final JsonNode parsed;
        try
        {
            parsed = MEMBER_MAPPER.readTree(encoded);
        }
        catch (JacksonException exception)
        {
            throw new IllegalArgumentException("多实例轮次成员快照 JSON 损坏", exception);
        }
        if (!(parsed instanceof ArrayNode array))
        {
            throw new IllegalArgumentException("多实例轮次成员快照必须是 JSON 数组");
        }
        List<String> members = new ArrayList<>(array.size());
        for (JsonNode member : array)
        {
            if (!member.isTextual())
            {
                throw new IllegalArgumentException("多实例轮次成员必须是规范用户主键文本");
            }
            members.add(member.textValue());
        }
        return requireMembers(members);
    }

    /**
     * 校验并缩窄一个与 Flowable 同步的修订号。
     *
     * @param revision long，待写入或对账的修订号
     * @return int，位于 0 到 Integer.MAX_VALUE 的安全修订号
     */
    public static int requireRevision(long revision)
    {
        if (revision < 0 || revision > MAX_REVISION)
        {
            throw new IllegalArgumentException(
                    "多实例轮次修订号必须在 0 到 Integer.MAX_VALUE 之间");
        }
        return (int) revision;
    }

    /**
     * 校验当前对象是否满足数据库持久化和生命周期组合约束。
     *
     * @return void，字段缺失、漂移或时间倒置时抛出 IllegalStateException
     */
    public void requireValidLifecycle()
    {
        if (roundId != null && roundId <= 0)
        {
            throw invalid("多实例轮次主键必须为正数");
        }
        requireId(deployId, 64, "Flowable 部署主键");
        requireId(processDefinitionId, 64, "Flowable 流程定义主键");
        requireId(processInstanceId, 64, "Flowable 流程实例主键");
        requireId(activityId, 255, "多实例节点标识");
        requireId(rootExecutionId, 64, "多实例根 execution 主键");
        if (roundNo == null || roundNo <= 0)
        {
            throw invalid("多实例轮次号必须从 1 开始");
        }
        if (!MODES.contains(mode))
        {
            throw invalid("多实例轮次模式只允许 ALL 或 ANY");
        }
        try
        {
            decodeMembers(membersJson);
        }
        catch (IllegalArgumentException exception)
        {
            throw invalid(exception.getMessage(), exception);
        }
        if (revisionNo == null)
        {
            throw invalid("多实例轮次修订号必须在 0 到 Integer.MAX_VALUE 之间");
        }
        try
        {
            requireRevision(revisionNo.longValue());
        }
        catch (IllegalArgumentException exception)
        {
            throw invalid(exception.getMessage(), exception);
        }
        if (roundStatus == null || createTime == null)
        {
            throw invalid("多实例轮次状态和创建时间不能为空");
        }
        requireLifecycleFields();
        requireTimeOrder();
    }

    /**
     * 校验成员数量、规范 Long 正整数文本和唯一性。
     *
     * @param members List&lt;String&gt;，待校验的有序成员集合
     * @return List&lt;String&gt;，保留原始顺序的不可变副本
     */
    private static List<String> requireMembers(List<String> members)
    {
        if (members == null || members.isEmpty() || members.size() > MAX_MEMBER_COUNT)
        {
            throw new IllegalArgumentException("多实例轮次成员数量必须在 1 到 100 之间");
        }
        Set<String> unique = new HashSet<>();
        for (String member : members)
        {
            requireCanonicalUserId(member, "多实例轮次成员");
            if (!unique.add(member))
            {
                throw new IllegalArgumentException("多实例轮次成员不能重复: " + member);
            }
        }
        return List.copyOf(members);
    }

    /**
     * 校验一个无前导零且不超过 Long.MAX_VALUE 的用户主键。
     *
     * @param userId String，待校验用户主键
     * @param fieldName String，用于异常提示的业务字段名
     * @return void，不规范时抛出 IllegalArgumentException
     */
    private static void requireCanonicalUserId(String userId, String fieldName)
    {
        if (userId == null || !CANONICAL_USER_ID.matcher(userId).matches()
                || new BigInteger(userId).compareTo(MAX_USER_ID) > 0)
        {
            throw new IllegalArgumentException(fieldName + "必须是规范 Long 正整数文本");
        }
    }

    /**
     * 根据轮次状态校验退回关联和终态时间的完整组合。
     *
     * @return void，任一关联缺失或在错误状态出现时抛出 IllegalStateException
     */
    private void requireLifecycleFields()
    {
        boolean hasReturnSource = hasText(returnSourceTaskId);
        boolean hasReturnActor = hasText(returnActorUserId);
        boolean hasApplicantTask = hasText(applicantTaskId);
        if (roundStatus == WorkflowMultiInstanceRoundStatus.ACTIVE
                && (!allFalse(hasReturnSource, hasReturnActor, hasApplicantTask)
                    || returnTime != null || reopenTime != null || completeTime != null
                    || terminateTime != null))
        {
            throw invalid("ACTIVE 轮次不能携带退回或终态字段");
        }
        if (roundStatus == WorkflowMultiInstanceRoundStatus.RETURNED
                && (!allTrue(hasReturnSource, hasReturnActor, hasApplicantTask)
                    || returnTime == null || reopenTime != null || completeTime != null
                    || terminateTime != null))
        {
            throw invalid("RETURNED 轮次必须具备完整退回关联且尚未重开");
        }
        if (roundStatus == WorkflowMultiInstanceRoundStatus.REOPENED
                && (!allTrue(hasReturnSource, hasReturnActor, hasApplicantTask)
                    || returnTime == null || reopenTime == null || completeTime != null
                    || terminateTime != null))
        {
            throw invalid("REOPENED 轮次必须具备完整退回与重开信息");
        }
        if (roundStatus == WorkflowMultiInstanceRoundStatus.COMPLETED
                && (!allFalse(hasReturnSource, hasReturnActor, hasApplicantTask)
                    || returnTime != null || reopenTime != null || completeTime == null
                    || terminateTime != null))
        {
            throw invalid("COMPLETED 轮次必须只携带正常完成时间");
        }
        if (roundStatus == WorkflowMultiInstanceRoundStatus.TERMINATED
                && ((!allFalse(hasReturnSource, hasReturnActor, hasApplicantTask)
                        && !allTrue(hasReturnSource, hasReturnActor, hasApplicantTask))
                    || (allFalse(hasReturnSource, hasReturnActor, hasApplicantTask)
                        && returnTime != null)
                    || (allTrue(hasReturnSource, hasReturnActor, hasApplicantTask)
                        && returnTime == null)
                    || reopenTime != null || completeTime != null
                    || terminateTime == null))
        {
            throw invalid("TERMINATED 轮次必须携带异常关闭时间并保持原退回关联");
        }
        if (hasReturnActor)
        {
            try
            {
                requireCanonicalUserId(returnActorUserId, "多实例整组退回操作人");
            }
            catch (IllegalArgumentException exception)
            {
                throw invalid(exception.getMessage(), exception);
            }
        }
        if (hasReturnSource) requireId(returnSourceTaskId, 64, "整组退回源任务主键");
        if (hasApplicantTask) requireId(applicantTaskId, 64, "申请人任务主键");
    }

    /**
     * 校验生命周期时间不早于创建时间，重开时间不早于退回时间。
     *
     * @return void，时间顺序异常时抛出 IllegalStateException
     */
    private void requireTimeOrder()
    {
        if ((returnTime != null && returnTime.isBefore(createTime))
                || (reopenTime != null && (returnTime == null || reopenTime.isBefore(returnTime)))
                || (completeTime != null && completeTime.isBefore(createTime))
                || (terminateTime != null && (terminateTime.isBefore(createTime)
                    || (returnTime != null && terminateTime.isBefore(returnTime)))))
        {
            throw invalid("多实例轮次生命周期时间顺序异常");
        }
    }

    /**
     * 校验 Flowable 和 BPMN 关联主键非空且未超过数据库列宽。
     *
     * @param value String，待校验关联主键
     * @param maxLength int，数据库列最大字符数
     * @param fieldName String，用于异常提示的业务字段名
     * @return void，不符合持久化约束时抛出 IllegalStateException
     */
    private static void requireId(String value, int maxLength, String fieldName)
    {
        if (!hasText(value) || value.length() > maxLength)
        {
            throw invalid(fieldName + "不能为空且长度不能超过 " + maxLength);
        }
    }

    /**
     * 判断文本是否包含非空白内容。
     *
     * @param value String，允许为空的文本
     * @return boolean，非空且非纯空白时返回 true
     */
    private static boolean hasText(String value)
    {
        return value != null && !value.isBlank();
    }

    /**
     * 判断三个退回关联标记是否全部为 true。
     *
     * @param first boolean，第一个关联标记
     * @param second boolean，第二个关联标记
     * @param third boolean，第三个关联标记
     * @return boolean，三个标记都为 true 时返回 true
     */
    private static boolean allTrue(boolean first, boolean second, boolean third)
    {
        return first && second && third;
    }

    /**
     * 判断三个退回关联标记是否全部为 false。
     *
     * @param first boolean，第一个关联标记
     * @param second boolean，第二个关联标记
     * @param third boolean，第三个关联标记
     * @return boolean，三个标记都为 false 时返回 true
     */
    private static boolean allFalse(boolean first, boolean second, boolean third)
    {
        return !first && !second && !third;
    }

    /**
     * 构造不含快照原文的稳定领域状态异常。
     *
     * @param message String，稳定业务字段提示
     * @return IllegalStateException，供读写边界失败关闭的异常
     */
    private static IllegalStateException invalid(String message)
    {
        return new IllegalStateException(message);
    }

    /**
     * 构造保留原始原因的稳定领域状态异常。
     *
     * @param message String，稳定业务字段提示
     * @param cause Throwable，JSON 或成员校验失败的原始原因
     * @return IllegalStateException，供读写边界失败关闭的异常
     */
    private static IllegalStateException invalid(String message, Throwable cause)
    {
        return new IllegalStateException(message, cause);
    }

    /** @return Long，多实例轮次主键。 */
    public Long getRoundId() { return roundId; }
    /** @param value Long，多实例轮次主键。 @return void，无返回值。 */
    public void setRoundId(Long value) { roundId = value; }
    /** @return String，Flowable 部署主键。 */
    public String getDeployId() { return deployId; }
    /** @param value String，Flowable 部署主键。 @return void，无返回值。 */
    public void setDeployId(String value) { deployId = value; }
    /** @return String，Flowable 流程定义主键。 */
    public String getProcessDefinitionId() { return processDefinitionId; }
    /** @param value String，Flowable 流程定义主键。 @return void，无返回值。 */
    public void setProcessDefinitionId(String value) { processDefinitionId = value; }
    /** @return String，Flowable 流程实例主键。 */
    public String getProcessInstanceId() { return processInstanceId; }
    /** @param value String，Flowable 流程实例主键。 @return void，无返回值。 */
    public void setProcessInstanceId(String value) { processInstanceId = value; }
    /** @return String，多实例 BPMN 节点标识。 */
    public String getActivityId() { return activityId; }
    /** @param value String，多实例 BPMN 节点标识。 @return void，无返回值。 */
    public void setActivityId(String value) { activityId = value; }
    /** @return String，本轮多实例根 execution 主键。 */
    public String getRootExecutionId() { return rootExecutionId; }
    /** @param value String，本轮多实例根 execution 主键。 @return void，无返回值。 */
    public void setRootExecutionId(String value) { rootExecutionId = value; }
    /** @return Integer，同实例同节点从 1 开始的轮次号。 */
    public Integer getRoundNo() { return roundNo; }
    /** @param value Integer，同实例同节点的轮次号。 @return void，无返回值。 */
    public void setRoundNo(Integer value) { roundNo = value; }
    /** @return String，ALL 或 ANY 多实例模式。 */
    public String getMode() { return mode; }
    /** @param value String，ALL 或 ANY 多实例模式。 @return void，无返回值。 */
    public void setMode(String value) { mode = value; }
    /** @return String，有序成员 JSON 数组。 */
    public String getMembersJson() { return membersJson; }
    /** @param value String，有序成员 JSON 数组。 @return void，无返回值。 */
    public void setMembersJson(String value) { membersJson = value; }
    /** @return List&lt;String&gt;，经严格解码的不可变有序成员。 */
    public List<String> getMembers() { return decodeMembers(membersJson); }
    /** @param value List&lt;String&gt;，待有序编码的成员。 @return void，无返回值。 */
    public void setMembers(List<String> value) { membersJson = encodeMembers(value); }
    /** @return Integer，与 Flowable 同步的 CAS 修订号。 */
    public Integer getRevisionNo() { return revisionNo; }
    /** @param value Integer，与 Flowable 同步的 CAS 修订号。 @return void，无返回值。 */
    public void setRevisionNo(Integer value) { revisionNo = value; }
    /** @return WorkflowMultiInstanceRoundStatus，轮次生命周期状态。 */
    public WorkflowMultiInstanceRoundStatus getRoundStatus() { return roundStatus; }
    /** @param value WorkflowMultiInstanceRoundStatus，轮次生命周期状态。 @return void，无返回值。 */
    public void setRoundStatus(WorkflowMultiInstanceRoundStatus value) { roundStatus = value; }
    /** @return String，整组退回源任务主键。 */
    public String getReturnSourceTaskId() { return returnSourceTaskId; }
    /** @param value String，整组退回源任务主键。 @return void，无返回值。 */
    public void setReturnSourceTaskId(String value) { returnSourceTaskId = value; }
    /** @return String，整组退回操作人用户主键。 */
    public String getReturnActorUserId() { return returnActorUserId; }
    /** @param value String，整组退回操作人用户主键。 @return void，无返回值。 */
    public void setReturnActorUserId(String value) { returnActorUserId = value; }
    /** @return String，整组退回后的申请人任务主键。 */
    public String getApplicantTaskId() { return applicantTaskId; }
    /** @param value String，整组退回后的申请人任务主键。 @return void，无返回值。 */
    public void setApplicantTaskId(String value) { applicantTaskId = value; }
    /** @return LocalDateTime，轮次创建时间。 */
    public LocalDateTime getCreateTime() { return createTime; }
    /** @param value LocalDateTime，轮次创建时间。 @return void，无返回值。 */
    public void setCreateTime(LocalDateTime value) { createTime = value; }
    /** @return LocalDateTime，整组退回时间。 */
    public LocalDateTime getReturnTime() { return returnTime; }
    /** @param value LocalDateTime，整组退回时间。 @return void，无返回值。 */
    public void setReturnTime(LocalDateTime value) { returnTime = value; }
    /** @return LocalDateTime，申请人重提关闭本轮时间。 */
    public LocalDateTime getReopenTime() { return reopenTime; }
    /** @param value LocalDateTime，申请人重提关闭本轮时间。 @return void，无返回值。 */
    public void setReopenTime(LocalDateTime value) { reopenTime = value; }
    /** @return LocalDateTime，整组正常完成时间。 */
    public LocalDateTime getCompleteTime() { return completeTime; }
    /** @param value LocalDateTime，整组正常完成时间。 @return void，无返回值。 */
    public void setCompleteTime(LocalDateTime value) { completeTime = value; }
    /** @return LocalDateTime，流程显式终止或引擎原生中断导致的异常关闭时间。 */
    public LocalDateTime getTerminateTime() { return terminateTime; }
    /** @param value LocalDateTime，流程异常关闭时间。 @return void，无返回值。 */
    public void setTerminateTime(LocalDateTime value) { terminateTime = value; }
}
