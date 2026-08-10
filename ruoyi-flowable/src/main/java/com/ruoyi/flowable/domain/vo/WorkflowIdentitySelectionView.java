package com.ruoyi.flowable.domain.vo;

/**
 * 设计器已保存身份对象的正式目录回显。
 *
 * @param value String，与作者 BPMN 精确匹配的受控目录值
 * @param label String，正式目录名称或已删除对象的稳定占位说明
 * @param type String，user、role 或 dept
 * @param available boolean，当前是否仍满足启用状态和请求的业务资格
 */
public record WorkflowIdentitySelectionView(
        String value, String label, String type, boolean available)
{
}
