package com.ruoyi.flowable.service.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 锁定人工催办跨 CallActivity 流程树的数据库锁和通知定位契约。
 */
class WorkflowNotificationCallActivityContractTest
{
    /**
     * 验证催办先冻结根 execution 树，再锁定子实例任务并以实际任务身份生成通知。
     *
     * @return void，根树锁、子任务定位或根审计任一契约漂移时测试失败
     * @throws Exception 读取生产源码失败
     */
    @Test
    void keepsRootTreeLockAndActualChildTaskNotificationIdentity() throws Exception
    {
        String source = Files.readString(findServiceSource(), StandardCharsets.UTF_8);

        assertThat(source)
                .contains("RuntimeProcessSnapshot process = lockRuntimeProcessTree(processInstanceId)")
                .contains("where ROOT_PROC_INST_ID_=? or PROC_INST_ID_=?")
                .contains("List<LockedTask> tasks = lockRuntimeTasks(process.processInstanceIds())")
                .contains("getProcessDefinition(task.processDefinitionId())")
                .contains("task.processInstanceId(), task.taskId(), task.taskDefinitionKey()")
                .contains("insertUrgeAudit(processInstanceId, actor")
                .doesNotContain("!process.processDefinitionId().equals(task.processDefinitionId())");
    }

    /**
     * 从 Maven 模块目录或仓库根目录定位通知领域服务源码。
     *
     * @return Path，WorkflowNotificationService.java 的绝对路径
     */
    private Path findServiceSource()
    {
        Path current = Path.of("").toAbsolutePath();
        Path moduleLocal = current.resolve(
                "src/main/java/com/ruoyi/flowable/service/notification/WorkflowNotificationService.java");
        if (Files.isRegularFile(moduleLocal)) return moduleLocal;
        Path reactorRoot = current.resolve(
                "ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/notification/WorkflowNotificationService.java");
        if (Files.isRegularFile(reactorRoot)) return reactorRoot;
        throw new IllegalStateException("无法定位通知领域服务源码");
    }
}
