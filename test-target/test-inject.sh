#!/usr/bin/env bash
# LSPFRIDA 注入测试 —— Java + Native（目标包 com.bail.testtarget）。
#
# 用法（在有 adb 的电脑上，手机已连接）：
#   bash test-inject.sh [lspfrifa-apk]
#
# 前置（手动，只需做一次）：
#   1. 手机装好 LSPosed，LSPFRIDA 模块已启用并勾选 com.bail.testtarget。
#   2. 在 LSPFRIDA 应用里，给 com.bail.testtarget 设置 test-hook.js 的内容。
#   3. 传 lspfrifa-apk 参数可顺带重装模块 APK；不传则只测目标包。
#
# 脚本做的事：
#   装目标 APK -> 启动目标应用（启动即自动各调一次 Java 与 Native）
#   -> 抓 logcat -> 按标记判定 Java / Native 基线与 hook 是否生效。
#   hook 标记没出现时不算硬失败（可能是脚本没设置好），会明确提示。
set -u

HERE=$(cd "$(dirname "$0")" && pwd)
TARGET_APK="$HERE/build/outputs/apk/debug/test-target-debug.apk"
PKG="com.bail.testtarget"
ACTIVITY="$PKG/.MainActivity"
MODULE_APK="${1:-}"

PASS=0
FAIL=0
ok()   { echo "PASS $*"; PASS=$((PASS + 1)); }
bad()  { echo "FAIL $*"; FAIL=$((FAIL + 1)); }
note() { echo "NOTE $*"; }

[ -x "$(command -v adb)" ] || { echo "FAIL adb not found"; exit 1; }
[ -f "$TARGET_APK" ] || { echo "FAIL target APK missing: $TARGET_APK (先跑 bash gradlew :test-target:assembleDebug)"; exit 1; }

adb wait-for-device
echo "device: $(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r') / $(adb shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"

if [ -n "$MODULE_APK" ]; then
    [ -f "$MODULE_APK" ] || { echo "FAIL module APK missing: $MODULE_APK"; exit 1; }
    echo "--- install module APK"
    adb install -r "$MODULE_APK" || bad "install module APK"
fi

echo "--- install target APK"
adb install -r "$TARGET_APK" || bad "install target APK"

echo "--- clear logcat, launch target"
adb logcat -c
adb shell am force-stop "$PKG"
adb shell am start -W -n "$ACTIVITY" >/dev/null
sleep 4

LOG=$(adb logcat -d -s TestTarget lsp-test 2>/dev/null)
echo "--- logcat (TestTarget / lsp-test)"
echo "$LOG"

echo "--- verdict"
echo "$LOG" | grep -q "TARGET_JAVA greet" && ok "Java 基线：Target.greet 跑了" || bad "Java 基线：没看到 TARGET_JAVA"
echo "$LOG" | grep -q "TARGET_NATIVE nativeCompute" && ok "Native 基线：nativeCompute 跑了" || bad "Native 基线：没看到 TARGET_NATIVE"
if echo "$LOG" | grep -q "HOOK_JAVA_FIRED"; then
    ok "Java hook：greet 被 hook 到"
else
    note "Java hook：没看到 HOOK_JAVA_FIRED（检查 LSPosed 是否启用模块并勾选目标包、脚本是否设置）"
fi
if echo "$LOG" | grep -q "HOOK_NATIVE_FIRED"; then
    ok "Native hook：nativeCompute 被 hook 到"
else
    note "Native hook：没看到 HOOK_NATIVE_FIRED（同上；native 还依赖 libtarget.so 已加载）"
fi

echo "---"
echo "baseline: PASS=$PASS FAIL=$FAIL (hook 两项只计 NOTE，不计入 FAIL)"
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
