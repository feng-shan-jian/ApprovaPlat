package com.ruoyi.flowable.extension;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;

class WorkflowExtensionChecksumTest
{
    @Test
    void deployedSnapshotKeepsTheExistingLengthPrefixedUtf8Protocol()
    {
        var snapshot = new WfDeployExtensionSnapshot();
        snapshot.setDeployId("部署:9"); snapshot.setProcessKey("expense");
        snapshot.setElementId("node_a"); snapshot.setExtensionKey("builtin.http");
        snapshot.setExtensionVersionId(17L); snapshot.setVersionNo(3);
        snapshot.setExtensionType("SERVICE_TASK"); snapshot.setImplementationKey("http");
        snapshot.setConfigJson("{\"z\":\"值\", \"a\":[2,1]}");
        assertThat(WorkflowExtensionChecksum.snapshotChecksum(snapshot))
                .isEqualTo("d3e7adee79998a2bb4eb02d4d801bac9b2361eb82855a1fdd35b44218b149231");
        snapshot.setConfigJson("{\"a\":[2,1],\"z\":\"值\"}");
        snapshot.setVersionChecksum("");
        assertThat(WorkflowExtensionChecksum.snapshotChecksum(snapshot))
                .isEqualTo("d3e7adee79998a2bb4eb02d4d801bac9b2361eb82855a1fdd35b44218b149231");
        snapshot.setConfigJson("{\"a\":[1,2],\"z\":\"值\"}");
        assertThat(WorkflowExtensionChecksum.snapshotChecksum(snapshot))
                .isNotEqualTo("d3e7adee79998a2bb4eb02d4d801bac9b2361eb82855a1fdd35b44218b149231");
    }

    @Test
    void nullableNumbersKeepStringValueOfEncodingWhileTextNullKeepsEmptyEncoding()
    {
        var snapshot = new WfDeployExtensionSnapshot();
        snapshot.setConfigJson("null");
        assertThat(WorkflowExtensionChecksum.snapshotChecksum(snapshot))
                .isEqualTo("0125d989803e3f52d8a5499440b413590dcae3488fe2107b25638013ab802313");
        assertThat(WorkflowExtensionChecksum.sha256("ab", "c"))
                .isNotEqualTo(WorkflowExtensionChecksum.sha256("a", "bc"));
    }
}
