#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=$1
ndk=${ANDROID_NDK_HOME:-/home/dcat/Android/Sdk/ndk/29.0.14206865}
cc="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
mkdir -p "$out/arm64-v8a"
"$cc" -std=c11 -fPIC -shared -O2 -fvisibility=hidden -Wl,-z,max-page-size=16384 \
    "$here/npu.c" -landroid -lneuralnetworks -llog \
    -o "$out/arm64-v8a/liboverlay_npu.so"
