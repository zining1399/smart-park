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
  admin: { user: 'E2E_USER_ADMIN', pass: 'E2E_PASS_ADMIN', role: 'ADMIN' },
  gate: { user: 'E2E_USER_GATE', pass: 'E2E_PASS_GATE', role: 'GATE' },
  stock: { user: 'E2E_USER_STOCK', pass: 'E2E_PASS_STOCK', role: 'STORE' }
}

/** 凭证三件套（M2 前端实测口径）：access / refresh / user(JSON)，缺一会被引导逻辑踢回登录页 */
const TOKEN_KEYS = {
  token: () => process.env.E2E_TOKEN_KEY || 'sp_token',
  refresh: () => process.env.E2E_REFRESH_KEY || 'sp_refresh',
  user: () => process.env.E2E_USER_KEY || 'sp_user'
}

/** 一键把凭证三件套写进 localStorage（addInitScript 覆盖首屏，evaluate 覆盖当前页） */
async function injectCredentials(page, token, refreshToken, userJson) {
  const payload = [
    [TOKEN_KEYS.token(), token],
    [TOKEN_KEYS.refresh(), refreshToken],
    [TOKEN_KEYS.user(), userJson]
  ].filter(([, v]) => v)
  if (!payload.length) return

  const write = ([entries]) => {
    try {
      for (const [k, v] of entries) window.localStorage.setItem(k, v)
    } catch {
      /* 无痕模式忽略 */
    }
  }
  await page.addInitScript(write, [payload])
  await page.evaluate(write, [payload])
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
    /** 顶栏用户信息（角色徽标在其中），点开下拉即登出入口 */
    userInfo: () => page.locator('.layout-header .user-info').first(),
    /**
     * 登出：点顶栏用户信息 → 下拉项「退出登录」→ 二次确认弹窗点「退出」。
     * 下拉项渲染在 teleport 的 popper 里，必须先点触发器；MainLayout.handleCommand 还有一层
     * ElMessageBox.confirm，不点确认则不会真正清凭证（漏这步会让"登出后回登录页"的用例失败）。
     */
    logout: async () => {
      await page.locator('.layout-header .user-info').first().click()
      await page.locator('.el-dropdown-menu__item', { hasText: '退出登录' }).first().click()
      await page.locator('.el-message-box__btns .el-button--primary', { hasText: '退出' }).first().click()
      await expect(page).toHaveURL(/\/login/)
    },
    /** 左侧菜单分组标题（.el-sub-menu__title 文本）集合，用于断言角色菜单可见性 */
    menuGroups: () => page.locator('.el-sub-menu__title'),
    /** 左侧菜单项文本集合 */
    menuItems: () => page.locator('.el-menu-item'),
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

      // 路径 A（默认）：接口登录 + 注入凭证三件套，跳过 UI 登录步骤
      let token = direct || null
      let refreshToken = process.env.E2E_REFRESH_TOKEN || null
      let userJson = null
      if (!token && username && password) {
        const res = await api.login(api.ctx, apiURL, username, password)
        const { data } = await api.parse(res)
        token = data?.token || data?.accessToken || null
        refreshToken = data?.refreshToken || refreshToken
        // sp_user 存的是扁平 JSON：{"username","realName","role"}，与前端引导逻辑一致
        userJson = data?.user ? JSON.stringify(data.user) : null
      }
      if (!userJson && username) {
        // 后端不可达时的兜底：直接按角色造一份，避免 /auth/me 401 把用例踢回登录页
        userJson = JSON.stringify({
          username,
          realName: username,
          role: ROLE_ENV[role].role
        })
      }
      await page.goto('/')
      await injectCredentials(page, token, refreshToken, userJson)
      await page.reload()
      await expect(page).toHaveURL(/\/dashboard/)
      return token
    }
    await use(login)
  }
})

export { expect }
