package com.ruoyi.flowable.service.model;

import java.util.List;
import java.util.Objects;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;

/**
 * 部署事务内生成、尚未绑定 Flowable deploymentId 的编译结果。
 *
 * @param compiledBpmn byte[]，移除作者配置字段并固定调度器后的可执行资源
 * @param snapshots List&lt;WfDeployExtensionSnapshot&gt;，待绑定部署主键的执行快照
 */
public record WorkflowPreparedExtensionDeployment(byte[] compiledBpmn,
        List<WfDeployExtensionSnapshot> snapshots)
{
    /**
     * 防御性复制部署资源和快照集合，避免部署前被调用方修改。
     * @param compiledBpmn byte[]，已编译 BPMN 资源
     * @param snapshots List&lt;WfDeployExtensionSnapshot&gt;，未绑定部署主键的快照
     * @return 无返回值，构造后保存副本
     */
    public WorkflowPreparedExtensionDeployment
    {
        compiledBpmn = Objects.requireNonNull(compiledBpmn, "编译 BPMN 不能为空").clone();
        snapshots = Objects.requireNonNull(snapshots, "部署扩展快照不能为空").stream()
                .map(WorkflowPreparedExtensionDeployment::copySnapshot)
                .toList();
    }

    /**
     * 返回编译 BPMN 字节副本。
     * @return byte[]，调用方可安全传给 Flowable 部署器的资源副本
     */
    @Override
    public byte[] compiledBpmn()
    {
        return compiledBpmn.clone();
    }

    /**
     * 返回部署快照的元素级副本，调用方绑定部署主键时不会修改准备结果本身。
     * @return List&lt;WfDeployExtensionSnapshot&gt;，可由当前部署步骤安全修改的快照副本
     */
    @Override
    public List<WfDeployExtensionSnapshot> snapshots()
    {
        return snapshots.stream()
                .map(WorkflowPreparedExtensionDeployment::copySnapshot)
                .toList();
    }

    /**
     * 复制一条可变部署快照的全部业务字段。
     * @param source WfDeployExtensionSnapshot，待复制快照
     * @return WfDeployExtensionSnapshot，与源对象无共享可变状态的快照
     */
    private static WfDeployExtensionSnapshot copySnapshot(WfDeployExtensionSnapshot source)
    {
        Objects.requireNonNull(source, "部署扩展快照元素不能为空");
        WfDeployExtensionSnapshot copy = new WfDeployExtensionSnapshot();
        copy.setSnapshotId(source.getSnapshotId());
        copy.setDeployId(source.getDeployId());
        copy.setProcessKey(source.getProcessKey());
        copy.setElementId(source.getElementId());
        copy.setExtensionKey(source.getExtensionKey());
        copy.setExtensionVersionId(source.getExtensionVersionId());
        copy.setVersionNo(source.getVersionNo());
        copy.setExtensionType(source.getExtensionType());
        copy.setImplementationKey(source.getImplementationKey());
        copy.setConfigJson(source.getConfigJson());
        copy.setVersionChecksum(source.getVersionChecksum());
        copy.setSnapshotChecksum(source.getSnapshotChecksum());
        copy.setCreateBy(source.getCreateBy());
        copy.setCreateTime(source.getCreateTime() == null
                ? null : new java.util.Date(source.getCreateTime().getTime()));
        return copy;
    }
}
