#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# ============================================================================
# CSV → INSERT 语句（数据导入通用工具：基础数据导入 / H2 存量迁移 共用同一条管道）
#
#   iconv -f GBK -t UTF-8 in.csv -o in_utf8.csv        # Excel 导出默认 GBK，必须先转码
#   python3 scripts/csv-to-inserts.py <表> "<列1,列2,..>" <csv> [选项] > out.sql || 中止
#   docker exec -i smart-park-mysql sh -c \
#     'exec mysql --defaults-extra-file=/run/secrets/app.cnf --default-character-set=utf8mb4 smart_park' \
#     < out.sql 2> import_err.log
#   [ -s import_err.log ] && { echo "导入失败，事务已回滚"; exit 1; }
#
# ⚠ 三条使用纪律：
#   1. 必须用 app.cnf（应用账号：仅 DML，无 DDL/DROP）；
#      用 bak.cnf（备份账号，仅 SELECT）导入会报 ERROR 1142；
#      不要用 mig.cnf（迁移账号带 DDL，不应暴露给高频的导入操作）
#      权限边界：即便本脚本转义有 bug 或 .sql 被手工改过，最坏情况也只是"插入脏数据
#      （可回滚、可被校验发现）"，不会变成删库
#   2. 必须先 "> out.sql" 再导入，不能 ./csv-to-inserts.py ... | docker exec ... 直接管道：
#      校验是"中途失败"语义（exit 4 且不写 COMMIT），管道会把已生成的部分灌进库里
#   3. stderr 必须单独留存（2> import_err.log），QA 断言其 0 字节；不要合并进 stdout
#
# 为什么不用 LOAD DATA LOCAL INFILE：
#   MySQL 8 的 local_infile 默认 OFF（报 ERROR 1148），为一次导入常开它有读写服务端
#   任意文件的安全风险，故统一走 INSERT 管道。
#
# 四条硬保证（对应 QA 用例 TC-MIG-02 / 08 / 12）：
#   1. 文件级事务：头部 START TRANSACTION + 尾部 COMMIT，要么全进要么全不进，
#      绝不留下"行数接近真值"的半成品库（半成品比完全失败更危险）
#   2. 严格 sql_mode：脏日期"响亮失败"（报错中止），绝不静默写 0000-00-00
#   3. 行级预校验：编码 / 业务键（非空 + 大写归一化）/ 枚举取值，
#      任一不合法即报出行号并整体中止（不跳过脏行继续）
#   4. 列名校验：CSV 表头必须与目标表列名按【名称】匹配，不接受按位置静默映射
#      —— 防止客户 Excel 列序调整后"导入不报错但数据全错"
#
# ⚠ PII 红线：本脚本不实现任何加解密。存量身份证/手机号加密只走后端一次性 Java Job
#    （复用同一套 CryptoService）—— 密钥落进 python 脚本等于加密白做。
# ============================================================================
import csv
import sys

USAGE = (
    "用法: csv-to-inserts.py <表名> \"<列1,列2,...>\" <CSV路径>\n"
    "      [--keys 列1,列2]          业务键：必须非空，并做大写归一化\n"
    "      [--enum 列=值1,值2]       枚举预校验（可重复传入多个）\n"
    "      [--uppercase 列1,列2]     仅做大写归一化（不强制非空）\n"
    "      [--allow-col-reorder]     允许列序不同（按名称映射）；默认要求列序一致\n"
)

# 未显式传 --keys 时，按列名自动识别业务键
DEFAULT_KEYS = {"emp_no", "plate_no", "code", "material_code", "visitor_no"}


class Abort(Exception):
    """校验失败：报出行号/列名并整体中止"""
    pass


def parse_args(argv):
    if len(argv) < 4:
        print(USAGE, file=sys.stderr)
        sys.exit(2)
    # 位置参数（表名 / 列名 / CSV 路径）与可选参数可任意顺序出现，减少现场误用
    positional = []
    keys, enums, uppercase, allow_reorder = set(), {}, set(), False
    i = 1
    while i < len(argv):
        a = argv[i]
        if a == "--keys":
            keys = set(x.strip() for x in argv[i + 1].split(","))
            i += 2
        elif a == "--enum":
            col, vals = argv[i + 1].split("=", 1)
            enums[col.strip()] = set(v.strip() for v in vals.split(","))
            i += 2
        elif a == "--uppercase":
            uppercase = set(x.strip() for x in argv[i + 1].split(","))
            i += 2
        elif a == "--allow-col-reorder":
            allow_reorder = True
            i += 1
        elif a.startswith("--"):
            print(f"未知参数: {a}\n{USAGE}", file=sys.stderr)
            sys.exit(2)
        else:
            positional.append(a)
            i += 1
    if len(positional) != 3:
        print(USAGE, file=sys.stderr)
        sys.exit(2)
    table, cols, path = positional[0], [c.strip() for c in positional[1].split(",")], positional[2]
    return table, cols, path, keys, enums, uppercase, allow_reorder


def escape(v):
    return "'" + v.replace("\\", "\\\\").replace("'", "''") + "'"


def decode_lines(path):
    """① 编码预校验：非 UTF-8 即报出行号并中止（Excel 导出 GBK 需先 iconv 转码）
       注意：禁止在本脚本内自动转码 —— 静默转码会掩盖"源文件编码不对"这个事实"""
    try:
        with open(path, "rb") as fb:
            raw = fb.read()
    except FileNotFoundError:
        raise Abort(f"文件不存在: {path}")
    try:
        return raw.decode("utf-8").splitlines(True)
    except UnicodeDecodeError as e:
        bad_line = raw[: e.start].count(b"\n") + 1
        raise Abort(f"编码预校验失败：第 {bad_line} 行附近不是合法 UTF-8（{e.reason}），"
                    f"请先执行 iconv -f GBK -t UTF-8（本脚本不做自动转码）")


def check_header(header, cols, allow_reorder):
    """④ 列名校验：按名称匹配，不接受按位置静默映射"""
    if header is None:
        raise Abort("列名校验失败：CSV 缺少表头行（首行必须为列名）")
    got = [c.strip() for c in header]
    if got == cols:
        return list(range(len(cols)))          # 列序一致，直接按位置取
    if set(got) == set(cols):
        if not allow_reorder:
            raise Abort("列名校验失败：列序与目标列不一致（已按名称识别，但若确认无误可加 "
                        "--allow-col-reorder 按名称映射）\n"
                        f"  CSV 表头: {got}\n  目标列  : {cols}")
        return [got.index(c) for c in cols]    # 按名称映射
    missing = [c for c in cols if c not in got]
    extra = [c for c in got if c not in cols]
    raise Abort(f"列名校验失败：缺少列 {missing}" + (f"；多余列 {extra}" if extra else "")
                + f"\n  CSV 表头: {got}\n  目标列  : {cols}")


def main():
    table, cols, path, keys, enums, uppercase, allow_reorder = parse_args(sys.argv)
    key_cols = keys or {c for c in cols if c in DEFAULT_KEYS}

    print("SET NAMES utf8mb4;")
    # 严格模式：脏数据报错中止（响亮失败），而非静默污染
    print("SET SESSION sql_mode='STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,"
          "ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION';")
    # 文件级事务：以下 INSERT 同属一个事务，COMMIT 仅在全部校验通过后写出
    print("SET SESSION autocommit=0;")
    print("START TRANSACTION;")

    col_list = ", ".join(cols)
    n = 0

    try:
        lines = decode_lines(path)             # ① 编码预校验
        reader = csv.reader(lines)
        header = next(reader, None)
        idx = check_header(header, cols, allow_reorder)   # ④ 列名校验

        for lineno, row in enumerate(reader, start=2):    # 从第 2 行开始（首行是表头）
            if not row or all(c.strip() == "" for c in row):
                continue
            if len(row) != len(header):
                raise Abort(f"第 {lineno} 行列数不符：{len(row)} != {len(header)}")

            values = []
            for col in cols:
                v = row[idx[cols.index(col)]].strip()

                # ② 业务键：必须非空 + 大写归一化（防空值、防 e1001/E1001 撞唯一键）
                if col in key_cols:
                    if v == "":
                        raise Abort(f"第 {lineno} 行业务键 {col} 为空")
                    v = v.upper()
                elif col in uppercase:
                    v = v.upper()

                # ③ 枚举取值
                if col in enums and v not in enums[col]:
                    raise Abort(f"第 {lineno} 行枚举非法：{col}={v!r}，合法值 {sorted(enums[col])}")

                values.append("NULL" if v == "" else escape(v))

            print(f"INSERT INTO {table} ({col_list}) VALUES ({', '.join(values)});")
            n += 1
    except Abort as e:
        # 不写 COMMIT：事务回滚，目标表保持原状（行数不变）
        print(f"-- [ABORT] {e}", file=sys.stderr)
        print(f"-- [ABORT] 已中止且未生成 COMMIT，导入端事务将回滚（表 {table} 不受影响）",
              file=sys.stderr)
        sys.exit(4)

    print("COMMIT;")
    print(f"-- 共生成 {n} 行 INSERT（表：{table}）")


if __name__ == "__main__":
    main()
