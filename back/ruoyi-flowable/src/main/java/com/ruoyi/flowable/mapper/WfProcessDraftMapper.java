package com.ruoyi.flowable.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfProcessDraft;

/**
 * 流程申请草稿正式数据访问层。
 */
public interface WfProcessDraftMapper
{
    /**
     * 写入包含不可变部署表单快照的新草稿。
     *
     * @param draft WfProcessDraft，已完成定义、权限和字段校验的草稿
     * @return int，成功写入返回 1
     */
    int insert(WfProcessDraft draft);

    /**
     * 按草稿主键和所有者查询详情，越权与不存在统一返回空。
     *
     * @param draftId String，草稿 UUID
     * @param ownerUserId Long，当前正式用户主键
     * @return WfProcessDraft，本人草稿或 null
     */
    WfProcessDraft selectOwnedById(@Param("draftId") String draftId,
            @Param("ownerUserId") Long ownerUserId);

    /**
     * 在写事务中锁定本人草稿，串行化保存、删除和提交。
     *
     * @param draftId String，草稿 UUID
     * @param ownerUserId Long，当前正式用户主键
     * @return WfProcessDraft，锁定草稿或 null
     */
    WfProcessDraft selectOwnedByIdForUpdate(@Param("draftId") String draftId,
            @Param("ownerUserId") Long ownerUserId);

    /**
     * 统计当前用户活动草稿列表。
     *
     * @param ownerUserId Long，当前正式用户主键
     * @param processName String，转义后的流程名称模糊条件
     * @param updatedAfter LocalDateTime，更新时间下界
     * @param updatedBefore LocalDateTime，更新时间上界
     * @return long，当前用户满足条件的活动草稿数
     */
    long countOwnedActive(@Param("ownerUserId") Long ownerUserId,
            @Param("processName") String processName,
            @Param("updatedAfter") LocalDateTime updatedAfter,
            @Param("updatedBefore") LocalDateTime updatedBefore);

    /**
     * 分页查询当前用户活动草稿。
     *
     * @param ownerUserId Long，当前正式用户主键
     * @param processName String，转义后的流程名称模糊条件
     * @param updatedAfter LocalDateTime，更新时间下界
     * @param updatedBefore LocalDateTime，更新时间上界
     * @param offset int，分页偏移
     * @param limit int，单页上限
     * @return List&lt;WfProcessDraft&gt;，更新时间倒序的本人草稿
     */
    List<WfProcessDraft> selectOwnedActivePage(@Param("ownerUserId") Long ownerUserId,
            @Param("processName") String processName,
            @Param("updatedAfter") LocalDateTime updatedAfter,
            @Param("updatedBefore") LocalDateTime updatedBefore,
            @Param("offset") int offset, @Param("limit") int limit);

    /**
     * 使用状态和版本双条件保存字段值。
     *
     * @param draftId String，草稿 UUID
     * @param ownerUserId Long，草稿所有者
     * @param expectedRevision long，期望乐观锁版本
     * @param formValues String，已校验字段 JSON
     * @param multiInstanceUserIds String，已校验发起成员 JSON
     * @param businessKey String，可为空的业务主键
     * @return int，成功更新返回 1，竞争失败返回 0
     */
    int updateActive(@Param("draftId") String draftId,
            @Param("ownerUserId") Long ownerUserId,
            @Param("expectedRevision") long expectedRevision,
            @Param("formValues") String formValues,
            @Param("multiInstanceUserIds") String multiInstanceUserIds,
            @Param("businessKey") String businessKey);

    /**
     * 使用状态和版本双条件软删除草稿。
     *
     * @param draftId String，草稿 UUID
     * @param ownerUserId Long，草稿所有者
     * @param expectedRevision long，期望乐观锁版本
     * @return int，成功删除返回 1，竞争失败返回 0
     */
    int markDeleted(@Param("draftId") String draftId,
            @Param("ownerUserId") Long ownerUserId,
            @Param("expectedRevision") long expectedRevision);

    /**
     * 在实例和附件均成功后将活动草稿原子置为已提交。
     *
     * @param draftId String，草稿 UUID
     * @param ownerUserId Long，草稿所有者
     * @param expectedRevision long，期望乐观锁版本
     * @param processInstanceId String，本次事务创建的真实实例主键
     * @param formValues String，正式提交采用的完整字段 JSON
     * @param multiInstanceUserIds String，正式提交采用的发起成员 JSON
     * @param businessKey String，正式提交采用的业务主键
     * @return int，成功更新返回 1，竞争失败返回 0
     */
    int markSubmitted(@Param("draftId") String draftId,
            @Param("ownerUserId") Long ownerUserId,
            @Param("expectedRevision") long expectedRevision,
            @Param("processInstanceId") String processInstanceId,
            @Param("formValues") String formValues,
            @Param("multiInstanceUserIds") String multiInstanceUserIds,
            @Param("businessKey") String businessKey);
}
