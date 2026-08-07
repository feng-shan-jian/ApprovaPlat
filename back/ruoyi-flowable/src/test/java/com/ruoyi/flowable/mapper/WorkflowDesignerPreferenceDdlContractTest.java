package com.ruoyi.flowable.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class WorkflowDesignerPreferenceDdlContractTest
{
    /**
     * 验证首个正式基线中的偏好表具备用户外键、主题和全部布尔约束。
     * @return void，正式偏好表可能产生孤儿记录或非法状态时测试失败
     * @throws Exception 正式 SQL 无法读取时测试失败
     */
    @Test
    void definesConstrainedNonDestructivePreferenceTable() throws Exception
    {
        String ddl = Files.readString(findProjectSql(
                "sql/flowable/business/8.0.0__workflow_business.sql"),
                StandardCharsets.UTF_8).toLowerCase();
        Pattern destructive = Pattern.compile(
                "(?im)^\\s*(drop|delete|update|alter|truncate|replace|call|set)\\b");

        assertThat(ddl).contains(
                "create table if not exists `wf_designer_preference`",
                "primary key (`user_id`)",
                "references `sys_user` (`user_id`)",
                "check (`theme` in ('light', 'dark', 'system'))",
                "`grid_enabled` in (0, 1)",
                "`minimap_enabled` in (0, 1)",
                "`lint_enabled` in (0, 1)",
                "`token_simulation_enabled` in (0, 1)",
                "`properties_collapsed` in (0, 1)");
        assertThat(destructive.matcher(ddl).find()).isFalse();
    }

    /**
     * 从模块或后端聚合工程目录向上定位正式 SQL。
     * @param relativePath String，以 back 为基准的 SQL 相对路径
     * @return Path，存在的正式 SQL 绝对路径
     */
    private Path findProjectSql(String relativePath)
    {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null)
        {
            Path direct = current.resolve(relativePath);
            if (Files.isRegularFile(direct))
            {
                return direct;
            }
            Path nested = current.resolve("back").resolve(relativePath);
            if (Files.isRegularFile(nested))
            {
                return nested;
            }
            current = current.getParent();
        }
        throw new AssertionError("未找到设计器偏好 SQL");
    }
}
