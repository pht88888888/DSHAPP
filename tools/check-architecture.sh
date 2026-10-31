#!/usr/bin/env bash
# 架构守卫：防止代码退化。跑法：./tools/check-architecture.sh
# 退出码 0 = 通过；非 0 = 有违规。
set -uo pipefail
cd "$(dirname "$0")/.."
fail=0

echo "── 1. 单文件行数上限（防回退成巨型类）──"
# 三个历史遗留大文件只许变小、不许变大，上限取当前值。
check_limit() {
  local f="$1" limit="$2" n
  n=$(wc -l < "$f")
  if [ "$n" -gt "$limit" ]; then
    echo "  ✗ $f: $n 行 > 上限 $limit"; fail=1
  else
    echo "  ✓ $f: $n / $limit"
  fi
}
check_limit "app/src/main/java/com/dsh/android/engine/EngineManager.kt" 11350
check_limit "app/src/main/java/com/dsh/android/ui/MainActivity.kt" 5480
check_limit "app/src/main/java/com/dsh/android/bubble/FloatingBubble.kt" 5430

echo
echo "── 2. 其余文件不得超过 3000 行（当前最大 2903） ──"
while IFS= read -r f; do
  case "$f" in
    *EngineManager.kt|*MainActivity.kt|*FloatingBubble.kt) continue ;;
  esac
  n=$(wc -l < "$f")
  if [ "$n" -gt 3000 ]; then
    echo "  ✗ $f: $n 行 > 3000"; fail=1
  fi
done < <(find app/src -name "*.kt")
echo "  （未列出的均在上限内）"

echo
echo "── 3. 禁止「修复 N」编号式注释（去 git log 查）──"
if grep -rn "修复 [0-9]\+" --include="*.kt" app/src/ 2>/dev/null | head -5; then
  echo "  ✗ 发现编号式注释"; fail=1
else
  echo "  ✓ 无编号式注释"
fi

echo
echo "── 4. 权限三件套一致性 ──"
if grep -q "android.permission.CAMERA" app/src/main/AndroidManifest.xml; then
  echo "  ✓ CAMERA 已声明"
else
  echo "  ✗ 清单缺 CAMERA"; fail=1
fi
if grep -q "android.permission.RECORD_AUDIO" app/src/main/AndroidManifest.xml; then
  echo "  ✓ RECORD_AUDIO 已声明"
else
  echo "  ✗ 清单缺 RECORD_AUDIO"; fail=1
fi
for cb in onPermissionRequest onShowFileChooser onGeolocationPermissionsShowPrompt onJsAlert; do
  if grep -q "$cb" app/src/system/java/com/dsh/android/web/SystemWebHost.kt; then
    echo "  ✓ system 实现 $cb"
  else
    echo "  ✗ system 缺 $cb"; fail=1
  fi
done

echo
echo "── 5. gecko 分派表与桥对齐 ──"
b=$(grep -oE '^ +fun [a-zA-Z0-9_]+' app/src/main/java/com/dsh/android/web/PageBridge.kt | sed 's/.*fun //' | sort -u)
g=$(grep -oE '"[a-zA-Z0-9_]+" ->' app/src/gecko/java/com/dsh/android/web/GeckoWebHost.kt | sed 's/"//g;s/ ->//' | sort -u)
missing=$(comm -23 <(echo "$b") <(echo "$g"))
if [ -n "$missing" ]; then
  echo "  ⚠ gecko 分派表缺以下方法（若靠 pushSnapshot 推送则正常）:"
  echo "$missing" | sed 's/^/      /'
else
  echo "  ✓ 无缺失"
fi

echo
if [ "$fail" -eq 0 ]; then echo "全部通过"; else echo "有违规项，见上"; fi
exit $fail
