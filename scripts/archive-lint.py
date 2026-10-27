#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# ============================================================================
# 归档 SQL 静态校验（无需 MySQL，纯文本检查，纳入 CI）
# 目标：docs/ops/archive.sql —— 归档脚本是"能跑通但数据悄悄错"的高危区，
#       下面每一条都对应一个人工 review 看不出来的真实事故模式。
#
# 用法: python3 scripts/archive-lint.py [docs/ops/archive.sql]
# 退出码: 0 通过 / 1 校验失败 / 2 用法或文件错误
# ============================================================================
import re
import sys

PATH = sys.argv[1] if len(sys.argv) > 1 else "docs/ops/archive.sql"

try:
    with open(PATH, encoding="utf-8") as f:
        raw = f.read()
except FileNotFoundError:
    print(f"[FAIL] 文件不存在: {PATH}")
    sys.exit(2)

# 去掉整行注释，避免注释里的示例语句干扰判定
code_lines = [ln for ln in raw.splitlines() if not ln.strip().startswith("--")]
code = "\n".join(code_lines)

errors = []


def norm(s):
    return re.sub(r"\s+", " ", s).strip()


# -------- ① 不得出现破坏性语句 --------
bad = re.findall(r"(?i)\b(drop\s+table|drop\s+database|truncate\s+table|truncate)\b", code)
if bad:
    errors.append(f"① 归档脚本出现破坏性语句: {sorted(set(bad))}")

# -------- 按 § 切段（按"§N "标题行切片，避免正则跨段）--------
def section(name):
    starts = [(m.start(), m.group(1)) for m in re.finditer(r"§(\d+)\s", raw)]
    idx = None
    for i, (pos, num) in enumerate(starts):
        if num == name:
            idx = i
            break
    if idx is None:
        return ""
    begin = starts[idx][0]
    end = starts[idx + 1][0] if idx + 1 < len(starts) else len(raw)
    return raw[begin:end]


for sec_id, table, time_col in (("2", "access_record", "pass_time"),
                                ("3", "stock_record", "create_time")):
    seg = section(sec_id)
    if not seg:
        errors.append(f"未找到 §{sec_id} 段落（{table}）")
        continue
    seg_code = "\n".join(ln for ln in seg.splitlines() if not ln.strip().startswith("--"))

    # -------- ② INSERT 与 DELETE 谓词必须逐字一致（防"搬的和删的不是同一批"）--------
    ins = re.search(rf"INSERT INTO {table}_archive\s+SELECT \*\s+FROM {table}\s+WHERE(.+?)ORDER BY(.+?)LIMIT",
                    seg_code, re.S | re.I)
    dele = re.search(rf"DELETE FROM {table}\s+WHERE(.+?)ORDER BY(.+?)LIMIT", seg_code, re.S | re.I)
    if not ins or not dele:
        errors.append(f"② {table}: 未匹配到 INSERT/DELETE 语句块")
        continue
    if norm(ins.group(1)) != norm(dele.group(1)):
        errors.append(f"② {table}: INSERT 与 DELETE 谓词不一致\n"
                      f"     INSERT: {norm(ins.group(1))}\n"
                      f"     DELETE: {norm(dele.group(1))}")

    # -------- ③ 必须确定性排序（ORDER BY <时间列>, id）--------
    for kind, order in (("INSERT", ins.group(2)), ("DELETE", dele.group(2))):
        o = norm(order).lower()
        if not re.search(r"id\s*$", o) or time_col not in o:
            errors.append(f"③ {table} {kind}: 排序非确定性（应为 ORDER BY {time_col}, id），实际: {norm(order)}")

    # -------- ④ ROW_COUNT() 必须在 COMMIT 之前 --------
    i_rc, i_commit = seg_code.find("ROW_COUNT()"), seg_code.find("COMMIT")
    if i_rc == -1:
        errors.append(f"④ {table}: 缺少 ROW_COUNT()")
    elif i_commit == -1:
        errors.append(f"④ {table}: 缺少 COMMIT")
    elif i_rc > i_commit:
        errors.append(f"④ {table}: ROW_COUNT() 出现在 COMMIT 之后，批次数判断会失效")

# -------- ⑤ stock_record 必须带"已完结"条件（防未归还 OUT 单被归档）--------
seg3 = section("3")
if seg3:
    seg3_code = "\n".join(ln for ln in seg3.splitlines() if not ln.strip().startswith("--"))
    if not re.search(r"type\s*=\s*'IN'", seg3_code, re.I):
        errors.append("⑤ stock_record: 缺少 type = 'IN' 条件")
    if not re.search(r"return_time\s+IS\s+NOT\s+NULL", seg3_code, re.I):
        errors.append("⑤ stock_record: 缺少 return_time IS NOT NULL 条件（未归还 OUT 单会被误归档）")

if errors:
    print(f"[FAIL] {PATH} 未通过归档静态校验（{len(errors)} 项）：")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(f"[OK] {PATH} 归档静态校验通过（5 项：破坏性语句 / 谓词一致 / 确定性排序 / ROW_COUNT 顺序 / 完结条件）")
sys.exit(0)
