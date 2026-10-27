# H2 → MySQL 8 数据迁移手册（路径 A / 路径 B）

> 作者：储海量（数据建模工程师）
> 适用版本：smart-park 生产交付版；目标库 **MySQL 8.0.16+**，字符集 `utf8mb4 / utf8mb4_0900_ai_ci`，时区 `Asia/Shanghai`
> 相关文件：`smart-park-backend/src/main/resources/db/migration/V1__init.sql`（表结构基线）、`docs/ops/archive.sql`（归档）

---

## 0. 先读这一节

| 项 | 结论 |
|---|---|
| **推荐路径** | **路径 A（空库启动）**。客户首次上线、H2 里只有演示数据时，走 A。 |
| **路径 B 何时用** | 只有当客户现场**已有 H2 存量真实数据**要带过来时才用。 |
| **路径 B 的导入机制** | **CSV → INSERT 语句 → `docker exec -i` 管道**。**不用 `LOAD DATA LOCAL INFILE`**（见 §4.1）。 |
| **路径 B 的原子性** | **每张表一个事务**（表内必须原子，表间不要求）。部分导入比整体失败更糟。 |
| **路径 B 的日期红线** | 会话 `sql_mode` 必须含 `STRICT_TRANS_TABLES` + `NO_ZERO_DATE`，否则脏日期会被静默写成 `0000-00-00`。 |
| **演示数据** | `data.sql` 含虚构人员与虚构身份证号，**禁止导入生产库**（见 §8）。 |
| **含身份证/手机号的存量** | **不要**在迁移脚本里加解密，走 §5 的应用侧方案。 |

---

## 1. 前提检查（两条路径都要做）

```bash
# 1.1 版本下限：>= 8.0.16，否则 CHECK 约束被静默忽略（不报错也不生效）
docker exec -i <mysql容器名> mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B -e "SELECT VERSION();"

# 1.2 库级字符集与排序规则
docker exec -i <mysql容器名> mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B -e \
  "SELECT @@character_set_database, @@collation_database, @@default_storage_engine; "
# 期望：utf8mb4 | utf8mb4_0900_ai_ci | InnoDB

# 1.3 时区：全局与会话都必须是 +08:00
docker exec -i <mysql容器名> mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B -e \
  "SELECT @@global.time_zone, @@session.time_zone, NOW();"
# 期望：+08:00 | +08:00 | 与 date 命令输出一致（误差 < 5 秒）
```

**1.4 账号**：导入用**迁移账号**（仅 DML），不要用 root；备份用**备份账号**；应用用**应用账号**。三者分离，权限最小化见 `docs/reviews/03-data-review.md` §10.6。

**1.5 备份**：无论走哪条路径，动手前先做一次全量备份（首次上线时库是空的，可跳过，但要在台账里写明"空库，无需备份"）。

---

## 2. 路径 A：空库启动（推荐）

适用场景：客户首次上线，H2 里只有演示数据。**不迁移任何数据，只让 Flyway 建表。**

```
1. 运维按 docs/reviews/05-devops-plan.md 完成 MySQL 初始化（建库、建账号、my.cnf）
2. 后端发布应用；application-prod.yml 已配好：
     spring.flyway.enabled: true
     spring.flyway.locations: classpath:db/migration
     spring.flyway.clean-disabled: true      ← 生产禁止 clean
     spring.sql.init.mode: never             ← 关键：schema.sql / data.sql 永不执行
3. 应用启动 → Flyway 自动执行 V1__init.sql（9 张主表 + 2 张归档表）
4. 校验建表结果（见下）
5. 应用首次启动由 `AdminInitializer` 自动写入三角色系统账号（`sys_user`），无需手工插数据
6. 客户在系统里录入基础数据（员工 / 车辆 / 物资 / 权限）
```

> **`sys_user` 是新增表，没有 H2 存量来源**，路径 B 的导出清单里不含它——它由应用初始化，不参与迁移。

**建表后校验**（三条，缺一不可）：

```sql
-- A1 表数量：9 张主表 + 2 张归档表 + flyway_schema_history
SELECT COUNT(*) FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE';     -- 期望 12

-- A2 CHECK 约束数量：20 条（低于该数说明 MySQL < 8.0.16 或 V1 未完整执行）
SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_TYPE = 'CHECK';     -- 期望 20（主表，归档表已 DROP）

-- A2b 约束名核对（QA 的 TC-ENUM 按「3819 + 约束名」双条件匹配，名字必须一字不差）
SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_TYPE = 'CHECK' ORDER BY CONSTRAINT_NAME;
-- 期望 20 个：chk_alarm_level / chk_alarm_status / chk_ar_direction / chk_ar_pass_type /
-- chk_ar_result / chk_employee_status / chk_material_category / chk_material_status /
-- chk_perm_area / chk_perm_status / chk_perm_target_type / chk_perm_time_range /
-- chk_sr_approve_status / chk_sr_type / chk_sys_user_role / chk_sys_user_status /
-- chk_vehicle_owner_type / chk_vehicle_status / chk_visitor_area / chk_visitor_status

-- A3 Flyway 执行记录只有一条且成功
SELECT installed_rank, version, description, success, execution_time
FROM flyway_schema_history ORDER BY installed_rank;                -- 期望 1 行，success=1
```

> 若 A2 返回 0 且 MySQL 版本 < 8.0.16：**停止上线**，先升级 MySQL。CHECK 静默失效意味着枚举脏数据可以长驱直入，比报错更危险。

---

## 3. 路径 B 总览：H2 存量数据迁移

```
[H2 文件库]                 [中转]                        [MySQL]
smart-park.mv.db  --CSVWRITE-->  *.csv  --csv-to-inserts.py-->  *.sql  --docker exec -i--> 目标表
   (应用已停止)              (空字段 = NULL)              (每表一个事务)        (STRICT sql_mode)
```

**转换环节统一复用运维的通用导入脚本 `scripts/csv-to-inserts.py`**（devops 交付），
与「客户 Excel 基础数据导入」是**同一条管道**，本项目不另建第二套。
该脚本已自带三件事：`SET NAMES utf8mb4`、严格 `sql_mode`、`autocommit=0` + 末尾 `COMMIT`。

中间产物（CSV / SQL）都在临时目录，迁移完成并校验通过后删除。

---

## 4. 路径 B 执行步骤

### 4.1 为什么不用 `LOAD DATA LOCAL INFILE`

| 机制 | 结论 |
|---|---|
| `LOAD DATA LOCAL INFILE` | **不可用**。MySQL 8.0 默认 `local_infile=OFF`，执行报 **ERROR 1148 / 3948**。要打开需改全局参数并给账号 `FILE` 权限，等于为了一次性迁移扩大生产库的攻击面。 |
| `LOAD DATA INFILE`（服务端文件） | 不可用。文件必须落在 `secure_file_priv` 指定目录，需要把文件塞进容器并改配置，同样要 DBA 权限。 |
| **CSV → INSERT → 管道** | **采用**。不需要任何服务端参数，用普通 DML 账号即可；慢一点（一次迁移，可接受）；可被事务包住；失败信息落在客户端 stderr，逐行可定位。 |

### 4.2 Step 1：从 H2 导出 CSV

**先停应用**（H2 文件库独占锁，应用运行时读不到）。

```bash
cd /path/to/smart-park-backend
H2JAR=$(find ~/.m2 -name "h2-*.jar" | grep -v sources | head -1)
echo "H2 jar: $H2JAR"

OUT=/tmp/smartpark_export && mkdir -p $OUT

cat > $OUT/export.sql <<'EOF'
-- 列清单里【不带 id】：目标库 id 走自增，原 H2 的 id 不保留（表间用业务键关联，无物理外键）
-- material.valuable 必须转 0/1：H2 的 BOOLEAN 导出成 TRUE/FALSE 字符串，
-- 直接插 TINYINT(1) 在 STRICT 模式下报 ERROR 1366
-- phone 默认【不导出】：目标库该列存密文，明文 CSV 落盘本身就是泄露。见 §5
CALL CSVWRITE('/tmp/smartpark_export/employee.csv',
  'SELECT emp_no, name, dept, status FROM employee ORDER BY id', 'charset=UTF-8');
CALL CSVWRITE('/tmp/smartpark_export/vehicle.csv',
  'SELECT plate_no, owner_type, owner_name, dept, status FROM vehicle ORDER BY id', 'charset=UTF-8');
CALL CSVWRITE('/tmp/smartpark_export/access_permission.csv',
  'SELECT target_type, target_no, target_name, area, start_time, end_time, status FROM access_permission ORDER BY id', 'charset=UTF-8');
-- id_card / phone 默认【不导出】，理由同上。若走 §5 第三档（应用侧 Java Job 回填），
-- 才临时把这两列加回来，且用完立即 shred
CALL CSVWRITE('/tmp/smartpark_export/visitor.csv',
  'SELECT visitor_name, plate_no, host_name, host_dept, reason, area, appoint_time, expire_time, status, check_in_time, check_out_time FROM visitor ORDER BY id', 'charset=UTF-8');
CALL CSVWRITE('/tmp/smartpark_export/material.csv',
  'SELECT code, name, category, spec, dept, location, CASE WHEN valuable THEN 1 ELSE 0 END AS valuable, status FROM material ORDER BY id', 'charset=UTF-8');
CALL CSVWRITE('/tmp/smartpark_export/access_record.csv',
  'SELECT pass_type, target_no, target_name, gate, direction, pass_time, result, reason FROM access_record ORDER BY id', 'charset=UTF-8');
CALL CSVWRITE('/tmp/smartpark_export/stock_record.csv',
  'SELECT material_code, material_name, type, borrower, dept, approve_status, expect_return_time, out_time, return_time, create_time, remark FROM stock_record ORDER BY id', 'charset=UTF-8');
CALL CSVWRITE('/tmp/smartpark_export/alarm.csv',
  'SELECT type, title, content, level, status, create_time, handle_time FROM alarm ORDER BY id', 'charset=UTF-8');
EOF

java -cp "$H2JAR" org.h2.tools.RunScript \
  -url "jdbc:h2:file:./data/smart-park" -user sa -password "" \
  -script $OUT/export.sql -showResults

wc -l $OUT/*.csv    # 行数 = 数据行数 + 1（表头），记下来，校验①要用
```

> **NULL 的表示：空字段。** H2 的 `CSVWRITE` 默认把 NULL 导出成空字段，
> `csv-to-inserts.py` 也把空字段映射成 SQL `NULL`，两边恰好对齐——**不要加 `null=\N`**，
> 加了反而会被当成字面字符串 `'\N'` 写进库里。
> 代价是「真正的空字符串」与 `NULL` 不可区分。本系统应用层非必填项写 `null` 而非 `''`，
> 故可接受；若客户 CSV 里确有需要保留的空串，先清洗再导入。
>
> **`id` 不进列清单**（裁决）：目标库走自增。表与表之间靠业务键（工号 / 车牌 / 物资编码）关联，
> 没有物理外键，不保留原 id 不会破坏任何引用关系。**不要**用 `SET id = NULL` 之类的写法绕过——
> 主键是 `NOT NULL AUTO_INCREMENT`，那写法本身就是非法的。

### 4.3 Step 2：CSV → INSERT（复用运维通用脚本，不要另写转换器）

直接用 **`scripts/csv-to-inserts.py`**（devops 交付），与「客户 Excel 基础数据导入」是**同一条管道**。

```bash
cd /path/to/smart-park

# 列清单必须严格对应 §4.2 导出的 CSV 列顺序，且【不含 id】
python3 scripts/csv-to-inserts.py employee \
  "emp_no,name,dept,status" /tmp/smartpark_export/employee.csv \
  --uppercase emp_no > /tmp/smartpark_export/employee.sql || { echo "生成失败 employee"; exit 1; }

python3 scripts/csv-to-inserts.py vehicle \
  "plate_no,owner_type,owner_name,dept,status" /tmp/smartpark_export/vehicle.csv \
  --uppercase plate_no > /tmp/smartpark_export/vehicle.sql || { echo "生成失败 vehicle"; exit 1; }

python3 scripts/csv-to-inserts.py material \
  "code,name,category,spec,dept,location,valuable,status" /tmp/smartpark_export/material.csv \
  --uppercase code > /tmp/smartpark_export/material.sql || { echo "生成失败 material"; exit 1; }

python3 scripts/csv-to-inserts.py visitor \
  "visitor_name,plate_no,host_name,host_dept,reason,area,appoint_time,expire_time,status,check_in_time,check_out_time" \
  /tmp/smartpark_export/visitor.csv \
  --uppercase plate_no > /tmp/smartpark_export/visitor.sql || { echo "生成失败 visitor"; exit 1; }

python3 scripts/csv-to-inserts.py access_permission \
  "target_type,target_no,target_name,area,start_time,end_time,status" \
  /tmp/smartpark_export/access_permission.csv \
  --uppercase target_no > /tmp/smartpark_export/access_permission.sql || { echo "生成失败 access_permission"; exit 1; }

python3 scripts/csv-to-inserts.py access_record \
  "pass_type,target_no,target_name,gate,direction,pass_time,result,reason" \
  /tmp/smartpark_export/access_record.csv \
  --uppercase target_no > /tmp/smartpark_export/access_record.sql || { echo "生成失败 access_record"; exit 1; }

python3 scripts/csv-to-inserts.py stock_record \
  "material_code,material_name,type,borrower,dept,approve_status,expect_return_time,out_time,return_time,create_time,remark" \
  /tmp/smartpark_export/stock_record.csv \
  --uppercase material_code > /tmp/smartpark_export/stock_record.sql || { echo "生成失败 stock_record"; exit 1; }

python3 scripts/csv-to-inserts.py alarm \
  "type,title,content,level,status,create_time,handle_time" /tmp/smartpark_export/alarm.csv \
  > /tmp/smartpark_export/alarm.sql || { echo "生成失败 alarm"; exit 1; }
```

三条使用要点：

1. **`--uppercase` 必加**：业务键（工号 / 车牌 / 物资编码）要大写归一化。
   MySQL 是 `utf8mb4_0900_ai_ci`（大小写不敏感），H2 是大小写敏感，不归一化的话
   同一个工号 `E001` / `e001` 在两边行为不一致。
2. **必须先重定向到文件、检查退出码，再导入**。
   不要 `python3 ... | docker exec -i ... mysql` 一条管道到底——转换中途失败时前面已经灌进去一部分了。
   该脚本的列数不符（`exit 3`）与枚举非法（`exit 4`）检查都是写 stderr + 非 0 退出。
3. **脚本已自带** `SET NAMES utf8mb4`、严格 `sql_mode`、`autocommit=0` + 末尾 `COMMIT`，不要在外面再套一层。
   它没设 `time_zone`——DATETIME 字面量不受会话时区影响，导入场景不需要。

### 4.4 Step 3：导入

```bash
# 导入必须用【应用账号】的凭据文件 app.cnf（仅 DML：SELECT/INSERT/UPDATE/DELETE）。
#   - bak.cnf（备份账号，仅 SELECT）导入会报 ERROR 1142
#   - 不要用 mig.cnf（迁移账号带 DDL/DROP）—— 导入是"把不可信 CSV 变成可执行 SQL"的
#     唯一环节，让它持有 DDL 会把最坏情况从"插脏数据（可回滚）"放大成"删表"
# 顺序：先主数据，后流水。乱序不会报错（无外键），但会让校验④的孤儿检查失去意义。
for t in employee vehicle material visitor access_permission access_record stock_record alarm; do
  echo "=== importing $t ==="
  docker exec -i smart-park-mysql sh -c \
    'exec mysql --defaults-extra-file=/run/secrets/app.cnf --default-character-set=utf8mb4 smart_park' \
    < /tmp/smartpark_export/$t.sql \
    2> /tmp/smartpark_export/$t.err        # ← stderr 必须单独留存，校验②要用
  echo "exit=$?  err_bytes=$(wc -c < /tmp/smartpark_export/$t.err)"
  [ -s /tmp/smartpark_export/$t.err ] && { echo "导入失败：$t，事务已回滚"; exit 1; }
done
```

> 实际值（已与运维对齐，2026 年交付版）：容器名 `smart-park-mysql`、库名 `smart_park`、
> 导入凭证 `/run/secrets/app.cnf`（由 `scripts/init-secrets.sh` 生成，权限 600）。
> 账号 host 口径：`docker exec` 内的 `mysql` 走 **socket**，连接 host 是 `localhost`，
> 因此 app / mig 账号均同时授 `@'172.28.%'` 与 `@'localhost'` 两段——
> 若报 **ERROR 1045** 是 host 段不匹配（不是密码错），若报 **1142** 才是权限不足。
> 不要把密码直接写进命令行（会进 `ps` 输出和 shell history）。

**导入顺序**：`employee / vehicle / material`（主数据）→ `visitor / access_permission`（依赖主数据的业务单）→ `access_record / stock_record / alarm`（流水与告警）。

### 4.5 原子性说明（QA 用例 TC-MIG-08 的实现依据）

- **表内原子**：`csv-to-inserts.py` 生成的每个 `.sql` 是 `SET autocommit=0; …INSERT…; COMMIT;`，
  即**每张表一个事务**。任意一行失败 → 客户端中止 → 未 COMMIT → 回滚 → 该表 0 行。
  **不允许"这张表失败一半就接着跑下一张表"**。
- **表间不原子**：表之间没有物理外键，某张表失败不会破坏其他表的数据一致性，只会在校验④暴露出来。这是刻意设计——千万行级大表不适合用跨表大事务。
- **大表替代方案**：`access_record` 若超过 100 万行，单事务 undo 膨胀明显，可改用「影子表 `tmp_ar` 分批提交导入 → 校验 → `RENAME TABLE access_record TO access_record_old, tmp_ar TO access_record`」。影子表用 `CREATE TABLE tmp_ar LIKE access_record` 建。

---

## 5. 个人敏感信息（身份证 / 手机号）的迁移口径 —— 重要

目标库的 `visitor.id_card`、`visitor.phone`、`employee.phone` 三列存的是 **AES-256-GCM 密文**，明文永不落库。
因此**不能**直接把 H2 导出的明文 CSV 插进去——那只是把明文搬了个家。

**§4.2 / §4.3 的示例默认就不导出这两列**——明文身份证一旦落进 `/tmp` 的 CSV 就是一次泄露，
默认不导出，只有走下面第三档时才临时加回来，用完立即 `shred`。

| 情况 | 做法 |
|---|---|
| 存量是**演示数据**（`data.sql` 那批虚构身份证） | **不迁移**，见 §8。客户在系统里重新录入。（**默认路径**：已确认走路径 A 空库上线，客户从零录入，本档即现状） |
| 存量是**真实数据，但身份证/手机号可以不要** | 迁移时三列**全部留 NULL**，其余字段照迁。即 §4.2 / §4.3 的默认写法——导出 SELECT 里不带这两列、`csv-to-inserts.py` 的列清单里也不带。 |
| 存量是**真实数据，必须保留身份证/手机号** | **走后端应用侧一次性 Job**（条件交付：等客户确认有真实存量才启用）。该 Job **必须复用后端同一套 `CryptoService`**——同一密钥、同一算法，**绝不允许另写一份加解密实现**（两套实现迟早漂移，届时库里一半密文解不开是最难救的事故）。**禁止在 SQL / shell / python 脚本里做加解密**——密钥会落进脚本文件、shell history 与进程列表。 |

> 判断依据只有一条：**密钥不能出现在数据库之外的任何文件里**。这是为什么第三档必须走 Java，
> 而不是给 python 脚本传个 key 参数。这条红线已同步进 `docs/ops/runbook.md` 的变更红线。

---

## 6. 五项校验（逐条执行，任何一条不过不许上线）

### ① 行数一致

```sql
-- 目标端（与 §4.2 记下的 CSV 数据行数比对，逐表相等）
SELECT 'employee' t, COUNT(*) FROM employee UNION ALL
SELECT 'vehicle', COUNT(*) FROM vehicle UNION ALL
SELECT 'access_permission', COUNT(*) FROM access_permission UNION ALL
SELECT 'visitor', COUNT(*) FROM visitor UNION ALL
SELECT 'material', COUNT(*) FROM material UNION ALL
SELECT 'access_record', COUNT(*) FROM access_record UNION ALL
SELECT 'stock_record', COUNT(*) FROM stock_record UNION ALL
SELECT 'alarm', COUNT(*) FROM alarm;
```

### ② 脏日期为零 —— **注意：这条的断言内容已变更**

原方案断言 `pass_time IS NULL` 的行数为 0。改成 CSV→INSERT 直插后，**该断言恒为 0，是空断言，已作废**（`pass_time` 是 `NOT NULL` 列，且 STRICT 模式下任何非法值都会直接报错，根本插不进来）。
替代为下面三条：

```bash
# ②-a 导入 stderr 必须为空（0 字节）。有任何 ERROR/WARNING 都要逐条看完再决定。
for t in employee vehicle access_permission visitor material access_record stock_record alarm; do
  s=$(wc -c < /tmp/smartpark_export/$t.err)
  [ "$s" -eq 0 ] && echo "OK   $t" || { echo "FAIL $t ($s bytes)"; cat /tmp/smartpark_export/$t.err; }
done
```

```bash
# ②-b 源端正则预检（在导入前跑，比导入后再查更有意义）
python3 - <<'EOF'
import csv, re, glob
PAT = re.compile(r'^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$')
TIMECOLS = {'start_time','end_time','pass_time','appoint_time','expire_time',
            'check_in_time','check_out_time','expect_return_time','out_time',
            'return_time','create_time','handle_time'}
for f in sorted(glob.glob('/tmp/smartpark_export/*.csv')):
    bad = 0
    with open(f, newline='', encoding='utf-8') as fh:
        r = csv.DictReader(fh)
        for row in r:
            for c in TIMECOLS & set(row):
                v = row[c]
                if v and v != '\\N' and not PAT.match(v):
                    bad += 1; print(f'{f}: 脏日期 {c}={v!r}')
    print(f'{f}: 脏日期 {bad} 处')   # 期望 0
EOF
```

```sql
-- ②-c 目标端兜底：任何时间列都不允许是零值（正常路径下必然为 0，留着防"绕过 STRICT 重跑"）
SELECT COUNT(*) FROM access_record WHERE pass_time = '0000-00-00 00:00:00';   -- 0
SELECT COUNT(*) FROM visitor      WHERE expire_time = '0000-00-00 00:00:00'; -- 0
SELECT COUNT(*) FROM stock_record WHERE expect_return_time = '0000-00-00 00:00:00'
                                    OR create_time = '0000-00-00 00:00:00';  -- 0
```

### ③ 枚举合法性

导入时由数据库 CHECK 兜底：任何非法枚举直接报 **ERROR 3819**，事务中止。
导入通过后跑一次分布统计，与源端比对：

```sql
SELECT status, COUNT(*) FROM employee GROUP BY status;      -- 只可能：在职 / 离职
SELECT type, COUNT(*)   FROM stock_record GROUP BY type;    -- 只可能：IN / OUT
SELECT status, COUNT(*) FROM visitor GROUP BY status;       -- 4 个值
SELECT area, COUNT(*)   FROM access_permission GROUP BY area;  -- 4 个值
```

> QA 断言请写 **错误码 3819**，不要只写"插入失败"——1364（缺默认值）、1406（超长）、1062（唯一键冲突）也会让插入失败，只断言"失败"会产生假通过。

### ④ 孤儿引用检查（无外键，靠这条兜）

```sql
-- 出入库流水指向的物资必须存在
SELECT COUNT(*) AS 孤儿出入库单 FROM stock_record sr
LEFT JOIN material m ON m.code = sr.material_code
WHERE m.code IS NULL;                                          -- 期望 0

-- 通行权限指向的员工 / 车辆必须存在
SELECT COUNT(*) AS 孤儿人员权限 FROM access_permission p
WHERE p.target_type = 'PERSON'
  AND NOT EXISTS (SELECT 1 FROM employee e WHERE e.emp_no = p.target_no);   -- 期望 0

SELECT COUNT(*) AS 孤儿车辆权限 FROM access_permission p
WHERE p.target_type = 'VEHICLE'
  AND NOT EXISTS (SELECT 1 FROM vehicle v WHERE v.plate_no = p.target_no);  -- 期望 0
```

> 已知：`data.sql` 里的 `alarm` 有一条引用了不存在的物资 `M-SPARE-009`，这类孤儿在演示数据里是存在的。
> 迁移真实数据时如果出现孤儿，**先让客户确认是"数据缺失"还是"历史脏数据"**，不要自行补记录也不要自行删记录。

### ⑤ 业务汇总与抽样比对

```sql
-- ⑤-a 关键业务口径（源端跑同样的 SQL，数字必须一致）
SELECT status, COUNT(*) FROM visitor GROUP BY status;
SELECT MIN(pass_time), MAX(pass_time), COUNT(*) FROM access_record;
SELECT approve_status, COUNT(*) FROM stock_record GROUP BY approve_status;

-- ⑤-b 随机抽 20 条逐字段比对（源端 CSV 与目标端查询结果人工核对）
SELECT * FROM visitor ORDER BY id LIMIT 20;
SELECT * FROM stock_record ORDER BY id DESC LIMIT 20;
```

---

## 7. 回滚

目标是**新库**时，回滚成本很低——直接清表重来：

```sql
-- 只在"确认目标库没有其他来源的数据"时执行
SET FOREIGN_KEY_CHECKS = 0;   -- 本项目无外键，留作防护
TRUNCATE TABLE alarm;
TRUNCATE TABLE stock_record;
TRUNCATE TABLE access_record;
TRUNCATE TABLE material;
TRUNCATE TABLE visitor;
TRUNCATE TABLE access_permission;
TRUNCATE TABLE vehicle;
TRUNCATE TABLE employee;
SET FOREIGN_KEY_CHECKS = 1;
```

若是**已上线库的追加迁移**，回滚必须走 PITR（全量备份 + binlog 恢复到导入前的时间点），不要靠 `DELETE` 手工挑数据。

---

## 8. 演示数据处置红线

`smart-park-backend/src/main/resources/data.sql` 里是演示数据（虚构姓名、虚构身份证号、日期围绕演示当天）。

**禁止**把它导入生产库，理由三条：
1. 里面的身份证号是虚构的，导入后无法区分哪些是真数据；
2. 它是明文身份证，导入即违反"明文永不落库"；
3. `spring.sql.init.mode: never` 已经保证应用启动时不会执行它——不要为了"图方便看效果"临时改回去，**那正是 B01 事故（重启清库）的根因**。

UAT 环境如需要演示数据，必须由客户书面确认，且**身份证/手机号字段置空后**再导入。

---

## 9. 常见错误对照表

| 错误 | 含义 | 处理 |
|---|---|---|
| `ERROR 1148 (42000) / 3948 (HY000)` | `local_infile` 关闭 | 说明有人改回了 `LOAD DATA LOCAL INFILE`，按 §4.1 换回 CSV→INSERT |
| `ERROR 1292 (22007) Incorrect datetime value` | 脏日期 | 不要放宽 `sql_mode` 绕过！去源端修数据，见校验②-b |
| `ERROR 3819 (HY000) Check constraint 'chk_xxx' is violated` | 脏枚举 | 源端修正；若确实是新业务值，走 DDL 变更改 CHECK，不要在导入时关约束 |
| `ERROR 1366 Incorrect integer value: 'TRUE'` | H2 BOOLEAN 未转换 | §4.2 的导出 SQL 里已用 `CASE WHEN valuable THEN 1 ELSE 0 END` 转掉；出现该错说明有人改回了 `SELECT *` |
| `ERROR 1142 (42000) ... command denied` | 账号权限不足 | 多半是用了备份账号（`bak.cnf`）导入。换成应用账号的 `app.cnf` |
| `ERROR 1406 (22001) Data too long` | 字段超长 | 多为 `reason`/`content`/`remark`，确认是否截断安全，否则改列宽走 Flyway V2 |
| `ERROR 1062 (23000) Duplicate entry` | 重复导入或源端本身有重复 | 先 `TRUNCATE` 再重导；若源端重复，让客户确认保留哪条 |
| `ERROR 1146 (42S02) Table doesn't exist` | Flyway 没跑成功 | 查 `SELECT * FROM flyway_schema_history`，V1 失败要先修 V1 再重来 |
| `ERROR 1045 (28000) Access denied` | 账号 host 段不匹配（不是权限不足） | `docker exec` 走 socket，连接 host 是 `localhost`：确认 app/mig 账号有 `@'localhost'` 授权（见 `mysql/init/01-accounts.sh`） |
| `ERROR 3911 (constraint does not exist)` | V1 里归档表的 `DROP CHECK` 找不到约束 | 该版本 `CREATE TABLE ... LIKE` 未继承 CHECK，删掉那两行 ALTER 即可 |
| H2 报 `Database may be already in use` | 应用没停 | 停应用后再导出 |

---

## 10. 迁移台账（人工填写）

```
执行时间 / 执行人 / 路径（A 或 B）/
备份文件位置（或"空库，无需备份"）/
逐表：CSV 行数 → 目标行数 → stderr 字节数 → 校验①~⑤结果 /
孤儿引用条数与客户确认结论 /
PII 处置方式（不迁移 / 置空 / 应用侧 Job）/
异常与处理 / 回滚方式（如需）
```
