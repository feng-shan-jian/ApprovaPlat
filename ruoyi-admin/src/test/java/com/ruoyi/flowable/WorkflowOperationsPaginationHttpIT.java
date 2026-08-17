package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.system.service.ISysConfigService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 通过真实 MySQL、Spring Security 和 HTTP 验证七组工作流运维列表可访问超过 1000 条的旧记录。
 */
@SpringBootTest(classes = RuoYiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.druid.master.url=${FLOWABLE_RBAC_JDBC_URL}",
            "spring.datasource.druid.master.username=${FLOWABLE_RBAC_DB_USERNAME}",
            "spring.datasource.druid.master.password=${FLOWABLE_RBAC_DB_PASSWORD}",
            "spring.datasource.druid.stat-view-servlet.enabled=false",
            "spring.datasource.druid.web-stat-filter.enabled=false",
            "spring.data.redis.host=${FLOWABLE_RBAC_REDIS_HOST}",
            "spring.data.redis.port=${FLOWABLE_RBAC_REDIS_PORT}",
            "spring.data.redis.password=${FLOWABLE_RBAC_REDIS_PASSWORD:}",
            "spring.data.redis.database=${FLOWABLE_RBAC_REDIS_DATABASE}",
            "token.secret=d29ya2Zsb3ctb3BlcmF0aW9ucy1wYWdpbmF0aW9uLWl0LXNlY3JldC13b3JrZmxvdy1vcGVyYXRpb25zLXBhZ2luYXRpb24taXQtc2VjcmV0",
            "flowable.operations-pagination.accounts-registered=${FLOWABLE_RBAC_ACCOUNTS_REGISTERED:false}",
            "flowable.database-schema-update=false",
            "flowable.async-executor-activate=false",
            "flowable.async-history-executor-activate=false",
            "spring.quartz.auto-startup=false",
            "spring.task.scheduling.enabled=false",
            "ruoyi.profile=target/workflow-operations-pagination/profile",
            "logging.level.com.ruoyi=warn"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkflowOperationsPaginationHttpIT
{
    /** 每个领域超过旧固定 1000 条截断边界的正式测试记录数。 */
    private static final int ROW_COUNT = 1005;
    /** 访问旧记录时使用服务端允许的最大页大小。 */
    private static final int OLD_PAGE_SIZE = 100;
    /** 第 11 页从第 1001 条开始，直接覆盖旧截断边界。 */
    private static final int OLD_PAGE_NUM = 11;
    /** 真实 HTTP 连接和响应读取总超时。 */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    /** 七组记录共用同一毫秒时间，确保稳定主键次排序可被直接验证。 */
    private static final LocalDateTime FIXTURE_TIME =
            LocalDateTime.of(2026, 8, 16, 10, 30, 0);
    /** 测试数据统一业务前缀，删除语句不得越过该边界。 */
    private static final String PREFIX = "workflow-pagination-http-it-";

    @LocalServerPort
    private int serverPort;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ISysConfigService sysConfigService;
    @Value("${flowable.operations-pagination.accounts-registered}")
    private boolean accountsRegistered;

    /** 本轮唯一短标识，用于数据库关键字筛选和精确清理。 */
    private final String runId = UUID.randomUUID().toString().replace("-", "")
            .substring(0, 12);
    private final ObjectMapper objectMapper = JsonMapper.shared();
    private HttpClient httpClient;
    private String adminToken;
    private String originalCaptchaEnabled;
    private String fixturePrefix;
    private String channelId;
    private Long credentialId;
    private Long endpointId;
    private long adminUserId;

    /**
     * 核对 94/27 目标结构和真实管理员账号，创建七组分页数据及其必要父对象。
     * @return void，环境、登录、外键父对象或批量数据不完整时整类测试失败
     * @throws Exception HTTP、摘要计算或数据库操作失败时抛出
     */
    @BeforeAll
    void prepareEnvironment() throws Exception
    {
        assertThat(accountsRegistered)
                .as("真实分页 HTTP IT 必须先完成工作流 RBAC 账号登记")
                .isTrue();
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema=database() and table_type='BASE TABLE'",
                Integer.class)).isEqualTo(94);
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema=database() and table_name like 'wf\\_%'",
                Integer.class)).isEqualTo(27);

        fixturePrefix = PREFIX + runId;
        httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        originalCaptchaEnabled = jdbc.queryForObject(
                "select config_value from sys_config where config_key=?",
                String.class, "sys.account.captchaEnabled");
        assertThat(jdbc.update(
                "update sys_config set config_value='false' where config_key=?",
                "sys.account.captchaEnabled")).isEqualTo(1);
        sysConfigService.resetConfigCache();

        String adminUsername = requireEnvironment("FLOWABLE_RBAC_WORKFLOW_ADMIN_USERNAME");
        adminToken = login(adminUsername);
        adminUserId = requireEnabledAdmin(adminUsername);

        createIntegrationParents();
        insertBpmnEventAudits();
        insertCollaborationRows();
        insertRuntimeEventRequests();
        insertSlaRows();
        insertNotificationOutboxRows();
    }

    /**
     * 按外键逆序删除本轮前缀数据、注销登录态并恢复验证码配置。
     * @return void，任何本轮数据残留或配置未恢复时测试失败
     * @throws Exception 注销 HTTP 请求失败时抛出
     */
    @AfterAll
    void cleanup() throws Exception
    {
        if (fixturePrefix != null)
        {
            jdbc.update("delete from wf_notification_outbox where process_instance_id like ?",
                    fixturePrefix + "%");
            jdbc.update("delete a from wf_task_sla_audit a "
                    + "join wf_task_sla_execution e on e.sla_execution_id=a.sla_execution_id "
                    + "where e.process_instance_id like ?", fixturePrefix + "%");
            jdbc.update("delete from wf_task_sla_execution where process_instance_id like ?",
                    fixturePrefix + "%");
            jdbc.update("delete from wf_bpmn_event_audit where deployment_id like ?",
                    fixturePrefix + "%");
        }
        if (channelId != null)
        {
            jdbc.update("delete from wf_collaboration_message where channel_id=?", channelId);
            jdbc.update("delete from wf_collaboration_outbox where channel_id=?", channelId);
            jdbc.update("delete from wf_collaboration_channel where channel_id=?", channelId);
        }
        if (credentialId != null)
        {
            jdbc.update("delete from wf_runtime_event_request where credential_id=?", credentialId);
        }
        if (endpointId != null)
        {
            jdbc.update("delete from wf_connector_endpoint where endpoint_id=?", endpointId);
        }
        if (credentialId != null)
        {
            jdbc.update("delete from wf_integration_credential where credential_id=?", credentialId);
        }

        try
        {
            if (adminToken != null)
            {
                requireCode(jsonRequest("POST", "/logout", adminToken, null), 200);
            }
        }
        finally
        {
            if (originalCaptchaEnabled != null)
            {
                jdbc.update("update sys_config set config_value=? where config_key=?",
                        originalCaptchaEnabled, "sys.account.captchaEnabled");
                sysConfigService.resetConfigCache();
            }
        }

        assertThat(jdbc.queryForObject("select count(*) from wf_bpmn_event_audit "
                + "where deployment_id like ?", Integer.class, fixturePrefix + "%")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from wf_task_sla_execution "
                + "where process_instance_id like ?", Integer.class, fixturePrefix + "%")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from wf_notification_outbox "
                + "where process_instance_id like ?", Integer.class, fixturePrefix + "%")).isZero();
        if (credentialId != null)
        {
            assertThat(jdbc.queryForObject("select count(*) from wf_integration_credential "
                    + "where credential_id=?", Integer.class, credentialId)).isZero();
        }
    }

    /**
     * 验证七个真实 HTTP 运维入口的页码、页大小、总数、旧页、稳定排序及组合筛选。
     * @return void，任何入口在 1000 条边界后不可达或 rows/total 漂移时测试失败
     * @throws Exception HTTP 或 JSON 处理失败时抛出
     */
    @Test
    void exposesOldRecordsWithStableFilteredPhysicalPagination() throws Exception
    {
        String timeRange = timeRangeQuery();
        assertPagedEndpoint(
                "/workflow/bpmn-event/audit?status=CAPTURED&eventType=ERROR"
                        + "&sourceType=HTTP&keyword=" + encode(fixturePrefix) + timeRange,
                "/workflow/bpmn-event/audit?status=UNMATCHED&keyword="
                        + encode(fixturePrefix),
                "auditId", expectedIds("select cast(audit_id as char) "
                        + "from wf_bpmn_event_audit where deployment_id like ? "
                        + "order by create_time desc,audit_id desc", fixturePrefix + "%"));

        assertPagedEndpoint(
                "/workflow/collaboration/inbound?status=PROCESSED&keyword="
                        + encode(fixturePrefix) + timeRange,
                "/workflow/collaboration/inbound?status=DEAD_LETTER&keyword="
                        + encode(fixturePrefix),
                "messageId", expectedIds("select message_id from wf_collaboration_message "
                        + "where channel_id=? order by create_time desc,message_id desc", channelId));

        assertPagedEndpoint(
                "/workflow/collaboration/outbox?status=PROCESSED&keyword="
                        + encode(fixturePrefix) + timeRange,
                "/workflow/collaboration/outbox?status=CANCELLED&keyword="
                        + encode(fixturePrefix),
                "messageId", expectedIds("select message_id from wf_collaboration_outbox "
                        + "where channel_id=? order by create_time desc,message_id desc", channelId));

        assertPagedEndpoint(
                "/workflow/runtime-event-audit/list?status=PROCESSED&eventType=MESSAGE"
                        + "&sourceType=BUSINESS_KEY&keyword=" + encode(fixturePrefix) + timeRange,
                "/workflow/runtime-event-audit/list?status=FAILED&keyword="
                        + encode(fixturePrefix),
                "requestId", expectedIds("select request_id from wf_runtime_event_request "
                        + "where credential_id=? order by create_time desc,request_id desc",
                        credentialId));

        assertPagedEndpoint(
                "/workflow/sla/executions?status=COMPLETED&keyword="
                        + encode(fixturePrefix) + timeRange,
                "/workflow/sla/executions?status=ACTIVE&keyword=" + encode(fixturePrefix),
                "slaExecutionId", expectedIds("select cast(sla_execution_id as char) "
                        + "from wf_task_sla_execution where process_instance_id like ? "
                        + "order by started_at desc,sla_execution_id desc", fixturePrefix + "%"));

        assertPagedEndpoint(
                "/workflow/sla/audits?actionType=COMPLETE&keyword="
                        + encode(fixturePrefix) + timeRange,
                "/workflow/sla/audits?actionType=PAUSE&keyword=" + encode(fixturePrefix),
                "auditId", expectedIds("select cast(a.audit_id as char) "
                        + "from wf_task_sla_audit a join wf_task_sla_execution e "
                        + "on e.sla_execution_id=a.sla_execution_id "
                        + "where e.process_instance_id like ? "
                        + "order by a.create_time desc,a.audit_id desc", fixturePrefix + "%"));

        assertPagedEndpoint(
                "/workflow/notification/outbox?status=PROCESSED&sourceType=APPROVAL"
                        + "&eventType=TASK_ARRIVED&channel=INBOX&keyword="
                        + encode(fixturePrefix) + timeRange,
                "/workflow/notification/outbox?status=DEAD_LETTER&keyword="
                        + encode(fixturePrefix),
                "outboxId", expectedIds("select cast(outbox_id as char) "
                        + "from wf_notification_outbox where process_instance_id like ? "
                        + "order by create_time desc,outbox_id desc", fixturePrefix + "%"));
    }

    /**
     * 创建协作和运行事件批量数据依赖的凭据、HTTP 端点与顺序通道。
     * @return void，三个父对象均成功落库且主键可回查
     * @throws Exception SHA-256 计算失败时抛出
     */
    private void createIntegrationParents() throws Exception
    {
        String tokenPrefix = ("pg" + runId).substring(0, 12);
        assertThat(jdbc.update(
                "insert into wf_integration_credential(credential_name,token_prefix,token_hash,"
                        + "scopes,allowed_variables,rate_limit_per_minute,create_by,create_time) "
                        + "values(?,?,?,'MESSAGE','',10000,?,?)",
                fixturePrefix + "-credential", tokenPrefix, sha256(fixturePrefix + "-token"),
                String.valueOf(adminUserId), Timestamp.valueOf(FIXTURE_TIME))).isOne();
        credentialId = jdbc.queryForObject(
                "select credential_id from wf_integration_credential where token_prefix=?",
                Long.class, tokenPrefix);

        String endpointKey = "pagination.endpoint." + runId;
        assertThat(jdbc.update(
                "insert into wf_connector_endpoint(endpoint_key,endpoint_name,base_url,"
                        + "allowed_methods,path_prefix,auth_type,connect_timeout_ms,"
                        + "request_timeout_ms,network_scope,revision_no,status,checksum,"
                        + "create_by,create_time) "
                        + "values(?,?,'http://127.0.0.1','POST','/workflow/runtime-event',"
                        + "'NONE',1000,5000,'PRIVATE',1,'ENABLED',?,?,?)",
                endpointKey, fixturePrefix + "-endpoint", sha256(endpointKey),
                String.valueOf(adminUserId), Timestamp.valueOf(FIXTURE_TIME))).isOne();
        endpointId = jdbc.queryForObject(
                "select endpoint_id from wf_connector_endpoint where endpoint_key=?",
                Long.class, endpointKey);

        channelId = sha256(fixturePrefix + "-channel");
        assertThat(jdbc.update(
                "insert into wf_collaboration_channel(channel_id,target_process_definition_key,"
                        + "correlation_type,correlation_value,outbound_sequence,inbound_sequence,"
                        + "revision_no,create_time) values(?,?,'BUSINESS_KEY',?,?,?,0,?)",
                channelId, fixturePrefix + "-target", fixturePrefix + "-correlation",
                ROW_COUNT, ROW_COUNT, Timestamp.valueOf(FIXTURE_TIME))).isOne();
    }

    /**
     * 批量写入 1005 条同时间戳 BPMN 事件审计，强制列表依赖 audit_id 次排序。
     * @return void，所有记录均满足 CAPTURED/ERROR/HTTP 组合筛选
     * @throws Exception SHA-256 计算失败时抛出
     */
    private void insertBpmnEventAudits() throws Exception
    {
        List<Object[]> rows = new ArrayList<>(ROW_COUNT);
        for (int index = 1; index <= ROW_COUNT; index++)
        {
            rows.add(new Object[] {
                    sha256(fixturePrefix + "-bpmn-" + index), fixturePrefix + "-deployment",
                    fixturePrefix + "-process-" + index, fixturePrefix + "-definition",
                    fixturePrefix + "-execution-" + index, fixturePrefix + "-element-" + index,
                    "HTTP", "ERROR", "PAGINATION_ERROR", "分页错误事件", "CAPTURED",
                    fixturePrefix + "-boundary", true, "分页审计", String.valueOf(adminUserId),
                    Timestamp.valueOf(FIXTURE_TIME)
            });
        }
        assertBatchCount(jdbc.batchUpdate(
                "insert into wf_bpmn_event_audit(idempotency_key,deployment_id,"
                        + "process_instance_id,process_definition_id,execution_id,source_element_id,"
                        + "source_type,event_type,event_code,event_name,match_status,boundary_event_id,"
                        + "interrupting,message_summary,initiator_user_id,create_time) "
                        + "values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", rows), "BPMN 事件审计");
    }

    /**
     * 批量写入 1005 条协作 inbound 和 1005 条 outbox，同一时间戳下按 message_id 稳定排序。
     * @return void，两组记录共享本轮通道但拥有独立连续序号
     * @throws Exception SHA-256 计算失败时抛出
     */
    private void insertCollaborationRows() throws Exception
    {
        List<Object[]> inboundRows = new ArrayList<>(ROW_COUNT);
        List<Object[]> outboxRows = new ArrayList<>(ROW_COUNT);
        Timestamp fixtureTimestamp = Timestamp.valueOf(FIXTURE_TIME);
        for (int index = 1; index <= ROW_COUNT; index++)
        {
            inboundRows.add(new Object[] {
                    deterministicUuid("inbound-" + index), credentialId,
                    String.valueOf(adminUserId), channelId, index,
                    fixturePrefix + "-message", fixturePrefix + "-source",
                    fixturePrefix + "-target", fixturePrefix + "-correlation", "{}",
                    sha256(fixturePrefix + "-inbound-payload-" + index),
                    fixtureTimestamp, fixtureTimestamp
            });
            outboxRows.add(new Object[] {
                    deterministicUuid("outbox-" + index), channelId, index,
                    fixturePrefix + "-source", fixturePrefix + "-source-instance-" + index,
                    fixturePrefix + "-source-execution-" + index,
                    fixturePrefix + "-source-element-" + index, fixturePrefix + "-message",
                    fixturePrefix + "-target", fixturePrefix + "-correlation", endpointId,
                    "/workflow/runtime-event/collaboration/message", "{}", "{}",
                    sha256(fixturePrefix + "-outbox-payload-" + index),
                    fixtureTimestamp, fixtureTimestamp, fixtureTimestamp
            });
        }
        assertBatchCount(jdbc.batchUpdate(
                "insert into wf_collaboration_message(message_id,credential_id,actor_user_id,"
                        + "channel_id,sequence_no,message_name,source_process_definition_key,"
                        + "target_process_definition_key,correlation_key,variables_json,payload_sha256,"
                        + "status,attempt_count,max_attempts,complete_time,create_time) "
                        + "values(?,?,?,?,?,?,?,?,?,?,?,'PROCESSED',1,5,?,?)", inboundRows),
                "协作 inbound");
        assertBatchCount(jdbc.batchUpdate(
                "insert into wf_collaboration_outbox(message_id,channel_id,sequence_no,"
                        + "source_process_definition_key,source_process_instance_id,source_execution_id,"
                        + "source_element_id,message_name,target_process_definition_key,correlation_key,"
                        + "endpoint_id,endpoint_revision,request_path,delivery_config_json,variables_json,"
                        + "payload_sha256,status,attempt_count,max_attempts,next_attempt_time,complete_time,"
                        + "create_time) values(?,?,?,?,?,?,?,?,?,?,?,1,?,?,?,?,'PROCESSED',1,5,?,?,?)",
                outboxRows), "协作 outbox");
    }

    /**
     * 批量写入 1005 条已完成运行事件请求，同一时间戳下按 UUID 主键稳定排序。
     * @return void，所有记录均满足 PROCESSED/MESSAGE/BUSINESS_KEY 组合筛选
     * @throws Exception SHA-256 计算失败时抛出
     */
    private void insertRuntimeEventRequests() throws Exception
    {
        List<Object[]> rows = new ArrayList<>(ROW_COUNT);
        Timestamp fixtureTimestamp = Timestamp.valueOf(FIXTURE_TIME);
        for (int index = 1; index <= ROW_COUNT; index++)
        {
            rows.add(new Object[] {
                    deterministicUuid("runtime-" + index), credentialId,
                    fixturePrefix + "-runtime-message", fixturePrefix + "-business-" + index,
                    sha256(fixturePrefix + "-runtime-payload-" + index),
                    fixturePrefix + "-matched-" + index, fixturePrefix + "-execution-" + index,
                    "WORKFLOW_EVENT_PROCESSED", "分页运行事件已处理",
                    fixtureTimestamp, fixtureTimestamp
            });
        }
        assertBatchCount(jdbc.batchUpdate(
                "insert into wf_runtime_event_request(request_id,credential_id,event_type,event_name,"
                        + "correlation_type,correlation_value,variables_sha256,"
                        + "matched_process_instance_id,matched_execution_id,status,result_code,"
                        + "result_summary,create_time,complete_time) "
                        + "values(?,?,'MESSAGE',?,'BUSINESS_KEY',?,?,?,?,'PROCESSED',?,?,?,?)",
                rows), "运行事件请求");
    }

    /**
     * 批量写入 1005 条 SLA execution，并为每条 execution 写入一条 COMPLETE 审计。
     * @return void，execution 和 audit 均使用同一时间戳并可按测试前缀精确清理
     */
    private void insertSlaRows()
    {
        List<Object[]> executions = new ArrayList<>(ROW_COUNT);
        Timestamp startedAt = Timestamp.valueOf(FIXTURE_TIME);
        Timestamp reminderAt = Timestamp.valueOf(FIXTURE_TIME.plusHours(1));
        Timestamp escalationAt = Timestamp.valueOf(FIXTURE_TIME.plusHours(2));
        for (int index = 1; index <= ROW_COUNT; index++)
        {
            executions.add(new Object[] {
                    fixturePrefix + "-deployment", fixturePrefix + "-process-" + index,
                    fixturePrefix + "-definition", fixturePrefix + "-task-" + index,
                    fixturePrefix + "-task-definition", String.valueOf(adminUserId),
                    startedAt, reminderAt, escalationAt, startedAt
            });
        }
        assertBatchCount(jdbc.batchUpdate(
                "insert into wf_task_sla_execution(deployment_id,process_instance_id,"
                        + "process_definition_id,task_id,task_definition_key,assignee_user_id,status,"
                        + "started_at,reminder_due_at,escalation_due_at,reminders_sent,paused_millis,"
                        + "revision,update_time) values(?,?,?,?,?,?,'COMPLETED',?,?,?,0,0,0,?)",
                executions), "SLA execution");

        List<Long> executionIds = jdbc.queryForList(
                "select sla_execution_id from wf_task_sla_execution "
                        + "where process_instance_id like ? order by sla_execution_id",
                Long.class, fixturePrefix + "%");
        assertThat(executionIds).hasSize(ROW_COUNT);
        List<Object[]> audits = new ArrayList<>(ROW_COUNT);
        for (Long executionId : executionIds)
        {
            audits.add(new Object[] { executionId, String.valueOf(adminUserId),
                    fixturePrefix + "-complete", startedAt });
        }
        assertBatchCount(jdbc.batchUpdate(
                "insert into wf_task_sla_audit(sla_execution_id,action_type,action_ordinal,"
                        + "actor_user_id,detail,create_time) values(?,'COMPLETE',0,?,?,?)", audits),
                "SLA audit");
    }

    /**
     * 批量写入 1005 条已处理通知 outbox，验证通知管理页不再依赖最近记录截断。
     * @return void，所有记录均满足 APPROVAL/TASK_ARRIVED/INBOX/PROCESSED 组合筛选
     * @throws Exception SHA-256 计算失败时抛出
     */
    private void insertNotificationOutboxRows() throws Exception
    {
        List<Object[]> rows = new ArrayList<>(ROW_COUNT);
        Timestamp fixtureTimestamp = Timestamp.valueOf(FIXTURE_TIME);
        for (int index = 1; index <= ROW_COUNT; index++)
        {
            rows.add(new Object[] {
                    sha256(fixturePrefix + "-notification-" + index),
                    fixturePrefix + "-notification-source-" + index, adminUserId,
                    fixturePrefix + "-definition", fixturePrefix + "-npi-" + index,
                    fixturePrefix + "-nt-" + index,
                    fixturePrefix + "-ntd", String.valueOf(adminUserId),
                    "分页通知 " + index, "通知旧记录分页验收", "/workflow/process/detail/"
                            + fixturePrefix + "-npi-" + index,
                    fixtureTimestamp, fixtureTimestamp, fixtureTimestamp
            });
        }
        assertBatchCount(jdbc.batchUpdate(
                "insert into wf_notification_outbox(idempotency_key,source_type,source_id,event_type,"
                        + "channel,recipient_user_id,process_definition_key,process_instance_id,task_id,"
                        + "task_definition_key,actor_user_id,title,content,route_path,status,attempt_count,"
                        + "total_attempt_count,max_attempts,next_attempt_at,create_time,processed_time) "
                        + "values(?,'APPROVAL',?,'TASK_ARRIVED','INBOX',?,?,?,?,?,?,?, ?,?,"
                        + "'PROCESSED',1,1,5,?,?,?)",
                rows), "通知 outbox");
    }

    /**
     * 对一个真实 HTTP 列表同时验证首页页大小、1000 条边界后的旧页、稳定排序和反向筛选。
     * @param filteredPath String，已包含领域组合筛选的 URL
     * @param mismatchPath String，应返回零记录的合法反向筛选 URL
     * @param idField String，响应 rows 中的稳定主键字段
     * @param expectedIds List&lt;String&gt;，真实 MySQL 按正式排序返回的全部主键
     * @return void，任何分页或筛选结果不一致时断言失败
     * @throws Exception HTTP 或 JSON 处理失败时抛出
     */
    private void assertPagedEndpoint(String filteredPath, String mismatchPath,
            String idField, List<String> expectedIds) throws Exception
    {
        assertThat(expectedIds).hasSize(ROW_COUNT);
        JsonNode firstPage = requireCode(jsonRequest("GET",
                filteredPath + "&pageNum=1&pageSize=37", adminToken, null), 200);
        assertThat(firstPage.path("total").longValue()).isEqualTo(ROW_COUNT);
        assertRowIds(firstPage, idField, expectedIds.subList(0, 37));

        JsonNode oldPage = requireCode(jsonRequest("GET",
                filteredPath + "&pageNum=" + OLD_PAGE_NUM + "&pageSize=" + OLD_PAGE_SIZE,
                adminToken, null), 200);
        assertThat(oldPage.path("total").longValue()).isEqualTo(ROW_COUNT);
        assertRowIds(oldPage, idField, expectedIds.subList(1000, ROW_COUNT));

        JsonNode mismatch = requireCode(jsonRequest("GET",
                mismatchPath + "&pageNum=1&pageSize=20", adminToken, null), 200);
        assertThat(mismatch.path("total").longValue()).isZero();
        assertThat(mismatch.path("rows").isArray()).isTrue();
        assertThat(mismatch.path("rows").size()).isZero();
    }

    /**
     * 核对响应当前页主键与 MySQL 正式排序切片完全一致。
     * @param response JsonNode，若依 TableDataInfo 响应
     * @param idField String，rows 中稳定主键字段
     * @param expectedIds List&lt;String&gt;，当前页期望主键顺序
     * @return void，缺行、重复、错序或跨页漂移时断言失败
     */
    private void assertRowIds(JsonNode response, String idField, List<String> expectedIds)
    {
        assertThat(response.path("rows").isArray()).isTrue();
        List<String> actualIds = new ArrayList<>();
        response.path("rows").forEach(row -> actualIds.add(row.path(idField).asText()));
        assertThat(actualIds).containsExactlyElementsOf(expectedIds);
    }

    /**
     * 查询真实 MySQL 中本轮数据的正式排序主键。
     * @param sql String，只包含一个前缀或父主键参数的只读 SQL
     * @param parameter Object，本轮隔离参数
     * @return List&lt;String&gt;，数据库排序后的全部主键文本
     */
    private List<String> expectedIds(String sql, Object parameter)
    {
        return jdbc.queryForList(sql, String.class, parameter);
    }

    /**
     * 断言 JdbcTemplate 批量写入每一行均真实影响一条记录。
     * @param counts int[]，JDBC 每条语句影响行数
     * @param domain String，失败时使用的领域名称
     * @return void，影响行数不是预期总量时断言失败
     */
    private void assertBatchCount(int[] counts, String domain)
    {
        assertThat(counts).as(domain + " 批次返回数").hasSize(ROW_COUNT);
        for (int count : counts)
        {
            // MySQL 驱动可返回精确影响行数或 JDBC SUCCESS_NO_INFO，两者都表示该语句已成功执行。
            assertThat(count).as(domain + " 单条批次结果")
                    .isIn(1, Statement.SUCCESS_NO_INFO);
        }
    }

    /**
     * 通过真实登录入口创建管理员 Redis 登录态。
     * @param username String，已登记工作流管理员用户名
     * @return String，服务端签发 JWT
     * @throws Exception HTTP 或 JSON 处理失败时抛出
     */
    private String login(String username) throws Exception
    {
        String password = requireEnvironment("FLOWABLE_RBAC_WORKFLOW_ADMIN_PASSWORD");
        JsonNode response = requireCode(jsonRequest("POST", "/login", null,
                objectMapper.createObjectNode().put("username", username)
                        .put("password", password).put("code", "").put("uuid", "")
                        .toString()), 200);
        String token = response.path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    /**
     * 核对登录账号为启用状态且只绑定 workflow_admin 工作流角色。
     * @param username String，管理员用户名
     * @return long，正式用户主键
     */
    private long requireEnabledAdmin(String username)
    {
        List<Long> userIds = jdbc.queryForList(
                "select user_id from sys_user where user_name=? and status='0' and del_flag='0'",
                Long.class, username);
        assertThat(userIds).singleElement();
        long userId = userIds.get(0);
        assertThat(jdbc.queryForList(
                "select r.role_key from sys_role r join sys_user_role ur on ur.role_id=r.role_id "
                        + "where ur.user_id=? and r.role_key like 'workflow_%'",
                String.class, userId)).containsExactly("workflow_admin");
        return userId;
    }

    /**
     * 发送真实登录用户 HTTP 请求并解析统一 JSON 响应。
     * @param method String，GET 或 POST
     * @param path String，相对路径，可含查询参数
     * @param token String，可空 JWT
     * @param body String，可空 JSON 正文
     * @return JsonNode，服务端原始 JSON 响应
     * @throws IOException 网络或响应读取失败时抛出
     * @throws InterruptedException 当前线程被中断时抛出
     */
    private JsonNode jsonRequest(String method, String path, String token, String body)
            throws IOException, InterruptedException
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri(path))
                .timeout(HTTP_TIMEOUT).header("Accept", "application/json")
                .header("User-Agent", "workflow-operations-pagination-http-it");
        if (token != null)
        {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null)
        {
            builder.header("Content-Type", "application/json; charset=UTF-8");
        }
        HttpRequest request = "GET".equals(method) ? builder.GET().build()
                : builder.POST(body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    /**
     * 断言若依统一业务码并返回原响应。
     * @param response JsonNode，服务端 JSON 响应
     * @param expectedCode int，期望业务码
     * @return JsonNode，原响应
     */
    private JsonNode requireCode(JsonNode response, int expectedCode)
    {
        assertThat(response.path("code").asInt()).as("业务响应=%s", response)
                .isEqualTo(expectedCode);
        return response;
    }

    /**
     * 构造包含开始和结束时间的 URL 查询片段。
     * @return String，覆盖本轮固定时间的编码后参数
     */
    private String timeRangeQuery()
    {
        return "&beginTime=" + encode("2026-08-16 10:00:00")
                + "&endTime=" + encode("2026-08-16 11:00:00");
    }

    /**
     * 为批量台账生成满足 MySQL UUID CHECK 的稳定 UUID。
     * @param suffix String，本轮领域和序号组合
     * @return String，版本 3 标准小写 UUID
     */
    private String deterministicUuid(String suffix)
    {
        return UUID.nameUUIDFromBytes((fixturePrefix + "-" + suffix)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * 计算测试父键、幂等键和载荷摘要使用的 SHA-256。
     * @param value String，不含凭据正文的稳定测试文本
     * @return String，64 位小写十六进制摘要
     * @throws Exception 摘要算法不可用时抛出
     */
    private String sha256(String value) throws Exception
    {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 从当前进程读取非空强制环境变量。
     * @param name String，环境变量名
     * @return String，非空原值，调用方不得输出
     */
    private String requireEnvironment(String name)
    {
        String value = System.getenv(name);
        if (value == null || value.isBlank())
        {
            throw new AssertionError("缺少强制环境变量: " + name);
        }
        return value;
    }

    /**
     * 构造当前随机端口的回环 URI。
     * @param path String，以斜线开头的相对路径
     * @return URI，真实 Spring Boot HTTP 地址
     */
    private URI baseUri(String path)
    {
        return URI.create("http://127.0.0.1:" + serverPort + path);
    }

    /**
     * 对查询参数执行 UTF-8 URL 编码。
     * @param value String，查询值
     * @return String，编码后文本
     */
    private String encode(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
