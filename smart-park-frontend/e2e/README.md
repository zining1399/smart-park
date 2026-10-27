# E2E 冒烟测试（Playwright）

智慧园区管理系统的端到端冒烟集，**15 条**关键路径用例。与 `docs/qa/testcases.md` 的 TC-FE-xx 一一对应。

## 目录结构

```
e2e/
├── playwright.config.js     # 三浏览器矩阵（承载 TC-FE-11 兼容性）+ 环境变量
├── fixtures/
│   ├── app.js               # 登录 fixture（接口登录/UI 登录双路径）+ Element Plus 交互helper
│   └── api.js               # 接口 helper + 造数工厂（E2E 专用数据，不与演示 data.sql 混用）
├── tests/                   # 冒烟用例（TC-FE-01 ~ TC-FE-15）
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
| TC-FE-15 | tests/08-stock.spec.js | 类型筛选无 RETURN 选项（裁决 15） |

> 附：01-auth.spec.js 中 TC-FE-14b（XSS 防御）为 TC-FE-14 的分支用例，不单独计数。

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
| `E2E_TOKEN_KEY` | token | token 在 localStorage 的键名（以 M2 鉴权落地为准） |
| `E2E_AUTH_TOKEN` | 空 | 直接给定 token，跳过登录（仅验证渲染时用） |
| `E2E_USER_ADMIN` / `E2E_PASS_ADMIN` | 空 | 管理员账号 |
| `E2E_USER_GATE` / `E2E_PASS_GATE` | 空 | 门岗账号 |
| `E2E_USER_STOCK` / `E2E_PASS_STOCK` | 空 | 仓管账号 |
| `E2E_START_SERVERS` | 空 | 置 1 时由 Playwright 拉起前后端 |

## 已知待联调项（M2 落地前）

1. `tests/01-auth.spec.js` 中依赖登录页的三条（TC-FE-01/02/13）在 `E2E_LOGIN_UI=1` 且 /login 页面实现后才可执行，否则 **skip**。
2. 登录接口路径（当前 `POST /api/auth/login`）、token 字段（`token` / `accessToken`）、localStorage 键名以 backend M2 与 api-contract 增补为准，fixture 已做成可配置。
3. **TC-FE-15 当前预期为 FAIL**：`src/views/StockRecord.vue` 仍保留 `<el-option label="归还" value="RETURN" />`（裁决 15 要求移除）。该 FAIL 是有效缺陷信号，**不得修改用例预期**，待 frontend-engineer 清理后转为 PASS。
4. 三条依赖 `addInitScript` + `evaluate` 注入 token 的行为在 M2 鉴权落地后需与前端存储方案对齐。

## 约定

- 造数一律使用 `fixtures/api.js` 中的 `factories`，**不使用演示 data.sql 的数据**，避免与联调数据互相污染。
- 删除类操作在接口侧二次确认（`api.expectOk` 反查），不只依赖前端行消失。
- 失败即留证：`trace`/`video`/`screenshot` 均为 `on-first-retry` / `retain-on-failure`，报告输出 `e2e/reports`。
