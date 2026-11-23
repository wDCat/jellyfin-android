#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=$1
deps=$2
bash "$here/build-placebo.sh" "$deps" arm64-v8a
mkdir -p "$out/arm64-v8a"
cp -L "$deps/lib/libplacebo.so" "$out/arm64-v8a/libplacebo.so"
ndk=${ANDROID_NDK_HOME:-/home/dcat/Android/Sdk/ndk/29.0.14206865}
cc="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android30-clang"
"$cc" -std=c11 -fPIC -shared -O2 -fvisibility=hidden -Wl,-z,max-page-size=16384 \
    -I"$deps/include" "$here/placebo.c" -L"$deps/lib" -lplacebo -lEGL -lGLESv3 -llog \
    -o "$out/arm64-v8a/liboverlay_gpu.so"
sources="$3/licenses/libplacebo"
mkdir -p "$sources"
cp "$deps/work/libplacebo-v7.349.0/LICENSE" "$here/libplacebo-gles.patch" "$here/build-placebo.sh" "$sources/"
cp "${PLACEBO_DOWNLOAD_CACHE:-/tmp/jellyfin-placebo-downloads}/libplacebo-7.349.0.tar.gz" "$sources/"
