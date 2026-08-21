package com.ruoyi.flowable.service.attachment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import com.ruoyi.common.exception.ServiceException;

/**
 * 附件存储真实文件系统契约测试，不使用文件系统 mock。
 */
class WorkflowAttachmentStorageContractTest
{
    @TempDir
    Path profile;

    /**
     * 验证正常上传、同通道读取校验、摘要复核和幂等删除。
     * @return void，无返回值
     * @throws Exception 真实临时文件操作失败
     */
    @Test
    void storesReadsVerifiesAndDeletesWithDigest() throws Exception
    {
        WorkflowAttachmentStorage storage = new WorkflowAttachmentStorage(profile, 1024);
        byte[] content = "contract-content".getBytes(StandardCharsets.UTF_8);
        StoredAttachmentFile stored = storage.store(file("memo.txt", content));
        assertEquals(content.length, stored.fileSize());
        assertEquals(sha256(content), stored.sha256());
        try (InputStream input = storage.openVerifiedForRead(stored.storageKey(),
                stored.fileSize(), stored.sha256()))
        {
            assertArrayEquals(content, input.readAllBytes());
        }
        storage.verify(stored.storageKey(), stored.fileSize(), stored.sha256());
        assertTrue(storage.delete(stored.storageKey()));
        assertFalse(storage.delete(stored.storageKey()));
    }

    /**
     * 验证对象键路径穿越和文件篡改会被拒绝。
     * @return void，无返回值
     * @throws Exception 真实临时文件操作失败
     */
    @Test
    void rejectsTraversalAndIntegrityFailures() throws Exception
    {
        WorkflowAttachmentStorage storage = new WorkflowAttachmentStorage(profile, 1024);
        assertThrows(ServiceException.class,
                () -> storage.openVerifiedForRead("../outside", 1, "0".repeat(64)));
        StoredAttachmentFile stored = storage.store(file("memo.txt", "safe".getBytes(StandardCharsets.UTF_8)));
        Path physical = profile.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME)
                .resolve(stored.storageKey());
        Files.writeString(physical, "tampered", StandardCharsets.UTF_8);
        assertThrows(ServiceException.class,
                () -> storage.verify(stored.storageKey(), stored.fileSize(), stored.sha256()));
        assertTrue(Files.exists(physical));
    }

    /**
     * 验证符号链接占据实际对象键位置时不会被读取。
     * @return void，无返回值
     * @throws Exception 真实临时文件操作失败
     */
    @Test
    void rejectsSymlinkAtActualObjectKey() throws Exception
    {
        WorkflowAttachmentStorage storage = new WorkflowAttachmentStorage(profile, 1024);
        byte[] content = "safe".getBytes(StandardCharsets.UTF_8);
        StoredAttachmentFile stored = storage.store(file("memo.txt", content));
        Path root = profile.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME);
        Path physical = root.resolve(stored.storageKey());
        Path outside = profile.resolve("outside.txt");
        Files.write(outside, content);
        Files.delete(physical);
        try
        {
            Files.createSymbolicLink(physical, outside);
            assertThrows(ServiceException.class,
                    () -> storage.openVerifiedForRead(stored.storageKey(), stored.fileSize(), stored.sha256()));
        }
        catch (UnsupportedOperationException | IOException failure)
        {
            Assumptions.assumeTrue(false, "当前文件系统不支持符号链接场景，未执行该契约");
        }
    }

    /**
     * 验证超限上传失败后不会遗留临时文件。
     * @return void，无返回值
     */
    @Test
    void rejectsOversizeAndLeavesNoTemporaryFiles()
    {
        WorkflowAttachmentStorage storage = new WorkflowAttachmentStorage(profile, 3);
        assertThrows(ServiceException.class,
                () -> storage.store(file("large.bin", new byte[] {1, 2, 3, 4})));
        assertNoTemporaryFiles();
    }

    /**
     * 验证共享存储标识冲突和 readiness 探针成功后的真实文件清理。
     * @return void，无返回值
     * @throws Exception 真实临时文件操作失败
     */
    @Test
    void validatesStorageIdAndReadinessLeavesNoProbeFiles() throws Exception
    {
        WorkflowAttachmentStorage storage = new WorkflowAttachmentStorage(profile, 1024);
        Path root = profile.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME);
        Path dateDirectory = root.resolve(LocalDate.now(ZoneOffset.UTC).toString().replace('-', '/'));
        Files.writeString(root.resolve(WorkflowAttachmentStorage.STORAGE_ID_MARKER_NAME),
                "shared-a", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> storage.verifyRuntimeReadiness("shared-b", 0));
        storage.verifyRuntimeReadiness("shared-a", 0);
        assertTrue(storage.usableSpace() >= 0L);
        assertNoTemporaryFiles();
        assertTrue(Files.exists(dateDirectory));
        try (Stream<Path> entries = Files.list(dateDirectory))
        {
            assertEquals(0L, entries
                    .filter(entry -> entry.getFileName().toString()
                            .matches("readiness-[0-9a-f]{32}\\.(?:target|part)"))
                    .count());
        }
    }

    /**
     * 验证可用空间采集不改变附件目录内容。
     * @return void，无返回值
     * @throws Exception 真实临时文件操作失败
     */
    @Test
    void usableSpaceDoesNotChangeDirectoryContents() throws Exception
    {
        WorkflowAttachmentStorage storage = new WorkflowAttachmentStorage(profile, 1024);
        Path root = profile.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME);
        List<String> before;
        try (Stream<Path> paths = Files.walk(root))
        {
            before = paths.map(root::relativize).map(Path::toString).sorted().toList();
        }
        assertTrue(storage.usableSpace() >= 0L);
        List<String> after;
        try (Stream<Path> paths = Files.walk(root))
        {
            after = paths.map(root::relativize).map(Path::toString).sorted().toList();
        }
        assertEquals(before, after);
    }

    /**
     * 验证上传输入流在临时文件已产生后抛出运行时异常时仍清理临时文件。
     * @return void，无返回值
     * @throws Exception 真实临时文件操作失败
     */
    @Test
    void runtimeFailureLeavesNoTemporaryFiles() throws Exception
    {
        WorkflowAttachmentStorage storage = new WorkflowAttachmentStorage(profile, 1024);
        MockMultipartFile failingFile = new MockMultipartFile("file", "failure.txt",
                "text/plain", new byte[] {1, 2, 3})
        {
            @Override
            public InputStream getInputStream()
            {
                return new InputStream()
                {
                    private boolean emitted;

                    @Override
                    public int read(byte[] buffer, int offset, int length)
                    {
                        if (!emitted)
                        {
                            emitted = true;
                            buffer[offset] = 1;
                            return 1;
                        }
                        throw new IllegalStateException("primary upload failure");
                    }

                    @Override
                    public int read()
                    {
                        throw new IllegalStateException("primary upload failure");
                    }
                };
            }
        };
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> storage.store(failingFile));
        assertEquals("primary upload failure", failure.getMessage());
        assertNoTemporaryFiles();
    }

    /**
     * 构造真实上传契约使用的 multipart 文件。
     * @param name String，上传文件名
     * @param content byte[]，上传正文
     * @return MockMultipartFile，仅作为真实输入流载体
     */
    private MockMultipartFile file(String name, byte[] content)
    {
        return new MockMultipartFile("file", name, "text/plain", content);
    }

    /**
     * 计算契约断言使用的 SHA-256 摘要。
     * @param content byte[]，文件正文
     * @return String，小写十六进制摘要
     * @throws Exception JDK 摘要算法不可用
     */
    private String sha256(byte[] content) throws Exception
    {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    /**
     * 断言私有临时目录中不存在任何遗留文件。
     * @return void，无返回值
     */
    private void assertNoTemporaryFiles()
    {
        Path temporary = profile.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME).resolve(".tmp");
        if (Files.exists(temporary))
        {
            try (Stream<Path> files = Files.list(temporary))
            {
                assertEquals(0L, files.count());
            }
            catch (IOException failure)
            {
                throw new AssertionError(failure);
            }
        }
    }
}
