#!/usr/bin/env bash
# CityGen 构建脚本（无 Gradle / 无 Loom）
#
# 26.x 的游戏 jar 不再混淆，version JSON 里也没有 client_mappings，
# Yarn 与 intermediary 都停在 1.21.11 —— Loom 那条路完全走不通。
# 所以这里直接拿 client.jar 当 classpath 调 javac，零 remap、零 refmap。
set -euo pipefail

# javac 的中文报错在 GBK 下是乱码，grep 常常捞不到，于是一次失败会被当成成功。
# 这里显式兜底：任何一步非零退出都打印醒目提示。
trap 'echo ""; echo "!!!!!! BUILD FAILED !!!!!!"; exit 1' ERR

ROOT="$(cd "$(dirname "$0")" && pwd)"
# ===== 本机配置 =====
# 以下路径均可通过环境变量覆盖，例如：
#   CITYGEN_JDK="C:/path/to/jdk-25/bin" CITYGEN_TOOLS=/d/your/tools ./build.sh
#
# CITYGEN_JDK   : JDK 25 所在目录（含 javac.exe / jar.exe）
# CITYGEN_TOOLS : 辅助工具目录，其中 cp.txt 是编译 classpath（分号分隔的单行文件）
#                 coreclasses / stubclasses 是编译产物的中转目录（路径须为 ASCII）
#
# 为什么有这些「中转目录」：javac 对含中文/非 ASCII 的 classpath 处理不可靠，
# 项目本身放在中文路径下时，必须把 class 文件中转到 ASCII 路径再参与编译。
# 详见仓库 README 的「已知限制」。
JDK="${CITYGEN_JDK:-C:/Users/sqdcz/AppData/Roaming/.minecraft/runtime/java-runtime-epsilon/bin}"
TOOLS="${CITYGEN_TOOLS:-/d/mc-city-tools}"
CP="$(cat "$TOOLS/cp.txt")"

echo "=== 1/4 编译规划核心 ==="
rm -rf "$ROOT/core/out/classes"
mkdir -p "$ROOT/core/out/classes"
"$JDK/javac.exe" -encoding UTF-8 -d "$ROOT/core/out/classes" \
    $(find "$ROOT/core/src" -name '*.java')

echo "=== 2/4 编译模组 ==="
# 规划核心的 class 必须先搬到纯 ASCII 路径：javac 的 classpath 里一旦出现中文路径，
# Git Bash 转参数时会被打乱，表现成「找不到符号: 类 CityPlan」
CORE_ASCII="$TOOLS/coreclasses"
rm -rf "$CORE_ASCII"
cp -r "$ROOT/core/out/classes" "$CORE_ASCII"

# HowToGo 的 API 编译桩：只给 javac 看签名，**不进 jar**（运行期由本体提供）
# 源码和产物都得走 ASCII 路径，中文路径 javac 会读不到
STUB_SRC="$TOOLS/stubsrc"
# 注意：进 classpath 的目录必须是 Windows 风格（D:/...），
# Git Bash 的 /d/... 风格在分号分隔的列表里不会被转换
STUB_OUT="D:/mc-city-tools/stubclasses"
rm -rf "$STUB_OUT"
mkdir -p "$STUB_OUT"
rm -rf "$STUB_SRC"
cp -r "$ROOT/mod/stub-java" "$STUB_SRC"
"$JDK/javac.exe" -encoding UTF-8 -d "$STUB_OUT" \
    $(find "$STUB_SRC" -name '*.java')

rm -rf "$ROOT/build/classes"
mkdir -p "$ROOT/build/classes"
"$JDK/javac.exe" -encoding UTF-8 -Xlint:all -d "$ROOT/build/classes" \
    -cp "$CP;D:/mc-city-tools/coreclasses;$STUB_OUT" \
    $(find "$ROOT/mod/src/main/java" -name '*.java' -not -path '*stub-java*')

echo "=== 3/4 合并资源 ==="
# 规划核心（citygen 包）没有单独发 jar，直接并进同一个产物，省得再维护一份依赖
cp -r "$ROOT/core/out/classes/." "$ROOT/build/classes/"
cp -r "$ROOT/mod/src/main/resources/." "$ROOT/build/classes/"

echo "=== 4/4 打包 ==="
rm -f "$ROOT/build/citygen-0.1.0.jar"
"$JDK/jar.exe" --create --file "$ROOT/build/citygen-0.1.0.jar" -C "$ROOT/build/classes" .

echo
echo "=== jar 内容 ==="
"$JDK/jar.exe" tf "$ROOT/build/citygen-0.1.0.jar" | grep -v '/$' | sort
ls -la "$ROOT/build/citygen-0.1.0.jar"

# 产物自检：mixin 清单里写的每个类都必须真在 jar 里，
# 否则运行时会静默不生效（require=0）——这个坑踩过一次
echo
echo "=== 产物自检 ==="
"$JDK/jar.exe" tf "$ROOT/build/citygen-0.1.0.jar" > /tmp/citygen-jar-list.txt
for m in $(grep -oE '"[A-Za-z]+Mixin"' "$ROOT/build/classes/citygen.mixins.json" | tr -d '"'); do
    if ! grep -q "mixin/$m.class" /tmp/citygen-jar-list.txt; then
        echo "MISSING MIXIN: $m"
        exit 1
    fi
    echo "  ok  $m"
done
