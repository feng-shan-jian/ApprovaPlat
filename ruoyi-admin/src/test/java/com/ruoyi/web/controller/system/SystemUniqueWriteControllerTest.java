package com.ruoyi.web.controller.system;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.system.domain.SysConfig;
import com.ruoyi.system.exception.ConfigKeyConflictException;
import com.ruoyi.system.exception.UserNameConflictException;
import com.ruoyi.system.service.*;

/** Controller 消息协议测试；数据库原子性另由正式 MySQL IT 证明。 */
class SystemUniqueWriteControllerTest
{
    @Test
    void managementCreateKeepsItsOwnUsernameConflictMessage()
    {
        var service = mock(ISysUserService.class);
        var roles = mock(ISysRoleService.class);
        when(roles.selectRolesInScope(any(), any())).thenReturn(List.of());
        when(service.insertUser(any())).thenThrow(UserNameConflictException.translate(duplicate("sys_user.uk_sys_user_active_name")));
        var controller = new SysUserController() { @Override public String getUsername() { return "admin"; } };
        ReflectionTestUtils.setField(controller, "userService", service);
        ReflectionTestUtils.setField(controller, "roleService", roles);
        var user = new SysUser(); user.setUserName("existing"); user.setPassword("password");
        var result = controller.add(user);
        assertEquals(500, result.get("code"));
        assertEquals("新增用户'existing'失败，登录账号已存在", result.get("msg"));
        var primary = duplicate("sys_user.PRIMARY");
        doThrow(primary).when(service).insertUser(any());
        assertSame(primary, assertThrows(DuplicateKeyException.class, () -> controller.add(user)));
    }

    @Test
    void configAddAndEditKeepTheirOwnMessagesAndUnknownFailuresEscape()
    {
        var service = mock(ISysConfigService.class);
        var conflict = ConfigKeyConflictException.translate(duplicate("sys_config.uk_sys_config_key"));
        when(service.insertConfig(any())).thenThrow(conflict);
        when(service.updateConfig(any())).thenThrow(conflict);
        var controller = new SysConfigController() { @Override public String getUsername() { return "admin"; } };
        ReflectionTestUtils.setField(controller, "configService", service);
        var config = new SysConfig(); config.setConfigName("配置"); config.setConfigKey("key");
        assertEquals("新增参数'配置'失败，参数键名已存在", controller.add(config).get("msg"));
        assertEquals("修改参数'配置'失败，参数键名已存在", controller.edit(config).get("msg"));
        var unknown = duplicate("sys_config.unrelated");
        doThrow(unknown).when(service).updateConfig(any());
        assertSame(unknown, assertThrows(DuplicateKeyException.class, () -> controller.edit(config)));
    }

    @Test
    void lookalikeTextWrongVendorCodeAndWrongStateAreNotKnownConflicts()
    {
        for (var cause : List.of(
                new SQLException("Duplicate entry 'uk_sys_user_active_name' for key 'sys_user.PRIMARY'", "23000", 1062),
                new SQLException("Duplicate entry 'x' for key 'sys_user.uk_sys_user_active_name'", "HY000", 1062),
                new SQLException("Duplicate entry 'x' for key 'sys_user.uk_sys_user_active_name'", "23000", 9999),
                new SQLException("Duplicate entry 'uk_sys_config_key' for key 'sys_config.unknown'", "23000", 1062)))
        {
            var error = new DuplicateKeyException("SQL write failed", cause);
            assertSame(error, UserNameConflictException.translate(error));
            assertSame(error, ConfigKeyConflictException.translate(error));
        }
    }

    private DuplicateKeyException duplicate(String key)
    {
        return new DuplicateKeyException("SQL write failed",
                new SQLException("Duplicate entry 'x' for key '" + key + "'", "23000", 1062));
    }
}
