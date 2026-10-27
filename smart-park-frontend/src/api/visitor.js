import request from './request'

/* ========== 访客 Visitor ========== */
export function pageVisitors(params) {
  return request.get('/visitors', { params })
}
// 预约登记
export function createVisitor(data) {
  return request.post('/visitors', data)
}
export function updateVisitor(id, data) {
  return request.put(`/visitors/${id}`, data)
}
export function deleteVisitor(id) {
  return request.delete(`/visitors/${id}`)
}
// 进场登记：已预约 → 在场
export function checkInVisitor(id) {
  return request.post(`/visitors/${id}/check-in`)
}
// 离场签销：在场/滞留预警 → 已签销
export function checkOutVisitor(id) {
  return request.post(`/visitors/${id}/check-out`)
}
