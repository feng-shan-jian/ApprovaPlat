package com.ruoyi.flowable.domain;

/**
 * 流程模型保存事务对 ACT_RE_MODEL 执行当前读锁得到的业务投影。
 *
 * @param modelId String，Flowable 模型主键
 * @param revision Integer，Flowable 乐观锁版本
 * @param modelKey String，模型版本分组 key
 * @param version Integer，模型业务版本号
 * @param deploymentId String，最近一次部署主键；未部署时为 null
 * @param name String，模型显示名称
 * @param category String，工作流分类编码
 * @param metaInfo String，Flowable 模型 JSON 元数据
 * @param tenantId String，Flowable 租户主键；本项目正式数据固定为空字符串
 */
public record WorkflowModelLockRow(
        String modelId,
        Integer revision,
        String modelKey,
        Integer version,
        String deploymentId,
        String name,
        String category,
        String metaInfo,
        String tenantId)
{
}
