package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.system.service.ISysUserService;

class WorkflowProcessQuerySupportTest
{
    @Test
    void pageCachePreservesNamesMissingIdentitiesAndQueryFailures()
    {
        ISysUserService users = mock(ISysUserService.class);
        SysUser named = new SysUser();
        named.setNickName("申请人");
        when(users.selectUserById(1L)).thenReturn(named);
        when(users.selectUserById(2L)).thenReturn(new SysUser());
        var page = new WorkflowProcessQuerySupport.UserNameCache(users);
        assertThat(page.resolve(null)).isNull();
        assertThat(page.resolve(" ")).isNull();
        assertThat(page.resolve("legacy-user")).isEqualTo("legacy-user");
        assertThat(page.resolve("1")).isEqualTo("申请人");
        assertThat(page.resolve("1")).isEqualTo("申请人");
        assertThat(page.resolve("2")).isEqualTo("2");
        assertThat(page.resolve("3")).isEqualTo("3");
        assertThat(page.resolve("3")).isEqualTo("3");
        verify(users).selectUserById(1L);
        verify(users).selectUserById(2L);
        verify(users).selectUserById(3L);
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(users.selectUserById(4L)).thenThrow(failure);
        assertThatThrownBy(() -> page.resolve("4")).isSameAs(failure);
        var nextPage = new WorkflowProcessQuerySupport.UserNameCache(users);
        assertThat(nextPage.resolve("1")).isEqualTo("申请人");
        verify(users, times(2)).selectUserById(1L);
    }
}
