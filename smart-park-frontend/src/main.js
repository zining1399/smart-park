import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { OfficeBuilding, Odometer, Lock, UserFilled, Box, Bell, User, ArrowDown } from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import { useAuthStore } from './stores/auth'
import { getUserInfo } from './api/auth'
import permission from './directives/permission'
import registerElementPlus from './plugins/element-plus'
import './styles/index.css'

const app = createApp(App)

// Element Plus 按需注册（组件 + 样式 + v-loading 指令）
registerElementPlus(app)

// 按需注册图标：旧版全量注册约 300 个图标组件是主包体积的来源之一
const icons = { OfficeBuilding, Odometer, Lock, UserFilled, Box, Bell, Monitor, User, ArrowDown }
Object.entries(icons).forEach(([name, component]) => app.component(name, component))

// 按钮级角色权限指令 v-permission="['ADMIN']"
app.directive('permission', permission)

app.use(createPinia())
app.use(router)

// 刷新页面后：本地有 token 但无用户信息时，用 /auth/me 恢复会话与菜单；失败则清凭证交给守卫跳登录
const auth = useAuthStore()
const bootstrap =
  auth.token && !auth.user
    ? getUserInfo()
        .then((user) => auth.setUser(user))
        .catch(() => auth.clearAuth())
    : Promise.resolve()

bootstrap.finally(() => app.mount('#app'))
