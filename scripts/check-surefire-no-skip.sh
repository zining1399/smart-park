#!/usr/bin/env bash
# 集成用例「静默跳过」守卫 —— 防 CI 假绿
#
# 【要解决的问题】
#   MySqlIntegrationTest 基类带 @Testcontainers(disabledWithoutDocker = true)：
#   一旦 runner/本机的 Docker 不可用（或镜像拉不动），JUnit 5 会把**整个测试类**记为
#   skipped，而 surefire 不报失败、mvn 退出码仍是 0 —— 于是「BUILD SUCCESS」里
#   可能一条集成用例都没跑。本仓的 P0 证据全部落在这些类里：
#     鉴权矩阵（AuthMatrixTest，42 接口 × 4 身份 = 168 断言）
#     CHECK 约束矩阵（EnumConstraintsTest，20 条 × 正/负 = 40 断言）
#     审计日志（AuditLogTest，TC-AUD-01）
#     账号禁用即时生效（AccountStatusTest，TC-AUTH-11/12）
#   跳过 = 没测。2026-10-27 归档表 `DROP CHECK` 缺陷一路带到本地真连库才暴露，
#   根因就是这里没有门禁。
#
# 【判据】对每个「必须真跑」的类：
#   ① surefire 报告文件必须存在（缺 = 该类根本没被执行，或被改名 → 同样红）；
#   ② 实际执行数 = tests - skipped 必须 > 0（整类被 Docker 跳过时恰好为 0）；
#   ③ skipped 不得超过该类允许的条数。全仓唯一合法跳过点是
#     AccountStatusTest 的 @Disabled("等待用户管理接口（POST /users）落地后启用")，
#     允许 1；其余类一律允许 0。
#   注意不能只看聚合数字（如 mvn 输出的 Skipped=11）：聚合里混着合法 @Disabled，
#   分不清「被 Docker 跳过」和「被有意停用」，故逐类判定。
#
# 用法：scripts/check-surefire-no-skip.sh [surefire-reports 目录]
#       缺省目录 smart-park-backend/target/surefire-reports
# 退出码：0 = 全部达标；1 = 存在缺失/整类跳过/超额跳过。
set -u

DIR="${1:-smart-park-backend/target/surefire-reports}"

# 格式：<测试类全名>:<允许 skipped 条数>
REQUIRED=(
  "com.smartpark.security.AuthMatrixTest:0"
  "com.smartpark.security.AccountStatusTest:1"
  "com.smartpark.security.AuditLogTest:0"
  "com.smartpark.data.EnumConstraintsTest:0"
)

fail=0

report() {
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo "::error::$1"
  else
    echo "FAIL: $1"
  fi
}

attr() { # 从 <testsuite ...> 开标签里取一个属性值
  local tag="$1" key="$2"
  printf '%s\n' "$tag" | sed -n "s/.* ${key}=\"\([0-9]*\)\".*/\1/p" | head -1
}

for entry in "${REQUIRED[@]}"; do
  cls="${entry%%:*}"
  allow_skip="${entry##*:}"
  file="$DIR/TEST-${cls}.xml"

  if [ ! -f "$file" ]; then
    report "$cls 缺少 surefire 报告 $file —— 该类没被执行（mvn 未跑到位 / 类被改名 / -DskipTests）"
    fail=1
    continue
  fi

  tag="$(grep -m1 -o '<testsuite[^>]*>' "$file")"
  tests="$(attr "$tag" tests)"
  skipped="$(attr "$tag" skipped)"
  errors="$(attr "$tag" errors)"
  failures="$(attr "$tag" failures)"

  if [ -z "$tests" ] || [ -z "$skipped" ]; then
    report "$cls 报告里读不到 tests/skipped 属性，无法判定 —— 请检查 surefire 版本与报告格式"
    fail=1
    continue
  fi

  executed=$(( tests - skipped ))
  if [ "$executed" -le 0 ]; then
    report "$cls 实际执行 0 条（tests=$tests skipped=$skipped）—— 整类被静默跳过，等于没测"
    if grep -q 'disabledWithoutDocker is true and Docker is not available' "$file"; then
      echo "       原因定位：Docker 不可用，Testcontainers 跳过了整类。修 runner 的 Docker，或改在能连 Docker 的环境执行。"
    fi
    fail=1
    continue
  fi

  if [ "$skipped" -gt "$allow_skip" ]; then
    report "$cls 跳过 $skipped 条，超过允许的 $allow_skip 条（合法 @Disabled 只有 AccountStatusTest 那 1 条）"
    fail=1
    continue
  fi

  echo "ok: $cls 执行 $executed 条 / skipped=$skipped（允许 $allow_skip）/ failures=$failures errors=$errors"
done

if [ "$fail" -ne 0 ]; then
  echo
  echo "集成用例守卫未通过：上表中的类是交付验收的 P0 证据来源，跳过即视为「未验证」，不允许绿。"
  exit 1
fi

echo "集成用例守卫通过：4 个必跑类均有真实执行量。"
