package com.ruoyi.flowable.service.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 保存、显式校验和部署共用的作者表单用户主键字段目录。
 */
public final class WorkflowAuthorFormFieldCatalog
{
    /** 按流程和表单节点保存可作为用户主键来源的正式字段。 */
    private final Map<String, Map<String, Set<String>>> fieldsByNode;

    /** 按流程汇总的正式用户主键来源字段，供流程级自动抄送规则使用。 */
    private final Map<String, Set<String>> fieldsByProcess;

    /**
     * 创建深度不可变的正式字段目录。
     * @param fieldsByNode Map&lt;String,Map&lt;String,Set&lt;String&gt;&gt;&gt;，流程、节点和字段三级目录
     * @param disqualifiedProcessFields Map&lt;String,Set&lt;String&gt;&gt;，流程级同名不合格字段
     * @return 无返回值，构造后目录不可修改
     */
    private WorkflowAuthorFormFieldCatalog(
            Map<String, Map<String, Set<String>>> fieldsByNode,
            Map<String, Set<String>> disqualifiedProcessFields)
    {
        LinkedHashMap<String, Map<String, Set<String>>> nodeCopy = new LinkedHashMap<>();
        LinkedHashMap<String, Set<String>> processCopy = new LinkedHashMap<>();
        fieldsByNode.forEach((processKey, nodes) ->
        {
            LinkedHashMap<String, Set<String>> copiedNodes = new LinkedHashMap<>();
            LinkedHashSet<String> processFields = new LinkedHashSet<>();
            nodes.forEach((nodeKey, fields) ->
            {
                Set<String> copiedFields = Collections.unmodifiableSet(
                        new LinkedHashSet<>(fields));
                copiedNodes.put(nodeKey, copiedFields);
                processFields.addAll(copiedFields);
            });
            // 流程级规则读取共享变量，任一节点的同名隐藏或复合声明都必须使该字段失败关闭。
            processFields.removeAll(disqualifiedProcessFields
                    .getOrDefault(processKey, Set.of()));
            nodeCopy.put(processKey, Collections.unmodifiableMap(copiedNodes));
            processCopy.put(processKey, Collections.unmodifiableSet(processFields));
        });
        this.fieldsByNode = Collections.unmodifiableMap(nodeCopy);
        this.fieldsByProcess = Collections.unmodifiableMap(processCopy);
    }

    /**
     * 创建字段目录构建器，同一节点被重复读取时字段按首次顺序合并。
     * @return Builder，可逐个加入本次事务冻结的节点表单字段
     */
    public static Builder builder()
    {
        return new Builder();
    }

    /**
     * 判断任务级规则引用是否来自该任务的正式用户主键字段目录。
     * @param processKey String，任务所属可执行流程标识
     * @param nodeKey String，用户任务节点标识
     * @param fieldName String，FORM_USER 或 FORM_USER_FIELD 引用的变量名
     * @return boolean，字段存在且满足正式可见单值约束时返回 true
     */
    public boolean containsTaskField(String processKey, String nodeKey, String fieldName)
    {
        return fieldsByNode.getOrDefault(normalize(processKey), Map.of())
                .getOrDefault(normalize(nodeKey), Set.of())
                .contains(normalize(fieldName));
    }

    /**
     * 判断流程级规则引用是否来自本流程任一正式用户主键字段目录。
     * @param processKey String，可执行流程标识
     * @param fieldName String，流程完成自动抄送读取的变量名
     * @return boolean，字段在本流程正式目录中存在时返回 true
     */
    public boolean containsProcessField(String processKey, String fieldName)
    {
        return fieldsByProcess.getOrDefault(normalize(processKey), Set.of())
                .contains(normalize(fieldName));
    }

    /**
     * 规范目录键，空值规范为空串，使缺失目录键按未命中处理。
     * @param value String，流程、节点或字段原始值
     * @return String，去除首尾空白后的稳定目录键
     */
    private static String normalize(String value)
    {
        return value == null ? "" : value.trim();
    }

    /**
     * 本次作者校验的一致性视图构建器。
     */
    public static final class Builder
    {
        /** 可变目录仅在构建阶段存在，不会暴露给校验调用方。 */
        private final Map<String, Map<String, LinkedHashSet<String>>> fieldsByNode =
                new LinkedHashMap<>();

        /** 流程中存在隐藏、无读权限或复合声明的字段，禁止被流程级并集重新放宽。 */
        private final Map<String, LinkedHashSet<String>> disqualifiedProcessFields =
                new LinkedHashMap<>();

        /** 流程级字段首次出现的值形态签名，用于识别跨节点同名异构声明。 */
        private final Map<String, Map<String, String>> fieldSignaturesByProcess =
                new LinkedHashMap<>();

        /**
         * 同时加入合格字段签名与全部声明，并对跨节点同名异构字段失败关闭。
         * @param processKey String，表单节点所属可执行流程标识
         * @param nodeKey String，开始节点或用户任务标识
         * @param eligibleFieldSignatures Map&lt;String,String&gt;，合格字段及受控值形态签名
         * @param declaredFieldNames Set&lt;String&gt;，当前正式表单声明的全部业务字段
         * @return Builder，当前构建器
         */
        public Builder add(String processKey, String nodeKey,
                Map<String, String> eligibleFieldSignatures, Set<String> declaredFieldNames)
        {
            String normalizedProcessKey = normalize(processKey);
            String normalizedNodeKey = normalize(nodeKey);
            LinkedHashSet<String> target = fieldsByNode
                    .computeIfAbsent(normalizedProcessKey, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(normalizedNodeKey, ignored -> new LinkedHashSet<>());
            LinkedHashSet<String> normalizedEligible = new LinkedHashSet<>();
            if (eligibleFieldSignatures != null)
            {
                Map<String, String> processSignatures = fieldSignaturesByProcess
                        .computeIfAbsent(normalizedProcessKey,
                                ignored -> new LinkedHashMap<>());
                LinkedHashSet<String> disqualified = disqualifiedProcessFields
                        .computeIfAbsent(normalizedProcessKey,
                                ignored -> new LinkedHashSet<>());
                eligibleFieldSignatures.forEach((fieldName, signature) ->
                {
                    String normalizedField = normalize(fieldName);
                    String normalizedSignature = normalize(signature);
                    if (normalizedField.isEmpty() || normalizedSignature.isEmpty()) return;
                    normalizedEligible.add(normalizedField);
                    String existing = processSignatures.putIfAbsent(
                            normalizedField, normalizedSignature);
                    if (existing != null && !existing.equals(normalizedSignature))
                    {
                        // 同一流程不同节点声明了不同值形态时，流程级规则不能猜测运行时类型。
                        disqualified.add(normalizedField);
                    }
                });
                target.addAll(normalizedEligible);
            }
            if (declaredFieldNames != null)
            {
                LinkedHashSet<String> disqualified = disqualifiedProcessFields
                        .computeIfAbsent(normalizedProcessKey,
                                ignored -> new LinkedHashSet<>());
                declaredFieldNames.stream().map(WorkflowAuthorFormFieldCatalog::normalize)
                        .filter(field -> !field.isEmpty())
                        .filter(field -> !normalizedEligible.contains(field))
                        .forEach(disqualified::add);
            }
            return this;
        }

        /**
         * 固化深度不可变目录，后续表单或集合变化不会影响本次作者校验。
         * @return WorkflowAuthorFormFieldCatalog，本次一致性视图
         */
        public WorkflowAuthorFormFieldCatalog build()
        {
            LinkedHashMap<String, Map<String, Set<String>>> result = new LinkedHashMap<>();
            fieldsByNode.forEach((processKey, nodes) ->
            {
                LinkedHashMap<String, Set<String>> copiedNodes = new LinkedHashMap<>();
                nodes.forEach(copiedNodes::put);
                result.put(processKey, copiedNodes);
            });
            LinkedHashMap<String, Set<String>> disqualified = new LinkedHashMap<>();
            disqualifiedProcessFields.forEach(disqualified::put);
            return new WorkflowAuthorFormFieldCatalog(result, disqualified);
        }
    }
}
