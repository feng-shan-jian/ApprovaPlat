package com.ruoyi.flowable.mapper;

import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WorkflowModelLockRow;
import com.ruoyi.flowable.domain.WorkflowModelSaveRecord;

/**
 * 流程模型保存的数据库并发锁和持久化幂等数据访问层。
 */
public interface WorkflowModelSaveMapper
{
    /**
     * 幂等登记保存请求；重复 requestId 只锁定并保留原始请求内容，不覆盖审计字段。
     *
     * @param requestId String，用户一次保存意图的稳定请求主键
     * @param userId String，事务内重新核验的规范工作流用户主键
     * @param sourceModelId String，保存请求最初指向的 Flowable 模型主键
     * @param payloadSha256 String，规范保存载荷的 SHA-256 小写十六进制摘要
     * @return int，数据库报告的影响行数；调用方不得用该值区分首次请求和重放
     */
    int ensureSaveRequest(@Param("requestId") String requestId,
            @Param("userId") String userId,
            @Param("sourceModelId") String sourceModelId,
            @Param("payloadSha256") String payloadSha256);

    /**
     * 按 requestId 锁定持久化保存请求，供服务校验用户、来源和载荷并返回已完成结果。
     *
     * @param requestId String，已经过格式校验的保存请求主键
     * @return WorkflowModelSaveRecord，锁定的请求投影；结构缺失时为 null
     */
    WorkflowModelSaveRecord selectSaveRequestForUpdate(
            @Param("requestId") String requestId);

    /**
     * 使用 ACT_UNIQ_MODEL 锁定默认租户指定 key 的最早模型版本，作为稳定版本组锚点。
     *
     * @param modelKey String，从来源模型读取并经过格式校验的版本分组 key
     * @return WorkflowModelLockRow，当前读得到的最早版本；该 key 不存在时为 null
     */
    WorkflowModelLockRow selectOldestDefaultTenantModelForUpdate(
            @Param("modelKey") String modelKey);

    /**
     * 在稳定版本组锚点已锁定后，使用 ACT_UNIQ_MODEL 读取默认租户当前最高模型版本。
     *
     * @param modelKey String，从来源模型读取并经过一致性复核的版本分组 key
     * @return WorkflowModelLockRow，当前读得到的最高版本；该 key 不存在时为 null
     */
    WorkflowModelLockRow selectLatestDefaultTenantModelForUpdate(
            @Param("modelKey") String modelKey);

    /**
     * 在已锁定同 key 稳定锚点和最高版本后，按主键锁定默认租户来源模型。
     *
     * @param modelId String，保存请求最初指向的 Flowable 模型主键
     * @return WorkflowModelLockRow，锁定的来源模型；模型不存在或不属于默认租户时为 null
     */
    WorkflowModelLockRow selectDefaultTenantModelForUpdate(
            @Param("modelId") String modelId);

    /**
     * 将仍处于处理中的幂等请求原子标记为完成并保存真实结果模型主键。
     *
     * @param requestId String，已由当前事务锁定的保存请求主键
     * @param savedModelId String，模型和 BPMN 源码均已真实落库的 Flowable 模型主键
     * @return int，首次完成返回 1；请求缺失或已完成返回 0
     */
    int completeSaveRequest(@Param("requestId") String requestId,
            @Param("savedModelId") String savedModelId);
}
