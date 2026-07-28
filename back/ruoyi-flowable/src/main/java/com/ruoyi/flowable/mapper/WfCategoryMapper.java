package com.ruoyi.flowable.mapper;

import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfCategory;

/**
 * 工作流分类数据访问层。
 */
public interface WfCategoryMapper
{
    /**
     * 查询有效分类。
     * @param categoryId Long，分类主键
     * @return WfCategory，有效分类；不存在时返回 null
     */
    WfCategory selectById(@Param("categoryId") Long categoryId);

    /**
     * 按可选条件查询有效分类列表。
     * @param filter WfCategory，分类名称和编码过滤条件
     * @return List&lt;WfCategory&gt;，有效分类列表
     */
    List<WfCategory> selectList(@Param("filter") WfCategory filter);

    /**
     * 按服务端上限查询导出分类，避免无界导出占用内存。
     * @param filter WfCategory，分类名称和编码过滤条件
     * @param limit int，服务端限定的最大返回行数
     * @return List&lt;WfCategory&gt;，不超过上限的有效分类列表
     */
    List<WfCategory> selectExportList(@Param("filter") WfCategory filter,
            @Param("limit") int limit);

    /**
     * 按分类编码查询有效分类。
     * @param code String，分类编码
     * @return WfCategory，有效分类；不存在时返回 null
     */
    WfCategory selectByCode(@Param("code") String code);

    /**
     * 新增分类。
     * @param category WfCategory，待新增分类及审计信息
     * @return int，受影响行数
     */
    int insert(@Param("category") WfCategory category);

    /**
     * 修改有效分类。
     * @param category WfCategory，待修改分类及审计信息
     * @return int，受影响行数
     */
    int update(@Param("category") WfCategory category);

    /**
     * 批量逻辑删除有效分类。
     * @param categoryIds Collection&lt;Long&gt;，分类主键集合
     * @param updateBy String，可信操作人账号
     * @return int，实际逻辑删除行数
     */
    int logicalDelete(@Param("categoryIds") Collection<Long> categoryIds,
            @Param("updateBy") String updateBy);

    /**
     * 统计指定主键中仍有效的分类数量。
     * @param categoryIds Collection&lt;Long&gt;，分类主键集合
     * @return int，有效分类数量
     */
    int countActiveByIds(@Param("categoryIds") Collection<Long> categoryIds);
}
