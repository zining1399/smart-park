import { test, expect } from '../fixtures/app.js'

/**
 * TC-FE-12 运营看板：三个数据源渲染正确、无 JS 错误
 * 覆盖接口：GET /dashboard/summary、/dashboard/access-trend、/dashboard/material-status
 */
test('TC-FE-12 看板统计卡片、趋势图与分布图渲染正确且无 JS 错误', async ({ page, ui, loginAs }) => {
  const pageErrors = []
  const consoleErrors = []
  page.on('pageerror', (err) => pageErrors.push(err.message))
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(msg.text())
  })

  await loginAs('admin')

  // 断言点①：5 张统计卡片渲染且数值非 '-'（接口已返回数据）
  const cards = page.locator('.stat-card')
  await expect(cards).toHaveCount(5)
  for (let i = 0; i < 5; i++) {
    await expect(cards.nth(i).locator('.stat-value')).not.toHaveText('-')
  }

  // 断言点②：两个 ECharts 容器渲染出 canvas 且非空白
  const charts = page.locator('.page-card canvas')
  await expect(charts).toHaveCount(2)
  for (let i = 0; i < 2; i++) {
    await expect(charts.nth(i)).toBeVisible()
    const box = await charts.nth(i).boundingBox()
    expect(box.width).toBeGreaterThan(0)
    expect(box.height).toBeGreaterThan(0)
  }

  // 断言点③：趋势图标题存在（近 7 天通行趋势 / 物资状态分布）
  await expect(page.locator('.chart-title').first()).toContainText('近 7 天通行趋势')
  await expect(page.locator('.chart-title').nth(1)).toContainText('物资状态分布')

  // 断言点④：无未捕获 JS 异常（ECharts 初始化失败会在此暴露）
  expect(pageErrors).toEqual([])
  expect(consoleErrors.filter((t) => !t.includes('favicon'))).toEqual([])
})

test('TC-FE-12b 看板接口侧数据一致性（summary 与 access-trend 字段完整）', async ({ api, apiURL, loginAs }) => {
  const token = await loginAs('admin')
  const headers = { Authorization: `Bearer ${token}` }

  const summaryRes = await api.ctx.get(`${apiURL}/dashboard/summary`, { headers })
  const summary = await api.expectOk(summaryRes, '看板汇总')
  // 断言点：5 个字段均为数值
  for (const key of ['todayAccessCount', 'visitorOnSite', 'materialOut', 'materialOverdue', 'unhandledAlarm']) {
    expect(typeof summary[key]).toBe('number')
  }

  const trendRes = await api.ctx.get(`${apiURL}/dashboard/access-trend`, { headers })
  const trend = await api.expectOk(trendRes, '通行趋势')
  // 断言点：7 天三个数组等长
  expect(trend.dates).toHaveLength(7)
  expect(trend.inCounts).toHaveLength(7)
  expect(trend.outCounts).toHaveLength(7)

  const statusRes = await api.ctx.get(`${apiURL}/dashboard/material-status`, { headers })
  const statusList = await api.expectOk(statusRes, '物资状态分布')
  // 断言点：状态值均合法（在库/在借/逾期/维修中）
  for (const item of statusList) {
    expect(['在库', '在借', '逾期', '维修中']).toContain(item.status)
  }
})
