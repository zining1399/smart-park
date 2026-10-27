// 常用输入格式校验（前端做第一道拦截，后端仍应校验）
export const PHONE_RE = /^1[3-9]\d{9}$/
export const ID_CARD_RE = /^\d{17}[\dXx]$/
// 车牌：省份简称 + 发牌机关字母 + 5~6 位序号（宽松校验，覆盖新能源与常规号牌）
export const PLATE_RE = /^[一-龥][A-Za-z][A-Za-z0-9]{4,6}$/

export const phoneRule = {
  pattern: PHONE_RE,
  message: '请输入正确的 11 位手机号',
  trigger: 'blur'
}

export const idCardRule = {
  pattern: ID_CARD_RE,
  message: '请输入 18 位身份证号',
  trigger: 'blur'
}

export const plateRule = {
  pattern: PLATE_RE,
  message: '请输入正确的车牌号，如 新A12345',
  trigger: 'blur'
}
