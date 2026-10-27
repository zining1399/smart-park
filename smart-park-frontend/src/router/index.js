import { createRouter, createWebHistory } from 'vue-router'
import MainLayout from '../layout/MainLayout.vue'
import { useAuthStore, ROLES } from '../stores/auth'

const { ADMIN, GATE, STORE } = ROLES
const ALL = [ADMIN, GATE, STORE]

const routes = [
  {
    // 登录页：独立全屏布局，不套 MainLayout。public 表示无需登录即可访问
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/403',
    name: 'Forbidden',
    component: () => import('../views/NotFound.vue'),
    meta: { title: '无访问权限' }
  },
  {
    path: '/',
    component: MainLayout,
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('../views/Dashboard.vue'),
        meta: { title: '运营看板', roles: ALL }
      },
      {
        path: 'access/employees',
        name: 'Employee',
        component: () => import('../views/Employee.vue'),
        meta: { title: '员工管理', roles: [ADMIN, GATE] }
      },
      {
        path: 'access/vehicles',
        name: 'Vehicle',
        component: () => import('../views/Vehicle.vue'),
        meta: { title: '车辆管理', roles: [ADMIN, GATE] }
      },
      {
        path: 'access/permissions',
        name: 'Permission',
        component: () => import('../views/Permission.vue'),
        meta: { title: '权限管理', roles: [ADMIN, GATE] }
      },
      {
        path: 'access/records',
        name: 'AccessRecord',
        component: () => import('../views/AccessRecord.vue'),
        meta: { title: '通行记录', roles: [ADMIN, GATE] }
      },
      {
        path: 'visitors',
        name: 'Visitor',
        component: () => import('../views/Visitor.vue'),
        meta: { title: '访客管理', roles: [ADMIN, GATE] }
      },
      {
        path: 'stock/materials',
        name: 'Material',
        component: () => import('../views/Material.vue'),
        meta: { title: '物资台账', roles: [ADMIN, STORE] }
      },
      {
        path: 'stock/records',
        name: 'StockRecord',
        component: () => import('../views/StockRecord.vue'),
        meta: { title: '出入库记录', roles: [ADMIN, STORE] }
      },
      {
        path: 'alarms',
        name: 'Alarm',
        component: () => import('../views/Alarm.vue'),
        meta: { title: '告警中心', roles: ALL }
      }
    ]
  },
  {
    // 兜底 404：必须放最后
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('../views/NotFound.vue'),
    meta: { title: '页面不存在' }
  }
]

const router = createRouter({
  // 部署在子路径时（VITE_BASE=/park/）自动带上 base，避免刷新 404 与资源路径错误
  history: createWebHistory(import.meta.env.BASE_URL),
  routes
})

// 路由守卫：登录态 + 角色校验
router.beforeEach((to) => {
  const auth = useAuthStore()
  // 登录页：已登录直接进工作台，避免重复登录
  if (to.meta.public) {
    return auth.isLogin ? { path: '/dashboard' } : true
  }
  // 未登录：带 redirect 跳登录，登录后原路返回
  if (!auth.isLogin) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  // 已登录但角色不匹配
  if (!auth.hasPermission(to.meta.roles)) {
    return { path: '/403', query: { from: to.fullPath } }
  }
  return true
})

// 切换页面时更新浏览器标题
router.afterEach((to) => {
  const title = import.meta.env.VITE_APP_TITLE || '智慧园区管理系统'
  document.title = to.meta.title ? `${to.meta.title} - ${title}` : title
})

export default router
