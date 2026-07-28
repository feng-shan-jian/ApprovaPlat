package com.ruoyi.web.controller.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentStorage;
import com.ruoyi.framework.config.ServerConfig;

/**
 * 通用上传下载入口与工作流私有附件目录隔离的真实 MVC 契约测试。
 */
class CommonControllerResourceDownloadTest
{
    @TempDir
    Path profileRoot;

    /** 测试前原始全局 profile，结束后必须恢复避免污染其他测试。 */
    private String originalProfile;

    /** 真实 Spring MVC 参数绑定和响应写出链。 */
    private MockMvc mockMvc;

    /**
     * 切换到隔离 profile 并创建通用 Controller。
     *
     * @return void，无返回值
     */
    @BeforeEach
    void setUp()
    {
        originalProfile = RuoYiConfig.getProfile();
        new RuoYiConfig().setProfile(profileRoot.toString());
        ServerConfig serverConfig = mock(ServerConfig.class);
        when(serverConfig.getUrl()).thenReturn("http://localhost");
        CommonController controller = new CommonController();
        ReflectionTestUtils.setField(controller, "serverConfig", serverConfig);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /**
     * 恢复全局 profile，防止静态配置影响同一 JVM 内其他测试。
     *
     * @return void，无返回值
     */
    @AfterEach
    void restoreProfile()
    {
        new RuoYiConfig().setProfile(originalProfile);
    }

    /**
     * 验证通用资源下载仍可返回公开文件，但真实私有文件只能得到 404 且无正文和下载头。
     *
     * @return void，公开资源被误伤或私有正文泄漏时测试失败
     * @throws Exception 创建文件或执行 MockMvc 请求失败
     */
    @Test
    void servesPublicProfileButRejectsPrivateWorkflowAttachment() throws Exception
    {
        byte[] publicContent = "public profile".getBytes(StandardCharsets.UTF_8);
        byte[] privateContent = "private workflow attachment".getBytes(StandardCharsets.UTF_8);
        Path publicFile = profileRoot.resolve("upload/2026/public.pdf");
        Path privateFile = profileRoot.resolve(
                WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME + "/2026/secret.pdf");
        Files.createDirectories(publicFile.getParent());
        Files.createDirectories(privateFile.getParent());
        Files.write(publicFile, publicContent);
        Files.write(privateFile, privateContent);

        mockMvc.perform(get("/common/download/resource")
                        .param("resource", "/profile/upload/2026/public.pdf"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(publicContent))
                .andExpect(header().exists(HttpHeaders.CONTENT_DISPOSITION));

        mockMvc.perform(get("/common/download/resource")
                        .param("resource", "/profile/workflow-attachments/2026/secret.pdf"))
                .andExpect(status().isNotFound())
                .andExpect(content().bytes(new byte[0]))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION))
                .andExpect(header().doesNotExist("download-filename"));

        mockMvc.perform(get("/common/download")
                        .param("fileName", "workflow-attachments/2026/secret.pdf")
                        .param("delete", "false"))
                .andExpect(status().isNotFound())
                .andExpect(content().bytes(new byte[0]));
    }

    /**
     * 验证通用上传即使收到路径型原文件名也只能写入固定公开 upload 根。
     *
     * @return void，客户端文件名可选择工作流私有目录时测试失败
     * @throws Exception 执行 multipart 请求或遍历隔离目录失败
     */
    @Test
    void confinesGenericUploadToPublicUploadDirectory() throws Exception
    {
        MockMultipartFile file = new MockMultipartFile("file",
                "../workflow-attachments/secret.pdf", "application/pdf",
                "%PDF-public-upload".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/common/upload").file(file))
                .andExpect(status().isOk());

        Path uploadRoot = profileRoot.resolve("upload");
        try (var paths = Files.walk(uploadRoot))
        {
            assertThat(paths.filter(Files::isRegularFile)).hasSize(1);
        }
        assertThat(profileRoot.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME))
                .doesNotExist();
    }

    /**
     * 验证 download 和 upload 公开目录中的真实符号链接都不能转向工作流私有正文。
     * @return void，任一通用下载入口可经别名读取私有附件时测试失败
     * @throws Exception 创建隔离文件、测试链接或执行 MockMvc 请求失败
     */
    @Test
    void rejectsPublicDirectoryAliasesIntoPrivateAttachmentRoot() throws Exception
    {
        byte[] privateContent = "private workflow attachment".getBytes(StandardCharsets.UTF_8);
        Path privateRoot = profileRoot.resolve(
                WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME);
        Path privateFile = privateRoot.resolve("2026/07/26/secret.pdf");
        Files.createDirectories(privateFile.getParent());
        Files.write(privateFile, privateContent);

        Path uploadAlias = profileRoot.resolve("upload/private-alias");
        Path downloadAlias = profileRoot.resolve("download/private-alias");
        Files.createDirectories(uploadAlias.getParent());
        Files.createDirectories(downloadAlias.getParent());
        assumeTrue(createSymbolicLink(uploadAlias, privateRoot)
                        && createSymbolicLink(downloadAlias, privateRoot),
                "当前文件系统不允许创建符号链接，Linux CI 必须执行该攻击回归");

        mockMvc.perform(get("/common/download/resource")
                        .param("resource",
                                "/profile/upload/private-alias/2026/07/26/secret.pdf"))
                .andExpect(status().isNotFound())
                .andExpect(content().bytes(new byte[0]))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION));

        mockMvc.perform(get("/common/download")
                        .param("fileName", "private-alias/2026/07/26/secret.pdf")
                        .param("delete", "false"))
                .andExpect(status().isNotFound())
                .andExpect(content().bytes(new byte[0]))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION));
    }

    /**
     * 在通用公开目录创建指向工作流私有根的真实符号链接。
     * @param alias Path，通用入口可见的公开别名路径
     * @param target Path，工作流私有附件根
     * @return boolean，当前平台成功创建链接时返回 true，不支持时返回 false
     */
    private boolean createSymbolicLink(Path alias, Path target)
    {
        try
        {
            Files.createSymbolicLink(alias, target.toAbsolutePath());
            return true;
        }
        catch (UnsupportedOperationException | SecurityException | java.io.IOException exception)
        {
            return false;
        }
    }
}
