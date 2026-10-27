import { test, expect } from '../fixtures/app.js'
import { factories } from '../fixtures/api.js'

/** TC-FE-06 物资模块：新增（贵重）→ 按状态筛选 → 编辑 → 删除 */
test('TC-FE-06 物资 CRUD 与贵重标记、状态筛选', async ({ page, ui, api, apiURL, loginAs }) => {
  const token = await loginAs('admin')
  const suffix = Date.now() % 1000
  const mat = factories.material(suffix, true) // 贵重物资，用于后续出入库审批链路

  await page.goto('/stock/materials')

  // ---- 新增 ----
  await ui.button('新增物资').click()
  const dialog = ui.dialog('新增物资')
  await expect(dialog).toBeVisible()
  await dialog.locator('.el-form-item:has(label:has-text("编码")) input').first().fill(mat.code)
  await dialog.locator('.el-form-item:has(label:has-text("物资名称")) input').first().fill(mat.name)
  await dialog.locator('.el-form-item:has(label:has-text("类别")) .el-select').click()
  await page.locator('.el-select-dropdown__item:visible', { hasText: '工具' }).first().click()
  await dialog.locator('.el-form-item:has(label:has-text("规格型号")) input').first().fill(mat.spec)
  await dialog.locator('.el-form-item:has(label:has-text("责任部门")) input').first().fill(mat.dept)
  await dialog.locator('.el-form-item:has(label:has-text("存放位置")) input').first().fill(mat.location)

  // 贵重开关：el-switch（label「是否贵重」）
  await dialog.locator('.el-form-item:has(label:has-text("是否贵重")) .el-switch').click()
  await dialog.getByRole('button', { name: '保存' }).click()

  // 断言点①：新增成功 + 列表显示「贵重」标签 + 状态「在库」
  await expect(ui.message()).toContainText('新增成功')
  const row = ui.rowContaining(mat.code)
  await expect(row).toContainText('贵重')
  await expect(row).toContainText('在库')

  // ---- 按状态筛选 ----
  await page.locator('.el-form-item:has(label:has-text("状态")) .el-select').click()
  await page.locator('.el-select-dropdown__item:visible', { hasText: '在库' }).first().click()
  await ui.button('查询').click()
  await expect(ui.rowContaining(mat.code)).toBeVisible()

  // ---- 编辑：贵重改普通 ----
  await ui.linkButton('编辑').click()
  const editDialog = ui.dialog('编辑物资')
  await editDialog.locator('.el-form-item:has(label:has-text("存放位置")) input').first().fill('B-02')
  await editDialog.getByRole('button', { name: '保存' }).click()
  await expect(ui.message()).toContainText('修改成功')

  // ---- 删除 ----
  await page.locator('.el-form-item:has(label:has-text("编码")) input').first().fill(mat.code)
  await ui.button('查询').click()
  await ui.linkButton('删除').click()
  await page.locator('.el-message-box__btns .el-button--primary').click()
  await expect(ui.message()).toContainText('删除成功')

  // 断言点②：接口侧确认删除
  const res = await api.ctx.get(`${apiURL}/materials?pageNum=1&pageSize=10&code=${encodeURIComponent(mat.code)}`, {
    headers: { Authorization: `Bearer ${token}` }
  })
  const data = await api.expectOk(res, '物资列表')
  expect(data.list.filter((m) => m.code === mat.code)).toHaveLength(0)
})
