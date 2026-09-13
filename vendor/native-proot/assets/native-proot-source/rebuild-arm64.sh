#!/usr/bin/env bash
# Offline rebuild/relink recipe for the supplied PRoot + talloc sources.
# Based on DroidRunner v1.0.0 build-proot.sh (GPL-2.0-only), itself adapted from
# Godot GABE (MIT); upstream source/script notices are retained in the archive.
# Produces a new build, NOT a promise of byte-identical reproduction of the donor.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
: "${ANDROID_NDK_ROOT:?Set ANDROID_NDK_ROOT to Android NDK r29}"
HOST_TAG="${HOST_TAG:-linux-x86_64}"
TOOLS="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG/bin"
CC="$TOOLS/aarch64-linux-android28-clang"
AR="$TOOLS/llvm-ar"
[ -x "$CC" ] && [ -x "$AR" ] || { echo "Missing NDK ARM64 compiler" >&2; exit 1; }
[ "$#" -eq 1 ] || { echo "Usage: ANDROID_NDK_ROOT=... bash rebuild-arm64.sh NEW_OUTPUT_DIR" >&2; exit 2; }
OUT="$1"
[ ! -e "$OUT" ] || { echo "Output must not exist; refusing to replace it" >&2; exit 2; }
SOURCE="$HERE/droidrunner-v1.0.0-source.tar.gz"
echo "35113c4e20d4e00a433d99cbc99de0ce47a16234674592f356d2a64e105f43a6  $SOURCE" | sha256sum -c -
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
# The archive is pinned and verified before extraction.
tar -xzf "$SOURCE" -C "$WORK"
SRC="$WORK/source-v1.0.0"
echo "dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd  $SRC/talloc/talloc-2.4.3.tar.gz" | sha256sum -c -
tar -xzf "$SRC/talloc/talloc-2.4.3.tar.gz" -C "$WORK"
mkdir -p "$WORK/talloc/lib" "$WORK/talloc/include"
(
  cd "$WORK/talloc-2.4.3"
  CC="$CC" ./configure build --disable-rpath --disable-python \
    --cross-compile --cross-answers="$HERE/talloc-answers.txt"
  "$AR" rcs "$WORK/talloc/lib/libtalloc.a" bin/default/talloc*.o
  cp talloc.h "$WORK/talloc/include/"
)
patch -d "$SRC/proot/proot" -p1 < "$SRC/droidrunner/patches/string-header.patch"
CPPFLAGS="-I$WORK/talloc/include" LDFLAGS="-L$WORK/talloc/lib" \
  make -j"${JOBS:-$(getconf _NPROCESSORS_ONLN)}" -C "$SRC/proot/proot/src" CC="$CC" CROSS_COMPILE="$TOOLS/llvm-" \
  PROOT_UNBUNDLE_LOADER=1 HAS_LOADER_32BIT=1 V=1 proot
mkdir -p "$OUT"
cp "$SRC/proot/proot/src/proot" "$OUT/libproot.so"
cp "$SRC/proot/proot/src/loader/loader" "$OUT/libproot-loader.so"
cp "$SRC/proot/proot/src/loader/loader-m32" "$OUT/libproot-loader32.so"
sha256sum "$OUT"/*.so
printf '%s\n' "Rebuilt files must be reviewed and tested before replacing pinned artifacts."
