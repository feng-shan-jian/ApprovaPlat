package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 锁定人工发起授权与 Flowable 内部子流程调用之间的架构边界。
 */
class WorkflowHumanStartBoundaryContractTest
{
    /**
     * 验证 assertCanStart 只由人工 API 发起服务调用，监听器和 CallActivity 服务不接入该门禁。
     * @return void，人工范围扩散到引擎内部调用链时测试失败
     * @throws Exception 读取生产源码失败
     */
    @Test
    void keepsHumanStartAuthorizationOutOfEngineCallActivityPath() throws Exception
    {
        Path sourceRoot = findSourceRoot();
        List<Path> javaFiles;
        try (var paths = Files.walk(sourceRoot))
        {
            javaFiles = paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
        List<String> callers = javaFiles.stream().filter(path ->
        {
            try
            {
                return Files.readString(path, StandardCharsets.UTF_8)
                        .contains(".assertCanStart(");
            }
            catch (Exception exception)
            {
                throw new IllegalStateException(exception);
            }
        }).map(path -> path.getFileName().toString()).toList();

        assertThat(callers).containsExactly("WorkflowProcessStartService.java");
        String startService = Files.readString(
                sourceRoot.resolve("service/process/WorkflowProcessStartService.java"),
                StandardCharsets.UTF_8);
        assertThat(startService).contains("runtimeService.startProcessInstanceById(")
                .doesNotContain("CallActivity", "SubProcess");
    }

    /**
     * 从 Maven 模块目录或仓库根目录定位生产 Java 源码。
     * @return Path，ruoyi-flowable 的 main/java 根目录
     */
    private Path findSourceRoot()
    {
        Path current = Path.of("").toAbsolutePath();
        Path moduleLocal = current.resolve("src/main/java/com/ruoyi/flowable");
        if (Files.isDirectory(moduleLocal)) return moduleLocal;
        Path reactorRoot = current.resolve(
                "ruoyi-flowable/src/main/java/com/ruoyi/flowable");
        if (Files.isDirectory(reactorRoot)) return reactorRoot;
        throw new IllegalStateException("无法定位 ruoyi-flowable 生产源码目录");
    }
}
