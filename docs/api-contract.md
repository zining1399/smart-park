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
| phone | String | 手机号 |
| status | String | 在职 / 离职 |

- `GET /employees`（条件：name、dept、status）分页
- `GET /employees/{id}`
- `POST /employees` 新增（body 为 Employee，无 id）
- `PUT /employees/{id}` 修改
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
| idCard | String | 身份证（存储全量，接口返回脱敏：前6后4） |
| phone | String | |
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
| type | String | IN / OUT / RETURN |
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
