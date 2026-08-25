package com.ruoyi.flowable.service.attachment;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;

/**
 * 工作流附件私有文件存储，所有物理操作统一走 java.nio.file 路径。
 */
@Component
public class WorkflowAttachmentStorage
{
    public static final String PRIVATE_DIRECTORY_NAME = "workflow-attachments";
    public static final String STORAGE_ID_MARKER_NAME = ".storage-id";
    private static final byte[] READINESS_PROBE_CONTENT =
            "approvaplat-workflow-storage-readiness".getBytes(StandardCharsets.US_ASCII);
    static final int MAX_ORIGINAL_NAME_LENGTH = 255;
    private static final int BUFFER_SIZE = 16 * 1024;
    private static final Pattern STORAGE_KEY_PATTERN = Pattern.compile(
            "[0-9]{4}/[0-9]{2}/[0-9]{2}/[0-9a-f]{32}(?:\\.[a-z0-9]{1,16})?");
    private static final Pattern MIME_PATTERN = Pattern.compile(
            "[a-z0-9][a-z0-9!#$&^_.+\\-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+\\-]{0,126}");
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern STORAGE_ID_PATTERN = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path storageRoot;
    private final Path realStorageRoot;
    private final long maxSize;

    /**
     * 创建生产附件存储。
     * @param profile String，若依 profile 根目录
     * @param properties WorkflowAttachmentProperties，附件限制配置
     */
    @Autowired
    public WorkflowAttachmentStorage(@Value("${ruoyi.profile}") String profile,
            WorkflowAttachmentProperties properties)
    {
        this(toProfilePath(profile), requireProperties(properties).getMaxSize());
    }

    /**
     * 创建指定根目录的附件存储。
     * @param profileRoot Path，profile 根目录
     * @param maxSize long，单文件大小上限
     */
    public WorkflowAttachmentStorage(Path profileRoot, long maxSize)
    {
        if (profileRoot == null || maxSize <= 0L)
        {
            throw new IllegalArgumentException("工作流附件存储配置不合法");
        }
        Path normalizedProfileRoot = profileRoot.toAbsolutePath().normalize();
        this.storageRoot = normalizedProfileRoot.resolve(PRIVATE_DIRECTORY_NAME).normalize();
        if (!storageRoot.startsWith(normalizedProfileRoot)
                || storageRoot.equals(normalizedProfileRoot))
        {
            throw new IllegalArgumentException("工作流附件私有目录必须位于若依profile子目录");
        }
        this.maxSize = maxSize;
        try
        {
            // profile 根只负责存在性和链接校验，不能改变其祖先目录权限。
            ensureDirectory(profileRoot, false, false);
            // 仅从私有根开始应用附件目录权限。
            ensureDirectory(storageRoot, false, true);
            this.realStorageRoot = storageRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!realStorageRoot.equals(storageRoot))
            {
                throw new IOException("私有附件根目录包含符号链接");
            }
        }
        catch (IOException failure)
        {
            throw new IllegalStateException("工作流附件私有存储根初始化失败", failure);
        }
    }

    /**
     * 流式写入上传文件，优先原子移动发布并回读校验可信元数据。
     * @param file MultipartFile，客户端上传文件
     * @return StoredAttachmentFile，服务端计算的文件元数据
     * @throws ServiceException 上传参数、大小、路径或发布校验失败
     */
    public StoredAttachmentFile store(MultipartFile file)
    {
        if (file == null)
        {
            throw invalidUpload("上传附件不能为空");
        }
        String originalName = normalizeOriginalName(file.getOriginalFilename());
        String storageKey = createStorageKey(originalName);
        Path destination = resolveStorageKey(storageKey);
        Path temporary = storageRoot.resolve(".tmp").resolve("upload-"
                + UUID.randomUUID().toString().replace("-", "") + ".part").normalize();
        boolean published = false;
        try
        {
            // 上传前拒绝已被删除或替换的私有根，避免业务写入静默重建存储边界。
            verifyStorageRoot();
            ensureDirectory(temporary.getParent(), true, true);
            ensureDirectory(destination.getParent(), true, true);
            FileDigest digest;
            try (OutputStream output = Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS))
            {
                digest = copyAndDigest(file, output);
            }
            applyFilePermissions(temporary);
            String contentType;
            try (InputStream input = new BufferedInputStream(Files.newInputStream(temporary,
                    StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))
            {
                contentType = detectContentType(input, originalName);
            }
            moveWithoutReplace(temporary, destination);
            published = true;
            requireOrdinaryFile(destination);
            verifyPublishedDigest(destination, digest);
            applyFilePermissions(destination);
            return new StoredAttachmentFile(storageKey, originalName, contentType,
                    digest.fileSize(), digest.sha256());
        }
        catch (ServiceException failure)
        {
            cleanupUploadFiles(temporary, destination, published, failure);
            throw failure;
        }
        catch (IOException failure)
        {
            ServiceException storageFailure = storageFailure("工作流附件写入失败", failure);
            cleanupUploadFiles(temporary, destination, published, storageFailure);
            throw storageFailure;
        }
        catch (RuntimeException failure)
        {
            cleanupUploadFiles(temporary, destination, published, failure);
            throw failure;
        }
    }

    /**
     * 打开并完整校验附件通道，返回位置归零的可信流。
     * @param storageKey String，数据库对象键
     * @param expectedSize long，数据库记录的文件大小
     * @param expectedSha256 String，数据库记录的 SHA-256
     * @return InputStream，调用方负责关闭
     * @throws ServiceException 文件不存在、路径不安全或完整性校验失败
     */
    public InputStream openVerifiedForRead(String storageKey, long expectedSize,
            String expectedSha256)
    {
        Path path = resolveStorageKey(storageKey);
        SeekableByteChannel channel = null;
        try
        {
            requireOrdinaryFile(path);
            channel = Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            long actualSize = channel.size();
            if (expectedSize <= 0L || expectedSha256 == null
                    || !SHA256_PATTERN.matcher(expectedSha256).matches()
                    || actualSize != expectedSize)
            {
                throw new ServiceException("工作流附件文件完整性校验失败", HttpStatus.ERROR);
            }
            FileDigest digest = digest(channel);
            if (digest.fileSize() != expectedSize || !expectedSha256.equals(digest.sha256()))
            {
                throw new ServiceException("工作流附件文件完整性校验失败", HttpStatus.ERROR);
            }
            requireOrdinaryFile(path);
            channel.position(0L);
            return Channels.newInputStream(channel);
        }
        catch (ServiceException failure)
        {
            closeChannel(channel);
            throw failure;
        }
        catch (NoSuchFileException failure)
        {
            closeChannel(channel);
            throw storageFailure("工作流附件文件不存在", failure, HttpStatus.NOT_FOUND);
        }
        catch (IOException failure)
        {
            closeChannel(channel);
            throw storageFailure("工作流附件文件读取失败", failure);
        }
        catch (RuntimeException failure)
        {
            closeChannel(channel);
            throw failure;
        }
    }

    /**
     * 校验物理文件与数据库摘要一致。
     * @param storageKey String，对象键
     * @param expectedSize long，期望大小
     * @param expectedSha256 String，期望摘要
     * @return void，无返回值
     * @throws ServiceException 物理文件与数据库摘要不一致
     */
    public void verify(String storageKey, long expectedSize, String expectedSha256)
    {
        try (InputStream ignored = openVerifiedForRead(storageKey, expectedSize, expectedSha256))
        {
        }
        catch (IOException failure)
        {
            throw storageFailure("工作流附件文件关闭失败", failure);
        }
    }

    /**
     * 幂等删除物理附件。
     * @param storageKey String，对象键
     * @return boolean，本次是否删除了现有文件
     * @throws ServiceException 文件路径不安全或删除失败
     */
    public boolean delete(String storageKey)
    {
        Path path = resolveStorageKey(storageKey);
        try
        {
            requireOrdinaryFile(path);
            return Files.deleteIfExists(path);
        }
        catch (NoSuchFileException ignored)
        {
            return false;
        }
        catch (ServiceException failure)
        {
            throw failure;
        }
        catch (IOException failure)
        {
            throw new WorkflowAttachmentStorageOperationException("工作流附件文件清理失败", failure);
        }
    }

    /**
     * 读取附件所在文件系统可用空间，不产生文件系统副作用。
     * @return long，非负可用字节数
     */
    public long usableSpace()
    {
        try
        {
            verifyStorageRoot();
            long usable = Files.getFileStore(realStorageRoot).getUsableSpace();
            if (usable < 0L)
            {
                throw new IOException("文件系统返回负数可用空间");
            }
            return usable;
        }
        catch (IOException failure)
        {
            throw storageFailure("工作流附件磁盘空间读取失败", failure);
        }
    }

    /**
     * 应用启动时执行一次真实写入、移动、回读和删除探针。
     * @param expectedStorageId String，共享卷预置标识；本地模式传 null
     * @param minFreeBytes long，最低可用空间
     * @return void，无返回值；结果由 readiness 异常表示
     * @throws IllegalArgumentException 最低可用空间为负数
     * @throws IllegalStateException 存储能力、标识、回读或清理失败
     */
    public void verifyRuntimeReadiness(String expectedStorageId, long minFreeBytes)
    {
        if (minFreeBytes < 0L)
        {
            throw new IllegalArgumentException("工作流附件磁盘低水位不能为负数");
        }
        Path temporary = storageRoot.resolve(".tmp").resolve("readiness-"
                + UUID.randomUUID().toString().replace("-", "") + ".part").normalize();
        Path targetDirectory = currentStorageDateDirectory();
        Path target = targetDirectory.resolve("readiness-"
                + UUID.randomUUID().toString().replace("-", "") + ".target").normalize();
        try
        {
            verifyStorageRoot();
            if (expectedStorageId != null)
            {
                verifyPreprovisionedStorageId(expectedStorageId);
            }
            ensureDirectory(temporary.getParent(), true, true);
            ensureDirectory(targetDirectory, true, true);
            try (OutputStream output = Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS))
            {
                output.write(READINESS_PROBE_CONTENT);
            }
            moveWithoutReplace(temporary, target);
            verifyProbeContent(target);
            long usable = Files.getFileStore(realStorageRoot).getUsableSpace();
            if (usable < minFreeBytes)
            {
                throw new IOException("附件存储可用空间低于生产配置低水位");
            }
            cleanupProbeFiles(temporary, target, null);
        }
        catch (IOException | RuntimeException failure)
        {
            cleanupProbeFiles(temporary, target, failure);
            throw new IllegalStateException("工作流附件生产存储就绪校验失败", failure);
        }
    }

    /**
     * 校验共享卷运维预置身份文件。
     * @param expectedStorageId String，部署声明的标识
     * @throws IOException 标识文件缺失、链接或内容冲突
     */
    private void verifyPreprovisionedStorageId(String expectedStorageId) throws IOException
    {
        if (!StringUtils.hasText(expectedStorageId)
                || !STORAGE_ID_PATTERN.matcher(expectedStorageId).matches())
        {
            throw new IOException("共享附件存储标识配置不合法");
        }
        Path marker = storageRoot.resolve(STORAGE_ID_MARKER_NAME);
        requireOrdinaryFile(marker);
        try (SeekableByteChannel channel = Files.newByteChannel(marker, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS))
        {
            long size = channel.size();
            if (size <= 0L || size > 128L)
            {
                throw new IOException("共享附件存储标记大小不合法");
            }
            ByteBuffer buffer = ByteBuffer.allocate((int) size);
            while (buffer.hasRemaining())
            {
                if (channel.read(buffer) <= 0)
                {
                    throw new IOException("共享附件存储标记读取未取得进展");
                }
            }
            for (byte value : buffer.array())
            {
                if ((value & 0x80) != 0 || value == '\r' || value == '\n')
                {
                    throw new IOException("共享附件存储标记不是稳定ASCII");
                }
            }
            String actual = new String(buffer.array(), StandardCharsets.US_ASCII).strip();
            if (!STORAGE_ID_PATTERN.matcher(actual).matches())
            {
                throw new IOException("共享附件存储标记格式不合法");
            }
            if (!expectedStorageId.equals(actual))
            {
                throw new IOException("共享附件存储标识与部署配置不一致");
            }
        }
    }

    /**
     * 计算上传流大小和摘要并执行大小上限。
     * @param file MultipartFile，上传内容
     * @param output OutputStream，临时文件输出流
     * @return FileDigest，实际大小和 SHA-256
     */
    private FileDigest copyAndDigest(MultipartFile file, OutputStream output) throws IOException
    {
        MessageDigest digest = sha256Digest();
        long size = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = file.getInputStream())
        {
            int read;
            while ((read = input.read(buffer)) != -1)
            {
                size += read;
                if (size > maxSize)
                {
                    throw invalidUpload("上传附件大小不能超过" + maxSize + "字节");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        if (size <= 0L)
        {
            throw invalidUpload("上传附件不能为空文件");
        }
        return new FileDigest(size, HexFormat.of().formatHex(digest.digest()));
    }

    /**
     * 在同一可定位通道上计算大小和摘要，并将位置复位到文件开头。
     * @param channel SeekableByteChannel，已通过 NOFOLLOW_LINKS 打开的只读通道
     * @return FileDigest，通道实际大小和 SHA-256
     */
    private FileDigest digest(SeekableByteChannel channel) throws IOException
    {
        MessageDigest digest = sha256Digest();
        long size = 0L;
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        channel.position(0L);
        int read;
        while ((read = channel.read(buffer)) != -1)
        {
            if (read == 0)
            {
                continue;
            }
            size += read;
            digest.update(buffer.array(), 0, read);
            buffer.clear();
        }
        channel.position(0L);
        return new FileDigest(size, HexFormat.of().formatHex(digest.digest()));
    }

    /**
     * 回读发布后的普通文件，确认普通移动或原子移动没有发布错误正文。
     * @param destination Path，已发布的目标文件
     * @param expected FileDigest，临时写入阶段计算的大小和摘要
     * @throws IOException 文件回读失败
     */
    private void verifyPublishedDigest(Path destination, FileDigest expected) throws IOException
    {
        try (SeekableByteChannel channel = Files.newByteChannel(destination,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
        {
            FileDigest actual = digest(channel);
            if (actual.fileSize() != expected.fileSize()
                    || !actual.sha256().equals(expected.sha256()))
            {
                throw new IOException("工作流附件发布后完整性校验失败");
            }
        }
    }

    /**
     * 解析并严格限制对象键位于私有根目录。
     * @param storageKey String，数据库对象键
     * @return Path，受控绝对路径
     */
    private Path resolveStorageKey(String storageKey)
    {
        if (!StringUtils.hasText(storageKey) || !STORAGE_KEY_PATTERN.matcher(storageKey).matches())
        {
            throw new ServiceException("工作流附件存储键异常", HttpStatus.ERROR);
        }
        Path resolved = storageRoot.resolve(storageKey).normalize();
        if (!resolved.startsWith(storageRoot) || resolved.equals(storageRoot))
        {
            throw new ServiceException("工作流存储路径异常", HttpStatus.ERROR);
        }
        return resolved;
    }

    /**
     * 确保目录树全部为普通目录且不通过符号链接越界。
     * @param directory Path，待创建目录
     * @param withinStorageRoot boolean，是否要求目录位于私有根
     * @param applyPrivatePermissions boolean，是否对私有根及其子目录应用权限
     */
    private void ensureDirectory(Path directory, boolean withinStorageRoot,
            boolean applyPrivatePermissions) throws IOException
    {
        Path normalized = directory.toAbsolutePath().normalize();
        if (withinStorageRoot && !normalized.startsWith(storageRoot))
        {
            throw new IOException("附件目录逃出私有存储根");
        }
        Path current = normalized.getRoot();
        for (Path segment : normalized)
        {
            current = current.resolve(segment);
            if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS))
            {
                try
                {
                    Files.createDirectory(current);
                }
                catch (FileAlreadyExistsException race)
                {
                    // 并发调用已创建该目录，下面的普通目录校验决定是否可继续。
                }
            }
            requireDirectory(current);
            // 只收紧私有根及其子目录，绝不触碰 profile 祖先或文件系统根目录。
            if (applyPrivatePermissions && current.startsWith(storageRoot))
            {
                applyDirectoryPermissions(current);
            }
        }
    }

    /**
     * 获取当前 UTC 日期对应的附件发布目录。
     * @return Path，私有根内的日期目录
     */
    private Path currentStorageDateDirectory()
    {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return storageRoot.resolve(String.format(Locale.ROOT, "%04d/%02d/%02d", today.getYear(),
                today.getMonthValue(), today.getDayOfMonth())).normalize();
    }

    /**
     * 使用拒绝符号链接的通道回读固定探针正文。
     * @param target Path，日期目录中的探针文件
     * @return void，无返回值
     * @throws IOException 探针类型、大小或正文不一致
     * @throws ServiceException 探针不是私有根内的普通文件
     */
    private void verifyProbeContent(Path target) throws IOException
    {
        requireOrdinaryFile(target);
        try (SeekableByteChannel channel = Files.newByteChannel(target, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS))
        {
            if (channel.size() != READINESS_PROBE_CONTENT.length)
            {
                throw new IOException("附件存储启动探针回读不一致");
            }
            ByteBuffer actual = ByteBuffer.allocate(READINESS_PROBE_CONTENT.length);
            while (actual.hasRemaining())
            {
                if (channel.read(actual) <= 0)
                {
                    throw new IOException("附件存储启动探针读取未取得进展");
                }
            }
            if (!Arrays.equals(actual.array(), READINESS_PROBE_CONTENT))
            {
                throw new IOException("附件存储启动探针回读不一致");
            }
        }
    }

    /**
     * 关闭校验失败时尚未移交给调用方的文件通道。
     * @param channel SeekableByteChannel，可能为空的待读通道
     * @return void，无返回值；关闭异常不会覆盖主校验异常
     */
    private void closeChannel(SeekableByteChannel channel)
    {
        if (channel == null)
        {
            return;
        }
        try
        {
            channel.close();
        }
        catch (IOException | RuntimeException ignored)
        {
        }
    }

    /**
     * 校验私有根未被替换。
     * @return void，无返回值
     * @throws IOException 根目录不存在、链接或真实路径变化
     */
    private void verifyStorageRoot() throws IOException
    {
        requireDirectory(storageRoot);
        if (!storageRoot.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(realStorageRoot))
        {
            throw new IOException("私有存储根路径边界或真实路径发生变化");
        }
    }

    /**
     * 校验普通目录。
     * @param directory Path，目录路径
     * @return void，无返回值
     * @throws IOException 目录为链接、特殊文件或不存在
     */
    private void requireDirectory(Path directory) throws IOException
    {
        BasicFileAttributes attributes = Files.readAttributes(directory,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther())
        {
            throw new IOException("附件目录包含链接或特殊文件");
        }
    }

    /**
     * 校验普通文件并确认真实路径仍在固定根内。
     * @param file Path，文件路径
     * @return void，无返回值
     * @throws IOException 文件不存在、链接或越界
     * @throws ServiceException 文件不是私有根内的普通文件
     */
    private void requireOrdinaryFile(Path file) throws IOException
    {
        BasicFileAttributes attributes = Files.readAttributes(file,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther())
        {
            throw new ServiceException("工作流附件文件完整性校验失败", HttpStatus.ERROR);
        }
        Path real = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.startsWith(realStorageRoot)
                || !real.getParent().equals(file.getParent().toAbsolutePath().normalize()))
        {
            throw new ServiceException("工作流附件文件完整性校验失败", HttpStatus.ERROR);
        }
    }

    /**
     * 优先使用 ATOMIC_MOVE 发布且不覆盖已有对象；不支持时回退同卷普通移动。
     * @param source Path，临时文件
     * @param destination Path，最终文件
     * @return void，无返回值
     * @throws IOException 原子移动不支持且普通同卷移动失败，或目标已存在
     */
    private void moveWithoutReplace(Path source, Path destination) throws IOException
    {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
        {
            throw new FileAlreadyExistsException(destination.toString());
        }
        try
        {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException failure)
        {
            Files.move(source, destination);
        }
    }

    /**
     * 清理上传文件并保留主业务异常。
     * @param temporary Path，当前上传的临时文件
     * @param destination Path，当前上传可能发布的目标文件
     * @param published boolean，目标文件是否由本次操作发布
     * @param originalFailure Throwable，必须继续向上抛出的原始异常
     * @return void，无返回值；清理失败作为 suppressed 信息附加
     */
    private void cleanupUploadFiles(Path temporary, Path destination, boolean published,
            Throwable originalFailure)
    {
        try
        {
            Files.deleteIfExists(temporary);
        }
        catch (IOException | RuntimeException cleanupFailure)
        {
            originalFailure.addSuppressed(cleanupFailure);
        }
        if (published)
        {
            try
            {
                Files.deleteIfExists(destination);
            }
            catch (IOException | RuntimeException cleanupFailure)
            {
                originalFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    /**
     * 探针退出时同时清理临时位置和日期目录位置，避免第一项清理失败导致另一项残留。
     * @param temporary Path，.tmp 中的探针文件
     * @param target Path，日期目录中的探针文件
     * @param operationFailure Throwable，主操作异常，可为空
     * @return void，无返回值；存在主异常时清理失败只作为 suppressed 信息
     * @throws IllegalStateException 没有主异常且清理失败
     */
    private void cleanupProbeFiles(Path temporary, Path target, Throwable operationFailure)
    {
        Throwable cleanupFailure = null;
        try
        {
            Files.deleteIfExists(temporary);
        }
        catch (IOException | RuntimeException failure)
        {
            cleanupFailure = failure;
        }
        try
        {
            Files.deleteIfExists(target);
        }
        catch (IOException | RuntimeException failure)
        {
            if (cleanupFailure == null)
            {
                cleanupFailure = failure;
            }
            else
            {
                cleanupFailure.addSuppressed(failure);
            }
        }
        if (cleanupFailure == null)
        {
            return;
        }
        if (operationFailure != null)
        {
            operationFailure.addSuppressed(cleanupFailure);
            return;
        }
        throw new IllegalStateException("附件启动探针文件清理失败", cleanupFailure);
    }

    /**
     * 生成 UTC 日期分片和随机对象键。
     * @param originalName String，规范化后的原始文件名
     * @return String，私有根内的对象键
     */
    private String createStorageKey(String originalName)
    {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String date = String.format(Locale.ROOT, "%04d/%02d/%02d", today.getYear(),
                today.getMonthValue(), today.getDayOfMonth());
        return date + "/" + UUID.randomUUID().toString().replace("-", "")
                + safeStorageExtension(originalName);
    }

    /**
     * 提取安全的扩展名片段。
     * @param originalName String，规范化后的原始文件名
     * @return String，空串或受控小写扩展名
     */
    private String safeStorageExtension(String originalName)
    {
        int dot = originalName.lastIndexOf('.');
        if (dot <= 0 || dot == originalName.length() - 1)
        {
            return "";
        }
        String extension = originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return extension.matches("[a-z0-9]{1,16}") ? "." + extension : "";
    }

    /**
     * 清理并校验客户端原始文件名。
     * @param originalFilename String，multipart 原始文件名
     * @return String，不含目录语义且长度受限的文件名
     */
    private String normalizeOriginalName(String originalFilename)
    {
        if (!StringUtils.hasText(originalFilename))
        {
            throw invalidUpload("上传附件文件名不能为空");
        }
        String normalized = Normalizer.normalize(originalFilename, Normalizer.Form.NFC)
                .replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (!StringUtils.hasText(name) || ".".equals(name) || "..".equals(name)
                || name.length() > MAX_ORIGINAL_NAME_LENGTH
                || name.codePoints().anyMatch(Character::isISOControl))
        {
            throw invalidUpload("上传附件文件名不合法");
        }
        return name;
    }

    /**
     * 根据文件内容和文件名探测受控 MIME 类型。
     * @param input InputStream，临时文件读取流
     * @param originalName String，规范化后的原始文件名
     * @return String，合法 MIME 类型
     * @throws IOException 文件内容读取失败
     */
    private String detectContentType(InputStream input, String originalName) throws IOException
    {
        String detected = URLConnection.guessContentTypeFromStream(
                input instanceof BufferedInputStream ? input : new BufferedInputStream(input));
        if (!StringUtils.hasText(detected))
        {
            detected = URLConnection.getFileNameMap().getContentTypeFor(originalName);
        }
        if (!StringUtils.hasText(detected))
        {
            return "application/octet-stream";
        }
        String normalized = detected.toLowerCase(Locale.ROOT).trim();
        return MIME_PATTERN.matcher(normalized).matches() ? normalized : "application/octet-stream";
    }

    /**
     * 应用私有目录的 POSIX 权限；非 POSIX 文件系统保持部署 ACL。
     * @param directory Path，已校验的私有目录
     * @return void，无返回值
     * @throws IOException 权限设置失败
     */
    private void applyDirectoryPermissions(Path directory) throws IOException
    {
        try
        {
            Files.setPosixFilePermissions(directory, PRIVATE_DIRECTORY_PERMISSIONS);
        }
        catch (UnsupportedOperationException ignored)
        {
        }
    }

    /**
     * 应用私有文件的 POSIX 权限；非 POSIX 文件系统保持部署 ACL。
     * @param file Path，已创建的私有文件
     * @return void，无返回值
     * @throws IOException 权限设置失败
     */
    private void applyFilePermissions(Path file) throws IOException
    {
        try
        {
            Files.setPosixFilePermissions(file, PRIVATE_FILE_PERMISSIONS);
        }
        catch (UnsupportedOperationException ignored)
        {
        }
    }

    /**
     * 创建新的 SHA-256 摘要器。
     * @return MessageDigest，新的 SHA-256 摘要器
     */
    private MessageDigest sha256Digest()
    {
        try
        {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException failure)
        {
            throw new IllegalStateException("JDK缺少SHA-256算法", failure);
        }
    }

    /**
     * 将 profile 配置转换为非空路径。
     * @param profile String，配置中的 profile 路径
     * @return Path，profile 路径
     */
    private static Path toProfilePath(String profile)
    {
        if (!StringUtils.hasText(profile))
        {
            throw new IllegalArgumentException("若依profile目录不能为空");
        }
        return Path.of(profile);
    }

    /**
     * 校验附件配置对象非空。
     * @param properties WorkflowAttachmentProperties，附件配置
     * @return WorkflowAttachmentProperties，原配置对象
     */
    private static WorkflowAttachmentProperties requireProperties(WorkflowAttachmentProperties properties)
    {
        if (properties == null)
        {
            throw new IllegalArgumentException("工作流附件配置不能为空");
        }
        return properties;
    }

    /**
     * 创建上传参数异常。
     * @param message String，稳定业务消息
     * @return ServiceException，HTTP 400 异常
     */
    private ServiceException invalidUpload(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 创建通用存储失败异常。
     * @param message String，稳定业务消息
     * @param cause Throwable，底层原因
     * @return ServiceException，HTTP 500 异常
     */
    private ServiceException storageFailure(String message, Throwable cause)
    {
        return storageFailure(message, cause, HttpStatus.ERROR);
    }

    /**
     * 创建指定状态的存储异常。
     * @param message String，稳定业务消息
     * @param cause Throwable，底层原因
     * @param status int，HTTP 状态码
     * @return ServiceException，封装底层原因的业务异常
     */
    private ServiceException storageFailure(String message, Throwable cause, int status)
    {
        ServiceException failure = new ServiceException(message, status);
        failure.initCause(cause);
        return failure;
    }

    /**
     * 单次流式操作计算出的文件大小和摘要。
     * @param fileSize long，实际文件大小
     * @param sha256 String，SHA-256 小写摘要
     */
    private record FileDigest(long fileSize, String sha256) { }
}
