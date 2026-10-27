import { test, expect } from '../fixtures/app.js'
import { factories } from '../fixtures/api.js'

/**
 * TC-FE-09 普通物资出库→归还
 * TC-FE-10 贵重物资出库→审批→出库
 * TC-FE-15 出入库类型筛选下拉不含 RETURN（裁决 15）
 *
 * ✅ M3 前端已删除 <el-option value="RETURN" /> 与 typeText 的三分支，本用例转 PASS。
 *    保留它作为回归哨兵：一旦有人把 RETURN 加回来，本用例立刻 FAIL。
 *    （未知类型的 fallback 也已改为 '-'，不会误导成「归还」。）
 */

const EXPECTED_TYPES = ['入库', '出库']

test('TC-FE-09 普通物资申领出库后状态变「在借」，归还后回「在库」', async ({ page, ui, api, apiURL, loginAs }) => {
  const token = await loginAs('admin')
  const suffix = Date.now() % 1000
  const mat = factories.material(suffix, false)
  const matId = await api.createMaterial(api.ctx, apiURL, token, mat)

  await page.goto('/stock/records')

  // ---- 申领出库（免审）----
  await ui.button('申领出库').click()
  const outDialog = ui.dialog('申领出库')
  await expect(outDialog).toBeVisible()
  await outDialog.locator('.el-form-item:has(label:has-text("物资")) .el-select').click()
  await page.locator('.el-select-dropdown__item:visible', { hasText: mat.code }).first().click()
  await outDialog.locator('.el-form-item:has(label:has-text("领用人")) input').first().fill('E2E领用人')
  await outDialog.locator('.el-form-item:has(label:has-text("领用部门")) input').first().fill('生产部')
  await ui.dateTime('预计归还', '2099-12-31 23:59:59')
  await outDialog.getByRole('button', { name: '提交申领' }).click()

  // 断言点①：免审出库成功提示（不是"已提交审批"）+ 审批状态=免审
  await expect(ui.message()).toContainText('出库成功')
  const row = ui.rowContaining(mat.code)
  await expect(row).toContainText('免审')

  // 断言点②：物资台账状态变为「在借」
  await page.goto('/stock/materials')
  await page.locator('.el-form-item:has(label:has-text("编码")) input').first().fill(mat.code)
  await ui.button('查询').click()
  await expect(ui.rowContaining(mat.code)).toContainText('在借')

  // ---- 归还 ----
  await page.goto('/stock/records')
  await page.locator('.el-form-item:has(label:has-text("物资名称")) input').first().fill(mat.name)
  await ui.button('查询').click()
  await ui.linkButton('归还').click()
  await page.locator('.el-message-box__btns .el-button--primary').click()
  await expect(ui.message()).toContainText('归还成功')

  // 断言点③：物资状态回「在库」，且归还后不出现「归还」类型新记录（TC-STK-25 前端侧佐证）
  await page.goto('/stock/materials')
  await page.locator('.el-form-item:has(label:has-text("编码")) input').first().fill(mat.code)
  await ui.button('查询').click()
  await expect(ui.rowContaining(mat.code)).toContainText('在库')

  await api.cleanup(api.ctx, apiURL, token, 'materials', matId)
})

test('TC-FE-10 贵重物资出库进入「待审批」，批准后完成出库', async ({ page, ui, api, apiURL, loginAs }) => {
  const token = await loginAs('admin')
  const suffix = (Date.now() + 1) % 1000
  const mat = factories.material(suffix, true)
  const matId = await api.createMaterial(api.ctx, apiURL, token, mat)

  await page.goto('/stock/records')

  // ---- 申领出库（贵重→待审批）----
  await ui.button('申领出库').click()
  const outDialog = ui.dialog('申领出库')
  await outDialog.locator('.el-form-item:has(label:has-text("物资")) .el-select').click()
  await page.locator('.el-select-dropdown__item:visible', { hasText: '贵重需审批' }).first().click()
  await outDialog.locator('.el-form-item:has(label:has-text("领用人")) input').first().fill('E2E领用人')
  await outDialog.locator('.el-form-item:has(label:has-text("领用部门")) input').first().fill('生产部')
  await ui.dateTime('预计归还', '2099-12-31 23:59:59')
  await outDialog.getByRole('button', { name: '提交申领' }).click()

  // 断言点①：提示走审批 + 记录审批状态=待审批 + 出现「批准/驳回」按钮
  await expect(ui.message()).toContainText('审批')
  const row = ui.rowContaining(mat.code)
  await expect(row).toContainText('待审批')
  await expect(ui.linkButton('批准')).toBeVisible()

  // 断言点②：待审批期间物资状态仍为「在库」（未实际出库）
  await page.goto('/stock/materials')
  await page.locator('.el-form-item:has(label:has-text("编码")) input').first().fill(mat.code)
  await ui.button('查询').click()
  await expect(ui.rowContaining(mat.code)).toContainText('在库')

  // ---- 批准 ----
  await page.goto('/stock/records')
  await page.locator('.el-form-item:has(label:has-text("物资名称")) input').first().fill(mat.name)
  await ui.button('查询').click()
  await ui.linkButton('批准').click()
  await page.locator('.el-message-box__btns .el-button--primary').click()
  await expect(ui.message()).toContainText('已批准')
  await expect(ui.rowContaining(mat.code)).toContainText('已批准')

  // 断言点③：批准后物资状态变「在借」
  await page.goto('/stock/materials')
  await page.locator('.el-form-item:has(label:has-text("编码")) input').first().fill(mat.code)
  await ui.button('查询').click()
  await expect(ui.rowContaining(mat.code)).toContainText('在借')

  await api.cleanup(api.ctx, apiURL, token, 'materials', matId)
})

test('TC-FE-15 出入库类型筛选下拉不含 RETURN 选项（裁决 15）', async ({ page, ui, loginAs }) => {
  await loginAs('admin')
  await page.goto('/stock/records')

  // 打开「类型」筛选下拉
  await page.locator('.el-form-item:has(label:has-text("类型")) .el-select').click()
  const optionTexts = (await page.locator('.el-select-dropdown__item:visible').allInnerTexts()).map((t) => t.trim()).filter(Boolean)

  // 断言点：类型选项恰好为 入库/出库；出现「归还」= 回归缺陷，立即上报
  expect(optionTexts.sort()).toEqual([...EXPECTED_TYPES].sort())
  expect(optionTexts).not.toContain('归还')
})
