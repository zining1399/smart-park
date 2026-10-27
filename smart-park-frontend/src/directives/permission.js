import { useAuthStore } from '../stores/auth'

// 按钮级权限：v-permission="['ADMIN']" 或 v-permission="'ADMIN'"
// 说明：菜单/路由负责页面级拦截，本指令负责页面内的操作按钮；
// 采用隐藏而非移除 DOM 的方式，避免 Vue 后续 patch 时找不到已摘除的节点。
export default {
  mounted(el, binding) {
    const auth = useAuthStore()
    if (!auth.hasPermission(binding.value)) {
      el.style.display = 'none'
      el.setAttribute('aria-hidden', 'true')
    }
  }
}
