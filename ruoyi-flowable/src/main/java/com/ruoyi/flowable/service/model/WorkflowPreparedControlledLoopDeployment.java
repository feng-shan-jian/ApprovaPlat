package com.ruoyi.flowable.service.model;

import java.util.Arrays;
import java.util.List;
import com.ruoyi.flowable.domain.WfDeployControlledLoop;

/**
 * 受控循环部署编译生成的执行 BPMN 和待落库快照。
 *
 * @param compiledBpmn byte[]，已经转换为受控网关回路的可执行 BPMN
 * @param snapshots List&lt;WfDeployControlledLoop&gt;，尚未绑定部署主键的循环快照
 */
public record WorkflowPreparedControlledLoopDeployment(byte[] compiledBpmn,
        List<WfDeployControlledLoop> snapshots)
{
    /**
     * 防御复制可变字节数组和快照集合。
     * @return 无返回值，构造后结果不可修改
     */
    public WorkflowPreparedControlledLoopDeployment
    {
        compiledBpmn = Arrays.copyOf(compiledBpmn, compiledBpmn.length);
        snapshots = List.copyOf(snapshots);
    }

    /**
     * 返回可执行 BPMN 的独立副本。
     * @return byte[]，调用方可安全传给下一部署编译阶段的字节副本
     */
    @Override
    public byte[] compiledBpmn()
    {
        return Arrays.copyOf(compiledBpmn, compiledBpmn.length);
    }
}
