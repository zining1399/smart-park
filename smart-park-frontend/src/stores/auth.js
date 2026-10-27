import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { tokenStorage } from '../utils/token'

// 角色码与后端冻结契约一致：ADMIN 管理员 / GATE 门岗 / STORE 仓管
export const ROLES = {
  ADMIN: 'ADMIN',
  GATE: 'GATE',
  STORE: 'STORE'
}

export const ROLE_LABELS = {
  [ROLES.ADMIN]: '管理员',
  [ROLES.GATE]: '门岗',
  [ROLES.STORE]: '仓管'
}

export const useAuthStore = defineStore('auth', () => {
  // 初始值直接从 localStorage 恢复，刷新页面无需重新登录
  const token = ref(tokenStorage.getToken())
  const refreshToken = ref(tokenStorage.getRefreshToken())
  const user = ref(tokenStorage.getUser())

  const isLogin = computed(() => !!token.value && !!user.value)
  const role = computed(() => (user.value && user.value.role) || '')
  const realName = computed(() => (user.value && user.value.realName) || (user.value && user.value.username) || '')
  const roleLabel = computed(() => ROLE_LABELS[role.value] || role.value)

  // 是否有角色权限：不传 roles 或传空数组表示不限制
  function hasPermission(roles) {
    if (!roles || (Array.isArray(roles) && roles.length === 0)) return true
    const expected = Array.isArray(roles) ? roles : [roles]
    return expected.includes(role.value)
  }

  // 登录/刷新成功：{ token, refreshToken, expiresIn, user }
  function setAuth(data) {
    if (!data) return
    if (data.token) token.value = data.token
    if (data.refreshToken) refreshToken.value = data.refreshToken
    if (data.user) user.value = data.user
    tokenStorage.set({ token: data.token, refreshToken: data.refreshToken, user: data.user })
  }

  function setUser(info) {
    user.value = info
    tokenStorage.set({ user: info })
  }

  // 登出：后端为无状态 JWT、无服务端登出端点，前端丢弃本地凭证即可
  function clearAuth() {
    token.value = ''
    refreshToken.value = ''
    user.value = null
    tokenStorage.clear()
  }

  return { token, refreshToken, user, isLogin, role, realName, roleLabel, hasPermission, setAuth, setUser, clearAuth }
})
