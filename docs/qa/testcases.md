# 智慧园区管理系统 · 测试用例执行规格

本文档是 `docs/api-contract.md`（契约）的下游产物：**契约说接口长什么样，本文档说怎么验它是对的**。
共 **110 条**用例，按模块组织；每条给出「前置条件 / 步骤 / 断言点 / 期望错误码」，可直接照着实施。

> **关于"110 条"的计数口径（请对照阅读，勿误解为覆盖率缩水）**
> - **110 = 用例条目数**（一份用例 = 一条业务路径），逐条可核（见 §13）。
> - 对外汇报的 **107 条** 是同一份清单的另一种口径：把 TC-ENUM-03 的两个分支（03a/03b）合为 1 条、且未落地的 TC-AUD-01 暂不计数（110 − 3 = 107）。**两者不是两个数，是同一份文档的两套数法**，引用时注明口径即可。
> - 按参数化展开后，**实际执行断言实例 268 个**：鉴权矩阵 168（42 接口 × 4 种身份）、CHECK 约束矩阵 40（20 条 × 正向/负向）、API 枚举校验 60（12 字段 × 5 类非法值）；
> - 另有 **4 条口径登记项不参与参数化展开**（`material.status` 忽略语义、`visitor.area` 空值、PUT 路径已移除的 code / empNo / plateNo 三字段），它们的作用是防误判，不是新增断言量；
> - 并发 4 条（TC-STK-11/22/23/24）另按 **10 并发 × 20 轮 = 800 次请求**执行；
> - 参数化数据源见 `docs/qa/fixtures/`（三份 CSV，74 行展开为 268 个实例）。

| 项 | 说明 |
|---|---|
| 适用阶段 | 开发联调 → 集成测试 → 上线割接 → UAT |
| 唯一事实来源 | `docs/api-contract.md`；冲突以契约为准 |
| 相关文档 | `docs/qa/gates.md`（Go/No-Go 门禁与缺陷分级） |

---

## 0. 读法与约定

### 0.1 优先级

| 级别 | 含义 | 放行影响 |
|---|---|---|
| **P0** | 阻断项：功能/数据/安全的核心正确性 | 未通过 → No-Go |
| **P1** | 上线前必须通过；可延后到上线前一周 | 未通过需定级、定责任人 |
| **P2** | 可带缺陷上线，须进已知问题清单 | 不影响放行 |

### 0.2 期望错误码（后端 `ErrorCodes`，数值冻结）

| 场景 | code | HTTP |
|---|---|---|
| 成功 | 0 | 200 |
| 参数校验失败 / 业务规则拒绝（BizException 默认） | **40001** | 400 |
| 未登录：token 缺失/过期/无效 | **40101** | 401 |
| 登录失败：用户名或密码错误 | **40102** | 401 |
| 登录失败：账号被禁用 | **40103** | 401 |
| 已登录但角色无权限 | **40301** | 403 |
| 资源不存在 | **40401** | 404 |
| 唯一键冲突 / 并发状态变更失败 | **40901** | 409 |
| 系统异常 | **50001** | 500 |

> 统一响应体含 `traceId`：异常详情只进服务端日志（按 traceId 检索），响应体仅给中文文案。

### 0.3 执行载体

| 标记 | 载体 | 归属 |
|---|---|---|
| **A** | 后端自动化（JUnit5 + MockMvc + Testcontainers/MySQL8） | backend-engineer 集成测试 |
| **E** | E2E 自动化（Playwright，见 `smart-park-frontend/e2e/`） | 本文档 §10 与 e2e/README.md |
| **M** | 人工割接操作（结构化断言，写进《上线割接操作检查单》） | 现场操作员执行 + 测试见证签字 |
| **D** | 数据脚本（SQL 直查 / 导入管道） | 数据工程师 + 测试 |

### 0.4 依赖状态（决定哪些现在能跑）

| 状态 | 说明 | 涉及用例 |
|---|---|---|
| ✅ 可执行 | 不依赖未完成开发 | §3~§7 业务用例（待 M2 骨架）、§9 枚举、§10 前端（联调后） |
| ⏳ 等 M2 骨架 | 依赖 backend M2（Security/JWT、MyBatis 数据权限、DTO 校验）落地 | §2 鉴权矩阵全部、§8 部分 |
| ⏳ 等 devops 管道 | 依赖 `csv-to-inserts.py` 与迁移脚本 | TC-MIG-02 / 08 / 12 |
| 🔀 条件执行 | 默认空库上线路径跳过；客户确认有 H2 存量时执行 | TC-MIG-01、与 H2 侧对账相关的断言 |

### 0.5 执行环境（本机 / CI / 现场，决定谁能跑）

> 本机实测 `docker: command not found`（team-lead 确认），因此依赖 Testcontainers 拉起 **MySQL 8 容器**的用例**本机一条也跑不了**。这是环境限制不是用例缺陷：
> **代码照写、语法与结构自检做到位，真实执行交给 CI（GitHub Actions 有 docker）与客户现场验收。不得因为本机跑不了就跳过不写。**

| 标记 | 可执行环境 | 涉及用例 | 说明 |
|---|---|---|---|
| 🖥️ | 本机可直接执行（无需 docker） | §10 前端 E2E 全 15 条（需前端已在 5173 起服务）、§11 TC-SEC-03 静态扫描、§8 的 D 类 SQL 直查（需指向目标库） | — |
| 🐳 | **必须 CI 或现场执行**（依赖 docker 拉起 MySQL 8 容器） | **§2 鉴权矩阵全 13 条、§3~§7 全部 A 载体用例、§9.1/9.4 的 D 载体 CHECK 断言（容器库）、§11 TC-SEC-01、TC-AUD-01** | 对应 `smart-park-backend/src/test` 下的 `*IntegrationTest` 族；本机执行会因 `disabledWithoutDocker=true` 自动跳过 |
| 👷 | 必须现场/割接窗口执行（依赖生产环境或真实管道） | TC-SMOKE-01、§8 全部 M/D 载体用例、§12 非功能全部 | 本机与 CI 均无法替代 |

> 容器共性前提：测试基类 `MySqlIntegrationTest` 以 `mysql:8.0` 启动容器并注入 `+08:00` 时区与 `utf8mb4`；
> CI 上若容器拉取失败（网络受限），需**标记为环境失败而非用例失败**，不得直接判定 PASS。

---

## 1. 冒烟（第一关，不过此关其余测试无意义）

| 用例ID | 优先级 | 载体 | 前置条件 | 步骤 | 断言点与期望 |
|---|---|---|---|---|---|
| **TC-SMOKE-01** | P0 | M | 生产配置启动的应用 + MySQL 8 | ① POST /api/employees 建 1 条员工；② POST /api/access-records/simulate 产生 1 条通行记录；③ **重启应用**；④ GET 上述两个资源 | ① 重启后两条数据**均仍在**；② `total` 与记录内容与重启前一致；③ 配置核验：`spring.sql.init.mode=never`；④ 配置核验：schema / Flyway 脚本中**无 `DROP TABLE IF EXISTS`**。任何一条不成立 → **No-Go** |

> 理由：`sql.init.mode: always` + DROP TABLE 会让任何一次重启清空全库，此时其余全部测试结果都无意义。

---

## 2. 鉴权与安全基线（🐳 M2 已落地；必须 CI / 现场执行，本机无 docker 跑不了）

> **权限矩阵 = 42 接口 × 4 身份 = 168 实例**，数据源 `docs/qa/fixtures/auth-matrix.csv`（与 `TC-AUTH-03` 参数化测试共用同一份文件）。
> 本节演进记录（写在这是为了防止以后有人按旧结论改回去）：
> - **`/api/auth/refresh` 必须免鉴权**（backend 自查发现并已修）：原先被 `/auth/** → authenticated` 覆盖，导致"access 过期 → 调 refresh → refresh 也 401 → 直接踢登录"，双 token 形同虚设。矩阵里"未登录对 refresh 期望 0"是对的。
> - **`GET /api/materials`（及 `/api/materials/{id}`）门岗 = 40301**（team-lead 裁决：门岗在门口，查物资台账无业务场景 + 物资编码与价值信息不该对门岗开放）。
> - **`POST /api/permissions`、`PUT /api/permissions/{id}` 门岗 = 放行**（team-lead 裁决：登记 → 授权 → 通行，缺授权则门岗业务闭环不了）。此条同时挂客户待确认项「现场授权 vs 集中管理」。
> - **`POST /api/stock-records/{id}/approve` 严格 ADMIN-only** 保持。
> - 越权拦截点是 URL 级 `requestMatchers`（进 Controller 之前），因此"越权时业务未执行"可**直接验副作用**（仓管调 simulate 无流水、门岗调 out 无单据），不依赖 mock。

| 用例ID | 优先级 | 载体 | 前置条件 | 步骤 | 断言点与期望 |
|---|---|---|---|---|---|
| TC-AUTH-01 | P0 | A | 无 token | GET /api/employees（不携带 Authorization） | HTTP 401、code=40101、data 为 null、无列表数据泄漏 |
| TC-AUTH-02 | P0 | A+E | 已获取 token 并等待过期（或构造过期 token） | 用过期 token 访问任意业务接口 | HTTP 401、code=40101；前端（E）跳转 /login |
| TC-AUTH-03 | P0 | A | 门岗角色 token | 门岗访问 POST /api/stock-records/out（仓管接口） | HTTP 403、code=40301；**库中无新增出入库记录** |
| TC-AUTH-04 | P0 | A | 仓管角色 token | 仓管访问 POST /api/access-records/simulate | HTTP 403、code=40301；无新增通行流水 |
| TC-AUTH-05 | P1 | A | 管理员 token | 管理员遍历 9 模块各 1 个代表接口 | 全部 code=0 |
| TC-AUTH-06 | P0 | A | 有效 token | 篡改签名/乱改 payload 后的 token 访问 | HTTP 401、code=40101 |
| TC-AUTH-07 | P1 | A | 登录接口 | 错误密码连续 5 次 POST /api/auth/login | 第 5 次仍返回 40102；若实现锁定则第 N 次起返回 40103 或限流响应【待确认：是否实现锁定/限流，未实现不阻断上线但需记录】 |
| TC-AUTH-08 | P0 | A | 生产配置启动 | GET /api/h2-console | HTTP 404 或 40301（**生产必须关闭 H2 控制台**） |
| TC-AUTH-09 | P0 | A+E | 有访客数据 | ① GET /api/visitors 取列表；② 抓包检查响应体原文 | ① idCard 匹配 `^\d{6}\*{4,}\d{4}$`（前 6 后 4 中间打码）；② 响应体原文**不含 18 位明文身份证**；③ 数据库 `id_card` 列为**密文**（裁决 17：cipher 列），不得出现明文 18 位 |
| TC-AUTH-10 | P0 | A | 有访客数据 | GET /api/visitors/{id}、PUT /api/visitors/{id} 响应体逐个检查 | 同上脱敏 + 库内密文；**逐个接口验证，防单接口漏脱敏** |
| TC-AUTH-11 | P1 | D | 日志文件 | 全局检索 18 位身份证号、明文密码 | 无命中（身份证加密三列落地后，日志中亦不得出现明文） |
| **TC-AUTH-12** | P0 | A | admin 已登录取得 token | ① 用 token 访问 /api/auth/me（code=0）；② `UPDATE sys_user SET status='禁用'`；③ **用同一个未过期 token** 再访问；④ 用该账号重新登录；⑤ 改回「启用」后原 token 再访问 | ③ **40103**（不是放行）；④ 40103；⑤ 恢复 0。覆盖"无状态 JWT 无法即时吊销"的解法：filter 每次请求查库且带 `status='启用'` 条件，**无需黑名单表**。⏳ backend 修复前本用例 FAIL（缺陷信号，不得改对） |
| **TC-AUTH-13** | P0 | A+D | 已有账号 `admin` | ① 插入 `ADMIN`（不同大小写）；② 用 `Admin` 登录；③（待有用户管理接口后）API 创建同名账号 | ① **ERROR 1062** 且消息含 `uk_sys_user_username`（不是 3819——两者不可混写）；② **登录成功**（ci 是刻意选择，正向钉住）；③ 返回 **40901「登录名已存在」**，**不得冒泡 50001**。⏳ ③ 依赖用户管理接口，当前 42 接口中不含，暂挂起 |

---

## 3. 智慧门禁

| 用例ID | 优先级 | 载体 | 前置条件 | 步骤 | 断言点与期望 |
|---|---|---|---|---|---|
| TC-ACC-01 | P0 | A+E | 车辆「正常」+ 权限「生效」且在有效期内 | POST /api/access-records/simulate `{passType:VEHICLE, targetNo, gate:东门, direction:IN}` | result=放行、reason 为空、passTime 为当前时间、流水落库 |
| TC-ACC-02 | P0 | A+E | 车辆 status=黑名单 | 同上 | result=拦截、reason 非空、同时产生「黑名单」告警 |
| TC-ACC-03 | P0 | A | 车辆正常但权限已失效 | 同上 | result=拦截、reason 含失效原因、产生告警 |
| TC-ACC-04 | P0 | A | 无任何权限记录的工号 | simulate `{passType:PERSON, ...}` | result=拦截 + 产生告警 |
| TC-ACC-05 | P1 | A | 权限 endTime 恰为当前时刻 | simulate | 固化边界语义后验证一致（当前实现 `lt now` 判失效 → endTime==now 视为失效） |
| TC-ACC-06 | P1 | A | PERSON 通行 | simulate `{gate:南门, direction:OUT}` | 按规则放行/拦截 |
| TC-ACC-07 | P1 | A+E | 合法人员 | simulate `{gate:"北门"}` | **API 层抛 BizException**（GATE_AREA 未知 key）：code=40001、HTTP 400、无流水落库。DB 层无 `chk_ar_gate`，不作 DB 断言 |
| TC-ACC-08 | P0 | A | 预置 1000 条流水 | GET /api/access-records 分页 + startTime/endTime 过滤 | 按 passTime **倒序**；范围过滤边界（00:00:00 / 23:59:59）包含性正确；total 正确 |
| TC-ACC-09 | P1 | A | 已有员工/车辆 | POST 重复 empNo / plateNo | HTTP 409、code=40901「数据已存在，请勿重复提交」 |
| TC-ACC-10 | P2 | A+D | — | ① API 创建 startTime>endTime 的权限；② SQL 直插违反 `chk_perm_time_range` | ① API 拒绝（code=40001）；② SQL 报 **3819** 且消息含 `chk_perm_time_range` |

---

## 4. 访客接待

| 用例ID | 优先级 | 载体 | 前置条件 | 步骤 | 断言点与期望 |
|---|---|---|---|---|---|
| TC-VIS-01 | P0 | A+E | 访客「已预约」 | POST /api/visitors/{id}/check-in | status=在场、checkInTime 已写 |
| TC-VIS-02 | P0 | A+E | 访客「在场」 | POST /api/visitors/{id}/check-out | status=已签销、checkOutTime 已写 |
| TC-VIS-03 | P0 | A+E | 访客「滞留预警」 | POST check-out | 允许签销（契约：在场/滞留预警 → 已签销） |
| TC-VIS-04 | P0 | A | 已签销 | 再次 check-out | code=40001、状态不变、无二次记录 |
| TC-VIS-05 | P1 | A | 「已预约」未进场 | POST check-out | code=40001（当前实现行为需固化，若放行则记 Major） |
| TC-VIS-06 | P0 | A | 在场且 expireTime 已过 | 触发 scanVisitors（手动触发或等调度） | status=滞留预警 + 新增「访客滞留」告警（level=警告） |
| TC-VIS-07 | P0 | A | 同一访客已滞留 | 二次触发扫描 | 状态不变、**告警不重复新增** |
| TC-VIS-08 | P1 | A | expireTime 恰为当前时刻 | 触发扫描 | 当前实现 `lt now` → 不判滞留（固化语义） |
| TC-VIS-09 | P0 | A+E | — | POST /api/visitors 新建 | 落库成功；返回 idCard 脱敏（前6后4）；库内密文 |
| TC-VIS-10 | P1 | A+E | 多条访客 | GET /api/visitors?visitorName=&status= 组合筛选 | 分页正确、按 id 倒序 |

---

## 5. 智慧仓管（状态机 + 审批 + 逾期；风险最集中）

| 用例ID | 优先级 | 载体 | 前置条件 | 步骤 | 断言点与期望 |
|---|---|---|---|---|---|
| TC-STK-01 | P0 | A+E | 普通物资在库 | POST /api/stock-records/out | approveStatus=免审、outTime 已写、物资→在借 |
| TC-STK-02 | P0 | A+E | 贵重物资（valuable=true）在库 | POST out | approveStatus=**待审批**、outTime 为空、**物资状态仍为在库** |
| TC-STK-03 | P0 | A+E | 待审批单 | POST /api/stock-records/{id}/approve `{approved:true}` | 已批准、outTime 已写、物资→在借 |
| TC-STK-04 | P0 | A | 待审批单 | approve `{approved:false}` | 已驳回；物资仍在库；**驳回单再 approve / return 均被拒**（40001） |
| TC-STK-05 | P0 | A | 同一贵重物资产生两张待审批单，第一张已批准 | approve 第二张 | code=40901「物资当前状态为「在借」，无法执行出库」；两张单审批状态不被污染 |
| TC-STK-06 | P0 | A | 物资在借 / 逾期 / 维修中 | POST out | code=40001「不可出库」 |
| TC-STK-07 | P0 | A+E | 免审 OUT 单 | POST /api/stock-records/{id}/return | returnTime 已写、type 不变、物资→在库（含从逾期恢复） |
| TC-STK-08 | P0 | A | 已归还单 | 再次 return | code=40001「请勿重复操作」 |
| TC-STK-09 | P0 | A | 待审批 / 已驳回的 OUT 单 | POST return | code=40001「未通过审批，未实际出库，不可归还」 |
| TC-STK-10 | P1 | A | IN 记录 | POST return | code=40001「仅出库记录可执行归还」 |
| **TC-STK-11** | P0 | A | 物资在库 | **并发**：2 线程同时 POST out 同一物资 | 期望 1 成 1 败（败者 40901）；**当前无锁，预期双双成功 → Blocker**。判定 SQL 见 §5.1 |
| **TC-STK-22** | P0 | A | 同一贵重物资的同一张待审批单 | **并发**：2 请求同时 approve(approved=true) | 期望 1 成 1 败；当前预期两次都成功、产生 2 张已批准单 → Blocker |
| **TC-STK-23** | P0 | A | 同一条 OUT 记录 | **并发**：2 请求同时 return | 期望 1 成 1 败；当前预期 return_time 被写两次 → Blocker |
| **TC-STK-24** | P0 | A | 同一物资：A 走免审出库，B 走待审批单批准 | 并发执行 | 不出现「已批准但物资已不在库」矛盾态；最终库存状态唯一 |
| TC-STK-12 | P0 | A | 张三在「工具」类有逾期未还 | 张三领「工具」类物资 | code=40001「禁止再次领用」 |
| TC-STK-13 | P1 | A | 张三在「工具」类逾期 | 张三领「备件」类物资 | 允许（逾期限制按类别隔离，与 `checkOverdueSameCategory` 一致） |
| TC-STK-14 | P0 | A | 张三逾期单已归还 | 张三再领同类别 | 允许领用 |
| TC-STK-15 | P0 | A | 出库 expectReturnTime 已过未还 | 触发 scanMaterials | 物资→逾期 + 新增「物资逾期」告警 |
| TC-STK-16 | P0 | A | 同一逾期物资 | 二次触发扫描 | **不重复告警**（实现以物资状态==在借做幂等） |
| TC-STK-17 | P1 | A | 逾期物资 | POST return | 物资回在库；已产生的逾期告警仍为未处理，需人工处理（固化行为） |
| TC-STK-18 | P0 | A+E | 任意状态物资 | POST /api/stock-records/in | type=IN、approveStatus=免审、物资→在库 |
| TC-STK-19 | P1 | A | — | POST out/in 传不存在的物资编码 | code=40401「物资不存在」 |
| TC-STK-20 | P2 | A | 在借物资 | DELETE /api/materials/{id} | 【待确认：当前是否校验状态，建议拦截；未拦截记 Minor 并建议加校验】 |
| TC-STK-21 | P1 | A | — | POST out 缺 expectReturnTime | code=40001（DTO @Pattern/@NotNull 校验） |
| **TC-STK-25** | P1 | A | 任意已出库单 | ① POST return；② 尝试任何写入 `type=RETURN` 的请求/直接 SQL | ① 归还后**无新增 type='RETURN' 记录**（仅更新原 OUT 单 return_time）；② 直接写入 RETURN 被 `chk_sr_type` 拒（3819）。出现 RETURN 记录 = 与裁决 15 不符，立即上报 |

### 5.1 并发用例执行与判定口径（TC-STK-11/22/23/24）

- 执行方式：**并发工具或并发脚本，10 并发 × 20 轮**（单机手工点击几乎必然复现不出，必须并发）。
- 判定 SQL（任一命中即缺陷）：

```sql
-- 同一物资存在多张「免审/已批准」且未归还的 OUT 单 = 并发缺陷
SELECT material_code, COUNT(*) c FROM stock_record
WHERE type='OUT' AND return_time IS NULL AND approve_status IN ('免审','已批准')
GROUP BY material_code HAVING c > 1;

-- 同一物资被重复出库后，物资状态与单据状态不一致
SELECT m.code, m.status, r.approve_status, r.return_time
FROM material m JOIN stock_record r ON r.material_code = m.code
WHERE m.status <> '在借' AND r.approve_status IN ('免审','已批准') AND r.return_time IS NULL;
```

---

## 6. 权限时效与定时任务（⏳ 部分依赖 M2 定时任务配置）

| 用例ID | 优先级 | 载体 | 前置条件 | 步骤 | 断言点与期望 |
|---|---|---|---|---|---|
| TC-SCH-01 | P0 | A | 权限 endTime 已过、status=生效 | 触发 scanPermissions | →失效 |
| TC-SCH-02 | P0 | A | 权限 endTime 在 7 天内 | 触发扫描 | →临期 |
| TC-SCH-03 | P1 | A | endTime 恰为 now+7 天 | 触发扫描 | →临期（`le` 含等号，固化语义） |
| TC-SCH-04 | P0 | A | 临期权限到期 | 触发扫描 | →失效 |
| TC-SCH-05 | P1 | A | 扫描中单条记录处理抛异常 | 注入异常后触发扫描 | 期望：**单条异常不影响本轮后续记录处理**，且不阻断下一轮。【当前 for 循环无 try-catch，单条异常会中断本轮 → 记缺陷】 |
| TC-SCH-06 | P1 | A | 三任务并发 | 正常运行 10 分钟 | 无死锁、无重复告警 |
| TC-SCH-07 | P1 | M | 运行中 | 重启应用 | 任务自动恢复执行，状态字段无需人工修复 |
| TC-SCH-08 | P1 | M | 生产配置 | 检查扫描间隔配置 | 间隔可配置且**非 1 分钟演示值**；建议：权限/逾期 1 小时、访客滞留 5 分钟【待确认：生产取值】 |

---

## 7. 告警与运营看板

| 用例ID | 优先级 | 载体 | 前置条件 | 步骤 | 断言点与期望 |
|---|---|---|---|---|---|
| TC-ALM-01 | P1 | A | 多类型告警 | GET /api/alarms?type=&level=&status=未处理 | 分页正确、按 id 倒序 |
| TC-ALM-02 | P1 | A | 未处理告警 | POST /api/alarms/{id}/handle | 已处理 + handleTime 已写；重复 handle 行为需固化（幂等或 40001）【待确认】 |
| TC-DASH-01 | P0 | A+E | 预置当日流水/在场访客/在借物资/未处理告警各 N 条 | GET /api/dashboard/summary | 5 个统计值与预置**完全一致**；重点：todayAccessCount 跨日边界、materialOverdue 与物资状态一致 |
| TC-DASH-02 | P0 | A+E | 近 7 天数据 | GET /api/dashboard/access-trend | dates/inCounts/outCounts 均为 7 个元素且等长；含**今日 0 点边界** |
| TC-DASH-03 | P1 | A+E | — | GET /api/dashboard/material-status | 各状态计数总和 = 物资总数；状态值 ∈ {在库,在借,逾期,维修中} |

---

## 8. 数据迁移与上线割接

> 迁移管道为 devops 的 `csv-to-inserts.py` + `docker exec -i` 灌入（MySQL 8 服务端 local_infile 默认 OFF，原 LOAD DATA 方案不可用）。
> 该管道**同时服务**：① H2 存量迁移（条件执行）② **客户 Excel 基础数据导入（默认路径必经步骤，默认必跑）**。

| 用例ID | 优先级 | 载体 | 前置条件 | 步骤 | 断言点与期望 |
|---|---|---|---|---|---|
| TC-MIG-01 🔀 | P0 | D+M | 客户有 H2 存量数据 | ① H2 侧逐表 COUNT；② 导入后 MySQL 侧逐表 COUNT | 8 张表行数一一相等 |
| **TC-MIG-02** | P0 | M+D | 源数据文件、目标库就绪 | ① **源端正则预检**：对 CSV 全部时间列跑 `^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$`；② 执行导入（stderr 重定向到日志文件）；③ 目标端查零值日期；④ **行为断言**：插入 `2026-13-45 00:00:00` | ① 源端脏值处数 = **0**（最早暴露，远早于导入中途失败）；② **导入日志 0 字节**（脏数据表现为整行报错中止；有任何 ERROR/WARNING 均需逐条看完）；③ `SELECT COUNT(*) FROM access_record WHERE pass_time='0000-00-00 00:00:00'` = **0**（目标端兜底）；④ **必须报错中止**（非零但非法的日期）。④ 是**只依赖 `STRICT_TRANS_TABLES`、不依赖 `NO_ZERO_DATE`** 的退路断言：将来 `NO_ZERO_DATE` 被移除时，本条仍有效，只需把零值那条降级为可选，不会出现整条用例失效 |

> **`sql_mode` 必须双断言，缺一即 FAIL（team-lead 裁定 + data-engineer 复核）**：
> `STRICT_TRANS_TABLES` 管的是"非法值报错还是静默截断"，`NO_ZERO_DATE` 管的是"零值日期被拒还是被接受"，**是两条不同防线**。
> 客户很可能只配了前者没配后者，此时 `'0000-00-00 00:00:00'` 会被**静默接受**（不报错也不警告）——正是最怕的"静默污染"：一个零值混进 `pass_time`，排序与区间查询全部失真且看不出异常。**所以 `NO_ZERO_DATE` 也必须列为必查项**。
| TC-MIG-03 | P0 | D | 导入完成 | ① 列表按时间排序（passTime 倒序）；② `SELECT MIN(pass_time), MAX(pass_time)`；③ 查会话 sql_mode；④ 大小写行为验证 | ① 排序正确（DATETIME 与原字符串序可能不同，重点验跨年/跨月）；② MIN/MAX 落在真实业务时间区间内；③ **sql_mode 双重断言**：**.sql 头部**须写入 `SET SESSION sql_mode='STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,…'`（**会话级强制**，即使服务端全局被改宽松，导入会话仍严格）；**同时**导入前用 runbook §7 的命令断言**服务端全局 sql_mode** 含 `STRICT_TRANS_TABLES`（防配置漂移）。两者是两件事，都要断；④ **钉住行为变化**：`emp_no='E001'` 与 `'e001'` 在 `utf8mb4_0900_ai_ci`（ci + NO PAD）下查到同一行——**这是预期行为变化，不是 bug**，用此用例固定住，防止以后被人当缺陷上报 |
| TC-MIG-04 🔀 | P0 | D | 导入完成 | 孤儿引用检查（两类） | ① `SELECT DISTINCT sr.material_code FROM stock_record sr LEFT JOIN material m ON sr.material_code=m.code WHERE m.code IS NULL` = **0 行**；② `access_permission.target_type='PERSON'` 且 `target_no` 在 employee 中不存在的条数 = **0 行**。⚠️ **演示数据 `data.sql` 下本项预期非零**（已知 `alarm` 引用不存在的 `M-SPARE-009`）——该场景作为**检查脚本有效性的反向验证**，正式数据下必须 0 |
| TC-MIG-05 | P0 | A | 导入完成（空库路径用造数） | 在导入数据上执行 check-in/out、return、审批等操作 | 时间字段写入为 DATETIME 正常值；前端显示仍为 `yyyy-MM-dd HH:mm:ss` |
| **TC-MIG-06** | P1 | M+D | 容器 TZ 可设置 | 环境矩阵：TZ=Asia/Shanghai 与 TZ=UTC 各插一条通行记录 | TZ=Asia/Shanghai **必过**：`NOW()/UTC_TIMESTAMP()/@@time_zone` 一致为北京时间，API 返回 passTime 与库内 pass_time 一致；TZ=UTC 为**已知缺陷（偏移 8 小时）**，修复前若客户环境为 UTC → **No-Go** |
| TC-MIG-07 | P1 | M | 迁移前快照 | 执行回滚 | 可回退到迁移前状态 |
| **TC-MIG-08** | P0 | M+D | 造 1 份含脏日期行/非法枚举行的测试 CSV | ① 执行转换 + 导入；② 查**该表**行数；③ **换乱列序**的 CSV 再跑一次 | ① **该表行数与导入前一致**（空表则为 0）。脚本遇错 **exit 4** 且不输出 `COMMIT;`（stderr 形如 `[ABORT] 第 1 行枚举非法…`）；行数增加 = 部分导入 = FAIL。依据：半成品库比完全失败更危险（有数据、行数接近，现场易误判成功）；② **口径：每张表一个事务，表内原子、表间不原子**——其他表不受影响是**预期，不得判 FAIL**；③ **列序错乱必须在转换阶段报"列名不匹配"并整体中止**，不得按位置静默映射（否则"员工姓名"被写进"身份证"列，导入不报错、上线才发现数据全错） |
| TC-MIG-09 | P0 | A+D | — | ① API 用 `e1001` 建员工、再用 `E1001` 建；② SQL 直查 | ① 第二次被拒（HTTP 409 / 40901）；② 唯一键在 ci collation 下不产生两条 |
| TC-MIG-10 | P1 | A | 库存车牌「新A12345」 | 模拟通行传「新a12345」 | 应能匹配（ci collation 特性）【待确认：产品期望这是特性还是缺陷】 |
| TC-MIG-11 | P1 | A+D | — | 录入含中文 + emoji 的备注后回查 | 完整显示、无 `???`；utf8mb4 生效 |
| **TC-MIG-12** | P0 | M+D | 造测试 CSV：GBK 编码 + 小写工号 `e1001` + 非法 status「再職」+ 空业务键 + 列数不符 | 运行 `csv-to-inserts.py` 转换（管道已由 devops 落地） | 四查全部在**转换阶段**报行号并整体中止（**exit 4**，stdout 末行为 `START TRANSACTION;`、`COMMIT;` 缺失）：① 编码：非 UTF-8 → `[ABORT] 编码预校验失败…请先执行 iconv -f GBK -t UTF-8`，**不得自动转码后继续**（拍板口径）；② 业务键**空值** → `[ABORT] 第 N 行业务键 emp_no 为空`；③ 枚举非法 → 报行号 + 合法值清单；④ 列数不符 → 报行号。⚠️ **例外（不要写成应报错）**：业务键**小写不报错**，被**自动大写归一化**（`e1001` → `E1001`）——这是期望行为。⏳ 列名校验（换乱列序）见 TC-MIG-08 第③步 |

| **TC-MIG-13** | P1 | D | UAT 阶段，造数据：含 `type='OUT' AND return_time IS NULL` 的未完结出库单 | 执行 `docs/ops/archive.sql` 归档作业，随后查归档表 | 归档表中 `type='OUT' AND return_time IS NULL` 的行数 = **0**。专门防「归档条件写反，把在外未还的单据搬走」——这类事故在归档后不可见，客户对账时才发现 |

> **尚未立用例（留存期待定）**：24/36 个月留存期为建议值，合规口径未确认前，「超出留存期后删除归档数据」**不进用例**【待确认】。
> 反向用例：`data.sql` 中已知的孤儿物资 `M-SPARE-009` 可用于验证 TC-MIG-04 的检查脚本本身有效（演示数据不进生产）。
> **条件执行（默认路径 A 空库上线不启用）**：存量 PII 加密回填 Job 相关验证——仅当客户确认有真实存量时才启用（backend 提供，复用同一套 CryptoService）。

---

## 9. 枚举与 CHECK 约束（封版口径）

### 9.1 CHECK 约束断言矩阵（TC-ENUM-01，与 `V1__init.sql` 对齐）

| 用例ID | 优先级 | 载体 | 步骤 | 断言点与期望 |
|---|---|---|---|---|
| **TC-ENUM-01** | P0 | D | 对每条约束：① 正向插 1 条合法值；② 负向插 1 条非法值 | 负向必须报 **MySQL 错误号 3819** 且错误消息**含该约束名**（双条件，排除 1364/1406/1062/1146 假通过）；正向全部成功 |

约束清单（**20 条，已固化**；与 `V1__init.sql` 实测逐字对齐，环境验收可用 `information_schema.TABLE_CONSTRAINTS` 核对 20 行）：

| 约束名 | 表.字段 | 合法值 | 可空 |
|---|---|---|---|
| chk_employee_status | employee.status | 在职 / 离职 | 否 |
| chk_vehicle_status | vehicle.status | 正常 / 黑名单 | 否 |
| chk_vehicle_owner_type | vehicle.owner_type | 员工 / 访客 / 仓储 | **是** |
| chk_perm_target_type | access_permission.target_type | PERSON / VEHICLE | 否 |
| chk_perm_status | access_permission.status | 生效 / 临期 / 失效 | 否 |
| chk_perm_area | access_permission.area | 办公区 / 生产区 / 仓储区 / **生活区** | 否 |
| chk_perm_time_range | access_permission（end_time >= start_time） | 范围约束 | — |
| chk_ar_pass_type | access_record.pass_type | PERSON / VEHICLE | 否 |
| chk_ar_result | access_record.result | 放行 / 拦截 | 否 |
| chk_ar_direction | access_record.direction | IN / OUT | **是** |
| chk_visitor_status | visitor.status | 已预约 / 在场 / 已签销 / 滞留预警 | 否 |
| **chk_visitor_area** | visitor.area | 办公区 / 生产区 / 仓储区 / 生活区 | **是** |
| chk_material_status | material.status | 在库 / 在借 / 逾期 / 维修中 | 否 |
| chk_material_category | material.category | 工具 / 备件 / 车辆 | **是** |
| chk_sr_type | stock_record.type | **IN / OUT**（裁决 15 移除 RETURN） | 否 |
| chk_sr_approve_status | stock_record.approve_status | 免审 / 待审批 / 已批准 / 已驳回 | 否 |
| chk_alarm_level | alarm.level | 提示 / 警告 / 严重 | 否 |
| chk_alarm_status | alarm.status | 未处理 / 已处理 | 否 |
| chk_sys_user_role | sys_user.role | ADMIN / GATE / STORE | 否 |
| chk_sys_user_status | sys_user.status | 启用 / 禁用 | 否 |

> **刻意不建 CHECK 的两列**（用例里**不得**写"应报错"，反而要写正向用例钉住）：
> - `access_record.gate`：门岗四门由 Java 层 `GATE_AREA` 拦截，DB 再拦会导致客户新增道闸要改两处 DDL。
> - `alarm.type`：告警类型由代码常量生成、不接收外部输入，新增类型是正常扩展。
>
> **可空 4 列**（`vehicle.owner_type`、`material.category`、`access_record.direction`、`visitor.area`）：
> MySQL CHECK 对 NULL 判定为「未知 → 通过」，NULL 必须**能插入**，见 TC-ENUM-04（极易被误写成"应报错"）。
>
> **`chk_perm_time_range` 已建，语义 `end_time >= start_time`（team-lead 终裁）**：
> - `end_time < start_time`（真倒挂）→ **3819 + 约束名**
> - `end_time = start_time`（零时长）→ **放行，不报错**。理由：拦零时长是业务判断不是数据完整性判断；客户批量导入历史权限时"当天有效"很容易撞上，为它挡住一次导入代价过大。真要管，放 API 层。
> - ⚠️ **不要**断言"零时长应报 3819"。
>
> **大小写陷阱（写负向样本时必看）**：`utf8mb4_0900_ai_ci` 大小写不敏感，所以 `PERSON`/`person`、`IN`/`in` 在 DB 层是**同一个值**——用小写做非法值样本会得到"应该报错却放行"的假通过。负向样本一律用 `PERSONS`/`INOUT`/`CAR` 这类**不同词**，不用大小写变体。
> （对应地，小写/尾空格在**API 层**必须被拒——那是另一层的断言，见 TC-ENUM-02。）

### 9.2 防线归属总表（决定用例优先级与断言归属）

| 字段 | 外部可传入 | 应用层校验 | 防线归属 | 测试落点 |
|---|---|---|---|---|
| employee.status / vehicle.status / vehicle.owner_type / material.category / access_permission.area | ✅ | ❌（area 仅非空） | 🔴 DB CHECK 是最后防线 | TC-ENUM-01 强断言 + TC-ENUM-02 API 层 |
| visitor.area | ✅ | ⏳ 待补 `@NotBlank` | 🟡 **分层**：DB CHECK 只拦非空值（NULL 放行）→ API 必填才是主防线 | TC-ENUM-04（DB 插 NULL **放行**）+ TC-ENUM-02（API 传空 **40001**，当前预期 FAIL）+ TC-ENUM-01（非空非法值 3819 + 约束名） |
| material.status | ❌（DTO 已移除） | — | ⚪ 外部传不进来，服务端固定为「在库」 | **不写"非法 status 期望 40001"**，改为忽略语义（见 §9.3 口径登记） |
| access_permission.target_type / time_range | ✅ | ✅ validate() | 🟢 双层 | 回归集（验证校验未被删） |
| access_record.pass_type / direction | ✅ | ✅ @Pattern | 🟢 双层 | 回归集 |
| access_record.gate | ✅ | ✅ GATE_AREA 抛异常 | 🟢 仅应用层 | TC-ACC-07（API 层），不测 DB |
| access_permission.status / access_record.result / visitor.status / stock_record.type / stock_record.approve_status / alarm.level / alarm.status | ❌ | — | ⚪ 不接收外部输入 | **不写枚举用例**（测不到的路径） |

### 9.3 API 层枚举校验（TC-ENUM-02，⏳ 依赖 M2 的 DTO 校验）

| 用例ID | 优先级 | 载体 | 步骤 | 断言点与期望 |
|---|---|---|---|---|
| **TC-ENUM-02** | P0（6 字段）/ P1（5 字段） | A | 对每个字段，API 提交非法值：中文错字「再职」、繁体「在職」、小写 `person`、尾空格 `'PERSON '`、空串 | HTTP 400、code=40001、**拒绝入库** |

- **P0 断言集（6 字段，API 是唯一防线）**：employee.status、vehicle.status、vehicle.owner_type、material.category、access_permission.area、visitor.area。
  - ⚠️ **`material.status` 已移出本集**：backend 把 status 从 DTO 移除、服务端固定新建为「在库」（修改时以库中值为准），非法值**到不了应用层**。该字段改为**忽略语义**断言（见下方口径登记）——**不得**再写"传非法 status 期望 40001"，那样会得到 200 并被误判成缺陷。
- **P1 回归集（5 字段，验证既有校验未被删除）**：target_type、pass_type、direction、gate、time_range。
- PASS 判定基线：backend 的 DTO 清单与 6 个 P0 字段一一对应；**任一 P0 字段 FAIL = DTO 校验漏项**，可直接定位责任方。

**口径登记项（4 条；不参与参数化展开，作用是防误判）**

| 字段 / 路径 | 现状 | 断言口径 |
|---|---|---|
| `material.status`（POST / PUT） | DTO 已移除，值被忽略 | **忽略语义**：传非法值期望 **code=0 / HTTP 200**，且落库 status 仍为合法枚举 |
| `visitor.area` 空值 | 只有 `@Pattern` 无 `@NotBlank`；`@Pattern` 对 `null` **视为通过** → 空值双向裸奔 | **目标态 40001**。**当前预期 FAIL**（backend 补 `@NotBlank` 之前）。⏳ 补完转 PASS。**不得为了让用例转绿而放宽断言** |
| `material.code`（PUT）/ `employee.empNo`（PUT）/ `vehicle.plateNo`（PUT） | 已从入参移除 | **禁止写 40001 断言**：非法值被忽略，期望 200 |

> **`visitor.area` 的分层交叉验证（team-lead 裁定）——「API 必填 + DB 可空」两侧不是矛盾，是分层**：
> - **DB 侧**：直插 NULL → **放行**（`chk_visitor_area` 写的是 `area IS NULL OR area IN (...)`，CHECK 对 NULL 判定为"未知→通过"），断言落在 **TC-ENUM-04**；
> - **API 侧**：传空 → **40001**（目标态），断言落在 **TC-ENUM-02**；
> - **为什么 API 必填**：访客登记若不知道去哪个区域，门岗放行就没有判断依据；
> - **为什么 DB 不收紧**：允许 NULL 保留了"全园区通用访客"的扩展空间，将来支持不用改表（与 `alarm.type` 刻意不建 CHECK 同理）。
> - **改动落点**：**DDL 不动**（V1 已冻结，改 `NOT NULL` 是破坏性变更且需回填），**只在 API 层补 `@NotBlank`**，与 `access_permission.area`（`@NotBlank` + `@Pattern` 双保险）口径一致。
> - **影响面（data-engineer 核实）**：`visitor.area` 在 Service / 定时任务里无消费点，**不会崩**，影响是业务完整性；且 update 路径走 `updateById()`（NOT_NULL 策略）不会把已有 area 清成 null，**风险只在 create**。

### 9.4 尾空格与大小写（03a / 03b）

| 用例ID | 优先级 | 载体 | 步骤 | 断言点与期望 |
|---|---|---|---|---|
| TC-ENUM-03a | P1 | D | SQL 直插 `'PERSON '`（尾空格） | MySQL 8 默认 `utf8mb4_0900_ai_ci`（**NO PAD**）→ **拒绝（3819）**；若 collation 降级为 `general_ci`（PAD SPACE）→ 放行。双分支预期，与 DDL 降级决策联动 |
| TC-ENUM-03b | P1 | D + A | ① SQL 直插小写 `'person'`；② API 提交 `'person'` | ① **DB 放行**（ci 大小写不敏感，且业务侧 toUpperCase 归一化，放行为正确预期）；② **API 拒绝**（@Pattern 默认大小写敏感）。「DB 放行 + API 拒绝」组合 = 防线分层正确性的交叉验证。**双向锁定**：若某天 API 也放行，说明有人给正则加了 `(?i)`，需确认有意还是误改 |
| TC-ENUM-04 | P2 | D | 对 **4 个可空字段**（vehicle.owner_type / access_record.direction / material.category / **visitor.area**）直插 NULL | **插入成功**（MySQL CHECK 对 NULL 判定为「未知 → 通过」；防 NOT NULL 误套，防未来 DDL 误加约束）。注意 direction「DB 可空 / API 必填」是两回事 |
| **TC-ENUM-05** | P1 | D | 对刻意无 CHECK 的两列（`access_record.gate`、`alarm.type`）直插任意值（如 `'北门'`、`'自定义告警类型'`） | **插入成功**（正向钉住）。目的：防止将来有人"顺手"把 CHECK 加回来，导致客户新增道闸/新增告警类型要改 DDL。**不得**写成"应报 3819" |

### 9.5 结果解读表（写进用例文档，防执行人误判）

| 环境 | TC-ENUM-01 表现 | 结论 |
|---|---|---|
| MySQL ≥ 8.0.16（本项目确认 8.0） | 非法值报 3819 拒绝 | PASS |
| MySQL < 8.0.16 | 非法值静默成功 | **FAIL，且该 FAIL 是有效缺陷信号（DB 层防线不存在）** |

> ⚠️ 执行人**不得**把这些 FAIL 修改成"预期通过"。5.7 场景下真正必须过的是 **TC-ENUM-02（API 层）**，它与版本无关。
> 本项目客户环境已确认为 **MySQL 8.0**，5.7 降级预案弃用，上述为历史记录保留。

---

## 10. 前端 E2E 冒烟（15 条，工程见 `smart-park-frontend/e2e/`）

| 用例ID | 优先级 | 载体 | 步骤要点 | 断言点 |
|---|---|---|---|---|
| TC-FE-01 | P0 | E | 登录 → 登出 → 浏览器回退 | 登录进 /dashboard 且 5 张卡片渲染；登出后回退不进入 /dashboard |
| TC-FE-02 | P0 | E | 错误密码登录 | `.el-message--error` 可见；URL 仍为 /login |
| TC-FE-03 | P0 | E+A | 访客列表身份证列 | 列文本匹配 `^\d{6}\*{4,}\d{4}$`；响应体原文与页面 DOM 均不含 18 位明文 |
| TC-FE-04 | P0 | E | 员工 新增→筛选→编辑→重置→删除 | 各步 ElMessage 文案正确；删除后接口侧反查为空 |
| TC-FE-05 | P0 | E | 车辆 新增→筛选→编辑为黑名单→删除 | 状态切换后按状态筛选可命中；删除后接口侧反查为空 |
| TC-FE-06 | P0 | E | 物资 新增（贵重）→状态筛选→编辑→删除 | 显示「贵重」标签；筛选与删除闭环 |
| TC-FE-07 | P0 | E | 权限 新增→四区域下拉→筛选→删除 | **区域下拉恰好 4 个值且含「生活区」** |
| TC-FE-08 | P0 | E | 访客 预约登记→进场登记→离场签销 | 状态链 已预约→在场→已签销；终态无进场/签销按钮 |
| TC-FE-09 | P0 | E | 普通物资 申领出库 → 归还 | 出库后物资「在借」，归还后「在库」 |
| TC-FE-10 | P0 | E | 贵重物资 申领出库 → 批准 | 出库后「待审批」且物资仍「在库」；批准后「已批准」且物资「在借」 |
| TC-FE-11 | P1 | E | 模拟通行（放行 1 次 + 拦截 1 次）+ 非法门岗 | 放行 result=放行 reason 空；拦截 result=拦截 reason 非空；非法门岗被 API 拒 |
| TC-FE-12 | P0 | E | 看板三图渲染 | 5 卡片非 `-`；2 个 canvas 尺寸 > 0；**无 pageerror / console error** |
| TC-FE-13 | P0 | E | 清除/过期 token 后操作 | 跳转 /login 且不白屏 |
| TC-FE-14 | P1 | E | 拦截接口返回 code≠0 / 含 XSS payload 的名称 | ElMessage 提示一致；页面骨架仍在；**XSS payload 不执行（无 img[src=x]、window.__xss 未定义）** |
| **TC-FE-15** | P2 | E | 出入库记录页类型筛选下拉 | 选项恰为「入库 / 出库」；**出现「归还」即 FAIL**。当前前端仍保留 RETURN 选项，修复前预期 FAIL（有效缺陷信号，不得改对） |

> 兼容性（原 TC-FE-11 独立用例）由 Playwright **三浏览器矩阵**（chromium/firefox/webkit × 1366×768）承载，不单独占用例条目。

---

## 11. 安全补充

| 用例ID | 优先级 | 载体 | 步骤 | 断言点与期望 |
|---|---|---|---|---|
| TC-SEC-01 | P0 | A | 触发未捕获异常（如传类型不符参数） | 响应体：`code=50001`、message=「系统繁忙，请稍后重试」、**含 traceId**；**不得**含表名/字段名/SQL 片段/堆栈。详情按 traceId 在服务端日志可查 |
| TC-SEC-02 | P0 | D | 直连数据库查访客表 | `id_card` 列为**密文**（裁决 17：cipher + hash + mask 三列）；**不得出现明文 18 位身份证**；手机号同理。命中即 No-Go |
| **TC-SEC-03** | P0 | D | 静态扫描全仓脚本与代码：`*.sql`、`*.sh`、`*.py`、`docs/ops/*` | **不得出现任何加解密实现**（如 `AES_ENCRYPT`/`AES_DECRYPT`、openssl 命令行、pycryptodome/JCE 调用、硬编码密钥字符串）。密钥一旦落进脚本与 shell history 即泄露。命中即 **Blocker**（红线裁定）。加解密只允许经 backend 的 CryptoService 在应用内完成 |
| **TC-AUD-01** | P1 | A | 执行登录、审批、模拟通行（放行拒绝）三类关键操作 | 读取本次响应的 traceId，捕获全部日志事件 | 每类操作必须产生**一条 INFO 级、内容含该 traceId** 的审计日志（可实现方式见 `src/test/java/com/smartpark/security/AuditLogTest.java`，logback 内存 Appender 捕获）。⏳ backend 审计日志落地前保持 @Disabled，落地后启用 |

---

## 12. 非功能测试规格

| 类型 | 用例 | 目标（确认前提：MySQL 8.0 / Asia/Shanghai / 日均通行 ≤1 万） | 通过标准 |
|---|---|---|---|
| 性能-并发 | 登录、列表分页、模拟通行、出库 4 类场景梯度加压 | 50 并发 | P95 < 1s（读）/ < 2s（写）；错误率 < 0.5%；无 5xx |
| 性能-数据量 | 流水表 100 万行后查列表与看板 | 1 年量级 | 列表 P95 < 2s、看板 < 3s；不达标则加索引（passTime、status 复合索引） |
| 性能-定时任务 | 100 万行规模触发扫描 | — | 单轮 < 60s，不阻塞业务写 |
| 安全-注入 | OWASP ZAP 基线 + 手工 payload（`' OR 1=1`、`%`） | 全接口 | 无注入生效；重点验 like 拼接处 |
| 安全-XSS | 姓名/备注/拦截原因提交存储型 payload | — | 渲染不执行（见 TC-FE-14） |
| 安全-越权 | 门岗 token 遍历接口 | **42 接口 × 3 角色**（非 44） | 与 §2 矩阵一致，40301 |
| 安全-配置 | 访问 /api/h2-console、/actuator、/swagger | 生产配置 | 全部 404/403；CORS 收敛为同源 Nginx 反代 |
| 可靠性-降级 | kill mysql 30s 后恢复 | — | 应用不崩、自动重连、定时任务恢复 |
| 可靠性-备份恢复 | 以每日 **02:30** 自动备份产物为源 → 新实例恢复 → 对账（全队统一备份时间 02:30） | RTO < 30 分钟 | 全表行数一致 + 抽样单据一致；且验证 02:30 定时任务确实产出备份文件（非手工触发补做） |
| 兼容性 | Chrome/Edge/Firefox 最新两个大版本 @1366×768 | — | 布局不错位、图表自适应【待确认：客户浏览器基线，若含国产浏览器需追加】 |

---

## 13. 用例清点与依赖汇总

| 章节 | 区间 | 条数 |
|---|---|---|
| 1 冒烟 | SMOKE-01 | 1 |
| 2 鉴权与安全 | AUTH-01~13 | 13 |
| 3 门禁 | ACC-01~10 | 10 |
| 4 访客 | VIS-01~10 | 10 |
| 5 仓管 | STK-01~25 | 25 |
| 6 定时任务 | SCH-01~08 | 8 |
| 7 告警看板 | ALM-01~02、DASH-01~03 | 5 |
| 8 迁移割接 | MIG-01~13（含 1 条留存期待定占位） | 13 |
| 9 枚举 | ENUM-01、02、03a、03b、04、05 | 6 |
| 10 前端 E2E | FE-01~15 | 15 |
| 11 安全与审计 | SEC-01~03、AUD-01 | 4 |
| **合计** | | **110** |

| 执行载体 | 条数 |
|---|---|
| A（后端自动化） | 66 |
| E（Playwright） | 15 |
| M（人工割接） | 7 |
| D（数据脚本） | 6 |
| 混合（A+E / A+D / M+D 等，按主载体计） | 10 |
| **合计** | **104** |

**必须先等 backend M2 骨架完成才能执行的用例**：§2 全 11 条（鉴权矩阵依赖 Security/JWT 与角色权限配置）、§3 的 TC-ACC-07/09（依赖 GATE_AREA 与唯一键 409 处理）、§5 的 TC-STK-21（依赖 DTO 校验）、§9.3 TC-ENUM-02（依赖 DTO @Pattern 全覆盖）、§11 TC-SEC-01（依赖 GlobalExceptionHandler 与 traceId，现已具备）。

**必须等 devops 管道完成才能执行的用例**：TC-MIG-02/08/12。

**条件执行（默认空库上线跳过）**：TC-MIG-01、TC-MIG-04 中与 H2 侧对账相关的断言。
