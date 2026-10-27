/**
 * 接口层 helper：封装 /api 契约调用与统一响应体解析。
 * 统一响应体：{ code: 0, message: 'ok', data: {...} }；业务错误 code != 0。
 * 页面类断言走 UI，断言"接口确实拒绝"走这里（接口级用例在 backend 集成测试里，此文件只服务 E2E 的前置造数与反查）。
 */
export const API = {
  /** 登录取 token。M2 落地后接口路径/字段以 api-contract 增补为准。 */
  async login(request, apiURL, username, password) {
    const res = await request.post(`${apiURL}/auth/login`, {
      data: { username, password },
      headers: { 'Content-Type': 'application/json' }
    })
    return res
  },

  /** 解析统一响应体，返回 { code, message, data } */
  async parse(res) {
    const body = await res.json().catch(() => null)
    if (!body) return { code: -1, message: '响应体不是合法 JSON', data: null }
    return { code: body.code, message: body.message, data: body.data }
  },

  /** 断言业务成功（code === 0）并返回 data */
  async expectOk(res, label = '') {
    const { code, message, data } = await API.parse(res)
    if (code !== 0) {
      throw new Error(`${label} 期望 code=0，实际 code=${code}，message=${message}`)
    }
    return data
  },

  /** 造数：员工。返回 id，测试结束由调用方决定是否清理。 */
  async createEmployee(request, apiURL, token, payload) {
    const res = await request.post(`${apiURL}/employees`, {
      data: payload,
      headers: API.authHeader(token)
    })
    return (await API.expectOk(res, '创建员工')).id
  },

  /** 造数：车辆 */
  async createVehicle(request, apiURL, token, payload) {
    const res = await request.post(`${apiURL}/vehicles`, {
      data: payload,
      headers: API.authHeader(token)
    })
    return (await API.expectOk(res, '创建车辆')).id
  },

  /** 造数：物资 */
  async createMaterial(request, apiURL, token, payload) {
    const res = await request.post(`${apiURL}/materials`, {
      data: payload,
      headers: API.authHeader(token)
    })
    return (await API.expectOk(res, '创建物资')).id
  },

  /** 造数：权限 */
  async createPermission(request, apiURL, token, payload) {
    const res = await request.post(`${apiURL}/permissions`, {
      data: payload,
      headers: API.authHeader(token)
    })
    return (await API.expectOk(res, '创建权限')).id
  },

  /** 造数：访客预约 */
  async createVisitor(request, apiURL, token, payload) {
    const res = await request.post(`${apiURL}/visitors`, {
      data: payload,
      headers: API.authHeader(token)
    })
    return (await API.expectOk(res, '创建访客')).id
  },

  /** 模拟通行（等价于终端上报） */
  async simulate(request, apiURL, token, payload) {
    const res = await request.post(`${apiURL}/access-records/simulate`, {
      data: payload,
      headers: API.authHeader(token)
    })
    return API.expectOk(res, '模拟通行')
  },

  /** 申领出库 */
  async stockOut(request, apiURL, token, payload) {
    const res = await request.post(`${apiURL}/stock-records/out`, {
      data: payload,
      headers: API.authHeader(token)
    })
    return API.expectOk(res, '申领出库')
  },

  /** 通用清理：删除各模块记录，失败不抛错（清理不是断言点） */
  async cleanup(request, apiURL, token, resource, id) {
    try {
      await request.delete(`${apiURL}/${resource}/${id}`, { headers: API.authHeader(token) })
    } catch {
      /* 清理失败不影响用例结论 */
    }
  },

  authHeader(token) {
    return token ? { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } : { 'Content-Type': 'application/json' }
  }
}

/** 造数数据工厂：集中放测试专属数据，避免与演示 data.sql 混用 */
export const factories = {
  empNo: (suffix) => `E9${String(suffix).padStart(3, '0')}`,
  plateNo: (suffix) => `新A9${String(suffix).padStart(3, '0')}`,
  materialCode: (suffix) => `M-E2E-${String(suffix).padStart(3, '0')}`,
  employee: (suffix = Date.now() % 1000) => ({
    empNo: factories.empNo(suffix),
    name: `E2E员工${suffix}`,
    dept: '生产部',
    phone: '13900000000',
    status: '在职'
  }),
  vehicle: (suffix = Date.now() % 1000) => ({
    plateNo: factories.plateNo(suffix),
    ownerType: '员工',
    ownerName: `E2E车主${suffix}`,
    dept: '生产部',
    status: '正常'
  }),
  material: (suffix = Date.now() % 1000, valuable = false) => ({
    code: factories.materialCode(suffix),
    name: `E2E物资${suffix}`,
    category: '工具',
    spec: 'E2E',
    dept: '生产部',
    location: 'A-01',
    valuable,
    status: '在库'
  }),
  permission: (targetNo, area = '办公区') => ({
    targetType: 'PERSON',
    targetNo,
    targetName: 'E2E权限',
    area,
    startTime: '2026-01-01 00:00:00',
    endTime: '2099-12-31 23:59:59',
    status: '生效'
  }),
  visitor: (suffix = Date.now() % 1000) => ({
    visitorName: `E2E访客${suffix}`,
    idCard: '110101199001011234', // 测试专用假号码；接口返回应脱敏为 110101********1234
    phone: '13900000000',
    hostName: 'E2E被访人',
    hostDept: '生产部',
    reason: 'E2E 预约',
    area: '办公区',
    appointTime: '2026-01-01 08:00:00',
    expireTime: '2099-12-31 23:59:59',
    status: '已预约'
  })
}
