# 测试参数化数据（fixtures）

三份 CSV 是 `docs/qa/testcases.md` 的**参数化数据源**：等 backend M2 骨架就位，直接喂进 JUnit5 参数化测试（`@ParameterizedTest` + `@CsvSource`/`@MethodSource`）即可，**省一轮返工**。

| 文件 | 内容 | 对应用例 | 行数 | 展开断言实例 |
|---|---|---|---|---|
| `auth-matrix.csv` | 42 个接口 × 3 角色 + 未登录 | TC-AUTH-01/03/04/05 | 42 | **168**（42×4） |
| `enum-constraints.csv` | 20 条 CHECK 约束的正/负向断言 | TC-ENUM-01 / 04 / 05 | 20 | **40**（20×2） |
| `enum-api-validation.csv` | 12 个字段 × 5 类非法值的 API 层断言 | TC-ENUM-02 | 12 | **60**（12×5） |
| **合计** | | | **72** | **264** |

> 规模口径：三份 CSV 共 **72 行 → 264 个断言实例**；对应 `docs/qa/testcases.md` 的 **107 条用例条目**。

## 字段口径

### auth-matrix.csv
| 列 | 说明 |
|---|---|
| 管理员期望 / 门岗期望 / 仓管期望 / 未登录期望 | 允许 = `0`（code=0, HTTP 200）；拒绝 = `40301`（HTTP 403）/ `40101`（HTTP 401） |
| 入merge门禁 | `Y` 的行进 CI merge 阻断（MockMvc + 单容器，全量约 1~2 分钟） |

> ⚠️ **角色→接口权限映射为按业务推导的建议值，需 backend M2 权限配置确认后定稿**。若 M2 采用不同映射（如门岗可维护员工），请由 backend-engineer 直接改本文件对应行，测试随之更新，无需改用例文档。

### enum-constraints.csv
| 列 | 说明 |
|---|---|
| 合法值 | `|` 分隔；正向用例逐个插入，均须成功 |
| 非法值样本 | `|` 分隔；负向用例逐个插入，须报 **3819** 且消息含**本行约束名** |
| 可空 / NULL 期望 | `是` 的字段需单独测 NULL 插入**成功**（TC-ENUM-04） |
| 正向期望 | 合法值插入成功（无错误） |

> `chk_sr_type` 的非法值含 `RETURN`：**若它能写入，说明裁决 15 未落地**，直接判 Blocker（与 TC-STK-25 联动）。

**约束名以完整表名为准**（与 `V1__init.sql` 对齐）：`chk_employee_status` / `chk_vehicle_status` / `chk_vehicle_owner_type` / `chk_material_status` / `chk_material_category`（若 data-engineer 侧为缩写命名 `chk_emp_*` / `chk_veh_*` / `chk_mat_*`，以 V1 最终定稿名为准，**双方必须一致，否则 3819+约束名断言会漏检**）。

**状态列约定**：期望列值为 `暂缓` 的行（`chk_perm_time_range`）= V1 尚未建该约束，**暂不断言、不删行**，等 team-lead 回报后固化（最终 17 或 18 条）。

**刻意无 CHECK 的两列不在本文件**：`access_record.gate`、`alarm.type` 由 **TC-ENUM-05 正向用例**覆盖（任意值可存），禁止写成"应报 3819"。

**可空 4 列**：`vehicle.owner_type`、`material.category`、`access_record.direction`、`visitor.area` —— NULL 必须能插入（TC-ENUM-04），MySQL CHECK 对 NULL 判定为「未知 → 通过」。

### enum-api-validation.csv
| 列 | 说明 |
|---|---|
| 非法值_* | 五类样本：中文错字 / 繁体 / 小写英文 / 尾空格 / 空串 |
| 期望 code / HTTP | API 层一律 `40001` / `400` |
| 优先级 | P0 的 7 个字段是 API 唯一防线，任一 FAIL = DTO 校验漏项；P1 为回归集 |

> 尾空格样本（如 `'PERSON '`）在 API 层**必须被拒**：DTO 正则默认大小写敏感且精确匹配。若放行，说明正则被加了 `(?i)`，需确认有意还是误改（TC-ENUM-03b 双向锁定）。

## 使用方式（M2 就位后）

```java
@ParameterizedTest
@CsvFileSource(resources = "/qa/auth-matrix.csv", numLinesToSkip = 1)
void authMatrix(String module, String caseId, String method, String path,
                String adminExpect, String gateExpect, String stockExpect,
                String anonExpect, boolean inMergeGate, String remark) { ... }
```
CSV 建议随测试资源置于 `src/test/resources/qa/`（可由 backend-engineer 复制，或用 `@CsvSource` 内联关键子集）。

## 一致性要求

- 契约变更（`docs/api-contract.md` 增删接口）→ **同步更新 auth-matrix.csv**，否则矩阵会静默漏测新接口。
- DDL 变更（增删 CHECK）→ **同步更新 enum-constraints.csv**。
- 两份 CSV 是测试与契约/DDL 之间的对账表，**建议纳入 PR 评审检查项**。
