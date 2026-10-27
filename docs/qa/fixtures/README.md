# 测试参数化数据（fixtures）

三份 CSV 是 `docs/qa/testcases.md` 的**参数化数据源**，直接喂进 JUnit5 参数化测试（`@ParameterizedTest` + `@MethodSource`，见 `smart-park-backend/src/test/java/com/smartpark/support/QaFixtures.java`），**文档与测试共用一个数据源，省一轮返工**。

| 文件 | 内容 | 对应用例 | 行数 | 展开断言实例 |
|---|---|---|---|---|
| `auth-matrix.csv` | 42 个接口 ×（管理员 / 门岗 / 仓管 / 未登录） | TC-AUTH-01 / 03 / 04 / 05 | 42 | **168**（42×4） |
| `enum-constraints.csv` | 20 条 CHECK 约束的正 / 负向断言 | TC-ENUM-01 / 04 / 05 | 20 | **40**（20×2） |
| `enum-api-validation.csv` | 12 个枚举字段 × 5 类非法值，另 **4 条口径登记项** | TC-ENUM-02 | 16 | **60**（12×5；登记项不参与展开） |
| **合计** | | | **78** | **268** |

> 规模口径：三份 CSV 共 **78 行 → 268 个断言实例**；对应 `docs/qa/testcases.md` 的 **115 条用例条目**（对外汇报 107 条为另一口径，见文档 §0 与 §13）。

> ⚠️ **BOM 红线**：三份 CSV 必须保存为**无 BOM 的 UTF-8**。带 BOM 时首列名会变成 `\ufeff模块`，列名匹配静默失败，报错信息看起来像"文件不存在"，极难定位。改动后自检：`head -c 3 文件 | xxd -p` 首字节应为内容字节，**不得是 `efbbbf`**。

## 字段口径

### auth-matrix.csv
| 列 | 说明 |
|---|---|
| 管理员期望 / 门岗期望 / 仓管期望 / 未登录期望 | 允许 = `0`（code=0 / HTTP 200）；拒绝 = `40301`（HTTP 403）/ `40101`（HTTP 401） |
| 入merge门禁 | `Y` 的行进 CI merge 阻断（Testcontainers + MockMvc，全量约 1~2 分钟，需 CI 有 docker） |

**本文件已与 backend 逐接口复核定稿（42 接口，非 44）**，三处演进已固化，回改前先看这里：

| 接口 | 终稿期望 | 依据 |
|---|---|---|
| `POST /api/auth/refresh` | 四身份全 `0`（免鉴权） | backend 自查发现并修复：原被 `/auth/** → authenticated` 覆盖 → refresh 自己也 401 → 双 token 形同虚设 |
| `GET /api/materials`、`GET /api/materials/{id}` | 门岗 **40301** | team-lead 裁决：门岗在门口、查物资台账无业务场景 + 物资编码与价值信息不该对门岗开放 |
| `POST /api/permissions`、`PUT /api/permissions/{id}` | 门岗 **0（放行）** | team-lead 裁决：登记 → 授权 → 通行，缺授权则门岗业务闭环不了；「现场授权 vs 集中管理」仍挂客户待确认项 |

其余保持：`POST /api/stock-records/{id}/approve` 严格 ADMIN-only；告警 / 看板三角色放行；未登录除 login / refresh 外全 `40101`。

### enum-constraints.csv
| 列 | 说明 |
|---|---|
| 合法值 | `|` 分隔；正向用例逐个插入，均须成功 |
| 非法值样本 | `|` 分隔；负向用例逐个插入，须报 **3819** 且消息含**本行约束名** |
| 可空 / NULL 期望 | `是` 的字段需单独测 NULL 插入**成功**（TC-ENUM-04） |
| 正向期望 | 合法值插入成功（无错误） |

> `chk_sr_type` 的非法值含 `RETURN`：**若它能写入，说明裁决 15 未落地**，直接判 Blocker（与 TC-STK-25 联动）。

**约束名已与 `V1__init.sql` 实测逐字对齐（终稿 20 条，顺序按字母）**：
`chk_alarm_level` / `chk_alarm_status` / `chk_ar_direction` / `chk_ar_pass_type` / `chk_ar_result` / `chk_employee_status` / `chk_material_category` / `chk_material_status` / `chk_perm_area` / `chk_perm_status` / `chk_perm_target_type` / `chk_perm_time_range` / `chk_sr_approve_status` / `chk_sr_type` / `chk_sys_user_role` / `chk_sys_user_status` / `chk_vehicle_owner_type` / `chk_vehicle_status` / `chk_visitor_area` / `chk_visitor_status`。
环境验收可用 `information_schema.TABLE_CONSTRAINTS` 核对应得 **20 行**。

**`chk_perm_time_range` 已建并固化**（不再"暂缓"），语义 **`end_time >= start_time`**（team-lead 终裁）：
- 真倒挂（`end < start`）→ **3819 + 约束名**；
- 零时长（`end = start`）→ **放行**，⛔ **不得**断言"零时长应报 3819"。

**刻意无 CHECK 的两列不在本文件**：`access_record.gate`、`alarm.type` 由 **TC-ENUM-05 正向用例**覆盖（任意值可存），禁止写成"应报 3819"。

**可空 4 列**：`vehicle.owner_type`、`material.category`、`access_record.direction`、`visitor.area` —— NULL 必须能插入（TC-ENUM-04），MySQL CHECK 对 NULL 判定为「未知 → 通过」。

**大小写陷阱**：`utf8mb4_0900_ai_ci` 大小写不敏感，`PERSON`/`person` 在 DB 层是同一个值，**负向样本一律用 `PERSONS`/`INOUT`/`CAR` 这类不同词**，用大小写变体会得到"应该报错却放行"的假通过。

### enum-api-validation.csv
| 列 | 说明 |
|---|---|
| 非法值_* | 五类样本：中文错字 / 繁体 / 小写英文 / 尾空格 / 空串 |
| 期望 code / HTTP | 默认 `40001` / `400`；**忽略语义行**为 `0` / `200`（见下） |
| 入merge门禁 | `N` 的两类：当前预期 FAIL 待修的、以及仅作口径登记的 |
| 优先级 | P0 的 6 个枚举字段是 API 主要防线，任一 FAIL = DTO 校验漏项；P1 为回归集；P2 为登记项 |

> 尾空格样本（如 `'PERSON '`）在 API 层**必须被拒**：DTO 正则默认大小写敏感且精确匹配。若放行，说明正则被加了 `(?i)`，需确认有意还是误改（TC-ENUM-03b 双向锁定）。

**4 条口径登记项（不是缺陷，也不是新增断言量）**

| 行 | 现状 | 写法 |
|---|---|---|
| `material.status` | DTO 已移除 status，服务端固定为「在库」 | **忽略语义**：传非法值期望 `0 / 200`，落库 status 仍合法。⛔ 不得写"非法 status 期望 40001" |
| `visitor.area(空值)` | 只有 `@Pattern` 无 `@NotBlank`，`@Pattern` 对 `null` 视为通过 → 空值裸奔 | 期望 **40001**（目标态），**当前预期 FAIL**，backend 补 `@NotBlank` 后改 `入merge门禁=Y`。⛔ 不得为转绿而放宽断言 |
| `material.code(PUT)` / `employee.empNo(PUT)` / `vehicle.plateNo(PUT)` | 已从入参移除 | 期望 `0 / 200`。⛔ 不得写 40001 断言 |

## 使用方式

```java
@ParameterizedTest
@MethodSource("com.smartpark.support.QaFixtures#authCases")
void authMatrix(AuthCase c) { ... }
```

数据源由 `QaFixtures` 直接读取本目录（默认 `../../docs/qa/fixtures`，可用 `-Dqa.fixtures.dir=<目录>` 覆盖），**不复制到 `src/test/resources`**——复制会产生第二份事实来源，改一处忘一处。

## 一致性要求

- 契约变更（`docs/api-contract.md` 增删接口）→ **同步更新 auth-matrix.csv**，否则矩阵会静默漏测新接口。
- DDL 变更（增删 CHECK）→ **同步更新 enum-constraints.csv**。
- DTO 变更（增删字段 / 改校验）→ **同步更新 enum-api-validation.csv**（尤其"字段从入参移除"必须改成忽略语义，否则会被误报成缺陷）。
- 三份 CSV 是测试与契约 / DDL / DTO 之间的对账表，**纳入 PR 评审检查项**。
