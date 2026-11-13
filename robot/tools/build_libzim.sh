#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Builds librobotzim.so, the Wikipedia reader of the :wiki process (docs/plans/wikipedia-offline-plan.md §3, job P1), for
# arm64-v8a only: openZIM's libzim with Xapian (the ZIM files' full-text and title indexes), zstd and xz (their
# clusters) and ICU (libzim folds accents with it), all static, behind Robot's own JNI (wiki/src/main/cpp). ICU's data is
# cut to what libzim reads (wiki/src/main/cpp/icu-filter.json), so the library stays a few MB.
#
# Every source is checked by its SHA-256 before anything is unpacked: zstd's matches its release's .sha256 file, ICU's
# its release's SHASUM512.txt (by SHA-512, here by SHA-256), the others were taken from the release files on 6 Nov 2026.
# Meson (libzim's build) is its own release archive, run from source, so nothing is installed. Everything goes into
# wiki/native/ (not in git; LIBZIM_WORK=<dir> puts it elsewhere); the library into wiki/src/main/jniLibs/arm64-v8a/
# (not in git), where Gradle packages it. Without it the module still builds and the reader says it isn't in this build.
# Before the Android build, a host build of ICU (needed to cross-build its data) runs a check that the cut data still
# folds accents as libzim asks ("Lower; NFD; [:M:] remove; NFC").
#
# Reproducible: fixed flags and paths mapped to /robotzim, no build id, SOURCE_DATE_EPOCH fixed; BUILT_SHA below is
# what this script makes from any directory (it says so if not).
#
# Needs curl, unzip, tar, xz, python3, make, a host C/C++ compiler, pkg-config and the Android SDK's NDK below
# (ANDROID_HOME, default /opt/android-sdk). About 20 minutes with two jobs (LIBZIM_JOBS=<n>); wiki/native/ holds ~1 GB.
#
# Usage: tools/build_libzim.sh          fetch, check and build
#        tools/build_libzim.sh --clean  delete wiki/native/
set -euo pipefail
# A copy goes to a new file renamed into place, never written into the old one: a hard link to the old file elsewhere
# (a worktree made by wt-local.sh) keeps its bytes, and a failed copy leaves the old file. cp_new SRC... DEST (a file, or
# a folder that exists).
cp_new() {
  local d="${!#}" s t tmp
  for s in "${@:1:$#-1}"; do
    t=$d; [ -d "$d" ] && t="$d/${s##*/}"
    # A hidden name (.NAME.new.PID): one left by a run cut short is never packaged (AGP skips dotfiles).
    [[ $t == */* ]] && tmp="${t%/*}/.${t##*/}.new.$$" || tmp=".$t.new.$$"
    cp -- "$s" "$tmp" || { rm -f -- "$tmp"; return 1; }
    mv -f -- "$tmp" "$t" || return 1
  done
}
HERE="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${LIBZIM_WORK:-$HERE/wiki/native}"
OUT="$HERE/wiki/src/main/jniLibs/arm64-v8a"
CPP="$HERE/wiki/src/main/cpp"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
NDK_VERSION=27.1.12297006
API=34 # robotMinSdk
NDK="$SDK/ndk/$NDK_VERSION"
JOBS="${LIBZIM_JOBS:-2}"

ZSTD_V=1.5.7; ZSTD_SHA=eb33e51f49a15e023950cd7825ca74a4a2b43db8354825ac24fc1b7ee09e6fa3
XZ_V=5.8.1; XZ_SHA=507825b599356c10dca1cd720c9d0d0c9d5400b9de300af00e4d1ea150795543
ICU_V=77_1; ICU_SHA=588e431f77327c39031ffbb8843c0e3bc122c211374485fa87dc5f3faff24061
ICU_DATA_SHA=1e08bfafa442260ccabf9a872d4eab12de813d42b90769df056bab032b37e1d3
XAPIAN_V=1.4.29; XAPIAN_SHA=c55c9bc8613ad3ec2c218eafca088c218ab7cddcba7ef08f3af0e542f4e521bc
LIBZIM_V=9.3.0; LIBZIM_SHA=f95f7ce51077ee471f48aa40b4149c4c1c55827bb3527aee687bdb5e9dab671f
MESON_V=1.6.1; MESON_SHA=1eca49eb6c26d58bbee67fd3337d8ef557c0804e30a6d16bfdf269db997464de
# What this script builds, from any directory (verify_apk.sh refuses anything else in a release).
BUILT_SHA=3e106b760ca83deac330876dba1b7e24f4e81ad61d411002eee0d7ec2f80651a

sha() { sha256sum "$1" | cut -d' ' -f1; }
check() { [ "$(sha "$1")" = "$2" ] || { echo "SHA-256 MISMATCH $1: $(sha "$1") != $2" >&2; exit 1; }; echo "ok  $(basename "$1")"; }
fetch() { [ -f "$WORK/src/$1" ] || curl -sSfL -o "$WORK/src/$1" "$2"; check "$WORK/src/$1" "$3"; }

if [ "${1:-}" = "--clean" ]; then rm -rf "$WORK"; echo "deleted $WORK"; exit 0; fi
[ -d "$NDK" ] || { echo "no NDK $NDK_VERSION in $SDK" >&2; exit 1; }
mkdir -p "$WORK/src"

fetch zstd-$ZSTD_V.tar.gz https://github.com/facebook/zstd/releases/download/v$ZSTD_V/zstd-$ZSTD_V.tar.gz $ZSTD_SHA
fetch xz-$XZ_V.tar.gz https://github.com/tukaani-project/xz/releases/download/v$XZ_V/xz-$XZ_V.tar.gz $XZ_SHA
fetch icu4c-$ICU_V-src.tgz https://github.com/unicode-org/icu/releases/download/release-${ICU_V/_/-}/icu4c-$ICU_V-src.tgz $ICU_SHA
fetch icu4c-$ICU_V-data.zip https://github.com/unicode-org/icu/releases/download/release-${ICU_V/_/-}/icu4c-$ICU_V-data.zip $ICU_DATA_SHA
fetch xapian-core-$XAPIAN_V.tar.xz https://oligarchy.co.uk/xapian/$XAPIAN_V/xapian-core-$XAPIAN_V.tar.xz $XAPIAN_SHA
fetch libzim-$LIBZIM_V.tar.xz https://download.openzim.org/release/libzim/libzim-$LIBZIM_V.tar.xz $LIBZIM_SHA
fetch meson-$MESON_V.tar.gz https://github.com/mesonbuild/meson/releases/download/$MESON_V/meson-$MESON_V.tar.gz $MESON_SHA

S="$WORK/s"; B="$WORK/b"; P="$WORK/arm64"; H="$WORK/host"
rm -rf "$S" "$B" "$P" "$H"
mkdir -p "$S" "$B" "$P" "$H"
for a in zstd-$ZSTD_V.tar.gz xz-$XZ_V.tar.gz icu4c-$ICU_V-src.tgz xapian-core-$XAPIAN_V.tar.xz libzim-$LIBZIM_V.tar.xz meson-$MESON_V.tar.gz; do
  tar xf "$WORK/src/$a" -C "$S"
done
rm -rf "$S/icu/source/data"
unzip -q "$WORK/src/icu4c-$ICU_V-data.zip" -d "$S/icu/source"
[ -f "$S/icu/source/data/Makefile.in" ] || { echo "ICU data archive not laid out as expected" >&2; exit 1; }
# Robot's one change to libzim: a descriptor is dup'd, never reopened by name (the :wiki process may not open Robot's files).
"$HERE/tools/libzim_fd.sh" patch "$S/libzim-$LIBZIM_V" "$B/fdcheck"
"$HERE/tools/libzim_fd.sh" check "$S/libzim-$LIBZIM_V" "$B/fdcheck" || { echo "libzim's descriptor check failed" >&2; exit 1; }

export SOURCE_DATE_EPOCH=1767225600 LC_ALL=C TZ=UTC
export ICU_DATA_FILTER_FILE="$CPP/icu-filter.json"
MAP="-ffile-prefix-map=$WORK=/robotzim -ffile-prefix-map=$HERE=/robot"

# --- ICU for the host: its tools build the cut data for Android, and its check proves the data is enough. ------------
( cd "$H" && CFLAGS="-O2" CXXFLAGS="-O2 -std=c++17" "$S/icu/source/configure" -q --disable-shared --enable-static \
    --with-data-packaging=static --disable-tests --disable-samples --disable-extras --disable-icuio --disable-layoutex \
  && make -s -j"$JOBS" ) > "$WORK/host.log" 2>&1 || { tail -30 "$WORK/host.log"; exit 1; }
c++ -std=c++17 -O1 -I"$S/icu/source/common" -I"$S/icu/source/i18n" "$CPP/icu_check.cpp" \
  -L"$H/lib" -licui18n -licuuc -licudata -lpthread -ldl -o "$H/icu_check"
"$H/icu_check"

# --- The Android toolchain. --------------------------------------------------------------------------------------------
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CC="$TC/aarch64-linux-android$API-clang" CXX="$TC/aarch64-linux-android$API-clang++"
export AR="$TC/llvm-ar" RANLIB="$TC/llvm-ranlib" STRIP="$TC/llvm-strip" NM="$TC/llvm-nm"
FLAGS="-O2 -fPIC -ffunction-sections -fdata-sections $MAP"
export CFLAGS="$FLAGS" CXXFLAGS="$FLAGS" CPPFLAGS="-I$P/include" LDFLAGS="-L$P/lib"
export PKG_CONFIG_LIBDIR="$P/lib/pkgconfig" PKG_CONFIG_PATH=""
HOST=aarch64-linux-android
step() { echo "--- $1"; }

step zstd
make -s -C "$S/zstd-$ZSTD_V/lib" -j"$JOBS" libzstd.a ZSTD_LEGACY_SUPPORT=0 > "$WORK/zstd.log" 2>&1
make -s -C "$S/zstd-$ZSTD_V/lib" install-static install-includes install-pc PREFIX="$P" ZSTD_LEGACY_SUPPORT=0 >> "$WORK/zstd.log" 2>&1

step xz
( cd "$S/xz-$XZ_V" && ./configure -q --host=$HOST --prefix="$P" --disable-shared --enable-static --with-pic \
    --disable-xz --disable-xzdec --disable-lzmadec --disable-lzmainfo --disable-lzma-links --disable-scripts --disable-doc \
    --disable-nls --disable-threads && make -s -j"$JOBS" && make -s install ) > "$WORK/xz.log" 2>&1 || { tail -30 "$WORK/xz.log"; exit 1; }

step icu
mkdir -p "$B/icu"
( cd "$B/icu" && "$S/icu/source/configure" -q --host=$HOST --with-cross-build="$H" --prefix="$P" --disable-shared \
    --enable-static --with-data-packaging=static --disable-tests --disable-samples --disable-extras --disable-icuio \
    --disable-layoutex --disable-tools CXXFLAGS="$FLAGS -std=c++17" && make -s -j"$JOBS" && make -s install ) \
  > "$WORK/icu.log" 2>&1 || { tail -30 "$WORK/icu.log"; exit 1; }

step xapian
( cd "$S/xapian-core-$XAPIAN_V" && ./configure -q --host=$HOST --prefix="$P" --disable-shared --enable-static --with-pic \
    --disable-backend-remote --disable-documentation --disable-sse && make -s -j"$JOBS" && make -s install ) \
  > "$WORK/xapian.log" 2>&1 || { tail -30 "$WORK/xapian.log"; exit 1; }

step libzim
cat > "$B/cross.txt" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
pkg-config = 'pkg-config'
[properties]
pkg_config_libdir = '$P/lib/pkgconfig'
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF
# USE_MMAP off: the :wiki process is isolated and reads the file through the descriptor it is handed, never maps it.
( python3 "$S/meson-$MESON_V/meson.py" setup "$B/zim" "$S/libzim-$LIBZIM_V" --cross-file "$B/cross.txt" --prefix="$P" \
    --libdir=lib --buildtype=release --default-library=static -Dstatic-linkage=true -Dexamples=false -Dtests=false \
    -Ddoc=false -Dwith_xapian=true -DUSE_MMAP=false -Db_staticpic=true \
  && ninja -C "$B/zim" -j"$JOBS" install ) > "$WORK/libzim.log" 2>&1 || { tail -30 "$WORK/libzim.log"; exit 1; }

step librobotzim.so
"$CXX" -std=c++17 $FLAGS -fvisibility=hidden -I"$P/include" -c "$CPP/robotzim_jni.cpp" -o "$B/robotzim_jni.o"
"$CXX" -shared -o "$B/librobotzim.so" "$B/robotzim_jni.o" -L"$P/lib" -lzim -lxapian -licui18n -licuuc -licudata -llzma \
  -lzstd -lz -lm -ldl -static-libstdc++ -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,--version-script="$CPP/robotzim.map" \
  -Wl,-z,max-page-size=16384 -Wl,--build-id=none -Wl,-z,noexecstack -Wl,--no-undefined -Wl,--hash-style=gnu
"$STRIP" --strip-unneeded "$B/librobotzim.so"
mkdir -p "$OUT"
cp_new "$B/librobotzim.so" "$OUT/librobotzim.so"
got=$(sha "$OUT/librobotzim.so")
echo "librobotzim.so $(stat -c %s "$OUT/librobotzim.so") bytes, sha256 $got"
[ "$got" = "$BUILT_SHA" ] && echo "ok  the pinned build" || echo "NOT the pinned build ($BUILT_SHA): a source, flag or tool changed"
