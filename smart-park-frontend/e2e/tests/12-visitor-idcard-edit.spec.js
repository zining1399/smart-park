import { test, expect } from '../fixtures/app.js'
import { factories } from '../fixtures/api.js'

/**
 * TC-FE-19 身份证：列表脱敏 + 编辑弹窗只读 + 保存后库内不变
 *
 * 前端实现（M3）：
 *   - 列表列渲染 `idCardMask`（前 6 后 4，如 110101********1234）
 *   - 编辑弹窗 `<el-input :disabled="!!form.id" placeholder="身份证不支持在线修改…">`
 *
 * ⚠️ 断言写法提醒：placeholder 文案在编辑态**不会作为文本渲染出来**（input 里有掩码值），
 *    所以不能 `toContainText('不支持在线修改')`，必须断言 **placeholder 属性**。
 *
 * 库内不变这一点，前端侧只能验到掩码；真正的"真实身份证未被改写"要靠
 * `id_card_hash`（HMAC-SHA256，确定性）保存前后比对，属 D 载体断言（见 §11 TC-SEC-02 同族）。
 */
const PLAIN_ID = '110101199001011234'
const MASK = '110101********1234'

test('TC-FE-19 访客编辑弹窗身份证只读，保存后脱敏值不变', async ({ page, ui, api, apiURL, loginAs }) => {
  const token = await loginAs('admin')
  const suffix = Date.now() % 1000
  const v = factories.visitor(suffix)
  const id = await api.createVisitor(api.ctx, apiURL, token, v)

  await page.goto('/visitors')
  await page.locator('.el-form-item:has(label:has-text("访客姓名")) input').first().fill(v.visitorName)
  await ui.button('查询').click()

  const row = ui.rowContaining(v.visitorName)

  // 断言点①：列表显示掩码，不含明文
  await expect(row.locator('td').nth(2)).toHaveText(MASK)
  expect(await page.content()).not.toContain(PLAIN_ID)

  // ---- 打开编辑弹窗 ----
  await ui.linkButton('编辑').click()
  const dlg = ui.dialog('编辑访客')
  await expect(dlg).toBeVisible()

  const idInput = dlg.locator('.el-form-item:has(label:has-text("身份证号")) input').first()
  // 断言点②：输入框 disabled
  await expect(idInput).toBeDisabled()
  // 断言点③：placeholder 明确告知不可在线修改（断言属性，不是文本）
  await expect(idInput).toHaveAttribute('placeholder', /不支持在线修改/)
  // 断言点④：框内是掩码值，不是明文
  await expect(idInput).toHaveValue(MASK)

  // ---- 改一个无关字段后保存 ----
  await dlg.locator('.el-form-item:has(label:has-text("来访事由")) textarea').first().fill('E2E改事由')
  await dlg.getByRole('button', { name: '保存' }).click()
  await expect(ui.message()).toContainText('修改成功')

  // 断言点⑤：保存后脱敏值仍为原掩码（未被清空/被改写），且列表无明文
  await page.locator('.el-form-item:has(label:has-text("访客姓名")) input').first().fill(v.visitorName)
  await ui.button('查询').click()
  await expect(ui.rowContaining(v.visitorName).locator('td').nth(2)).toHaveText(MASK)
  expect(await page.content()).not.toContain(PLAIN_ID)

  // 断言点⑥：接口侧反查，idCard 仍是掩码（防前端本地缓存假象）
  const res = await api.ctx.get(`${apiURL}/visitors/${id}`, { headers: { Authorization: `Bearer ${token}` } })
  const data = await api.expectOk(res, '反查访客')
  expect(data.idCard).toBe(MASK)

  await api.cleanup(api.ctx, apiURL, token, 'visitors', id)
})
