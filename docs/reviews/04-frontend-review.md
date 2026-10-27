# 附录 04 · 前端生产就绪评审与鉴权对接方案（颜可心 · 前端体验工程师）

> 归档说明：本文件为成员产出原文归档（主报告 + 修订定稿）。**汇编裁决修正**：①角色编码最终值 `ADMIN / GATE / STORE`（原报告预写 KEEPER 作废）；②新增 B7（访客身份证编辑回填覆盖缺陷）与后端主清单编号统一为 **B6**，三层防护已双端定稿；③token 方案已拍板**双 token**（access 2h + refresh 7d 轮换，不留单 token 删减分支）；④40101/40102/40103/40301 等 8 枚错误码已冻结（见终稿 §六）。以 `docs/delivery-improvement-plan.md` 为执行基准。

---

# 主报告

评审人：颜可心（前端体验工程师）。已通读 `src/` 全部源码（9 视图 / 6 API 模块 / 路由 / 布局 / 构建 / 产物），并复核了任务书 4 项关键事实——**全部属实**，且每项深挖出额外细节。

## 一、总体结论

代码本身质量是不错的（统一 axios 封装、列表页模式统一、删除有二次确认、图表组件销毁正确），但它目前是一个**"演示级可用"**的成品：**后端一旦上线 Spring Security + JWT，现有前端会在第一个请求上全军覆没**——只弹错误 toast、不会跳登录，所有页面都不可用。此外部署形态硬编码、版本管理基础缺失、包体积超预算，都不满足"客户现场交付"标准。

**结论：不具备生产上线条件，需约 8 人日改造（见第六节）后交付。**

## 二、关键事实复核（每项均有深挖补充）

| 事实 | 复核结果 | 深挖补充 |
|---|---|---|
| ① 无登录、token 拦截器只留 TODO | ✅ `request.js:11-19` | 更严重的是**响应侧**：`request.js:32-37` 对 HTTP 401/403 与 500/网络错误一视同仁，只弹 ElMessage。后端上鉴权后 401 不会跳登录；多请求并发失败会**连续弹 N 个重复错误 toast**（无防抖去重） |
| ② dist 已构建且在源码目录 | ✅ 2.7MB，17 个文件 | 实测体积：`index-*.js` **1.21MB**（全量 Element Plus+全量图标）、`Dashboard-*.js` **1.04MB**（`import * as echarts` 全量引入）、`index-*.css` 362KB。无 gzip 预压缩产物 |
| ③ 无 .gitignore、非 git 仓库 | ✅ | **连 `.env`、`.editorconfig`、ESLint/Prettier 配置都没有**，工程除源码外零工程化配置 |
| ④ /api 代理与后端前缀 | ✅ dev 正确 | 但 `baseURL: '/api'` **硬编码**在 `request.js:6`，生产无环境变量可配；`createWebHistory()` 无 base，**子路径部署（如 /park/）会白屏** |

另：**`docs/api-contract.md` 中没有任何登录/鉴权接口定义**——前后端改造的公共前置缺口（已与 backend-engineer 定稿，见终稿 §六）。

## 三、前端改造清单

### 🔴 Blocker（合计 ~4.5 人日）

**B1. 登录鉴权链路整体缺失**
- 位置：`src/api/request.js:10-19`（TODO）、无 `Login.vue`、无 `src/api/auth.js`、无 `src/stores/auth.js`
- 影响：后端上 JWT 后，首个 `/api/dashboard/summary` 请求 401，页面仅弹 toast，系统整体不可用
- 修改：按第四节完整方案实施

**B2. 响应拦截器无 401/403 分支，错误提示无去重防抖**
- 位置：`request.js:23-38`
- 修改：40101 → 清凭证 → 防抖跳 `/login?redirect=...`（模块级 `isRedirecting` 标志位保证只跳一次）；40301 → 无权限提示；其余错误加 ElMessage 节流（同一消息 2 秒只弹一次）；401 分支跳过 ElMessage 避免双重打扰

**B3. 无路由守卫 + 无 404 路由**
- 位置：`src/router/index.js`（只有 afterEach，无 beforeEach；无 `/:pathMatch(.*)*`）
- 现象：任意 URL 直接进入任意页面；不存在的路径主内容区**空白**（用户以为卡死）
- 修改：`router.beforeEach` 校验登录态与 `meta.roles`；补 404 路由

**B4. 角色权限 UI 完全缺失**
- 位置：`MainLayout.vue:16-53`（菜单硬编码）、各视图操作按钮
- 现象：门岗会看到"物资台账/出入库"，**所有删除/审批/模拟通行按钮对所有角色可见可点**
- 修改：路由 `meta.roles` + 菜单按角色过滤 + `v-permission` 按钮级指令

**B5. 部署路径与 API 地址硬编码，无环境变量体系**
- 位置：`request.js:6`、`router/index.js:69`、`vite.config.js`、项目根无 `.env*`
- 修改：见第五节环境变量清单；`createWebHistory(import.meta.env.BASE_URL)`

**B6. 版本管理基础缺失**（与后端清单统一编号；原报告 B7 与后端 B6 为同一问题，身份证编辑回填缺陷见下）
- 修改：立即 `git init` + `.gitignore`（node_modules/dist/*.local/.env.*.local），**dist 从版本库排除**

### 🟠 强烈建议（合计 ~2.5 人日）

- **S1. Element Plus 全量引入 → 主包 1.21MB**：`main.js:3-6,14-17`；改 `unplugin-vue-components` + `unplugin-auto-import`（ElementPlusResolver）按需引入；图标只注册实际用到的约 7 个
- **S2. ECharts 全量引入 → Dashboard chunk 1.04MB**：`Chart.vue:8`；改 `echarts/core` 按需注册（体积降 ~70%）+ `manualChunks` 拆 vendor
- **S3. 无 gzip 预压缩**：`vite-plugin-compression` 产出 `.gz` + Nginx `gzip_static on`；配合 S1/S2 总传输从 ~2.5MB 降到 ~500KB 级
- **S4. 死依赖 dayjs**：`package.json:14` 声明了但零引用，移除
- **S5. 错误态与取消分支缺失**：各视图 `loadData` 无 catch，失败后表格空态无"重试"；`ElMessageBox.confirm` 取消 reject 未捕获，控制台 unhandled rejection 噪音。补 `loadError` 状态 + 重试 UI + `.catch(() => {})`
- **S6. 表单校验偏弱**：手机号/身份证/车牌只校验必填；生效失效时间无先后校验。补正则与自定义校验器
- **S7. 出库物资下拉 pageSize=500 hack**：`StockRecord.vue:218-226`，物资超 500 条后下拉**静默缺项**。【待确认物资量级】→ 轻量 options 接口或远程搜索
- **S8. 敏感信息展示**：身份证列已用后端脱敏结果；`phone` 列未脱敏全量展示，建议 `138****5678`
- **S9. 无 favicon**：每次访问产生 404 日志噪音。补 32x32 ico
- **S10. 查询条件空字符串直传**：`?name=&dept=` 建议剔除空参数

### 🟡 可选
O1 移动端适配【待确认诉求】；O2 老浏览器兼容（Element Plus 本身不支持 IE，需现场摸底）；O3 ESLint/Prettier（客户二开建议补）；O4 全局 errorHandler 上报；O5 Chart.vue 深比较开销、图表 loading 遮罩；O6 告警 30s 轮询 badge（WebSocket 成本高不建议本期做）。

## 四、鉴权对接实施方案（定稿版）

**新增模块**：
```
src/
├── api/auth.js          # login / getUserInfo / refreshToken
├── stores/auth.js       # Pinia：token、refreshToken、userInfo{username, realName, role}
├── views/Login.vue      # 登录页（路由顶层，独立于 MainLayout）
├── views/NotFound.vue   # 404
├── directives/permission.js  # v-permission 按钮级指令
└── router/index.js      # 加 /login、404、meta.roles、beforeEach
```

**1. Token 存储**：accessToken 存 localStorage（`sp_token`）+ Pinia 内存镜像；`sp_refresh` / `sp_user` 三把 key。内网后台常规做法，XSS 风险靠"无富文本、无三方脚本注入面"控制。

**2. 登录页**：居中卡片 + 必填校验 + 提交 loading + 失败显示后端 message；成功后 `authStore.login()` 存凭证 → `router.push(redirect || '/dashboard')`。

**3. axios 拦截器（终版分支逻辑）**：
```
error.response:
  status 401:
    code 40102 / 40103 → ElMessage 后端 message，不跳转（仅登录页）
    code 40101 →
      有 refreshToken 且未重试过(_retried) → 单飞调 /auth/refresh：
        成功 → 轮换存储新 token + refreshToken → 重试原请求
        失败 → 清凭证 → 提示"登录已失效" → 跳 /login?redirect=...
      无 refreshToken 或已重试 → 清凭证 → 跳 /login?redirect=...
  status 403 → ElMessage.warning("无操作权限")，不跳转
  其余 → ElMessage 后端 message（50001 前端兜底文案特判）
并发保护：模块级 isRefreshing 标志 + 等待队列，多请求同时 40101 只发一次 refresh，其余挂起后统一重放；跳登录防抖只跳一次
```

**4. 路由守卫**：
```js
const WHITE_LIST = ['/login']
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (WHITE_LIST.includes(to.path)) return auth.token ? '/dashboard' : true
  if (!auth.token) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.meta.roles && !to.meta.roles.includes(auth.role)) return '/403'
  return true
})
```
注意：守卫内读 Pinia 需在 `main.js` 中先 `app.use(pinia)` 再 `use(router)`（当前顺序恰好正确）。

**5. 角色矩阵**（角色码 ADMIN/GATE/STORE，【待确认】最终以业务方拍板为准）：

| 页面 | ADMIN | GATE | STORE |
|---|---|---|---|
| 运营看板 | ✅ | ✅（精简卡片） | ✅ |
| 员工/车辆/权限管理 | ✅ 全部 | ✅ 只读 | ❌ |
| 通行记录（含模拟通行） | ✅ | ✅ | ❌ |
| 访客管理 | ✅ | ✅ | ❌ |
| 物资台账/出入库 | ✅ | ❌ | ✅ |
| 告警中心（处理按钮） | ✅ | ✅ | ✅ |

实现：路由 `meta: { roles: [...] }`；`MainLayout.vue` 菜单抽成配置数组（path/icon/roles）computed 过滤；危险按钮 `v-permission="['ADMIN']"`（指令内无权限直接 `el.remove()`）。门岗"只读"页面内差异用按钮级指令，路由只做页面级。

**6. 退出登录**：顶栏右侧用户下拉（姓名 + 角色徽标 + 退出）→ 确认框 → 清 Pinia 与 localStorage → 跳 /login（无服务端登出端点）。

**联调必覆盖**：登录成功/密码错；token 过期被踢回登录带回 redirect；门岗直输 URL 访问 /stock 被守卫拦截；隐藏按钮接口被直调时 403 提示表现。

## 五、生产构建与部署对接

**环境变量**（交付 `.env.development` / `.env.production` 模板）：
```
VITE_API_BASE_URL=/api
VITE_APP_TITLE=智慧园区管理系统
# 子路径部署时：VITE_BASE=/park/【待确认部署形态】
```

**Nginx 要求**：① `try_files $uri $uri/ /index.html`（SPA fallback 必须）；② `/api/` 反代 127.0.0.1:8080（同域零 CORS）；③ assets 长缓存 + index.html 禁缓存；④ gzip（配合 .gz 产物 `gzip_static on`）。

**其他**：Node 18+ 与 `npm ci`（有 lock 文件固定用 ci）；Docker 构建阶段跑 `npm run build`，dist 不入库。另需补 `package-lock.json`（当前缺失，CI `npm ci` 会失败，0.25 人日）。

## 六、工作量估算与实施顺序（定稿 8 人日）

| 序 | 事项 | 人日 | 依赖 |
|----|------|------|------|
| D1 | git init + .gitignore + dist/node_modules 排除 + 移除 dayjs + package-lock 生成 | 0.25 | 无 |
| D2 | .env 体系 + baseURL/BASE 可配置化 + 404 路由 | 0.75 | 无 |
| D3 | 登录页 + auth store + 拦截器 + 路由守卫 + 单飞 refresh + 退出登录（B1/B2/B3） | 2 | 后端契约（已定稿） |
| D4 | 角色菜单过滤 + v-permission（B4） | 1.5 | 角色编码（已定稿 STORE） |
| D5 | 构建优化：EP 按需 + ECharts 按需 + manualChunks + gzip 产物（S1-S3） | 1 | 无 |
| D6 | 交互打磨：错误重试态 + confirm 捕获 + 表单校验 + favicon + 手机号脱敏 + **B6 idCard 编辑禁用与 payload 剔除** | 1.75 | 无 |
| D7 | 与后端联调（登录/过期/权限矩阵回归）+ Nginx 部署演练 | 1 | D1-D6 |
| **合计** | | **8** | |

实施顺序 D1→D7：先固化工程基础，再走鉴权主线（关键路径，契约已解除依赖），构建优化与体验打磨并行穿插，最后联调演练。

## 七、待确认清单
① 部署形态（根路径/子路径/HTTPS）② 角色矩阵业务拍板 ③ 物资量级是否超 500 ④ 客户现场浏览器基线 ⑤ 移动端诉求。（后端契约 6 项与 idCard 服务端脱敏两项已全部落定。）

## 联调用例补充（供 QA 回归清单）
1. 并发 3 个请求同时遇 40101 → 网络面板只 1 次 /auth/refresh + 3 次原请求重放；
2. refreshToken 也过期 → 一次性踢回登录，不循环刷新；
3. 40102/40103 → 仅提示不跳转不闪动；
4. 编辑访客保存 → 库内身份证保持真实值（B6 三层防护验证）；
5. 409/404 场景 → 展示后端中文 message 无英文堆栈。
