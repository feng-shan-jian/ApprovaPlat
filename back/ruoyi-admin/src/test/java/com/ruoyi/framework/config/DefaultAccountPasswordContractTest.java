package com.ruoyi.framework.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.utils.SecurityUtils;

/**
 * 本地验收账号默认密码合同测试。
 */
class DefaultAccountPasswordContractTest
{
    /** SQL 基线中预置管理员 BCrypt 摘要的提取规则。 */
    private static final Pattern ADMIN_PASSWORD_PATTERN = Pattern.compile(
            "insert into sys_user values\\(1,.*?'(\\$2[aby]\\$[^']+)'\\s*,\\s*'0'",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * 验证全新安装管理员、用户初始密码和密码长度规则均允许统一口令 wang。
     * @return void，SQL 基线或后端密码下限发生漂移时测试失败
     * @throws Exception SQL 基线读取失败时测试失败
     */
    @Test
    void keepsWangAsTheUsableDefaultAccountPassword() throws Exception
    {
        Path baseline = Path.of("..", "sql", "ry_20260417.sql");
        String sql = Files.readString(baseline, StandardCharsets.UTF_8);
        Matcher matcher = ADMIN_PASSWORD_PATTERN.matcher(sql);

        assertThat(matcher.find()).as("SQL 基线必须包含已启用的 admin BCrypt 密码").isTrue();
        assertThat(SecurityUtils.matchesPassword("wang", matcher.group(1))).isTrue();
        assertThat(sql).contains(
                "'sys.user.initPassword',            'wang'",
                "'sys.account.initPasswordModify',   '0'");
        assertThat(UserConstants.PASSWORD_MIN_LENGTH).isLessThanOrEqualTo("wang".length());
    }
}
