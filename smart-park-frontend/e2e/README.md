# E2E 冒烟测试（Playwright）

智慧园区管理系统的端到端冒烟集，**22 条**关键路径用例。与 `docs/qa/testcases.md` 的 TC-FE-xx 一一对应。

## 目录结构

```
e2e/
├── playwright.config.js     # 三浏览器矩阵（承载 TC-FE-11 兼容性）+ 环境变量
├── fixtures/
│   ├── app.js               # 登录 fixture（接口登录/UI 登录双路径）+ Element Plus 交互helper
│   └── api.js               # 接口 helper + 造数工厂（E2E 专用数据，不与演示 data.sql 混用）
├── tests/                   # 冒烟用例（TC-FE-01 ~ TC-FE-22）
├── package.json
└── README.md
```

## 用例索引

| 用例ID | 所在文件 | 内容 |
|---|---|---|
| TC-FE-01 | tests/01-auth.spec.js | 登录→登出→回退不进入鉴权页 |
| TC-FE-02 | tests/01-auth.spec.js | 错误密码报错且不跳转 |
| TC-FE-03 | tests/03-visitor.spec.js | 访客身份证脱敏（接口+页面双层断言） |
| TC-FE-04 | tests/04-employee.spec.js | 员工 CRUD + 筛选 + 重置 |
| TC-FE-05 | tests/05-vehicle.spec.js | 车辆 CRUD + 状态切换黑名单 |
| TC-FE-06 | tests/06-material.spec.js | 物资 CRUD + 贵重标记 + 状态筛选 |
| TC-FE-07 | tests/07-permission.spec.js | 权限 CRUD + 四区域下拉（含生活区） |
| TC-FE-08 | tests/03-visitor.spec.js | 预约→进场→签销全链路 |
| TC-FE-09 | tests/08-stock.spec.js | 普通物资出库→归还 |
| TC-FE-10 | tests/08-stock.spec.js | 贵重物资出库→审批→出库 |
| TC-FE-11 | tests/02-access.spec.js | 模拟通行放行/拦截 + 非法门岗拦截 |
| TC-FE-12 | tests/09-dashboard.spec.js | 看板三图渲染 + 数据一致性 |
| TC-FE-13 | tests/01-auth.spec.js | token 过期跳登录页 |
| TC-FE-14 | tests/01-auth.spec.js | 业务错误统一提示 + XSS 防御 |
| TC-FE-15 | tests/08-stock.spec.js | 类型筛选无 RETURN 选项（裁决 15，M3 已修复转 PASS，保留作回归哨兵） |
| TC-FE-16 | tests/11-login-redirect.spec.js | 未登录直输 `/visitors` → `/login?redirect=%2Fvisitors` → 登录后回原路径 |
| TC-FE-16b | tests/11-login-redirect.spec.js | 对照：无 redirect 时登录后落 /dashboard（防回跳逻辑写反） |
| TC-FE-17 | tests/13-auth-session.spec.js | 门岗的写操作按钮**被隐藏且节点仍在**（v-permission 语义），权限页可写（与后端放行一致） |
| TC-FE-17b | tests/13-auth-session.spec.js | 对照：管理员可见写操作按钮（防上条写成永真） |
| TC-FE-18 | tests/13-auth-session.spec.js | 门岗/仓管越权直输 URL 落 403 且不渲染表格；未知路径落 404；管理员全页面可达 |
| TC-FE-19 | tests/12-visitor-idcard-edit.spec.js | 身份证：列表脱敏 + 编辑弹窗只读（disabled + placeholder 提示）+ 保存后掩码不变 |
| TC-FE-20 | tests/13-auth-session.spec.js | 并发 401 单飞 refresh：3 并发只触发 1 次 refresh，业务请求 3 失败 + 3 重放 = 6 |
| TC-FE-20b | tests/13-auth-session.spec.js | refresh 也失效时一次性踢登录、不循环刷新、不再重放、凭证清空 |
| TC-FE-22a | tests/16-sensitive-mask-contract.spec.js | **敏感字段命名与形态契约**：出参恒为驼峰 `idCardMask`/`phoneMask`，无下划线写法，密文/哈希不下发 |
| TC-FE-22b | tests/16-sensitive-mask-contract.spec.js | 禁用账号登录报 40103 且停留登录页（需 `E2E_USER_DISABLED`，否则 skip） |
| TC-FE-22c | tests/16-sensitive-mask-contract.spec.js | 业务请求上的 40103 必须踢回登录页（防"页面在、数据永远刷不出"的僵死态） |

> 附：01-auth.spec.js 中 TC-FE-14b（XSS 防御）为 TC-FE-14 的分支用例，不单独计数。
>
> **文件分工（别再跨文件重复同一条断言）**：
> - `16-sensitive-mask-contract.spec.js`（frontend 维护）：出参长什么样 + 账号禁用 40103。
> - `13-auth-session.spec.js`（qa 维护）：谁能看到（菜单/按钮/越权/404）+ 会话怎么失效（单飞 refresh）。
> - 账号禁用只出现在 16 号文件；菜单/越权/单飞 refresh 只出现在 13 号文件。
>   同一条断言放两处 = 修一处漏另一处，这是本目录已经发生过一次的事故（见 13 号文件头部注释）。

## 运行

```bash
cd smart-park-frontend/e2e
npm install
npm run install:browsers      # 首次需下载浏览器

# 方式一：连接已启动的环境（推荐，前端 5173 + 后端 8080 已就绪）
npm run test:smoke

# 方式二：一键起服务再跑（需要 maven + npm 可用）
E2E_START_SERVERS=1 npm run test:smoke

# 兼容性矩阵（三浏览器，承载 TC-FE-11）
npm run test:compat
```

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `E2E_BASE_URL` | http://localhost:5173 | 前端地址 |
| `E2E_API_URL` | http://localhost:8080/api | 后端地址 |
| `E2E_LOGIN_UI` | 空（走接口登录） | 置 `1` 走 /login 表单登录 |
| `E2E_TOKEN_KEY` | sp_token | access token 在 localStorage 的键名 |
| `E2E_REFRESH_KEY` | sp_refresh | refresh token 的键名 |
| `E2E_USER_KEY` | sp_user | 用户信息 JSON 的键名（**三件套缺一会被引导逻辑踢回登录页**） |
| `E2E_AUTH_TOKEN` / `E2E_REFRESH_TOKEN` | 空 | 直接给定 token / refreshToken，跳过登录（仅验证渲染时用） |
| `E2E_USER_ADMIN` / `E2E_PASS_ADMIN` | 空 | 管理员账号（role=ADMIN） |
| `E2E_USER_GATE` / `E2E_PASS_GATE` | 空 | 门岗账号（role=GATE） |
| `E2E_USER_STORE` / `E2E_PASS_STORE` | 空 | 仓管账号（**角色码 `STORE`、登录名 `store`**，不是 STOCK/stock） |
| `E2E_USER_DISABLED` / `E2E_PASS_DISABLED` | 空 | 禁用账号（可选，未配置则 TC-FE-15b 自动 skip） |
| `E2E_START_SERVERS` | 空 | 置 1 时由 Playwright 拉起前后端 |

> CI 注入口径：账号密码**全部走环境变量，无硬编码**。`loginAs(role)` 从
> `E2E_USER_{ADMIN,GATE,STORE}` / `E2E_PASS_*` 取值，未取到时回落到
> "按角色造一份假 `sp_user`"——**用例不会红，但等于没验到真实登录**，CI 必须注入这 6 个变量。
> 角色实参写 `admin` / `gate` / `store`（`stock` 为历史别名，会自动归一到 `store`）。

后端 `AdminInitializer` 只在 `sys_user` 为空时建号，口令由 `SMARTPARK_INIT_{ADMIN,GATE,STORE}_PASSWORD`
注入；**不注入就是随机 UUID 且只在启动日志打印一次**，E2E 拿不到 → 上面那 6 个变量必须与之对应。

| `E2E_KNOWN_FAIL` | 空 | 置 `1` 时执行"已知缺陷哨兵"用例（当前用于 40103 相关），默认 skip 以免长期染红 CI |

## 已知待联调项

1. `tests/01-auth.spec.js` 中依赖登录页的三条（TC-FE-01/02/13）在 `E2E_LOGIN_UI=1` 且后端 `/auth/*` 就位后才可执行，否则 **skip**。
2. ~~**TC-FE-15 当前预期为 FAIL**~~ 已修复：`src/views/StockRecord.vue` 的 `RETURN` 选项已按裁决 15 移除，`typeText` 兜底改为 `-`，TC-FE-15 转为 PASS。
3. **当前预期 FAIL 的哨兵用例**：TC-FE-22c（业务请求 40103 不踢登录）。前端 `request.js` 把 40103 与 40102 一并当作"仅登录页会出现"处理 → 业务请求上只提示不跳转。修法二选一待 team-lead 裁决：
   - (a) 前端改：401 且 code=40103 且当前不在登录页 → 踢登录并提示含「禁用」（保留精确语义）；
   - (b) 后端改：`JwtAuthFilter` 对禁用账号返回 40101，前端现有分支直接接管（前端零改动，但丢失"被禁用 vs 已过期"的区分度）。

## 写用例时的三个坑（都是踩出来的）

1. **`v-permission` 是隐藏（`display:none`）不是移除节点**。Playwright 的 `toBeHidden()` 对"节点不存在"也返回通过，所以必须先 `toHaveCount(1)`（或 `toBeAttached()`）再 `toBeHidden()`，否则把正确实现判成缺陷。
2. **placeholder 文案不会作为文本渲染**。`el-input` 的 placeholder 是属性，编辑态输入框里还有值，所以 `toContainText('不支持在线修改')` 必红，要 `toHaveAttribute('placeholder', /…/)`。
3. **凭证是三件套**。只注入 `sp_token` 时，应用会走"有 token 无 user → 调 `/auth/me` 恢复"，后端不可达即 401 → 清凭证 → 用例在第 0 步被踢回登录页。`loginAs` 已同时注入 `sp_token` / `sp_refresh` / `sp_user`，不要绕过它手写注入。

## 约定

- 造数一律使用 `fixtures/api.js` 中的 `factories`，**不使用演示 data.sql 的数据**，避免与联调数据互相污染。
- 删除类操作在接口侧二次确认（`api.expectOk` 反查），不只依赖前端行消失。
- 失败即留证：`trace`/`video`/`screenshot` 均为 `on-first-retry` / `retain-on-failure`，报告输出 `e2e/reports`。
