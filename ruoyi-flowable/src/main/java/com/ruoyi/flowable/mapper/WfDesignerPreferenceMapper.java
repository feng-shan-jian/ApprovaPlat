package com.ruoyi.flowable.mapper;

import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfDesignerPreference;

/**
 * BPMN 设计器偏好数据访问层。
 */
public interface WfDesignerPreferenceMapper
{
    /**
     * 查询指定用户的正式设计器偏好。
     * @param userId Long，正式用户主键
     * @return WfDesignerPreference，不存在时返回 null
     */
    WfDesignerPreference selectByUserId(@Param("userId") Long userId);

    /**
     * 原子新增或覆盖指定用户的完整设计器偏好。
     * @param preference WfDesignerPreference，已校验且包含用户主键的完整偏好
     * @return int，受影响行数
     */
    int upsert(@Param("preference") WfDesignerPreference preference);
}
