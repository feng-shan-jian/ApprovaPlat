package com.ruoyi.flowable.service;

import java.util.Collection;

/**
 * 分类和表单删除前的 Flowable 及业务快照引用检查器。
 */
public interface WorkflowReferenceChecker
{
    /**
     * 判断分类编码是否被未部署模型或已部署流程定义引用。
     * @param categoryCode String，分类编码
     * @return boolean，存在引用或引用状态无法可靠判定时返回 true
     */
    boolean hasCategoryReference(String categoryCode);

    /**
     * 判断任一表单是否被部署快照、未部署模型或已部署流程定义引用。
     * @param formIds Collection&lt;Long&gt;，待检查表单主键集合
     * @return boolean，存在引用或引用状态无法可靠判定时返回 true
     */
    boolean hasFormReference(Collection<Long> formIds);
}
