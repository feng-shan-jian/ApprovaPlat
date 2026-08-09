package com.ruoyi.flowable.domain.vo;

/**
 * 调用活动可映射的正式流程变量字段。
 *
 * @param name String，Flowable 变量名
 * @param label String，表单中的业务显示名称
 * @param type String，TEXT、NUMBER、BOOLEAN 或 SCALAR
 * @param required boolean，字段是否必填
 * @param readable boolean，字段是否允许作为输出来源
 * @param writable boolean，字段是否允许作为输入目标
 */
public record WorkflowCallActivityVariableView(String name, String label, String type,
        boolean required, boolean readable, boolean writable)
{
}
