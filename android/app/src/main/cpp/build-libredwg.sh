#!/usr/bin/env bash
# Compila LibreDWG (GPLv3) como biblioteca estática para Android.
# Uso: ANDROID_NDK_HOME=... ./build-libredwg.sh [abi ...]   (por defecto: arm64-v8a)
set -euo pipefail
VERSION=0.13.3
API=26
HERE="$(cd "$(dirname "$0")" && pwd)"
ABIS=("${@:-arm64-v8a}")
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
[ -d "$NDK" ] || { echo "Defina ANDROID_NDK_HOME"; exit 1; }
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
WORK="${WORK:-$HERE/.build}"
mkdir -p "$WORK"
cd "$WORK"
if [ ! -d "libredwg-$VERSION" ]; then
  curl -sSfL -o src.tar.xz "https://github.com/LibreDWG/libredwg/releases/download/$VERSION/libredwg-$VERSION.tar.xz"
  tar xf src.tar.xz
fi
for ABI in "${ABIS[@]}"; do
  case "$ABI" in
    arm64-v8a)   HOST=aarch64-linux-android;   CCP=aarch64-linux-android$API ;;
    armeabi-v7a) HOST=arm-linux-androideabi;   CCP=armv7a-linux-androideabi$API ;;
    x86_64)      HOST=x86_64-linux-android;    CCP=x86_64-linux-android$API ;;
    *) echo "ABI no soportada: $ABI"; exit 1 ;;
  esac
  OUT="$HERE/prebuilt/$ABI"
  rm -rf "build-$ABI" && cp -r "libredwg-$VERSION" "build-$ABI"
  ( cd "build-$ABI"
    ./configure --host="$HOST" \
      CC="$TC/bin/$CCP-clang" AR="$TC/bin/llvm-ar" RANLIB="$TC/bin/llvm-ranlib" STRIP="$TC/bin/llvm-strip" \
      CFLAGS="-O2 -fPIC -ffunction-sections -fdata-sections -DNDEBUG" \
      --disable-shared --enable-static --disable-bindings --disable-python --disable-json \
      --disable-werror --enable-release > configure.log 2>&1 || { tail -50 configure.log; exit 1; }
    make -C src -j"$(nproc)" > make.log 2>&1 || { tail -80 make.log; exit 1; }
  )
  rm -rf "$OUT" && mkdir -p "$OUT/lib" "$OUT/include"
  cp "build-$ABI/src/.libs/libredwg.a" "$OUT/lib/"
  # Cabeceras públicas + internas necesarias para dwg_write_dxf (out_dxf.h, bits.h, config.h...)
  cp "build-$ABI"/include/*.h "$OUT/include/"
  cp "build-$ABI"/src/*.h "$OUT/include/"
  cp "build-$ABI"/src/config.h "$OUT/include/" 2>/dev/null || true
  echo "LibreDWG $VERSION listo para $ABI → $OUT"
done
