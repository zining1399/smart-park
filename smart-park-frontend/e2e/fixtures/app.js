import { test as base, expect } from '@playwright/test'
import { API } from './api.js'

/**
 * 应用级 fixture：
 *  - loginAs(role)：以角色身份进入工作台（默认走接口登录 + 注入 token；E2E_LOGIN_UI=1 走登录页表单）
 *  - ui：Element Plus 常用元素的操作/断言helper，选择器按 smart-park-frontend/src 现有实现书写
 *  - api / apiURL：供造数与反查
 *
 * 说明：M2 落地鉴权后，token 键名（E2E_TOKEN_KEY）与登录接口路径需与 backend 对齐，本文件已做成可配置。
 */
const ROLE_ENV = {
  admin: { user: 'E2E_USER_ADMIN', pass: 'E2E_PASS_ADMIN' },
  gate: { user: 'E2E_USER_GATE', pass: 'E2E_PASS_GATE' },
  stock: { user: 'E2E_USER_STOCK', pass: 'E2E_PASS_STOCK' }
}

/** Element Plus 通用交互helper（选择器以项目现有 .vue 为准，不引入 test-id 依赖） */
function makeUi(page) {
  return {
    page,
    /** 表格体行（排除表头） */
    rows: () => page.locator('.el-table__body-wrapper tbody tr'),
    /** 含指定文本的数据行（用于定位刚创建的那条） */
    rowContaining: (text) => page.locator('.el-table__body-wrapper tbody tr', { hasText: text }),
    /** 按钮：优先语义角色，回落到 Element Plus class */
    button: (name) => page.getByRole('button', { name }).or(page.locator(`.el-button:has-text("${name}")`)),
    /** 链接型操作按钮（表格操作列） */
    linkButton: (name) => page.locator(`.el-table__body-wrapper .el-button:has-text("${name}")`),
    /** 弹窗内按 label 取表单控件 */
    dialog: (title) => page.locator('.el-dialog', { has: page.locator(`.el-dialog__title:has-text("${title}")`) }),
    formItem: (label) => page.locator('.el-form-item', { has: page.locator(`label:has-text("${label}")`) }),
    /** 下拉：点开后按选项文本选（Element Plus 选项渲染在 .el-select-dropdown） */
    select: async (labelText, optionText) => {
      await page.locator('.el-form-item', { has: page.locator(`label:has-text("${labelText}")`) }).locator('.el-select').click()
      await page.locator('.el-select-dropdown__item:visible', { hasText: optionText }).first().click()
    },
    /** 下拉选项文本集合（用于 TC-FE-15 断言无 RETURN 选项） */
    selectOptions: (labelText) => {
      const item = page.locator('.el-form-item', { has: page.locator(`label:has-text("${labelText}")`) }).locator('.el-select')
      return item.locator('.el-select-dropdown__item')
    },
    /** 日期时间选择器：直接输入 value-format 字符串，避免依赖日历面板 */
    dateTime: async (labelText, value) => {
      const input = page
        .locator('.el-form-item', { has: page.locator(`label:has-text("${labelText}")`) })
        .locator('input.el-input__inner')
        .first()
      await input.fill(value)
      await input.press('Enter')
    },
    /** ElMessage 提示（成功/错误/警告统一走 .el-message） */
    message: () => page.locator('.el-message'),
    messageText: async () => (await page.locator('.el-message').first().innerText()).trim(),
    /** 分页总数文案 */
    paginationTotal: () => page.locator('.el-pagination .el-pagination__total'),
    /** 左侧菜单跳转 */
    menu: (name) => page.locator('.el-menu-item', { hasText: name }).first(),
    /** 子菜单展开：先点分组标题 */
    openMenuGroup: async (group) => {
      await page.locator('.el-sub-menu__title', { hasText: group }).first().click()
    }
  }
}

export const test = base.extend({
  apiURL: [process.env.E2E_API_URL || 'http://localhost:8080/api', { option: true }],

  api: async ({ playwright, apiURL }, use) => {
    const ctx = await playwright.request.newContext({
      baseURL: apiURL,
      extraHTTPHeaders: { 'Content-Type': 'application/json' }
    })
    await use({ ctx, ...API })
    await ctx.dispose()
  },

  ui: async ({ page }, use) => {
    await use(makeUi(page))
  },

  /** 以角色登录并进入工作台。返回 token（接口登录）或 null（UI 登录） */
  loginAs: async ({ page, api, apiURL, baseURL }, use) => {
    const login = async (role = 'admin') => {
      const creds = ROLE_ENV[role]
      const username = process.env[creds.user]
      const password = process.env[creds.pass]
      const direct = process.env.E2E_AUTH_TOKEN

      if (process.env.E2E_LOGIN_UI === '1') {
        // 路径 B：走登录页表单（M2 提供 /login 后启用）
        await page.goto('/login')
        await page.locator('input[name="username"], input[placeholder*="用户名"]').first().fill(username || 'admin')
        await page.locator('input[name="password"], input[type="password"]').first().fill(password || 'admin123')
        await page.locator('button[type="submit"], .el-button:has-text("登录")').first().click()
        await expect(page).toHaveURL(/\/dashboard/)
        return null
      }

      // 路径 A（默认）：接口登录 + 注入 token，跳过 UI 登录步骤
      let token = direct || null
      if (!token && username && password) {
        const res = await api.login(api.ctx, apiURL, username, password)
        const data = await api.parse(res)
        token = data?.data?.token || data?.data?.accessToken || null
      }
      await page.goto('/')
      if (token) {
        await page.addInitScript(
          ([key, value]) => {
            try {
              window.localStorage.setItem(key, value)
            } catch {
              /* 无痕模式忽略 */
            }
          },
          [process.env.E2E_TOKEN_KEY || 'token', token]
        )
        await page.evaluate(
          ([key, value]) => {
            try {
              window.localStorage.setItem(key, value)
            } catch {
              /* 忽略 */
            }
          },
          [process.env.E2E_TOKEN_KEY || 'token', token]
        )
        await page.reload()
      }
      await expect(page).toHaveURL(/\/dashboard/)
      return token
    }
    await use(login)
  }
})

export { expect }
