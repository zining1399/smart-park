// 凭证本地持久化：localStorage 三把 key（sp_token / sp_refresh / sp_user）
// 单独抽成工具模块，让 axios 拦截器不依赖 Pinia 实例（避免"getActivePinia 未激活"问题）
const TOKEN_KEY = 'sp_token'
const REFRESH_KEY = 'sp_refresh'
const USER_KEY = 'sp_user'

function readUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch {
    return null
  }
}

export const tokenStorage = {
  getToken: () => localStorage.getItem(TOKEN_KEY) || '',
  getRefreshToken: () => localStorage.getItem(REFRESH_KEY) || '',
  getUser: readUser,
  set({ token, refreshToken, user }) {
    if (token) localStorage.setItem(TOKEN_KEY, token)
    if (refreshToken) localStorage.setItem(REFRESH_KEY, refreshToken)
    if (user) localStorage.setItem(USER_KEY, JSON.stringify(user))
  },
  clear() {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(REFRESH_KEY)
    localStorage.removeItem(USER_KEY)
  }
}
