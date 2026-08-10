package com.ruoyi.web.controller.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.annotation.RepeatSubmit;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.flowable.domain.WorkflowAttachmentStatus;
import com.ruoyi.flowable.domain.vo.WorkflowAttachmentView;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentDownload;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentService;

/**
 * 工作流附件 Controller 的真实 multipart 绑定、下载响应和权限契约测试。
 */
class WfAttachmentControllerTest
{
    private static final String ATTACHMENT_ID =
            "d9428888-122b-4c6f-8f0c-9c3e1dbd3210";
    private static final String SHA256 = "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    private WorkflowAttachmentService attachmentService;
    private MockMvc mockMvc;

    /**
     * 为每个测试创建独立附件服务替身和真实 Spring MVC 参数绑定链路。
     * @return void，无返回值
     */
    @BeforeEach
    void setUp()
    {
        attachmentService = mock(WorkflowAttachmentService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new WfAttachmentController(attachmentService)).build();
    }

    /**
     * 验证 multipart 上传使用真实字段名和文件体，并只返回安全附件元数据。
     * @return void，请求绑定、服务调用或响应投影不符合时测试失败
     * @throws Exception MockMvc 执行请求失败
     */
    @Test
    void uploadsMultipartContentAndReturnsSafeMetadata() throws Exception
    {
        byte[] content = "formal attachment".getBytes(StandardCharsets.UTF_8);
        WorkflowAttachmentView view = attachmentView(content.length);
        when(attachmentService.uploadTemporary(any(), any())).thenReturn(view);
        MockMultipartFile file = new MockMultipartFile(
                "file", "invoice.pdf", "application/pdf", content);

        String response = mockMvc.perform(multipart("/workflow/attachment")
                        .file(file)
                        .param("fieldName", "invoiceFiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(HttpStatus.SUCCESS))
                .andExpect(jsonPath("$.data.attachmentId").value(ATTACHMENT_ID))
                .andExpect(jsonPath("$.data.fieldName").value("invoiceFiles"))
                .andExpect(jsonPath("$.data.originalName").value("invoice.pdf"))
                .andExpect(jsonPath("$.data.fileSize").value(content.length))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        verify(attachmentService).uploadTemporary("invoiceFiles", file);
        assertThat(response).doesNotContain(
                "storageKey", "ownerUserId", "workflow-attachments", "url");
    }

    /**
     * 验证私有下载响应强制 attachment、no-store、nosniff、可信长度和内容摘要 ETag。
     * @return void，任一安全响应头或真实文件体缺失时测试失败
     * @throws Exception 创建测试文件或 MockMvc 执行失败
     */
    @Test
    void downloadsPrivateFileWithSafeResponseHeaders() throws Exception
    {
        byte[] content = "%PDF-private-content".getBytes(StandardCharsets.UTF_8);
        when(attachmentService.openReadableDownload(ATTACHMENT_ID)).thenReturn(
                new WorkflowAttachmentDownload(new ByteArrayInputStream(content), "invoice.pdf",
                        MediaType.APPLICATION_PDF_VALUE, content.length, SHA256));

        String contentDisposition = mockMvc.perform(
                        get("/workflow/attachment/{attachmentId}/content", ATTACHMENT_ID))
                .andExpect(status().isOk())
                .andExpect(content().bytes(content))
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, content.length))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.ETAG, '"' + SHA256 + '"'))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);

        assertThat(contentDisposition)
                .startsWith("attachment;")
                .contains("filename=\"invoice.pdf\"", "filename*=UTF-8''invoice.pdf");

        verify(attachmentService).openReadableDownload(ATTACHMENT_ID);
    }

    /**
     * 验证元数据和删除接口分别委托对象授权查询与仅所有者临时删除服务。
     * @return void，路径参数或成功协议映射错误时测试失败
     * @throws Exception MockMvc 执行请求失败
     */
    @Test
    void delegatesMetadataAndOwnedTemporaryDeletion() throws Exception
    {
        when(attachmentService.getReadableMetadata(ATTACHMENT_ID))
                .thenReturn(attachmentView(18L));

        mockMvc.perform(get("/workflow/attachment/{attachmentId}", ATTACHMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attachmentId").value(ATTACHMENT_ID));
        mockMvc.perform(delete("/workflow/attachment/{attachmentId}", ATTACHMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(HttpStatus.SUCCESS));

        verify(attachmentService).getReadableMetadata(ATTACHMENT_ID);
        verify(attachmentService).deleteOwnedTemporary(ATTACHMENT_ID);
    }

    /**
     * 验证四个入口使用独立最小权限，并保留上传/删除审计和防重复提交契约。
     * @return void，权限码、路由、日志类型或上传约束漂移时测试失败
     * @throws NoSuchMethodException Controller 方法签名不存在
     */
    @Test
    void keepsEndpointSecurityAndAuditContracts() throws NoSuchMethodException
    {
        Method upload = WfAttachmentController.class.getMethod(
                "upload", String.class, org.springframework.web.multipart.MultipartFile.class);
        Method metadata = WfAttachmentController.class.getMethod("metadata", String.class);
        Method download = WfAttachmentController.class.getMethod("download", String.class);
        Method remove = WfAttachmentController.class.getMethod("remove", String.class);

        assertThat(upload.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("@ss.hasPermi('workflow:attachment:upload')");
        assertThat(metadata.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("@ss.hasPermi('workflow:attachment:query')");
        assertThat(download.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("@ss.hasPermi('workflow:attachment:query')");
        assertThat(remove.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("@ss.hasPermi('workflow:attachment:remove')");

        assertThat(upload.getAnnotation(PostMapping.class).consumes())
                .containsExactly(MediaType.MULTIPART_FORM_DATA_VALUE);
        assertThat(metadata.getAnnotation(GetMapping.class).value())
                .containsExactly("/{attachmentId}");
        assertThat(download.getAnnotation(GetMapping.class).value())
                .containsExactly("/{attachmentId}/content");
        assertThat(remove.getAnnotation(DeleteMapping.class).value())
                .containsExactly("/{attachmentId}");
        assertThat(upload.getAnnotation(RepeatSubmit.class)).isNotNull();
        assertThat(upload.getAnnotation(Log.class).businessType())
                .isEqualTo(BusinessType.INSERT);
        assertThat(remove.getAnnotation(Log.class).businessType())
                .isEqualTo(BusinessType.DELETE);
    }

    /**
     * 创建不包含私有存储定位的附件响应对象。
     * @param fileSize long，测试响应中的真实文件大小
     * @return WorkflowAttachmentView，临时附件安全元数据
     */
    private WorkflowAttachmentView attachmentView(long fileSize)
    {
        return new WorkflowAttachmentView(
                ATTACHMENT_ID, "invoiceFiles", "invoice.pdf",
                MediaType.APPLICATION_PDF_VALUE, fileSize, SHA256,
                WorkflowAttachmentStatus.TEMP, LocalDateTime.now().plusHours(1),
                null, null, null);
    }
}
