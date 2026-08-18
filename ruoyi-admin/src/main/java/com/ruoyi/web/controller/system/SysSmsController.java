package com.ruoyi.web.controller.system;

import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.domain.integration.SmsConfigRequest;
import com.ruoyi.system.domain.integration.SmsSendRequest;
import com.ruoyi.system.service.integration.SysSmsService;

/**
 * 短信配置、测试发送和脱敏日志管理接口。
 */
@Validated
@RestController
@RequestMapping("/system/sms")
public class SysSmsController extends BaseController
{
    private final SysSmsService smsService;

    /**
     * 创建短信管理控制器。
     *
     * @param smsService SysSmsService，正式短信领域服务
     * @return void，构造后由 Spring 管理
     */
    public SysSmsController(SysSmsService smsService)
    {
        this.smsService = smsService;
    }

    /**
     * 查询脱敏短信配置。
     *
     * @return AjaxResult，配置列表
     */
    @PreAuthorize("@ss.hasPermi('system:sms:list')")
    @GetMapping("/configs")
    public AjaxResult configs()
    {
        return success(smsService.listConfigs());
    }

    /**
     * 新增短信配置。
     *
     * @param request SmsConfigRequest，配置请求
     * @return AjaxResult，新配置主键
     */
    @PreAuthorize("@ss.hasPermi('system:sms:add')")
    @Log(title = "短信配置", businessType = BusinessType.INSERT)
    @PostMapping("/configs")
    public AjaxResult addConfig(@Valid @RequestBody SmsConfigRequest request)
    {
        return success(Map.of("configId", smsService.createConfig(request, getUsername())));
    }

    /**
     * 修改短信配置。
     *
     * @param request SmsConfigRequest，包含主键的配置请求
     * @return AjaxResult，成功结果
     */
    @PreAuthorize("@ss.hasPermi('system:sms:edit')")
    @Log(title = "短信配置", businessType = BusinessType.UPDATE)
    @PutMapping("/configs")
    public AjaxResult editConfig(@Valid @RequestBody SmsConfigRequest request)
    {
        smsService.updateConfig(request, getUsername());
        return success();
    }

    /**
     * 启用唯一短信配置。
     *
     * @param configId long，配置主键
     * @return AjaxResult，成功结果
     */
    @PreAuthorize("@ss.hasPermi('system:sms:edit')")
    @Log(title = "短信配置启用", businessType = BusinessType.UPDATE)
    @PutMapping("/configs/{configId}/activate")
    public AjaxResult activate(@PathVariable @Positive long configId)
    {
        smsService.activate(configId, getUsername());
        return success();
    }

    /**
     * 删除没有审计引用的停用短信配置。
     *
     * @param configId long，配置主键
     * @return AjaxResult，成功结果
     */
    @PreAuthorize("@ss.hasPermi('system:sms:remove')")
    @Log(title = "短信配置", businessType = BusinessType.DELETE)
    @DeleteMapping("/configs/{configId}")
    public AjaxResult delete(@PathVariable @Positive long configId)
    {
        smsService.deleteConfig(configId);
        return success();
    }

    /**
     * 通过当前启用配置执行真实测试短信发送。
     *
     * @param request SmsSendRequest，接收人和供应商模板参数
     * @return AjaxResult，真实发送日志主键与脱敏供应商结果
     */
    @PreAuthorize("@ss.hasPermi('system:sms:send')")
    @Log(title = "短信测试发送", businessType = BusinessType.OTHER)
    @PostMapping("/send")
    public AjaxResult send(@Valid @RequestBody SmsSendRequest request)
    {
        return success(smsService.sendTest(request, getUsername()));
    }

    /**
     * 分页查询最近短信发送审计。
     *
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数，最大 100
     * @return TableDataInfo，若依标准 rows、total 分页响应
     */
    @PreAuthorize("@ss.hasPermi('system:sms:list')")
    @GetMapping("/logs")
    public TableDataInfo logs(
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") int pageNum,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "每页记录数必须大于0")
            @Max(value = 100, message = "每页记录数不能超过100") int pageSize)
    {
        return smsService.listLogs(pageNum, pageSize);
    }
}
