package com.ruoyi.flowable.extension;

/**
 * 作者 BPMN 与部署编译器之间的受控扩展协议常量。
 */
public final class WorkflowExtensionBpmnContract
{
    /** 所有受控 Java ServiceTask 唯一允许的固定调度 Bean。 */
    public static final String DELEGATE_EXPRESSION = "${workflowExtensionDelegate}";

    /** 作者和编译 XML 中业务监听器唯一允许的固定调度 Bean。 */
    public static final String BUSINESS_LISTENER_DELEGATE_EXPRESSION = "${workflowBusinessListener}";

    /** 作者 XML 中保存扩展稳定键的 Flowable Field 名。 */
    public static final String EXTENSION_KEY_FIELD = "approvaExtensionKey";

    /** 作者 XML 中保存节点配置 JSON 的 Flowable Field 名。 */
    public static final String EXTENSION_CONFIG_FIELD = "approvaExtensionConfig";

    /** 业务监听器快照使用的元素标识前缀，与普通活动快照保持物理隔离。 */
    private static final String LISTENER_SNAPSHOT_PREFIX = "listener_";

    /**
     * 根据元素、监听器种类和事件生成运行时可重算的稳定快照元素标识。
     * @param elementId String，监听器所属 BPMN 元素标识
     * @param listenerKind String，EXECUTION 或 TASK
     * @param event String，Flowable 监听事件名
     * @return String，固定前缀加 32 位 SHA-256 摘要的快照元素标识
     */
    public static String listenerSnapshotElementId(String elementId, String listenerKind,
            String event)
    {
        String checksum = WorkflowExtensionChecksum.sha256(
                String.valueOf(elementId), String.valueOf(listenerKind), String.valueOf(event));
        return LISTENER_SNAPSHOT_PREFIX + checksum.substring(0, 32);
    }

    /** 禁止实例化协议常量类。 */
    private WorkflowExtensionBpmnContract()
    {
    }
}
