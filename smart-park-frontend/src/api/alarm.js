import request from './request'

/* ========== 告警 Alarm ========== */
export function pageAlarms(params) {
  return request.get('/alarms', { params })
}
// 处理告警：置已处理
export function handleAlarm(id) {
  return request.post(`/alarms/${id}/handle`)
}
