package com.ruoyi.web.controller.system.dto;

import java.util.List;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 角色菜单增量授权请求，只允许追加正式菜单关联，不承载替换语义。
 *
 * @param menuIds List&lt;Long&gt;，需要追加到目标角色的菜单主键，最多100项
 */
public record SysRoleMenuGrantRequest(
        @NotEmpty(message = "待授权菜单不能为空")
        @Size(max = 100, message = "单次最多授权100个菜单")
        List<@NotNull(message = "菜单主键不能为空")
                @Positive(message = "菜单主键必须为正数") Long> menuIds)
{
}
