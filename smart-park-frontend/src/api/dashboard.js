import request from './request'

/* ========== 运营看板 Dashboard ========== */
// 统计卡片：{ todayAccessCount, visitorOnSite, materialOut, materialOverdue, unhandledAlarm }
export function getDashboardSummary() {
  return request.get('/dashboard/summary')
}
// 近 7 天通行趋势：{ dates: [], inCounts: [], outCounts: [] }
export function getAccessTrend() {
  return request.get('/dashboard/access-trend')
}
// 物资状态分布：[{ status: '在库', count: 40 }, ...]
export function getMaterialStatus() {
  return request.get('/dashboard/material-status')
}
