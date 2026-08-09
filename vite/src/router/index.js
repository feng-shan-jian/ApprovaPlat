import { createWebHistory, createRouter } from 'vue-router'
/* Layout */
import Layout from '@/layout'

// 扩展流程管理将低频页面收拢到二级目录；旧地址保留重定向，避免收藏夹、页签和外部入口失效。
const workflowExtendedManagementRedirects = [
  { path: '/workflow/extension', redirect: '/workflow/extensions/extension', hidden: true },
  { path: '/workflow/sqlDatasource', redirect: '/workflow/extensions/sqlDatasource', hidden: true },
  { path: '/workflow/integrationCredential', redirect: '/workflow/extensions/integrationCredential', hidden: true },
  { path: '/workflow/dmn', redirect: '/workflow/extensions/dmn', hidden: true },
  { path: '/workflow/runtimeEvent', redirect: '/workflow/extensions/runtimeEvent', hidden: true },
  { path: '/workflow/collaboration', redirect: '/workflow/extensions/collaboration', hidden: true },
  { path: '/workflow/bpmnEvent', redirect: '/workflow/extensions/bpmnEvent', hidden: true },
  { path: '/workflow/instance', redirect: '/workflow/extensions/instance', hidden: true }
]

/**
 * Note: 路由配置项
 *
 * hidden: true                     // 当设置 true 的时候该路由不会再侧边栏出现 如401，login等页面，或者如一些编辑页面/edit/1
 * alwaysShow: true                 // 当你一个路由下面的 children 声明的路由大于1个时，自动会变成嵌套的模式--如组件页面
 *                                  // 只有一个时，会将那个子路由当做根路由显示在侧边栏--如引导页面
 *                                  // 若你想不管路由下面的 children 声明的个数都显示你的根路由
 *                                  // 你可以设置 alwaysShow: true，这样它就会忽略之前定义的规则，一直显示根路由
 * redirect: noRedirect             // 当设置 noRedirect 的时候该路由在面包屑导航中不可被点击
 * name:'router-name'               // 设定路由的名字，一定要填写不然使用<keep-alive>时会出现各种问题
 * query: '{"id": 1, "name": "ry"}' // 访问路由的默认传递参数
 * roles: ['admin', 'common']       // 访问路由的角色权限
 * permissions: ['a:a:a', 'b:b:b']  // 访问路由的菜单权限
 * meta : {
    noCache: true                   // 如果设置为true，则不会被 <keep-alive> 缓存(默认 false)
    title: 'title'                  // 设置该路由在侧边栏和面包屑中展示的名字
    icon: 'svg-name'                // 设置该路由的图标，对应路径src/assets/icons/svg
    breadcrumb: false               // 如果设置为false，则不会在breadcrumb面包屑中显示
    activeMenu: '/system/user'      // 当路由设置了该属性，则会高亮相对应的侧边栏。
  }
 */

// 公共路由
export const constantRoutes = [
  ...workflowExtendedManagementRedirects,
  {
    path: '/redirect',
    component: Layout,
    hidden: true,
    children: [
      {
        path: '/redirect/:path(.*)',
        component: () => import('@/views/redirect/index.vue')
      }
    ]
  },
  {
    path: '/login',
    component: () => import('@/views/login'),
    hidden: true
  },
  {
    path: '/register',
    component: () => import('@/views/register'),
    hidden: true
  },
  {
    path: "/:pathMatch(.*)*",
    component: () => import('@/views/error/404'),
    hidden: true
  },
  {
    path: '/401',
    component: () => import('@/views/error/401'),
    hidden: true
  },
  {
    path: '',
    component: Layout,
    redirect: '/index',
    children: [
      {
        path: '/index',
        component: () => import('@/views/index'),
        name: 'Index',
        meta: { title: '工作台', icon: 'dashboard', affix: true }
      }
    ]
  },
  {
    path: '/lock',
    component: () => import('@/views/lock'),
    hidden: true,
    meta: { title: '锁定屏幕' }
  },
  {
    path: '/user',
    component: Layout,
    hidden: true,
    redirect: 'noredirect',
    children: [
      {
        path: 'profile/:activeTab?',
        component: () => import('@/views/system/user/profile/index'),
        name: 'Profile',
        meta: { title: '个人中心', icon: 'user' }
      }
    ]
  }
]

// 动态路由，基于用户权限动态去加载
export const dynamicRoutes = [
  {
    path: '/system/user-auth',
    component: Layout,
    hidden: true,
    permissions: ['system:user:edit'],
    children: [
      {
        path: 'role/:userId(\\d+)',
        component: () => import('@/views/system/user/authRole'),
        name: 'AuthRole',
        meta: { title: '分配角色', activeMenu: '/system/user' }
      }
    ]
  },
  {
    path: '/system/role-auth',
    component: Layout,
    hidden: true,
    permissions: ['system:role:edit'],
    children: [
      {
        path: 'user/:roleId(\\d+)',
        component: () => import('@/views/system/role/authUser'),
        name: 'AuthUser',
        meta: { title: '分配用户', activeMenu: '/system/role' }
      }
    ]
  },
  {
    path: '/system/dict-data',
    component: Layout,
    hidden: true,
    permissions: ['system:dict:list'],
    children: [
      {
        path: 'index/:dictId(\\d+)',
        component: () => import('@/views/system/dict/data'),
        name: 'Data',
        meta: { title: '字典数据', activeMenu: '/system/dict' }
      }
    ]
  },
  {
    path: '/monitor/job-log',
    component: Layout,
    hidden: true,
    permissions: ['monitor:job:list'],
    children: [
      {
        path: 'index/:jobId(\\d+)',
        component: () => import('@/views/monitor/job/log'),
        name: 'JobLog',
        meta: { title: '调度日志', activeMenu: '/monitor/job' }
      }
    ]
  },
  {
    path: '/tool/gen-edit',
    component: Layout,
    hidden: true,
    permissions: ['tool:gen:edit'],
    children: [
      {
        path: 'index/:tableId(\\d+)',
        component: () => import('@/views/tool/gen/editTable'),
        name: 'GenEdit',
        meta: { title: '修改生成配置', activeMenu: '/tool/gen' }
      }
    ]
  },
  {
    path: '/workflow/form-design',
    component: Layout,
    hidden: true,
    permissions: ['workflow:form:add', 'workflow:form:edit'],
    children: [
      {
        path: '',
        component: () => import('@/views/tool/build/index'),
        name: 'WorkflowFormDesign',
        meta: { title: '流程表单设计', activeMenu: '/workflow/form', noCache: true }
      }
    ]
  },
  {
    path: '/workflow/model-design',
    component: Layout,
    hidden: true,
    permissions: ['workflow:model:designer'],
    children: [
      {
        path: ':modelId',
        component: () => import('@/views/workflow/model/design'),
        name: 'WorkflowModelDesign',
        meta: { title: '流程模型设计', activeMenu: '/workflow/model', noCache: true }
      }
    ]
  },
  {
    path: '/workflow/process-start',
    component: Layout,
    hidden: true,
    permissions: ['workflow:process:start'],
    children: [
      {
        path: ':definitionId',
        component: () => import('@/views/workflow/work/start'),
        name: 'WorkflowProcessStart',
        meta: { title: '发起流程', activeMenu: '/office/create', noCache: true }
      }
    ]
  },
  {
    path: '/workflow/process-draft',
    component: Layout,
    hidden: true,
    permissions: ['workflow:process:draftQuery'],
    children: [
      {
        path: ':draftId',
        component: () => import('@/views/workflow/work/start'),
        name: 'WorkflowProcessDraftEdit',
        meta: { title: '继续编辑申请', activeMenu: '/office/draft', noCache: true }
      }
    ]
  },
  {
    path: '/workflow/process-detail',
    component: Layout,
    hidden: true,
    permissions: ['workflow:process:query'],
    children: [
      {
        path: ':instanceId',
        component: () => import('@/views/workflow/work/detail'),
        name: 'WorkflowProcessDetail',
        meta: { title: '流程详情', noCache: true }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes: constantRoutes,
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) {
      return savedPosition
    }
    return { top: 0 }
  },
})

export default router
