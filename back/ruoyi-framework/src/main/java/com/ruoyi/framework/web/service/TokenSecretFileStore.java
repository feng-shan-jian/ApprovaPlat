package com.ruoyi.framework.web.service;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import io.jsonwebtoken.io.Encoders;

/**
 * 单节点部署令牌密钥的持久化存储。
 *
 * 该类只在显式启用持久化自动生成时使用：密钥首次生成后写入受限目录，
 * 后续进程在文件锁保护下复用同一值，避免每次重启导致全部登录态失效。
 */
final class TokenSecretFileStore
{
    /** HS512 所需的原始随机密钥长度。 */
    private static final int SECRET_BYTES = 64;

    /** 同一 JVM 内按密钥路径串行化，避免 FileChannel 对重叠锁直接抛出异常。 */
    private static final ConcurrentMap<Path, Object> PROCESS_LOCKS =
            new ConcurrentHashMap<>();

    /** 负责生成高熵随机密钥，不能使用可预测的普通随机数。 */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 读取已有本地密钥；目标不存在时在进程锁保护下生成并持久化一个新密钥。
     *
     * @param configuredPath String，本地密钥文件的绝对或相对路径
     * @return String，Base64 编码的原始 64 字节密钥
     */
    String loadOrCreate(String configuredPath)
    {
        Path secretPath = normalizePath(configuredPath);
        Path parent = secretPath.getParent();
        if (parent == null)
        {
            throw new IllegalStateException("本地令牌密钥路径必须包含父目录");
        }
        try
        {
            Files.createDirectories(parent);
            securePath(parent, true);
            Path lockPath = parent.resolve(secretPath.getFileName() + ".lock");
            // processLock 只协调当前 JVM；文件锁继续负责多个后端进程之间的互斥。
            Object processLock = PROCESS_LOCKS.computeIfAbsent(lockPath,
                    ignored -> new Object());
            synchronized (processLock)
            {
                try (FileChannel lockChannel = FileChannel.open(lockPath,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                        FileLock ignored = lockChannel.lock())
                {
                    securePath(lockPath, false);
                    if (Files.exists(secretPath, LinkOption.NOFOLLOW_LINKS))
                    {
                        return readExisting(secretPath);
                    }
                    return createNew(secretPath);
                }
            }
        }
        catch (IOException exception)
        {
            throw new IllegalStateException(
                    "本地令牌密钥无法读取或创建，请检查路径权限：" + secretPath, exception);
        }
    }

    /**
     * 规范化并校验本地密钥路径，拒绝空路径和已存在的符号链接。
     *
     * @param configuredPath String，配置文件提供的路径
     * @return Path，规范化后的绝对路径
     */
    private Path normalizePath(String configuredPath)
    {
        if (configuredPath == null || configuredPath.isBlank())
        {
            throw new IllegalStateException("本地令牌密钥路径不能为空");
        }
        Path path = Path.of(configuredPath).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path))
        {
            throw new IllegalStateException("本地令牌密钥路径不能是符号链接：" + path);
        }
        return path;
    }

    /**
     * 读取已存在的密钥文件，保留内容校验交给 TokenService 的 Base64/长度门禁。
     *
     * @param secretPath Path，本地密钥文件路径
     * @return String，去除首尾空白后的密钥文本
     * @throws IOException 密钥文件无法读取时抛出
     */
    private String readExisting(Path secretPath) throws IOException
    {
        if (!Files.isRegularFile(secretPath, LinkOption.NOFOLLOW_LINKS))
        {
            throw new IllegalStateException("本地令牌密钥必须是普通文件：" + secretPath);
        }
        securePath(secretPath, false);
        String value = Files.readString(secretPath, StandardCharsets.UTF_8).trim();
        if (value.isEmpty())
        {
            throw new IllegalStateException("本地令牌密钥文件为空：" + secretPath);
        }
        return value;
    }

    /**
     * 生成临时文件并原子移动到目标路径，避免进程中断留下半个密钥文件。
     *
     * @param secretPath Path，本地密钥目标路径
     * @return String，新生成的 Base64 密钥文本
     * @throws IOException 文件创建或原子移动失败时抛出
     */
    private String createNew(Path secretPath) throws IOException
    {
        byte[] bytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(bytes);
        String generated = Encoders.BASE64.encode(bytes);
        Path temporaryPath = secretPath.resolveSibling(
                "." + secretPath.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try
        {
            Files.writeString(temporaryPath, generated + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            securePath(temporaryPath, false);
            try
            {
                Files.move(temporaryPath, secretPath, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException exception)
            {
                Files.move(temporaryPath, secretPath);
            }
            securePath(secretPath, false);
            return generated;
        }
        catch (FileAlreadyExistsException exception)
        {
            // 外部进程抢先创建时只读取已提交的完整文件，不覆盖对方密钥。
            return readExisting(secretPath);
        }
        finally
        {
            Files.deleteIfExists(temporaryPath);
        }
    }

    /**
     * 将目录或文件限制为当前运行账户可读写，兼容 POSIX 与 Windows ACL。
     *
     * @param path Path，需要收紧权限的路径
     * @param directory boolean，是否按目录权限处理
     * @throws IOException 权限无法设置时抛出
     */
    private void securePath(Path path, boolean directory) throws IOException
    {
        PosixFileAttributeView posix = Files.getFileAttributeView(path,
                PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null)
        {
            Set<PosixFilePermission> permissions = directory
                    ? Set.of(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE)
                    : Set.of(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, permissions);
            return;
        }

        AclFileAttributeView acl = Files.getFileAttributeView(path,
                AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null)
        {
            FileOwnerAttributeView ownerView = acl;
            // Windows 打开/替换文件还依赖命名属性、删除和 ACL 维护权限；仅授予所有者完整集合。
            Set<AclEntryPermission> permissions =
                    EnumSet.allOf(AclEntryPermission.class);
            acl.setAcl(List.of(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(ownerView.getOwner())
                    .setPermissions(permissions)
                    .build()));
        }
    }
}
