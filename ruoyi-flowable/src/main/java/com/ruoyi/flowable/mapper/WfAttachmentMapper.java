package com.ruoyi.flowable.mapper;

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
     * 锁定迁移脚本预置的固定全局配额行，使全部上传容量检查严格串行。
     *
     * @return Long，成功锁定时固定返回 0；部署数据缺失时返回 null
     */
    Long selectGlobalQuotaGuardForUpdate();

    /**
     * 在已持有全局配额行锁时幂等创建用户附件配额互斥行。
     *
     * @param ownerUserId Long，事务内核验的正式用户主键
     * @return int，首次创建返回 1，已存在返回 0
     */
    int ensureOwnerQuotaGuard(@Param("ownerUserId") Long ownerUserId);

    /**
     * 在已持有全局配额行锁时锁定用户配额互斥行。
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
     * 聚合全部尚未完成物理删除的附件字节数，包含 TEMP、BOUND、EXPIRED 和 DELETED。
     *
     * @return Long，已持有全局 guard 行锁时读取的非负全局占用
     */
    Long selectUndeletedTotalBytes();

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
    List<WfAttachment> selectCleanupCandidates(@Param("limit") int limit);

    /**
     * 将到期且仍未绑定的临时附件原子标记为过期。
     *
     * @param attachmentId String，到期附件 UUID
     * @return int，成功标记返回 1；状态已变化返回 0
     */
    int markExpired(@Param("attachmentId") String attachmentId);

    /**
     * 在私有文件实际不存在后记录物理清理完成时间。
     *
     * @param attachmentId String，已处于 EXPIRED 或 DELETED 的附件 UUID
     * @return int，首次记录返回 1；状态不允许或已记录返回 0
     */
    int markStorageDeleted(@Param("attachmentId") String attachmentId);

    /**
     * 在期望重试版本仍一致时持久化下次清理时间和稳定错误码，避免固定前 N 条饥饿。
     *
     * @param attachmentId String，清理失败附件 UUID
     * @param expectedRetryCount int，候选快照中的当前重试次数
     * @param nextRetryTime java.time.LocalDateTime，指数退避后的下次候选时间
     * @param errorCode String，固定脱敏错误码
     * @return int，成功调度返回 1；并发状态或版本变化返回 0
     */
    int scheduleCleanupRetry(@Param("attachmentId") String attachmentId,
            @Param("expectedRetryCount") int expectedRetryCount,
            @Param("nextRetryTime") java.time.LocalDateTime nextRetryTime,
            @Param("errorCode") String errorCode);
}
