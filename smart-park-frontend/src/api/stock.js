import request from './request'

/* ========== 物资 Material ========== */
export function pageMaterials(params) {
  return request.get('/materials', { params })
}
export function createMaterial(data) {
  return request.post('/materials', data)
}
export function updateMaterial(id, data) {
  return request.put(`/materials/${id}`, data)
}
export function deleteMaterial(id) {
  return request.delete(`/materials/${id}`)
}

/* ========== 出入库流水 StockRecord ========== */
export function pageStockRecords(params) {
  return request.get('/stock-records', { params })
}
// 入库：{ materialCode, remark }
export function stockIn(data) {
  return request.post('/stock-records/in', data)
}
// 申领出库：{ materialCode, borrower, dept, expectReturnTime, remark }
export function stockOut(data) {
  return request.post('/stock-records/out', data)
}
// 审批：{ approved: true/false }
export function approveStockRecord(id, approved) {
  return request.post(`/stock-records/${id}/approve`, { approved })
}
// 归还
export function returnStockRecord(id) {
  return request.post(`/stock-records/${id}/return`)
}
