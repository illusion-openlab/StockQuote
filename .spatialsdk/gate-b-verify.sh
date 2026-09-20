#!/bin/bash
# 门禁 B 合规执行脚本（spatial-design-first-build 的 B2 节）
#
# 存在的原因：2026-08-11 那次是凭 AGENTS.md 里的二手信息自己搭验证脚本，把该 skill 的几条硬约束漏掉了
# ——最要紧的是**截完图没有 app stop**，导致应用在共享模拟器上一直挂着占设备。这个脚本把规则固化下来，
# 下一个会话直接用，不要再手搓。
#
#   锁内只做：install → launch → 等渲染 → 截图 → app stop → release
#   锁外做：  gradle 编译、读图判定、logcat、adb pull、改代码
#
# 用法: .spatialsdk/gate-b-verify.sh <截图基名> [渲染等待秒数] [设备serial]
set -u

PROJ=/Users/zohar/WorkSpace/Project/PicoProjects/StockQuote
LOCK=/Users/zohar/WorkSpace/Project/PicoProjects/.claude/skills/spatial-design-first-build/scripts/device-lock.sh
PKG=tech.illusion.stockquote
ACTIVITY=.platform.LaunchActivity
APK=app/build/outputs/apk/debug/app-debug.apk
NAME="${1:?用法: gate-b-verify.sh <截图基名> [渲染等待秒数] [设备serial]}"
RENDER_WAIT="${2:-50}"
# 默认锁定模拟器。本机装了多个 AVD，emulator-5554 这个端口会在它们之间跳（AGENTS.md 有记录）；
# 而且 2026-08-11 起还会有真机（PB3B4XJGL2090011G）插着，所以 serial 必须一路显式传下去。
SERIAL="${3:-emulator-5554}"
LOG="$PROJ/.spatialsdk/ui-verify/${NAME}.runlog"

cd "$PROJ" || exit 1

# 释放锁是无条件义务；任何退出路径都先 stop 再 release
cleanup() {
  pico-cli app stop "$PKG" --device "$SERIAL" >>"$LOG" 2>&1
  "$LOCK" release "$OWNER" "$SERIAL" >>"$LOG" 2>&1
}

# ── 预检：目标设备真的在线吗（锁外做，很快）─────────────────────────────
if ! adb devices | awk '$2=="device"{print $1}' | grep -qx "$SERIAL"; then
  echo "目标设备 $SERIAL 不在线。当前在线设备："
  adb devices | awk '$2=="device"{print "  "$1}'
  echo "换设备就把 serial 作为第三个参数传进来：$0 $NAME $RENDER_WAIT <serial>"
  exit 2
fi

OWNER="StockQuote-$$"
: > "$LOG"

# ── B2 锁内 ────────────────────────────────────────────────────────────
# acquire 退出码 1 = 等待超时。**不死等、不重试第二遍**（skill 红旗："设备被占用，我多等一会儿"）。
if ! "$LOCK" acquire "$OWNER" 120 "$SERIAL" >>"$LOG" 2>&1; then
  echo "device busy — 本轮 UI 验证已跳过（未重试）。当前锁状态："
  "$LOCK" status "$SERIAL"
  echo "等对方释放后重跑：$0 $NAME $RENDER_WAIT $SERIAL"
  exit 1
fi
trap cleanup EXIT INT TERM

LOCK_START=$(date +%s)

# ⚠️ 每个 pico-cli 都要显式 --device：有真机+模拟器同时在线时，不指定会选错设备
# （2026-08-11 踩到：真机插上后这一轮直接哑掉，而当时输出被丢进 /dev/null 所以看不出原因，
#  现在全部落到 $LOG 里）。
STEP_FAIL=0
pico-cli app install "$APK" --device "$SERIAL" >>"$LOG" 2>&1 || STEP_FAIL=1
pico-cli app launch "$PKG" --activity "$ACTIVITY" --device "$SERIAL" >>"$LOG" 2>&1 || STEP_FAIL=1
sleep "$RENDER_WAIT"
pico-cli capture screenshot --out "./.spatialsdk/ui-verify/${NAME}.png" --device "$SERIAL" >>"$LOG" 2>&1 || STEP_FAIL=1
pico-cli app stop "$PKG" --device "$SERIAL" >>"$LOG" 2>&1
"$LOCK" release "$OWNER" "$SERIAL" >>"$LOG" 2>&1

LOCK_SECONDS=$(( $(date +%s) - LOCK_START ))
trap - EXIT INT TERM

echo "锁内耗时: ${LOCK_SECONDS}s (目标 ≤90s，硬上限 120s) | 设备: $SERIAL"
[ "$LOCK_SECONDS" -gt 120 ] && echo "⚠️ 超过 120s 硬上限，本轮记为验证失败（原因：超时）"
[ "$STEP_FAIL" -ne 0 ] && echo "⚠️ 有步骤失败，详见 $LOG"
echo "应用已停止，锁已释放，设备已让出。"

if [ -f "./.spatialsdk/ui-verify/${NAME}.png" ]; then
  ls -la "./.spatialsdk/ui-verify/${NAME}.png"
else
  echo "❌ 截图未生成 —— 本轮无效。$LOG 末尾："
  tail -20 "$LOG"
  exit 3
fi

# 崩溃检查放在锁外（skill 明确禁止锁内翻 logcat）
echo "--- 崩溃缓冲区（锁外读取）---"
adb -s "$SERIAL" logcat -b crash -d 2>&1 | tail -15
