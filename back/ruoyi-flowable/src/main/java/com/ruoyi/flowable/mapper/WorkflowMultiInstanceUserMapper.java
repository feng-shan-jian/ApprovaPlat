package com.ruoyi.flowable.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceUserRow;

/**
 * 动态多实例成员名称批量查询 Mapper，不返回密码、联系方式等敏感字段。
 */
public interface WorkflowMultiInstanceUserMapper
{
    /**
     * 按成员主键批量查询最小用户名称投影，保留停用或逻辑删除用户的历史辨识信息。
     *
     * @param userIds List&lt;Long&gt;，服务端成员快照中的正整数用户主键
     * @return List&lt;WorkflowMultiInstanceUserRow&gt;，数据库存在的用户名称投影
     */
    List<WorkflowMultiInstanceUserRow> selectUserNamesByIds(
            @Param("userIds") List<Long> userIds);
}
