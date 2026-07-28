package com.ruoyi.framework.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

class ProtectedProfilePathResourceResolverTest
{
    @TempDir
    Path profileRoot;

    /**
     * 验证匿名静态资源解析只保留原 profile 公共文件，私有附件目录始终返回不可解析。
     * @return void，私有文件可被资源链直接解析或公共文件被误伤时测试失败
     * @throws Exception 创建和读取隔离文件失败
     */
    @Test
    void blocksAnonymousPrivateAttachmentReadsWithoutBreakingPublicProfile() throws Exception
    {
        Path publicFile = profileRoot.resolve("upload/public.txt");
        Path privateFile = profileRoot.resolve("workflow-attachments/2026/07/26/secret.pdf");
        Files.createDirectories(publicFile.getParent());
        Files.createDirectories(privateFile.getParent());
        Files.writeString(publicFile, "public", StandardCharsets.UTF_8);
        Files.writeString(privateFile, "secret", StandardCharsets.UTF_8);

        ExposedResolver resolver = new ExposedResolver();
        // 生产配置使用 file:<profile>/ 目录资源，测试也保留尾分隔符以匹配 createRelative 语义。
        Resource location = new FileSystemResource(
                profileRoot.toAbsolutePath() + File.separator);
        Resource resolvedPublic = resolver.resolve("upload/public.txt", location);

        assertThat(resolvedPublic).isNotNull();
        assertThat(resolvedPublic.getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo("public");
        for (String privatePath : privatePathVariants())
        {
            assertThat(resolver.resolve(privatePath, location))
                    .as("匿名 /profile 静态链必须拒绝: " + privatePath)
                    .isNull();
        }
    }

    /**
     * 验证公开 upload 目录中的真实符号链接不能把匿名静态资源解析转向私有附件根。
     * @return void，公开别名可返回私有正文时测试失败
     * @throws Exception 创建隔离目录、文件或测试链接失败
     */
    @Test
    void blocksAnonymousAliasResolvingIntoPrivateAttachmentRoot() throws Exception
    {
        Path privateFile = profileRoot.resolve(
                "workflow-attachments/2026/07/26/secret.pdf");
        Files.createDirectories(privateFile.getParent());
        Files.writeString(privateFile, "secret", StandardCharsets.UTF_8);
        Path alias = profileRoot.resolve("upload/private-alias");
        Files.createDirectories(alias.getParent());
        assumeTrue(createSymbolicLink(alias,
                profileRoot.resolve("workflow-attachments")),
                "当前文件系统不允许创建符号链接，Linux CI 必须执行该攻击回归");

        ExposedResolver resolver = new ExposedResolver();
        Resource location = new FileSystemResource(
                profileRoot.toAbsolutePath() + File.separator);

        assertThat(resolver.resolve(
                "upload/private-alias/2026/07/26/secret.pdf", location)).isNull();
    }

    /**
     * 在公开目录创建指向私有根的真实符号链接。
     * @param alias Path，匿名静态链可见的公开别名路径
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

    /**
     * 返回大小写、路径穿越、反斜杠及单/双重编码的私有目录写法。
     * @return List&lt;String&gt;，必须全部被资源解析器拒绝的路径
     */
    private List<String> privatePathVariants()
    {
        return List.of(
                "workflow-attachments/2026/07/26/secret.pdf",
                "WORKFLOW-ATTACHMENTS/2026/07/26/secret.pdf",
                "workflow%2Dattachments/2026/07/26/secret.pdf",
                "workflow%252Dattachments/2026/07/26/secret.pdf",
                "workflow-attachments%2F2026%2F07%2F26%2Fsecret.pdf",
                "workflow-attachments%252F2026%252F07%252F26%252Fsecret.pdf",
                "upload/../workflow-attachments/2026/07/26/secret.pdf",
                "upload\\..\\workflow-attachments\\2026\\07\\26\\secret.pdf",
                "upload/%2e%2e/workflow-attachments/2026/07/26/secret.pdf",
                "upload/%252e%252e/workflow-attachments/secret.pdf",
                "bad%encoding/workflow-attachments/secret.pdf");
    }

    /**
     * 暴露受保护解析入口供同包安全回归测试调用。
     */
    private static final class ExposedResolver
            extends ResourcesConfig.ProtectedProfilePathResourceResolver
    {
        /**
         * 调用生产资源解析逻辑。
         * @param path String，待解析 profile 相对路径
         * @param location Resource，隔离 profile 根资源
         * @return Resource，允许公开的文件或 null
         * @throws Exception 默认资源解析失败
         */
        private Resource resolve(String path, Resource location) throws Exception
        {
            return getResource(path, location);
        }
    }
}
