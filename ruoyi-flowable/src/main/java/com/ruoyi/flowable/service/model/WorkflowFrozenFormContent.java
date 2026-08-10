package com.ruoyi.flowable.service.model;

import java.util.List;
import java.util.Objects;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;

/**
 * 自定义表单字段完成精确版本冻结后的部署结果。
 *
 * @param content String，写入 {@code wf_deploy_form} 的不可变表单 JSON
 * @param extensionSnapshots List&lt;WfDeployExtensionSnapshot&gt;，写入统一扩展部署台账的字段版本快照
 */
public record WorkflowFrozenFormContent(String content,
        List<WfDeployExtensionSnapshot> extensionSnapshots)
{
    /**
     * 创建防御性部署结果，禁止调用方替换快照集合。
     * @param content String，已经冻结版本元数据的正式表单 JSON
     * @param extensionSnapshots List&lt;WfDeployExtensionSnapshot&gt;，字段级扩展快照
     * @return 无返回值，构造后保存不可变集合
     */
    public WorkflowFrozenFormContent
    {
        Objects.requireNonNull(content, "冻结表单内容不能为空");
        extensionSnapshots = List.copyOf(Objects.requireNonNull(
                extensionSnapshots, "表单字段扩展快照不能为空"));
    }
}
