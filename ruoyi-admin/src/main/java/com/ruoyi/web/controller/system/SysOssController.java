package com.ruoyi.web.controller.system;

import java.io.InputStream;
import java.util.Map;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.file.FileUtils;
import com.ruoyi.system.domain.integration.OssConfigRequest;
import com.ruoyi.system.service.integration.SysOssService;
import com.ruoyi.system.service.integration.SysOssService.OpenedOssObject;
import com.ruoyi.system.service.integration.SysOssService.StoredOssObject;

/**
 * S3 兼容 OSS 配置、对象上传下载和生命周期管理接口。
 */
@Validated
@RestController
@RequestMapping("/system/oss")
public class SysOssController extends BaseController
{
    private final SysOssService ossService;

    /**
     * 创建 OSS 管理控制器。
     *
     * @param ossService SysOssService，正式对象存储服务
     * @return void，构造后由 Spring 管理
     */
    public SysOssController(SysOssService ossService)
    {
        this.ossService = ossService;
    }

    /**
     * 查询脱敏 OSS 配置。
     *
     * @return AjaxResult，配置列表
     */
    @PreAuthorize("@ss.hasPermi('system:oss:list')")
    @GetMapping("/configs")
    public AjaxResult configs()
    {
        return success(ossService.listConfigs());
    }

    /**
     * 新增停用状态 OSS 配置。
     *
     * @param request OssConfigRequest，配置请求
     * @return AjaxResult，新配置主键
     */
    @PreAuthorize("@ss.hasPermi('system:oss:add')")
    @Log(title = "OSS 配置", businessType = BusinessType.INSERT)
    @PostMapping("/configs")
    public AjaxResult addConfig(@Valid @RequestBody OssConfigRequest request)
    {
        return success(Map.of("configId", ossService.createConfig(request, getUsername())));
    }

    /**
     * 修改 OSS 配置。
     *
     * @param request OssConfigRequest，包含主键的配置请求
     * @return AjaxResult，成功结果
     */
    @PreAuthorize("@ss.hasPermi('system:oss:edit')")
    @Log(title = "OSS 配置", businessType = BusinessType.UPDATE)
    @PutMapping("/configs")
    public AjaxResult editConfig(@Valid @RequestBody OssConfigRequest request)
    {
        ossService.updateConfig(request, getUsername());
        return success();
    }

    /**
     * 对指定配置执行真实 HeadBucket 连通性验证。
     *
     * @param configId long，配置主键
     * @return AjaxResult，真实 HTTP 验证结果
     */
    @PreAuthorize("@ss.hasPermi('system:oss:test')")
    @PostMapping("/configs/{configId}/test")
    public AjaxResult test(@PathVariable @Positive long configId)
    {
        return success(ossService.testConfig(configId));
    }

    /**
     * 启用唯一 OSS 配置。
     *
     * @param configId long，配置主键
     * @return AjaxResult，成功结果
     */
    @PreAuthorize("@ss.hasPermi('system:oss:edit')")
    @Log(title = "OSS 配置启用", businessType = BusinessType.UPDATE)
    @PutMapping("/configs/{configId}/activate")
    public AjaxResult activate(@PathVariable @Positive long configId)
    {
        ossService.activate(configId, getUsername());
        return success();
    }

    /**
     * 删除没有对象台账引用的停用 OSS 配置。
     *
     * @param configId long，配置主键
     * @return AjaxResult，成功结果
     */
    @PreAuthorize("@ss.hasPermi('system:oss:remove')")
    @Log(title = "OSS 配置", businessType = BusinessType.DELETE)
    @DeleteMapping("/configs/{configId}")
    public AjaxResult deleteConfig(@PathVariable @Positive long configId)
    {
        ossService.deleteConfig(configId);
        return success();
    }

    /**
     * 查询对象元数据台账。
     *
     * @return AjaxResult，最近 1000 条对象事实
     */
    @PreAuthorize("@ss.hasPermi('system:oss:list')")
    @GetMapping("/objects")
    public AjaxResult objects()
    {
        return success(ossService.listObjects());
    }

    /**
     * 上传对象并返回正式对象主键和安全访问投影。
     *
     * @param file MultipartFile，客户端文件
     * @return AjaxResult，对象元数据
     */
    @PreAuthorize("@ss.hasPermi('system:oss:upload')")
    @Log(title = "OSS 对象上传", businessType = BusinessType.INSERT)
    @PostMapping(value = "/objects", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AjaxResult upload(@RequestPart("file") MultipartFile file)
    {
        return success(ossService.upload(file, getUsername()));
    }

    /**
     * 经权限校验代理下载私有或公开对象，不暴露内部对象键。
     *
     * @param objectId long，对象主键
     * @param response HttpServletResponse，下载响应
     * @return void，对象流直接写入响应
     * @throws Exception 响应流写入失败
     */
    @PreAuthorize("@ss.hasPermi('system:oss:download')")
    @GetMapping("/objects/{objectId}/download")
    public void download(@PathVariable @Positive long objectId, HttpServletResponse response) throws Exception
    {
        OpenedOssObject object = ossService.open(objectId);
        response.setContentType(object.contentType());
        response.setContentLengthLong(object.fileSize());
        FileUtils.setAttachmentResponseHeader(response, object.originalName());
        try (InputStream input = object.content())
        {
            input.transferTo(response.getOutputStream());
        }
    }

    /**
     * 删除对象并驱动 DELETE_PENDING 状态机。
     *
     * @param objectId long，对象主键
     * @return AjaxResult，成功结果
     */
    @PreAuthorize("@ss.hasPermi('system:oss:remove')")
    @Log(title = "OSS 对象删除", businessType = BusinessType.DELETE)
    @DeleteMapping("/objects/{objectId}")
    public AjaxResult deleteObject(@PathVariable @Positive long objectId)
    {
        ossService.delete(objectId, getUsername());
        return success();
    }
}
