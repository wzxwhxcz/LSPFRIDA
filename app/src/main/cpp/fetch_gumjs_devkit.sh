#!/data/data/com.termux/files/usr/bin/bash
# 或在任意 Linux/终端环境运行：bash fetch_gumjs_devkit.sh <版本号>
# 用途：下载 frida-gumjs devkit 静态库并放入 cpp 目录
set -e

VERSION="${1:-16.5.9}"
BASE="https://github.com/frida/frida/releases/download/${VERSION}"
DEST="$(dirname "$0")"

for ABI in arm64 arm; do
    PKG="frida-gumjs-devkit-${VERSION}-android-${ABI}.tar.xz"
    DIR="${DEST}/frida-gumjs-devkit"
    [ "$ABI" = "arm64" ] && GRADLE_ABI="arm64-v8a" || GRADLE_ABI="armeabi-v7a"
    OUT="${DIR}/${GRADLE_ABI}"

    echo "==> ${PKG}"
    curl -L -o "/tmp/${PKG}" "${BASE}/${PKG}"
    mkdir -p "${OUT}"
    tar -xJf "/tmp/${PKG}" -C "${OUT}"
    rm -f "/tmp/${PKG}"
    ls -la "${OUT}"
done

echo "完成。devkit 已就位于 app/src/main/cpp/frida-gumjs-devkit/"