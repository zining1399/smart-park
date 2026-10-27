import request from './request'

/* ========== 鉴权（与后端冻结契约一致） ========== */
// 登录：匿名放行，返回 { token, refreshToken, expiresIn, user }
// 失败：HTTP 401 + code 40102（密码错）/ 40103（账号禁用），由拦截器统一提示且不跳转
export function login(data) {
  return request.post('/auth/login', data)
}

// 当前登录用户：刷新页面后用它恢复会话与菜单（返回 user 对象）
export function getUserInfo() {
  return request.get('/auth/me')
}

// 刷新令牌：入参 { refreshToken }，返回结构与登录一致，refresh 每次轮换。
// skipAuthRefresh 标记避免刷新请求自身进入 40101 重试逻辑造成死循环
export function refreshToken(refreshToken) {
  return request.post('/auth/refresh', { refreshToken }, { skipAuthRefresh: true })
}

// 注：后端为无状态 JWT，无服务端登出端点，登出由前端丢弃凭证完成
