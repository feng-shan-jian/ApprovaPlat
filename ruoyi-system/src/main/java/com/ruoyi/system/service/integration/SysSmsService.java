package com.ruoyi.system.service.integration;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.integration.SmsConfigRequest;
import com.ruoyi.system.domain.integration.SmsSendRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 短信配置、供应商投递和发送审计服务。
 */
@Service
public class SysSmsService
{
    private static final URI ALIYUN_ENDPOINT = URI.create("https://dysmsapi.aliyuncs.com/");
    private static final URI TENCENT_ENDPOINT = URI.create("https://sms.tencentcloudapi.com/");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[1-9][0-9]{6,14}$");
    private static final Pattern PARAMETER_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,64}$");
    private static final int MAX_PHONE_COUNT = 20;
    private static final int MAX_PARAMETER_COUNT = 20;
    private static final int MAX_PARAMETER_VALUE_LENGTH = 700;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /**
     * 创建短信服务并固定使用不跟随跳转的 JDK HTTPS 客户端。
     *
     * @param jdbcTemplate JdbcTemplate，正式短信配置与发送日志数据源
     * @param objectMapper ObjectMapper，项目统一 Jackson 3 编解码器
     * @return void，构造后由 Spring 管理
     */
    @Autowired
    public SysSmsService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper)
    {
        this(jdbcTemplate, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    /**
     * 使用显式 HTTP 客户端创建服务，供受控测试替换网络边界。
     *
     * @param jdbcTemplate JdbcTemplate，正式短信配置与发送日志数据源
     * @param objectMapper ObjectMapper，JSON 编解码器
     * @param httpClient HttpClient，禁止为空的供应商传输客户端
     * @return void，参数不完整时拒绝创建
     */
    SysSmsService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, HttpClient httpClient)
    {
        if (jdbcTemplate == null || objectMapper == null || httpClient == null)
        {
            throw new IllegalArgumentException("短信服务依赖不能为空");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /**
     * 查询全部短信配置，返回结果永久排除访问密钥明文。
     *
     * @return List&lt;Map&lt;String,Object&gt;&gt;，按主键倒序的脱敏配置
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listConfigs()
    {
        return jdbcTemplate.queryForList("select config_id as configId,config_name as configName," +
                "provider,access_key_id as accessKeyId,case when access_key_secret<>'' then 1 else 0 end " +
                "as secretConfigured,sign_name as signName,sdk_app_id as sdkAppId,region,status," +
                "create_by as createBy,create_time as createTime,update_by as updateBy," +
                "update_time as updateTime,remark from sys_sms_config order by config_id desc");
    }

    /**
     * 新增短信配置，密钥只进入正式配置表且不进入返回对象。
     *
     * @param request SmsConfigRequest，已通过 Bean Validation 的配置请求
     * @param actor String，当前管理员账号
     * @return long，新配置主键
     */
    @Transactional(rollbackFor = Exception.class)
    public long createConfig(SmsConfigRequest request, String actor)
    {
        ValidatedSmsConfig config = validateConfig(request, false);
        KeyHolder holder = new GeneratedKeyHolder();
        try
        {
            jdbcTemplate.update(connection ->
            {
                var statement = connection.prepareStatement(
                        "insert into sys_sms_config (config_name,provider,access_key_id,access_key_secret," +
                        "sign_name,sdk_app_id,region,status,create_by,create_time,remark) " +
                        "values (?,?,?,?,?,?,?,'1',?,current_timestamp(3),?)",
                        java.sql.Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, config.configName());
                statement.setString(2, config.provider());
                statement.setString(3, config.accessKeyId());
                statement.setString(4, config.accessKeySecret());
                statement.setString(5, config.signName());
                statement.setString(6, config.sdkAppId());
                statement.setString(7, config.region());
                statement.setString(8, normalizedActor(actor));
                statement.setString(9, config.remark());
                return statement;
            }, holder);
        }
        catch (DuplicateKeyException exception)
        {
            throw new ServiceException("短信配置名称已存在", HttpStatus.CONFLICT);
        }
        if (holder.getKey() == null)
        {
            throw new ServiceException("短信配置保存失败", HttpStatus.ERROR);
        }
        return holder.getKey().longValue();
    }

    /**
     * 修改短信配置；未提交新密钥时在 SQL 层保留原值。
     *
     * @param request SmsConfigRequest，必须包含正式配置主键
     * @param actor String，当前管理员账号
     * @return void，配置不存在或版本外状态异常时抛出业务异常
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateConfig(SmsConfigRequest request, String actor)
    {
        ValidatedSmsConfig config = validateConfig(request, true);
        int updated;
        try
        {
            updated = jdbcTemplate.update("update sys_sms_config set config_name=?,provider=?," +
                    "access_key_id=?,access_key_secret=case when ?='' then access_key_secret else ? end," +
                    "sign_name=?,sdk_app_id=?,region=?,update_by=?,update_time=current_timestamp(3),remark=? " +
                    "where config_id=?", config.configName(), config.provider(), config.accessKeyId(),
                    config.accessKeySecret(), config.accessKeySecret(), config.signName(), config.sdkAppId(),
                    config.region(), normalizedActor(actor), config.remark(), request.configId());
        }
        catch (DuplicateKeyException exception)
        {
            throw new ServiceException("短信配置名称已存在", HttpStatus.CONFLICT);
        }
        if (updated != 1)
        {
            throw new ServiceException("短信配置不存在", HttpStatus.NOT_FOUND);
        }
    }

    /**
     * 原子启用指定短信配置并停用其他配置，保证工作流只有一个确定出口。
     *
     * @param configId long，待启用配置主键
     * @param actor String，当前管理员账号
     * @return void，配置不存在时拒绝改变任何状态
     */
    @Transactional(rollbackFor = Exception.class)
    public void activate(long configId, String actor)
    {
        Integer exists = jdbcTemplate.queryForObject(
                "select count(*) from sys_sms_config where config_id=? for update", Integer.class, configId);
        if (exists == null || exists != 1)
        {
            throw new ServiceException("短信配置不存在", HttpStatus.NOT_FOUND);
        }
        jdbcTemplate.update("update sys_sms_config set status='1',update_by=?," +
                "update_time=current_timestamp(3) where status='0'", normalizedActor(actor));
        int updated = jdbcTemplate.update("update sys_sms_config set status='0',update_by=?," +
                "update_time=current_timestamp(3) where config_id=?", normalizedActor(actor), configId);
        if (updated != 1)
        {
            throw new ServiceException("短信配置启用失败", HttpStatus.CONFLICT);
        }
    }

    /**
     * 删除未启用且没有被发送日志引用的短信配置。
     *
     * @param configId long，配置主键
     * @return void，启用配置或已有审计引用时拒绝删除
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteConfig(long configId)
    {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select status from sys_sms_config where config_id=? for update", configId);
        if (rows.isEmpty())
        {
            throw new ServiceException("短信配置不存在", HttpStatus.NOT_FOUND);
        }
        if ("0".equals(String.valueOf(rows.get(0).get("status"))))
        {
            throw new ServiceException("启用中的短信配置不能删除", HttpStatus.CONFLICT);
        }
        Integer referenced = jdbcTemplate.queryForObject(
                "select count(*) from sys_sms_log where config_id=?", Integer.class, configId);
        if (referenced != null && referenced > 0)
        {
            throw new ServiceException("已有发送审计的短信配置不能删除", HttpStatus.CONFLICT);
        }
        jdbcTemplate.update("delete from sys_sms_config where config_id=?", configId);
    }

    /**
     * 查询最近短信投递日志，不返回手机号明文、模板参数或供应商原始响应。
     *
     * @return List&lt;Map&lt;String,Object&gt;&gt;，最近 500 条脱敏投递审计
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listLogs()
    {
        return jdbcTemplate.queryForList("select log_id as logId,config_id as configId,provider," +
                "source_type as sourceType,recipient_masked as recipientMasked,recipient_count as recipientCount," +
                "template_id as templateId,status,provider_request_id as providerRequestId,error_code as errorCode," +
                "error_summary as errorSummary,create_by as createBy,create_time as createTime," +
                "finish_time as finishTime from sys_sms_log order by log_id desc limit 500");
    }

    /**
     * 使用当前启用配置执行管理员测试发送。
     *
     * @param request SmsSendRequest，手机号、模板和参数
     * @param actor String，当前管理员账号
     * @return SmsDeliveryResult，真实供应商投递结果
     */
    public SmsDeliveryResult sendTest(SmsSendRequest request, String actor)
    {
        return send(request, "ADMIN_TEST", normalizedActor(actor));
    }

    /**
     * 使用当前启用配置执行正式业务短信发送。
     *
     * @param request SmsSendRequest，服务端构造的接收人和模板请求
     * @param source String，稳定业务来源标识
     * @return SmsDeliveryResult，真实供应商投递结果
     */
    public SmsDeliveryResult sendBusiness(SmsSendRequest request, String source)
    {
        return send(request, normalizedSource(source), "SYSTEM");
    }

    /**
     * 先持久化 PENDING 审计，再执行供应商调用并回写确定终态。
     *
     * @param request SmsSendRequest，原始发送请求
     * @param source String，ADMIN_TEST 或正式业务来源
     * @param actor String，发送主体账号
     * @return SmsDeliveryResult，成功、失败码和脱敏说明
     */
    private SmsDeliveryResult send(SmsSendRequest request, String source, String actor)
    {
        List<String> phones = validatePhones(request == null ? null : request.phones());
        String templateId = normalized(request == null ? null : request.templateId(), 64, "短信模板 ID 不合法");
        Map<String, String> parameters = validateParameters(request == null ? null : request.parameters());
        ActiveSmsConfig config = activeConfig();
        if ("WORKFLOW".equals(source) && "TENCENT".equals(config.provider())
                && parameters.size() == 1 && parameters.containsKey("content"))
        {
            // 工作流策略只维护跨供应商的正文语义；腾讯云模板按位置取值，需转换为第一个参数。
            parameters = Map.of("1", parameters.get("content"));
        }
        if ("TENCENT".equals(config.provider()))
        {
            List<Integer> positions = parameters.keySet().stream()
                    .map(SysSmsService::numericKey).sorted().toList();
            for (int index = 0; index < positions.size(); index++)
            {
                if (positions.get(index) != index + 1)
                {
                    throw new ServiceException("腾讯云短信模板参数必须使用从 1 开始的连续数字键",
                            HttpStatus.BAD_REQUEST);
                }
            }
        }
        long logId = insertPendingLog(config, source, phones, templateId, actor);
        SmsDeliveryResult result;
        try
        {
            result = "ALIYUN".equals(config.provider())
                    ? sendAliyun(config, phones, templateId, parameters)
                    : sendTencent(config, phones, templateId, parameters);
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            result = SmsDeliveryResult.failed("SMS_INTERRUPTED", "短信供应商调用被中断");
        }
        catch (Exception exception)
        {
            // 原始异常可能包含主机、签名或供应商响应，禁止写日志或返回管理端。
            result = SmsDeliveryResult.failed("SMS_DELIVERY_FAILED", "短信供应商调用失败");
        }
        finishLog(logId, result);
        return result.withLogId(logId);
    }

    /**
     * 调用阿里云 SendSms RPC API 并解析稳定响应字段。
     *
     * @param config ActiveSmsConfig，已启用阿里云配置
     * @param phones List&lt;String&gt;，已校验手机号
     * @param templateId String，模板 ID
     * @param parameters Map&lt;String,String&gt;，模板变量
     * @return SmsDeliveryResult，供应商真实结果
     * @throws Exception 网络、签名或响应解析失败
     */
    private SmsDeliveryResult sendAliyun(ActiveSmsConfig config, List<String> phones,
            String templateId, Map<String, String> parameters) throws Exception
    {
        TreeMap<String, String> values = new TreeMap<>();
        values.put("AccessKeyId", config.accessKeyId());
        values.put("Action", "SendSms");
        values.put("Format", "JSON");
        values.put("PhoneNumbers", String.join(",", phones));
        values.put("RegionId", "cn-hangzhou");
        values.put("SignatureMethod", "HMAC-SHA1");
        values.put("SignatureNonce", UUID.randomUUID().toString());
        values.put("SignatureVersion", "1.0");
        values.put("SignName", config.signName());
        values.put("TemplateCode", templateId);
        values.put("TemplateParam", objectMapper.writeValueAsString(parameters));
        values.put("Timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)));
        values.put("Version", "2017-05-25");
        String canonical = formEncoded(values);
        String signature = base64HmacSha1(config.accessKeySecret() + "&",
                "POST&%2F&" + percentEncode(canonical));
        String body = canonical + "&Signature=" + percentEncode(signature);
        HttpRequest httpRequest = HttpRequest.newBuilder(ALIYUN_ENDPOINT)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = httpClient.send(httpRequest,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200)
        {
            return SmsDeliveryResult.failed("ALIYUN_HTTP_" + response.statusCode(), "阿里云短信接口请求失败");
        }
        JsonNode root = objectMapper.readTree(response.body());
        String code = text(root, "Code");
        String requestId = text(root, "RequestId");
        return "OK".equalsIgnoreCase(code)
                ? SmsDeliveryResult.delivered(requestId)
                : SmsDeliveryResult.failed(safeCode(code, "ALIYUN_REJECTED"), "阿里云拒绝短信请求", requestId);
    }

    /**
     * 调用腾讯云 SendSms API 并解析全部号码投递状态。
     *
     * @param config ActiveSmsConfig，已启用腾讯云配置
     * @param phones List&lt;String&gt;，已校验手机号
     * @param templateId String，模板 ID
     * @param parameters Map&lt;String,String&gt;，数字键顺序参数
     * @return SmsDeliveryResult，供应商真实结果
     * @throws Exception 网络、签名或响应解析失败
     */
    private SmsDeliveryResult sendTencent(ActiveSmsConfig config, List<String> phones,
            String templateId, Map<String, String> parameters) throws Exception
    {
        List<String> internationalPhones = phones.stream().map(SysSmsService::toTencentPhone).toList();
        List<String> parameterValues = parameters.entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> numericKey(entry.getKey())))
                .map(Map.Entry::getValue).toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("PhoneNumberSet", internationalPhones);
        payload.put("SmsSdkAppId", config.sdkAppId());
        payload.put("SignName", config.signName());
        payload.put("TemplateId", templateId);
        payload.put("TemplateParamSet", parameterValues);
        String json = objectMapper.writeValueAsString(payload);
        long timestamp = Instant.now().getEpochSecond();
        String authorization = tencentAuthorization(config, json, timestamp);
        HttpRequest httpRequest = HttpRequest.newBuilder(TENCENT_ENDPOINT)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", authorization)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Host", "sms.tencentcloudapi.com")
                .header("X-TC-Action", "SendSms")
                .header("X-TC-Timestamp", Long.toString(timestamp))
                .header("X-TC-Version", "2021-01-11")
                .header("X-TC-Region", config.region())
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = httpClient.send(httpRequest,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200)
        {
            return SmsDeliveryResult.failed("TENCENT_HTTP_" + response.statusCode(), "腾讯云短信接口请求失败");
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode responseNode = root.path("Response");
        String requestId = text(responseNode, "RequestId");
        JsonNode error = responseNode.path("Error");
        if (!error.isMissingNode() && !error.isNull())
        {
            return SmsDeliveryResult.failed(safeCode(text(error, "Code"), "TENCENT_REJECTED"),
                    "腾讯云拒绝短信请求", requestId);
        }
        JsonNode statuses = responseNode.path("SendStatusSet");
        if (!statuses.isArray() || statuses.isEmpty())
        {
            return SmsDeliveryResult.failed("TENCENT_EMPTY_STATUS", "腾讯云未返回号码投递状态", requestId);
        }
        for (JsonNode status : statuses)
        {
            if (!"Ok".equalsIgnoreCase(text(status, "Code")))
            {
                return SmsDeliveryResult.failed(safeCode(text(status, "Code"), "TENCENT_REJECTED"),
                        "腾讯云拒绝部分或全部短信请求", requestId);
            }
        }
        return SmsDeliveryResult.delivered(requestId);
    }

    /**
     * 读取唯一启用短信配置，并在数据漂移时 fail closed。
     *
     * @return ActiveSmsConfig，包含供应商调用所需正式密钥
     */
    private ActiveSmsConfig activeConfig()
    {
        List<ActiveSmsConfig> configs = jdbcTemplate.query(
                "select config_id,provider,access_key_id,access_key_secret,sign_name,sdk_app_id,region " +
                "from sys_sms_config where status='0' order by config_id",
                (result, rowNum) -> new ActiveSmsConfig(result.getLong("config_id"),
                        result.getString("provider"), result.getString("access_key_id"),
                        result.getString("access_key_secret"), result.getString("sign_name"),
                        result.getString("sdk_app_id"), result.getString("region")));
        if (configs.isEmpty())
        {
            throw new ServiceException("短信服务尚未启用", HttpStatus.CONFLICT);
        }
        if (configs.size() != 1)
        {
            throw new ServiceException("短信启用配置不唯一", HttpStatus.ERROR);
        }
        return configs.get(0);
    }

    /**
     * 写入外部调用前的不可丢失 PENDING 审计。
     *
     * @param config ActiveSmsConfig，当前启用配置
     * @param source String，业务来源
     * @param phones List&lt;String&gt;，手机号集合
     * @param templateId String，模板 ID
     * @param actor String，发送主体
     * @return long，发送日志主键
     */
    private long insertPendingLog(ActiveSmsConfig config, String source, List<String> phones,
            String templateId, String actor)
    {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection ->
        {
            var statement = connection.prepareStatement(
                    "insert into sys_sms_log (config_id,provider,source_type,recipient_masked," +
                    "recipient_count,template_id,status,create_by,create_time) " +
                    "values (?,?,?,?,?,?,'PENDING',?,current_timestamp(3))",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, config.configId());
            statement.setString(2, config.provider());
            statement.setString(3, source);
            statement.setString(4, phones.stream().map(SysSmsService::maskPhone)
                    .collect(java.util.stream.Collectors.joining(",")));
            statement.setInt(5, phones.size());
            statement.setString(6, templateId);
            statement.setString(7, actor);
            return statement;
        }, holder);
        if (holder.getKey() == null)
        {
            throw new ServiceException("短信发送审计保存失败", HttpStatus.ERROR);
        }
        return holder.getKey().longValue();
    }

    /**
     * 把 PENDING 日志原子提交为 DELIVERED 或 FAILED。
     *
     * @param logId long，发送日志主键
     * @param result SmsDeliveryResult，脱敏供应商结果
     * @return void，日志状态漂移时抛出服务端异常
     */
    private void finishLog(long logId, SmsDeliveryResult result)
    {
        int updated = jdbcTemplate.update("update sys_sms_log set status=?,provider_request_id=?," +
                "error_code=?,error_summary=?,finish_time=current_timestamp(3) " +
                "where log_id=? and status='PENDING'", result.success() ? "DELIVERED" : "FAILED",
                result.providerRequestId(), result.errorCode(), result.summary(), logId);
        if (updated != 1)
        {
            throw new ServiceException("短信发送审计状态已变化", HttpStatus.CONFLICT);
        }
    }

    /**
     * 校验供应商配置并按供应商补齐严格约束。
     *
     * @param request SmsConfigRequest，配置请求
     * @param updating boolean，是否为修改请求
     * @return ValidatedSmsConfig，规范化配置
     */
    private ValidatedSmsConfig validateConfig(SmsConfigRequest request, boolean updating)
    {
        if (request == null || (updating && (request.configId() == null || request.configId() <= 0)))
        {
            throw new ServiceException("短信配置主键不合法", HttpStatus.BAD_REQUEST);
        }
        String provider = normalized(request.provider(), 16, "短信供应商不合法").toUpperCase(Locale.ROOT);
        if (!List.of("ALIYUN", "TENCENT").contains(provider))
        {
            throw new ServiceException("短信供应商不受支持", HttpStatus.BAD_REQUEST);
        }
        String secret = optional(request.accessKeySecret(), 256);
        if (!updating && !StringUtils.hasText(secret))
        {
            throw new ServiceException("短信访问密钥不能为空", HttpStatus.BAD_REQUEST);
        }
        String sdkAppId = optional(request.sdkAppId(), 64);
        String region = optional(request.region(), 64);
        if ("TENCENT".equals(provider) && (!StringUtils.hasText(sdkAppId) || !StringUtils.hasText(region)))
        {
            throw new ServiceException("腾讯云短信必须配置应用 ID 和地域", HttpStatus.BAD_REQUEST);
        }
        return new ValidatedSmsConfig(normalized(request.configName(), 64, "短信配置名称不合法"),
                provider, normalized(request.accessKeyId(), 128, "短信访问密钥 ID 不合法"),
                secret == null ? "" : secret, normalized(request.signName(), 64, "短信签名不合法"),
                sdkAppId, region, optional(request.remark(), 500));
    }

    /**
     * 校验手机号集合、去重并保持提交顺序。
     *
     * @param raw String，逗号分隔手机号
     * @return List&lt;String&gt;，一至二十个规范手机号
     */
    private List<String> validatePhones(String raw)
    {
        if (!StringUtils.hasText(raw))
        {
            throw new ServiceException("短信手机号不能为空", HttpStatus.BAD_REQUEST);
        }
        LinkedHashMap<String, Boolean> unique = new LinkedHashMap<>();
        for (String item : raw.split(","))
        {
            String phone = item.trim().replace(" ", "");
            if (!PHONE_PATTERN.matcher(phone).matches())
            {
                throw new ServiceException("短信手机号格式不合法", HttpStatus.BAD_REQUEST);
            }
            unique.put(phone, Boolean.TRUE);
        }
        if (unique.isEmpty() || unique.size() > MAX_PHONE_COUNT)
        {
            throw new ServiceException("单次短信接收人必须为 1 至 20 个", HttpStatus.BAD_REQUEST);
        }
        return List.copyOf(unique.keySet());
    }

    /**
     * 校验模板参数数量、键和值长度，禁止控制字符进入供应商请求。
     *
     * @param raw Map&lt;String,String&gt;，客户端模板参数
     * @return Map&lt;String,String&gt;，保持顺序的不可变参数
     */
    private Map<String, String> validateParameters(Map<String, String> raw)
    {
        if (raw == null || raw.size() > MAX_PARAMETER_COUNT)
        {
            throw new ServiceException("短信模板参数数量不合法", HttpStatus.BAD_REQUEST);
        }
        LinkedHashMap<String, String> checked = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet())
        {
            if (entry.getKey() == null || !PARAMETER_KEY_PATTERN.matcher(entry.getKey()).matches())
            {
                throw new ServiceException("短信模板参数名不合法", HttpStatus.BAD_REQUEST);
            }
            String value = entry.getValue();
            if (value == null || value.length() > MAX_PARAMETER_VALUE_LENGTH
                    || value.chars().anyMatch(Character::isISOControl))
            {
                throw new ServiceException("短信模板参数值不合法", HttpStatus.BAD_REQUEST);
            }
            checked.put(entry.getKey(), value);
        }
        return Map.copyOf(checked);
    }

    /**
     * 生成腾讯云 TC3-HMAC-SHA256 Authorization。
     *
     * @param config ActiveSmsConfig，正式腾讯云密钥
     * @param payload String，请求 JSON
     * @param timestamp long，UTC 秒级时间戳
     * @return String，Authorization 请求头
     * @throws Exception HMAC 算法不可用
     */
    private String tencentAuthorization(ActiveSmsConfig config, String payload, long timestamp) throws Exception
    {
        String date = LocalDate.ofInstant(Instant.ofEpochSecond(timestamp), ZoneOffset.UTC).toString();
        String canonicalHeaders = "content-type:application/json; charset=utf-8\n" +
                "host:sms.tencentcloudapi.com\n" + "x-tc-action:sendsms\n";
        String signedHeaders = "content-type;host;x-tc-action";
        String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + sha256(payload);
        String scope = date + "/sms/tc3_request";
        String stringToSign = "TC3-HMAC-SHA256\n" + timestamp + "\n" + scope + "\n" + sha256(canonicalRequest);
        byte[] secretDate = hmacSha256(("TC3" + config.accessKeySecret()).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmacSha256(secretDate, "sms");
        byte[] secretSigning = hmacSha256(secretService, "tc3_request");
        String signature = HexFormat.of().formatHex(hmacSha256(secretSigning, stringToSign));
        return "TC3-HMAC-SHA256 Credential=" + config.accessKeyId() + "/" + scope +
                ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }

    /**
     * 按 RFC 3986 规则编码阿里云 RPC 参数。
     *
     * @param value String，原始参数
     * @return String，签名规范编码
     */
    static String percentEncode(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    /**
     * 将有序参数映射序列化为阿里云 canonical query。
     *
     * @param values Map&lt;String,String&gt;，已经按 key 排序的参数
     * @return String，未含签名字段的表单正文
     */
    private static String formEncoded(Map<String, String> values)
    {
        List<String> parts = new ArrayList<>();
        values.forEach((key, value) -> parts.add(percentEncode(key) + "=" + percentEncode(value)));
        return String.join("&", parts);
    }

    /**
     * 计算 HMAC-SHA1 并进行 Base64 编码。
     *
     * @param key String，签名密钥
     * @param value String，待签名正文
     * @return String，Base64 签名
     * @throws Exception 算法不可用
     */
    private static String base64HmacSha1(String key, String value) throws Exception
    {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 计算 HMAC-SHA256。
     *
     * @param key byte[]，密钥
     * @param value String，待签名正文
     * @return byte[]，二进制摘要
     * @throws Exception 算法不可用
     */
    private static byte[] hmacSha256(byte[] key, String value) throws Exception
    {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 计算 SHA-256 小写十六进制摘要。
     *
     * @param value String，待摘要文本
     * @return String，64 位摘要
     * @throws Exception 算法不可用
     */
    private static String sha256(String value) throws Exception
    {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 将中国大陆手机号补齐腾讯云要求的 E.164 前缀。
     *
     * @param phone String，已校验手机号
     * @return String，腾讯云 PhoneNumberSet 值
     */
    private static String toTencentPhone(String phone)
    {
        return phone.startsWith("+") ? phone : "+86" + phone;
    }

    /**
     * 将腾讯云参数键转换为正整数排序位次。
     *
     * @param key String，模板参数键
     * @return int，排序位次
     */
    private static int numericKey(String key)
    {
        try
        {
            int value = Integer.parseInt(key);
            return value > 0 ? value : Integer.MAX_VALUE;
        }
        catch (NumberFormatException exception)
        {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * 从 JSON 对象读取可空文本。
     *
     * @param node JsonNode，父节点
     * @param field String，字段名
     * @return String，字段文本或空串
     */
    private static String text(JsonNode node, String field)
    {
        JsonNode value = node == null ? null : node.path(field);
        return value == null || value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    /**
     * 规范供应商错误码，防止超长或控制字符进入数据库。
     *
     * @param code String，供应商错误码
     * @param fallback String，缺失时稳定错误码
     * @return String，最多 96 字符的安全错误码
     */
    private static String safeCode(String code, String fallback)
    {
        if (!StringUtils.hasText(code) || code.chars().anyMatch(Character::isISOControl))
        {
            return fallback;
        }
        return code.length() > 96 ? code.substring(0, 96) : code;
    }

    /**
     * 对手机号做不可逆展示脱敏。
     *
     * @param phone String，已校验手机号
     * @return String，仅保留前三位和后四位
     */
    private static String maskPhone(String phone)
    {
        String prefix = phone.startsWith("+") ? "+" : "";
        String digits = phone.startsWith("+") ? phone.substring(1) : phone;
        if (digits.length() <= 7)
        {
            return prefix + "***" + digits.substring(Math.max(0, digits.length() - 2));
        }
        return prefix + digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
    }

    /**
     * 规范管理员账号。
     *
     * @param actor String，当前登录账号
     * @return String，最长 64 字符的非空账号
     */
    private static String normalizedActor(String actor)
    {
        return normalized(actor, 64, "短信操作账号不合法");
    }

    /**
     * 规范业务来源枚举。
     *
     * @param source String，业务调用方提供的来源
     * @return String，WORKFLOW 或受控来源
     */
    private static String normalizedSource(String source)
    {
        String value = normalized(source, 32, "短信业务来源不合法").toUpperCase(Locale.ROOT);
        if (!Pattern.matches("[A-Z][A-Z0-9_]{0,31}", value))
        {
            throw new ServiceException("短信业务来源不合法", HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    /**
     * 规范必填文本并拒绝控制字符。
     *
     * @param value String，原始文本
     * @param max int，最大字符数
     * @param message String，校验失败提示
     * @return String，去除首尾空白的文本
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
            throw new ServiceException("短信配置文本不合法", HttpStatus.BAD_REQUEST);
        }
        return result;
    }

    /** 短信配置校验后的内部不可变投影。 */
    private record ValidatedSmsConfig(String configName, String provider, String accessKeyId,
            String accessKeySecret, String signName, String sdkAppId, String region, String remark)
    {
    }

    /** 启用配置内部投影，密钥不得离开服务边界。 */
    private record ActiveSmsConfig(long configId, String provider, String accessKeyId,
            String accessKeySecret, String signName, String sdkAppId, String region)
    {
    }

    /**
     * 短信供应商投递结果，不携带手机号、密钥、正文或原始响应。
     *
     * @param success boolean，供应商是否接受全部号码请求
     * @param providerRequestId String，供应商请求追踪号
     * @param errorCode String，稳定失败码
     * @param summary String，脱敏结果摘要
     * @param logId Long，正式发送日志主键
     */
    public record SmsDeliveryResult(boolean success, String providerRequestId,
            String errorCode, String summary, Long logId)
    {
        /**
         * 创建成功结果。
         *
         * @param requestId String，供应商请求号
         * @return SmsDeliveryResult，成功结果
         */
        static SmsDeliveryResult delivered(String requestId)
        {
            return new SmsDeliveryResult(true, requestId, null, "短信供应商已接受请求", null);
        }

        /**
         * 创建无请求号失败结果。
         *
         * @param code String，稳定失败码
         * @param summary String，脱敏失败摘要
         * @return SmsDeliveryResult，失败结果
         */
        static SmsDeliveryResult failed(String code, String summary)
        {
            return failed(code, summary, null);
        }

        /**
         * 创建带供应商请求号的失败结果。
         *
         * @param code String，稳定失败码
         * @param summary String，脱敏失败摘要
         * @param requestId String，供应商请求号
         * @return SmsDeliveryResult，失败结果
         */
        static SmsDeliveryResult failed(String code, String summary, String requestId)
        {
            return new SmsDeliveryResult(false, requestId, code, summary, null);
        }

        /**
         * 关联正式发送日志主键。
         *
         * @param value long，日志主键
         * @return SmsDeliveryResult，带日志主键的新结果
         */
        SmsDeliveryResult withLogId(long value)
        {
            return new SmsDeliveryResult(success, providerRequestId, errorCode, summary, value);
        }
    }
}
