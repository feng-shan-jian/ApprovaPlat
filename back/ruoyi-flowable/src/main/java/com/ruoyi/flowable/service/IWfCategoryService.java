package com.ruoyi.flowable.service;

import java.util.Collection;
import java.util.List;
import com.ruoyi.flowable.domain.WfCategory;

/**
 * 工作流分类业务服务。
 */
public interface IWfCategoryService
{
    /**
     * 查询有效分类。
     * @param categoryId Long，分类主键
     * @return WfCategory，有效分类；不存在时返回 null
     */
    WfCategory queryById(Long categoryId);

    /**
     * 查询有效分类列表。
     * @param filter WfCategory，可选名称和编码过滤条件
     * @return List&lt;WfCategory&gt;，有效分类列表
     */
    List<WfCategory> queryList(WfCategory filter);

    /**
     * 查询有界分类导出数据。
     * @param filter WfCategory，可选名称和编码过滤条件
     * @param limit int，服务端请求上限，范围 1..10001
     * @return List&lt;WfCategory&gt;，不超过上限的有效分类列表
     */
    List<WfCategory> queryExportList(WfCategory filter, int limit);

    /**
     * 新增分类。
     * @param category WfCategory，分类及创建审计信息
     * @return int，实际新增行数
     */
    int insertCategory(WfCategory category);

    /**
     * 修改分类。
     * @param category WfCategory，分类主键、修改内容及更新审计信息
     * @return int，实际修改行数
     */
    int updateCategory(WfCategory category);

    /**
     * 在完成真实模型和流程定义引用检查后批量逻辑删除分类。
     * @param categoryIds Collection&lt;Long&gt;，待删除分类主键集合
     * @param updateBy String，来自认证上下文的可信操作人账号
     * @return int，实际逻辑删除行数
     */
    int deleteWithValidByIds(Collection<Long> categoryIds, String updateBy);

    /**
     * 校验分类编码在当前有效记录中是否唯一。
     * @param category WfCategory，分类主键和待校验编码
     * @return boolean，唯一返回 true，否则返回 false
     */
    boolean checkCategoryCodeUnique(WfCategory category);
}
