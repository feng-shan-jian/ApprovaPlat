package com.ruoyi.common.utils.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * profile 私有工作流附件路径的统一编码与目录段判定契约测试。
 */
class FileUtilsProtectedProfilePathTest
{
    /**
     * 验证原始、大小写、反斜杠、穿越和多层编码写法都不能进入通用下载。
     *
     * @param path String，攻击者可提交的 profile 路径变体
     * @return void，任一路径未被统一策略拒绝时测试失败
     */
    @ParameterizedTest
    @MethodSource("protectedPaths")
    void rejectsProtectedAndInvalidProfilePaths(String path)
    {
        assertThat(FileUtils.isProtectedProfilePath(path)).isTrue();
        assertThat(FileUtils.checkAllowDownload(path)).isFalse();
    }

    /**
     * 验证普通公开文件及仅在文件名中包含相似文本的资源不被误伤。
     *
     * @param path String，允许通用下载的公开 PDF 路径
     * @return void，公开资源被错误识别为私有目录时测试失败
     */
    @ParameterizedTest
    @MethodSource("publicPaths")
    void allowsPublicProfilePaths(String path)
    {
        assertThat(FileUtils.isProtectedProfilePath(path)).isFalse();
        assertThat(FileUtils.checkAllowDownload(path)).isTrue();
    }

    /**
     * 构造必须 fail-closed 的私有、非法和超深编码路径。
     *
     * @return Stream&lt;String&gt;，统一策略必须拒绝的路径集合
     */
    private static Stream<String> protectedPaths()
    {
        return Stream.of(
                null,
                "",
                "   ",
                "workflow-attachments/2026/secret.pdf",
                "/profile/workflow-attachments/2026/secret.pdf",
                "/profile/WORKFLOW-ATTACHMENTS/secret.pdf",
                "workflow-attachments\\2026\\secret.pdf",
                "upload//..\\workflow-attachments//secret.pdf",
                "workflow%2Dattachments/secret.pdf",
                "workflow-attachments%2F2026%2Fsecret.pdf",
                "workflow-attachments%5C2026%5Csecret.pdf",
                "%77%6f%72%6b%66%6c%6f%77%2d%61%74%74%61%63%68%6d%65%6e%74%73/secret.pdf",
                "workflow%252Dattachments%252Fsecret.pdf",
                "workflow%25252Dattachments%25252Fsecret.pdf",
                "workflow%2525252Dattachments/secret.pdf",
                "upload/../workflow-attachments/secret.pdf",
                "upload/%2e%2e/workflow-attachments/secret.pdf",
                "upload/%252e%252e/workflow-attachments/secret.pdf",
                "%",
                "%2",
                "%GG",
                "bad%encoding/workflow-attachments/secret.pdf");
    }

    /**
     * 构造目录段不命中的公开路径。
     *
     * @return Stream&lt;String&gt;，统一策略应允许的公开路径集合
     */
    private static Stream<String> publicPaths()
    {
        return Stream.of(
                "/profile/upload/2026/public.pdf",
                "/profile/upload/workflow-attachments.pdf",
                "/profile/workflow-attachments-backup/public.pdf",
                "/profile/my-workflow-attachments/public.pdf",
                "/profile/upload/%E4%B8%AD%E6%96%87.pdf");
    }
}
