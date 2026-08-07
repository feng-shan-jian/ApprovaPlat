package com.ruoyi.flowable.service.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

class WorkflowAttachmentStorageTest
{
    @TempDir
    Path profileRoot;

    /**
     * 按当前文件系统能力验证共享模式：支持安全目录句柄时完成预置身份和原子写探针，
     * 不支持时必须失败关闭且不创建临时目录。
     *
     * @return void，支持平台未完成探针或不支持平台仍接受共享模式时测试失败
     * @throws Exception 预置共享卷身份或遍历临时目录失败
     */
    @Test
    void verifiesSharedStorageAgainstSecureDirectoryCapability() throws Exception
    {
        WorkflowAttachmentStorage storage = new WorkflowAttachmentStorage(profileRoot, 1024L);
        Path privateRoot = profileRoot.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME);
        Path marker = privateRoot.resolve(WorkflowAttachmentStorage.STORAGE_ID_MARKER_NAME);
        Files.writeString(marker, "shared-storage-a\n", StandardCharsets.UTF_8);
        boolean secureDirectorySupported = supportsSecureDirectoryStream(privateRoot);
        if (!secureDirectorySupported)
        {
            // Windows 默认文件系统没有 SecureDirectoryStream，必须证明共享模式拒绝启动而非跳过门禁。
            assertThatThrownBy(() -> storage.verifyRuntimeReadiness(
                    "shared-storage-a", 0L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("工作流附件生产存储就绪校验失败");
            assertThat(privateRoot.resolve(".tmp")).doesNotExist();
            return;
        }

        storage.verifyRuntimeReadiness("shared-storage-a", 0L);

        assertThat(marker).hasContent("shared-storage-a\n");
        Path temporaryDirectory = privateRoot.resolve(".tmp");
        try (var paths = Files.list(temporaryDirectory))
        {
            assertThat(paths).isEmpty();
        }
        try (var paths = Files.walk(privateRoot))
        {
            assertThat(paths.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.startsWith(
                            WorkflowAttachmentStorage.READINESS_PROBE_NAME_PREFIX));
        }
    }

    /**
     * 验证共享 storage-id 缺失或不匹配时 fail-closed，应用不会自行创建或覆盖标记。
     *
     * @return void，错误挂载点仍可通过启动门禁时测试失败
     * @throws Exception 写入不匹配标记失败
     */
    @Test
    void rejectsMissingOrMismatchedSharedStorageIdentity() throws Exception
    {
        WorkflowAttachmentStorage storage = new WorkflowAttachmentStorage(profileRoot, 1024L);
        Path marker = profileRoot.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME)
                .resolve(WorkflowAttachmentStorage.STORAGE_ID_MARKER_NAME);

        assertThatThrownBy(() -> storage.verifyRuntimeReadiness("shared-storage-a", 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("工作流附件生产存储就绪校验失败");
        assertThat(marker).doesNotExist();

        Files.writeString(marker, "shared-storage-b\n", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> storage.verifyRuntimeReadiness("shared-storage-a", 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("工作流附件生产存储就绪校验失败");
        assertThat(marker).hasContent("shared-storage-b\n");
    }

    /**
     * 验证本地持久卷模式无需伪造共享标识，但仍执行真实写入和原子移动能力探针。
     *
     * @return void，本地模式跳过文件系统能力校验或创建 storage-id 时测试失败
     */
    @Test
    void verifiesLocalPersistentStorageWithoutCreatingSharedIdentity()
    {
        WorkflowAttachmentStorage storage = new WorkflowAttachmentStorage(profileRoot, 1024L);

        long usableBytes = storage.probeRuntimeReadiness(null, 0L);

        Path marker = profileRoot.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME)
                .resolve(WorkflowAttachmentStorage.STORAGE_ID_MARKER_NAME);
        assertThat(marker).doesNotExist();
        assertThat(usableBytes).isNotNegative();
    }

    /**
     * 验证周期或重启探针只回收严格 journal 关联且超过保护窗口的崩溃残留；当前并发探针、
     * 非协议文件和正式附件均不得被删除。
     *
     * @return void，陈旧残留未清理或并发/业务文件被误删时测试失败
     * @throws Exception 构造隔离 journal、正式日期目录和探针文件失败
     */
    @Test
    void recoversOnlyStrictlyJournaledStaleProbeArtifacts() throws Exception
    {
        WorkflowAttachmentStorage storage = new WorkflowAttachmentStorage(profileRoot, 1024L);
        Path privateRoot = profileRoot.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME);
        Path temporaryDirectory = privateRoot.resolve(".tmp");
        Path journalDirectory = privateRoot.resolve(
                WorkflowAttachmentStorage.READINESS_PROBE_JOURNAL_DIRECTORY_NAME);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Path destinationDirectory = privateRoot.resolve(String.format(Locale.ROOT,
                "%04d/%02d/%02d", today.getYear(), today.getMonthValue(),
                today.getDayOfMonth()));
        Files.createDirectories(temporaryDirectory);
        Files.createDirectories(journalDirectory);
        Files.createDirectories(destinationDirectory);

        long staleCreatedAt = System.currentTimeMillis()
                - WorkflowAttachmentStorage.READINESS_PROBE_STALE_MILLIS - 10_000L;
        String staleBase = readinessProbeBase(staleCreatedAt, today);
        Path staleJournal = writeReadinessJournal(journalDirectory, staleBase);
        Path staleSource = Files.writeString(
                temporaryDirectory.resolve(staleBase + ".source"), "partial");
        Path staleTarget = Files.writeString(
                destinationDirectory.resolve(staleBase + ".target"), "complete");
        Files.setLastModifiedTime(staleJournal, FileTime.fromMillis(staleCreatedAt));

        long activeCreatedAt = System.currentTimeMillis();
        String activeBase = readinessProbeBase(activeCreatedAt, today);
        Path activeJournal = writeReadinessJournal(journalDirectory, activeBase);
        Path activeSource = Files.writeString(
                temporaryDirectory.resolve(activeBase + ".source"), "active-source");
        Path activeTarget = Files.writeString(
                destinationDirectory.resolve(activeBase + ".target"), "active-target");
        Path unrelatedJournal = Files.writeString(
                journalDirectory.resolve("manual-do-not-delete.journal"), "operator");
        Path businessAttachment = Files.writeString(
                destinationDirectory.resolve("a".repeat(32) + ".pdf"), "business");

        storage.probeRuntimeReadiness(null, 0L);

        assertThat(staleJournal).doesNotExist();
        assertThat(staleSource).doesNotExist();
        assertThat(staleTarget).doesNotExist();
        assertThat(activeJournal).exists();
        assertThat(activeSource).exists();
        assertThat(activeTarget).exists();
        assertThat(unrelatedJournal).exists();
        assertThat(businessAttachment).hasContent("business");
    }

    /**
     * 验证运维探针拒绝跟随预先替换的 .tmp 目录链接，攻击者目录不得收到探针文件。
     *
     * @return void，临时父目录链接可把启动探针重定向到私有根外时测试失败
     * @throws Exception 创建隔离攻击目录或符号链接失败
     */
    @Test
    void rejectsReadinessProbeWhenTemporaryDirectoryIsSymbolicLink() throws Exception
    {
        WorkflowAttachmentStorage storage = new WorkflowAttachmentStorage(profileRoot, 1024L);
        Path privateRoot = profileRoot.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME);
        Path temporaryDirectory = privateRoot.resolve(".tmp");
        Path attackerDirectory = profileRoot.resolve("attacker-readiness-directory");
        Files.createDirectory(attackerDirectory);
        boolean linkCreated;
        try
        {
            Files.createSymbolicLink(temporaryDirectory, attackerDirectory.toAbsolutePath());
            linkCreated = true;
        }
        catch (UnsupportedOperationException | SecurityException | java.io.IOException exception)
        {
            linkCreated = false;
        }
        assumeTrue(linkCreated,
                "当前文件系统不允许创建符号链接，Linux CI 必须执行该攻击回归");

        assertThatThrownBy(() -> storage.verifyRuntimeReadiness(null, 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("工作流附件生产存储就绪校验失败");
        try (var paths = Files.list(attackerDirectory))
        {
            assertThat(paths).isEmpty();
        }
    }

    /**
     * 验证存储忽略客户端路径和 MIME，按真实内容计算大小、摘要并写入 profile 私有子目录。
     * @return void，任一可信元数据或路径隔离契约不符合时测试失败
     * @throws Exception 文件读取或测试摘要计算失败
     */
    @Test
    void storesOnlyServerGeneratedPrivateObjectAndComputedMetadata() throws Exception
    {
        byte[] content = "%PDF-1.7\nworkflow attachment".getBytes();
        WorkflowAttachmentStorage storage = new WorkflowAttachmentStorage(profileRoot, 1024L);
        MockMultipartFile file = new MockMultipartFile("file",
                "C:\\fakepath\\发票.pdf", "text/html", content);

        StoredAttachmentFile stored = storage.store(file);

        assertThat(stored.originalName()).isEqualTo("发票.pdf");
        assertThat(stored.contentType()).isEqualTo("application/pdf");
        assertThat(stored.fileSize()).isEqualTo(content.length);
        assertThat(stored.sha256()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content)));
        assertThat(stored.storageKey())
                .matches("[0-9]{4}/[0-9]{2}/[0-9]{2}/[0-9a-f]{32}\\.pdf")
                .doesNotContain("发票", "fakepath", "profile");

        try (var contentStream = storage.openVerifiedForRead(
                stored.storageKey(), content.length, stored.sha256()))
        {
            assertThat(contentStream.readAllBytes()).isEqualTo(content);
        }
    }

    /**
     * 验证空文件、服务端实际超限、控制字符文件名和路径型存储键均被拒绝且不遗留文件。
     * @return void，任一非法输入可落盘或产生路径穿越时测试失败
     * @throws Exception 遍历隔离目录失败
     */
    @Test
    void rejectsInvalidUploadsAndStorageTraversalWithoutResidue() throws Exception
    {
        WorkflowAttachmentStorage storage = new WorkflowAttachmentStorage(profileRoot, 4L);

        assertBadRequest(() -> storage.store(new MockMultipartFile(
                "file", "empty.txt", "text/plain", new byte[0])),
                "上传附件不能为空文件");
        assertBadRequest(() -> storage.store(new MockMultipartFile(
                "file", "large.txt", "text/plain", new byte[5])),
                "上传附件大小不能超过4字节");
        assertBadRequest(() -> storage.store(new MockMultipartFile(
                "file", "bad\r\nname.txt", "text/plain", new byte[] { 1 })),
                "上传附件文件名不合法");
        assertThatThrownBy(() -> storage.openVerifiedForRead(
                "../../outside.txt", 1L, "a".repeat(64)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR);
                    assertThat(exception.getMessage()).isEqualTo("工作流附件存储键异常");
                });

        Path privateRoot = profileRoot.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME);
        if (Files.exists(privateRoot))
        {
            try (var paths = Files.walk(privateRoot))
            {
                assertThat(paths.filter(Files::isRegularFile)).isEmpty();
            }
        }
    }

    /**
     * 验证同长度正文替换无法绕过下载和绑定共用的 SHA-256 完整性门禁。
     * @return void，篡改文件仍能返回内容流或通过 verify 时测试失败
     * @throws Exception 创建并覆盖隔离私有文件失败
     */
    @Test
    void rejectsSameLengthContentTamperingForReadAndBinding() throws Exception
    {
        byte[] original = "trusted-content".getBytes();
        byte[] tampered = "changed-content".getBytes();
        assertThat(tampered).hasSameSizeAs(original);
        WorkflowAttachmentStorage storage = new WorkflowAttachmentStorage(profileRoot, 1024L);
        StoredAttachmentFile stored = storage.store(new MockMultipartFile(
                "file", "evidence.txt", "text/plain", original));
        Path storedPath = profileRoot.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME)
                .resolve(stored.storageKey());
        Files.write(storedPath, tampered);

        assertIntegrityFailure(() -> storage.openVerifiedForRead(
                stored.storageKey(), stored.fileSize(), stored.sha256()));
        assertIntegrityFailure(() -> storage.verify(
                stored.storageKey(), stored.fileSize(), stored.sha256()));
    }

    /**
     * 验证日期父目录被替换为符号链接后，读、写、删全部按目录安全异常关闭。
     * @return void，任一操作跟随攻击者目录链接时测试失败
     * @throws Exception 创建真实附件、移动目录或创建测试链接失败
     */
    @Test
    void rejectsReadWriteAndDeleteAfterDateDirectoryBecomesSymbolicLink() throws Exception
    {
        WorkflowAttachmentStorage storage = new WorkflowAttachmentStorage(profileRoot, 1024L);
        StoredAttachmentFile stored = storage.store(new MockMultipartFile(
                "file", "trusted.txt", "text/plain", "trusted".getBytes()));
        Path storedPath = profileRoot.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME)
                .resolve(stored.storageKey());
        Path dateDirectory = storedPath.getParent();
        Path attackerDirectory = profileRoot.resolve("attacker-date-directory");
        assumeTrue(replaceWithSymbolicLink(dateDirectory, attackerDirectory),
                "当前文件系统不允许创建符号链接，Linux CI 必须执行该攻击回归");

        assertStorageDirectoryFailure(() -> storage.openVerifiedForRead(
                stored.storageKey(), stored.fileSize(), stored.sha256()));
        assertStorageDirectoryFailure(() -> storage.delete(stored.storageKey()));
        assertStorageDirectoryFailure(() -> storage.store(new MockMultipartFile(
                "file", "new.txt", "text/plain", "new".getBytes())));
    }

    /**
     * 验证私有根在服务运行期间被替换为符号链接后，全部文件操作拒绝使用新目标。
     * @return void，固定根身份失效后仍可读、写或删时测试失败
     * @throws Exception 创建真实附件、移动目录或创建测试链接失败
     */
    @Test
    void rejectsReadWriteAndDeleteAfterPrivateRootBecomesSymbolicLink() throws Exception
    {
        WorkflowAttachmentStorage storage = new WorkflowAttachmentStorage(profileRoot, 1024L);
        StoredAttachmentFile stored = storage.store(new MockMultipartFile(
                "file", "trusted.txt", "text/plain", "trusted".getBytes()));
        Path privateRoot = profileRoot.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME);
        Path attackerRoot = profileRoot.resolve("attacker-private-root");
        assumeTrue(replaceWithSymbolicLink(privateRoot, attackerRoot),
                "当前文件系统不允许创建符号链接，Linux CI 必须执行该攻击回归");

        assertStorageDirectoryFailure(() -> storage.openVerifiedForRead(
                stored.storageKey(), stored.fileSize(), stored.sha256()));
        assertStorageDirectoryFailure(() -> storage.delete(stored.storageKey()));
        assertStorageDirectoryFailure(() -> storage.store(new MockMultipartFile(
                "file", "new.txt", "text/plain", "new".getBytes())));
    }

    /**
     * 验证最终正文被替换为符号链接后，下载、绑定校验和清理均不跟随链接。
     * @return void，最终链接可被打开或删除其目标时测试失败
     * @throws Exception 创建真实附件、移动文件或创建测试链接失败
     */
    @Test
    void rejectsFinalAttachmentFileSymbolicLink() throws Exception
    {
        WorkflowAttachmentStorage storage = new WorkflowAttachmentStorage(profileRoot, 1024L);
        StoredAttachmentFile stored = storage.store(new MockMultipartFile(
                "file", "trusted.txt", "text/plain", "trusted".getBytes()));
        Path storedPath = profileRoot.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME)
                .resolve(stored.storageKey());
        Path attackerFile = profileRoot.resolve("attacker-file.txt");
        assumeTrue(replaceWithSymbolicLink(storedPath, attackerFile),
                "当前文件系统不允许创建符号链接，Linux CI 必须执行该攻击回归");

        assertIntegrityFailure(() -> storage.openVerifiedForRead(
                stored.storageKey(), stored.fileSize(), stored.sha256()));
        assertIntegrityFailure(() -> storage.verify(
                stored.storageKey(), stored.fileSize(), stored.sha256()));
        assertIntegrityFailure(() -> storage.delete(stored.storageKey()));
        assertThat(attackerFile).exists();
    }

    /**
     * 把现有目录或文件移动到攻击者目标，再在原词法路径创建真实符号链接。
     * @param original Path，存储服务仍会访问的原词法路径
     * @param target Path，攻击者控制且承载原内容的链接目标
     * @return boolean，当前平台成功创建符号链接时返回 true，不支持时返回 false
     * @throws Exception 移动原对象失败
     */
    private boolean replaceWithSymbolicLink(Path original, Path target) throws Exception
    {
        Files.move(original, target);
        try
        {
            Files.createSymbolicLink(original, target.toAbsolutePath());
            return true;
        }
        catch (UnsupportedOperationException | SecurityException | java.io.IOException exception)
        {
            return false;
        }
    }

    /**
     * 判断当前测试文件系统是否支持抵抗共享卷父目录 ABA 的安全目录句柄。
     *
     * @param directory Path，待打开的普通目录
     * @return boolean，DirectoryStream 同时实现 SecureDirectoryStream 时为 true
     * @throws Exception 打开测试目录失败
     */
    private boolean supportsSecureDirectoryStream(Path directory) throws Exception
    {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory))
        {
            return stream instanceof SecureDirectoryStream<?>;
        }
    }

    /**
     * 生成与生产恢复协议一致的严格探针基础名。
     *
     * @param createdAtMillis long，名称中冻结的创建时间毫秒
     * @param date LocalDate，target 所在 UTC 日期
     * @return String，可关联 journal、source 和 target 的基础名
     */
    private String readinessProbeBase(long createdAtMillis, LocalDate date)
    {
        return WorkflowAttachmentStorage.READINESS_PROBE_NAME_PREFIX
                + String.format(Locale.ROOT, "%013d", createdAtMillis)
                + "-" + date.format(DateTimeFormatter.BASIC_ISO_DATE) + "-"
                + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 写入与严格基础名绑定的探针 journal 正文。
     *
     * @param journalDirectory Path，隔离 journal 目录
     * @param baseName String，测试探针严格基础名
     * @return Path，已经写入的 journal 文件
     * @throws Exception 文件创建失败
     */
    private Path writeReadinessJournal(Path journalDirectory, String baseName)
            throws Exception
    {
        return Files.writeString(journalDirectory.resolve(baseName + ".journal"),
                "approvaplat-workflow-storage-readiness-journal:"
                        + baseName + "\n",
                StandardCharsets.US_ASCII);
    }

    /**
     * 断言父目录或私有根身份异常使用稳定 HTTP 500 且不泄露物理路径。
     * @param action ThrowingCallable，预期被目录身份门禁拒绝的存储操作
     * @return void，异常类型、状态码或提示漂移时测试失败
     */
    private void assertStorageDirectoryFailure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action)
    {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR);
            assertThat(exception.getMessage()).isEqualTo("工作流附件存储目录安全校验失败");
        });
    }

    /**
     * 断言物理文件与正式摘要不一致时使用稳定 HTTP 500 数据完整性语义。
     * @param action ThrowingCallable，预期被完整性门禁拒绝的操作
     * @return void，异常类型、状态码或提示漂移时测试失败
     */
    private void assertIntegrityFailure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action)
    {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR);
            assertThat(exception.getMessage()).isEqualTo("工作流附件文件完整性校验失败");
        });
    }

    /**
     * 断言存储参数错误使用稳定 HTTP 400 业务语义。
     * @param action ThrowingCallable，预期失败的存储操作
     * @param message String，预期业务提示
     * @return void，异常契约不符合时测试失败
     */
    private void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            String message)
    {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exception.getMessage()).isEqualTo(message);
        });
    }
}
