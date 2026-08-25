package com.ruoyi.flowable.mapper;

import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfCopy;

/**
 * 工作流抄送记录数据访问层。
 */
public interface WfCopyMapper
{
    /**
     * 批量写入抄送记录，由数据库唯一键阻止同一事件重复抄送给同一用户。
     * @param copies List&lt;WfCopy&gt;，已由服务端解析并校验的抄送记录
     * @return int，实际写入行数
     */
    int insertBatch(@Param("copies") List<WfCopy> copies);

    /**
     * 幂等写入同一生命周期事件的抄送记录，并合并手工与自动重复来源。
     * @param copies List&lt;WfCopy&gt;，已校验的生命周期抄送记录
     * @return int，MySQL 实际影响行数；重复事件允许为零或更新行数
     */
    int insertBatchIdempotent(@Param("copies") List<WfCopy> copies);

    /**
     * 按抄送自然键批量读取当前有效的完整事实。
     * @param copies List&lt;WfCopy&gt;，仅使用 copyEventId 和 userId 作为查询键
     * @return List&lt;WfCopy&gt;，数据库当前仍有效的正式事实
     */
    List<WfCopy> selectActiveByNaturalKeys(@Param("copies") List<WfCopy> copies);

    /**
     * 按当前认证用户查询有效抄送，避免越权请求探测他人记录。
     * @param copyId Long，抄送记录主键
     * @param userId Long，当前认证用户主键
     * @return WfCopy，当前用户记录；不存在或越权时均返回 null
     */
    WfCopy selectByIdAndUserId(@Param("copyId") Long copyId,
            @Param("userId") Long userId);

    /**
     * 原子写入当前用户的首次阅读状态，重复调用不会覆盖首次时间。
     * @param copyId Long，抄送记录主键
     * @param userId Long，当前认证用户主键
     * @param updateBy String，可信操作用户主键
     * @return int，仅第一次标记返回 1，已读返回 0
     */
    int markRead(@Param("copyId") Long copyId, @Param("userId") Long userId,
            @Param("updateBy") String updateBy);

    /**
     * 统计当前用户符合条件的有效抄送记录，供领域层执行真实分页。
     * @param userId Long，当前登录用户主键
     * @param filter WfCopy，服务端构造的查询条件，不读取其中的 userId
     * @return long，符合条件的有效抄送记录总数
     */
    long countListByUserId(@Param("userId") Long userId,
            @Param("filter") WfCopy filter);

    /**
     * 分页查询当前用户符合条件的有效抄送记录。
     * @param userId Long，当前登录用户主键
     * @param filter WfCopy，服务端构造的查询条件，不读取其中的 userId
     * @param offset int，从零开始的安全分页偏移
     * @param limit int，本次最多返回的记录数
     * @return List&lt;WfCopy&gt;，按抄送时间和主键倒序的当前页记录
     */
    List<WfCopy> selectPageByUserId(@Param("userId") Long userId,
            @Param("filter") WfCopy filter, @Param("offset") int offset,
            @Param("limit") int limit);

    /**
     * 统计指定流程实例中当前用户的有效抄送记录，用于实例级对象授权。
     * @param instanceId String，流程实例主键
     * @param userId Long，当前登录用户主键
     * @return long，匹配的有效抄送记录数量
     */
    long countActiveByInstanceAndUser(@Param("instanceId") String instanceId,
            @Param("userId") Long userId);

    /**
     * 统计一组流程实例仍然有效的抄送记录，供历史删除前后执行引用一致性检查。
     * @param instanceIds Collection&lt;String&gt;，已由服务端去重并验证的流程实例主键集合
     * @return long，目标实例集合中的有效抄送记录数量
     */
    long countActiveByInstanceIds(@Param("instanceIds") Collection<String> instanceIds);

    /**
     * 按流程实例批量逻辑删除有效抄送记录，必须与 Flowable 历史删除处于同一事务。
     * @param instanceIds Collection&lt;String&gt;，包含目标实例及其子流程的完整实例主键集合
     * @param updateBy String，可信操作人的若依用户主键
     * @return int，实际逻辑删除的有效抄送记录数量
     */
    int logicalDeleteByInstanceIds(@Param("instanceIds") Collection<String> instanceIds,
            @Param("updateBy") String updateBy);
}
