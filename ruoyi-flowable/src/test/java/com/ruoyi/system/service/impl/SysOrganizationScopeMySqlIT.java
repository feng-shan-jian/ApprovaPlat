package com.ruoyi.system.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.ruoyi.common.core.domain.entity.*;
import com.ruoyi.common.core.domain.TreeSelect;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.testsupport.WorkflowMySqlITSupport;
import com.ruoyi.framework.aspectj.DataScopeAspect;
import com.ruoyi.framework.security.context.PermissionContextHolder;
import com.ruoyi.system.domain.*;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.service.impl.SysConfigDictDeletionMySqlIT.SqlBoundary;

/** 真实 DataScope 切面、Spring 事务代理和生产 XML；仅创建、清理本测试自己的行。 */
class SysOrganizationScopeMySqlIT
{
    private static MysqlDataSource source;
    private final String owner = "c07-" + UUID.randomUUID();
    private final SqlBoundary boundary = new SqlBoundary();
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private DataSourceTransactionManager manager;
    private SysUserServiceImpl users;
    private SysRoleServiceImpl roles;
    private SysDeptServiceImpl depts;
    private SysMenuServiceImpl menus;
    private ValidatorFactory validators;
    private long a, child, leaf, b, actor, inside, outside, ra, rc, rb;

    @BeforeAll
    static void verifyIsolation() throws Exception
    {
        source = WorkflowMySqlITSupport.createDataSource();
        WorkflowMySqlITSupport.verifyIsolatedBaseline(source, "组织范围与部门移动 IT", "approvaplat_it",
                List.of("sys_user", "sys_dept", "sys_role", "sys_user_role", "sys_user_post", "sys_role_menu", "sys_role_dept", "sys_menu"));
    }

    @BeforeEach
    void setUp() throws Exception
    {
        jdbc = new JdbcTemplate(source);
        assertEquals(8, jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database() "
                + "and table_name in ('sys_user','sys_dept','sys_role','sys_user_role','sys_user_post','sys_role_menu','sys_role_dept','sys_menu') "
                + "and engine='InnoDB'", Integer.class));
        manager = new DataSourceTransactionManager(source);
        transactions = new TransactionTemplate(manager);
        transactions.setTimeout(15);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliases(SysUser.class, SysRole.class, SysDept.class, SysUserRole.class, SysUserPost.class,
                SysRoleMenu.class, SysRoleDept.class, SysMenu.class);
        factory.setMapperLocations(List.of("SysUser", "SysRole", "SysDept", "SysUserRole", "SysUserPost", "SysRoleMenu", "SysRoleDept", "SysMenu")
                .stream().map(n -> new ClassPathResource("mapper/system/" + n + "Mapper.xml")).toArray(ClassPathResource[]::new));
        factory.setPlugins(boundary);
        var session = new SqlSessionTemplate(factory.getObject());
        menus = new SysMenuServiceImpl();
        ReflectionTestUtils.setField(menus, "menuMapper", session.getMapper(SysMenuMapper.class));
        depts = new SysDeptServiceImpl();
        ReflectionTestUtils.setField(depts, "deptMapper", session.getMapper(SysDeptMapper.class));
        ReflectionTestUtils.setField(depts, "roleMapper", session.getMapper(SysRoleMapper.class));
        depts = proxy(depts);
        roles = new SysRoleServiceImpl();
        ReflectionTestUtils.setField(roles, "roleMapper", session.getMapper(SysRoleMapper.class));
        ReflectionTestUtils.setField(roles, "userRoleMapper", session.getMapper(SysUserRoleMapper.class));
        ReflectionTestUtils.setField(roles, "roleMenuMapper", session.getMapper(SysRoleMenuMapper.class));
        ReflectionTestUtils.setField(roles, "roleDeptMapper", session.getMapper(SysRoleDeptMapper.class));
        roles = proxy(roles);
        users = new SysUserServiceImpl();
        ReflectionTestUtils.setField(users, "userMapper", session.getMapper(SysUserMapper.class));
        ReflectionTestUtils.setField(users, "userRoleMapper", session.getMapper(SysUserRoleMapper.class));
        ReflectionTestUtils.setField(users, "userPostMapper", session.getMapper(SysUserPostMapper.class));
        ReflectionTestUtils.setField(users, "deptService", depts);
        var config = mock(ISysConfigService.class);
        when(config.selectConfigByKey("sys.user.initPassword")).thenReturn("C07-initial");
        ReflectionTestUtils.setField(users, "configService", config);
        validators = Validation.buildDefaultValidatorFactory();
        ReflectionTestUtils.setField(users, "validator", validators.getValidator());
        users = proxy(users);
        a = department("A", 0, "0");
        child = department("child", a, "0," + a);
        leaf = department("leaf", child, "0," + a + "," + child);
        b = department("B", 0, "0");
        actor = user("actor", a);
        inside = user("inside", child);
        outside = user("outside", b);
        ra = role("a"); rc = role("child"); rb = role("b");
        jdbc.update("insert into sys_user_role values (?,?),(?,?),(?,?)", actor, ra, inside, rc, outside, rb);
        admin();
        boundary.statements.clear();
    }

    @AfterEach
    void cleanOnlyOwnedRows()
    {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        if (validators != null) validators.close();
        if (jdbc == null) return;
        jdbc.update("delete ur from sys_user_role ur join sys_user u on u.user_id=ur.user_id where u.create_by=?", owner);
        jdbc.update("delete ur from sys_user_role ur join sys_role r on r.role_id=ur.role_id where r.create_by=?", owner);
        jdbc.update("delete up from sys_user_post up join sys_user u on u.user_id=up.user_id where u.create_by=?", owner);
        jdbc.update("delete rd from sys_role_dept rd join sys_role r on r.role_id=rd.role_id where r.create_by=?", owner);
        jdbc.update("delete rm from sys_role_menu rm join sys_role r on r.role_id=rm.role_id where r.create_by=?", owner);
        jdbc.update("delete from sys_user where create_by=?", owner);
        jdbc.update("delete from sys_role where create_by=?", owner);
        jdbc.update("delete from sys_dept where create_by=?", owner);
        jdbc.update("delete from sys_menu where create_by=?", owner);
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target)
    {
        var proxy = new AspectJProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.setExposeProxy(true);
        proxy.addAspect(new DataScopeAspect());
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    @ParameterizedTest
    @ValueSource(strings = { "1", "2", "3", "4", "5" })
    void everyDataScopeFiltersRealDetailsAndCollections(String scope)
    {
        jdbc.update("insert into sys_role_dept values (?,?)", ra, b);
        signIn(actor, a, List.of(scopeRole(ra, scope, "0", "manage")), "manage");
        boolean all = "1".equals(scope);
        assertAccess(all || "3".equals(scope) || "4".equals(scope) || "5".equals(scope), () -> users.selectUserByIdInScope(actor));
        assertAccess(all || "4".equals(scope), () -> users.selectUserByIdInScope(inside));
        assertAccess(all || "2".equals(scope), () -> users.selectUserByIdInScope(outside));
        assertAccess(all || "3".equals(scope) || "4".equals(scope), () -> roles.selectRoleById(ra));
        assertAccess(all || "4".equals(scope), () -> roles.selectRoleById(rc));
        assertAccess(all || "2".equals(scope), () -> roles.selectRoleById(rb));
        assertAccess(all || "3".equals(scope) || "4".equals(scope), () -> depts.selectDeptById(a));
        assertAccess(all || "4".equals(scope), () -> depts.selectDeptById(child));
        assertAccess(all || "2".equals(scope), () -> depts.selectDeptById(b));
        assertEquals(0, boundary.count("selectUserList"));
        assertEquals(0, boundary.count("selectRoleList"));
        assertEquals(0, boundary.count("selectDeptList"));
    }

    @Test
    void departmentTreeUsesRealDataScopeAndPromotesOnlyVisibleOrphans()
    {
        jdbc.update("insert into sys_role_dept values (?,?),(?,?)", ra, child, ra, b);
        signIn(actor, a, List.of(scopeRole(ra, "2", "0", "manage")), "manage");
        var custom = depts.selectDeptTreeList(new SysDept());
        assertEquals(List.of(b, child), custom.stream().map(TreeSelect::getId).toList());
        assertTrue(custom.stream().allMatch(node -> node.getChildren().isEmpty()));
        signIn(inside, child, List.of(scopeRole(rc, "4", "0", "manage")), "manage");
        var subtree = depts.selectDeptTreeList(new SysDept());
        assertEquals(List.of(child), subtree.stream().map(TreeSelect::getId).toList());
        assertEquals(List.of(leaf), subtree.get(0).getChildren().stream().map(TreeSelect::getId).toList());
        signIn(inside, child, List.of(scopeRole(rc, "3", "0", "manage")), "manage");
        var onlyOwn = depts.selectDeptTreeList(new SysDept());
        assertEquals(List.of(child), onlyOwn.stream().map(TreeSelect::getId).toList());
        assertTrue(onlyOwn.get(0).getChildren().isEmpty());
        signIn(actor, a, List.of(scopeRole(ra, "5", "0", "manage")), "manage");
        assertTrue(depts.selectDeptTreeList(new SysDept()).isEmpty());
        signIn(actor, a, List.of(scopeRole(ra, "1", "1", "manage")), "manage");
        assertTrue(depts.selectDeptTreeList(new SysDept()).isEmpty());
    }

    @Test
    void menuSqlPermissionsKeepEditorOrphansSeparateFromAuthorizedRoutes()
    {
        long root = menu("root", 0, "M", "0", 0);
        long second = menu("second", root, "C", "0", 20);
        long first = menu("first", root, "C", "0", 10);
        long button = menu("button", first, "F", "0", 0);
        long disabled = menu("disabled", root, "C", "1", 30);
        long denied = menu("denied", root, "C", "0", 40);
        for (long id : List.of(second, first, button, disabled))
            jdbc.update("insert into sys_role_menu values (?,?)", ra, id);

        var editor = menus.buildMenuTreeSelect(menus.selectMenuList(actor));
        assertEquals(List.of(first, second, disabled), editor.stream().map(TreeSelect::getId).toList());
        assertEquals(List.of(button), editor.get(0).getChildren().stream().map(TreeSelect::getId).toList());
        assertTrue(menus.selectMenuTreeByUserId(actor).isEmpty(), "未授权父目录不能提升为路由根");
        jdbc.update("insert into sys_role_menu values (?,?)", ra, root);
        var tree = menus.selectMenuTreeByUserId(actor);
        assertEquals(List.of(root), tree.stream().map(SysMenu::getMenuId).toList());
        assertEquals(List.of(first, second), tree.get(0).getChildren().stream().map(SysMenu::getMenuId).toList());
        assertTrue(tree.get(0).getChildren().stream().allMatch(node -> node.getChildren().isEmpty()));
        var routes = menus.buildMenus(tree);
        assertEquals("/l05-root", routes.get(0).getPath());
        assertEquals(List.of("l05-first", "l05-second"),
                routes.get(0).getChildren().stream().map(route -> route.getPath()).toList());
        var adminRoot = menus.selectMenuTreeByUserId(1L).stream().filter(node -> node.getMenuId() == root).findFirst().orElseThrow();
        assertEquals(List.of(first, second, denied), adminRoot.getChildren().stream().map(SysMenu::getMenuId).toList());
        jdbc.update("update sys_role set status='1' where role_id=?", ra);
        assertTrue(menus.selectMenuList(actor).isEmpty());
        assertTrue(menus.selectMenuTreeByUserId(actor).isEmpty());
    }

    @Test
    void permissionContextDisabledRolesAndMultipleCustomScopesRemainEffective()
    {
        jdbc.update("insert into sys_role_dept values (?,?),(?,?)", ra, a, rb, b);
        signIn(actor, a, List.of(scopeRole(ra, "2", "0", "manage"), scopeRole(rb, "2", "0", "manage"),
                scopeRole(rc, "1", "1", "manage"), scopeRole(rc + 1, "1", "0", "other")), "manage");
        assertEquals(2, users.selectUsersInScope(new SysUser(), new Long[] { actor, outside }).size());
        assertThrows(ServiceException.class, () -> users.selectUserByIdInScope(inside));
        PermissionContextHolder.setContext("unknown");
        assertThrows(ServiceException.class, () -> depts.selectDeptById(a));
        SysUser forged = new SysUser();
        forged.getParams().put("dataScope", " OR 1=1");
        assertThrows(ServiceException.class, () -> users.selectUsersInScope(forged, new Long[] { actor }));
    }

    @Test
    void detailsKeepNestedRolesDepartmentAndParentName()
    {
        signIn(actor, a, List.of(scopeRole(ra, "4", "0", "manage")), "manage");
        SysUser detail = users.selectUserByIdInScope(inside);
        assertEquals("hash", detail.getPassword());
        assertEquals("0," + a, detail.getDept().getAncestors());
        assertEquals(a, detail.getDept().getParentId());
        assertEquals("leader", detail.getDept().getLeader());
        assertEquals(rc, detail.getRoles().get(0).getRoleId());
        assertEquals("key-child-" + owner, detail.getRoles().get(0).getRoleKey());
        SysRole role = roles.selectRoleById(rc);
        assertFalse(role.isDeptCheckStrictly());
        assertFalse(role.isMenuCheckStrictly());
        assertEquals("remark", role.getRemark());
        SysDept dept = depts.selectDeptById(child);
        assertEquals("A", dept.getParentName());
        assertEquals("123", dept.getPhone());
        assertEquals("dept@example.test", dept.getEmail());
        assertEquals(1, boundary.count("selectUsersInScope"));
        assertEquals(1, boundary.count("selectRolesInScope"));
        assertEquals(1, boundary.count("selectDeptsInScope"));
    }

    @Test
    void mixedUnauthorizedBatchesMakeNoPartialWrites()
    {
        signIn(actor, a, List.of(scopeRole(ra, "4", "0", "manage")), "manage");
        assertThrows(ServiceException.class, () -> users.deleteUserByIds(new Long[] { inside, outside }));
        assertThrows(ServiceException.class, () -> roles.deleteRoleByIds(new Long[] { rc, rb }));
        assertThrows(ServiceException.class, () -> roles.selectRolesInScope(new SysRole(), new Long[] { rc, rb }));
        assertThrows(ServiceException.class, () -> depts.updateDeptSort(new String[] { "" + child, "" + b }, new String[] { "8", "9" }));
        assertEquals("0", jdbc.queryForObject("select del_flag from sys_user where user_id=?", String.class, inside));
        assertEquals(1, jdbc.queryForObject("select count(*) from sys_user_role where user_id=?", Integer.class, inside));
        assertEquals(0, jdbc.queryForObject("select order_num from sys_dept where dept_id=?", Integer.class, child));
        assertEquals(1, boundary.count("selectUsersInScope"));
    }

    @Test
    void allowedBatchesUseLoadedObjectsAndProtectSystemAccounts()
    {
        assertThrows(ServiceException.class, () -> users.deleteUserByIds(new Long[] { inside, 1L }));
        assertThrows(ServiceException.class, () -> roles.deleteRoleByIds(new Long[] { rc, 1L }));
        ServiceException assigned = assertThrows(ServiceException.class, () -> roles.deleteRoleByIds(new Long[] { rc }));
        assertEquals("child已分配,不能删除", assigned.getMessage());
        assertEquals(1, users.deleteUserByIds(new Long[] { inside, inside }));
        boundary.statements.clear();
        assertEquals(1, roles.deleteRoleByIds(new Long[] { rc, rc }));
        assertEquals(1, boundary.count("selectRolesInScope"));
        assertEquals(1, boundary.count("selectAssignedRoleIds"));
        assertEquals(0, boundary.count("selectRoleById"));
    }

    @Test
    void importUsesScopedBatchAndKeepsPartialRowReportingAndAdminProtection()
    {
        signIn(actor, a, List.of(scopeRole(ra, "4", "0", "manage")), "manage");
        SysUser accepted = importRow(inside, "changed");
        accepted.setDeptId(child);
        SysUser denied = importRow(outside, "forbidden");
        denied.setDeptId(b);
        ServiceException error = assertThrows(ServiceException.class, () -> users.importUser(List.of(accepted, denied), true, owner));
        assertTrue(error.getMessage().contains("没有权限访问用户数据"));
        assertEquals("changed", jdbc.queryForObject("select nick_name from sys_user where user_id=?", String.class, inside));
        assertEquals("outside", jdbc.queryForObject("select nick_name from sys_user where user_id=?", String.class, outside));
        assertEquals(1, boundary.count("selectImportUsersInScope"));
        assertEquals(1, boundary.count("selectDeptsInScope"));
        assertEquals(0, boundary.count("selectUserByUserName"));
        admin();
        SysUser protectedUser = importRow(1L, "forbidden");
        assertTrue(assertThrows(ServiceException.class, () -> users.importUser(List.of(protectedUser), true, owner))
                .getMessage().contains("不允许操作超级管理员用户"));
    }

    @Test
    void newImportsUseAuthorizedDepartmentCollectionWithoutLoadingExistingDetails()
    {
        signIn(actor, a, List.of(scopeRole(ra, "4", "0", "manage")), "manage");
        SysUser allowed = new SysUser();
        allowed.setUserName("new-" + owner.substring(0, 20)); allowed.setNickName("new"); allowed.setDeptId(child);
        SysUser denied = new SysUser();
        denied.setUserName("denied-" + owner.substring(0, 20)); denied.setNickName("denied"); denied.setDeptId(b);
        assertTrue(assertThrows(ServiceException.class, () -> users.importUser(List.of(allowed, denied), false, owner))
                .getMessage().contains("没有权限访问部门数据"));
        assertEquals(1, jdbc.queryForObject("select count(*) from sys_user where user_name=? and dept_id=?", Integer.class, allowed.getUserName(), child));
        assertEquals(0, jdbc.queryForObject("select count(*) from sys_user where user_name=?", Integer.class, denied.getUserName()));
        assertEquals(1, boundary.count("selectDeptsInScope"));
        assertEquals(0, boundary.count("selectImportUsersInScope"));
    }

    @Test
    void moveUsesAuthorizedTargetsAndUpdatesAllDescendantPaths()
    {
        signIn(actor, a, List.of(scopeRole(ra, "4", "0", "manage")), "manage");
        assertThrows(ServiceException.class, () -> depts.updateDept(move(child, b)));
        assertPath(child, a, "0," + a);
        admin();
        assertEquals(1, depts.updateDept(move(child, b)));
        assertPath(child, b, "0," + b);
        assertPath(leaf, child, "0," + b + "," + child);
        assertEquals(4, boundary.count("selectDeptsForUpdate"));
    }

    @Test
    void selfAndDescendantMovesAreRejectedBeforeWrites()
    {
        assertTrue(assertThrows(ServiceException.class, () -> depts.updateDept(move(a, a))).getMessage().contains("不能是自己"));
        assertTrue(assertThrows(ServiceException.class, () -> depts.updateDept(move(a, leaf))).getMessage().contains("子部门"));
        assertPath(a, 0, "0");
        assertPath(leaf, child, "0," + a + "," + child);
        assertEquals(0, boundary.count("updateDept"));
        assertEquals(0, boundary.count("updateDeptChildren"));
    }

    @Test
    void statusChangesAndParentActivationAreAtomic()
    {
        SysDept disable = move(a, 0); disable.setStatus("1");
        assertThrows(ServiceException.class, () -> depts.updateDept(disable));
        jdbc.update("update sys_dept set status='1' where dept_id in (?,?)", b, leaf);
        SysDept newChild = newDepartment("rejected", b);
        assertEquals("部门停用，不允许新增", assertThrows(ServiceException.class, () -> depts.insertDept(newChild)).getMessage());
        SysDept move = move(child, b); move.setStatus("1");
        assertEquals(1, depts.updateDept(move));
        assertEquals("1", status(b));
        move.setStatus("0");
        depts.updateDept(move);
        assertEquals("0", status(b));
        assertEquals("1", status(leaf));
        depts.insertDept(newDepartment("accepted", b));
        long added = jdbc.queryForObject("select dept_id from sys_dept where create_by=? and dept_name='accepted'", Long.class, owner);
        assertPath(added, b, "0," + b);
    }

    @ParameterizedTest
    @ValueSource(strings = { "updateDeptChildren", "updateDept", "updateDeptStatusNormal" })
    void realSqlFailureRollsBackPathsOwnRowAndParentStatus(String failingAfter)
    {
        jdbc.update("update sys_dept set status='1' where dept_id=?", b);
        boundary.after = statement -> {
            if (statement.endsWith("." + failingAfter))
                jdbc.update("update sys_dept set dept_id=null where dept_id=?", leaf);
        };
        assertThrows(DataAccessException.class, () -> depts.updateDept(move(child, b)));
        assertPath(child, a, "0," + a);
        assertPath(leaf, child, "0," + a + "," + child);
        assertEquals("1", status(b));
    }

    @Test
    void concurrentCrossMoveWaitsThenRejectsCycle() throws Exception
    {
        competing(() -> depts.updateDept(move(a, b)), () -> depts.updateDept(move(b, a)), true);
        assertPath(a, b, "0," + b);
        assertPath(b, 0, "0");
        assertPath(leaf, child, "0," + b + "," + a + "," + child);
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void concurrentInsertAndMoveCannotLoseNewDescendant(boolean insertFirst) throws Exception
    {
        Runnable insert = () -> depts.insertDept(newDepartment("concurrent", child));
        Runnable move = () -> depts.updateDept(move(a, b));
        competing(insertFirst ? insert : move, insertFirst ? move : insert, false);
        long added = jdbc.queryForObject("select dept_id from sys_dept where create_by=? and dept_name='concurrent'", Long.class, owner);
        assertPath(added, child, "0," + b + "," + a + "," + child);
    }

    @Test
    void concurrentDeleteAndInsertCannotCreateChildOfDeletedParent() throws Exception
    {
        competing(() -> depts.deleteDeptById(leaf), () -> depts.insertDept(newDepartment("concurrent", leaf)), true);
        assertEquals(0, jdbc.queryForObject("select count(*) from sys_dept where create_by=? and dept_name='concurrent'", Integer.class, owner));
    }

    @Test
    void metadataAndSortDoNotRewriteRelationshipsAndDeleteKeepsWarningContract()
    {
        SysDept edit = move(child, a); edit.setDeptName("renamed");
        depts.updateDept(edit);
        depts.updateDeptSort(new String[] { "" + child, "" + leaf }, new String[] { "5", "8" });
        assertPath(child, a, "0," + a);
        assertPath(leaf, child, "0," + a + "," + child);
        assertEquals(0, boundary.count("selectChildrenDeptById"));
        assertEquals(5, jdbc.queryForObject("select order_num from sys_dept where dept_id=?", Integer.class, child));
        assertEquals(601, assertThrows(ServiceException.class, () -> depts.deleteDeptById(a)).getCode());
        assertEquals(601, assertThrows(ServiceException.class, () -> depts.deleteDeptById(b)).getCode());
    }

    @ParameterizedTest
    @ValueSource(strings = { "2", "3", "4" })
    void sameParentEditDoesNotRequireAccessToExistingParent(String scope)
    {
        jdbc.update("insert into sys_role_dept values (?,?)", rc, child);
        jdbc.update("update sys_dept set status='1' where dept_id in (?,?,?)", a, child, leaf);
        signIn(inside, child, List.of(scopeRole(rc, scope, "0", "manage")), "manage");
        assertThrows(ServiceException.class, () -> depts.selectDeptById(a));

        SysDept edit = move(child, a);
        edit.setDeptName("renamed");
        edit.setAncestors("untrusted");
        assertEquals(1, assertDoesNotThrow(() -> depts.updateDept(edit)));
        assertPath(child, a, "0," + a);
        assertEquals("renamed", jdbc.queryForObject("select dept_name from sys_dept where dept_id=?", String.class, child));
        assertEquals("0", status(child));
        assertEquals("0", status(a), "启用使用旧部门已锁定的祖先关系，即使原父级不在当前可见范围");
        assertEquals(1, boundary.count("selectDeptsForUpdate"));
        assertEquals(0, boundary.count("selectChildrenDeptById"));

        assertThrows(ServiceException.class, () -> depts.updateDept(move(child, b)), "实际移动仍必须取得有权新父级");
        assertPath(child, a, "0," + a);
    }

    @ParameterizedTest
    @CsvSource({ "5,false,false", "3,false,false", "3,true,true", "1,false,true" })
    void duplicateNewImportNeedsActualUpdateScope(String scope, boolean withDepartment, boolean canUpdate)
    {
        signIn(actor, a, List.of(scopeRole(ra, scope, "0", "manage")), "manage");
        String name = "new-" + owner.substring(0, 20);
        SysUser first = new SysUser(); first.setUserName(name); first.setNickName("first");
        SysUser second = new SysUser(); second.setUserName(name); second.setNickName("second");
        SysUser third = new SysUser(); third.setUserName(name); third.setNickName("third");
        if (withDepartment)
        {
            first.setDeptId(a); second.setDeptId(a); third.setDeptId(a);
        }
        if (canUpdate)
        {
            String result = assertDoesNotThrow(() -> users.importUser(List.of(first, second, third), true, owner));
            assertTrue(result.contains("共 3 条"));
        }
        else
        {
            ServiceException error = assertThrows(ServiceException.class,
                    () -> users.importUser(List.of(first, second, third), true, owner));
            assertTrue(error.getMessage().contains("共 2 条数据格式不正确"));
            assertTrue(error.getMessage().contains("没有权限访问用户数据"));
        }
        long id = jdbc.queryForObject("select user_id from sys_user where user_name=? and create_by=?", Long.class, name, owner);
        assertEquals(canUpdate ? "third" : "first",
                jdbc.queryForObject("select nick_name from sys_user where user_id=?", String.class, id));
        assertEquals(1, boundary.count("selectImportUsersInScope"));
        assertEquals(1, boundary.count("selectUsersInScope"), "仅新建后首次重复需要补查实际更新范围，普通集合与后续重复行不重查");
        assertAccess(canUpdate, () -> users.selectUserByIdInScope(id));
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void olderRepeatableReadSnapshotDoesNotHideCommittedDepartmentReference(boolean childReference) throws Exception
    {
        var worker = Executors.newSingleThreadExecutor();
        String countSql = childReference
                ? "select count(*) from sys_dept where parent_id=? and del_flag='0'"
                : "select count(*) from sys_user where dept_id=? and del_flag='0'";
        try
        {
            transactions.executeWithoutResult(tx -> {
                assertEquals("REPEATABLE-READ", jdbc.queryForObject("select @@transaction_isolation", String.class));
                assertEquals(0, jdbc.queryForObject(countSql, Integer.class, leaf));
                var inserted = worker.submit(() -> {
                    admin();
                    try
                    {
                        if (childReference)
                        {
                            depts.insertDept(newDepartment("new-reference", leaf));
                        }
                        else
                        {
                            SysUser user = new SysUser();
                            user.setUserName("new-" + owner.substring(0, 20)); user.setNickName("new-reference");
                            user.setDeptId(leaf); user.setCreateBy(owner);
                            users.insertUser(user);
                        }
                    }
                    finally { SecurityContextHolder.clearContext(); RequestContextHolder.resetRequestAttributes(); }
                });
                try { inserted.get(10, TimeUnit.SECONDS); }
                catch (Exception e) { throw new AssertionError(e); }
                assertEquals(0, jdbc.queryForObject(countSql, Integer.class, leaf), "先证明外层仍持有旧 RR 快照");
                ServiceException error = assertThrows(ServiceException.class, () -> depts.deleteDeptById(leaf));
                assertEquals(601, error.getCode());
                assertEquals(childReference ? "存在下级部门,不允许删除" : "部门存在用户,不允许删除", error.getMessage());
                tx.setRollbackOnly();
            });
        }
        finally
        {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject(countSql, Integer.class, leaf));
        assertEquals("0", jdbc.queryForObject("select del_flag from sys_dept where dept_id=?", String.class, leaf));
        assertPath(leaf, child, "0," + a + "," + child);
        if (childReference)
        {
            long id = jdbc.queryForObject("select dept_id from sys_dept where create_by=? and dept_name='new-reference'", Long.class, owner);
            assertPath(id, leaf, "0," + a + "," + child + "," + leaf);
        }
    }

    private void competing(Runnable winner, Runnable contender, boolean rejected) throws Exception
    {
        var workers = Executors.newFixedThreadPool(2);
        var written = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempting = new CountDownLatch(1);
        try
        {
            var first = workers.submit(() -> {
                admin();
                try { transactions.executeWithoutResult(tx -> { winner.run(); written.countDown(); await(release); }); }
                finally { SecurityContextHolder.clearContext(); RequestContextHolder.resetRequestAttributes(); }
            });
            assertTrue(written.await(10, TimeUnit.SECONDS));
            boundary.before = statement -> { if (statement.endsWith(".lockDeptRelations")) attempting.countDown(); };
            var second = workers.submit(() -> {
                admin();
                try { contender.run(); }
                finally { SecurityContextHolder.clearContext(); RequestContextHolder.resetRequestAttributes(); }
            });
            assertTrue(attempting.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(250, TimeUnit.MILLISECONDS));
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            if (rejected)
                assertInstanceOf(ServiceException.class, assertThrows(ExecutionException.class, () -> second.get(10, TimeUnit.SECONDS)).getCause());
            else second.get(10, TimeUnit.SECONDS);
        }
        finally
        {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch)
    {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    private long menu(String name, long parent, String type, String status, int order)
    {
        SysMenu menu = new SysMenu();
        menu.setMenuName(name); menu.setParentId(parent); menu.setMenuType(type); menu.setStatus(status);
        menu.setOrderNum(order); menu.setPath("l05-" + name); menu.setIsFrame("1"); menu.setCreateBy(owner);
        menus.insertMenu(menu);
        return jdbc.queryForObject("select menu_id from sys_menu where create_by=? and menu_name=?", Long.class, owner, name);
    }

    private long department(String name, long parent, String ancestors)
    {
        jdbc.update("insert into sys_dept(parent_id,ancestors,dept_name,order_num,leader,phone,email,status,del_flag,create_by) "
                + "values(?,?,?,0,'leader','123','dept@example.test','0','0',?)", parent, ancestors, name, owner);
        return jdbc.queryForObject("select dept_id from sys_dept where create_by=? and dept_name=?", Long.class, owner, name);
    }

    private long user(String suffix, long dept)
    {
        String name = (suffix + owner.substring(0, 20));
        jdbc.update("insert into sys_user(dept_id,user_name,nick_name,password,status,del_flag,create_by) values(?,?,?,'hash','0','0',?)", dept, name, suffix, owner);
        return jdbc.queryForObject("select user_id from sys_user where create_by=? and user_name=?", Long.class, owner, name);
    }

    private long role(String suffix)
    {
        jdbc.update("insert into sys_role(role_name,role_key,role_sort,data_scope,menu_check_strictly,dept_check_strictly,status,del_flag,remark,create_by) "
                + "values(?,?,1,'1',0,0,'0','0','remark',?)", suffix, "key-" + suffix + "-" + owner, owner);
        return jdbc.queryForObject("select role_id from sys_role where create_by=? and role_name=?", Long.class, owner, suffix);
    }

    private SysUser importRow(long id, String name)
    {
        SysUser row = new SysUser();
        row.setUserName(jdbc.queryForObject("select user_name from sys_user where user_id=?", String.class, id));
        row.setNickName(name);
        return row;
    }

    private SysDept move(long id, long parent)
    {
        SysDept dept = new SysDept(); dept.setDeptId(id); dept.setParentId(parent); dept.setStatus("0"); return dept;
    }

    private SysDept newDepartment(String name, long parent)
    {
        SysDept dept = new SysDept(); dept.setDeptName(name); dept.setParentId(parent); dept.setStatus("0"); dept.setCreateBy(owner); return dept;
    }

    private String status(long id) { return jdbc.queryForObject("select status from sys_dept where dept_id=?", String.class, id); }

    private void assertPath(long id, long parent, String ancestors)
    {
        assertEquals(parent, jdbc.queryForObject("select parent_id from sys_dept where dept_id=?", Long.class, id));
        assertEquals(ancestors, jdbc.queryForObject("select ancestors from sys_dept where dept_id=?", String.class, id));
    }

    private void assertAccess(boolean allowed, Runnable read)
    {
        if (allowed) assertDoesNotThrow(read::run); else assertThrows(ServiceException.class, read::run);
    }

    private SysRole scopeRole(long id, String scope, String status, String permission)
    {
        SysRole role = new SysRole(id); role.setDataScope(scope); role.setStatus(status); role.setPermissions(Set.of(permission)); return role;
    }

    private void admin() { signIn(1L, a, List.of(), "manage"); }

    private void signIn(long id, long dept, List<SysRole> grants, String permission)
    {
        SysUser user = new SysUser(id); user.setDeptId(dept); user.setRoles(grants);
        var login = new LoginUser(id, dept, user, Set.of(permission));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, List.of()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        PermissionContextHolder.setContext(permission);
    }

}
