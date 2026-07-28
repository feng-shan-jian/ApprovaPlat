package com.ruoyi.framework.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.enums.UserStatus;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.mapper.SysUserMapper;

/**
 * 预置管理员一次性初始化状态流测试。
 */
class AdminBootstrapInitializerTest
{
    /** 仅用于测试密码门禁和 BCrypt 校验的固定夹具，不对应任何真实账号。 */
    private static final String TEST_ONLY_PASSWORD = "Fixture#Only9Password!Aa";

    /**
     * 验证待初始化标记会被 BCrypt 摘要替换，同时把预置管理员切换为启用状态。
     * @return void，原子更新参数、状态或写后校验不符合契约时测试失败
     */
    @Test
    void initializesMarkerAccountAndEnablesAdministrator()
    {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysUser storedUser = bootstrapUser(AdminBootstrapInitializer.BOOTSTRAP_PASSWORD_MARKER,
                UserStatus.DISABLE.getCode());
        when(userMapper.selectUserById(AdminBootstrapInitializer.BOOTSTRAP_ADMIN_USER_ID))
                .thenReturn(storedUser);
        when(userMapper.initializeBootstrapAdminCredential(
                eq(AdminBootstrapInitializer.BOOTSTRAP_ADMIN_USER_ID),
                eq(AdminBootstrapInitializer.BOOTSTRAP_ADMIN_USERNAME),
                eq(AdminBootstrapInitializer.BOOTSTRAP_PASSWORD_MARKER), anyString()))
                .thenAnswer(invocation -> {
                    // 模拟 Mapper 的条件更新：只有待初始化记录可以被启用，写入值来自初始化器。
                    if (!AdminBootstrapInitializer.BOOTSTRAP_PASSWORD_MARKER
                            .equals(storedUser.getPassword())
                            || !UserStatus.DISABLE.getCode().equals(storedUser.getStatus()))
                    {
                        return 0;
                    }
                    storedUser.setPassword(invocation.getArgument(3, String.class));
                    storedUser.setStatus(UserStatus.OK.getCode());
                    return 1;
                });

        AdminBootstrapInitializer initializer = new AdminBootstrapInitializer(
                userMapper, TEST_ONLY_PASSWORD);

        assertThatCode(() -> initializer.run(null)).doesNotThrowAnyException();

        ArgumentCaptor<String> encodedPassword = ArgumentCaptor.forClass(String.class);
        verify(userMapper).initializeBootstrapAdminCredential(
                eq(AdminBootstrapInitializer.BOOTSTRAP_ADMIN_USER_ID),
                eq(AdminBootstrapInitializer.BOOTSTRAP_ADMIN_USERNAME),
                eq(AdminBootstrapInitializer.BOOTSTRAP_PASSWORD_MARKER),
                encodedPassword.capture());
        verify(userMapper, times(2)).selectUserById(
                AdminBootstrapInitializer.BOOTSTRAP_ADMIN_USER_ID);
        assertThat(encodedPassword.getValue())
                .isNotEqualTo(TEST_ONLY_PASSWORD)
                .startsWith("$2");
        assertThat(SecurityUtils.matchesPassword(
                TEST_ONLY_PASSWORD, encodedPassword.getValue())).isTrue();
        assertThat(storedUser.getStatus()).isEqualTo(UserStatus.OK.getCode());
    }

    /**
     * 验证弱密码会在读取或修改正式管理员记录之前阻断启动。
     * @return void，弱密码未被拒绝或 Mapper 被提前调用时测试失败
     */
    @Test
    void rejectsWeakBootstrapPasswordBeforeDatabaseAccess()
    {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        AdminBootstrapInitializer initializer = new AdminBootstrapInitializer(
                userMapper, "Weak1!");

        assertThatThrownBy(() -> initializer.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RUOYI_BOOTSTRAP_ADMIN_PASSWORD 必须为 20-128 位可打印随机复杂密码");
        verify(userMapper, never()).selectUserById(
                AdminBootstrapInitializer.BOOTSTRAP_ADMIN_USER_ID);
        verify(userMapper, never()).initializeBootstrapAdminCredential(
                eq(AdminBootstrapInitializer.BOOTSTRAP_ADMIN_USER_ID),
                eq(AdminBootstrapInitializer.BOOTSTRAP_ADMIN_USERNAME),
                eq(AdminBootstrapInitializer.BOOTSTRAP_PASSWORD_MARKER), anyString());
    }

    /**
     * 验证管理员状态不是停用待初始化或已启用同密码时，初始化器拒绝修改记录。
     * @return void，非法状态触发更新或未中止启动时测试失败
     */
    @Test
    void rejectsAdministratorWithUnexpectedStatus()
    {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysUser storedUser = bootstrapUser(AdminBootstrapInitializer.BOOTSTRAP_PASSWORD_MARKER,
                UserStatus.DELETED.getCode());
        when(userMapper.selectUserById(AdminBootstrapInitializer.BOOTSTRAP_ADMIN_USER_ID))
                .thenReturn(storedUser);
        AdminBootstrapInitializer initializer = new AdminBootstrapInitializer(
                userMapper, TEST_ONLY_PASSWORD);

        assertThatThrownBy(() -> initializer.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("预置管理员不处于允许的一次性初始化状态");
        verify(userMapper, never()).initializeBootstrapAdminCredential(
                eq(AdminBootstrapInitializer.BOOTSTRAP_ADMIN_USER_ID),
                eq(AdminBootstrapInitializer.BOOTSTRAP_ADMIN_USERNAME),
                eq(AdminBootstrapInitializer.BOOTSTRAP_PASSWORD_MARKER), anyString());
    }

    /**
     * 验证初始化成功后携带同一密码再次启动时保持幂等，不重复写入密码摘要。
     * @return void，幂等重启触发更新或被错误拒绝时测试失败
     */
    @Test
    void allowsIdempotentRestartWithMatchingInitializedPassword()
    {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysUser storedUser = bootstrapUser(SecurityUtils.encryptPassword(TEST_ONLY_PASSWORD),
                UserStatus.OK.getCode());
        when(userMapper.selectUserById(AdminBootstrapInitializer.BOOTSTRAP_ADMIN_USER_ID))
                .thenReturn(storedUser);
        AdminBootstrapInitializer initializer = new AdminBootstrapInitializer(
                userMapper, TEST_ONLY_PASSWORD);

        assertThatCode(() -> initializer.run(null)).doesNotThrowAnyException();
        verify(userMapper, times(1)).selectUserById(
                AdminBootstrapInitializer.BOOTSTRAP_ADMIN_USER_ID);
        verify(userMapper, never()).initializeBootstrapAdminCredential(
                eq(AdminBootstrapInitializer.BOOTSTRAP_ADMIN_USER_ID),
                eq(AdminBootstrapInitializer.BOOTSTRAP_ADMIN_USERNAME),
                eq(AdminBootstrapInitializer.BOOTSTRAP_PASSWORD_MARKER), anyString());
    }

    /**
     * 构造符合 SQL 基线身份约束的预置管理员测试记录。
     * @param password String，待初始化标记或 BCrypt 密码摘要
     * @param status String，管理员业务状态码
     * @return SysUser，可供初始化器读取的非逻辑删除管理员记录
     */
    private SysUser bootstrapUser(String password, String status)
    {
        SysUser user = new SysUser();
        user.setUserId(AdminBootstrapInitializer.BOOTSTRAP_ADMIN_USER_ID);
        user.setUserName(AdminBootstrapInitializer.BOOTSTRAP_ADMIN_USERNAME);
        user.setPassword(password);
        user.setStatus(status);
        user.setDelFlag("0");
        return user;
    }
}
