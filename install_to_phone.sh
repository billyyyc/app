#!/bin/bash
# 调试迭代脚本：重装 APK 但保留已授予的无障碍/悬浮窗权限，免去反复手动授权。
# 用法：
#   ./install_to_phone.sh            # 默认用工作区 app-debug.apk，USB/已连接设备
#   ./install_to_phone.sh 192.168.x.x:xxxxx   # 先 adb connect 无线调试地址
#   ./install_to_phone.sh "" log     # 只重装并 tail 日志（不重连）
set -e

PKG=com.example.studentlookup
SVC=com.example.studentlookup/.service.LookupAccessibilityService
ADB="${ADB:-/Users/yuyuanchuan/WorkBuddy/2026-10-07-16-44-51/adb}"
APK="${APK:-/Users/yuyuanchuan/WorkBuddy/2026-10-07-16-44-51/app-debug.apk}"

# 若指定了无线地址则先连接
if [ -n "$1" ] && [[ "$1" == *:* ]]; then
  echo ">> adb connect $1"
  "$ADB" connect "$1" || true
fi

echo ">> adb devices"
"$ADB" devices | sed '1d' | grep -q device || { echo "未检测到已连接设备，请先连接（USB 或无线调试）"; exit 1; }

echo ">> 重装 APK（保留数据/授权）"
"$ADB" install -r -t "$APK"

echo ">> 自动补授权（跳过手动开关）"
"$ADB" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
# 让 App 具备「自愈」能力（把无障碍服务摘掉再装回，触发系统重绑）
"$ADB" shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS || true

# 关键：重装后系统常处于「设置里已启用、但服务没绑定」的假死态（点悬浮球没反应的根因）。
# 先 delete 再 put，制造一次真实变更，逼系统重新绑定无障碍服务。
echo ">> 强制重新绑定无障碍服务"
"$ADB" shell settings delete secure enabled_accessibility_services >/dev/null 2>&1 || true
"$ADB" shell settings put secure enabled_accessibility_services "$SVC"
"$ADB" shell settings put secure accessibility_enabled 1

echo ">> 拉起 app"
"$ADB" shell am start -n "$PKG/.ui.MainActivity" || true

if [ "$2" = "log" ]; then
  echo ">> 日志（Ctrl+C 退出）："
  "$ADB" logcat -c
  "$ADB" logcat | grep -iE "studentlookup|AndroidRuntime|crash|FATAL" || true
fi

echo "完成。无障碍/悬浮窗授权已保留，下次只需重新跑本脚本即可。"
