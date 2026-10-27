# smart-park 运维手册（客户现场版）

> 适用版本：compose 冻结版 v1.4.2 ｜ 部署形态：单机 docker compose（MySQL + 后端 + 前端/Nginx）
> 部署根目录约定：`/opt/smart-park/`（下文所有命令均在此目录执行）

---

## 1. 架构与端口

| 组件 | 容器名 | 对外端口 | 说明 |
|------|--------|---------|------|
| MySQL 8.0.39 | smart-park-mysql | **无**（不暴露） | 数据卷 `smart-park-mysql-data` 持久化 |
| Spring Boot 后端 | smart-park-backend | **无**（仅内网） | context-path `/api`，健康检查 `/api/actuator/health` |
| Vue3 + Nginx | smart-park-frontend | `${NGINX_PORT:-80}` | **唯一对外入口**，/api 同域反代 |

**全栈对外只有 Nginx 一个入口**：3306 / 8080 均不发布到宿主机，数据库与后端只能经 `docker exec` 或内网访问。

---

## 2. 首次部署（一键）

> ⚠ **前端地址是构建期变量，不是运行时变量**：Vite 的 `VITE_API_BASE` / `VITE_BASE` 会在
> `npm run build` 时内联进产物 JS。客户现场改了 `.env` 后执行 `docker compose up -d`
> **前端仍指向旧地址**——必须 `docker compose build frontend`（或重新导入镜像）才生效。
> 同域根路径部署使用默认值（`/api`、`/`）即可，无需任何前端环境变量；只有子路径部署
> （如 `http://host/park/`）才需要显式设置 `VITE_BASE=/park/` 并重新构建。

```bash
# ① 准备配置
cp .env.example .env && chmod 600 .env && vi .env     # 填写所有空值（密码用 openssl rand 生成）
./scripts/init-secrets.sh                             # 生成 mysql/bak.cnf（600）

# ② 确认客户 LAN 未占用容器网段（默认 172.28.0.0/24）
ip -4 route | grep -E '^172\.28\.'                    # 应为空；若冲突改 .env 的 SP_NET_SUBNET

# ③ 启动（有外网 / 内网离线先 docker load -i images/smart-park-images.tar.gz）
docker compose --env-file .env up -d

# ④ 观察（frontend 最后转 healthy，说明 Flyway 迁移已完成）
docker compose ps
docker compose logs -f backend

# ⑤ 冒烟（★ 强烈建议：一条命令跑完全部断言，失败即 exit 1 并打印失败清单）
./scripts/smoke.sh
#    覆盖：compose config / 三容器 healthy / Flyway 1 行 success=1 / 业务表 11 + CHECK 20 /
#         actuator UP / 前端 200 + SPA fallback / 3306 与 8080 未对宿主机暴露
#    只跑断言不重启栈：SMOKE_UP=0 ./scripts/smoke.sh
```

首次启动顺序由 compose 保证：`mysql healthy → backend 启动并执行 Flyway 迁移 → backend healthy → frontend 启动放流量`。

---

## 3. 日常巡检（每日）

```bash
docker compose ps                                   # 三容器均 healthy
curl -s http://127.0.0.1/api/actuator/health        # 期望 {"status":"UP"}
tail -100 logs/backend/smart-park-error.log         # ERROR 日志
df -h /                                             # 磁盘（≥80% 需处理）
docker stats --no-stream                            # 内存/CPU
ls -lt backup/ | head -3                            # 备份是否每日更新
```

自动化巡检：`scripts/inspect.sh` 通过 crontab 每分钟执行，异常写入 `logs/alerts.log`（P0/P1/P2）。

---

## 4. 备份与恢复

- **策略**：每日 02:30 全量（`scripts/backup.sh`），本机保留 7 份 + 异机/离线 1 份（`.env` 的 `RSYNC_TARGET`）
- **失败告警**：巡检脚本断言最新备份 `mtime < 25h`，超龄即 P1（防备份静默失败）
- **窗口约束**：`--single-transaction` 与 Flyway DDL 不能并发，**发布窗口避开 02:00±1h；冲突时先发布后备份**

```bash
# 手工触发一次备份
./scripts/backup.sh

# 恢复（演练流程，每季度一次）
# ⚠ 必须用 mig.cnf：全量恢复要建表 + 写入，bak.cnf（只读）会在第一条 CREATE TABLE 报 1142，
#    app.cnf（无 DDL）同样不够。恢复是低频高危操作，用带 DDL 的迁移账号是唯一可行动作。
docker compose stop backend
gunzip < backup/full_YYYYMMDD_HHMM.sql.gz | \
  docker exec -i smart-park-mysql sh -c 'exec mysql --defaults-extra-file=/run/secrets/mig.cnf smart_park'
docker compose start backend

# PITR（需 binlog，保留 7 天）：先恢复全量，再重放 binlog 到误操作前一刻
# mysqlbinlog --start-datetime="..." --stop-datetime="..." /var/lib/mysql/mysql-bin.0000XX | mysql ...
```

> **没演练过的备份等同于没有备份**。每季度恢复演练一次并记录实测 RTO。

---

## 5. 发布与回滚

**发布**：改 `.env` 的 `APP_VERSION` 为新版本 → `docker compose up -d backend frontend`。
**回滚**：改回上一个版本 → 同样一条命令，窗口 < 30 秒，MySQL 不动。

```bash
# 回滚示例
sed -i 's/^APP_VERSION=.*/APP_VERSION=1.0.0/' .env
docker compose --env-file .env up -d backend frontend
curl -s http://127.0.0.1/api/actuator/health && 抽查核心页面
```

**铁律**：
- 旧版本镜像在宿主机**永不主动删除**（磁盘告警时才清理 N-2 之前版本）
- 数据库不随代码回滚——Flyway 迁移必须**向后兼容一个版本**（加列可空/带默认值，禁止直接删列）
- 每次上线前在 staging 跑一次"发布 → 制造故障 → 回滚"全流程演练

---

## 6. 排障速查（含口诀）

**口诀：backend 容器长期 `starting` / `unhealthy` → 一查 curl、二查 actuator、三查应用。**
（三个失效点共用同一条症状：frontend 因 `depends_on: service_healthy` 永不启动，表现为"网站打不开"，极易误判为 nginx/网络问题）

```bash
docker inspect --format '{{.State.Health.Status}}' smart-park-backend   # 期望 healthy
docker run --rm smart-park-backend:<版本> which curl                    # curl 缺失即 healthcheck 永久失败
docker compose logs --tail 200 backend                                  # Flyway / 连接池报错
docker compose logs --tail 100 mysql
```

| 现象 | 排查 |
|------|------|
| 刷新页面 404 | nginx.conf 的 `try_files $uri $uri/ /index.html;` 是否被覆盖 |
| 接口 502 | backend 是否 healthy；`docker exec smart-park-frontend nginx -t` |
| 数据库连不上 | `docker exec smart-park-mysql mysqladmin ping -u root -p`；看 mysql 日志 |
| 时间差 8 小时 | `docker exec smart-park-mysql mysql -uroot -p -e "SELECT NOW(), UTC_TIMESTAMP(), @@global.time_zone;"` 四值比对 |
| 用户报障定位 | 让用户提供"追踪号" → `grep '"traceId":"<追踪号>"' logs/backend/smart-park.json` |
| 磁盘涨 | 先清 30 天前日志与旧备份，**绝不动 `mysql-data` 卷** |

---

## 7. 数据导入（通用）

基础数据导入（客户 Excel）与 H2 存量迁移**共用同一条管道**，禁止再使用 `LOAD DATA LOCAL INFILE`（MySQL 8 的 `local_infile` 默认 OFF，会报 ERROR 1148）。

```bash
# ⓪ 前置检查：sql_mode 必须含 STRICT_TRANS_TABLES（缺失即中止导入）
docker exec smart-park-mysql sh -c \
  'exec mysql --defaults-extra-file=/run/secrets/bak.cnf -N -B -e "SELECT @@sql_mode;"' | grep -q STRICT_TRANS_TABLES \
  || { echo "❌ sql_mode 未启用严格模式，禁止导入"; exit 1; }

# ① Excel 导出默认 GBK，必须转 UTF-8（乱码在导入时不报错、事后才发现）
iconv -f GBK -t UTF-8 employee_gbk.csv -o employee_utf8.csv

# ② CSV → INSERT（四查：列名 / 编码 / 业务键 / 枚举，失败报行号并整体中止）
#    CSV 首行必须是表头，且列名与目标列【按名称匹配】；列序不一致默认中止，
#    确认无误可加 --allow-col-reorder 按名称映射（不推荐）
python3 scripts/csv-to-inserts.py employee "emp_no,name,dept,phone,status" \
  /import/employee_utf8.csv --keys emp_no --enum "status=在职,离职,休假" > /import/employee.sql \
  || { echo "❌ 预校验未通过，未生成 COMMIT，导入中止"; exit 1; }

# ③ 导入（docker exec -i，不加 -t 防 CR 污染）
#    ⚠ 必须用 app.cnf（应用账号：仅 DML）；bak.cnf 是备份账号（仅 SELECT），拿它导入会报 ERROR 1142；
#      也不要用 mig.cnf（迁移账号带 DDL，不应暴露给高频导入操作）
docker exec -i smart-park-mysql sh -c \
  'exec mysql --defaults-extra-file=/run/secrets/app.cnf --default-character-set=utf8mb4 smart_park' \
  < /import/employee.sql 2> /import/import_err.log

# ④ 校验（不可跳过）
[ -s /import/import_err.log ] && { echo "❌ 导入有错，事务已回滚"; cat /import/import_err.log; exit 1; }
#    行数比对 / MIN(pass_time)MAX(pass_time) 落在真实区间 / '0000-00-00' 计数为 0 / 孤儿引用检查
```

四条硬保证（`csv-to-inserts.py` 内置，QA 用例 TC-MIG-02 / 08 / 12 逐条断言）：

1. **文件级事务**：生成的 .sql 头部 `START TRANSACTION;`、尾部 `COMMIT;`——要么全进要么全不进。校验不通过时**不写 COMMIT**，导入端事务回滚，目标表行数不变（半成品库比完全失败更危险）。
2. **严格 `sql_mode`**：脏日期"响亮失败"（报错中止），绝不静默写 `0000-00-00`。
3. **行级预校验**：编码（非 UTF-8 报行号，且**禁止自动转码**）/ 业务键非空 + 大写归一化（防 `e1001` 与 `E1001` 撞唯一键）/ 枚举取值非法——任一失败即**报出行号并整体中止**，不跳过脏行继续。
4. **列名校验**：CSV 表头必须存在，且与目标列**按名称匹配**，不接受按位置静默映射——防止客户 Excel 列序调整后"导入不报错但数据全错"（这类事故只能靠全量对账发现，代价极高）。

> **导入的三条使用纪律**：
> 1. 必须用 `app.cnf`（应用账号：仅 **DML，无 DDL/DROP**）；`bak.cnf`（备份账号，仅 SELECT）导入会报 **ERROR 1142**；不要用 `mig.cnf`（带 DDL，不该暴露给高频导入）。
>    账号 host 口径：`docker exec` 内的 `mysql` 客户端默认走 **socket**，连接 host 是 `localhost`，
>    所以 app / mig 账号都同时授了 `@'172.28.%'`（容器内 TCP）与 `@'localhost'`（socket）两段
>    —— 少了 localhost 段，导入/归档/恢复报的是 **ERROR 1045（拒绝连接）**，不是 1142，别照 1142 去查。
>    权限边界：即便转义有 bug 或 `.sql` 被手工改过，最坏情况也只是"插入脏数据（可回滚、可被校验发现）"，不会变成删库。
> 2. **必须先 `> out.sql` 再导入，不能直接管道** —— 校验是"中途失败"语义（`exit 4` 且不写 `COMMIT`），管道会把已生成的部分灌进库里。
> 3. **stderr 必须单独留存**（上面的 `2> /import/import_err.log`），QA 校验项断言其"0 字节"；不要把 stderr 合并进 stdout 丢失。

---

## 8. 变更红线（改动前必读）

1. **禁止销毁 `mysql-data` 卷** —— init 脚本只在卷首次创建时执行，销毁即数据不可恢复（仅恢复演练可执行）。
2. **改 `.env` 的 `SP_NET_SUBNET` 前先确认客户 LAN 未占用该段** —— 冲突会导致容器网络异常，且是交付现场才发现。
3. **不要删 my.cnf 的 `skip-name-resolve`** —— 删了会因 DNS 反解导致偶发连接超时（极难排查）。
4. **不要给 MySQL 账号加主机名形式授权**（`@'app-host'`）—— `skip-name-resolve` 下会失效。
5. **不要新增 `GRANT ... ON <db>.flyway_schema_history`** —— 该表在 init 阶段不存在，会报 ERROR 1146 中断首次部署。
6. **不要给后端换 alpine 基础镜像而不改 healthcheck** —— alpine 无 curl，`/dev/tcp` 兜底也需 bash。
7. **不要只给 app / mig 授 `@'172.28.%'` 一段 host** —— `docker exec` 内的 `mysql` 客户端默认走 socket，连接 host 是 `localhost`；少了 `@'localhost'` 段，导入 / 归档 / 恢复全部报 **ERROR 1045**（不是 1142，容易被误判成密码问题）。备份账号 bak 只有 localhost 段，正是因为它只服务 `docker exec`。
8. **🔴 PII 红线：`scripts/csv-to-inserts.py` 里不得实现任何加解密**。存量身份证/手机号加密只走后端一次性 Java Job（复用同一套 CryptoService）——密钥落进 python 脚本等于加密白做。同理，导入脚本不做自动 `iconv` 转码：编码问题必须暴露给操作员，不能静默掩盖。

### 已知约束与变更步骤：应用与数据库分离部署
若客户要求应用容器与数据库分离部署或改连已有 MySQL：
1. 改 `.env` 的 `SP_NET_SUBNET`（或对新主机 IP 追加账号授权：`CREATE USER 'x'@'<新IP>'` + 同步 GRANT）
2. `useSSL` **必须改为 true 并配置证书**；`allowPublicKeyRetrieval=true` 仅在 compose 内网明文链路可接受
3. 重启应用并验证连通性

---

## 9. 归档作业（每月低峰）

在线保留策略（data-engineer 定义）：`access_record` **24 个月**、`stock_record` **36 个月**，过期数据转入同名 `_archive` 表（`ROW_FORMAT=COMPRESSED`）。

```bash
# crontab：每月 1 日 04:00（备份每日 02:30 起，1.5GB dump 常超 30 分钟，排 03:00 会重叠）
#          脚本内另有 mysqldump 进程守卫：备份仍在跑即中止，避免"备份文件已存在≠备份已结束"
0 4 1 * * /opt/smart-park/scripts/archive.sh >> /opt/smart-park/logs/archive.log 2>&1

# 结果：logs/archive.log 记录每张表的搬运行数/批数/残留行数；
#       异常时写入 logs/alerts.log（P1）——归档失败是静默的，必须留痕并被巡检捕获
```

三条硬要求（脚本内置）：**执行前必须有当日备份**（无备份即中止）、**避开发布/备份窗口**、**分批 5000 行 + 批间 sleep 0.1s**（禁止一次性大事务）。驱动方式走容器内 `mysql` 客户端（`docker exec`），不用 `/dev/tcp`（alpine 无 bash 会静默失败）。

> InnoDB 删行后空间会被复用，**默认不做 `OPTIMIZE TABLE`**（千万行级重建耗时以小时计）。确需把磁盘还给 OS 时，在低峰单独执行，主从环境需先看从库延迟。

监控建议：`access_record` 行数/体积增长曲线（判断归档是否失效）+ 归档作业执行结果。

---

## 10. 割接检查单（首次上线）

**上线前**
- [ ] `git status` 无 `target/`、`node_modules/`、`dist/`、`.env`、`mysql/bak.cnf`
- [ ] Flyway `V1__init.sql` 已评审（无 DROP TABLE，utf8mb4）
- [ ] `.env` 各密码/密钥已用 `openssl rand` 生成，`chmod 600`
- [ ] `ip -4 route | grep -E '^172\.28\.'` 为空（网段无冲突）
- [ ] staging 冒烟通过 + 回滚演练完成一次并留记录
- [ ] 备份 crontab 已配置、异机副本可达
- [ ] **🔴 真实容器冒烟已补做**：直接在目标机跑 `./scripts/smoke.sh`（一条命令覆盖全部断言：
      `compose config` / 三容器 healthy / Flyway 1 行 success=1 / 业务表 11 + CHECK 20 / actuator UP /
      前端 200 + SPA fallback / 3306 与 8080 未暴露），失败即 exit 1 并打印失败清单。
      —— 开发机**无 docker**，静态校验（YAML/语法/变量交叉/脚本功能自测）**无法覆盖 compose 网络与 init 时序问题**；
      CI 的 `which curl` 自检只能挡住镜像依赖缺失。此项必须在**有 docker 的机器或客户现场**完成，不得跳过

**上线中**
- [ ] 三容器 `healthy`，Flyway 日志显示迁移仅执行一次
- [ ] 时区四值一致：`SELECT NOW(), UTC_TIMESTAMP(), @@global.time_zone, @@session.time_zone;`
- [ ] 重启 backend 一次，抽查明细表行数不变（验证生产未清库）
- [ ] `curl -o /dev/null -w '%{http_code}' http://<host>/api/h2-console` → **404**
- [ ] `curl -o /dev/null -w '%{http_code}' http://<host>/api/actuator/prometheus` → **403**
- [ ] 用 app 账号执行一条 DROP → 应报 Access denied（最小权限实测）
- [ ] **建表结果三条硬校验**（Flyway 执行后立即跑）：
      - `SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE';` → 期望 **12**（9 主表 + 2 归档 + flyway_schema_history）
      - 口径说明：若只数业务表则为 **11**（9 主表 + 2 归档表），加上 Flyway 自建的 `flyway_schema_history` 为 **12**。检查单以 **12** 为准，不要拿 11 去对 `information_schema` 结果。
      - `SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=DATABASE() AND CONSTRAINT_TYPE='CHECK';` → 期望 **20**；**返回 0 说明 MySQL < 8.0.16，CHECK 被静默忽略，必须停上线先升 MySQL**
      - `SELECT installed_rank, version, success FROM flyway_schema_history;` → 期望 1 行且 `success=1`
- [ ] **Flyway 执行账号验证**（首次部署必验）：迁移日志显示的用户必须是 `smartpark_mig`（Flyway 走独立数据源）；
      反向验证：用 `smartpark_app` 执行一条 `CREATE TABLE` 应报 **1142**（证明运行时账号无 DDL）
- [ ] 首次备份手工触发一次并验证可恢复

**上线后**
- [ ] 30 分钟观察：错误率、ERROR 日志、连接数正常
- [ ] 24 小时复检：日志滚动正常、磁盘增速正常
- [ ] 旧版本镜像保留在宿主机（可回滚）
- [ ] 运维手册移交 + 客户运维培训完成
