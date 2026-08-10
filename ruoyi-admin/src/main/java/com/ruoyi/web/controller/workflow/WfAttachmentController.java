package com.ruoyi.web.controller.workflow;

import java.nio.charset.StandardCharsets;
import jakarta.validation.constraints.NotBlank;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.annotation.RepeatSubmit;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.flowable.domain.vo.WorkflowAttachmentView;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentDownload;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentService;

/**
 * 工作流私有附件上传、元数据、对象级下载和未绑定删除接口。
 */
@Validated
@RestController
@RequestMapping("/workflow/attachment")
public class WfAttachmentController extends BaseController
{
    private final WorkflowAttachmentService attachmentService;

    /**
     * 创建工作流附件 Controller。
     *
     * @param attachmentService WorkflowAttachmentService，附件归属、存储和授权领域服务
     * @return 无返回值，构造后由 Spring 管理该 Controller
     */
    public WfAttachmentController(WorkflowAttachmentService attachmentService)
    {
        this.attachmentService = attachmentService;
    }

    /**
     * 为当前认证用户上传指定表单字段的临时附件。
     *
     * @param fieldName String，el-upload 组件对应的表单变量名
     * @param file MultipartFile，真实 multipart 文件内容
     * @return AjaxResult，只包含附件 UUID 和服务端计算的安全元数据
     */
    @PreAuthorize("@ss.hasPermi('workflow:attachment:upload')")
    @Log(title = "工作流附件", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AjaxResult upload(
            @RequestParam @NotBlank(message = "工作流附件表单字段不能为空") String fieldName,
            @RequestPart("file") MultipartFile file)
    {
        return success(attachmentService.uploadTemporary(fieldName, file));
    }

    /**
     * 查询当前用户可读取的附件安全元数据。
     *
     * @param attachmentId String，服务端生成的附件 UUID
     * @return AjaxResult，不包含内部存储键或静态 URL 的附件元数据
     */
    @PreAuthorize("@ss.hasPermi('workflow:attachment:query')")
    @GetMapping("/{attachmentId}")
    public AjaxResult metadata(
            @PathVariable @NotBlank(message = "工作流附件标识不能为空") String attachmentId)
    {
        WorkflowAttachmentView attachment = attachmentService
                .getReadableMetadata(attachmentId);
        return success(attachment);
    }

    /**
     * 通过附件或所属流程对象授权后流式下载私有文件。
     * 成功响应统一使用二进制 MIME，确保前端可与 JSON 业务错误无歧义地区分。
     *
     * @param attachmentId String，服务端生成的附件 UUID
     * @return ResponseEntity&lt;Resource&gt;，禁止缓存和 MIME 嗅探的受控下载响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:attachment:query')")
    @GetMapping("/{attachmentId}/content")
    public ResponseEntity<Resource> download(
            @PathVariable @NotBlank(message = "工作流附件标识不能为空") String attachmentId)
    {
        WorkflowAttachmentDownload download = attachmentService
                .openReadableDownload(attachmentId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.originalName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                // 原始 MIME 已保存在安全元数据中；下载响应固定为二进制，合法 JSON 附件不会被误判为 AjaxResult。
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(download.fileSize())
                .cacheControl(CacheControl.noStore())
                .eTag('"' + download.sha256() + '"')
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(download.content()));
    }

    /**
     * 仅由所有者删除仍未绑定流程对象的临时附件。
     *
     * @param attachmentId String，服务端生成的附件 UUID
     * @return AjaxResult，删除状态和私有文件清理均完成后的成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:attachment:remove')")
    @Log(title = "工作流附件", businessType = BusinessType.DELETE)
    @DeleteMapping("/{attachmentId}")
    public AjaxResult remove(
            @PathVariable @NotBlank(message = "工作流附件标识不能为空") String attachmentId)
    {
        attachmentService.deleteOwnedTemporary(attachmentId);
        return success();
    }
}
