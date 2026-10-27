import { test, expect } from '../fixtures/app.js'
import { factories } from '../fixtures/api.js'

/**
 * TC-FE-07 权限模块 CRUD（含「生活区」分支）
 * 生活区是 GATE_AREA 映射（西门→生活区）中存在、但演示种子数据从未覆盖的分支，本用例专门覆盖。
 */
test('TC-FE-07 权限 CRUD 与四区域下拉完整性（含生活区）', async ({ page, ui, api, apiURL, loginAs }) => {
  const token = await loginAs('admin')
  const suffix = Date.now() % 1000
  const emp = factories.employee(suffix)
  const empId = await api.createEmployee(api.ctx, apiURL, token, emp)

  await page.goto('/access/permissions')

  // ---- 新增：区域选「生活区」（种子数据未覆盖分支）----
  await ui.button('新增权限').click()
  const dialog = ui.dialog('新增权限')
  await expect(dialog).toBeVisible()
  await dialog.locator('.el-form-item:has(label:has-text("目标类型")) .el-select').click()
  await page.locator('.el-select-dropdown__item:visible', { hasText: 'PERSON' }).first().click()
  await dialog.locator('.el-form-item:has(label:has-text("目标编号")) input').first().fill(emp.empNo)
  await dialog.locator('.el-form-item:has(label:has-text("目标名称")) input').first().fill(emp.name)

  // 断言点①：区域下拉必须含且仅含 4 个合法值（办公区/生产区/仓储区/生活区）
  await dialog.locator('.el-form-item:has(label:has-text("区域")) .el-select').click()
  const areaOptions = page.locator('.el-select-dropdown__item:visible')
  const areaTexts = (await areaOptions.allInnerTexts()).map((t) => t.trim()).filter(Boolean)
  expect(areaTexts.sort()).toEqual(['仓储区', '办公区', '生产区', '生活区'].sort())
  await areaOptions.filter({ hasText: '生活区' }).first().click()

  await ui.dateTime('生效时间', '2026-01-01 00:00:00')
  await ui.dateTime('失效时间', '2099-12-31 23:59:59')
  await dialog.getByRole('button', { name: '保存' }).click()
  await expect(ui.message()).toContainText('新增成功')

  const row = ui.rowContaining(emp.empNo)
  await expect(row).toContainText('生活区')
  await expect(row).toContainText('生效')

  // ---- 筛选：按目标编号 + 区域=生活区 联合筛选 ----
  await page.locator('.el-form-item:has(label:has-text("目标编号")) input').first().fill(emp.empNo)
  await page.locator('.el-form-item:has(label:has-text("区域")) .el-select').click()
  await page.locator('.el-select-dropdown__item:visible', { hasText: '生活区' }).first().click()
  await ui.button('查询').click()
  await expect(ui.rows()).toHaveCount(1)

  // ---- 删除 ----
  await ui.linkButton('删除').click()
  await page.locator('.el-message-box__btns .el-button--primary').click()
  await expect(ui.message()).toContainText('删除成功')

  await api.cleanup(api.ctx, apiURL, token, 'employees', empId)
})
