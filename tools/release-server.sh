#!/bin/bash
# 兼容入口：仅转调分步发布CLI。不会运行Gradle或默认操作网盘。
# 用法：bash tools/release-server.sh help
#       bash tools/release-server.sh prepare --out build/releases/candidate --development
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
if [ "$#" -eq 0 ]; then set -- help; fi
exec node "$SCRIPT_DIR/release/cli.mjs" "$@"
