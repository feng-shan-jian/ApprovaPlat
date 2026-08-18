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
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
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
 * 工作流附件私有文件存储，负责受控命名、流式限额、摘要、MIME 探测和路径隔离。
 */
@Component
public class WorkflowAttachmentStorage
{
    /** 位于若依 profile 下但禁止通过 /profile/** 静态映射读取的私有目录名。 */
    public static final String PRIVATE_DIRECTORY_NAME = "workflow-attachments";

    /** 共享卷必须由运维预置的非敏感身份文件，应用只读校验且绝不自动创建。 */
    public static final String STORAGE_ID_MARKER_NAME = ".storage-id";

    /** 启动探针写入并回读的固定非敏感正文。 */
    private static final byte[] READINESS_PROBE_CONTENT =
            "approvaplat-workflow-storage-readiness".getBytes(StandardCharsets.US_ASCII);

    /** 崩溃恢复 journal 使用的私有目录名，业务附件对象键无法进入该命名空间。 */
    static final String READINESS_PROBE_JOURNAL_DIRECTORY_NAME = ".readiness-probes";

    /** 探针名称协议版本前缀；正式附件只允许 32 位十六进制文件名，不会与其重叠。 */
    static final String READINESS_PROBE_NAME_PREFIX = "workflow-readiness-v1-";

    /** 至少一小时未更新的 journal 才允许回收，避免并发节点正在执行的探针被误删。 */
    static final long READINESS_PROBE_STALE_MILLIS = 60L * 60L * 1000L;

    /** 单轮最多检查的 journal 条目数，防止异常目录内容拖死唯一周期采集 worker。 */
    private static final int MAX_READINESS_JOURNAL_ENTRIES = 1024;

    /**
     * journal 名冻结创建毫秒、UTC 日期和随机标识；恢复逻辑只处理完全匹配的普通文件。
     */
    private static final Pattern READINESS_JOURNAL_NAME_PATTERN = Pattern.compile(
            "workflow-readiness-v1-([0-9]{13})-([0-9]{8})-([0-9a-f]{32})\\.journal");

    /** journal 日期使用固定 UTC 基本格式，解析后再规范化回 yyyy/MM/dd。 */
    private static final DateTimeFormatter READINESS_DATE_FORMATTER =
            DateTimeFormatter.BASIC_ISO_DATE;

    /** 客户端文件名最大字符数，与正式表列长度一致。 */
    static final int MAX_ORIGINAL_NAME_LENGTH = 255;

    /** 单次流式写入缓冲区大小。 */
    private static final int BUFFER_SIZE = 16 * 1024;

    /** 私有对象键的固定结构，拒绝绝对路径、反斜杠和任何父目录片段。 */
    private static final Pattern STORAGE_KEY_PATTERN = Pattern.compile(
            "[0-9]{4}/[0-9]{2}/[0-9]{2}/[0-9a-f]{32}(?:\\.[a-z0-9]{1,16})?");

    /** 合法 MIME 主类型和子类型，不接受参数或响应头控制字符。 */
    private static final Pattern MIME_PATTERN = Pattern.compile(
            "[a-z0-9][a-z0-9!#$&^_.+\\-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+\\-]{0,126}");

    /** 数据库摘要必须是存储阶段生成的 SHA-256 小写十六进制文本。 */
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    /** 私有目录在 POSIX 文件系统上使用的最小权限。 */
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    /** 私有文件在 POSIX 文件系统上使用的最小权限。 */
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    /** 规范化后的若依 profile 根目录。 */
    private final Path profileRoot;

    /** profile 下受保护的附件私有根目录。 */
    private final Path storageRoot;

    /** 构造阶段固定且不经过链接的私有根真实路径。 */
    private final Path realStorageRoot;

    /** 构造阶段记录的私有根目录身份，防止运行中被普通目录或链接替换。 */
    private final DirectoryIdentity storageRootIdentity;

    /** 服务端实际流式读取允许的最大字节数。 */
    private final long maxSize;

    /**
     * 使用正式若依 profile 和附件资源配置创建私有存储。
     *
     * @param profile String，若依正式 profile 目录
     * @param properties WorkflowAttachmentProperties，附件资源限制
     * @return 无返回值，构造后由 Spring 管理该组件
     */
    @Autowired
    public WorkflowAttachmentStorage(@Value("${ruoyi.profile}") String profile,
            WorkflowAttachmentProperties properties)
    {
        this(toProfilePath(profile), requireProperties(properties).getMaxSize());
    }

    /**
     * 使用显式 profile 根目录创建存储，供隔离文件系统集成测试复用。
     *
     * @param profileRoot Path，测试或正式若依 profile 根目录
     * @param maxSize long，单个附件实际字节上限
     * @return 无返回值，参数异常时拒绝创建存储
     */
    public WorkflowAttachmentStorage(Path profileRoot, long maxSize)
    {
        if (profileRoot == null || maxSize <= 0L)
        {
            throw new IllegalArgumentException("工作流附件存储配置不合法");
        }
        this.profileRoot = profileRoot.toAbsolutePath().normalize();
        this.storageRoot = this.profileRoot.resolve(PRIVATE_DIRECTORY_NAME).normalize();
        if (!this.storageRoot.startsWith(this.profileRoot)
                || this.storageRoot.equals(this.profileRoot))
        {
            throw new IllegalArgumentException("工作流附件私有目录必须位于若依profile子目录");
        }
        this.maxSize = maxSize;
        try
        {
            createDirectoryTreeWithoutLinks(this.profileRoot);
            DirectoryIdentity profileIdentity = readOrdinaryDirectoryIdentity(
                    this.profileRoot);
            if (Files.exists(this.storageRoot, LinkOption.NOFOLLOW_LINKS))
            {
                readOrdinaryDirectoryIdentity(this.storageRoot);
            }
            else
            {
                Files.createDirectory(this.storageRoot);
            }
            applyDirectoryPermissions(this.storageRoot);
            this.storageRootIdentity = readOrdinaryDirectoryIdentity(this.storageRoot);
            this.realStorageRoot = this.storageRootIdentity.realPath();
            if (!this.realStorageRoot.getParent().equals(profileIdentity.realPath()))
            {
                throw new IOException("私有附件根真实路径不位于profile直属目录");
            }
        }
        catch (IOException | RuntimeException exception)
        {
            throw new IllegalStateException("工作流附件私有存储根初始化失败", exception);
        }
    }

    /**
     * 将认证请求中的文件流写入私有目录并计算全部可信元数据。
     *
     * @param file MultipartFile，客户端上传文件；客户端路径和 URL 均不参与存储定位
     * @return StoredAttachmentFile，服务端计算的对象键、名称、MIME、大小和摘要
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
        try
        {
            Path temporaryDirectory = storageRoot.resolve(".tmp").normalize();
            ensurePrivateDirectory(temporaryDirectory);
            ensurePrivateDirectory(destination.getParent());
            try (DirectoryStream<Path> rootStream = Files.newDirectoryStream(storageRoot))
            {
                if (rootStream instanceof SecureDirectoryStream<?>)
                {
                    @SuppressWarnings("unchecked")
                    SecureDirectoryStream<Path> secureRoot =
                            (SecureDirectoryStream<Path>) rootStream;
                    return storeUsingSecureDirectories(file, storageKey, originalName,
                            destination, temporaryDirectory, secureRoot);
                }
            }
            return storeUsingCheckedPaths(file, storageKey, originalName,
                    destination, temporaryDirectory);
        }
        catch (ServiceException exception)
        {
            throw exception;
        }
        catch (IOException exception)
        {
            throw storageFailure("工作流附件写入失败", exception);
        }
    }

    /**
     * 在支持 SecureDirectoryStream 的文件系统中使用目录句柄完成临时写入和相对移动。
     *
     * @param file MultipartFile，客户端上传文件
     * @param storageKey String，服务端生成的最终对象键
     * @param originalName String，规范化后的展示文件名
     * @param destination Path，最终对象词法路径
     * @param temporaryDirectory Path，私有根内临时目录
     * @param secureRoot SecureDirectoryStream&lt;Path&gt;，已打开的私有根目录句柄
     * @return StoredAttachmentFile，完成落盘和摘要计算的可信元数据
     * @throws IOException 目录句柄遍历、文件写入、探测或移动失败
     */
    private StoredAttachmentFile storeUsingSecureDirectories(MultipartFile file,
            String storageKey, String originalName, Path destination,
            Path temporaryDirectory, SecureDirectoryStream<Path> secureRoot)
            throws IOException
    {
        DirectoryChain temporaryChain = verifyTrustedDirectoryChain(temporaryDirectory);
        DirectoryChain destinationChain = verifyTrustedDirectoryChain(
                destination.getParent());
        Path temporaryName = Path.of("upload-"
                + UUID.randomUUID().toString().replace("-", "") + ".part");
        boolean moved = false;
        try (SecureParentHandle temporaryHandle = openSecureDirectory(
                    secureRoot, temporaryDirectory);
                SecureParentHandle destinationHandle = openSecureDirectory(
                    secureRoot, destination.getParent()))
        {
            try
            {
                FileDigest digest;
                Set<OpenOption> writeOptions = Set.of(StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                try (SeekableByteChannel channel = temporaryHandle.directory()
                        .newByteChannel(temporaryName, writeOptions))
                {
                    digest = copyAndDigest(file, Channels.newOutputStream(channel));
                }
                applyFilePermissions(temporaryHandle.directory(), temporaryName);
                String contentType = detectContentType(
                        temporaryHandle.directory(), temporaryName, originalName);

                temporaryHandle.directory().move(temporaryName,
                        destinationHandle.directory(), destination.getFileName());
                moved = true;
                applyFilePermissions(destinationHandle.directory(), destination.getFileName());
                verifyDirectoryChainUnchanged(temporaryChain);
                verifyDirectoryChainUnchanged(destinationChain);
                return new StoredAttachmentFile(storageKey, originalName, contentType,
                        digest.fileSize(), digest.sha256());
            }
            catch (RuntimeException | IOException failure)
            {
                deleteSecureFileQuietly(moved ? destinationHandle.directory()
                        : temporaryHandle.directory(), moved ? destination.getFileName()
                                : temporaryName, failure);
                throw failure;
            }
        }
    }

    /**
     * 在不支持 SecureDirectoryStream 的文件系统中以前后目录身份复核完成写入。
     *
     * @param file MultipartFile，客户端上传文件
     * @param storageKey String，服务端生成的最终对象键
     * @param originalName String，规范化后的展示文件名
     * @param destination Path，最终对象词法路径
     * @param temporaryDirectory Path，私有根内临时目录
     * @return StoredAttachmentFile，完成落盘和摘要计算的可信元数据
     * @throws IOException 文件写入、探测、移动或目录身份复核失败
     */
    private StoredAttachmentFile storeUsingCheckedPaths(MultipartFile file,
            String storageKey, String originalName, Path destination,
            Path temporaryDirectory) throws IOException
    {
        DirectoryChain temporaryChain = verifyTrustedDirectoryChain(temporaryDirectory);
        DirectoryChain destinationChain = verifyTrustedDirectoryChain(
                destination.getParent());
        Path temporaryFile = temporaryDirectory.resolve("upload-"
                + UUID.randomUUID().toString().replace("-", "") + ".part");
        boolean moved = false;
        try
        {
            FileDigest digest;
            try (OutputStream output = Files.newOutputStream(temporaryFile,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS))
            {
                digest = copyAndDigest(file, output);
            }
            applyFilePermissions(temporaryFile);
            String contentType;
            try (InputStream input = new BufferedInputStream(Files.newInputStream(
                    temporaryFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))
            {
                contentType = detectContentType(input, originalName);
            }
            verifyDirectoryChainUnchanged(temporaryChain);
            verifyDirectoryChainUnchanged(destinationChain);
            moveWithoutReplace(temporaryFile, destination);
            moved = true;
            verifyDirectoryChainUnchanged(destinationChain);
            requireOrdinaryFileRealPath(destination);
            applyFilePermissions(destination);
            return new StoredAttachmentFile(storageKey, originalName, contentType,
                    digest.fileSize(), digest.sha256());
        }
        catch (RuntimeException | IOException failure)
        {
            deleteCheckedFileQuietly(moved ? destination : temporaryFile, failure);
            throw failure;
        }
    }

    /**
     * 使用同一打开文件通道完成路径、大小和 SHA-256 校验，并把该通道直接交给响应流。
     *
     * @param storageKey String，数据库保存的私有相对对象键
     * @param expectedSize long，数据库保存的实际文件大小
     * @param expectedSha256 String，数据库保存的 SHA-256 小写摘要
     * @return InputStream，位置已复位到零且由调用方关闭的一次性可信内容流
     */
    public InputStream openVerifiedForRead(String storageKey, long expectedSize,
            String expectedSha256)
    {
        Path path = resolveStorageKey(storageKey);
        SeekableByteChannel channel = null;
        try
        {
            channel = openVerifiedChannel(path, expectedSize, expectedSha256);
            return Channels.newInputStream(channel);
        }
        catch (ServiceException exception)
        {
            closeChannelQuietly(channel, exception);
            throw exception;
        }
        catch (NoSuchFileException exception)
        {
            ServiceException failure = new ServiceException(
                    "工作流附件文件不存在", HttpStatus.NOT_FOUND);
            failure.initCause(exception);
            closeChannelQuietly(channel, failure);
            throw failure;
        }
        catch (IOException exception)
        {
            ServiceException failure = storageFailure("工作流附件文件读取失败", exception);
            closeChannelQuietly(channel, failure);
            throw failure;
        }
    }

    /**
     * 在附件绑定事务内重新核对物理文件和正式元数据一致。
     *
     * @param storageKey String，数据库保存的私有相对对象键
     * @param expectedSize long，数据库保存的实际文件大小
     * @param expectedSha256 String，数据库保存的 SHA-256 小写摘要
     * @return void，文件缺失、被替换或正文篡改时抛出业务异常
     */
    public void verify(String storageKey, long expectedSize, String expectedSha256)
    {
        try (InputStream ignored = openVerifiedForRead(
                storageKey, expectedSize, expectedSha256))
        {
            // openVerifiedForRead 已完成全文摘要，关闭同一通道即可。
        }
        catch (ServiceException exception)
        {
            throw exception;
        }
        catch (IOException exception)
        {
            throw storageFailure("工作流附件文件关闭失败", exception);
        }
    }

    /**
     * 打开拒绝最终符号链接的文件通道，并在该通道上完成全文摘要校验。
     *
     * @param path Path，由受控对象键解析出的候选文件
     * @param expectedSize long，数据库记录的服务端实际字节数
     * @param expectedSha256 String，数据库记录的 SHA-256 摘要
     * @return SeekableByteChannel，校验成功且位置已复位到零的打开通道
     * @throws IOException 真实路径解析、通道打开或读取失败
     */
    private SeekableByteChannel openVerifiedChannel(Path path, long expectedSize,
            String expectedSha256) throws IOException
    {
        if (expectedSize <= 0L || expectedSha256 == null
                || !SHA256_PATTERN.matcher(expectedSha256).matches())
        {
            throw new ServiceException("工作流附件文件完整性元数据异常", HttpStatus.ERROR);
        }
        DirectoryChain directoryChain = verifyTrustedDirectoryChain(path.getParent());
        SeekableByteChannel channel = null;
        try
        {
            try (DirectoryStream<Path> rootStream = Files.newDirectoryStream(storageRoot))
            {
                if (rootStream instanceof SecureDirectoryStream<?>)
                {
                    @SuppressWarnings("unchecked")
                    SecureDirectoryStream<Path> secureRoot =
                            (SecureDirectoryStream<Path>) rootStream;
                    try (SecureParentHandle parentHandle = openSecureDirectory(
                            secureRoot, path.getParent()))
                    {
                        requireSecureOrdinaryFile(parentHandle.directory(), path.getFileName());
                        channel = parentHandle.directory().newByteChannel(path.getFileName(),
                                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                    }
                }
            }
            if (channel == null)
            {
                Path realFile = requireOrdinaryFileRealPath(path);
                channel = Files.newByteChannel(realFile,
                        StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            }
            verifyDirectoryChainUnchanged(directoryChain);
            FileDigest digest = digestChannel(channel);
            if (channel.size() != expectedSize || digest.fileSize() != expectedSize
                    || !expectedSha256.equals(digest.sha256()))
            {
                throw new ServiceException("工作流附件文件完整性校验失败", HttpStatus.ERROR);
            }
            verifyDirectoryChainUnchanged(directoryChain);
            channel.position(0L);
            return channel;
        }
        catch (RuntimeException | IOException exception)
        {
            closeChannelQuietly(channel, exception);
            throw exception;
        }
    }

    /**
     * 在已打开通道上流式计算实际大小和 SHA-256，不重新按路径打开文件。
     *
     * @param channel SeekableByteChannel，位置可调整的只读附件通道
     * @return FileDigest，从同一通道读取的实际字节数和摘要
     * @throws IOException 通道定位或读取失败
     */
    private FileDigest digestChannel(SeekableByteChannel channel) throws IOException
    {
        MessageDigest messageDigest = sha256Digest();
        long fileSize = 0L;
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        channel.position(0L);
        int bytesRead;
        while ((bytesRead = channel.read(buffer)) != -1)
        {
            if (bytesRead == 0)
            {
                buffer.clear();
                continue;
            }
            fileSize += bytesRead;
            messageDigest.update(buffer.array(), 0, bytesRead);
            buffer.clear();
        }
        return new FileDigest(fileSize, HexFormat.of().formatHex(messageDigest.digest()));
    }

    /**
     * 关闭尚未移交给响应的文件通道，并把关闭失败附加到原异常。
     *
     * @param channel SeekableByteChannel，可为空的待关闭通道
     * @param originalFailure Throwable，必须继续抛出的原失败
     * @return void，无返回值
     */
    private void closeChannelQuietly(SeekableByteChannel channel, Throwable originalFailure)
    {
        if (channel == null)
        {
            return;
        }
        try
        {
            channel.close();
        }
        catch (IOException closeFailure)
        {
            originalFailure.addSuppressed(closeFailure);
        }
    }

    /**
     * 删除指定私有对象；文件已不存在视为清理成功以支持幂等重试。
     *
     * @param storageKey String，数据库保存的私有相对对象键
     * @return boolean，文件本次实际被删除时返回 true，原本不存在返回 false
     */
    public boolean delete(String storageKey)
    {
        Path path = resolveStorageKey(storageKey);
        try
        {
            DirectoryChain directoryChain = verifyTrustedDirectoryChain(path.getParent());
            try (DirectoryStream<Path> rootStream = Files.newDirectoryStream(storageRoot))
            {
                if (rootStream instanceof SecureDirectoryStream<?>)
                {
                    @SuppressWarnings("unchecked")
                    SecureDirectoryStream<Path> secureRoot =
                            (SecureDirectoryStream<Path>) rootStream;
                    try (SecureParentHandle parentHandle = openSecureDirectory(
                            secureRoot, path.getParent()))
                    {
                        requireSecureOrdinaryFile(parentHandle.directory(), path.getFileName());
                        parentHandle.directory().deleteFile(path.getFileName());
                    }
                    verifyDirectoryChainUnchanged(directoryChain);
                    return true;
                }
            }

            Path realFile = requireOrdinaryFileRealPath(path);
            Files.delete(realFile);
            verifyDirectoryChainUnchanged(directoryChain);
            return true;
        }
        catch (NoSuchFileException exception)
        {
            // 文件或其受控日期目录已不存在时按幂等删除成功处理。
            return false;
        }
        catch (ServiceException exception)
        {
            throw exception;
        }
        catch (IOException exception)
        {
            // 仅明确的文件系统 I/O 失败进入调度退避；安全和完整性异常继续 fail-closed。
            throw new WorkflowAttachmentStorageOperationException(
                    "工作流附件文件清理失败", exception);
        }
    }

    /**
     * 在固定私有根身份校验后读取文件系统当前可用空间。
     *
     * @return long，私有根所在文件系统可供当前进程使用的非负字节数
     */
    public long usableSpace()
    {
        try
        {
            verifyStorageRootIdentity();
            long usableBytes = Files.getFileStore(realStorageRoot).getUsableSpace();
            if (usableBytes < 0L)
            {
                throw new IOException("文件系统返回负数可用空间");
            }
            return usableBytes;
        }
        catch (ServiceException exception)
        {
            throw exception;
        }
        catch (IOException exception)
        {
            throw storageFailure("工作流附件磁盘空间读取失败", exception);
        }
    }

    /**
     * 校验受控根、共享卷预置标识、真实写入回读和跨目录发布，并核对磁盘低水位。
     * 本机探针只能证明当前挂载点能力，不能替代跨节点共享存储验收。
     *
     * @param expectedStorageId String，共享模式预置 .storage-id 的期望值；本地持久卷传 null
     * @param minFreeBytes long，完成探针后必须保留的最小可用字节数
     * @return void，任一能力、身份或容量门禁失败时阻止生产应用启动
     */
    public void verifyRuntimeReadiness(String expectedStorageId, long minFreeBytes)
    {
        probeRuntimeReadiness(expectedStorageId, minFreeBytes);
    }

    /**
     * 执行启动和周期采集共用的真实存储探针，并返回清理完成后的可用空间。探针先核对共享
     * 卷身份，再安全回收陈旧 journal，最后执行写入、跨目录移动、回读和无残留清理。
     *
     * @param expectedStorageId String，共享模式预置 .storage-id 的期望值；本地持久卷传 null
     * @param minFreeBytes long，完成探针并清理后必须保留的最小可用字节数
     * @return long，本轮探针全部成功且清理完成后的文件系统可用字节数
     */
    public long probeRuntimeReadiness(String expectedStorageId, long minFreeBytes)
    {
        if (minFreeBytes < 0L)
        {
            throw new IllegalArgumentException("工作流附件磁盘低水位不能为负数");
        }
        try
        {
            verifyStorageRootIdentity();
            if (expectedStorageId != null)
            {
                // 共享卷身份必须先于任何目录创建或探针写入确认，避免误挂载点产生副作用。
                verifyPreprovisionedStorageId(expectedStorageId);
                requireSecureDirectoryStreamSupport();
            }
            Path temporaryDirectory = storageRoot.resolve(".tmp").normalize();
            ensurePrivateDirectory(temporaryDirectory);
            Path destinationDirectory = currentStorageDateDirectory();
            ensurePrivateDirectory(destinationDirectory);
            Path journalDirectory = storageRoot.resolve(
                    READINESS_PROBE_JOURNAL_DIRECTORY_NAME).normalize();
            ensurePrivateDirectory(journalDirectory);
            recoverStaleReadinessProbes(temporaryDirectory, journalDirectory,
                    expectedStorageId != null);
            verifyWritableAtomicMove(temporaryDirectory, destinationDirectory,
                    journalDirectory, expectedStorageId != null);
            long usableBytes = Files.getFileStore(realStorageRoot).getUsableSpace();
            if (usableBytes < minFreeBytes)
            {
                throw new IOException("附件存储可用空间低于生产配置低水位");
            }
            verifyStorageRootIdentity();
            return usableBytes;
        }
        catch (IOException | RuntimeException exception)
        {
            throw new IllegalStateException("工作流附件生产存储就绪校验失败", exception);
        }
    }

    /**
     * 读取运维预置共享卷身份，拒绝缺失、链接、超长、乱码或与部署声明不一致的标记。
     *
     * @param expectedStorageId String，部署审批中冻结的共享卷非敏感标识
     * @return void，标记不是受控普通文件或内容不一致时抛出异常
     * @throws IOException 文件属性或正文读取失败
     */
    private void verifyPreprovisionedStorageId(String expectedStorageId) throws IOException
    {
        if (expectedStorageId.isBlank()
                || expectedStorageId.getBytes(StandardCharsets.US_ASCII).length > 64)
        {
            throw new IOException("共享附件存储标识配置不合法");
        }
        Path marker = storageRoot.resolve(STORAGE_ID_MARKER_NAME).normalize();
        Path realMarker = requireOrdinaryFileRealPath(marker);
        long markerSize = Files.size(realMarker);
        if (markerSize <= 0L || markerSize > 128L)
        {
            throw new IOException("共享附件存储标记大小不合法");
        }
        String actualStorageId = Files.readString(realMarker, StandardCharsets.UTF_8).strip();
        requireOrdinaryFileRealPath(marker);
        if (!expectedStorageId.equals(actualStorageId))
        {
            throw new IOException("共享附件存储标识与部署配置不一致");
        }
    }

    /**
     * 在共享卷产生任何探针目录副作用前确认文件系统支持安全目录句柄。
     *
     * @return void，能力可用时返回
     * @throws IOException 文件系统不支持抵抗父目录 ABA 的安全目录句柄
     */
    private void requireSecureDirectoryStreamSupport() throws IOException
    {
        try (DirectoryStream<Path> rootStream = Files.newDirectoryStream(storageRoot))
        {
            if (!(rootStream instanceof SecureDirectoryStream<?>))
            {
                throw new IOException("共享附件存储不支持安全目录句柄");
            }
        }
    }

    /**
     * 在新探针开始前回收崩溃遗留；仅严格匹配协议、创建时间与最后修改时间都超过一小时
     * 的 journal 可触发删除，当前或并发节点探针不会进入回收分支。
     *
     * @param temporaryDirectory Path，探针 source 所在私有临时目录
     * @param journalDirectory Path，严格隔离的探针 journal 目录
     * @param requireSecureDirectoryStream boolean，共享卷是否强制安全目录句柄
     * @return void，陈旧探针全部清理或不存在时返回
     * @throws IOException journal 损坏、目录能力不足或安全清理失败
     */
    private void recoverStaleReadinessProbes(Path temporaryDirectory,
            Path journalDirectory, boolean requireSecureDirectoryStream)
            throws IOException
    {
        DirectoryChain temporaryChain = verifyTrustedDirectoryChain(temporaryDirectory);
        DirectoryChain journalChain = verifyTrustedDirectoryChain(journalDirectory);
        try (DirectoryStream<Path> rootStream = Files.newDirectoryStream(storageRoot))
        {
            if (rootStream instanceof SecureDirectoryStream<?>)
            {
                @SuppressWarnings("unchecked")
                SecureDirectoryStream<Path> secureRoot =
                        (SecureDirectoryStream<Path>) rootStream;
                recoverStaleReadinessProbesUsingSecureDirectories(temporaryDirectory,
                        journalDirectory, temporaryChain, journalChain, secureRoot);
                return;
            }
        }
        if (requireSecureDirectoryStream)
        {
            throw new IOException("共享附件存储不支持安全目录句柄");
        }
        recoverStaleReadinessProbesUsingCheckedPaths(temporaryDirectory,
                journalDirectory, temporaryChain, journalChain);
    }

    /**
     * 使用固定根、临时目录和 journal 目录安全句柄回收陈旧探针，target 目录也只按 journal
     * 中冻结的 UTC 日期逐级无链接打开。
     *
     * @param temporaryDirectory Path，探针 source 所在目录
     * @param journalDirectory Path，探针 journal 所在目录
     * @param temporaryChain DirectoryChain，临时目录操作前身份链
     * @param journalChain DirectoryChain，journal 目录操作前身份链
     * @param secureRoot SecureDirectoryStream&lt;Path&gt;，私有根安全句柄
     * @return void，所有可回收陈旧探针安全删除后返回
     * @throws IOException 条目损坏、目录替换或删除失败
     */
    private void recoverStaleReadinessProbesUsingSecureDirectories(
            Path temporaryDirectory, Path journalDirectory,
            DirectoryChain temporaryChain, DirectoryChain journalChain,
            SecureDirectoryStream<Path> secureRoot) throws IOException
    {
        try (SecureParentHandle temporaryHandle = openSecureDirectory(
                    secureRoot, temporaryDirectory);
                SecureParentHandle journalHandle = openSecureDirectory(
                    secureRoot, journalDirectory))
        {
            int inspectedEntries = 0;
            for (Path entry : journalHandle.directory())
            {
                if (++inspectedEntries > MAX_READINESS_JOURNAL_ENTRIES)
                {
                    throw new IOException("附件存储探针journal条目超过安全上限");
                }
                Path journalName = entry.getFileName();
                ReadinessProbeIdentity identity = parseReadinessJournalName(
                        journalName.toString());
                if (identity == null)
                {
                    // 非协议条目不属于应用，绝不凭前缀猜测并删除。
                    continue;
                }
                BasicFileAttributes attributes;
                try
                {
                    attributes = readSecureOrdinaryFileAttributes(
                            journalHandle.directory(), journalName);
                }
                catch (NoSuchFileException concurrentCleanup)
                {
                    continue;
                }
                if (!isStaleReadinessJournal(identity, attributes))
                {
                    continue;
                }
                verifySecureReadinessJournal(journalHandle.directory(), journalName,
                        identity);

                IOException cleanupFailure = deleteSecureProbeFile(
                        temporaryHandle.directory(), identity.sourceName());
                try
                {
                    Path destinationDirectory = identity.destinationDirectory(storageRoot);
                    try (SecureParentHandle destinationHandle = openSecureDirectory(
                            secureRoot, destinationDirectory))
                    {
                        cleanupFailure = mergeCleanupFailure(cleanupFailure,
                                deleteSecureProbeFile(destinationHandle.directory(),
                                        identity.targetName()));
                    }
                }
                catch (NoSuchFileException missingDestinationDirectory)
                {
                    // 目标日期目录不存在时 target 不可能存在，可继续清理 source 与 journal。
                }
                if (cleanupFailure != null)
                {
                    throw cleanupFailure;
                }
                IOException journalFailure = deleteSecureProbeFile(
                        journalHandle.directory(), journalName);
                if (journalFailure != null)
                {
                    throw journalFailure;
                }
            }
            verifyDirectoryChainUnchanged(temporaryChain);
            verifyDirectoryChainUnchanged(journalChain);
        }
    }

    /**
     * 在不支持安全目录句柄的本地持久卷上，以父目录身份前后复核回收陈旧探针。
     *
     * @param temporaryDirectory Path，探针 source 所在目录
     * @param journalDirectory Path，探针 journal 所在目录
     * @param temporaryChain DirectoryChain，临时目录身份链
     * @param journalChain DirectoryChain，journal 目录身份链
     * @return void，所有可回收陈旧探针安全删除后返回
     * @throws IOException journal 校验、目录身份复核或删除失败
     */
    private void recoverStaleReadinessProbesUsingCheckedPaths(Path temporaryDirectory,
            Path journalDirectory, DirectoryChain temporaryChain,
            DirectoryChain journalChain) throws IOException
    {
        int inspectedEntries = 0;
        try (DirectoryStream<Path> journalEntries = Files.newDirectoryStream(journalDirectory))
        {
            for (Path journal : journalEntries)
            {
                if (++inspectedEntries > MAX_READINESS_JOURNAL_ENTRIES)
                {
                    throw new IOException("附件存储探针journal条目超过安全上限");
                }
                ReadinessProbeIdentity identity = parseReadinessJournalName(
                        journal.getFileName().toString());
                if (identity == null)
                {
                    continue;
                }
                BasicFileAttributes attributes;
                try
                {
                    attributes = readOrdinaryFileAttributes(journal);
                }
                catch (NoSuchFileException concurrentCleanup)
                {
                    continue;
                }
                if (!isStaleReadinessJournal(identity, attributes))
                {
                    continue;
                }
                verifyCheckedReadinessJournal(journal, identity);

                IOException cleanupFailure = deleteCheckedProbeFile(
                        temporaryDirectory.resolve(identity.sourceName()));
                Path destinationDirectory = identity.destinationDirectory(storageRoot);
                if (Files.exists(destinationDirectory, LinkOption.NOFOLLOW_LINKS))
                {
                    cleanupFailure = mergeCleanupFailure(cleanupFailure,
                            deleteCheckedProbeFile(
                                    destinationDirectory.resolve(identity.targetName())));
                }
                if (cleanupFailure != null)
                {
                    throw cleanupFailure;
                }
                IOException journalFailure = deleteCheckedProbeFile(journal);
                if (journalFailure != null)
                {
                    throw journalFailure;
                }
            }
        }
        verifyDirectoryChainUnchanged(temporaryChain);
        verifyDirectoryChainUnchanged(journalChain);
    }

    /**
     * 从私有临时目录写入并移动到正式日期目录，回读后无残留删除唯一探针。共享卷强制
     * 使用安全目录句柄抵抗父目录 ABA，本地卷回退路径执行操作前后身份链复核。
     *
     * @param temporaryDirectory Path，已完成身份校验的 storageRoot/.tmp 目录
     * @param destinationDirectory Path，与真实上传一致的 UTC 年/月/日正式目录
     * @param journalDirectory Path，崩溃恢复 journal 所在私有目录
     * @param requireSecureDirectoryStream boolean，共享卷是否必须支持安全目录句柄
     * @return void，探针能力不足或无法无残留清理时抛出异常
     * @throws IOException 文件写入、跨目录移动、回读或清理失败
     */
    private void verifyWritableAtomicMove(Path temporaryDirectory,
            Path destinationDirectory, Path journalDirectory,
            boolean requireSecureDirectoryStream)
            throws IOException
    {
        DirectoryChain temporaryChain = verifyTrustedDirectoryChain(temporaryDirectory);
        DirectoryChain destinationChain = verifyTrustedDirectoryChain(destinationDirectory);
        try (DirectoryStream<Path> rootStream = Files.newDirectoryStream(storageRoot))
        {
            if (rootStream instanceof SecureDirectoryStream<?>)
            {
                @SuppressWarnings("unchecked")
                SecureDirectoryStream<Path> secureRoot =
                        (SecureDirectoryStream<Path>) rootStream;
                verifyWritableMoveUsingSecureDirectories(temporaryDirectory,
                        destinationDirectory, journalDirectory, temporaryChain,
                        destinationChain, secureRoot);
                return;
            }
        }
        if (requireSecureDirectoryStream)
        {
            // 共享卷不能使用存在父目录 ABA 窗口的词法路径回退，能力不足必须 fail-closed。
            throw new IOException("共享附件存储不支持安全目录句柄");
        }
        verifyWritableAtomicMoveUsingCheckedPaths(temporaryDirectory,
                destinationDirectory, journalDirectory, temporaryChain, destinationChain);
    }

    /**
     * 使用打开的私有根和临时目录句柄完成跨目录探针，所有条目操作均为单段随机名称。
     *
     * @param temporaryDirectory Path，私有根内临时目录词法路径
     * @param destinationDirectory Path，与真实上传一致的正式日期目录
     * @param journalDirectory Path，探针崩溃恢复 journal 目录
     * @param temporaryChain DirectoryChain，操作前临时目录身份链
     * @param destinationChain DirectoryChain，操作前正式私有根身份链
     * @param secureRoot SecureDirectoryStream&lt;Path&gt;，固定私有根安全句柄
     * @return void，写入、移动、回读、身份复核或清理失败时抛出异常
     * @throws IOException 安全目录句柄操作失败
     */
    private void verifyWritableMoveUsingSecureDirectories(Path temporaryDirectory,
            Path destinationDirectory, Path journalDirectory,
            DirectoryChain temporaryChain,
            DirectoryChain destinationChain, SecureDirectoryStream<Path> secureRoot)
            throws IOException
    {
        ReadinessProbeIdentity identity = createReadinessProbeIdentity(
                destinationDirectory);
        try (SecureParentHandle temporaryHandle = openSecureDirectory(
                    secureRoot, temporaryDirectory);
                SecureParentHandle destinationHandle = openSecureDirectory(
                    secureRoot, destinationDirectory);
                SecureParentHandle journalHandle = openSecureDirectory(
                    secureRoot, journalDirectory))
        {
            Throwable operationFailure = null;
            boolean targetOwned = false;
            try
            {
                Set<OpenOption> journalWriteOptions = Set.of(
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
                try (SeekableByteChannel journalChannel = journalHandle.directory()
                        .newByteChannel(identity.journalName(), journalWriteOptions))
                {
                    writeReadinessJournal(journalChannel, identity);
                }
                applyFilePermissions(journalHandle.directory(), identity.journalName());
                requireSecureOrdinaryFile(journalHandle.directory(), identity.journalName());

                Set<OpenOption> writeOptions = Set.of(StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                try (SeekableByteChannel channel = temporaryHandle.directory()
                        .newByteChannel(identity.sourceName(), writeOptions))
                {
                    writeProbeContent(channel);
                }
                applyFilePermissions(temporaryHandle.directory(), identity.sourceName());
                requireSecureOrdinaryFile(temporaryHandle.directory(), identity.sourceName());

                // 句柄相对 move 覆盖真实上传使用的 .tmp 到正式目录拓扑且不重新解析父路径。
                temporaryHandle.directory().move(identity.sourceName(),
                        destinationHandle.directory(), identity.targetName());
                targetOwned = true;
                requireSecureOrdinaryFile(destinationHandle.directory(),
                        identity.targetName());
                try (SeekableByteChannel channel = destinationHandle.directory()
                        .newByteChannel(identity.targetName(),
                                Set.of(StandardOpenOption.READ,
                                        LinkOption.NOFOLLOW_LINKS)))
                {
                    verifyProbeContent(channel);
                }
                verifyDirectoryChainUnchanged(temporaryChain);
                verifyDirectoryChainUnchanged(destinationChain);
            }
            catch (RuntimeException | Error | IOException failure)
            {
                operationFailure = failure;
                throw failure;
            }
            finally
            {
                IOException cleanupFailure = cleanupSecureProbeFiles(
                        temporaryHandle.directory(), identity.sourceName(),
                        destinationHandle.directory(), identity.targetName(),
                        targetOwned);
                if (cleanupFailure == null)
                {
                    // source/target 都清理完成后才能删除 journal，否则崩溃恢复会失去定位凭据。
                    cleanupFailure = deleteSecureProbeFile(
                            journalHandle.directory(), identity.journalName());
                }
                propagateProbeCleanupFailure(operationFailure, cleanupFailure);
            }
        }
    }

    /**
     * 在不支持安全目录句柄的平台执行跨目录原子移动，并在每个危险边界前后复核目录身份。
     *
     * @param temporaryDirectory Path，私有根内临时目录
     * @param destinationDirectory Path，与真实上传一致的正式日期目录
     * @param journalDirectory Path，探针崩溃恢复 journal 目录
     * @param temporaryChain DirectoryChain，操作前临时目录身份链
     * @param destinationChain DirectoryChain，操作前正式私有根身份链
     * @return void，写入、原子移动、回读、身份复核或清理失败时抛出异常
     * @throws IOException 文件系统操作失败
     */
    private void verifyWritableAtomicMoveUsingCheckedPaths(Path temporaryDirectory,
            Path destinationDirectory, Path journalDirectory,
            DirectoryChain temporaryChain,
            DirectoryChain destinationChain) throws IOException
    {
        ReadinessProbeIdentity identity = createReadinessProbeIdentity(
                destinationDirectory);
        Path journal = journalDirectory.resolve(identity.journalName());
        Path source = temporaryDirectory.resolve(identity.sourceName());
        Path target = destinationDirectory.resolve(identity.targetName());
        Throwable operationFailure = null;
        boolean targetOwned = false;
        try
        {
            try (SeekableByteChannel journalChannel = Files.newByteChannel(journal,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS))
            {
                writeReadinessJournal(journalChannel, identity);
            }
            applyFilePermissions(journal);
            requireOrdinaryFileRealPath(journal);

            try (SeekableByteChannel channel = Files.newByteChannel(source,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS))
            {
                writeProbeContent(channel);
            }
            applyFilePermissions(source);
            requireOrdinaryFileRealPath(source);
            verifyDirectoryChainUnchanged(temporaryChain);
            verifyDirectoryChainUnchanged(destinationChain);

            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            targetOwned = true;
            requireOrdinaryFileRealPath(target);
            try (SeekableByteChannel channel = Files.newByteChannel(target,
                    StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
            {
                verifyProbeContent(channel);
            }
            verifyDirectoryChainUnchanged(temporaryChain);
            verifyDirectoryChainUnchanged(destinationChain);
        }
        catch (RuntimeException | Error | IOException failure)
        {
            operationFailure = failure;
            throw failure;
        }
        finally
        {
            IOException cleanupFailure = cleanupCheckedProbeFiles(
                    source, target, targetOwned);
            if (cleanupFailure == null)
            {
                cleanupFailure = deleteCheckedProbeFile(journal);
            }
            propagateProbeCleanupFailure(operationFailure, cleanupFailure);
        }
    }

    /**
     * 为一次探针生成冻结创建时间、UTC 日期和随机标识的严格名称集合。日期从已选正式
     * 目录反推，避免 UTC 跨日瞬间 journal 与 target 目录不一致。
     *
     * @param destinationDirectory Path，本轮探针正式日期目录
     * @return ReadinessProbeIdentity，journal、source 与 target 的唯一关联标识
     * @throws IOException 日期目录不是 storageRoot/yyyy/MM/dd 时拒绝创建探针
     */
    private ReadinessProbeIdentity createReadinessProbeIdentity(
            Path destinationDirectory) throws IOException
    {
        Path relative = storageRoot.relativize(destinationDirectory.toAbsolutePath().normalize());
        if (relative.getNameCount() != 3)
        {
            throw new IOException("附件存储探针目标日期目录不合法");
        }
        String compactDate = relative.getName(0).toString()
                + relative.getName(1) + relative.getName(2);
        LocalDate date;
        try
        {
            date = LocalDate.parse(compactDate, READINESS_DATE_FORMATTER);
        }
        catch (RuntimeException invalidDate)
        {
            throw new IOException("附件存储探针目标日期目录不合法", invalidDate);
        }
        long createdAtMillis = System.currentTimeMillis();
        String randomId = UUID.randomUUID().toString().replace("-", "");
        String baseName = READINESS_PROBE_NAME_PREFIX
                + String.format(Locale.ROOT, "%013d", createdAtMillis)
                + "-" + date.format(READINESS_DATE_FORMATTER) + "-" + randomId;
        return new ReadinessProbeIdentity(createdAtMillis, date, baseName);
    }

    /**
     * 解析严格 journal 名；不完全匹配协议的条目返回 null，恢复流程不会猜测或删除它。
     *
     * @param journalName String，journal 目录内的单段文件名
     * @return ReadinessProbeIdentity，合法协议身份；非协议名称返回 null
     * @throws IOException 名称匹配但时间或 UTC 日期无法安全解析
     */
    private ReadinessProbeIdentity parseReadinessJournalName(String journalName)
            throws IOException
    {
        Matcher matcher = READINESS_JOURNAL_NAME_PATTERN.matcher(journalName);
        if (!matcher.matches())
        {
            return null;
        }
        try
        {
            long createdAtMillis = Long.parseLong(matcher.group(1));
            LocalDate date = LocalDate.parse(matcher.group(2),
                    READINESS_DATE_FORMATTER);
            String baseName = READINESS_PROBE_NAME_PREFIX + matcher.group(1)
                    + "-" + matcher.group(2) + "-" + matcher.group(3);
            return new ReadinessProbeIdentity(createdAtMillis, date, baseName);
        }
        catch (RuntimeException invalidJournal)
        {
            throw new IOException("附件存储探针journal名称不合法", invalidJournal);
        }
    }

    /**
     * 同时使用名称冻结时间和文件系统最后修改时间判断 journal 是否陈旧，任一时间仍在
     * 保护窗口内都视为当前或并发探针。
     *
     * @param identity ReadinessProbeIdentity，名称中冻结的创建时间
     * @param attributes BasicFileAttributes，安全句柄或 NOFOLLOW_LINKS 读取的 journal 属性
     * @return boolean，两项时间都超过一小时保护窗口时为 true
     */
    private boolean isStaleReadinessJournal(ReadinessProbeIdentity identity,
            BasicFileAttributes attributes)
    {
        long staleCutoff = System.currentTimeMillis() - READINESS_PROBE_STALE_MILLIS;
        return identity.createdAtMillis() <= staleCutoff
                && attributes.lastModifiedTime().toMillis() <= staleCutoff;
    }

    /**
     * 通过安全目录句柄读取普通 journal 属性，拒绝链接、目录和特殊文件。
     *
     * @param directory SecureDirectoryStream&lt;Path&gt;，journal 所在目录安全句柄
     * @param fileName Path，严格 journal 单段文件名
     * @return BasicFileAttributes，拒绝链接后的普通文件属性
     * @throws IOException 条目不存在、类型异常或属性读取失败
     */
    private BasicFileAttributes readSecureOrdinaryFileAttributes(
            SecureDirectoryStream<Path> directory, Path fileName) throws IOException
    {
        BasicFileAttributes attributes = directory.getFileAttributeView(fileName,
                java.nio.file.attribute.BasicFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS).readAttributes();
        requireOrdinaryProbeFile(attributes);
        return attributes;
    }

    /**
     * 通过 NOFOLLOW_LINKS 读取本地持久卷 journal 属性，拒绝链接、目录和特殊文件。
     *
     * @param file Path，严格 journal 词法路径
     * @return BasicFileAttributes，普通文件属性
     * @throws IOException 条目不存在、类型异常或属性读取失败
     */
    private BasicFileAttributes readOrdinaryFileAttributes(Path file) throws IOException
    {
        BasicFileAttributes attributes = Files.readAttributes(file,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        requireOrdinaryProbeFile(attributes);
        return attributes;
    }

    /**
     * 校验探针或 journal 条目只能是普通文件，避免恢复逻辑处理链接或特殊对象。
     *
     * @param attributes BasicFileAttributes，拒绝跟随链接读取的条目属性
     * @return void，普通文件直接返回
     * @throws IOException 类型不符合探针协议时抛出
     */
    private void requireOrdinaryProbeFile(BasicFileAttributes attributes)
            throws IOException
    {
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                || attributes.isOther())
        {
            throw new IOException("附件存储探针条目不是普通文件");
        }
    }

    /**
     * 用安全目录句柄回读 journal 正文，确保严格文件名与当前探针身份一致后才允许恢复。
     *
     * @param directory SecureDirectoryStream&lt;Path&gt;，journal 目录句柄
     * @param journalName Path，严格 journal 单段文件名
     * @param identity ReadinessProbeIdentity，从文件名解析的预期身份
     * @return void，正文完全一致时返回
     * @throws IOException 安全打开或正文校验失败
     */
    private void verifySecureReadinessJournal(SecureDirectoryStream<Path> directory,
            Path journalName, ReadinessProbeIdentity identity) throws IOException
    {
        try (SeekableByteChannel channel = directory.newByteChannel(journalName,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))
        {
            verifyExactContent(channel, readinessJournalContent(identity),
                    "附件存储探针journal正文不一致");
        }
    }

    /**
     * 在本地持久卷按可信词法路径回读 journal 正文，校验后仍复核普通文件真实边界。
     *
     * @param journal Path，严格 journal 词法路径
     * @param identity ReadinessProbeIdentity，从文件名解析的预期身份
     * @return void，正文、类型和边界均一致时返回
     * @throws IOException 文件打开或正文校验失败
     */
    private void verifyCheckedReadinessJournal(Path journal,
            ReadinessProbeIdentity identity) throws IOException
    {
        requireOrdinaryFileRealPath(journal);
        try (SeekableByteChannel channel = Files.newByteChannel(journal,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
        {
            verifyExactContent(channel, readinessJournalContent(identity),
                    "附件存储探针journal正文不一致");
        }
        requireOrdinaryFileRealPath(journal);
    }

    /**
     * 写入带当前探针身份的 journal 正文，恢复时只有正文与文件名一致才允许删除关联条目。
     *
     * @param channel SeekableByteChannel，新建 journal 的独占写通道
     * @param identity ReadinessProbeIdentity，本轮探针身份
     * @return void，正文完整写入后返回
     * @throws IOException 通道无法继续写入
     */
    private void writeReadinessJournal(SeekableByteChannel channel,
            ReadinessProbeIdentity identity) throws IOException
    {
        writeExactContent(channel, readinessJournalContent(identity),
                "附件存储探针journal写入未取得进展");
    }

    /**
     * 生成 journal 固定 ASCII 正文，不包含物理路径、节点凭据或业务标识。
     *
     * @param identity ReadinessProbeIdentity，本轮探针身份
     * @return byte[]，协议版本和严格基础名组成的 ASCII 正文
     */
    private byte[] readinessJournalContent(ReadinessProbeIdentity identity)
    {
        return ("approvaplat-workflow-storage-readiness-journal:"
                + identity.baseName() + "\n").getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * 合并两次清理失败，保留首个异常并将后续异常附加，确保 source 与 target 都会尝试。
     *
     * @param first IOException，已有清理失败，可为 null
     * @param next IOException，本次清理失败，可为 null
     * @return IOException，合并后的首个失败；均成功时为 null
     */
    private IOException mergeCleanupFailure(IOException first, IOException next)
    {
        if (first == null)
        {
            return next;
        }
        if (next != null)
        {
            first.addSuppressed(next);
        }
        return first;
    }

    /**
     * 将固定探针正文完整写入当前新建通道，处理文件系统可能出现的短写。
     *
     * @param channel SeekableByteChannel，当前探针独占的新文件通道
     * @return void，正文完整写入后返回
     * @throws IOException 通道无法继续写入
     */
    private void writeProbeContent(SeekableByteChannel channel) throws IOException
    {
        writeExactContent(channel, READINESS_PROBE_CONTENT,
                "附件存储启动探针写入未取得进展");
    }

    /**
     * 将给定有界正文完整写入新建通道，统一处理文件系统短写。
     *
     * @param channel SeekableByteChannel，当前探针独占写通道
     * @param expected byte[]，待完整写入的有界正文
     * @param noProgressMessage String，通道无进展时的脱敏异常消息
     * @return void，全部正文写入后返回
     * @throws IOException 通道无法继续写入
     */
    private void writeExactContent(SeekableByteChannel channel, byte[] expected,
            String noProgressMessage) throws IOException
    {
        ByteBuffer content = ByteBuffer.wrap(expected);
        while (content.hasRemaining())
        {
            if (channel.write(content) <= 0)
            {
                throw new IOException(noProgressMessage);
            }
        }
    }

    /**
     * 从同一打开通道有界回读固定探针正文，拒绝长度或内容不一致。
     *
     * @param channel SeekableByteChannel，移动后正式目录内的探针文件通道
     * @return void，长度和正文均一致时返回
     * @throws IOException 文件大小、读取进度或正文不一致
     */
    private void verifyProbeContent(SeekableByteChannel channel) throws IOException
    {
        verifyExactContent(channel, READINESS_PROBE_CONTENT,
                "附件存储启动探针回读不一致");
    }

    /**
     * 从同一通道有界回读预期正文，拒绝长度、读取进度或内容不一致。
     *
     * @param channel SeekableByteChannel，位置在零的只读探针或 journal 通道
     * @param expected byte[]，预期完整正文
     * @param mismatchMessage String，长度或正文不一致时的脱敏消息
     * @return void，长度与正文完全一致时返回
     * @throws IOException 通道读取失败或内容不一致
     */
    private void verifyExactContent(SeekableByteChannel channel, byte[] expected,
            String mismatchMessage) throws IOException
    {
        if (channel.size() != expected.length)
        {
            throw new IOException(mismatchMessage);
        }
        ByteBuffer actual = ByteBuffer.allocate(expected.length);
        while (actual.hasRemaining())
        {
            if (channel.read(actual) <= 0)
            {
                throw new IOException("附件存储启动探针回读未取得进展");
            }
        }
        if (!Arrays.equals(actual.array(), expected))
        {
            throw new IOException(mismatchMessage);
        }
    }

    /**
     * 使用安全目录句柄分别清理 source 与 target，首个失败保留为主清理异常，其余附加。
     *
     * @param sourceDirectory SecureDirectoryStream&lt;Path&gt;，临时目录安全句柄
     * @param sourceName Path，随机 source 单段文件名
     * @param targetDirectory SecureDirectoryStream&lt;Path&gt;，正式目录安全句柄
     * @param targetName Path，随机 target 单段文件名
     * @param targetOwned boolean，本轮 move 已成功且 target 确由当前探针创建
     * @return IOException，两项均已不存在时为 null，否则为合并清理失败
     */
    private IOException cleanupSecureProbeFiles(
            SecureDirectoryStream<Path> sourceDirectory, Path sourceName,
            SecureDirectoryStream<Path> targetDirectory, Path targetName,
            boolean targetOwned)
    {
        IOException firstFailure = deleteSecureProbeFile(sourceDirectory, sourceName);
        IOException targetFailure = targetOwned
                ? deleteSecureProbeFile(targetDirectory, targetName) : null;
        if (firstFailure == null)
        {
            return targetFailure;
        }
        if (targetFailure != null)
        {
            firstFailure.addSuppressed(targetFailure);
        }
        return firstFailure;
    }

    /**
     * 删除一个安全目录句柄内的探针普通文件，不存在视为已达到补偿目标。
     *
     * @param directory SecureDirectoryStream&lt;Path&gt;，文件所属安全目录句柄
     * @param fileName Path，随机探针单段文件名
     * @return IOException，清理成功时为 null，失败时为脱敏异常
     */
    private IOException deleteSecureProbeFile(SecureDirectoryStream<Path> directory,
            Path fileName)
    {
        try
        {
            requireSecureOrdinaryFile(directory, fileName);
            directory.deleteFile(fileName);
            return null;
        }
        catch (NoSuchFileException ignored)
        {
            return null;
        }
        catch (IOException | RuntimeException failure)
        {
            return new IOException("附件存储启动探针安全清理失败", failure);
        }
    }

    /**
     * 在词法路径平台分别清理 source 与 target，即使第一项失败也继续尝试第二项。
     *
     * @param source Path，临时目录 source 路径
     * @param target Path，正式目录 target 路径
     * @param targetOwned boolean，本轮原子移动已成功且 target 归当前探针所有
     * @return IOException，两项均已不存在时为 null，否则为合并清理失败
     */
    private IOException cleanupCheckedProbeFiles(Path source, Path target,
            boolean targetOwned)
    {
        IOException firstFailure = deleteCheckedProbeFile(source);
        IOException targetFailure = targetOwned ? deleteCheckedProbeFile(target) : null;
        if (firstFailure == null)
        {
            return targetFailure;
        }
        if (targetFailure != null)
        {
            firstFailure.addSuppressed(targetFailure);
        }
        return firstFailure;
    }

    /**
     * 仅在父目录身份可信且条目仍为普通文件时删除词法路径探针。
     *
     * @param file Path，随机探针路径
     * @return IOException，清理成功时为 null，安全校验或删除失败时为脱敏异常
     */
    private IOException deleteCheckedProbeFile(Path file)
    {
        try
        {
            DirectoryChain chain = verifyTrustedDirectoryChain(file.getParent());
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS))
            {
                BasicFileAttributes attributes = Files.readAttributes(file,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                        || attributes.isOther())
                {
                    throw new IOException("附件存储启动探针清理目标不是普通文件");
                }
                Files.delete(file);
            }
            verifyDirectoryChainUnchanged(chain);
            return null;
        }
        catch (IOException | RuntimeException failure)
        {
            return new IOException("附件存储启动探针清理失败", failure);
        }
    }

    /**
     * 在 finally 中传播清理失败：存在主操作失败时只追加 suppressed，否则清理失败本身
     * 必须令生产门禁失败。
     *
     * @param operationFailure Throwable，写入、移动或回读阶段主异常，可为 null
     * @param cleanupFailure IOException，source/target 合并清理异常，可为 null
     * @return void，无返回值
     * @throws IOException 主操作成功但清理失败时抛出
     */
    private void propagateProbeCleanupFailure(Throwable operationFailure,
            IOException cleanupFailure) throws IOException
    {
        if (cleanupFailure == null)
        {
            return;
        }
        if (operationFailure != null)
        {
            operationFailure.addSuppressed(cleanupFailure);
            return;
        }
        throw cleanupFailure;
    }

    /**
     * 流式复制上传内容并计算实际大小与 SHA-256。
     *
     * @param file MultipartFile，待读取的上传文件
     * @param output OutputStream，当前上传独占且由调用方关闭的受控输出流
     * @return FileDigest，实际字节数和内容摘要
     * @throws IOException 上传流读取或临时文件写入失败
     */
    private FileDigest copyAndDigest(MultipartFile file, OutputStream output) throws IOException
    {
        MessageDigest messageDigest = sha256Digest();
        long fileSize = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = file.getInputStream())
        {
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1)
            {
                fileSize += bytesRead;
                if (fileSize > maxSize)
                {
                    throw invalidUpload("上传附件大小不能超过" + maxSize + "字节");
                }
                messageDigest.update(buffer, 0, bytesRead);
                output.write(buffer, 0, bytesRead);
            }
        }
        if (fileSize <= 0L)
        {
            throw invalidUpload("上传附件不能为空文件");
        }
        return new FileDigest(fileSize, HexFormat.of().formatHex(messageDigest.digest()));
    }

    /**
     * 生成日期分片和随机文件名组成的私有对象键。
     *
     * @param originalName String，已规范化的原始文件名
     * @return String，不含 profile 路径且不包含客户端文件名的相对对象键
     */
    private String createStorageKey(String originalName)
    {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String datePrefix = String.format(Locale.ROOT, "%04d/%02d/%02d",
                today.getYear(), today.getMonthValue(), today.getDayOfMonth());
        String extension = safeStorageExtension(originalName);
        return datePrefix + "/" + UUID.randomUUID().toString().replace("-", "")
                + extension;
    }

    /**
     * 计算当前 UTC 日期对应的真实上传目标目录，启动探针必须覆盖与业务写入相同的目录拓扑。
     *
     * @return Path，storageRoot/yyyy/MM/dd 规范绝对路径
     */
    private Path currentStorageDateDirectory()
    {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String datePrefix = String.format(Locale.ROOT, "%04d/%02d/%02d",
                today.getYear(), today.getMonthValue(), today.getDayOfMonth());
        Path directory = storageRoot.resolve(datePrefix).normalize();
        if (!directory.startsWith(storageRoot) || directory.equals(storageRoot))
        {
            throw new IllegalStateException("工作流附件启动探针日期目录异常");
        }
        return directory;
    }

    /**
     * 从已规范化文件名提取受控扩展名，仅用于 MIME 辅助探测和存储可读性。
     *
     * @param originalName String，已规范化的原始文件名
     * @return String，空串或带前导点的小写 ASCII 扩展名
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
     * 规范化客户端原始文件名并移除任何目录语义。
     *
     * @param originalFilename String，multipart 声明的原始文件名
     * @return String，不含路径、控制字符且长度受限的显示文件名
     */
    private String normalizeOriginalName(String originalFilename)
    {
        if (!StringUtils.hasText(originalFilename))
        {
            throw invalidUpload("上传附件文件名不能为空");
        }
        String normalizedPath = Normalizer.normalize(originalFilename, Normalizer.Form.NFC)
                .replace('\\', '/');
        String fileName = normalizedPath.substring(normalizedPath.lastIndexOf('/') + 1).trim();
        if (!StringUtils.hasText(fileName) || ".".equals(fileName) || "..".equals(fileName)
                || fileName.length() > MAX_ORIGINAL_NAME_LENGTH
                || fileName.codePoints().anyMatch(Character::isISOControl))
        {
            throw invalidUpload("上传附件文件名不合法");
        }
        return fileName;
    }

    /**
     * 根据文件内容优先、规范化文件名其次探测 MIME，拒绝客户端 Content-Type 覆盖。
     *
     * @param input InputStream，已通过安全目录边界打开的临时文件流
     * @param originalName String，已规范化的原始文件名
     * @return String，合法小写 MIME 或 application/octet-stream
     * @throws IOException 文件内容读取失败
     */
    private String detectContentType(InputStream input, String originalName) throws IOException
    {
        String detected;
        detected = URLConnection.guessContentTypeFromStream(
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
        return MIME_PATTERN.matcher(normalized).matches()
                ? normalized : "application/octet-stream";
    }

    /**
     * 通过安全父目录句柄重新打开临时文件并探测 MIME，禁止回退到词法路径二次打开。
     *
     * @param directory SecureDirectoryStream&lt;Path&gt;，临时文件所在可信目录句柄
     * @param fileName Path，目录内随机临时文件名
     * @param originalName String，规范化后的客户端展示文件名
     * @return String，服务端探测并规范化的 MIME
     * @throws IOException 安全通道打开或内容探测失败
     */
    private String detectContentType(SecureDirectoryStream<Path> directory,
            Path fileName, String originalName) throws IOException
    {
        requireSecureOrdinaryFile(directory, fileName);
        try (SeekableByteChannel channel = directory.newByteChannel(fileName,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                InputStream input = new BufferedInputStream(Channels.newInputStream(channel)))
        {
            return detectContentType(input, originalName);
        }
    }

    /**
     * 将受信数据库对象键解析到私有根目录，并阻止路径穿越或编码后的目录语义。
     *
     * @param storageKey String，数据库保存的相对对象键
     * @return Path，词法规范化后仍位于私有根目录的绝对路径
     */
    private Path resolveStorageKey(String storageKey)
    {
        if (!StringUtils.hasText(storageKey)
                || !STORAGE_KEY_PATTERN.matcher(storageKey).matches())
        {
            throw new ServiceException("工作流附件存储键异常", HttpStatus.ERROR);
        }
        Path resolved = storageRoot.resolve(storageKey).normalize();
        if (!resolved.startsWith(storageRoot) || resolved.equals(storageRoot))
        {
            throw new ServiceException("工作流附件存储路径异常", HttpStatus.ERROR);
        }
        return resolved;
    }

    /**
     * 从文件系统根开始逐级创建目录，并拒绝任一级已有链接、junction 或特殊文件。
     *
     * @param directory Path，待创建并核验的绝对目录
     * @return void，无返回值
     * @throws IOException 目录创建或属性读取失败
     */
    private void createDirectoryTreeWithoutLinks(Path directory) throws IOException
    {
        Path normalized = directory.toAbsolutePath().normalize();
        Path current = normalized.getRoot();
        if (current == null)
        {
            throw new IOException("附件目录缺少文件系统根");
        }
        readOrdinaryDirectoryIdentity(current);
        for (Path segment : normalized)
        {
            Path child = current.resolve(segment);
            if (Files.exists(child, LinkOption.NOFOLLOW_LINKS))
            {
                readOrdinaryDirectoryIdentity(child);
            }
            else
            {
                Files.createDirectory(child);
                readOrdinaryDirectoryIdentity(child);
            }
            current = child;
        }
    }

    /**
     * 在固定私有根下逐级创建日期或临时目录，并在每一步前后复核父目录身份。
     *
     * @param directory Path，必须位于私有存储根内的目录
     * @return void，无返回值
     * @throws IOException 目录创建、权限设置或身份复核失败
     */
    private void ensurePrivateDirectory(Path directory) throws IOException
    {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!normalized.startsWith(storageRoot))
        {
            throw unsafeStorageDirectory("待创建目录逃出私有存储根");
        }
        verifyStorageRootIdentity();
        Path current = storageRoot;
        for (Path segment : storageRoot.relativize(normalized))
        {
            DirectoryIdentity parentBefore = readOrdinaryDirectoryIdentity(current);
            Path child = current.resolve(segment);
            if (Files.exists(child, LinkOption.NOFOLLOW_LINKS))
            {
                readOrdinaryDirectoryIdentity(child);
            }
            else
            {
                Files.createDirectory(child);
                applyDirectoryPermissions(child);
                readOrdinaryDirectoryIdentity(child);
            }
            if (!parentBefore.sameDirectory(readOrdinaryDirectoryIdentity(current)))
            {
                throw unsafeStorageDirectory("创建子目录期间父目录身份发生变化");
            }
            current = child;
        }
        verifyTrustedDirectoryChain(normalized);
    }

    /**
     * 读取拒绝链接和特殊文件的普通目录身份，作为操作前后稳定性凭据。
     *
     * @param directory Path，待核验的目录路径
     * @return DirectoryIdentity，真实路径、fileKey 或创建时间组成的目录身份
     * @throws IOException 目录不存在或属性读取失败
     */
    private DirectoryIdentity readOrdinaryDirectoryIdentity(Path directory) throws IOException
    {
        BasicFileAttributes attributes = Files.readAttributes(directory,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther())
        {
            throw unsafeStorageDirectory("附件目录包含链接、junction或特殊文件");
        }
        Path normalized = directory.toAbsolutePath().normalize();
        Path realPath = directory.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!realPath.equals(normalized))
        {
            throw unsafeStorageDirectory("附件目录真实路径与配置路径不一致");
        }
        FileTime creationTime = attributes.creationTime();
        if (creationTime == null)
        {
            throw unsafeStorageDirectory("文件系统无法提供目录身份属性");
        }
        return new DirectoryIdentity(normalized, realPath,
                attributes.fileKey(), creationTime);
    }

    /**
     * 核对私有根仍是构造阶段固定的同一普通目录。
     *
     * @return void，根目录被删除、替换或改为链接时立即失败
     * @throws IOException 根目录属性读取失败
     */
    private void verifyStorageRootIdentity() throws IOException
    {
        DirectoryIdentity current = readOrdinaryDirectoryIdentity(storageRoot);
        if (!storageRootIdentity.sameDirectory(current)
                || !realStorageRoot.equals(current.realPath()))
        {
            throw unsafeStorageDirectory("私有存储根目录身份发生变化");
        }
    }

    /**
     * 逐级核验私有根到目标目录均为普通目录并记录身份快照。
     *
     * @param directory Path，最终文件的父目录或待使用私有目录
     * @return DirectoryChain，用于操作完成后的身份复核
     * @throws IOException 任一级目录不存在或属性读取失败
     */
    private DirectoryChain verifyTrustedDirectoryChain(Path directory) throws IOException
    {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!normalized.startsWith(storageRoot))
        {
            throw unsafeStorageDirectory("附件父目录逃出私有存储根");
        }
        verifyStorageRootIdentity();
        List<DirectoryIdentity> identities = new ArrayList<>();
        identities.add(readOrdinaryDirectoryIdentity(storageRoot));
        Path current = storageRoot;
        for (Path segment : storageRoot.relativize(normalized))
        {
            current = current.resolve(segment);
            DirectoryIdentity identity = readOrdinaryDirectoryIdentity(current);
            if (!identity.realPath().startsWith(realStorageRoot))
            {
                throw unsafeStorageDirectory("附件父目录真实路径逃出私有存储根");
            }
            identities.add(identity);
        }
        return new DirectoryChain(List.copyOf(identities));
    }

    /**
     * 对照操作前快照复核每一级目录身份，发现替换时拒绝继续使用操作结果。
     *
     * @param chain DirectoryChain，操作前记录的目录身份链
     * @return void，任一级目录身份变化时立即失败
     * @throws IOException 目录属性读取失败
     */
    private void verifyDirectoryChainUnchanged(DirectoryChain chain) throws IOException
    {
        for (DirectoryIdentity expected : chain.identities())
        {
            if (!expected.sameDirectory(readOrdinaryDirectoryIdentity(expected.path())))
            {
                throw unsafeStorageDirectory("附件操作期间父目录身份发生变化");
            }
        }
        verifyStorageRootIdentity();
    }

    /**
     * 核验最终文件为私有真实根内的普通文件，拒绝最终链接及父目录别名。
     *
     * @param file Path，待打开或删除的最终文件词法路径
     * @return Path，不经过链接且位于固定私有根内的真实文件路径
     * @throws IOException 文件不存在或属性读取失败
     */
    private Path requireOrdinaryFileRealPath(Path file) throws IOException
    {
        BasicFileAttributes attributes = Files.readAttributes(file,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther())
        {
            throw new ServiceException("工作流附件文件完整性校验失败", HttpStatus.ERROR);
        }
        Path realFile = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!realFile.startsWith(realStorageRoot)
                || !realFile.getParent().equals(
                        file.getParent().toAbsolutePath().normalize()))
        {
            throw new ServiceException("工作流附件文件完整性校验失败", HttpStatus.ERROR);
        }
        return realFile;
    }

    /**
     * 通过安全目录句柄读取最终条目属性并拒绝链接、junction 或特殊文件。
     *
     * @param directory SecureDirectoryStream&lt;Path&gt;，最终文件所在目录句柄
     * @param fileName Path，目录内单段文件名
     * @return void，条目不是普通文件时抛出完整性异常
     * @throws IOException 条目不存在或属性读取失败
     */
    private void requireSecureOrdinaryFile(SecureDirectoryStream<Path> directory,
            Path fileName) throws IOException
    {
        BasicFileAttributes attributes = directory.getFileAttributeView(fileName,
                java.nio.file.attribute.BasicFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS).readAttributes();
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther())
        {
            throw new ServiceException("工作流附件文件完整性校验失败", HttpStatus.ERROR);
        }
    }

    /**
     * 从私有根安全目录句柄逐级打开目标目录，所有目录段均使用 NOFOLLOW_LINKS。
     *
     * @param secureRoot SecureDirectoryStream&lt;Path&gt;，私有根目录句柄
     * @param directory Path，私有根内目标目录词法路径
     * @return SecureParentHandle，持有目标目录及全部中间目录句柄
     * @throws IOException 目录段不存在、不是普通目录或链接拒绝失败
     */
    private SecureParentHandle openSecureDirectory(SecureDirectoryStream<Path> secureRoot,
            Path directory) throws IOException
    {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!normalized.startsWith(storageRoot))
        {
            throw unsafeStorageDirectory("安全目录句柄目标逃出私有存储根");
        }
        SecureDirectoryStream<Path> current = secureRoot;
        List<SecureDirectoryStream<Path>> opened = new ArrayList<>();
        try
        {
            for (Path segment : storageRoot.relativize(normalized))
            {
                BasicFileAttributes attributes = current.getFileAttributeView(segment,
                        java.nio.file.attribute.BasicFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS).readAttributes();
                if (!attributes.isDirectory() || attributes.isSymbolicLink()
                        || attributes.isOther())
                {
                    throw unsafeStorageDirectory("安全目录句柄遇到链接或特殊目录");
                }
                SecureDirectoryStream<Path> child = current.newDirectoryStream(
                        segment, LinkOption.NOFOLLOW_LINKS);
                opened.add(child);
                current = child;
            }
            return new SecureParentHandle(current, opened);
        }
        catch (RuntimeException | IOException failure)
        {
            closeSecureDirectoriesQuietly(opened, failure);
            throw failure;
        }
    }

    /**
     * 在安全目录句柄内应用仅所有者读写权限；不支持 POSIX 时保留平台 ACL。
     *
     * @param directory SecureDirectoryStream&lt;Path&gt;，文件所在可信目录句柄
     * @param fileName Path，目录内文件名
     * @return void，无返回值
     * @throws IOException POSIX 权限写入失败
     */
    private void applyFilePermissions(SecureDirectoryStream<Path> directory,
            Path fileName) throws IOException
    {
        try
        {
            PosixFileAttributeView view = directory.getFileAttributeView(fileName,
                    PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view != null)
            {
                view.setPermissions(PRIVATE_FILE_PERMISSIONS);
            }
        }
        catch (UnsupportedOperationException ignored)
        {
            // 非 POSIX 平台继续依赖运行账号 ACL，但目录类型和真实边界仍必须通过校验。
        }
    }

    /**
     * 在支持 POSIX 权限时将私有目录限制为当前系统用户读写执行。
     *
     * @param directory Path，已经创建并通过普通目录校验的私有目录
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
            // 非 POSIX 平台由运行账号 ACL 控制访问。
        }
    }

    /**
     * 在支持 POSIX 权限时将附件文件限制为当前系统用户读写。
     *
     * @param file Path，已经创建的临时或正式私有文件
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
            // Windows ACL 由运行账号和部署目录权限控制，不伪造 POSIX 能力。
        }
    }

    /**
     * 将临时文件移动为最终对象且绝不覆盖已有文件。
     *
     * @param source Path，当前上传的临时文件
     * @param destination Path，随机生成的最终文件路径
     * @return void，无返回值
     * @throws IOException 原子或普通移动失败
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
        catch (AtomicMoveNotSupportedException exception)
        {
            Files.move(source, destination);
        }
    }

    /**
     * 创建 SHA-256 摘要器；JDK 缺失必需算法时按服务端配置错误处理。
     *
     * @return MessageDigest，新的 SHA-256 摘要器
     */
    private MessageDigest sha256Digest()
    {
        try
        {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException exception)
        {
            throw storageFailure("运行环境不支持SHA-256", exception);
        }
    }

    /**
     * 使用已打开安全目录句柄补偿删除未完成上传文件，并保留原异常。
     *
     * @param directory SecureDirectoryStream&lt;Path&gt;，临时或最终文件所在目录句柄
     * @param fileName Path，目录内待删除文件名
     * @param originalFailure Throwable，必须继续抛出的原始失败
     * @return void，无返回值
     */
    private void deleteSecureFileQuietly(SecureDirectoryStream<Path> directory,
            Path fileName, Throwable originalFailure)
    {
        try
        {
            directory.deleteFile(fileName);
        }
        catch (NoSuchFileException ignored)
        {
            // 已经不存在即达到补偿目标。
        }
        catch (IOException cleanupFailure)
        {
            originalFailure.addSuppressed(cleanupFailure);
        }
    }

    /**
     * 在非安全目录流平台仅当父目录身份仍可信时补偿删除上传文件。
     *
     * @param file Path，临时或最终文件词法路径
     * @param originalFailure Throwable，必须继续抛出的原始失败
     * @return void，无返回值
     */
    private void deleteCheckedFileQuietly(Path file, Throwable originalFailure)
    {
        try
        {
            DirectoryChain chain = verifyTrustedDirectoryChain(file.getParent());
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS))
            {
                BasicFileAttributes attributes = Files.readAttributes(file,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                        || attributes.isOther())
                {
                    throw unsafeStorageDirectory("补偿删除目标不是普通文件");
                }
                Files.delete(file);
            }
            verifyDirectoryChainUnchanged(chain);
        }
        catch (RuntimeException | IOException cleanupFailure)
        {
            originalFailure.addSuppressed(cleanupFailure);
        }
    }

    /**
     * 逆序关闭安全目录句柄，并把关闭失败附加到原异常。
     *
     * @param directories List&lt;SecureDirectoryStream&lt;Path&gt;&gt;，已打开的子目录句柄
     * @param originalFailure Throwable，必须继续抛出的原始失败
     * @return void，无返回值
     */
    private void closeSecureDirectoriesQuietly(
            List<SecureDirectoryStream<Path>> directories, Throwable originalFailure)
    {
        List<SecureDirectoryStream<Path>> reversed = new ArrayList<>(directories);
        Collections.reverse(reversed);
        for (SecureDirectoryStream<Path> directory : reversed)
        {
            try
            {
                directory.close();
            }
            catch (IOException closeFailure)
            {
                originalFailure.addSuppressed(closeFailure);
            }
        }
    }

    /**
     * 创建不泄露物理路径的私有目录 fail-closed 异常。
     *
     * @param reason String，仅用于内部异常原因且不包含实际路径
     * @return ServiceException，稳定 HTTP 500 存储目录安全异常
     */
    private ServiceException unsafeStorageDirectory(String reason)
    {
        ServiceException failure = new ServiceException(
                "工作流附件存储目录安全校验失败", HttpStatus.ERROR);
        failure.initCause(new IOException(reason));
        return failure;
    }

    /**
     * 将若依 profile 文本转换为非空绝对路径。
     *
     * @param profile String，配置文件中的 profile 文本
     * @return Path，规范化前的 profile 路径
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
     * 校验 Spring 注入的附件配置对象不为空。
     *
     * @param properties WorkflowAttachmentProperties，待校验配置
     * @return WorkflowAttachmentProperties，原配置对象
     */
    private static WorkflowAttachmentProperties requireProperties(
            WorkflowAttachmentProperties properties)
    {
        if (properties == null)
        {
            throw new IllegalArgumentException("工作流附件配置不能为空");
        }
        return properties;
    }

    /**
     * 创建不回显客户端文件内容的上传参数异常。
     *
     * @param message String，稳定业务提示
     * @return ServiceException，HTTP 400 业务异常
     */
    private ServiceException invalidUpload(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 创建保留内部原因但不暴露磁盘路径的存储异常。
     *
     * @param message String，稳定业务提示
     * @param cause Throwable，内部文件系统或运行环境异常
     * @return ServiceException，HTTP 500 业务异常
     */
    private ServiceException storageFailure(String message, Throwable cause)
    {
        ServiceException failure = new ServiceException(message, HttpStatus.ERROR);
        failure.initCause(cause);
        return failure;
    }

    /**
     * 一次可恢复存储探针的严格身份，三个文件共享同一基础名且日期只用于定位正式目录。
     *
     * @param createdAtMillis long，名称中冻结的探针创建时间毫秒
     * @param date LocalDate，target 所在 UTC 正式日期目录
     * @param baseName String，不含扩展名的严格协议基础名
     */
    private record ReadinessProbeIdentity(long createdAtMillis, LocalDate date,
            String baseName)
    {
        /**
         * 获取 journal 单段文件名。
         *
         * @return Path，严格基础名加 .journal
         */
        private Path journalName()
        {
            return Path.of(baseName + ".journal");
        }

        /**
         * 获取临时目录 source 单段文件名。
         *
         * @return Path，严格基础名加 .source
         */
        private Path sourceName()
        {
            return Path.of(baseName + ".source");
        }

        /**
         * 获取正式日期目录 target 单段文件名。
         *
         * @return Path，严格基础名加 .target
         */
        private Path targetName()
        {
            return Path.of(baseName + ".target");
        }

        /**
         * 将冻结 UTC 日期解析为私有根下 yyyy/MM/dd 目录，并再次校验词法边界。
         *
         * @param storageRoot Path，固定私有附件根
         * @return Path，关联 target 的规范正式目录
         */
        private Path destinationDirectory(Path storageRoot)
        {
            String datePrefix = String.format(Locale.ROOT, "%04d/%02d/%02d",
                    date.getYear(), date.getMonthValue(), date.getDayOfMonth());
            Path destination = storageRoot.resolve(datePrefix).normalize();
            if (!destination.startsWith(storageRoot) || destination.equals(storageRoot))
            {
                throw new IllegalStateException("附件存储探针恢复日期目录异常");
            }
            return destination;
        }
    }

    /**
     * 单个普通目录在一次操作前后的稳定身份。
     *
     * @param path Path，拒绝链接后的词法绝对路径
     * @param realPath Path，NOFOLLOW_LINKS 得到的真实绝对路径
     * @param fileKey Object，文件系统可提供时使用的稳定目录标识
     * @param creationTime FileTime，不提供 fileKey 的平台用于替换检测的创建时间
     */
    private record DirectoryIdentity(Path path, Path realPath,
            Object fileKey, FileTime creationTime)
    {
        /**
         * 比较两次属性读取是否仍指向同一普通目录。
         *
         * @param other DirectoryIdentity，操作后重新读取的目录身份
         * @return boolean，路径、真实路径及平台稳定身份均一致时返回 true
         */
        private boolean sameDirectory(DirectoryIdentity other)
        {
            if (other == null || !path.equals(other.path)
                    || !realPath.equals(other.realPath))
            {
                return false;
            }
            if (fileKey != null || other.fileKey != null)
            {
                return fileKey != null && other.fileKey != null
                        && Objects.equals(fileKey, other.fileKey);
            }
            return creationTime.equals(other.creationTime);
        }
    }

    /**
     * 私有根到目标父目录的操作前身份快照。
     *
     * @param identities List&lt;DirectoryIdentity&gt;，从私有根到目标目录的有序身份
     */
    private record DirectoryChain(List<DirectoryIdentity> identities)
    {
    }

    /**
     * 持有 SecureDirectoryStream 逐级打开的全部子目录句柄，关闭时按逆序释放。
     */
    private final class SecureParentHandle implements AutoCloseable
    {
        /** 最终目标目录句柄。 */
        private final SecureDirectoryStream<Path> directory;

        /** 不包含外部根句柄、需要由当前对象释放的中间及最终目录句柄。 */
        private final List<SecureDirectoryStream<Path>> openedDirectories;

        /**
         * 创建安全父目录句柄容器。
         *
         * @param directory SecureDirectoryStream&lt;Path&gt;，最终目标目录句柄
         * @param openedDirectories List&lt;SecureDirectoryStream&lt;Path&gt;&gt;，待逆序关闭的子句柄
         * @return 无返回值，构造后由 try-with-resources 管理
         */
        private SecureParentHandle(SecureDirectoryStream<Path> directory,
                List<SecureDirectoryStream<Path>> openedDirectories)
        {
            this.directory = directory;
            this.openedDirectories = List.copyOf(openedDirectories);
        }

        /**
         * 获取最终目标目录的安全句柄。
         *
         * @return SecureDirectoryStream&lt;Path&gt;，用于相对打开、移动或删除文件
         */
        private SecureDirectoryStream<Path> directory()
        {
            return directory;
        }

        /**
         * 逆序关闭当前对象拥有的全部子目录句柄，保留第一个关闭异常的完整上下文。
         *
         * @return void，无返回值
         * @throws IOException 任一目录句柄关闭失败
         */
        @Override
        public void close() throws IOException
        {
            IOException firstFailure = null;
            List<SecureDirectoryStream<Path>> reversed = new ArrayList<>(openedDirectories);
            Collections.reverse(reversed);
            for (SecureDirectoryStream<Path> opened : reversed)
            {
                try
                {
                    opened.close();
                }
                catch (IOException closeFailure)
                {
                    if (firstFailure == null)
                    {
                        firstFailure = closeFailure;
                    }
                    else
                    {
                        firstFailure.addSuppressed(closeFailure);
                    }
                }
            }
            if (firstFailure != null)
            {
                throw firstFailure;
            }
        }
    }

    /**
     * 单次上传流式统计结果。
     *
     * @param fileSize long，实际写入字节数
     * @param sha256 String，SHA-256 小写摘要
     */
    private record FileDigest(long fileSize, String sha256)
    {
    }
}
