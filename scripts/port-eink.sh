#!/usr/bin/env bash
# 将 E-Ink Compose 模块与宿主桥接层复制到目标 legado 系上游仓库。
# 用法: ./scripts/port-eink.sh <目标仓库路径> [目标分支名]
# 详见 docs/eink-porting.md（移植契约与差异表）。
set -euo pipefail

SRC_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST_ROOT="${1:?用法: port-eink.sh <目标仓库路径> [目标分支名]}"
BRANCH="${2:-port/eink}"

echo "==> 源: $SRC_ROOT"
echo "==> 目标: $DEST_ROOT (分支 $BRANCH)"

cd "$DEST_ROOT"
git rev-parse --is-inside-work-tree >/dev/null
git checkout -B "$BRANCH"

# 1. 模块树（零改动整体复制）
mkdir -p modules
rm -rf modules/eink
cp -r "$SRC_ROOT/modules/eink" modules/eink
rm -rf modules/eink/build

# 2. 宿主桥接层与入口（需按目标引擎适配，见 docs/eink-porting.md §3）
EINK_PKG="app/src/main/java/io/legado/app/eink"
mkdir -p "$EINK_PKG/reader"
cp -r "$SRC_ROOT/$EINK_PKG/bridge" "$EINK_PKG/bridge"
cp "$SRC_ROOT/$EINK_PKG/EInkMainActivity.kt" "$EINK_PKG/"
cp "$SRC_ROOT/$EINK_PKG/reader/ReaderPageCanvas.kt" "$EINK_PKG/reader/"

echo "==> 复制完成。剩余人工步骤："
echo "    1. settings.gradle 增加 include ':modules:eink'"
echo "    2. app/build.gradle 增加 implementation project(':modules:eink') + compose/lifecycle/coroutines/glide 依赖（见 docs §2）"
echo "    3. AndroidManifest.xml 注册 .eink.EInkMainActivity（见 docs §2 步骤 6）"
echo "    4. 按差异表适配 bridge/ 与入口接线（docs §3，重点：SearchModel.CallBack 补 2 个 override、searchBookAwait 三参 filter）"
echo "    5. 构建验证：./gradlew :modules:eink:assembleDebug assembleDebug"
