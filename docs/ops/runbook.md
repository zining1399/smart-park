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
docker compose stop backend
gunzip < backup/full_YYYYMMDD_HHMM.sql.gz | \
  docker exec -i smart-park-mysql sh -c 'exec mysql --defaults-extra-file=/run/secrets/bak.cnf smart_park'
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
# ① Excel 导出默认 GBK，必须转 UTF-8（乱码在导入时不报错、事后才发现）
iconv -f GBK -t UTF-8 employee_gbk.csv -o employee_utf8.csv

# ② CSV → INSERT（可选 --uppercase 业务键大写归一化、--enum 枚举预校验）
python3 scripts/csv-to-inserts.py employee "emp_no,name,dept,phone,status" \
  /import/employee_utf8.csv --uppercase emp_no --enum "status=在职,离职,休假" > /import/employee.sql

# ③ 管道导入（docker exec -i，不加 -t 防 CR 污染）
docker exec -i smart-park-mysql sh -c \
  'exec mysql --defaults-extra-file=/run/secrets/bak.cnf --default-character-set=utf8mb4 smart_park' \
  < /import/employee.sql 2> /import/import_err.log

# ④ 校验（不可跳过）
[ -s /import/import_err.log ] && { echo "❌ 导入有错，事务已回滚"; cat /import/import_err.log; exit 1; }
#    行数比对 / MIN(pass_time)MAX(pass_time) 落在真实区间 / '0000-00-00' 计数为 0 / 孤儿引用检查
```

两条硬保证（脚本内置）：严格 `sql_mode` 让脏日期**响亮失败**而非静默写 `0000-00-00`；`autocommit=0 + COMMIT` 保证**要么全进要么全不进**（半成品库比失败更危险）。

---

## 8. 变更红线（改动前必读）

1. **禁止销毁 `mysql-data` 卷** —— init 脚本只在卷首次创建时执行，销毁即数据不可恢复（仅恢复演练可执行）。
2. **改 `.env` 的 `SP_NET_SUBNET` 前先确认客户 LAN 未占用该段** —— 冲突会导致容器网络异常，且是交付现场才发现。
3. **不要删 my.cnf 的 `skip-name-resolve`** —— 删了会因 DNS 反解导致偶发连接超时（极难排查）。
4. **不要给 MySQL 账号加主机名形式授权**（`@'app-host'`）—— `skip-name-resolve` 下会失效。
5. **不要新增 `GRANT ... ON <db>.flyway_schema_history`** —— 该表在 init 阶段不存在，会报 ERROR 1146 中断首次部署。
6. **不要给后端换 alpine 基础镜像而不改 healthcheck** —— alpine 无 curl，`/dev/tcp` 兜底也需 bash。

### 已知约束与变更步骤：应用与数据库分离部署
若客户要求应用容器与数据库分离部署或改连已有 MySQL：
1. 改 `.env` 的 `SP_NET_SUBNET`（或对新主机 IP 追加账号授权：`CREATE USER 'x'@'<新IP>'` + 同步 GRANT）
2. `useSSL` **必须改为 true 并配置证书**；`allowPublicKeyRetrieval=true` 仅在 compose 内网明文链路可接受
3. 重启应用并验证连通性

---

## 9. 割接检查单（首次上线）

**上线前**
- [ ] `git status` 无 `target/`、`node_modules/`、`dist/`、`.env`、`mysql/bak.cnf`
- [ ] Flyway `V1__init.sql` 已评审（无 DROP TABLE，utf8mb4）
- [ ] `.env` 各密码/密钥已用 `openssl rand` 生成，`chmod 600`
- [ ] `ip -4 route | grep -E '^172\.28\.'` 为空（网段无冲突）
- [ ] staging 冒烟通过 + 回滚演练完成一次并留记录
- [ ] 备份 crontab 已配置、异机副本可达

**上线中**
- [ ] 三容器 `healthy`，Flyway 日志显示迁移仅执行一次
- [ ] 时区四值一致：`SELECT NOW(), UTC_TIMESTAMP(), @@global.time_zone, @@session.time_zone;`
- [ ] 重启 backend 一次，抽查明细表行数不变（验证生产未清库）
- [ ] `curl -o /dev/null -w '%{http_code}' http://<host>/api/h2-console` → **404**
- [ ] `curl -o /dev/null -w '%{http_code}' http://<host>/api/actuator/prometheus` → **403**
- [ ] 用 app 账号执行一条 DROP → 应报 Access denied（最小权限实测）
- [ ] 首次备份手工触发一次并验证可恢复

**上线后**
- [ ] 30 分钟观察：错误率、ERROR 日志、连接数正常
- [ ] 24 小时复检：日志滚动正常、磁盘增速正常
- [ ] 旧版本镜像保留在宿主机（可回滚）
- [ ] 运维手册移交 + 客户运维培训完成
