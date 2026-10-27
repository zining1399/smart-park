# 前后端接口契约（唯一事实来源）

- Base URL：`/api`（后端 context-path = `/api`）
- 统一响应体：`{ "code": 0, "message": "ok", "data": ... }`；业务错误 code ≠ 0，前端拦截器统一 ElMessage 报错。
- 分页请求参数：`pageNum`（默认 1）、`pageSize`（默认 10）+ 各列表自己的查询条件（均为 query 可选参数）。
- 分页响应 `data`：`{ "total": 100, "list": [...] }`
- 时间格式：`yyyy-MM-dd HH:mm:ss`（字符串）。
- ID 均为 Long，自增。

## 一、智慧门禁

### 员工 Employee
| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | |
| empNo | String | 工号，唯一 |
| name | String | 姓名 |
| dept | String | 部门 |
| phone | String | 手机号密文，**禁止前端渲染**，展示请用 `phoneMask` |
| phoneMask | String | 手机号掩码（前 3 后 4，如 `138****1234`），只读 |
| status | String | 在职 / 离职 |

- `GET /employees`（条件：name、dept、status）分页
- `GET /employees/{id}`
- `POST /employees` 新增（body 为 Employee，无 id）
- `PUT /employees/{id}` 修改：**工号 empNo 不可修改**（服务端以库中值为准，入参携带也忽略）
- `DELETE /employees/{id}`

### 车辆 Vehicle
| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | |
| plateNo | String | 车牌号，唯一 |
| ownerType | String | 员工 / 访客 / 仓储 |
| ownerName | String | 车主姓名 |
| dept | String | 归属部门（可空） |
| status | String | 正常 / 黑名单 |

- `GET /vehicles`（条件：plateNo、ownerType、status）分页；`POST/PUT/DELETE` 同上模式。

### 权限 AccessPermission（区域 + 有效期）
| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | |
| targetType | String | PERSON / VEHICLE |
| targetNo | String | 工号或车牌号 |
| targetName | String | 姓名或车牌（冗余展示用） |
| area | String | 办公区/生产区/仓储区/生活区 |
| startTime | String | 生效时间 |
| endTime | String | 失效时间 |
| status | String | 生效 / 临期 / 失效（服务端按时间计算） |

- `GET /permissions`（条件：targetType、targetNo、area、status）分页；`POST/PUT/DELETE` 同上。

### 通行流水 AccessRecord
| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | |
| passType | String | PERSON / VEHICLE |
| targetNo | String | 工号或车牌 |
| targetName | String | 姓名或车牌 |
| gate | String | 东门/西门/南门/货运门 |
| direction | String | IN / OUT |
| passTime | String | 通行时间 |
| result | String | 放行 / 拦截 |
| reason | String | 拦截原因（可空） |

- `GET /access-records`（条件：passType、targetNo、gate、direction、result、startTime、endTime）分页，按 passTime 倒序。
- `POST /access-records/simulate` 模拟终端上报，body：`{ "passType": "VEHICLE", "targetNo": "新A12345", "gate": "东门", "direction": "IN" }`。
  服务端逻辑：查白名单（车辆正常且在权限有效期内→放行，否则拦截并写 reason，同时产生一条告警）。返回创建后的 AccessRecord。

## 二、访客接待 Visitor
| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | |
| visitorName | String | 访客姓名 |
| idCard | String | 身份证密文，**禁止前端渲染**，展示请用 `idCardMask` |
| idCardMask | String | 身份证掩码（前 6 后 4），只读 |
| phone | String | 手机号密文，**禁止前端渲染**，展示请用 `phoneMask` |
| phoneMask | String | 手机号掩码（前 3 后 4），只读 |
| plateNo | String | 访客车牌（可空） |
| hostName | String | 被访人 |
| hostDept | String | 被访部门 |
| reason | String | 来访事由 |
| area | String | 访问区域 |
| appointTime | String | 预约到访时间 |
| expireTime | String | 授权失效时间 |
| status | String | 已预约 / 在场 / 已签销 / 滞留预警 |
| checkInTime | String | 进场时间（可空） |
| checkOutTime | String | 离场时间（可空） |

- `GET /visitors`（条件：visitorName、hostName、status）分页，按 id 倒序。
- `POST /visitors` 预约登记；`PUT /visitors/{id}` 修改；`DELETE /visitors/{id}`。
- `POST /visitors/{id}/check-in`：已预约→在场，写 checkInTime。
- `POST /visitors/{id}/check-out`：在场/滞留预警→已签销，写 checkOutTime。
- 定时任务：在场且当前时间 > expireTime → 置「滞留预警」并产生告警。

## 三、智慧仓管

### 物资 Material
| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | |
| code | String | 一物一码（RFID/二维码），唯一 |
| name | String | 物资名称 |
| category | String | 工具 / 备件 / 车辆 |
| spec | String | 规格型号 |
| dept | String | 责任部门 |
| location | String | 存放位置 |
| valuable | Boolean | 是否贵重（出库需审批） |
| status | String | 在库 / 在借 / 逾期 / 维修中 |

- `GET /materials`（条件：name、code、category、status）分页；`POST/PUT/DELETE` 同上。

### 出入库流水 StockRecord
| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | |
| materialCode | String | |
| materialName | String | |
| type | String | IN / OUT（裁决 15：**无 RETURN**，归还写 returnTime，type 不变） |
| borrower | String | 领用人（IN 时可空） |
| dept | String | 领用部门 |
| approveStatus | String | 免审 / 待审批 / 已批准 / 已驳回 |
| expectReturnTime | String | 预计归还时间（OUT 必填） |
| outTime | String | 出库时间（可空，批准后写） |
| returnTime | String | 归还时间（可空） |
| createTime | String | 申请/入库时间 |
| remark | String | 备注 |

- `GET /stock-records`（条件：materialName、type、approveStatus、borrower）分页，按 id 倒序。
- `POST /stock-records/in` 入库，body：`{ "materialCode": "...", "remark": "..." }` → 物资状态置「在库」。
- `POST /stock-records/out` 申领出库，body：`{ "materialCode": "...", "borrower": "...", "dept": "...", "expectReturnTime": "...", "remark": "..." }`
  - 物资须在库；领用人在该物资类别上有逾期未还记录 → 拒绝（业务错误）。
  - 贵重物资 → approveStatus=待审批，物资状态不变；普通物资 → 免审，直接出库，物资置「在借」。
- `POST /stock-records/{id}/approve` 审批，body：`{ "approved": true }`。批准→出库（写 outTime、物资在借）；驳回→记录置已驳回。
- `POST /stock-records/{id}/return` 归还：写 returnTime，type 不变（该单闭环），物资回切「在库」。
- 定时任务：在借且 expectReturnTime 已过 → 物资置「逾期」并告警。

## 四、告警中心 Alarm
| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | |
| type | String | 门禁异常 / 访客滞留 / 物资逾期 / 黑名单 |
| title | String | |
| content | String | |
| level | String | 提示 / 警告 / 严重 |
| status | String | 未处理 / 已处理 |
| createTime | String | |
| handleTime | String | 可空 |

- `GET /alarms`（条件：type、level、status）分页，按 id 倒序。
- `POST /alarms/{id}/handle` 处理（置已处理，写 handleTime）。

## 五、运营看板 Dashboard
- `GET /dashboard/summary` →
```json
{
  "todayAccessCount": 128,
  "visitorOnSite": 6,
  "materialOut": 12,
  "materialOverdue": 2,
  "unhandledAlarm": 5
}
```
- `GET /dashboard/access-trend` → 近 7 天通行量：`{ "dates": ["10-20",...], "inCounts": [...], "outCounts": [...] }`
- `GET /dashboard/material-status` → `[{ "status": "在库", "count": 40 }, ...]`

---

## 六、敏感字段与掩码契约（裁决 17）— 命名已冻结

**加密范围**：`visitor.id_card`、`visitor.phone`、`employee.phone`。
落库三列：`xxx`（AES-256-GCM 密文）+ `xxx_hash`（HMAC-SHA256，等值检索）+ `xxx_mask`（掩码，展示）。

### 字段命名（唯一口径，前后端与 DB 三层对照）

| 语义 | **JSON 字段名**（前端用这个） | DB 列名（SQL / DDL 用这个） |
|---|---|---|
| 身份证掩码 | `idCardMask` | `id_card_mask` |
| 手机号掩码 | `phoneMask` | `phone_mask` |
| 身份证密文 | `idCard` | `id_card` |
| 手机号密文 | `phone` | `phone` |

> **冻结说明**：JSON 一律**驼峰**（Jackson 未启用 snake_case 策略，实体字段即驼峰）。
> 历史上出现过的 `phone_mask` 说法指的是 **DB 列名**，不是 JSON 字段名——这是本次命名不一致的根因，现以本表为准，前端不得再做 `phoneMask \|\| phone_mask` 双读容错。

### 读写口径（三条硬约束）

1. **出参永不返回明文或密文**：列表/详情接口把 `idCard`、`phone` 替换为对应 `*_mask` 值，`*_hash` 恒为 `null`（不外泄）。前端若直接渲染 `phone` / `idCard` 会看到 base64 乱码，属硬性 No-Go。
2. **入参接受明文**：`POST` / `PUT` 传 11 位明文手机号（或 18 位身份证）即正常加密写入；**管理员可以正常修改手机号**，不存在"改不了"的隐性缺陷。
3. **入参传掩码 = 不更新该字段**（不报错）：服务端识别含 `*` 的值后丢弃该字段（B05 三层防护之服务端层）。因此前端"用户没改就把掩码原样回传"是安全的，**也推荐提交前直接剔除该字段**，两者行为等价。

### 不可变业务唯一键（改了会悬挂引用）

| 资源 | 不可变字段 | 前端要求 |
|---|---|---|
| 员工 | `empNo` | 编辑态禁用 + 提交剔除 |
| 车辆 | `plateNo` | 同上 |
| 物资 | `code`、`status`（status 由出入库流程维护） | 同上 |

## 七、鉴权与权限矩阵（最小鉴权，已冻结）

- 登录 `POST /auth/login`（匿名）→ `{ token, refreshToken, ... }`；`POST /auth/refresh` **免鉴权**（否则双 token 链路自断）。
- 凭证：`Authorization: Bearer <token>`；access 2 小时，refresh 7 天。
- 三角色：`ADMIN` 管理员 / `GATE` 门岗 / `STORE` 仓管。
- 账号禁用**即时生效**（每次请求查库校验 `status='启用'`），被禁用回 `40103`。

| 端点 | ADMIN | GATE | STORE |
|---|---|---|---|
| `GET /employees`、`GET /vehicles` | ✓ | ✓ | ✗ |
| `POST/PUT/DELETE /employees`、`/vehicles` | ✓ | ✗ | ✗ |
| `GET /permissions` | ✓ | ✓ | ✗ |
| `POST /permissions`、`PUT /permissions/{id}` | ✓ | ✓ | ✗ |
| `DELETE /permissions/{id}` | ✓ | ✗ | ✗ |
| `/access-records/**`（含 simulate） | ✓ | ✓ | ✗ |
| `/visitors/**`（含 check-in / check-out） | ✓ | ✓ | ✗ |
| `/materials/**` | ✓ | ✗ | ✓ |
| `GET /stock-records`、`POST /in`、`/out`、`/{id}/return` | ✓ | ✗ | ✓ |
| `POST /stock-records/{id}/approve` | ✓ | ✗ | ✗（严格仅管理员，防仓管自我审批） |
| `/alarms/**`、`/dashboard/**`、`/auth/**` | ✓ | ✓ | ✓ |

> `POST/PUT /permissions` 给门岗的理由：门岗登记访客后必须能发权限/改有效期，否则业务链条断裂。
> `DELETE` 收归管理员的理由：撤销风险高，且权限到期会自动失效，无需门岗手工删。

### 错误码（已冻结，前端直接映射文案）

| code | HTTP | 含义 |
|---|---|---|
| 0 | 200 | 成功 |
| 40001 | 400 | 参数错误 / 业务校验失败 |
| 40101 | 401 | 未登录或凭证无效 |
| 40102 | 401 | 用户名或密码错误 |
| 40103 | 401 | 账号已被禁用 |
| 40301 | 403 | 无操作权限 |
| 40401 | 404 | 资源不存在 |
| 40901 | 409 | 冲突（唯一键重复 / 状态已变更） |
| 50001 | 500 | 系统繁忙 |

响应体额外带 `traceId`（同时回写 `X-Trace-Id` 响应头），报错时请一并展示便于排查。
