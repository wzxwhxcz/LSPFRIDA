#!/usr/bin/env bash
# LSPFRIDA zygote gate + injector builder.
#
#   bash tools/zygote/build.sh            # host x86_64: gate + injector + tests in out/host
#   bash tools/zygote/build.sh --android  # NDK 28 arm64: lspfrifa-gate.elf + lspfrifa-zygote in out/arm64
#
# No CMake.  Direct clang/gcc calls, mirrors the frida-core helper flags
# (src/linux/helpers/meson.build lines 70-105) adapted to this machine:
#   -mno-memcpy / -fno-optimize-strlen are NOT supported by host gcc 13 or
#   NDK clang 28, they are dropped (they only guided builtin selection and we
#   also pass -fno-builtin -ffreestanding).
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SRC=$HERE/src
OUT_HOST=$HERE/out/host
OUT_ANDROID=$HERE/out/arm64

JDK=${JDK_HOME:-$HOME/android-sdk/jdk-25}
NDK=${ANDROID_NDK_HOME:-$HOME/android-sdk/ndk/28.2.13676358}
NDK_PREBUILT=$NDK/toolchains/llvm/prebuilt/linux-x86_64
NDK_BIN=$NDK_PREBUILT/bin
ANDROID_CLANG=${ANDROID_CLANG:-$NDK_BIN/aarch64-linux-android28-clang}
# Host gate needs clang (naked x86_64 attribute); use the NDK clang with a
# host triple, since the system has no clang installed.
HOST_CLANG=${HOST_CLANG:-$NDK_BIN/clang}
HOST_CC=${HOST_CC:-gcc}
HOST_TRIPLE=${HOST_TRIPLE:-x86_64-unknown-linux-gnu}

BUILD_ANDROID=0
for arg in "$@"; do
  case "$arg" in
    --android) BUILD_ANDROID=1 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

log() { printf '[build] %s\n' "$*"; }

# ---------------------------------------------------------------------------
# gate payload flags (frozen by design §3.1, ported from frida meson.build)
# ---------------------------------------------------------------------------
GATE_COMMON_FLAGS=(
  -std=gnu99 -O2
  -nostdlib -shared -fPIC
  -fvisibility=hidden
  -fno-builtin
  -fno-stack-protector
  -fno-asynchronous-unwind-tables
  -fno-function-sections
  -fno-data-sections
  -ffreestanding
  -Wall -Wextra
  -Wl,-T,"$SRC/gate.lds"
  -Wl,--build-id=none
  -Wl,-z,noexecstack
)

verify_gate() {
  local elf=$1
  local arch=$2
  log "verifying $elf"
  file "$elf"
  echo "--- dynamic dependencies (must be none) ---"
  if readelf -d "$elf" | grep -q NEEDED; then
    echo "FAIL: $elf has DT_NEEDED" >&2
    readelf -d "$elf" | grep NEEDED >&2
    return 1
  fi
  readelf -d "$elf" | grep -E 'NEEDED|SONAME' || echo "  (no DT_NEEDED)"
  echo "--- exported entry symbols (dynsym) ---"
  readelf --dyn-syms -W "$elf" | grep -E 'lsp_gate_replacement_(setcontext|setargv0)' || {
    echo "FAIL: entry symbols missing" >&2; return 1; }
  local count
  count=$(readelf --dyn-syms -W "$elf" | grep -cE 'GLOBAL +DEFAULT +[0-9]+ lsp_gate_replacement_(setcontext|setargv0)$' || true)
  if [ "$count" -ne 2 ]; then
    echo "FAIL: expected exactly 2 exported entry symbols, got $count" >&2
    return 1
  fi
  echo "--- undefined symbols (must be none) ---"
  if readelf --dyn-syms -W "$elf" | awk '$7 == "UND" && $8 != "" {print}' | grep -q .; then
    echo "FAIL: gate has undefined dynamic symbols" >&2
    readelf --dyn-syms -W "$elf" | awk '$7 == "UND" && $8 != "" {print}' >&2
    return 1
  fi
  echo "  (none)"
  echo "--- relocations (payload must be position independent, none needed) ---"
  if readelf -rW "$elf" | grep -q 'R_'; then
    echo "FAIL: gate has relocations; raw payload copy would be invalid" >&2
    readelf -rW "$elf" | head -20 >&2
    return 1
  fi
  echo "  (none)"
  echo "--- payload segment layout ---"
  readelf -lW "$elf" | grep -A1 'LOAD'
  # the template the injector copies must fit in one page
  local filesz
  filesz=$(readelf -lW "$elf" | awk '$1 == "LOAD" && $7 ~ /E/ {print $5; exit}')
  filesz=${filesz:-0}
  echo "  executable LOAD filesz=${filesz} (hex; must fit one page)"
  local filesz_dec=$((16#${filesz#0x}))
  if [ "$filesz_dec" -gt 4096 ]; then
    echo "FAIL: gate template larger than one page (${filesz} bytes)" >&2
    return 1
  fi
}

build_gate_host() {
  mkdir -p "$OUT_HOST"
  log "gate: host x86_64 (clang: $HOST_CLANG, triple $HOST_TRIPLE)"
  "$HOST_CLANG" --target="$HOST_TRIPLE" \
    "${GATE_COMMON_FLAGS[@]}" \
    -I"$JDK/include" -I"$JDK/include/linux" \
    -o "$OUT_HOST/lspfrifa-gate.elf" "$SRC/gate.c"
  verify_gate "$OUT_HOST/lspfrifa-gate.elf" host
}

build_gate_android() {
  mkdir -p "$OUT_ANDROID"
  log "gate: android arm64 (clang: $ANDROID_CLANG)"
  "$ANDROID_CLANG" \
    "${GATE_COMMON_FLAGS[@]}" \
    -mbranch-protection=bti \
    -Wl,-z,max-page-size=16384 \
    -o "$OUT_ANDROID/lspfrifa-gate.elf" "$SRC/gate.c"
  verify_gate "$OUT_ANDROID/lspfrifa-gate.elf" arm64
}

build_host_programs() {
  mkdir -p "$OUT_HOST"
  local cflags=(-std=gnu11 -O2 -g -Wall -Wextra
    -I"$SRC" -I"$JDK/include" -I"$JDK/include/linux")
  # shellcheck disable=SC2206
  local srcs=(
    "$SRC/inject.c" "$SRC/proc_maps.c" "$SRC/elf_parser.c"
    "$SRC/payload.c" "$SRC/state.c" "$SRC/wire.c"
  )
  for f in "${srcs[@]}"; do
    [ -f "$f" ] || { log "missing $f (injector not written yet)"; return 0; }
  done
  log "injector: host x86_64 (gcc)"
  "$HOST_CC" "${cflags[@]}" -o "$OUT_HOST/lspfrifa-zygote" "${srcs[@]}" -ldl
  log "component tests: host x86_64"
  "$HOST_CC" "${cflags[@]}" -o "$OUT_HOST/component_tests" \
    "$HERE/tests/component_tests.c" \
    "$SRC/proc_maps.c" "$SRC/elf_parser.c" "$SRC/payload.c" "$SRC/state.c" "$SRC/wire.c" -ldl

  # mock zygote fixtures (host E2E, never part of the Android build)
  local mock="$HERE/tests/mock"
  log "mock fixtures: libselinux.so libandroid_runtime.so libstagefright.so"
  "$HOST_CC" -shared -fPIC -Wl,-soname,libselinux.so -o "$mock/libselinux.so" "$mock/selinux_stub.c"
  "$HOST_CC" -shared -fPIC -Wl,-soname,libandroid_runtime.so \
    -o "$mock/libandroid_runtime.so" "$mock/runtime_stub.c" \
    -L"$mock" -lselinux -Wl,-rpath,'$ORIGIN'
  "$HOST_CC" -shared -fPIC -Wl,-soname,libstagefright.so -o "$mock/libstagefright.so" "$mock/stagefright_stub.c"
  log "mock zygote: host x86_64"
  "$HOST_CC" "${cflags[@]}" -o "$OUT_HOST/mock_zygote" "$HERE/tests/mock_zygote.c" -ldl
}

build_android_programs() {
  mkdir -p "$OUT_ANDROID"
  local cflags=(-std=gnu11 -O2 -Wall -Wextra -fPIE -pie -I"$SRC")
  local srcs=(
    "$SRC/inject.c" "$SRC/proc_maps.c" "$SRC/elf_parser.c"
    "$SRC/payload.c" "$SRC/state.c" "$SRC/wire.c"
  )
  for f in "${srcs[@]}"; do
    [ -f "$f" ] || { log "missing $f (injector not written yet)"; return 0; }
  done
  log "injector: android arm64 PIE (clang: $ANDROID_CLANG)"
  "$ANDROID_CLANG" "${cflags[@]}" -o "$OUT_ANDROID/lspfrifa-zygote" "${srcs[@]}"
  file "$OUT_ANDROID/lspfrifa-zygote"
  readelf -h "$OUT_ANDROID/lspfrifa-zygote" | grep -E 'Class|Machine|Type'
}

if [ "$BUILD_ANDROID" -eq 1 ]; then
  build_gate_android
  build_android_programs
  log "android build OK: $OUT_ANDROID"
else
  build_gate_host
  build_host_programs
  log "host build OK: $OUT_HOST"
fi
