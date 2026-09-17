package com.ruoyi.flowable.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceUserRow;

/**
 * 工作流用户名称批量投影，不返回密码、联系方式等敏感字段。
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

    /** 工作台使用原始昵称；不得修剪昵称或回退账号名，也不排除历史停用、删除用户。 */
    List<WorkflowMultiInstanceUserRow> selectRawNickNamesByIds(
            @Param("userIds") List<Long> userIds);
}
