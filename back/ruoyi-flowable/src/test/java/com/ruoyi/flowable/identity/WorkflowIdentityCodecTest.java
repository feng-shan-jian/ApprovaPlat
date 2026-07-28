package com.ruoyi.flowable.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

class WorkflowIdentityCodecTest
{
    private final WorkflowIdentityCodec codec = new WorkflowIdentityCodec();

    /**
     * 验证数字用户 ID 会转换为 Flowable 使用的规范十进制形式。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void normalizesPositiveNumericUserIds()
    {
        assertThat(codec.normalizeUserId("00042")).isEqualTo("42");
        assertThat(codec.normalizeUserId(Long.toString(Long.MAX_VALUE)))
                .isEqualTo(Long.toString(Long.MAX_VALUE));
    }

    /**
     * 验证空值、负数、零、符号、空白、小数和溢出用户 ID 均被稳定拒绝。
     *
     * @param invalidUserId String，非法用户标识
     * @return 无返回值；断言失败时测试失败
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "0", "-1", "+1", "1.0", "1 ", " 1", "abc", "9223372036854775808" })
    void rejectsInvalidUserIds(String invalidUserId)
    {
        assertThatThrownBy(() -> codec.normalizeUserId(invalidUserId))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo("工作流用户标识无效");
                });
    }

    /**
     * 验证角色和部门候选组能被解析为明确类型和主键。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void parsesRoleAndDepartmentGroups()
    {
        assertThat(codec.parseCandidateGroup("ROLE12"))
                .isEqualTo(new WorkflowCandidateGroup(WorkflowCandidateGroupType.ROLE, 12L));
        assertThat(codec.parseCandidateGroup("DEPT34"))
                .isEqualTo(new WorkflowCandidateGroup(WorkflowCandidateGroupType.DEPT, 34L));
        assertThat(codec.roleGroup(12L)).isEqualTo("ROLE12");
        assertThat(codec.deptGroup(34L)).isEqualTo("DEPT34");
    }

    /**
     * 验证错前缀、空后缀、前导零、负数、零、空白和溢出候选组均被拒绝。
     *
     * @param invalidGroup String，非法候选组标识
     * @return 无返回值；断言失败时测试失败
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "ROLE", "DEPT", "ROLE0", "ROLE007", "DEPT003", "DEPT-1",
            "role1", "USER1", "ROLE_1", " ROLE1", "DEPT1 ", "ROLE9223372036854775808" })
    void rejectsInvalidCandidateGroups(String invalidGroup)
    {
        assertThatThrownBy(() -> codec.parseCandidateGroup(invalidGroup))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo("工作流候选组标识无效");
                });
    }
}
