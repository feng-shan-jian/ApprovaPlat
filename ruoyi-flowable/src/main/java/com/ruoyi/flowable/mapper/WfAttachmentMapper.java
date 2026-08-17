package com.ruoyi.flowable.mapper;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfAttachment;
import com.ruoyi.flowable.domain.WorkflowAttachmentQuotaUsage;

/**
 * 工作流私有附件数据访问层。
 */
public interface WfAttachmentMapper
{
    /**
     * 幂等创建用户附件配额互斥行；不同用户使用不同主键，不形成跨用户串行锁。
     *
     * @param ownerUserId Long，事务内核验的正式用户主键
     * @return int，首次创建返回 1，已存在返回 0
     */
    int ensureOwnerQuotaGuard(@Param("ownerUserId") Long ownerUserId);

    /**
     * 锁定同一用户配额互斥行，串行化该用户的临时附件容量核算。
     *
     * @param ownerUserId Long，事务内核验的正式用户主键
     * @return Long，成功锁定的同一用户主键；数据异常时返回 null
     */
    Long selectOwnerQuotaGuardForUpdate(@Param("ownerUserId") Long ownerUserId);

    /**
     * 聚合仍实际占用临时磁盘空间的附件数量和字节。
     *
     * @param ownerUserId Long，已持有配额互斥行锁的用户主键
     * @return WorkflowAttachmentQuotaUsage，非空且非负的当前占用
     */
    WorkflowAttachmentQuotaUsage selectTemporaryQuotaUsage(
            @Param("ownerUserId") Long ownerUserId);

    /**
     * 写入已完成私有文件落盘和摘要计算的临时附件元数据。
     *
     * @param attachment WfAttachment，服务端生成且状态为 TEMP 的完整元数据
     * @return int，实际写入行数
     */
    int insert(WfAttachment attachment);

    /**
     * 按附件 UUID 查询完整内部元数据。
     *
     * @param attachmentId String，服务端生成的附件 UUID
     * @return WfAttachment，附件元数据；不存在时返回 null
     */
    WfAttachment selectById(@Param("attachmentId") String attachmentId);

    /**
     * 以稳定顺序锁定待绑定附件，保证校验、引擎发起和绑定处于同一事务视图。
     *
     * @param attachmentIds Collection&lt;String&gt;，已去重且非空的附件 UUID
     * @return List&lt;WfAttachment&gt;，按附件 UUID 排序的锁定行
     */
    List<WfAttachment> selectByIdsForUpdate(
            @Param("attachmentIds") Collection<String> attachmentIds);

    /**
     * 锁定指定草稿当前绑定的全部附件，用于保存对账、删除和提交迁移。
     *
     * @param draftId String，草稿 UUID
     * @param ownerUserId Long，草稿所有者正式用户主键
     * @return List&lt;WfAttachment&gt;，按附件 UUID 排序的 DRAFT 附件
     */
    List<WfAttachment> selectByDraftIdForUpdate(@Param("draftId") String draftId,
            @Param("ownerUserId") Long ownerUserId);

    /**
     * 将仍有效的本人临时附件绑定到指定草稿。
     *
     * @param attachmentId String，附件 UUID
     * @param ownerUserId Long，当前用户主键
     * @param fieldName String，开始表单上传字段名
     * @param draftId String，草稿 UUID
     * @return int，成功迁移返回 1，状态竞争返回 0
     */
    int bindDraftAttachment(@Param("attachmentId") String attachmentId,
            @Param("ownerUserId") Long ownerUserId,
            @Param("fieldName") String fieldName, @Param("draftId") String draftId);

    /**
     * 将草稿移除或随草稿删除的附件转入可清理终态。
     *
     * @param attachmentId String，附件 UUID
     * @param ownerUserId Long，草稿所有者
     * @param draftId String，草稿 UUID
     * @return int，成功迁移返回 1，状态竞争返回 0
     */
    int markDraftAttachmentDeleted(@Param("attachmentId") String attachmentId,
            @Param("ownerUserId") Long ownerUserId, @Param("draftId") String draftId);

    /**
     * 将同一草稿附件原子迁移到新建流程实例。
     *
     * @param attachmentId String，附件 UUID
     * @param ownerUserId Long，草稿所有者
     * @param fieldName String，开始表单字段名
     * @param draftId String，草稿 UUID
     * @param processInstanceId String，真实流程实例主键
     * @param nodeKey String，开始节点 key
     * @return int，成功迁移返回 1，状态竞争返回 0
     */
    int bindDraftStartAttachment(@Param("attachmentId") String attachmentId,
            @Param("ownerUserId") Long ownerUserId,
            @Param("fieldName") String fieldName, @Param("draftId") String draftId,
            @Param("processInstanceId") String processInstanceId,
            @Param("nodeKey") String nodeKey);

    /**
     * 将仍属于指定用户、字段且未过期的临时附件原子绑定到真实流程实例。
     *
     * @param attachmentId String，待绑定附件 UUID
     * @param ownerUserId Long，事务内核验的当前用户主键
     * @param fieldName String，部署表单白名单中的上传字段名
     * @param processInstanceId String，刚创建的真实 Flowable 流程实例主键
     * @param nodeKey String，部署快照中的 BPMN 开始节点 key
     * @return int，成功绑定返回 1；状态或归属竞争失败返回 0
     */
    int bindStartAttachment(@Param("attachmentId") String attachmentId,
            @Param("ownerUserId") Long ownerUserId,
            @Param("fieldName") String fieldName,
            @Param("processInstanceId") String processInstanceId,
            @Param("nodeKey") String nodeKey);

    /**
     * 将仍属于当前办理人、字段且未过期的临时附件原子绑定到真实任务节点。
     *
     * @param attachmentId String，待绑定附件 UUID
     * @param ownerUserId Long，事务内核验的当前办理人主键
     * @param fieldName String，部署任务表单白名单中的上传字段名
     * @param processInstanceId String，当前任务所属真实流程实例主键
     * @param taskId String，当前真实 Flowable 任务主键
     * @param nodeKey String，当前任务 BPMN 节点 key
     * @return int，成功绑定返回 1；状态、有效期或归属竞争失败返回 0
     */
    int bindTaskAttachment(@Param("attachmentId") String attachmentId,
            @Param("ownerUserId") Long ownerUserId,
            @Param("fieldName") String fieldName,
            @Param("processInstanceId") String processInstanceId,
            @Param("taskId") String taskId,
            @Param("nodeKey") String nodeKey);

    /**
     * 统计指定流程实例集合仍作为审计证据保留的已绑定附件。
     *
     * @param processInstanceIds Collection&lt;String&gt;，已受上层数量门禁保护的流程实例主键集合
     * @return long，状态为 BOUND 且关联任一目标实例的附件数量
     */
    long countBoundByProcessInstanceIds(
            @Param("processInstanceIds") Collection<String> processInstanceIds);

    /**
     * 统计指定正式状态的附件数量，状态值只由监控组件固定枚举提供。
     *
     * @param status String，TEMP、BOUND、EXPIRED 或 DELETED
     * @return long，当前状态记录数
     */
    long countByStatus(@Param("status") String status);

    /**
     * 统计已经终态但尚未完成物理文件删除的附件数。
     *
     * @return long，清理调度需要继续处理的持久化记录数
     */
    long countPendingStorageDeletion();

    /**
     * 统计已按退避策略推迟、当前尚未到重试时间的物理清理记录。
     *
     * @return long，未来时间再次进入候选的持久化记录数
     */
    long countDeferredStorageDeletion();

    /**
     * 仅将当前所有者仍未绑定的临时附件原子标记为已删除。
     *
     * @param attachmentId String，待删除附件 UUID
     * @param ownerUserId Long，事务内核验的当前用户主键
     * @return int，成功标记返回 1；越权或状态冲突返回 0
     */
    int markDeletedByOwner(@Param("attachmentId") String attachmentId,
            @Param("ownerUserId") Long ownerUserId);

    /**
     * 查询一批到期临时附件或尚未完成物理清理的终态附件。
     *
     * @param limit int，单批最多返回的记录数
     * @return List&lt;WfAttachment&gt;，按失效时间和附件 UUID 排序的清理候选
     */
    List<WfAttachment> selectCleanupCandidatesForUpdate(@Param("limit") int limit);

    /**
     * 将锁定候选原子领取到同一批次；到期 TEMP 同时迁移为 EXPIRED。
     *
     * @param attachmentIds Collection&lt;String&gt;，本事务已锁定的候选 UUID
     * @param claimToken String，本批次规范 UUID 领取令牌
     * @param leaseSeconds long，以数据库当前时间计算的正数租约秒数
     * @return int，成功领取的候选行数
     */
    int claimCleanupCandidates(@Param("attachmentIds") Collection<String> attachmentIds,
            @Param("claimToken") String claimToken,
            @Param("leaseSeconds") long leaseSeconds);

    /**
     * 原子领取单条已进入 DELETED 的手工删除附件。
     *
     * @param attachmentId String，手工删除附件 UUID
     * @param claimToken String，本次删除的规范 UUID 令牌
     * @param leaseSeconds long，以数据库当前时间计算的正数租约秒数
     * @return int，领取成功返回 1；其他节点持有有效租约时返回 0
     */
    int claimDeletedAttachment(@Param("attachmentId") String attachmentId,
            @Param("claimToken") String claimToken,
            @Param("leaseSeconds") long leaseSeconds);

    /**
     * 按批次令牌读取本事务刚领取的正式附件行。
     *
     * @param claimToken String，规范 UUID 批次令牌
     * @return List&lt;WfAttachment&gt;，按清理到期顺序返回的领取行
     */
    List<WfAttachment> selectClaimedByToken(@Param("claimToken") String claimToken);

    /**
     * 仅允许当前领取令牌在私有文件实际不存在后记录物理清理完成时间。
     *
     * @param attachmentId String，已处于 EXPIRED 或 DELETED 的附件 UUID
     * @param claimToken String，执行对象删除的领取令牌
     * @return int，首次记录返回 1；状态不允许或已记录返回 0
     */
    int markStorageDeleted(@Param("attachmentId") String attachmentId,
            @Param("claimToken") String claimToken);

    /**
     * 在期望重试版本仍一致时持久化下次清理时间和稳定错误码，避免固定前 N 条饥饿。
     *
     * @param attachmentId String，清理失败附件 UUID
     * @param claimToken String，执行对象删除的领取令牌
     * @param expectedRetryCount int，候选快照中的当前重试次数
     * @param nextRetryTime LocalDateTime，指数退避后的下次候选时间
     * @param errorCode String，固定脱敏错误码
     * @return int，成功调度返回 1；并发状态或版本变化返回 0
     */
    int scheduleCleanupRetry(@Param("attachmentId") String attachmentId,
            @Param("claimToken") String claimToken,
            @Param("expectedRetryCount") int expectedRetryCount,
            @Param("nextRetryTime") LocalDateTime nextRetryTime,
            @Param("errorCode") String errorCode);

    /**
     * 锁定一批已完成物理删除且超过元数据保留期的附件。
     * @param cutoffTime LocalDateTime，物理删除时间截止点
     * @param limit int，单批最大记录数
     * @return List&lt;String&gt;，按物理删除时间和附件主键稳定排序的锁定主键
     */
    List<String> selectRetentionIdsForUpdate(@Param("cutoffTime") LocalDateTime cutoffTime,
            @Param("limit") int limit);

    /**
     * 删除仍已完成物理删除且超过截止时间的附件元数据。
     * @param attachmentIds List&lt;String&gt;，当前事务已锁定的附件主键
     * @param cutoffTime LocalDateTime，物理删除时间截止点
     * @return int，实际删除记录数
     */
    int deleteRetentionByIds(@Param("attachmentIds") List<String> attachmentIds,
            @Param("cutoffTime") LocalDateTime cutoffTime);

    /**
     * 查询最早的附件物理删除完成时间。
     * @return LocalDateTime，最早物理删除时间；没有时为空
     */
    LocalDateTime selectOldestRetentionTime();
}
