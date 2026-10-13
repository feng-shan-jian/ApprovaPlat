package com.ruoyi.flowable.service.notification;

import com.ruoyi.common.testsupport.WorkflowMysqlTargetGuard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.flowable.mapper.WorkflowIdentityMapper;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;

/** 真实 MySQL 检查候选关系、跨角色权限并集以及身份共享锁。 */
@EnabledIfEnvironmentVariable(named = "WORKFLOW_MYSQL_TEST_URL", matches = ".+")
class WorkflowUrgeIdentityMysqlTest
{
    private JdbcTemplate jdbc;
    private WorkflowIdentityMapper mapper;
    private TransactionTemplate transaction;
    private final long base = 7_000_000_000_000L + ThreadLocalRandom.current().nextLong(100_000_000L) * 10_000;
    private final String taskId = UUID.randomUUID().toString();
    private final List<Long> users = new ArrayList<>();
    private final List<String> links = new ArrayList<>();
    private final List<String> permissions = List.of("claimList", "claim", "todoList", "query", "approval");

    @BeforeEach
    void setUp()
    {
        String url = System.getenv("WORKFLOW_MYSQL_TEST_URL");
        WorkflowMysqlTargetGuard.verify(url);
        var source = new DriverManagerDataSource(url, System.getenv("WORKFLOW_MYSQL_TEST_USERNAME"),
                System.getenv("WORKFLOW_MYSQL_TEST_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        mapper = WorkflowH2SchemaMapperSupport.createSpringMapper(source, "urge", WorkflowIdentityMapper.class,
                "mapper/flowable/WorkflowIdentityMapper.xml");
        jdbc.update("insert into ACT_RU_TASK(ID_,REV_,STATE_) values(?,1,'created')", taskId);
        jdbc.update("insert into sys_dept(dept_id,dept_name,status,del_flag) values(?,'rule urge','0','0')", base);
        for (long id : List.of(base, base + 1))
            jdbc.update("insert into sys_role(role_id,role_name,role_key,role_sort,status,del_flag) values(?,?,?,1,'0','0')", id, "rule urge", "rule" + id);
        for (int i = 0; i < 5; i++)
        {
            jdbc.update("insert into sys_menu(menu_id,menu_name,perms,status) values(?,'rule urge',?,'0')", base + i,
                    "workflow:process:" + permissions.get(i));
            jdbc.update("insert into sys_role_menu(role_id,menu_id) values(?,?)", base + (i < 2 ? 0 : 1), base + i);
        }
        user(base); user(base + 1);
        link("1", null); link(Long.toString(base), null); link(null, "ROLE" + base);
    }

    @AfterEach
    void close()
    {
        if (jdbc == null) return;
        for (String id : links) jdbc.update("delete from ACT_RU_IDENTITYLINK where ID_=?", id);
        jdbc.update("delete from ACT_RU_TASK where ID_=?", taskId);
        jdbc.batchUpdate("delete from sys_user_role where user_id=?", users.stream().map(id -> new Object[]{id}).toList());
        jdbc.batchUpdate("delete from sys_user where user_id=?", users.stream().map(id -> new Object[]{id}).toList());
        for (long role : List.of(base, base + 1))
        {
            jdbc.update("delete from sys_role_menu where role_id=?", role);
            jdbc.update("delete from sys_role where role_id=?", role);
        }
        for (int i = 0; i < 5; i++) jdbc.update("delete from sys_menu where menu_id=?", base + i);
        jdbc.update("delete from sys_dept where dept_id=?", base);
    }

    @Test
    void candidateModesUseUnionOfAllEnabledRolesAndCurrentRelationships()
    {
        assertThat(mapper.selectClaimEligibleTaskCandidates(taskId, false)).containsExactly("1", Long.toString(base), Long.toString(base + 1));
        assertThat(mapper.selectClaimEligibleUserIdsByUserIds(List.of(base, base + 1))).containsExactly(base, base + 1);
        jdbc.update("update sys_role set status='1' where role_id=?", base + 1);
        assertThat(mapper.selectClaimEligibleTaskCandidates(taskId, false)).containsExactly("1");
        jdbc.update("update sys_role set status='0',del_flag='2' where role_id=?", base + 1);
        assertThat(mapper.selectClaimEligibleTaskCandidates(taskId, false)).containsExactly("1");
        jdbc.update("update sys_role set del_flag='0' where role_id=?", base + 1);
        jdbc.update("update sys_menu set status='1' where menu_id=?", base + 4);
        assertThat(mapper.selectClaimEligibleTaskCandidates(taskId, false)).containsExactly("1");
        jdbc.update("update sys_menu set status='0' where menu_id=?", base + 4);
        jdbc.update("delete from sys_user_role where user_id=? and role_id=?", base + 1, base + 1);
        assertThat(mapper.selectClaimEligibleTaskCandidates(taskId, false)).containsExactly("1", Long.toString(base));
        jdbc.update("insert into sys_user_role values(?,?)", base + 1, base + 1);
        jdbc.update("delete from ACT_RU_IDENTITYLINK where TASK_ID_=? and GROUP_ID_=?", taskId, "ROLE" + base);
        link(null, "DEPT" + base);
        assertThat(mapper.selectClaimEligibleTaskCandidates(taskId, false)).containsExactly("1", Long.toString(base), Long.toString(base + 1));
        jdbc.update("update sys_dept set status='1' where dept_id=?", base);
        assertThat(mapper.selectClaimEligibleTaskCandidates(taskId, false)).containsExactly("1", Long.toString(base));
        jdbc.update("update sys_user set del_flag='2' where user_id=?", base);
        assertThat(mapper.selectClaimEligibleTaskCandidates(taskId, false)).containsExactly("1");
    }

    @Test
    void lockingReadBlocksConcurrentUserRevocationUntilWriterFinishes() throws Exception
    {
        var worker = Executors.newSingleThreadExecutor();
        var attempted = new CountDownLatch(1);
        var pending = new java.util.concurrent.atomic.AtomicReference<Future<?>>();
        try
        {
            transaction.executeWithoutResult(status -> {
                assertThat(mapper.selectClaimEligibleTaskCandidates(taskId, true)).contains(Long.toString(base));
                pending.set(worker.submit(() -> {
                    attempted.countDown();
                    jdbc.update("update sys_user set status='1' where user_id=?", base);
                }));
                try { assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                assertThatThrownBy(() -> pending.get().get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            });
            pending.get().get(5, TimeUnit.SECONDS);
            assertThat(mapper.selectClaimEligibleTaskCandidates(taskId, false)).doesNotContain(Long.toString(base));
        }
        finally { worker.shutdownNow(); assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test
    void candidateReadRemainsOrderedAndBoundedAt2001()
    {
        // 2002 eligible synthetic users plus user 1 make the sentinel limit observable.
        transaction.executeWithoutResult(status -> {
            for (int i = 2; i < 2002; i++) user(base + i);
            link(null, "DEPT" + base);
            var candidates = mapper.selectClaimEligibleTaskCandidates(taskId, false);
            assertThat(candidates).hasSize(2001);
            assertThat(candidates.get(0)).isEqualTo("1");
            assertThat(candidates.get(2000)).isEqualTo(Long.toString(base + 1999));
            status.setRollbackOnly();
        });
    }

    private void user(long id)
    {
        users.add(id);
        jdbc.update("insert into sys_user(user_id,dept_id,user_name,nick_name,status,del_flag) values(?,?,?,'rule urge','0','0')",
                id, base, "rule" + id);
        for (long role : List.of(base, base + 1)) jdbc.update("insert into sys_user_role(user_id,role_id) values(?,?)", id, role);
    }

    private void link(String user, String group)
    {
        String id = UUID.randomUUID().toString(); links.add(id);
        jdbc.update("insert into ACT_RU_IDENTITYLINK(ID_,REV_,TYPE_,USER_ID_,GROUP_ID_,TASK_ID_) values(?,1,'candidate',?,?,?)",
                id, user, group, taskId);
    }
}
