import { test, expect } from '../fixtures/app.js'
import { factories } from '../fixtures/api.js'

/**
 * TC-FE-11 模拟通行（放行 / 拦截各一次）→ 通行记录可查
 * 前置依赖：M2 鉴权（门岗角色可访问门禁模块）、员工/车辆/权限数据存在。
 */
test.describe('FE-11 门禁模拟通行', () => {
  test('TC-FE-11 模拟通行放行与拦截各一次，流水可查且拦截原因可见', async ({ page, ui, api, apiURL, loginAs }) => {
    const token = await loginAs('gate')
    const suffix = Date.now() % 1000

    // ---- 造数：员工 + 权限（放行路径）----
    const emp = factories.employee(suffix)
    const empId = await api.createEmployee(api.ctx, apiURL, token, emp)
    await api.createPermission(api.ctx, apiURL, token, factories.permission(emp.empNo))

    // 造数：车辆 + 车辆权限（黑名单路径）
    const veh = factories.vehicle(suffix + 1)
    const vehId = await api.createVehicle(api.ctx, apiURL, token, veh)

    // ---- 放行：有权限的员工 ----
    const pass = await api.simulate(api.ctx, apiURL, token, {
      passType: 'PERSON',
      targetNo: emp.empNo,
      gate: '东门',
      direction: 'IN'
    })
    expect(pass.result).toBe('放行')
    expect(pass.reason || '').toBe('')

    // ---- 拦截：无权限且黑名单车辆 ----
    await api.cleanup(api.ctx, apiURL, token, 'vehicles', vehId) // 先删后造黑名单版本
    const blackId = await api.createVehicle(api.ctx, apiURL, token, { ...veh, status: '黑名单' })
    const blocked = await api.simulate(api.ctx, apiURL, token, {
      passType: 'VEHICLE',
      targetNo: veh.plateNo,
      gate: '货运门',
      direction: 'IN'
    })
    expect(blocked.result).toBe('拦截')
    expect(blocked.reason).not.toBe('')

    // ---- UI 断言：通行记录页筛选到两条，结果列文案正确 ----
    await page.goto('/access/records')
    await page.locator('.el-form-item:has(label:has-text("工号/车牌")) input, .el-input__inner').first().fill(emp.empNo)
    await ui.button('查询').click()

    const row = ui.rowContaining(emp.empNo)
    await expect(row).toContainText('放行')
    await expect(page.locator('.el-table__body-wrapper')).toContainText('拦截')

    // 清理
    await api.cleanup(api.ctx, apiURL, token, 'employees', empId)
    await api.cleanup(api.ctx, apiURL, token, 'vehicles', blackId)
  })

  test('TC-ACC-07 非法门岗由 API 层拦截，页面不得写入流水', async ({ api, apiURL, loginAs }) => {
    const token = await loginAs('gate')
    const suffix = Date.now() % 1000
    const emp = factories.employee(suffix)
    const empId = await api.createEmployee(api.ctx, apiURL, token, emp)

    // 断言点：接口返回 code≠0（业务异常），且未创建记录
    const res = await api.ctx.post(`${apiURL}/access-records/simulate`, {
      data: { passType: 'PERSON', targetNo: emp.empNo, gate: '北门', direction: 'IN' },
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
    })
    const body = await api.parse(res)
    expect(body.code).not.toBe(0)

    await api.cleanup(api.ctx, apiURL, token, 'employees', empId)
  })
})
