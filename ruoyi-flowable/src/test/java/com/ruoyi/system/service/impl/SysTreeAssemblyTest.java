package com.ruoyi.system.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.core.domain.entity.SysMenu;

/** 直接验证生产树组装服务及 TreeSelect、Router 的公开 JSON。 */
class SysTreeAssemblyTest
{
    private final SysDeptServiceImpl departments = new SysDeptServiceImpl();
    private final SysMenuServiceImpl menus = new SysMenuServiceImpl();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void emptyInputsKeepEmptyTreesAndOutputs()
    {
        List<SysDept> depts = List.of();
        List<SysMenu> items = List.of();
        assertSame(depts, departments.buildDeptTree(depts));
        assertSame(items, menus.buildMenuTree(items));
        assertJson("[]", departments.buildDeptTreeSelect(depts));
        assertJson("[]", menus.buildMenuTreeSelect(items));
        assertJson("[]", menus.buildMenus(menus.getChildPerms(items, 0)));
    }

    @Test
    void departmentForestKeepsInputRootSiblingOrderAndObjects()
    {
        SysDept root = dept(10, 0, "总部");
        SysDept first = dept(12, 10, "先出现");
        SysDept second = dept(11, 10, "后出现");
        SysDept leaf = dept(13, 12, "末级");
        SysDept orphan = dept(20, 99, "裁剪根");
        root.setLeader("负责人");
        first.setStatus("1");
        List<SysDept> input = List.of(leaf, orphan, first, second, root);

        assertEquals(List.of(orphan, root), departments.buildDeptTree(input));
        assertEquals(List.of(first, second), root.getChildren());
        assertSame(leaf, first.getChildren().get(0));
        assertEquals("负责人", root.getLeader());
        assertEquals(List.of(leaf, orphan, first, second, root), input);
        assertJson("""
                [{"id":20,"label":"裁剪根","disabled":false},
                 {"id":10,"label":"总部","disabled":false,"children":[
                   {"id":12,"label":"先出现","disabled":true,"children":[
                     {"id":13,"label":"末级","disabled":false}]},
                   {"id":11,"label":"后出现","disabled":false}]}]
                """, departments.buildDeptTreeSelect(input));
    }

    @Test
    void departmentRebuildClearsOldDescendantsOnNewLeavesAndOrphanRoots()
    {
        SysDept root = dept(10, 0, "总部");
        SysDept child = dept(11, 10, "分部");
        SysDept leaf = dept(12, 11, "末级");
        departments.buildDeptTree(List.of(root, child, leaf));
        assertSame(leaf, child.getChildren().get(0));
        assertJson("""
                [{"id":10,"label":"总部","disabled":false,"children":[
                  {"id":11,"label":"分部","disabled":false}]}]
                """, departments.buildDeptTreeSelect(List.of(root, child)));
        assertTrue(child.getChildren().isEmpty());
        assertJson("[{\"id\":11,\"label\":\"分部\",\"disabled\":false}]",
                departments.buildDeptTreeSelect(List.of(child)));
    }

    @Test
    void departmentRootlessFallbackStaysFlatAndUnreachableCycleIsNotConnected()
    {
        SysDept first = dept(10, 11, "甲");
        SysDept second = dept(11, 10, "乙");
        List<SysDept> rootless = List.of(second, first);
        assertSame(rootless, departments.buildDeptTree(rootless));
        assertJson("""
                [{"id":11,"label":"乙","disabled":false},
                 {"id":10,"label":"甲","disabled":false}]
                """, departments.buildDeptTreeSelect(rootless));
        SysDept root = dept(20, 0, "根");
        assertEquals(List.of(root), departments.buildDeptTree(List.of(first, root, second)));
        assertTrue(first.getChildren().isEmpty());
        assertTrue(second.getChildren().isEmpty());
    }

    @Test
    void menuEditorForestKeepsOrphansOrderButtonsAndObjects()
    {
        SysMenu root = menu(10, 0, "管理", "manage", "M");
        SysMenu first = menu(12, 10, "先出现", "first", "C");
        SysMenu second = menu(11, 10, "后出现", "second", "C");
        SysMenu button = menu(13, 12, "新增", "", "F");
        SysMenu orphan = menu(20, 99, "裁剪根", "orphan", "C");
        button.setPerms("system:user:add");
        List<SysMenu> input = List.of(button, orphan, first, second, root);

        assertEquals(List.of(orphan, root), menus.buildMenuTree(input));
        assertEquals(List.of(first, second), root.getChildren());
        assertSame(button, first.getChildren().get(0));
        assertEquals("system:user:add", button.getPerms());
        assertEquals(List.of(button, orphan, first, second, root), input);
        assertJson("""
                [{"id":20,"label":"裁剪根","disabled":false},
                 {"id":10,"label":"管理","disabled":false,"children":[
                   {"id":12,"label":"先出现","disabled":false,"children":[
                     {"id":13,"label":"新增","disabled":false}]},
                   {"id":11,"label":"后出现","disabled":false}]}]
                """, menus.buildMenuTreeSelect(input));
    }

    @Test
    void routingStartsOnlyAtExplicitParentAndKeepsSiblingOrder()
    {
        SysMenu root = menu(10, 0, "管理", "manage", "M");
        SysMenu first = menu(12, 10, "先出现", "first", "M");
        SysMenu second = menu(11, 10, "后出现", "second", "C");
        SysMenu leaf = menu(13, 12, "末级", "leaf", "C");
        SysMenu orphan = menu(20, 99, "裁剪根", "orphan", "C");
        List<SysMenu> input = List.of(leaf, orphan, first, second, root);
        assertEquals(List.of(root), menus.getChildPerms(input, 0));
        assertEquals(List.of(first, second), root.getChildren());
        assertEquals(List.of(first, second), menus.getChildPerms(input, 10));
        assertSame(leaf, first.getChildren().get(0));
        assertEquals(List.of(orphan), menus.getChildPerms(input, 99));
        assertTrue(menus.getChildPerms(input, 88).isEmpty());
        assertTrue(menus.getChildPerms(List.of(orphan), 0).isEmpty());
        assertEquals(List.of(orphan), menus.buildMenuTree(List.of(orphan)));
    }

    @Test
    void menuRebuildDropsOldChildrenFromEditorAndRouterOutputs()
    {
        SysMenu root = menu(10, 0, "管理", "manage", "M");
        SysMenu child = menu(11, 10, "目录", "folder", "M");
        SysMenu leaf = menu(12, 11, "末级", "leaf", "C");
        menus.getChildPerms(List.of(root, child, leaf), 0);
        assertSame(leaf, child.getChildren().get(0));
        assertJson("""
                [{"id":10,"label":"管理","disabled":false,"children":[
                  {"id":11,"label":"目录","disabled":false}]}]
                """, menus.buildMenuTreeSelect(List.of(root, child)));
        menus.buildMenuTree(List.of(root, child, leaf));
        var routes = menus.buildMenus(menus.getChildPerms(List.of(root, child), 0));
        assertNull(routes.get(0).getChildren().get(0).getChildren());
        assertTrue(child.getChildren().isEmpty());
        assertJson("[{\"id\":11,\"label\":\"目录\",\"disabled\":false}]",
                menus.buildMenuTreeSelect(List.of(child)));
    }

    @Test
    void menuRootlessFallbackStaysFlatAndRoutesExcludeUnreachableCycles()
    {
        SysMenu first = menu(10, 11, "甲", "first", "M");
        SysMenu second = menu(11, 10, "乙", "second", "M");
        List<SysMenu> rootless = List.of(second, first);
        assertSame(rootless, menus.buildMenuTree(rootless));
        assertJson("""
                [{"id":11,"label":"乙","disabled":false},
                 {"id":10,"label":"甲","disabled":false}]
                """, menus.buildMenuTreeSelect(rootless));
        assertJson("[]", menus.buildMenus(menus.getChildPerms(rootless, 0)));
        SysMenu root = menu(20, 0, "根", "root", "M");
        assertEquals(List.of(root), menus.buildMenuTree(List.of(first, root, second)));
        assertEquals(List.of(root), menus.getChildPerms(List.of(first, root, second), 0));
        assertTrue(first.getChildren().isEmpty());
        assertTrue(second.getChildren().isEmpty());
    }

    @Test
    void routerJsonKeepsNestedComponentsHiddenCacheQueryAndRouteNames()
    {
        SysMenu root = menu(10, 0, "管理", "manage", "M");
        SysMenu folder = menu(11, 10, "组织", "organization", "M");
        SysMenu page = menu(12, 11, "用户", "users", "C");
        page.setComponent("system/user/index");
        page.setRouteName("userList");
        page.setVisible("1");
        page.setIsCache("1");
        page.setQuery("{\"type\":1}");
        assertJson("""
                [{"name":"Manage","path":"/manage","hidden":false,"redirect":"noRedirect",
                  "component":"Layout","alwaysShow":true,
                  "meta":{"title":"管理","icon":"tree","noCache":false,"link":null},"children":[
                    {"name":"Organization","path":"organization","hidden":false,"redirect":"noRedirect",
                     "component":"ParentView","alwaysShow":true,
                     "meta":{"title":"组织","icon":"tree","noCache":false,"link":null},"children":[
                       {"name":"UserList","path":"users","hidden":true,"component":"system/user/index",
                        "query":"{\\\"type\\\":1}",
                        "meta":{"title":"用户","icon":"tree","noCache":true,"link":null}}]}]}]
                """, menus.buildMenus(menus.getChildPerms(List.of(page, folder, root), 0)));
    }

    @Test
    void routerJsonKeepsTopLevelPageAndInnerLinkWrappers()
    {
        SysMenu page = menu(10, 0, "个人", "profile", "C");
        page.setComponent("system/profile/index");
        SysMenu link = menu(20, 0, "文档", "https://www.example.test/docs", "M");
        assertJson("""
                [{"path":"/","hidden":false,"component":"Layout","children":[
                   {"name":"Profile","path":"profile","hidden":false,"component":"system/profile/index",
                    "meta":{"title":"个人","icon":"tree","noCache":false,"link":null}}]},
                 {"name":"Https://www.example.test/docs","path":"/","hidden":false,"component":"Layout",
                  "meta":{"title":"文档","icon":"tree","noCache":false,"link":null},"children":[
                    {"name":"Example/test/docs","path":"example/test/docs","hidden":false,"component":"InnerLink",
                     "meta":{"title":"文档","icon":"tree","noCache":false,"link":"https://www.example.test/docs"}}]}]
                """, menus.buildMenus(menus.getChildPerms(List.of(page, link), 0)));
    }

    private SysDept dept(long id, long parent, String name)
    {
        SysDept dept = new SysDept();
        dept.setDeptId(id);
        dept.setParentId(parent);
        dept.setDeptName(name);
        dept.setStatus("0");
        return dept;
    }

    private SysMenu menu(long id, long parent, String name, String path, String type)
    {
        SysMenu menu = new SysMenu();
        menu.setMenuId(id);
        menu.setParentId(parent);
        menu.setMenuName(name);
        menu.setPath(path);
        menu.setMenuType(type);
        menu.setIsFrame("1");
        menu.setIsCache("0");
        menu.setVisible("0");
        menu.setStatus("0");
        menu.setIcon("tree");
        return menu;
    }

    private void assertJson(String expected, Object actual)
    {
        assertEquals(json.readTree(expected), json.readTree(json.writeValueAsString(actual)));
    }
}
