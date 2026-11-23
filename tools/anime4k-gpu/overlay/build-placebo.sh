#!/usr/bin/env bash
# Build libplacebo 7.349.0 (LGPL-2.1-or-later) and its headers for Android.
# Usage: ANDROID_NDK_HOME=/path/to/ndk/29... bash build-placebo.sh OUTPUT_DIR [arm64-v8a]
# OUTPUT_DIR/{lib,include}; use -I.../include -L.../lib -lplacebo.
# Host: Linux x86_64, Python 3, curl, unzip, Meson >=1.2, Ninja, sha256sum.
# Build-time generators: glad2 (MIT/Apache-2.0), Jinja2 and MarkupSafe (BSD-3-Clause).
set -euo pipefail
out=${1:?usage: build-placebo.sh OUTPUT_DIR [arm64-v8a]}
abi=${2:-arm64-v8a}
: "${ANDROID_NDK_HOME:=/home/dcat/Android/Sdk/ndk/29.0.14206865}"
case "$abi" in
  arm64-v8a) triple=aarch64-linux-android; cpu_family=aarch64; cpu=aarch64 ;;
  *) echo "Unsupported ABI: $abi (only arm64-v8a is currently validated)" >&2; exit 2 ;;
esac
ndk=$(cd "$ANDROID_NDK_HOME" && pwd)
tools="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
test -x "$tools/${triple}21-clang"
mkdir -p "$out"
out=$(cd "$out" && pwd)
cache=${PLACEBO_DOWNLOAD_CACHE:-/tmp/jellyfin-placebo-downloads}
mkdir -p "$cache" "$out/work" "$out/python"
fetch() {
  local url=$1 path=$2 hash=$3
  if ! echo "$hash  $path" | sha256sum -c --status 2>/dev/null; then
    curl -fL --retry 3 "$url" -o "$path.part"
    echo "$hash  $path.part" | sha256sum -c --status
    mv "$path.part" "$path"
  fi
}
fetch 'https://deb.debian.org/debian/pool/main/libp/libplacebo/libplacebo_7.349.0.orig.tar.gz' "$cache/libplacebo-7.349.0.tar.gz" 79120e685a1836344b51b13b6a5661622486a84e4d4a35f6c8d01679a20fbc86
wheel() {
 local name=$1 version=$2 filename=$3 hash=$4 path="$cache/$3"
 if ! echo "$hash  $path" | sha256sum -c --status 2>/dev/null; then
   local url
   url=$(python3 - "$name" "$version" "$filename" <<'PY'
import json, sys, urllib.request
with urllib.request.urlopen('https://pypi.org/pypi/' + sys.argv[1] + '/' + sys.argv[2] + '/json') as r:
    data = json.load(r)
print(next(x['url'] for x in data['urls'] if x['filename'] == sys.argv[3]))
PY
)
   fetch "$url" "$path" "$hash"
 fi
 unzip -oq "$path" -d "$out/python"
}
wheel glad2 2.0.8 glad2-2.0.8-py3-none-any.whl cfe84018233043554710aa747d0bf4be55015154f8bcd451765d1984bcc9d70b
wheel Jinja2 3.1.6 jinja2-3.1.6-py3-none-any.whl 85ece4451f492d0c13c5dd7c13a64681a86afae63a5f347908daf103ce6d2f67
# The pure Python implementation in this wheel is portable to other host Python versions.
wheel MarkupSafe 3.0.3 markupsafe-3.0.3-cp314-cp314-manylinux2014_x86_64.manylinux_2_17_x86_64.manylinux_2_28_x86_64.whl 457a69a9577064c05a97c41f4e65148652db078a3a509039e64d3467b9e7ef97
src="$out/work/libplacebo-v7.349.0"
if [ ! -f "$src/meson.build" ]; then tar -xzf "$cache/libplacebo-7.349.0.tar.gz" -C "$out/work"; fi
# GLES 3.1 compute support and storage-image binding/allocation on Adreno.
changes=$(cd "$(dirname "$0")" && pwd)/libplacebo-gles.patch
if ! patch --silent --dry-run --reverse --force -d "$src" -p1 < "$changes"; then
    patch --forward --batch -d "$src" -p1 < "$changes"
fi
cat > "$out/work/android.cross" <<CROSS
[binaries]
c = '$tools/${triple}21-clang'
cpp = '$tools/${triple}21-clang++'
ar = '$tools/llvm-ar'
strip = '$tools/llvm-strip'
[host_machine]
system = 'android'
cpu_family = '$cpu_family'
cpu = '$cpu'
endian = 'little'
CROSS
export PYTHONPATH="$out/python"
if [ ! -f "$out/work/build/build.ninja" ]; then
 meson setup "$out/work/build" "$src" --cross-file "$out/work/android.cross" --prefix "$out" --libdir lib --default-library shared --wrap-mode nodownload \
  -Dcpp_link_args=-static-libstdc++ -Dvulkan=disabled -Dopengl=enabled -Dgl-proc-addr=enabled -Dd3d11=disabled -Dglslang=disabled -Dshaderc=disabled -Dlcms=disabled -Ddovi=disabled -Dlibdovi=disabled -Dunwind=disabled -Dxxhash=disabled -Ddemos=false -Dtests=false -Dbench=false -Dfuzz=false
fi
meson configure "$out/work/build" -Dcpp_link_args=-static-libstdc++
meson compile -C "$out/work/build"
meson install -C "$out/work/build"
