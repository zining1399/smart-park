<template>
  <el-container class="layout">
    <!-- 左侧深色菜单（按角色过滤：ADMIN 全部 / GATE 门禁与访客 / STORE 仓管） -->
    <el-aside width="220px" class="layout-aside">
      <div class="logo">
        <el-icon :size="22" color="#fff"><OfficeBuilding /></el-icon>
        <span>智慧园区管理系统</span>
      </div>
      <el-menu
        :default-active="$route.path"
        router
        background-color="#001529"
        text-color="#a6adb4"
        active-text-color="#ffffff"
      >
        <template v-for="menu in visibleMenus" :key="menu.index">
          <el-sub-menu v-if="menu.children" :index="menu.index">
            <template #title>
              <el-icon><component :is="menu.icon" /></el-icon>
              <span>{{ menu.title }}</span>
            </template>
            <el-menu-item v-for="child in menu.children" :key="child.index" :index="child.index">
              {{ child.title }}
            </el-menu-item>
          </el-sub-menu>
          <el-menu-item v-else :index="menu.index">
            <el-icon><component :is="menu.icon" /></el-icon>
            <span>{{ menu.title }}</span>
          </el-menu-item>
        </template>
      </el-menu>
    </el-aside>

    <el-container>
      <!-- 顶栏：左侧页面标题，右侧当前用户 + 退出登录 -->
      <el-header class="layout-header">
        <span class="page-title">{{ $route.meta.title || '' }}</span>
        <el-dropdown @command="handleCommand">
          <span class="user-info">
            <el-icon><UserFilled /></el-icon>
            <span class="user-name">{{ auth.realName || '未登录' }}</span>
            <el-tag size="small" type="info" effect="plain">{{ auth.roleLabel || '-' }}</el-tag>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>

      <!-- 主内容区 -->
      <el-main class="layout-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus/es/components/message-box/index.mjs'
import { Odometer, Lock, UserFilled, Box, Bell, ArrowDown } from '@element-plus/icons-vue'
import { useAuthStore, ROLES } from '../stores/auth'

const { ADMIN, GATE, STORE } = ROLES
const router = useRouter()
const auth = useAuthStore()

// 菜单配置化：children 为空的子菜单整体不渲染
const menuConfig = [
  { index: '/dashboard', title: '运营看板', icon: Odometer, roles: [ADMIN, GATE, STORE] },
  {
    index: 'access',
    title: '智慧门禁',
    icon: Lock,
    roles: [ADMIN, GATE],
    children: [
      { index: '/access/employees', title: '员工管理' },
      { index: '/access/vehicles', title: '车辆管理' },
      { index: '/access/permissions', title: '权限管理' },
      { index: '/access/records', title: '通行记录' }
    ]
  },
  {
    index: 'visitor',
    title: '访客接待',
    icon: UserFilled,
    roles: [ADMIN, GATE],
    children: [{ index: '/visitors', title: '访客管理' }]
  },
  {
    index: 'stock',
    title: '智慧仓管',
    icon: Box,
    roles: [ADMIN, STORE],
    children: [
      { index: '/stock/materials', title: '物资台账' },
      { index: '/stock/records', title: '出入库记录' }
    ]
  },
  { index: '/alarms', title: '告警中心', icon: Bell, roles: [ADMIN, GATE, STORE] }
]

const visibleMenus = computed(() =>
  menuConfig
    .filter((menu) => auth.hasPermission(menu.roles))
    .map((menu) => ({
      ...menu,
      children: menu.children ? menu.children.filter((child) => auth.hasPermission(child.roles)) : null
    }))
    .filter((menu) => !menu.children || menu.children.length > 0)
)

async function handleCommand(command) {
  if (command !== 'logout') return
  // 后端为无状态 JWT、无服务端登出端点：前端丢弃本地凭证即可
  const ok = await ElMessageBox.confirm('确认退出登录吗？', '提示', {
    type: 'warning',
    confirmButtonText: '退出',
    cancelButtonText: '取消'
  })
    .then(() => true)
    .catch(() => false)
  if (!ok) return
  auth.clearAuth()
  router.replace({ path: '/login' })
}
</script>

<style scoped>
.layout {
  height: 100%;
}

.layout-aside {
  background-color: #001529;
  overflow-x: hidden;
}

.layout-aside :deep(.el-menu) {
  border-right: none;
}

.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  background-color: #002140;
}

.layout-header {
  height: 60px;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
}

.page-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #606266;
  font-size: 14px;
  cursor: pointer;
  outline: none;
}

.user-name {
  font-weight: 500;
}

.layout-main {
  background-color: #f0f2f5;
  padding: 16px;
}
</style>
