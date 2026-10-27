import request from './request'

/* ========== 员工 Employee ========== */
export function pageEmployees(params) {
  return request.get('/employees', { params })
}
export function createEmployee(data) {
  return request.post('/employees', data)
}
export function updateEmployee(id, data) {
  return request.put(`/employees/${id}`, data)
}
export function deleteEmployee(id) {
  return request.delete(`/employees/${id}`)
}

/* ========== 车辆 Vehicle ========== */
export function pageVehicles(params) {
  return request.get('/vehicles', { params })
}
export function createVehicle(data) {
  return request.post('/vehicles', data)
}
export function updateVehicle(id, data) {
  return request.put(`/vehicles/${id}`, data)
}
export function deleteVehicle(id) {
  return request.delete(`/vehicles/${id}`)
}

/* ========== 权限 AccessPermission ========== */
export function pagePermissions(params) {
  return request.get('/permissions', { params })
}
export function createPermission(data) {
  return request.post('/permissions', data)
}
export function updatePermission(id, data) {
  return request.put(`/permissions/${id}`, data)
}
export function deletePermission(id) {
  return request.delete(`/permissions/${id}`)
}

/* ========== 通行流水 AccessRecord ========== */
export function pageAccessRecords(params) {
  return request.get('/access-records', { params })
}
// 模拟终端上报一次通行
export function simulateAccess(data) {
  return request.post('/access-records/simulate', data)
}
