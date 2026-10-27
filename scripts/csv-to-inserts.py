#!/usr/bin/env python3
# ============================================================================
# CSV → INSERT 语句（数据导入通用工具）
#
# 适用：基础数据导入（客户 Excel→CSV）与 H2 存量迁移，均为同一条管道：
#   iconv -f GBK -t UTF-8 in.csv -o in_utf8.csv     # Excel 导出默认 GBK，必须先转码
#   python3 scripts/csv-to-inserts.py <表> "<列1,列2,...>" <csv> [选项] > out.sql
#   docker exec -i smart-park-mysql sh -c \
#     'exec mysql --defaults-extra-file=/run/secrets/bak.cnf --default-character-set=utf8mb4 smart_park' \
#     < out.sql 2> import_err.log
#   [ -s import_err.log ] && { echo "导入失败，事务已回滚"; exit 1; }
#
# 为什么不用 LOAD DATA LOCAL INFILE：MySQL 8 的 local_infile 默认 OFF（报 ERROR 1148），
# 为一次导入常开它有读写服务端任意文件的安全风险，故统一走 INSERT 管道。
#
# 两条硬保证：
#   1. 严格 sql_mode：脏日期"响亮失败"（报错中止），绝不静默写 0000-00-00
#   2. autocommit=0 + 末尾 COMMIT：要么全进要么全不进，绝不留下半成品库
# ============================================================================
import csv
import sys

USAGE = (
    "用法: csv-to-inserts.py <表名> \"<列1,列2,...>\" <CSV路径> "
    "[--uppercase 列1,列2] [--enum 列=值1,值2] [--no-header]\n"
)


def parse_args(argv):
    if len(argv) < 4:
        print(USAGE, file=sys.stderr)
        sys.exit(2)
    table, cols, path = argv[1], argv[2].split(","), argv[3]
    uppercase, enums, skip_header = set(), {}, False
    i = 4
    while i < len(argv):
        a = argv[i]
        if a == "--uppercase":
            uppercase = set(argv[i + 1].split(","))
            i += 2
        elif a == "--enum":
            col, vals = argv[i + 1].split("=", 1)
            enums[col.strip()] = set(v.strip() for v in vals.split(","))
            i += 2
        elif a == "--no-header":
            skip_header = True
            i += 1
        else:
            print(f"未知参数: {a}\n{USAGE}", file=sys.stderr)
            sys.exit(2)
    return table, cols, path, uppercase, enums, skip_header


def escape(v):
    return "'" + v.replace("\\", "\\\\").replace("'", "''") + "'"


def main():
    table, cols, path, uppercase, enums, skip_header = parse_args(sys.argv)

    print("SET NAMES utf8mb4;")
    # 严格模式：脏数据报错中止，而非静默污染
    print("SET SESSION sql_mode='STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,"
          "ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION';")
    # 关闭自动提交：全量事务，中途失败即回滚
    print("SET SESSION autocommit=0;")

    col_list = ", ".join(cols)
    n = 0
    with open(path, encoding="utf-8") as f:
        reader = csv.reader(f)
        for lineno, row in enumerate(reader, start=1):
            # 首行若与传入列名完全一致则视为表头跳过（也可用 --no-header 强制不跳）
            if lineno == 1 and not skip_header and [c.strip() for c in row] == [c.strip() for c in cols]:
                continue
            if len(row) != len(cols):
                print(f"-- [ERROR] 第 {lineno} 行列数不符（{len(row)} != {len(cols)}），请修正后重试",
                      file=sys.stderr)
                sys.exit(3)
            values = []
            for col, raw in zip(cols, row):
                v = raw.strip()
                if col in uppercase:
                    v = v.upper()          # 业务键大写归一化，防 e1001/E1001 撞唯一键
                if col in enums and v not in enums[col]:
                    print(f"-- [ERROR] 第 {lineno} 行枚举非法：{col}={v!r}（合法值 {sorted(enums[col])}）",
                          file=sys.stderr)
                    sys.exit(4)
                values.append("NULL" if v == "" else escape(v))
            print(f"INSERT INTO {table} ({col_list}) VALUES ({', '.join(values)});")
            n += 1

    print("COMMIT;")
    print(f"-- 共生成 {n} 行 INSERT（表：{table}）")


if __name__ == "__main__":
    main()
