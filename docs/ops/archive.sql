-- =============================================================================
-- docs/ops/archive.sql · 历史数据归档作业
-- 智慧园区管理系统（smart-park）
-- 作者：储海量（数据建模工程师）
-- =============================================================================
--
-- 【归档策略】
--   access_record（通行流水）：在线保留 24 个月，超期迁入 access_record_archive
--   stock_record（出入库流水）：在线保留 36 个月，超期迁入 stock_record_archive
--   其余 6 张表是主数据 / 配置数据，量级在千级，不归档。
--   归档表在 Flyway V1__init.sql 中已用 CREATE TABLE ... LIKE 建好并解除了 CHECK 继承。
--
-- 【执行纪律 —— 五条，违反任何一条都可能把归档变成删数据】
--   1. 执行前必须有一份当日可用备份（全量或最近一次全量 + 完整 binlog）。
--   2. 只在业务低峰执行；必须与 Flyway 发布窗口错开
--      （--single-transaction 备份 / DDL 与归档大事务并发有风险）。
--   3. 分批搬运，每批 5000 行，批间 sleep 0.1s，绝不一次性大事务搬几十万行。
--   4. 先 INSERT 后 DELETE，两者在同一事务内，靠确定性排序保证搬的就是删的那批。
--   5. 每批都记下影响行数，任何一批 INSERT 与 DELETE 行数不等 → 立刻停、保留现场。
--
-- 【执行方式】
--   本文件是"可直接粘进 mysql 客户端"的 SQL，但循环控制需要 shell 驱动。
--   推荐在 mysql 容器内用下面的一行循环跑（mysql 客户端随镜像自带，不需要额外装工具）：
--
--     docker exec -i <mysql容器名> mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$SMARTPARK_DB_NAME" <<'SQL'
--       -- 把下面 §2.1 或 §3.1 的单批块粘进来
--     SQL
--
--   循环驱动示例（bash，在宿主机上，容器内需有 mysql 客户端）：
--
--     CUTOFF_MONTHS=24
--     while :; do
--       N=$(docker exec -i <mysql容器名> mysql -N -B -uroot -p"$MYSQL_ROOT_PASSWORD" "$SMARTPARK_DB_NAME" <<SQL
-- SET SESSION autocommit=0;
-- SET @cutoff = DATE_SUB(NOW(), INTERVAL ${CUTOFF_MONTHS} MONTH);
-- START TRANSACTION;
-- INSERT INTO access_record_archive SELECT * FROM access_record WHERE pass_time < @cutoff ORDER BY pass_time, id LIMIT 5000;
-- DELETE FROM access_record WHERE pass_time < @cutoff ORDER BY pass_time, id LIMIT 5000;
-- SELECT ROW_COUNT();   -- 必须在 COMMIT 前取：COMMIT 之后 ROW_COUNT() 不再反映 DELETE 的行数
-- COMMIT;
-- SQL
--       )
--       echo "batch moved: $N"
--       [ "$N" = "0" ] && break
--       sleep 0.1
--     done
--
-- =============================================================================


-- =============================================================================
-- §1 执行前检查（必做，三分钟能省一次事故）
-- =============================================================================

-- 1.1 确认归档表存在（4 张表都必须查到；ROW_FORMAT 应为 COMPRESSED）
SELECT TABLE_NAME, ENGINE, ROW_FORMAT, CREATE_TIME
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('access_record', 'access_record_archive', 'stock_record', 'stock_record_archive');
-- 期望：4 行；两张归档表 ROW_FORMAT = COMPRESSED

-- 1.1b 确认列数一致（归档表是 CREATE TABLE ... LIKE 建的，列数必须完全相同）
SELECT t1.TABLE_NAME AS 主表, t2.TABLE_NAME AS 归档表,
       (SELECT COUNT(*) FROM information_schema.COLUMNS c1
         WHERE c1.TABLE_SCHEMA = DATABASE() AND c1.TABLE_NAME = t1.TABLE_NAME) AS 主表列数,
       (SELECT COUNT(*) FROM information_schema.COLUMNS c2
         WHERE c2.TABLE_SCHEMA = DATABASE() AND c2.TABLE_NAME = t2.TABLE_NAME) AS 归档表列数
FROM (SELECT 'access_record' AS TABLE_NAME) t1
JOIN (SELECT 'access_record_archive' AS TABLE_NAME) t2
UNION ALL
SELECT 'stock_record', 'stock_record_archive',
       (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_record'),
       (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_record_archive');

-- 1.2 确认归档表没有继承到 CHECK（有则说明 V1 的 DROP CHECK 没跑成功，先补跑再归档）
SELECT CONSTRAINT_NAME, TABLE_NAME
FROM information_schema.TABLE_CONSTRAINTS
WHERE TABLE_SCHEMA = DATABASE()
  AND CONSTRAINT_TYPE = 'CHECK'
  AND TABLE_NAME LIKE '%_archive';
-- 期望：0 行

-- 1.3 预演：先看这一轮要搬多少行（不写任何数据）
SET @cutoff_ar = DATE_SUB(NOW(), INTERVAL 24 MONTH);
SET @cutoff_sr = DATE_SUB(NOW(), INTERVAL 36 MONTH);
SELECT 'access_record' AS 表, COUNT(*) AS 待归档行数 FROM access_record WHERE pass_time < @cutoff_ar
UNION ALL
SELECT 'stock_record', COUNT(*) FROM stock_record
 WHERE create_time < @cutoff_sr AND (type = 'IN' OR return_time IS NOT NULL);
-- 行数超过 50 万时，把批大小调到 2000 并拉长 sleep，避免长事务顶住主从延迟


-- =============================================================================
-- §2 access_record 归档（在线 24 个月）
-- =============================================================================

-- §2.1 单批搬运（重复执行直到"影响行数 = 0"）
--      排序 (pass_time, id) 是确定性的：INSERT 选出的行与 DELETE 删掉的行必然是同一批。
--      换成任何非唯一排序（比如只按 pass_time）都可能在时间重复时搬删不一致。
--
--      前提：归档窗口内不得补录历史数据。本系统通行记录的 pass_time 一律写当前时间，
--      不会被 cutoff 命中，因此正常运行下无此风险；若临时需要补录历史数据，必须先停归档作业
--      ——否则 INSERT 与 DELETE 之间新插入的历史行可能被 DELETE 命中却没被归档，造成丢数据。
SET SESSION autocommit = 0;
SET @cutoff = DATE_SUB(NOW(), INTERVAL 24 MONTH);

START TRANSACTION;

INSERT INTO access_record_archive
SELECT * FROM access_record
WHERE pass_time < @cutoff
ORDER BY pass_time, id
LIMIT 5000;

DELETE FROM access_record
WHERE pass_time < @cutoff
ORDER BY pass_time, id
LIMIT 5000;

-- ROW_COUNT() 必须在 COMMIT 之前取：COMMIT 之后它不再反映 DELETE 的行数
SELECT ROW_COUNT() AS 本批搬运行数;
COMMIT;

-- §2.2 本轮结束后的校验（三条都必须符合预期）
--  ① 主表不再有早于 cutoff 的数据
SELECT COUNT(*) AS 主表残留超期行 FROM access_record WHERE pass_time < @cutoff;
-- 期望：0

--  ② 归档表没有被误搬进"未超期"的数据（cutoff 方向写反时这条会立刻暴露）
SELECT COUNT(*) AS 归档表混入未超期行 FROM access_record_archive WHERE pass_time >= @cutoff;
-- 期望：0

--  ③ 总量守恒：归档表 + 主表 == 归档前主表行数（用 §1.3 记下的数比对）
SELECT (SELECT COUNT(*) FROM access_record) + (SELECT COUNT(*) FROM access_record_archive) AS 归档后总行数;


-- =============================================================================
-- §3 stock_record 归档（在线 36 个月）
-- =============================================================================

-- §3.1 单批搬运
--      归档条件比 access_record 多一条：只搬"已完结"的单据
--        · type='IN'（入库单，天然完结）
--        · type='OUT' 且 return_time IS NOT NULL（已归还）
--      仍在外未还的 OUT 单必须留在线表，否则物资逾期扫描与领用校验会查不到。
SET SESSION autocommit = 0;
SET @cutoff = DATE_SUB(NOW(), INTERVAL 36 MONTH);

START TRANSACTION;

INSERT INTO stock_record_archive
SELECT * FROM stock_record
WHERE create_time < @cutoff
  AND (type = 'IN' OR return_time IS NOT NULL)
ORDER BY create_time, id
LIMIT 5000;

DELETE FROM stock_record
WHERE create_time < @cutoff
  AND (type = 'IN' OR return_time IS NOT NULL)
ORDER BY create_time, id
LIMIT 5000;

SELECT ROW_COUNT() AS 本批搬运行数;
COMMIT;

-- §3.2 校验
SELECT COUNT(*) AS 主表残留超期行 FROM stock_record
 WHERE create_time < @cutoff AND (type = 'IN' OR return_time IS NOT NULL);
-- 期望：0

SELECT COUNT(*) AS 归档表混入未完结行 FROM stock_record_archive
 WHERE type = 'OUT' AND return_time IS NULL;
-- 期望：0（归档表里出现未归还的 OUT 单说明归档条件写错了，必须回滚）

SELECT COUNT(*) AS 归档表混入未超期行 FROM stock_record_archive WHERE create_time >= @cutoff;
-- 期望：0


-- =============================================================================
-- §4 空间回收（可选，默认不做）
-- =============================================================================
-- InnoDB 删除行后空间会被后续插入复用，不主动 OPTIMIZE 通常也能撑住。
-- 只有确需把磁盘还给操作系统时才做，且必须在低峰：OPTIMIZE 是重建表，千万行级耗时以小时计。
-- MySQL 8.0 的 OPTIMIZE TABLE 是 online DDL，但仍有额外 IO 与复制延迟，主从环境先看从库延迟。
--   OPTIMIZE TABLE access_record;
--   OPTIMIZE TABLE stock_record;
-- 归档表本身是 ROW_FORMAT=COMPRESSED，不要再对它 OPTIMIZE（会解压重算，白费力气）。


-- =============================================================================
-- §5 回滚（只在最近一批内可靠；多批之后请走备份恢复）
-- =============================================================================
-- 把刚搬走的一批原样搬回主表。注意：主表 id 是自增，这里显式带回原 id 以保住引用关系。
-- 回滚条件：只搬了一批且尚未做下一次备份。超过一批请用 PITR 恢复到归档前的时间点。

SET SESSION autocommit = 0;
SET @cutoff = DATE_SUB(NOW(), INTERVAL 24 MONTH);   -- 与 §2.1 保持一致

START TRANSACTION;

INSERT INTO access_record
SELECT * FROM access_record_archive
WHERE pass_time < @cutoff
ORDER BY pass_time, id
LIMIT 5000;

DELETE FROM access_record_archive
WHERE pass_time < @cutoff
ORDER BY pass_time, id
LIMIT 5000;

SELECT ROW_COUNT() AS 回滚行数;
COMMIT;


-- =============================================================================
-- §6 作业台账（人工填写，归档不是"跑完就算"）
-- =============================================================================
-- 每次归档在运维记录里留一行，便于审计与事故回溯：
--   执行时间 / 执行人 / 归档表 / cutoff 时间点 / 备份文件位置 /
--   搬运总行数 / 批数 / 校验①②③结果 / 异常与处理
--
-- 【待确认】留存合规口径：24 / 36 个月是本项目建议值，若客户方（或等保/行业规范）
--           要求更长的最短留存期，需先确认再执行——归档本身可逆，但"超出最短留存期后
--           删除"这一步不可逆，未确认前不得删除归档表数据。
