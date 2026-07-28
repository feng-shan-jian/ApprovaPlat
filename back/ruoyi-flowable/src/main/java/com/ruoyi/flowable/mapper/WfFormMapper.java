package com.ruoyi.flowable.mapper;

import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfForm;

/**
 * 可编辑工作流表单模板数据访问层。
 */
public interface WfFormMapper
{
    /**
     * 查询有效表单模板。
     * @param formId Long，表单主键
     * @return WfForm，有效表单；不存在时返回 null
     */
    WfForm selectById(@Param("formId") Long formId);

    /**
     * 按可选条件查询有效表单模板。
     * @param filter WfForm，表单名称过滤条件
     * @return List&lt;WfForm&gt;，有效表单列表
     */
    List<WfForm> selectList(@Param("filter") WfForm filter);

    /**
     * 查询不包含大字段 content 的有界表单摘要，用于导出前超量判定。
     * @param filter WfForm，表单名称过滤条件
     * @param limit int，服务端限定的最大返回行数
     * @return List&lt;WfForm&gt;，不超过上限且 content 未加载的有效表单摘要
     */
    List<WfForm> selectSummaryList(@Param("filter") WfForm filter,
            @Param("limit") int limit);

    /**
     * 新增表单模板。
     * @param form WfForm，待新增表单及审计信息
     * @return int，受影响行数
     */
    int insert(@Param("form") WfForm form);

    /**
     * 修改有效表单模板。
     * @param form WfForm，待修改表单及审计信息
     * @return int，受影响行数
     */
    int update(@Param("form") WfForm form);

    /**
     * 批量逻辑删除有效表单模板。
     * @param formIds Collection&lt;Long&gt;，表单主键集合
     * @param updateBy String，可信操作人账号
     * @return int，实际逻辑删除行数
     */
    int logicalDelete(@Param("formIds") Collection<Long> formIds,
            @Param("updateBy") String updateBy);

    /**
     * 统计指定主键中仍有效的表单数量。
     * @param formIds Collection&lt;Long&gt;，表单主键集合
     * @return int，有效表单数量
     */
    int countActiveByIds(@Param("formIds") Collection<Long> formIds);
}
