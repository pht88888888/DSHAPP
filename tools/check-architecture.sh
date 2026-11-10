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
check_limit "app/src/main/java/com/dsh/android/engine/EngineManager.kt" 7419
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
echo "── 5. 双内核真实桥协议与标注行为覆盖 ──"
# 读取协议及完整 PageBridge/System 签名，并在 VM 调用真实 Gecko/标注桥。
# snapshot 与 invoke 按协议分别验证；缺文件或不一致直接失败。
if ! node tools/test-bridge-contract.mjs; then fail=1; fi

echo
echo "── 6. 插件状态所有权与组件入口边界 ──"
if ! node tools/check-plugin-boundaries.mjs; then fail=1; fi

echo
if [ "$fail" -eq 0 ]; then echo "全部通过"; else echo "有违规项，见上"; fi
exit $fail
