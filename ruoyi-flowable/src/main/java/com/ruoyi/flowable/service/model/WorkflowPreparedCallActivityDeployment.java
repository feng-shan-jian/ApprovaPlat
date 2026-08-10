package com.ruoyi.flowable.service.model;

import java.util.List;
import java.util.Objects;
import com.ruoyi.flowable.domain.WfDeployCallActivitySnapshot;

/**
 * 调用活动完成权限、循环、映射与版本冻结后的部署准备结果。
 *
 * @param compiledBpmn byte[]，只引用精确子流程定义 ID 的可执行 BPMN
 * @param snapshots List&lt;WfDeployCallActivitySnapshot&gt;，待绑定父部署主键的依赖快照
 */
public record WorkflowPreparedCallActivityDeployment(byte[] compiledBpmn,
        List<WfDeployCallActivitySnapshot> snapshots)
{
    /**
     * 创建不可变部署准备结果。
     * @param compiledBpmn byte[]，可执行 BPMN 字节
     * @param snapshots List&lt;WfDeployCallActivitySnapshot&gt;，依赖快照
     * @return 无返回值，构造后复制字节和列表
     */
    public WorkflowPreparedCallActivityDeployment
    {
        compiledBpmn = Objects.requireNonNull(compiledBpmn, "调用活动编译资源不能为空").clone();
        snapshots = List.copyOf(Objects.requireNonNull(snapshots, "调用活动快照不能为空"));
    }

    /** @return byte[]，可执行 BPMN 字节副本。 */
    @Override
    public byte[] compiledBpmn() { return compiledBpmn.clone(); }
}
