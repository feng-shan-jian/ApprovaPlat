package com.ruoyi.flowable.service.process;

import java.util.Map;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WorkflowIdentityMapper;
import com.ruoyi.flowable.mapper.WorkflowMultiInstanceUserMapper;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.flowable.service.task.WorkflowTaskRevokeApplicationService;
import com.ruoyi.flowable.service.task.WorkflowTaskUnclaimPolicy;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;
import com.ruoyi.flowable.testsupport.WorkflowMySqlTestDatabase;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 独占随机 schema 中使用全部十四份正式基线，复用 H2 的列表业务判据。 */
class WorkflowWorkbenchProjectionMySqlIT extends WorkflowWorkbenchProjectionIntegrationTest
{
    private WorkflowMySqlTestDatabase database;

    @Override protected WorkflowFlowableEngineTestSupport startDatabase() throws Exception
    {
        database = new WorkflowMySqlTestDatabase("C06 workbench projection");
        return WorkflowFlowableEngineTestSupport.startOnExistingDatabase(database.dataSource(), Map.of());
    }

    @Override protected void closeDatabase()
    {
        if (database != null) database.close();
    }

    /** 同一个旧登录会话必须读取当前数据库权限与用户状态，不能靠替身证明列表授权。 */
    @Test
    void rechecksRealClaimPermissionsAndAccountStateForEveryListRequest()
    {
        var jdbc = support.jdbcTemplate();
        jdbc.update("insert into sys_role(role_id,role_name,role_key,role_sort,status,del_flag) values(700,'工作台角色','c06_workbench',0,'0','0')");
        jdbc.update("insert into sys_user_role(user_id,role_id) values(700,700)");
        String grant = "insert into sys_role_menu(role_id,menu_id) select 700,menu_id from sys_menu where perms in ('workflow:process:claimList','workflow:process:claim','workflow:process:todoList','workflow:process:query','workflow:process:approval')";
        jdbc.update(grant);
        assertThat(jdbc.queryForObject("select count(*) from sys_role_menu where role_id=700", Integer.class)).isEqualTo(5);
        var resolver = new WorkflowIdentityResolver(mapper(WorkflowIdentityMapper.class), new WorkflowIdentityCodec());
        var operations = support.transactionalProxy(new WorkflowEngineOperations(
                mock(WorkflowAuthenticationContext.class), new WorkflowExceptionTranslator(), resolver));
        var engine = support.processEngine();
        var names = mapper(WorkflowMultiInstanceUserMapper.class);
        var tasks = new WorkflowProcessTaskQueryService(operations, engine.getRepositoryService(), engine.getHistoryService(),
                engine.getTaskService(), resolver, names, mock(WorkflowTaskRevokeApplicationService.class), new WorkflowTaskUnclaimPolicy());
        var instances = new WorkflowProcessInstanceQueryService(operations, engine.getRepositoryService(), engine.getHistoryService(),
                engine.getRuntimeService(), engine.getTaskService(), resolver, mapper(WfCopyMapper.class), names);
        SysUser user = new SysUser(); user.setUserId(700L);
        LoginUser login = new LoginUser(700L, null, user, Set.of("workflow:process:claimList", "workflow:process:claim", "workflow:process:todoList", "workflow:process:query", "workflow:process:approval"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, List.of()));
        try
        {
            engine.getIdentityService().setAuthenticatedUserId("700");
            engine.getRuntimeService().createProcessInstanceBuilder().processDefinitionKey("workbench").businessStatus("running").start();
            engine.getIdentityService().setAuthenticatedUserId(null);
            assertThat(instances.listOwned(null, 1, 20).total()).isEqualTo(1);
            assertThat(tasks.listClaimable(null, 1, 20).total()).isEqualTo(1);
            jdbc.update("delete from sys_role_menu where role_id=700 and menu_id in (select menu_id from sys_menu where perms='workflow:process:approval')");
            assertThat(tasks.listClaimable(null, 1, 20).total()).isZero();
            jdbc.update("delete from sys_role_menu where role_id=700");
            jdbc.update(grant);
            var row = tasks.listClaimable(null, 1, 20).rows().get(0);
            assertThat(row.startUserName()).isEqualTo(" 当前昵称 ");
            engine.getTaskService().claim(row.taskId(), "700");
            assertThat(tasks.listAssigned(null, 1, 20).total()).isEqualTo(1);
            jdbc.update("update sys_user set status='1' where user_id=700");
            assertThatThrownBy(() -> tasks.listAssigned(null, 1, 20)).isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(403));
            assertThatThrownBy(() -> instances.listManaged(null, 1, 20)).isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(403));
        }
        finally
        {
            engine.getIdentityService().setAuthenticatedUserId(null);
            SecurityContextHolder.clearContext();
        }
    }

    private <T> T mapper(Class<T> type)
    {
        return WorkflowH2SchemaMapperSupport.createSpringMapper(support.dataSource(), type.getSimpleName(), type,
                "mapper/flowable/" + type.getSimpleName() + ".xml");
    }
}
