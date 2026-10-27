# 智慧园区管理系统前端（smart-park-frontend）

Vue 3 + Vite 5 + Element Plus 实现的智慧园区管理系统前端，覆盖 **运营看板 / 智慧门禁 / 访客接待 / 智慧仓管 / 告警中心** 五大模块，并已接入 **登录鉴权 + 角色权限**。

## 技术栈

- Vue 3（Composition API + `<script setup>`）
- Vite 5
- Element Plus（按需引入 + 中文语言包）
- Vue Router 4 / Pinia
- Axios（统一拦截器：token 注入、traceId、错误码分支、401 单飞刷新）
- ECharts（按需注册，仅看板页加载）

## 快速开始

```bash
# 安装依赖（需要 Node 18+）
npm install

# 复制环境变量模板（仓库 .gitignore 忽略 .env.*，需本地放置）
cp .env.example .env.development

# 启动开发服务器（端口 5173，/api 自动代理到 http://localhost:8080）
npm run dev
```

浏览器访问 http://localhost:5173 ，未登录会自动跳转 `/login`。

> 启动前端前请先启动后端 `smart-park-backend`（接口前缀 `http://localhost:8080/api`），否则页面请求会失败。

## 登录与角色

- 角色码（与后端冻结契约一致）：`ADMIN` 管理员 / `GATE` 门岗 / `STORE` 仓管。
- 页面级权限：路由 `meta.roles` + 路由守卫（`src/router/index.js`），无权限跳 `/403`。
- 菜单级权限：左侧菜单按角色过滤（`src/layout/MainLayout.vue` 的菜单配置）。
- 按钮级权限：`v-permission="['ADMIN']"`（`src/directives/permission.js`），用于新增/编辑/删除/审批等操作。
- 登出：后端为无状态 JWT、无服务端登出端点，前端丢弃本地凭证即可（顶栏用户下拉 → 退出登录）。

### 鉴权链路关键实现

| 能力 | 位置 |
|------|------|
| 凭证存储（localStorage：`sp_token` / `sp_refresh` / `sp_user`） | `src/utils/token.js` |
| 登录态与角色 | `src/stores/auth.js` |
| 登录 / 刷新 / 当前用户接口 | `src/api/auth.js` |
| 请求头注入 `Authorization` 与 `X-Trace-Id` | `src/api/request.js` 请求拦截器 |
| **40101 单飞 refresh**（并发只刷新一次，其余排队重放） | `src/api/request.js` 的 `handleTokenExpired` / `enqueue` |
| 错误码分支（40101/40102/40103/40301/40001/40401/40901/50001） | `src/api/request.js` 响应拦截器 |
| 路由守卫（未登录跳登录并带 `redirect`） | `src/router/index.js` |
| 刷新页面恢复会话（`/auth/me`） | `src/main.js` 启动引导 |

错误码语义与 HTTP 状态码对照见 `../docs/api-contract.md`；这些数值为联调冻结值，前端 hard-code，变更需前后端同步。

## 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `VITE_API_BASE` | 接口基地址，生产由 Nginx 同域反代 | `/api` |
| `VITE_PROXY_TARGET` | 仅开发环境：dev server 代理目标 | `http://localhost:8080` |
| `VITE_BASE` | 部署基础路径（子路径部署填 `/park/`） | `/` |
| `VITE_APP_TITLE` | 站点标题 | 智慧园区管理系统 |

## 生产构建与部署

```bash
npm run build   # 产物在 dist/，同时产出 .gz（配合 Nginx gzip_static）
```

Nginx 侧必需配置（详细清单见部署方案文档）：

1. SPA history 回退：`try_files $uri $uri/ /index.html;`（否则刷新 `/visitors` 会 404）
2. `/api` 同域反代到后端（前端因此无需跨域）
3. 带 hash 的 `/assets/` 长缓存，`index.html` 禁缓存
4. 开启 `gzip`（或 `gzip_static on` 直接吐前端预压缩产物）

## 目录结构

```
src/
├── api/            # axios 实例（request.js）+ 按模块拆分的 API 函数
│   ├── request.js      # 拦截器：token/traceId/错误码分支/401 单飞 refresh
│   ├── auth.js         # 登录 / 刷新 / 当前用户
│   ├── access.js        # 员工/车辆/权限/通行记录
│   ├── visitor.js       # 访客
│   ├── stock.js         # 物资/出入库流水
│   ├── alarm.js         # 告警
│   └── dashboard.js    # 运营看板
├── components/
│   └── Chart.vue       # ECharts 通用容器（按需注册 + ResizeObserver）
├── directives/
│   └── permission.js    # v-permission 按钮级角色权限
├── layout/
│   └── MainLayout.vue   # 左侧菜单（按角色过滤）+ 顶栏用户区 + 退出登录
├── plugins/
│   └── element-plus.js  # Element Plus 按需注册（组件 + 样式，避免整库引入）
├── router/
│   └── index.js         # 路由表 + 路由守卫 + meta.roles + 404/403
├── stores/
│   ├── auth.js          # 登录态 / 角色 / 权限判断
│   └── dict.js          # 字典 store（门岗/区域/类型等枚举）
├── utils/
│   ├── token.js         # 凭证本地持久化
│   └── validators.js    # 手机号/身份证/车牌校验规则
├── styles/
│   └── index.css        # 全局样式
└── views/               # 业务页面（登录/看板/员工/车辆/权限/通行/访客/物资/出入库/告警/404）
```

## 说明

- 接口契约见 `../docs/api-contract.md`，前端字段名、路径、枚举均与其保持一致。
- 访客身份证为敏感字段：列表/详情展示后端返回的 `idCardMask` 掩码值；编辑模式禁止修改（防掩码回写覆盖真实身份证），新增可输入并由服务端加密存储。
- 出入库类型已按后端裁决仅保留 `IN` / `OUT`（原 `RETURN` 死枚举已移除）。
