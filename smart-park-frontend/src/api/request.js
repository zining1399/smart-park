import axios from 'axios'
import { ElMessage } from 'element-plus/es/components/message/index.mjs'
import { tokenStorage } from '../utils/token'
import router from '../router'

// 后端冻结错误码：HTTP 状态码 + body.code 双判断，前端 hard-code 分支（联调期不得变更）
export const ERROR_CODE = {
  TOKEN_INVALID: 40101, // 401 token 缺失/过期/无效
  BAD_CREDENTIALS: 40102, // 401 账号密码错误（仅 /auth/login）
  ACCOUNT_DISABLED: 40103, // 401 账号禁用（仅 /auth/login）
  FORBIDDEN: 40301, // 403 无角色权限
  BAD_REQUEST: 40001, // 400 参数校验失败
  NOT_FOUND: 40401, // 404 资源不存在
  CONFLICT: 40901, // 409 状态冲突/重复操作
  SERVER_ERROR: 50001 // 500 系统异常
}

// 变量命名与运维侧对齐：优先 VITE_API_BASE_URL（部署方案/ Dockerfile 使用的名字），
// 兼容 VITE_API_BASE；两者都未配时默认 /api（Nginx 同域反代，零 CORS 依赖）
const BASE_URL = import.meta.env.VITE_API_BASE_URL || import.meta.env.VITE_API_BASE || '/api'

const request = axios.create({
  baseURL: BASE_URL,
  timeout: 15000
})

// 刷新令牌专用实例：不带拦截器，避免刷新请求自身进入 40101 重试逻辑形成死循环
const plainRequest = axios.create({ baseURL: BASE_URL, timeout: 15000 })

function genTraceId() {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

// ---------- 错误提示去重（同文案 3 秒内不重复弹） ----------
const lastNotifyAt = new Map()
function notify(message, type = 'error') {
  const now = Date.now()
  const last = lastNotifyAt.get(message) || 0
  if (now - last < 3000) return
  lastNotifyAt.set(message, now)
  ElMessage({ message, type, grouping: true, showClose: true })
}

function withTrace(message, traceId) {
  return traceId ? `${message}（追踪号 ${traceId}）` : message
}

// ---------- 跳转登录（防抖，保证只跳一次） ----------
let redirecting = false
function redirectToLogin(tip) {
  tokenStorage.clear()
  if (redirecting) return
  if (router.currentRoute.value && router.currentRoute.value.path === '/login') return
  redirecting = true
  const redirect = window.location.pathname + window.location.search + window.location.hash
  if (tip) notify(tip, 'warning')
  router.replace({ path: '/login', query: { redirect } }).finally(() => {
    redirecting = false
  })
}

// ---------- 40101 单飞 refresh：并发请求只触发一次刷新，其余排队重放 ----------
let isRefreshing = false
const waitingQueue = []

function flushQueue(token, err) {
  const queue = waitingQueue.splice(0)
  queue.forEach((item) => (err ? item.reject(err) : item.resolve(token)))
}

function enqueue(config) {
  return new Promise((resolve, reject) => {
    waitingQueue.push({
      resolve: (token) => {
        config.headers = config.headers || {}
        config.headers.Authorization = `Bearer ${token}`
        config._retried = true
        resolve(request(config))
      },
      reject
    })
  })
}

async function handleTokenExpired(config, error) {
  const refreshToken = tokenStorage.getRefreshToken()
  // 无 refresh 凭据 / 已重试过一次 / 刷新请求自身失败 → 直接踢登录
  if (!refreshToken || config._retried || config.skipAuthRefresh) {
    redirectToLogin('登录已失效，请重新登录')
    return Promise.reject(error)
  }

  // 已有刷新在进行 → 挂起，等待新 token 后统一重放
  if (isRefreshing) return enqueue(config)

  isRefreshing = true
  try {
    const res = await plainRequest.post('/auth/refresh', { refreshToken })
    const body = res.data || {}
    const data = body.code === 0 ? body.data : null
    if (!data || !data.token) throw new Error('刷新令牌失败')

    // refresh 每次轮换：新 access + 新 refresh 一并落盘
    tokenStorage.set({ token: data.token, refreshToken: data.refreshToken, user: data.user || undefined })
    try {
      const { useAuthStore } = await import('../stores/auth')
      useAuthStore().setAuth(data)
    } catch {
      /* 应用未挂载时仅落盘，不影响请求重放 */
    }

    isRefreshing = false
    flushQueue(data.token)

    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${data.token}`
    config._retried = true
    return request(config)
  } catch (e) {
    isRefreshing = false
    flushQueue(null, e)
    redirectToLogin('登录已失效，请重新登录')
    return Promise.reject(error)
  }
}

// ---------- 请求拦截器：注入 token 与 traceId ----------
request.interceptors.request.use(
  (config) => {
    const token = tokenStorage.getToken()
    if (token) config.headers.Authorization = `Bearer ${token}`
    config.headers['X-Trace-Id'] = config.headers['X-Trace-Id'] || genTraceId()
    return config
  },
  (error) => Promise.reject(error)
)

// ---------- 响应拦截器 ----------
request.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && body.code === 0) {
      return body.data
    }
    // 兜底：个别接口仍返回 HTTP 200 但 code !== 0
    const message = (body && body.message) || '请求失败'
    notify(message, 'error')
    return Promise.reject(new Error(message))
  },
  async (error) => {
    const res = error.response
    const config = error.config || {}
    const body = res && res.data
    const code = body && body.code
    const traceId = (res && res.headers && (res.headers['x-trace-id'] || res.headers['X-Trace-Id'])) || ''
    const serverMessage = (body && body.message) || ''

    if (res && res.status === 401) {
      if (code === ERROR_CODE.TOKEN_INVALID) {
        return handleTokenExpired(config, error)
      }
      // 40102 / 40103：仅登录页会出现，只提示不跳转
      if (code === ERROR_CODE.BAD_CREDENTIALS || code === ERROR_CODE.ACCOUNT_DISABLED) {
        notify(serverMessage || '用户名或密码错误', 'error')
        return Promise.reject(error)
      }
      notify(serverMessage || '登录已失效，请重新登录', 'error')
      return Promise.reject(error)
    }

    if (res && res.status === 403) {
      notify(serverMessage || '无操作权限', 'warning')
      return Promise.reject(error)
    }

    if (code === ERROR_CODE.SERVER_ERROR || (res && res.status >= 500)) {
      // 系统异常兜底文案，附追踪号便于 grep 服务端日志
      notify(withTrace('系统繁忙，请稍后重试', traceId), 'error')
      return Promise.reject(error)
    }

    // 40001 / 40401 / 40901 等：直接展示后端中文文案（后端已保证面向用户）
    const fallback = error.code === 'ECONNABORTED' ? '请求超时，请检查网络' : '网络异常，请稍后重试'
    notify(serverMessage || fallback, 'error')
    return Promise.reject(error)
  }
)

export default request
