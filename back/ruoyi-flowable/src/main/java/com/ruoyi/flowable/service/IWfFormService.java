package com.ruoyi.flowable.service;

import java.util.Collection;
import java.util.List;
import com.ruoyi.flowable.domain.WfForm;

/**
 * 可编辑工作流表单模板业务服务。
 */
public interface IWfFormService
{
    /**
     * 查询有效表单模板。
     * @param formId Long，表单主键
     * @return WfForm，有效表单；不存在时返回 null
     */
    WfForm queryById(Long formId);

    /**
     * 查询有效表单模板列表。
     * @param filter WfForm，可选表单名称过滤条件
     * @return List&lt;WfForm&gt;，有效表单列表
     */
    List<WfForm> queryList(WfForm filter);

    /**
     * 查询不包含 content 的有界表单摘要，用于导出和超量判定。
     * @param filter WfForm，可选表单名称过滤条件
     * @param limit int，服务端请求上限，范围 1..10001
     * @return List&lt;WfForm&gt;，不超过上限的有效表单摘要
     */
    List<WfForm> querySummaryList(WfForm filter, int limit);

    /**
     * 校验并新增表单模板。
     * @param form WfForm，表单模板及创建审计信息
     * @return int，实际新增行数
     */
    int insertForm(WfForm form);

    /**
     * 校验并修改表单模板，不影响已有部署快照。
     * @param form WfForm，表单主键、模板内容及更新审计信息
     * @return int，实际修改行数
     */
    int updateForm(WfForm form);

    /**
     * 在完成快照、模型和已部署定义引用检查后批量逻辑删除表单。
     * @param formIds Collection&lt;Long&gt;，待删除表单主键集合
     * @param updateBy String，来自认证上下文的可信操作人账号
     * @return int，实际逻辑删除行数
     */
    int deleteWithValidByIds(Collection<Long> formIds, String updateBy);
}
