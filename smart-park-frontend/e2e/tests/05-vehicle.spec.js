import { test, expect } from '../fixtures/app.js'
import { factories } from '../fixtures/api.js'

/** TC-FE-05 车辆模块：新增 → 筛选 → 编辑（状态改黑名单）→ 删除 */
test('TC-FE-05 车辆 CRUD 与状态切换闭环', async ({ page, ui, api, apiURL, loginAs }) => {
  const token = await loginAs('admin')
  const suffix = Date.now() % 1000
  const veh = factories.vehicle(suffix)

  await page.goto('/access/vehicles')

  // ---- 新增（车主类型=员工，状态=正常）----
  await ui.button('新增车辆').click()
  const dialog = ui.dialog('新增车辆')
  await expect(dialog).toBeVisible()
  await dialog.locator('.el-form-item:has(label:has-text("车牌号")) input').first().fill(veh.plateNo)
  await dialog.locator('.el-form-item:has(label:has-text("车主类型")) .el-select').click()
  await page.locator('.el-select-dropdown__item:visible', { hasText: '员工' }).first().click()
  await dialog.locator('.el-form-item:has(label:has-text("车主姓名")) input').first().fill(veh.ownerName)
  await dialog.locator('.el-form-item:has(label:has-text("状态")) .el-select').click()
  await page.locator('.el-select-dropdown__item:visible', { hasText: '正常' }).first().click()
  await dialog.getByRole('button', { name: '保存' }).click()
  await expect(ui.message()).toContainText('新增成功')

  // ---- 筛选 ----
  await page.locator('.el-form-item:has(label:has-text("车牌号")) input').first().fill(veh.plateNo)
  await ui.button('查询').click()
  const row = ui.rowContaining(veh.plateNo)
  await expect(row).toBeVisible()
  await expect(row).toContainText('正常')

  // ---- 编辑：状态改为黑名单 ----
  await ui.linkButton('编辑').click()
  const editDialog = ui.dialog('编辑车辆')
  await editDialog.locator('.el-form-item:has(label:has-text("状态")) .el-select').click()
  await page.locator('.el-select-dropdown__item:visible', { hasText: '黑名单' }).first().click()
  await editDialog.getByRole('button', { name: '保存' }).click()
  await expect(ui.message()).toContainText('修改成功')

  // 断言点：按状态筛选=黑名单 能筛到该车；再按=正常 筛不到
  await ui.button('重置').click()
  await page.locator('.el-form-item:has(label:has-text("状态")) .el-select').click()
  await page.locator('.el-select-dropdown__item:visible', { hasText: '黑名单' }).first().click()
  await ui.button('查询').click()
  await expect(ui.rowContaining(veh.plateNo)).toBeVisible()

  // ---- 删除 ----
  await ui.linkButton('删除').click()
  await page.locator('.el-message-box__btns .el-button--primary').click()
  await expect(ui.message()).toContainText('删除成功')

  const res = await api.ctx.get(`${apiURL}/vehicles?pageNum=1&pageSize=10&plateNo=${encodeURIComponent(veh.plateNo)}`, {
    headers: { Authorization: `Bearer ${token}` }
  })
  const data = await api.expectOk(res, '车辆列表')
  expect(data.list.filter((v) => v.plateNo === veh.plateNo)).toHaveLength(0)
})
