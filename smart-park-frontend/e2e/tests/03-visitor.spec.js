import { test, expect } from '../fixtures/app.js'
import { factories } from '../fixtures/api.js'

/**
 * TC-FE-03 身份证脱敏展示 / TC-FE-08 访客预约→进场→签销全链路
 * 脱敏规则：保留前 6 后 4 位，中间打码（110101********1234）
 */
const MASKED_ID = /^\d{6}\*{4,}\d{4}$/

test.describe('FE-03 身份证脱敏', () => {
  test('TC-FE-03 访客列表身份证列仅显示脱敏值，明文 18 位不得出现在页面与响应体', async ({ page, ui, api, apiURL, loginAs }) => {
    const token = await loginAs('admin')
    const suffix = Date.now() % 1000
    const visitor = factories.visitor(suffix)
    const id = await api.createVisitor(api.ctx, apiURL, token, visitor)

    // 断言点①：列表接口响应体本身就是脱敏值（防脱敏只做在前端）
    const listRes = await api.ctx.get(`${apiURL}/visitors?pageNum=1&pageSize=10&visitorName=${encodeURIComponent(visitor.visitorName)}`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    const rawBody = await listRes.text()
    expect(rawBody).not.toContain('110101199001011234')
    const list = await api.expectOk(listRes, '访客列表')
    const idCard = list.list.find((v) => v.visitorName === visitor.visitorName)?.idCard
    expect(idCard).toMatch(MASKED_ID)

    // 断言点②：页面渲染为脱敏值，且 DOM 中不含明文
    await page.goto('/visitors')
    await page.locator('.el-form-item:has(label:has-text("访客姓名")) input').first().fill(visitor.visitorName)
    await ui.button('查询').click()
    const row = ui.rowContaining(visitor.visitorName)
    await expect(row.locator('td').nth(2)).toHaveText(new RegExp(MASKED_ID.source))
    expect(await page.content()).not.toContain('110101199001011234')

    await api.cleanup(api.ctx, apiURL, token, 'visitors', id)
  })
})

test.describe('FE-08 访客全链路', () => {
  test('TC-FE-08 预约登记 → 进场登记 → 离场签销，状态与按钮随状态机变化', async ({ page, ui, api, apiURL, loginAs }) => {
    const token = await loginAs('admin')
    const suffix = Date.now() % 1000
    const v = factories.visitor(suffix)

    // ---- 预约登记（UI）----
    await page.goto('/visitors')
    await ui.button('预约登记').click()
    const dialog = ui.dialog('预约登记')
    await expect(dialog).toBeVisible()
    await dialog.locator('.el-form-item:has(label:has-text("访客姓名")) input').first().fill(v.visitorName)
    await dialog.locator('.el-form-item:has(label:has-text("身份证号")) input').first().fill(v.idCard)
    await dialog.locator('.el-form-item:has(label:has-text("手机号")) input').first().fill(v.phone)
    await dialog.locator('.el-form-item:has(label:has-text("被访人")) input').first().fill(v.hostName)
    await dialog.locator('.el-form-item:has(label:has-text("被访部门")) input').first().fill(v.hostDept)
    await dialog.locator('.el-form-item:has(label:has-text("来访事由")) textarea').first().fill(v.reason)
    await dialog.locator('.el-form-item:has(label:has-text("访问区域")) .el-select').click()
    await page.locator('.el-select-dropdown__item:visible', { hasText: '办公区' }).first().click()
    await ui.dateTime('预约时间', v.appointTime)
    await ui.dateTime('失效时间', v.expireTime)
    await dialog.getByRole('button', { name: '保存' }).click()

    // 断言点①：创建成功提示 + 新行状态为「已预约」+ 出现「进场登记」按钮
    await expect(ui.message()).toContainText('预约登记成功')
    const row = ui.rowContaining(v.visitorName)
    await expect(row).toContainText('已预约')
    await expect(ui.linkButton('进场登记')).toBeVisible()

    // ---- 进场登记 ----
    await ui.linkButton('进场登记').click()
    await page.locator('.el-message-box__btns .el-button--primary').click()
    await expect(ui.message()).toContainText('进场登记成功')
    await expect(row).toContainText('在场')

    // ---- 离场签销 ----
    await ui.linkButton('离场签销').click()
    await page.locator('.el-message-box__btns .el-button--primary').click()
    await expect(ui.message()).toContainText('离场签销成功')
    await expect(row).toContainText('已签销')

    // 断言点②：已签销后不再出现进场/签销按钮（终态）
    await expect(ui.rowContaining(v.visitorName).getByText('进场登记')).toHaveCount(0)

    // 清理：按姓名反查 id（UI 创建不返回 id）
    const findRes = await api.ctx.get(`${apiURL}/visitors?pageNum=1&pageSize=10&visitorName=${encodeURIComponent(v.visitorName)}`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    const found = await api.expectOk(findRes, '反查访客')
    for (const item of found.list) {
      await api.cleanup(api.ctx, apiURL, token, 'visitors', item.id)
    }
  })
})
