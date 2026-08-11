package com.ruoyi.flowable.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorkflowControlledLoopMapperXmlTest
{
    /**
     * 验证循环运行审计 Mapper 支持幂等插入、详情查询和历史实例集合清理。
     * @return void，运行审计查询或清理契约不完整时测试失败
     * @throws Exception 读取 Mapper XML 失败
     */
    @Test
    void definesControlledLoopExecutionHistoryContract() throws Exception
    {
        String xml = readMapper("WfControlledLoopExecutionMapper.xml").toLowerCase();

        assertThat(xml).contains(
                "insert into wf_controlled_loop_execution",
                "select coalesce(max(iteration_no), 0)",
                "where process_instance_id = #{processinstanceid}",
                "<select id=\"countbyprocessinstanceids\" resulttype=\"long\">",
                "<delete id=\"deletebyprocessinstanceids\">",
                "<foreach collection=\"processinstanceids\" item=\"instanceid\"");
    }

    /**
     * 从当前 Maven 模块定位指定正式 Mapper XML。
     * @param fileName String，Mapper XML 文件名
     * @return String，UTF-8 Mapper XML 正文
     * @throws Exception 文件不存在或读取失败
     */
    private String readMapper(String fileName) throws Exception
    {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null)
        {
            Path candidate = current.resolve("src/main/resources/mapper/flowable").resolve(fileName);
            if (Files.isRegularFile(candidate))
            {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            candidate = current.resolve("ruoyi-flowable/src/main/resources/mapper/flowable")
                    .resolve(fileName);
            if (Files.isRegularFile(candidate))
            {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("未找到受控循环 Mapper XML: " + fileName);
    }
}
