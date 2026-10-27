import { defineStore } from 'pinia'

// 全局字典 store：门岗、区域等业务枚举（与后端约定一致）
export const useDictStore = defineStore('dict', {
  state: () => ({
    // 门岗
    gates: ['东门', '西门', '南门', '货运门'],
    // 区域
    areas: ['办公区', '生产区', '仓储区', '生活区'],
    // 通行/权限目标类型
    targetTypes: [
      { value: 'PERSON', label: '人员' },
      { value: 'VEHICLE', label: '车辆' }
    ],
    // 通行方向
    directions: [
      { value: 'IN', label: '进场' },
      { value: 'OUT', label: '离场' }
    ],
    // 物资类别
    materialCategories: ['工具', '备件', '车辆'],
    // 车主类型
    vehicleOwnerTypes: ['员工', '访客', '仓储'],
    // 告警类型与级别
    alarmTypes: ['门禁异常', '访客滞留', '物资逾期', '黑名单'],
    alarmLevels: ['提示', '警告', '严重']
  })
})
