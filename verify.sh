#!/usr/bin/env bash
# Smoke-test the ViaPageZoom LSPosed module on a device.
#
# Prereq: the module is enabled in LSPosed with scope mark.via.gp, and the device
# has been rebooted since enabling.
#
# Device access is abstracted behind ADB_CMD, so this works over adb or ssh:
#   ./verify.sh                                  # local adb
#   ADB_CMD="ssh root@phone" ./verify.sh         # adb-over-ssh / remote shell
set -uo pipefail

ADB_CMD="${ADB_CMD:-adb}"
PKG="${PKG:-mark.via.gp}"
TAG="ViaPageZoom"

remote() { $ADB_CMD "$@"; }

echo "== 1. module installed?"
remote "pm path io.github.zw1.viapagezoom" || true

echo
echo "== 2. clear logcat"
remote "logcat -c" 2>/dev/null || true

echo "== 3. force-stop and start $PKG"
remote "am force-stop $PKG; sleep 1; monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; echo started"

echo "== 4. wait for hooks"
sleep 6

echo
echo "== 5. module log"
remote "logcat -d -s ${TAG}:* | tail -40"

echo
echo "== 6. verdict"
OUT="$(remote "logcat -d -s ${TAG}:*" 2>/dev/null)"
if grep -q "hooks installed" <<<"$OUT"; then
  echo "OK: hooks installed. Now open Via's toolbox and look for 页面缩放."
elif grep -q "loading into" <<<"$OUT"; then
  echo "PARTIAL: injected but some hooks failed - inspect the [hookXxx] errors above."
else
  echo "NOT LOADED: no ViaPageZoom log. Check: module enabled? scope=mark.via.gp? rebooted?"
fi
