#!/usr/bin/env bash
# 架构守卫：职责边界、逐项体积基线、权限与真实双内核桥契约。
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
fail=0

echo "── 1. 跨模块生产源码与单向依赖 ──"
if ! node tools/check-module-boundaries.mjs; then fail=1; fi

echo
echo "── 2. 生产源码体积与注释策略 ──"
if ! node tools/check-source-policy.mjs; then fail=1; fi

echo
echo "── 3. 权限三件套一致性 ──"
for permission in CAMERA RECORD_AUDIO; do
  if grep -q "android.permission.$permission" app/src/main/AndroidManifest.xml; then
    echo "  ✓ $permission 已声明"
  else
    echo "  ✗ 清单缺 $permission"; fail=1
  fi
done
for cb in onPermissionRequest onShowFileChooser onGeolocationPermissionsShowPrompt onJsAlert; do
  if grep -q "$cb" web/host/src/system/java/com/dsh/android/web/SystemWebHost.kt; then
    echo "  ✓ system 实现 $cb"
  else
    echo "  ✗ system 缺 $cb"; fail=1
  fi
done

echo
echo "── 4. 双内核真实桥协议与标注行为覆盖 ──"
if ! node tools/test-bridge-contract.mjs; then fail=1; fi

echo
echo "── 5. 插件状态所有权与组件入口边界 ──"
if ! node tools/check-plugin-boundaries.mjs; then fail=1; fi

echo
if [ "$fail" -eq 0 ]; then echo "全部通过"; else echo "有违规项，见上"; fi
exit "$fail"
