package com.ruoyi.flowable.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.vo.WorkflowNotificationItem;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WorkflowIdentityMapper;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;
import com.ruoyi.flowable.testsupport.WorkflowMySqlITSupport;

/** 正式 MySQL 表、身份 Mapper 和事务代理验证收件箱；JDBC 日志只辅助证明重复 SQL 已删除。 */
class WorkflowNotificationInboxMySqlIT
{
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 9, 1, 10, 0);
    private JdbcTemplate jdbc;
    private WorkflowNotificationInboxService inbox;
    private long userId;
    private Logger jdbcLogger;
    private Level previousLevel;
    private final ListAppender<ILoggingEvent> sqlLog = new ListAppender<>();

    @BeforeEach
    void setUp() throws Exception
    {
        var source = WorkflowMySqlITSupport.createDataSource();
        WorkflowMySqlITSupport.verifyIsolatedBaseline(source, "通知收件箱 IT", "approvaplat_it",
                List.of("sys_user", "sys_role", "sys_dept", "sys_user_role", "wf_notification_inbox"));
        jdbc = new JdbcTemplate(source);
        userId = 60_000_000_000L + Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000L) * 10;
        for (long id : List.of(userId, userId + 1, userId + 2))
        {
            jdbc.update("insert into sys_user(user_id,user_name,nick_name,status,del_flag) values(?,?,?,'0','0')",
                    id, "l06_inbox_" + id, "Inbox test");
        }
        // 两个用户的主键交错，第三个用户为空；期望行和计数均由夹具显式给出。
        insert(1, userId, "UNREAD");
        insert(2, userId + 1, "UNREAD");
        insert(3, userId, "READ");
        insert(4, userId, "UNREAD");
        insert(5, userId + 1, "READ");
        insert(6, userId, "READ");
        insert(7, userId, "UNREAD");
        var mapper = WorkflowH2SchemaMapperSupport.createSpringMapper(source, "notification-inbox",
                WorkflowIdentityMapper.class, "mapper/flowable/WorkflowIdentityMapper.xml");
        inbox = WorkflowMySqlITSupport.transactionalProxy(new WorkflowNotificationInboxService(jdbc,
                new WorkflowIdentityResolver(mapper, new WorkflowIdentityCodec())),
                new DataSourceTransactionManager(source));
        login(userId);
        jdbcLogger = (Logger) LoggerFactory.getLogger(JdbcTemplate.class);
        previousLevel = jdbcLogger.getLevel();
        jdbcLogger.setLevel(Level.DEBUG);
        sqlLog.start();
        jdbcLogger.addAppender(sqlLog);
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
        if (jdbcLogger != null)
        {
            jdbcLogger.detachAppender(sqlLog);
            jdbcLogger.setLevel(previousLevel);
            sqlLog.stop();
        }
        if (jdbc != null && userId != 0)
        {
            jdbc.update("delete from wf_notification_inbox where recipient_user_id in (?,?,?)",
                    userId, userId + 1, userId + 2);
            jdbc.update("delete from sys_user where user_id in (?,?,?) and user_name like 'l06_inbox_%'",
                    userId, userId + 1, userId + 2);
            assertThat(jdbc.queryForObject("select count(*) from wf_notification_inbox where recipient_user_id in (?,?,?)",
                    Long.class, userId, userId + 1, userId + 2)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from sys_user where user_id in (?,?,?)",
                    Long.class, userId, userId + 1, userId + 2)).isZero();
        }
    }

    @Test
    void filtersKeepIndependentTotalsAndDescendingPhysicalPagesForCurrentUser()
    {
        assertPage("ALL", 1, 2, 5, 3, 2, 7, 6);
        assertPage("ALL", 2, 2, 5, 3, 2, 4, 3);
        assertPage("ALL", 3, 2, 5, 3, 2, 1);
        assertPage("UNREAD", 1, 2, 3, 3, 1, 7, 4);
        assertPage("UNREAD", 2, 2, 3, 3, 1, 1);
        assertPage("READ", 1, 1, 2, 3, 2, 6);
        assertPage("READ", 2, 1, 2, 3, 2, 3);
        login(userId + 1);
        assertPage("ALL", 1, 20, 2, 1, 2, 5, 2);
        assertPage("UNREAD", 1, 20, 1, 1, 1, 2);
        assertPage("READ", 1, 20, 1, 1, 2, 5);
    }

    @Test
    void statusNormalizationRetainsNullDefaultCaseAndWhitespaceBehavior()
    {
        assertPage(null, 1, 20, 5, 3, 2, 7, 6, 4, 3, 1);
        assertPage(" aLl \t", 1, 20, 5, 3, 2, 7, 6, 4, 3, 1);
        assertPage(" uNrEaD \n", 1, 20, 3, 3, 1, 7, 4, 1);
        assertPage(" rEaD ", 1, 20, 2, 3, 2, 6, 3);
        for (String invalid : List.of("", " \t", "UNKNOWN"))
        {
            assertThatThrownBy(() -> inbox.inbox(invalid, 1, 20))
                    .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(400));
        }
    }

    @Test
    void emptyAndOutOfRangePagesKeepCountsWithoutLoadingRows()
    {
        assertPage("ALL", 4, 2, 5, 3, 2);
        assertPage("UNREAD", 3, 2, 3, 3, 1);
        assertPage("READ", 2, 2, 2, 3, 2);
        assertPage("UNREAD", Integer.MAX_VALUE, 1, 3, 3, 1);
        login(userId + 2);
        assertPage("ALL", 1, 20, 0, 0, 2);
        assertPage("UNREAD", 1, 20, 0, 0, 1);
        assertPage("READ", 1, 20, 0, 0, 2);
    }

    @Test
    void pageParameterBoundariesRemainEnforced()
    {
        assertPage("UNREAD", 1, 100, 3, 3, 1, 7, 4, 1);
        for (int[] page : List.of(new int[] { 0, 20 }, new int[] { 1, 0 },
                new int[] { 1, 101 }, new int[] { Integer.MAX_VALUE, 2 }))
        {
            assertThatThrownBy(() -> inbox.inbox("UNREAD", page[0], page[1]))
                    .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(400));
        }
    }

    @Test
    void committedReadChangesAreRecountedOnEveryRequestAndStayUserScoped()
    {
        assertPage("UNREAD", 1, 20, 3, 3, 1, 7, 4, 1);
        assertThatThrownBy(() -> inbox.markRead(userId + 2))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(404));
        inbox.markRead(userId + 7);
        assertThat(jdbc.queryForObject("select read_time from wf_notification_inbox where notification_id=?",
                LocalDateTime.class, userId + 7)).isNotNull();
        assertPage("UNREAD", 1, 20, 2, 2, 1, 4, 1);
        assertPage("ALL", 1, 20, 5, 2, 2, 7, 6, 4, 3, 1);
        assertPage("READ", 1, 20, 3, 2, 2, 7, 6, 3);
        assertThat(inbox.markAllRead()).isEqualTo(2);
        assertPage("UNREAD", 1, 20, 0, 0, 1);
        assertPage("ALL", 1, 20, 5, 0, 2, 7, 6, 4, 3, 1);
        assertPage("READ", 1, 20, 5, 0, 2, 7, 6, 4, 3, 1);
        login(userId + 1);
        assertPage("UNREAD", 1, 20, 1, 1, 1, 2);
        assertPage("READ", 1, 20, 1, 1, 2, 5);
    }

    private void assertPage(String status, int number, int size, long total, long unread,
            int countQueries, Integer... expectedIds)
    {
        sqlLog.list.clear();
        var result = inbox.inbox(status, number, size);
        assertThat(result.total()).isEqualTo(total);
        assertThat(result.unreadCount()).isEqualTo(unread);
        assertThat(result.items()).extracting(WorkflowNotificationItem::notificationId)
                .containsExactly(java.util.Arrays.stream(expectedIds).map(id -> userId + id).toArray(Long[]::new));
        // 先断言真实数据库业务结果，再以 JDBC 执行日志辅助确认 count 和 LIMIT/OFFSET。
        var statements = sqlLog.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("Executing prepared SQL statement [")).toList();
        assertThat(statements.stream().filter(sql -> sql.contains("select count(*) from wf_notification_inbox")))
                .hasSize(countQueries);
        var rowQueries = statements.stream().filter(sql -> sql.contains("select notification_id,")).toList();
        assertThat(rowQueries).hasSize(expectedIds.length == 0 ? 0 : 1);
        if (!rowQueries.isEmpty())
        {
            assertThat(rowQueries.get(0)).contains("order by notification_id desc limit ? offset ?");
            var item = result.items().get(0);
            assertThat(item.eventType()).isEqualTo("TASK_ARRIVED");
            assertThat(item.title()).isEqualTo("Inbox title " + item.notificationId());
            assertThat(item.content()).isEqualTo("Inbox content");
            assertThat(item.processInstanceId()).isEqualTo("l06-process");
            assertThat(item.taskId()).isEqualTo("l06-task");
            assertThat(item.routePath()).isEqualTo("/workflow/process/detail/l06-process");
            assertThat(item.createTime()).isEqualTo(CREATED);
            if (item.readStatus().equals("UNREAD")) assertThat(item.readTime()).isNull();
            else assertThat(item.readTime()).isNotNull();
        }
    }

    private void insert(int offset, long recipient, String status)
    {
        long id = userId + offset;
        jdbc.update("insert into wf_notification_inbox (notification_id,notification_key,source_type,source_id,"
                + "recipient_user_id,event_type,title,content,process_instance_id,task_id,route_path,read_status,create_time,read_time) "
                + "values(?,sha2(?,256),'APPROVAL',?,?,'TASK_ARRIVED',?,'Inbox content',"
                + "'l06-process','l06-task','/workflow/process/detail/l06-process',?,?,?)",
                id, "l06-" + id, "l06-" + id, recipient, "Inbox title " + id, status, CREATED,
                "READ".equals(status) ? CREATED.plusMinutes(1) : null);
    }

    private void login(long id)
    {
        LoginUser user = new LoginUser();
        user.setUserId(id);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
