package com.ruoyi.flowable.domain.vo;

/**
 * 工作流设计和任务转办使用的最小身份选项。
 *
 * @param value String，用户数字 ID 或 ROLE/DEPT 规范候选组编码
 * @param label String，供界面辨识的名称，不包含手机号、邮箱等敏感字段
 * @param type String，user、role 或 dept
 */
public record WorkflowIdentityOptionView(String value, String label, String type)
{
}
