package com.ruoyi.system.service.integration;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.integration.OssConfigRequest;

/**
 * S3 兼容 OSS 配置、对象生命周期与签名传输服务。
 */
@Service
public class SysOssService
{
    private static final long MAX_OBJECT_SIZE = 50L * 1024L * 1024L;
    private static final int MAX_ORIGINAL_NAME_LENGTH = 255;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern BUCKET_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$");
    private static final Pattern REGION_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");
    private static final Pattern PREFIX_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9/_-]{0,127}$");
    private static final Pattern CONTENT_TYPE_PATTERN = Pattern.compile(
            "^[a-z0-9][a-z0-9!#$&^_.+\\-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+\\-]{0,126}$");
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbcTemplate;
    private final HttpClient httpClient;
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建对象存储服务并固定禁止 HTTP 跳转，防止签名凭据跨主机发送。
     *
     * @param jdbcTemplate JdbcTemplate，正式配置和对象台账数据源
     * @param transactionManager PlatformTransactionManager，对象删除状态转换事务管理器
     * @return void，构造后由 Spring 管理
     */
    @Autowired
    public SysOssService(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager)
    {
        this(jdbcTemplate, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build(),
                new TransactionTemplate(transactionManager));
    }

    /**
     * 使用显式 HTTP 客户端创建服务，供受控测试替换网络边界。
     *
     * @param jdbcTemplate JdbcTemplate，正式配置和对象台账数据源
     * @param httpClient HttpClient，S3 传输客户端
     * @param transactionTemplate TransactionTemplate，删除状态转换事务模板
     * @return void，参数不完整时拒绝创建
     */
    SysOssService(JdbcTemplate jdbcTemplate, HttpClient httpClient,
            TransactionTemplate transactionTemplate)
    {
        if (jdbcTemplate == null || httpClient == null || transactionTemplate == null)
        {
            throw new IllegalArgumentException("OSS 服务依赖不能为空");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.httpClient = httpClient;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 查询 OSS 配置列表并永久排除 SecretKey 明文。
     *
     * @return List&lt;Map&lt;String,Object&gt;&gt;，脱敏配置列表
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listConfigs()
    {
        return jdbcTemplate.queryForList("select config_id as configId,config_name as configName," +
                "endpoint,region,bucket_name as bucketName,access_key as accessKey," +
                "case when secret_key<>'' then 1 else 0 end as secretConfigured,domain,prefix," +
                "path_style as pathStyle,access_policy as accessPolicy,status,create_by as createBy," +
                "create_time as createTime,update_by as updateBy,update_time as updateTime,remark " +
                "from sys_oss_config order by config_id desc");
    }

    /**
     * 新增停用状态 OSS 配置，启用前必须执行真实连通性测试。
     *
     * @param request OssConfigRequest，配置请求
     * @param actor String，当前管理员账号
     * @return long，新配置主键
     */
    @Transactional(rollbackFor = Exception.class)
    public long createConfig(OssConfigRequest request, String actor)
    {
        ValidatedOssConfig config = validateConfig(request, false);
        KeyHolder holder = new GeneratedKeyHolder();
        try
        {
            jdbcTemplate.update(connection ->
            {
                var statement = connection.prepareStatement(
                        "insert into sys_oss_config (config_name,endpoint,region,bucket_name,access_key," +
                        "secret_key,domain,prefix,path_style,access_policy,status,create_by,create_time,remark) " +
                        "values (?,?,?,?,?,?,?,?,?,?,'1',?,current_timestamp(3),?)",
                        java.sql.Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, config.configName());
                statement.setString(2, config.endpoint().toString());
                statement.setString(3, config.region());
                statement.setString(4, config.bucketName());
                statement.setString(5, config.accessKey());
                statement.setString(6, config.secretKey());
                statement.setString(7, config.domain());
                statement.setString(8, config.prefix());
                statement.setString(9, config.pathStyle());
                statement.setString(10, config.accessPolicy());
                statement.setString(11, actor(actor));
                statement.setString(12, config.remark());
                return statement;
            }, holder);
        }
        catch (DuplicateKeyException exception)
        {
            throw new ServiceException("OSS 配置名称已存在", HttpStatus.CONFLICT);
        }
        if (holder.getKey() == null)
        {
            throw new ServiceException("OSS 配置保存失败", HttpStatus.ERROR);
        }
        return holder.getKey().longValue();
    }

    /**
     * 修改 OSS 配置；空 SecretKey 在 SQL 层保留原值。
     *
     * @param request OssConfigRequest，包含正式主键的配置请求
     * @param actor String，当前管理员账号
     * @return void，配置不存在时抛出 404
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateConfig(OssConfigRequest request, String actor)
    {
        ValidatedOssConfig config = validateConfig(request, true);
        int updated;
        try
        {
            updated = jdbcTemplate.update("update sys_oss_config set config_name=?,endpoint=?,region=?," +
                    "bucket_name=?,access_key=?,secret_key=case when ?='' then secret_key else ? end," +
                    "domain=?,prefix=?,path_style=?,access_policy=?,update_by=?," +
                    "update_time=current_timestamp(3),remark=? where config_id=?",
                    config.configName(), config.endpoint().toString(), config.region(), config.bucketName(),
                    config.accessKey(), config.secretKey(), config.secretKey(), config.domain(), config.prefix(),
                    config.pathStyle(), config.accessPolicy(), actor(actor), config.remark(), request.configId());
        }
        catch (DuplicateKeyException exception)
        {
            throw new ServiceException("OSS 配置名称已存在", HttpStatus.CONFLICT);
        }
        if (updated != 1)
        {
            throw new ServiceException("OSS 配置不存在", HttpStatus.NOT_FOUND);
        }
    }

    /**
     * 对指定配置执行真实 S3 HeadBucket 验证，不创建桶、不修改远端权限。
     *
     * @param configId long，待测试配置主键
     * @return Map&lt;String,Object&gt;，真实 HTTP 状态和成功标志
     */
    @Transactional(readOnly = true)
    public Map<String, Object> testConfig(long configId)
    {
        ActiveOssConfig config = configById(configId);
        try
        {
            S3Response response = execute(config, "HEAD", null, null, null, 0L);
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            if (response.body() != null)
            {
                response.body().close();
            }
            return Map.of("success", success, "statusCode", response.statusCode(),
                    "message", success ? "存储桶连接正常" : "存储桶拒绝访问");
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new ServiceException("OSS 连通性测试被中断", HttpStatus.ERROR);
        }
        catch (Exception exception)
        {
            throw new ServiceException("OSS 连通性测试失败", HttpStatus.CONFLICT);
        }
    }

    /**
     * 原子启用指定 OSS 配置并停用其他配置。
     *
     * @param configId long，待启用配置主键
     * @param actor String，当前管理员账号
     * @return void，配置不存在时拒绝状态变化
     */
    @Transactional(rollbackFor = Exception.class)
    public void activate(long configId, String actor)
    {
        Integer exists = jdbcTemplate.queryForObject(
                "select count(*) from sys_oss_config where config_id=? for update", Integer.class, configId);
        if (exists == null || exists != 1)
        {
            throw new ServiceException("OSS 配置不存在", HttpStatus.NOT_FOUND);
        }
        jdbcTemplate.update("update sys_oss_config set status='1',update_by=?," +
                "update_time=current_timestamp(3) where status='0'", actor(actor));
        if (jdbcTemplate.update("update sys_oss_config set status='0',update_by=?," +
                "update_time=current_timestamp(3) where config_id=?", actor(actor), configId) != 1)
        {
            throw new ServiceException("OSS 配置启用失败", HttpStatus.CONFLICT);
        }
    }

    /**
     * 删除未启用且没有对象台账引用的 OSS 配置。
     *
     * @param configId long，配置主键
     * @return void，启用中或有对象引用时拒绝删除
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteConfig(long configId)
    {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select status from sys_oss_config where config_id=? for update", configId);
        if (rows.isEmpty())
        {
            throw new ServiceException("OSS 配置不存在", HttpStatus.NOT_FOUND);
        }
        if ("0".equals(String.valueOf(rows.get(0).get("status"))))
        {
            throw new ServiceException("启用中的 OSS 配置不能删除", HttpStatus.CONFLICT);
        }
        Integer objects = jdbcTemplate.queryForObject(
                "select count(*) from sys_oss_object where config_id=?", Integer.class, configId);
        if (objects != null && objects > 0)
        {
            throw new ServiceException("已有对象台账的 OSS 配置不能删除", HttpStatus.CONFLICT);
        }
        jdbcTemplate.update("delete from sys_oss_config where config_id=?", configId);
    }

    /**
     * 查询对象台账，私有对象不返回可绕过授权的远端 URL。
     *
     * @return List&lt;Map&lt;String,Object&gt;&gt;，最近 1000 条对象元数据
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listObjects()
    {
        return jdbcTemplate.queryForList("select object_id as objectId,config_id as configId," +
                "original_name as originalName,file_suffix as fileSuffix,content_type as contentType," +
                "file_size as fileSize,sha256,access_policy as accessPolicy,public_url as publicUrl," +
                "status,last_error as lastError,create_by as createBy,create_time as createTime," +
                "delete_time as deleteTime from sys_oss_object order by object_id desc limit 1000");
    }

    /**
     * 将 MultipartFile 流式暂存、计算摘要、上传远端并持久化对象台账。
     *
     * @param file MultipartFile，客户端文件
     * @param actor String，当前登录账号
     * @return StoredOssObject，正式对象元数据
     */
    public StoredOssObject upload(MultipartFile file, String actor)
    {
        if (file == null || file.isEmpty())
        {
            throw new ServiceException("上传文件不能为空", HttpStatus.BAD_REQUEST);
        }
        if (file.getSize() < 0 || file.getSize() > MAX_OBJECT_SIZE)
        {
            throw new ServiceException("上传文件不能超过 50 MiB", HttpStatus.BAD_REQUEST);
        }
        String originalName = originalName(file.getOriginalFilename());
        String contentType = contentType(file.getContentType());
        ActiveOssConfig config = activeConfig();
        String objectKey = objectKey(config.prefix(), originalName);
        Path staged = null;
        boolean remoteWritten = false;
        try
        {
            staged = Files.createTempFile("approvaplat-oss-", ".upload");
            try (InputStream input = file.getInputStream())
            {
                Files.copy(input, staged, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            long actualSize = Files.size(staged);
            if (actualSize != file.getSize() || actualSize > MAX_OBJECT_SIZE)
            {
                throw new ServiceException("上传文件大小校验失败", HttpStatus.BAD_REQUEST);
            }
            String sha256 = sha256(staged);
            S3Response response = execute(config, "PUT", objectKey, contentType, staged, actualSize);
            close(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
            {
                throw new ServiceException("OSS 拒绝上传请求", HttpStatus.CONFLICT);
            }
            remoteWritten = true;
            String publicUrl = "PUBLIC".equals(config.accessPolicy())
                    ? publicUrl(config, objectKey) : null;
            long objectId = insertObject(config, objectKey, originalName, suffix(originalName),
                    contentType, actualSize, sha256, publicUrl, actor(actor));
            return new StoredOssObject(objectId, originalName, contentType, actualSize,
                    sha256, config.accessPolicy(), publicUrl);
        }
        catch (ServiceException exception)
        {
            if (remoteWritten)
            {
                compensateDelete(config, objectKey, exception);
            }
            throw exception;
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new ServiceException("OSS 上传被中断", HttpStatus.ERROR);
        }
        catch (Exception exception)
        {
            ServiceException failure = new ServiceException("OSS 上传失败", HttpStatus.ERROR);
            if (remoteWritten)
            {
                compensateDelete(config, objectKey, failure);
            }
            throw failure;
        }
        finally
        {
            if (staged != null)
            {
                try
                {
                    Files.deleteIfExists(staged);
                }
                catch (IOException ignored)
                {
                    // 临时文件清理由操作系统兜底，禁止覆盖真实上传结果。
                }
            }
        }
    }

    /**
     * 按对象主键打开远端内容，只有 ACTIVE 对象允许下载。
     *
     * @param objectId long，正式对象主键
     * @return OpenedOssObject，响应元数据和必须由调用方关闭的输入流
     */
    @Transactional(readOnly = true)
    public OpenedOssObject open(long objectId)
    {
        ObjectFact object = objectFact(objectId, "ACTIVE");
        ActiveOssConfig config = configById(object.configId());
        try
        {
            S3Response response = execute(config, "GET", object.objectKey(), null, null, 0L);
            if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body() == null)
            {
                close(response.body());
                throw new ServiceException("OSS 对象不存在或不可读取", HttpStatus.NOT_FOUND);
            }
            return new OpenedOssObject(object.originalName(), object.contentType(),
                    object.fileSize(), response.body());
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new ServiceException("OSS 下载被中断", HttpStatus.ERROR);
        }
        catch (ServiceException exception)
        {
            throw exception;
        }
        catch (Exception exception)
        {
            throw new ServiceException("OSS 下载失败", HttpStatus.ERROR);
        }
    }

    /**
     * 通过 DELETE_PENDING 状态机删除对象；失败时保留 DELETE_FAILED 供人工重试。
     *
     * @param objectId long，正式对象主键
     * @param actor String，当前管理员账号
     * @return void，远端删除成功后台账进入 DELETED
     */
    public void delete(long objectId, String actor)
    {
        ObjectFact object = transactionTemplate.execute(status -> markDeleting(objectId, actor(actor)));
        if (object == null)
        {
            throw new ServiceException("OSS 对象删除状态转换失败", HttpStatus.ERROR);
        }
        ActiveOssConfig config = configById(object.configId());
        try
        {
            S3Response response = execute(config, "DELETE", object.objectKey(), null, null, 0L);
            close(response.body());
            if (!((response.statusCode() >= 200 && response.statusCode() < 300)
                    || response.statusCode() == 404))
            {
                markDeleteFailed(objectId, "OSS 拒绝删除请求");
                throw new ServiceException("OSS 拒绝删除请求", HttpStatus.CONFLICT);
            }
            if (jdbcTemplate.update("update sys_oss_object set status='DELETED',last_error=null," +
                    "delete_time=current_timestamp(3) where object_id=? and status='DELETE_PENDING'", objectId) != 1)
            {
                throw new ServiceException("OSS 对象删除状态已变化", HttpStatus.CONFLICT);
            }
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            markDeleteFailed(objectId, "OSS 删除被中断");
            throw new ServiceException("OSS 删除被中断", HttpStatus.ERROR);
        }
        catch (ServiceException exception)
        {
            throw exception;
        }
        catch (Exception exception)
        {
            markDeleteFailed(objectId, "OSS 删除调用失败");
            throw new ServiceException("OSS 删除调用失败", HttpStatus.ERROR);
        }
    }

    /**
     * 将 ACTIVE 或 DELETE_FAILED 对象原子改为 DELETE_PENDING 并返回锁定事实。
     *
     * @param objectId long，对象主键
     * @param actor String，删除操作账号
     * @return ObjectFact，远端删除所需不可变事实
     */
    private ObjectFact markDeleting(long objectId, String actor)
    {
        List<ObjectFact> rows = jdbcTemplate.query(
                "select object_id,config_id,object_key,original_name,content_type,file_size " +
                "from sys_oss_object where object_id=? and status in " +
                "('ACTIVE','DELETE_PENDING','DELETE_FAILED') for update",
                (result, rowNum) -> new ObjectFact(result.getLong("object_id"),
                        result.getLong("config_id"), result.getString("object_key"),
                        result.getString("original_name"), result.getString("content_type"),
                        result.getLong("file_size")), objectId);
        if (rows.size() != 1)
        {
            throw new ServiceException("OSS 对象不存在或状态不允许删除", HttpStatus.CONFLICT);
        }
        if (jdbcTemplate.update("update sys_oss_object set status='DELETE_PENDING',last_error=null," +
                "update_by=?,update_time=current_timestamp(3) where object_id=? " +
                "and status in ('ACTIVE','DELETE_PENDING','DELETE_FAILED')", actor, objectId) != 1)
        {
            throw new ServiceException("OSS 对象删除状态已变化", HttpStatus.CONFLICT);
        }
        return rows.get(0);
    }

    /**
     * 把远端删除失败写入可重试终态。
     *
     * @param objectId long，对象主键
     * @param summary String，不含端点或凭据的失败摘要
     * @return void，仅更新当前 DELETE_PENDING 状态
     */
    private void markDeleteFailed(long objectId, String summary)
    {
        jdbcTemplate.update("update sys_oss_object set status='DELETE_FAILED',last_error=?," +
                "update_time=current_timestamp(3) where object_id=? and status='DELETE_PENDING'",
                summary, objectId);
    }

    /**
     * 持久化已经成功写入远端的对象元数据。
     *
     * @param config ActiveOssConfig，当前启用配置
     * @param objectKey String，服务端生成对象键
     * @param originalName String，客户端原始文件名
     * @param suffix String，规范后缀
     * @param contentType String，规范 MIME
     * @param fileSize long，服务端实测字节数
     * @param sha256 String，服务端摘要
     * @param publicUrl String，公开对象 URL，私有对象为空
     * @param actor String，上传账号
     * @return long，正式对象主键
     */
    private long insertObject(ActiveOssConfig config, String objectKey, String originalName,
            String suffix, String contentType, long fileSize, String sha256, String publicUrl,
            String actor)
    {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection ->
        {
            var statement = connection.prepareStatement(
                    "insert into sys_oss_object (config_id,object_key,original_name,file_suffix," +
                    "content_type,file_size,sha256,access_policy,public_url,status,create_by,create_time) " +
                    "values (?,?,?,?,?,?,?,?,?,'ACTIVE',?,current_timestamp(3))",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, config.configId());
            statement.setString(2, objectKey);
            statement.setString(3, originalName);
            statement.setString(4, suffix);
            statement.setString(5, contentType);
            statement.setLong(6, fileSize);
            statement.setString(7, sha256);
            statement.setString(8, config.accessPolicy());
            statement.setString(9, publicUrl);
            statement.setString(10, actor);
            return statement;
        }, holder);
        if (holder.getKey() == null)
        {
            throw new ServiceException("OSS 对象台账保存失败", HttpStatus.ERROR);
        }
        return holder.getKey().longValue();
    }

    /**
     * 数据库登记失败时尽力删除刚写入的远端对象，并保留原异常。
     *
     * @param config ActiveOssConfig，上传使用配置
     * @param objectKey String，刚写入对象键
     * @param original RuntimeException，必须继续抛出的原失败
     * @return void，补偿失败会作为 suppressed 异常附加
     */
    private void compensateDelete(ActiveOssConfig config, String objectKey, RuntimeException original)
    {
        try
        {
            S3Response response = execute(config, "DELETE", objectKey, null, null, 0L);
            close(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
            {
                original.addSuppressed(new IllegalStateException("OSS 上传补偿删除被拒绝"));
            }
        }
        catch (Exception cleanupFailure)
        {
            original.addSuppressed(cleanupFailure);
        }
    }

    /**
     * 使用 AWS Signature V4 执行单次 S3 请求。
     *
     * @param config ActiveOssConfig，正式存储配置
     * @param method String，HEAD、PUT、GET 或 DELETE
     * @param objectKey String，可空对象键；空表示桶操作
     * @param contentType String，可空 MIME
     * @param body Path，可空上传文件
     * @param contentLength long，上传字节数
     * @return S3Response，HTTP 状态与响应流
     * @throws Exception 签名、网络或文件读取失败
     */
    private S3Response execute(ActiveOssConfig config, String method, String objectKey,
            String contentType, Path body, long contentLength) throws Exception
    {
        RequestTarget target = requestTarget(config, objectKey);
        Instant now = Instant.now();
        String amzDate = AMZ_DATE.format(now);
        String date = DATE_STAMP.format(now);
        String payloadHash = body == null ? sha256(new byte[0]) : sha256(body);
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        String normalizedType = contentType == null ? null : contentType;
        if (normalizedType != null)
        {
            headers.put("content-type", normalizedType);
        }
        headers.put("host", target.hostHeader());
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", amzDate);
        String signedHeaders = String.join(";", headers.keySet());
        StringBuilder canonicalHeaders = new StringBuilder();
        headers.forEach((name, value) -> canonicalHeaders.append(name).append(':')
                .append(value.trim()).append('\n'));
        String canonicalRequest = method + "\n" + target.canonicalPath() + "\n\n" +
                canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String scope = date + "/" + config.region() + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" +
                sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] dateKey = hmac(("AWS4" + config.secretKey()).getBytes(StandardCharsets.UTF_8), date);
        byte[] regionKey = hmac(dateKey, config.region());
        byte[] serviceKey = hmac(regionKey, "s3");
        byte[] signingKey = hmac(serviceKey, "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(signingKey, stringToSign));
        String authorization = "AWS4-HMAC-SHA256 Credential=" + config.accessKey() + "/" + scope +
                ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        HttpRequest.Builder builder = HttpRequest.newBuilder(target.uri()).timeout(REQUEST_TIMEOUT)
                .header("Authorization", authorization)
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate);
        if (normalizedType != null)
        {
            builder.header("Content-Type", normalizedType);
        }
        switch (method)
        {
            case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofFile(body));
            case "GET" -> builder.GET();
            case "DELETE" -> builder.DELETE();
            case "HEAD" -> builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
            default -> throw new IllegalArgumentException("不支持的 S3 方法");
        }
        HttpResponse<InputStream> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        return new S3Response(response.statusCode(), response.body());
    }

    /**
     * 按路径或虚拟主机风格构造真实请求 URI 与规范路径。
     *
     * @param config ActiveOssConfig，存储配置
     * @param objectKey String，可空对象键
     * @return RequestTarget，请求目标
     */
    private RequestTarget requestTarget(ActiveOssConfig config, String objectKey)
    {
        URI endpoint = config.endpoint();
        String encodedKey = objectKey == null ? "" : "/" + encodePath(objectKey);
        String path;
        String host;
        if ("Y".equals(config.pathStyle()))
        {
            path = "/" + encodeSegment(config.bucketName()) + encodedKey;
            host = endpoint.getHost();
        }
        else
        {
            path = objectKey == null ? "/" : encodedKey;
            host = config.bucketName() + "." + endpoint.getHost();
        }
        int port = endpoint.getPort();
        String authority = port < 0 ? host : host + ":" + port;
        URI uri = URI.create(endpoint.getScheme() + "://" + authority + path);
        return new RequestTarget(uri, path, authority);
    }

    /**
     * 读取唯一启用 OSS 配置。
     *
     * @return ActiveOssConfig，远端调用所需密钥配置
     */
    private ActiveOssConfig activeConfig()
    {
        List<ActiveOssConfig> rows = queryConfigs("where status='0' order by config_id");
        if (rows.isEmpty())
        {
            throw new ServiceException("OSS 服务尚未启用", HttpStatus.CONFLICT);
        }
        if (rows.size() != 1)
        {
            throw new ServiceException("OSS 启用配置不唯一", HttpStatus.ERROR);
        }
        return rows.get(0);
    }

    /**
     * 按主键读取 OSS 配置，供历史对象继续访问原存储桶。
     *
     * @param configId long，配置主键
     * @return ActiveOssConfig，完整内部配置
     */
    private ActiveOssConfig configById(long configId)
    {
        List<ActiveOssConfig> rows = queryConfigs("where config_id=" + configId);
        if (rows.size() != 1)
        {
            throw new ServiceException("OSS 配置不存在", HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
    }

    /**
     * 使用服务端固定 SQL 片段查询内部 OSS 配置。
     *
     * @param clause String，仅由本类常量和 long 主键构造的条件片段
     * @return List&lt;ActiveOssConfig&gt;，内部密钥配置
     */
    private List<ActiveOssConfig> queryConfigs(String clause)
    {
        return jdbcTemplate.query("select config_id,endpoint,region,bucket_name,access_key," +
                "secret_key,domain,prefix,path_style,access_policy from sys_oss_config " + clause,
                (result, rowNum) -> new ActiveOssConfig(result.getLong("config_id"),
                        URI.create(result.getString("endpoint")), result.getString("region"),
                        result.getString("bucket_name"), result.getString("access_key"),
                        result.getString("secret_key"), result.getString("domain"),
                        result.getString("prefix"), result.getString("path_style"),
                        result.getString("access_policy")));
    }

    /**
     * 查询指定状态对象事实。
     *
     * @param objectId long，对象主键
     * @param status String，要求状态
     * @return ObjectFact，对象存储定位与响应元数据
     */
    private ObjectFact objectFact(long objectId, String status)
    {
        List<ObjectFact> rows = jdbcTemplate.query(
                "select object_id,config_id,object_key,original_name,content_type,file_size " +
                "from sys_oss_object where object_id=? and status=?",
                (result, rowNum) -> new ObjectFact(result.getLong("object_id"),
                        result.getLong("config_id"), result.getString("object_key"),
                        result.getString("original_name"), result.getString("content_type"),
                        result.getLong("file_size")), objectId, status);
        if (rows.size() != 1)
        {
            throw new ServiceException("OSS 对象不存在", HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
    }

    /**
     * 校验并规范 OSS 配置。
     *
     * @param request OssConfigRequest，配置请求
     * @param updating boolean，是否为修改
     * @return ValidatedOssConfig，规范化配置
     */
    private ValidatedOssConfig validateConfig(OssConfigRequest request, boolean updating)
    {
        if (request == null || (updating && (request.configId() == null || request.configId() <= 0)))
        {
            throw new ServiceException("OSS 配置主键不合法", HttpStatus.BAD_REQUEST);
        }
        URI endpoint = endpoint(request.endpoint());
        String bucket = normalized(request.bucketName(), 128, "OSS 存储桶不合法").toLowerCase(Locale.ROOT);
        if (!BUCKET_PATTERN.matcher(bucket).matches())
        {
            throw new ServiceException("OSS 存储桶不合法", HttpStatus.BAD_REQUEST);
        }
        String region = normalized(request.region(), 64, "OSS 地域不合法").toLowerCase(Locale.ROOT);
        if (!REGION_PATTERN.matcher(region).matches())
        {
            throw new ServiceException("OSS 地域不合法", HttpStatus.BAD_REQUEST);
        }
        String secret = optional(request.secretKey(), 256);
        if (!updating && !StringUtils.hasText(secret))
        {
            throw new ServiceException("OSS SecretKey 不能为空", HttpStatus.BAD_REQUEST);
        }
        String prefix = optional(request.prefix(), 128);
        if (prefix != null)
        {
            prefix = prefix.replaceAll("^/+|/+$", "");
            if (!PREFIX_PATTERN.matcher(prefix).matches() || prefix.contains("//") || prefix.contains(".."))
            {
                throw new ServiceException("OSS 对象前缀不合法", HttpStatus.BAD_REQUEST);
            }
        }
        String domain = domain(request.domain());
        String pathStyle = normalized(request.pathStyle(), 1, "OSS 寻址模式不合法");
        String policy = normalized(request.accessPolicy(), 16, "OSS 访问策略不合法");
        if (!List.of("Y", "N").contains(pathStyle) || !List.of("PRIVATE", "PUBLIC").contains(policy))
        {
            throw new ServiceException("OSS 访问配置不合法", HttpStatus.BAD_REQUEST);
        }
        if ("PUBLIC".equals(policy) && !StringUtils.hasText(domain))
        {
            throw new ServiceException("公开 OSS 配置必须提供受控访问域名", HttpStatus.BAD_REQUEST);
        }
        return new ValidatedOssConfig(normalized(request.configName(), 64, "OSS 配置名称不合法"),
                endpoint, region, bucket, normalized(request.accessKey(), 128, "OSS AccessKey 不合法"),
                secret == null ? "" : secret, domain, prefix, pathStyle, policy,
                optional(request.remark(), 500));
    }

    /**
     * 校验端点 URI；公网必须 HTTPS，HTTP 只允许字面量回环或私网地址。
     *
     * @param raw String，管理员提交端点
     * @return URI，规范根 URI
     */
    private URI endpoint(String raw)
    {
        try
        {
            URI uri = URI.create(normalized(raw, 255, "OSS 端点不合法"));
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || (StringUtils.hasText(uri.getPath()) && !"/".equals(uri.getPath())))
            {
                throw new IllegalArgumentException("endpoint shape");
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme) && !("http".equals(scheme) && isPrivateLiteralHost(uri.getHost())))
            {
                throw new IllegalArgumentException("endpoint scheme");
            }
            int port = uri.getPort();
            String authority = port < 0 ? uri.getHost() : uri.getHost() + ":" + port;
            return URI.create(scheme + "://" + authority);
        }
        catch (RuntimeException exception)
        {
            throw new ServiceException("OSS 端点必须是 HTTPS 根地址；HTTP 仅允许字面量私网地址",
                    HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 判断 HTTP 端点是否为字面量回环或 RFC1918 私网地址，禁止域名绕过。
     *
     * @param host String，URI 主机
     * @return boolean，仅私网字面量返回 true
     */
    private boolean isPrivateLiteralHost(String host)
    {
        if (host == null || !host.matches("[0-9a-fA-F:.]+") && !"localhost".equalsIgnoreCase(host))
        {
            return false;
        }
        try
        {
            InetAddress address = InetAddress.getByName(host);
            return address.isLoopbackAddress() || address.isSiteLocalAddress();
        }
        catch (Exception exception)
        {
            return false;
        }
    }

    /**
     * 校验公开域名，只允许 HTTPS 且不包含路径凭据或查询串。
     *
     * @param raw String，可空公开域名
     * @return String，规范化域名或 null
     */
    private String domain(String raw)
    {
        if (!StringUtils.hasText(raw))
        {
            return null;
        }
        try
        {
            URI uri = URI.create(raw.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (StringUtils.hasText(uri.getPath()) && !"/".equals(uri.getPath())))
            {
                throw new IllegalArgumentException("domain shape");
            }
            return "https://" + uri.getAuthority();
        }
        catch (RuntimeException exception)
        {
            throw new ServiceException("OSS 公开域名必须是 HTTPS 根地址", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 生成不可预测且按日期分区的对象键。
     *
     * @param prefix String，可空业务前缀
     * @param originalName String，规范原文件名
     * @return String，服务端对象键
     */
    private String objectKey(String prefix, String originalName)
    {
        String date = LocalDate.now(ZoneOffset.UTC).toString().replace('-', '/');
        String key = date + "/" + UUID.randomUUID().toString().replace("-", "") + suffix(originalName);
        return StringUtils.hasText(prefix) ? prefix + "/" + key : key;
    }

    /**
     * 规范客户端原文件名，移除路径语义和控制字符。
     *
     * @param raw String，Multipart 原文件名
     * @return String，安全显示名称
     */
    private String originalName(String raw)
    {
        if (!StringUtils.hasText(raw))
        {
            throw new ServiceException("上传文件名不能为空", HttpStatus.BAD_REQUEST);
        }
        String name = raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (!StringUtils.hasText(name) || name.equals(".") || name.equals("..")
                || name.length() > MAX_ORIGINAL_NAME_LENGTH
                || name.chars().anyMatch(Character::isISOControl))
        {
            throw new ServiceException("上传文件名不合法", HttpStatus.BAD_REQUEST);
        }
        return name;
    }

    /**
     * 规范 MIME，未知或不合法值统一使用二进制类型。
     *
     * @param raw String，客户端 MIME
     * @return String，安全 MIME
     */
    private String contentType(String raw)
    {
        String value = StringUtils.hasText(raw) ? raw.trim().toLowerCase(Locale.ROOT) : "";
        return CONTENT_TYPE_PATTERN.matcher(value).matches() ? value : "application/octet-stream";
    }

    /**
     * 提取最长 16 字符的小写安全后缀。
     *
     * @param name String，规范文件名
     * @return String，含点后缀或空串
     */
    private String suffix(String name)
    {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1)
        {
            return "";
        }
        String suffix = name.substring(dot).toLowerCase(Locale.ROOT);
        return suffix.matches("\\.[a-z0-9]{1,16}") ? suffix : "";
    }

    /**
     * 生成 PUBLIC 对象的稳定访问 URL。
     *
     * @param config ActiveOssConfig，公开配置
     * @param objectKey String，对象键
     * @return String，公开 URL
     */
    private String publicUrl(ActiveOssConfig config, String objectKey)
    {
        return config.domain().replaceAll("/+$", "") + "/" + encodePath(objectKey);
    }

    /**
     * 按路径段编码对象键，保留目录斜杠。
     *
     * @param value String，对象键
     * @return String，S3 canonical URI 片段
     */
    static String encodePath(String value)
    {
        return java.util.Arrays.stream(value.split("/", -1))
                .map(SysOssService::encodeSegment)
                .collect(java.util.stream.Collectors.joining("/"));
    }

    /**
     * 按 RFC 3986 编码单个路径段。
     *
     * @param value String，路径段
     * @return String，规范编码段
     */
    private static String encodeSegment(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    /**
     * 计算文件 SHA-256。
     *
     * @param path Path，暂存文件
     * @return String，64 位小写摘要
     * @throws Exception 文件读取或算法失败
     */
    private static String sha256(Path path) throws Exception
    {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ))
        {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0)
            {
                if (read > 0)
                {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 计算字节数组 SHA-256。
     *
     * @param value byte[]，待摘要内容
     * @return String，64 位小写摘要
     * @throws Exception 算法失败
     */
    private static String sha256(byte[] value) throws Exception
    {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    /**
     * 计算 HMAC-SHA256。
     *
     * @param key byte[]，签名密钥
     * @param value String，待签名正文
     * @return byte[]，二进制签名
     * @throws Exception 算法失败
     */
    private static byte[] hmac(byte[] key, String value) throws Exception
    {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 安全关闭可空响应流。
     *
     * @param stream InputStream，可空响应流
     * @return void，关闭失败不覆盖主业务结果
     */
    private static void close(InputStream stream)
    {
        if (stream == null)
        {
            return;
        }
        try
        {
            stream.close();
        }
        catch (IOException ignored)
        {
            // HTTP 客户端会在流关闭或回收后释放连接。
        }
    }

    /**
     * 规范管理员账号。
     *
     * @param value String，当前账号
     * @return String，最长 64 字符账号
     */
    private static String actor(String value)
    {
        return normalized(value, 64, "OSS 操作账号不合法");
    }

    /**
     * 规范必填文本并拒绝控制字符。
     *
     * @param value String，原始文本
     * @param max int，最大字符数
     * @param message String，失败提示
     * @return String，规范文本
     */
    private static String normalized(String value, int max, String message)
    {
        if (!StringUtils.hasText(value))
        {
            throw new ServiceException(message, HttpStatus.BAD_REQUEST);
        }
        String result = value.trim();
        if (result.length() > max || result.chars().anyMatch(Character::isISOControl))
        {
            throw new ServiceException(message, HttpStatus.BAD_REQUEST);
        }
        return result;
    }

    /**
     * 规范可空文本并拒绝控制字符。
     *
     * @param value String，原始文本
     * @param max int，最大字符数
     * @return String，规范文本或 null
     */
    private static String optional(String value, int max)
    {
        if (!StringUtils.hasText(value))
        {
            return null;
        }
        String result = value.trim();
        if (result.length() > max || result.chars().anyMatch(Character::isISOControl))
        {
            throw new ServiceException("OSS 配置文本不合法", HttpStatus.BAD_REQUEST);
        }
        return result;
    }

    /** 校验后的新增或修改配置。 */
    private record ValidatedOssConfig(String configName, URI endpoint, String region,
            String bucketName, String accessKey, String secretKey, String domain, String prefix,
            String pathStyle, String accessPolicy, String remark)
    {
    }

    /** 内部 OSS 配置，密钥不得离开服务边界。 */
    private record ActiveOssConfig(long configId, URI endpoint, String region,
            String bucketName, String accessKey, String secretKey, String domain, String prefix,
            String pathStyle, String accessPolicy)
    {
    }

    /** S3 请求目标。 */
    private record RequestTarget(URI uri, String canonicalPath, String hostHeader)
    {
    }

    /** S3 响应状态与流。 */
    private record S3Response(int statusCode, InputStream body)
    {
    }

    /** 对象删除或下载所需数据库事实。 */
    protected record ObjectFact(long objectId, long configId, String objectKey,
            String originalName, String contentType, long fileSize)
    {
    }

    /**
     * 上传成功后返回的安全对象元数据。
     *
     * @param objectId long，正式对象主键
     * @param originalName String，原文件名
     * @param contentType String，MIME
     * @param fileSize long，字节数
     * @param sha256 String，服务端摘要
     * @param accessPolicy String，PRIVATE 或 PUBLIC
     * @param publicUrl String，公开 URL，私有对象为空
     */
    public record StoredOssObject(long objectId, String originalName, String contentType,
            long fileSize, String sha256, String accessPolicy, String publicUrl)
    {
    }

    /**
     * 已授权打开的对象响应。
     *
     * @param originalName String，下载文件名
     * @param contentType String，MIME
     * @param fileSize long，字节数
     * @param content InputStream，调用方必须关闭的远端流
     */
    public record OpenedOssObject(String originalName, String contentType, long fileSize,
            InputStream content)
    {
    }
}
