import { test, expect } from '../fixtures/app.js'
import { factories } from '../fixtures/api.js'

/** TC-FE-04 员工模块：新增 → 查询筛选 → 编辑 → 删除（完整 CRUD 闭环） */
test('TC-FE-04 员工 CRUD 与查询筛选闭环', async ({ page, ui, api, apiURL, loginAs }) => {
  const token = await loginAs('admin')
  const suffix = Date.now() % 1000
  const emp = factories.employee(suffix)

  await page.goto('/access/employees')

  // ---- 新增 ----
  await ui.button('新增员工').click()
  const dialog = ui.dialog('新增员工')
  await expect(dialog).toBeVisible()
  await dialog.locator('.el-form-item:has(label:has-text("工号")) input').first().fill(emp.empNo)
  await dialog.locator('.el-form-item:has(label:has-text("姓名")) input').first().fill(emp.name)
  await dialog.locator('.el-form-item:has(label:has-text("部门")) input').first().fill(emp.dept)
  await dialog.locator('.el-form-item:has(label:has-text("手机号")) input').first().fill(emp.phone)
  await dialog.locator('.el-form-item:has(label:has-text("状态")) .el-select').click()
  await page.locator('.el-select-dropdown__item:visible', { hasText: '在职' }).first().click()
  await dialog.getByRole('button', { name: '保存' }).click()

  // 断言点①：新增成功提示 + 新行出现 + 状态为在职
  await expect(ui.message()).toContainText('新增成功')
  await expect(ui.rowContaining(emp.empNo)).toContainText('在职')

  // ---- 查询筛选 ----
  await page.locator('.el-form-item:has(label:has-text("姓名")) input').first().fill(emp.name)
  await ui.button('查询').click()
  await expect(ui.rows()).toHaveCount(1)
  await expect(ui.rowContaining(emp.empNo)).toBeVisible()

  // ---- 编辑 ----
  await ui.linkButton('编辑').click()
  const editDialog = ui.dialog('编辑员工')
  await editDialog.locator('.el-form-item:has(label:has-text("姓名")) input').first().fill(`${emp.name}-改`)
  await editDialog.getByRole('button', { name: '保存' }).click()
  await expect(ui.message()).toContainText('修改成功')
  await expect(ui.rowContaining(emp.empNo)).toContainText(`${emp.name}-改`)

  // ---- 重置按钮 ----
  await ui.button('重置').click()
  await expect(page.locator('.el-form-item:has(label:has-text("姓名")) input').first()).toHaveValue('')

  // ---- 删除（含二次确认）----
  await page.locator('.el-form-item:has(label:has-text("姓名")) input').first().fill(emp.name)
  await ui.button('查询').click()
  await ui.linkButton('删除').click()
  await page.locator('.el-message-box__btns .el-button--primary').click()
  await expect(ui.message()).toContainText('删除成功')
  await expect(ui.rowContaining(emp.empNo)).toHaveCount(0)

  // 断言点②：接口侧确认已删除（避免仅前端移除）
  const res = await api.ctx.get(`${apiURL}/employees?pageNum=1&pageSize=10&name=${encodeURIComponent(emp.name)}`, {
    headers: { Authorization: `Bearer ${token}` }
  })
  const data = await api.expectOk(res, '员工列表')
  expect(data.list.filter((e) => e.empNo === emp.empNo)).toHaveLength(0)
})
