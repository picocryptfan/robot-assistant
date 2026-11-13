#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Robot's source offer for the copyleft native code it ships (GPL-3.0 section 6), made once per release.
#
# Robot's APK holds native code built outside Gradle:
#   - libsherpa-onnx-jni.so (statically linking espeak-ng, GPL-3.0-or-later, and the other sources below), built by
#     tools/build_sherpa.sh with Robot's patch to espeak-ng, sherpa-onnx's libonnxruntime.so, and 17 compiled espeak-ng data
#     files: Robot's repository holds only that patch of their source;
#   - librobotmt.so (since 0.6.0: slimt, GPL-2.0-or-later, with ruy, cpuinfo and SentencePiece, and Robot's own wrapper in
#     mt/src/main/cpp), built by tools/build_robotmt.sh from commits it pins;
#   - librobot_llama.so (since 0.4.5, when built: llama.cpp and KleidiAI, MIT and Apache-2.0, and Robot's own bridge in
#     brain/src/main/cpp), built by tools/build_llama.sh from the tag and release it pins;
#   - librobotzim.so (since Wikipedia P1: libzim and Xapian, GPL-2.0-or-later, with Zstandard, liblzma and ICU, and Robot's
#     own JNI in wiki/src/main/cpp), built by tools/build_libzim.sh from the release archives it pins;
#   - Robot to Robot's three (1.5), two prebuilt by others and only taken from pinned files: libsignal_jni.so (libsignal,
#     AGPL-3.0-only, Signal's own build, tools/fetch_libsignal.sh), libtor.so (Tor with OpenSSL, libevent and zlib, Briar's
#     build, BSD-3-Clause, Apache-2.0, zlib) from tools/fetch_link_deps.sh; and liblyrebird.so (lyrebird and its Go modules,
#     GPL-3.0-or-later, BSD and others), which Robot builds itself: tools/build_lyrebird.sh;
#   - three more prebuilt from Maven under Apache-2.0, whose source is named here but not carried: liblitertlm_jni.so,
#     libLiteRtDispatch_GoogleTensor.so and libandroidx.graphics.path.so.
# NATIVE_SOURCES below names the source of every library by file name. `--check-apk` and every run of this script fail on a
# library that has no row (verify_apk.sh runs the first on each APK, the second reads verify_apk.sh's allowlist), so the next
# addition can't be missed; and packing fails if a row's source isn't in the archive.
# So every release attaches the complete source of all of it as one archive, next to the APK:
#
#   robot-<version>-third-party-sources.tar.gz   and   robot-<version>-third-party-sources.tar.gz.sha256
#
# It holds:
#   - sherpa-onnx at the commit of v1.13.8 (11afbd00), as `git archive` of that commit (verified by its commit id);
#   - every source sherpa-onnx's Android build compiles into the library (espeak-ng at ed530aa1, piper-phonemize,
#     kaldi-native-fbank, kaldi-decoder, kaldifst, OpenFst, simple-sentencepiece, JSON for Modern C++, hclust-cpp,
#     Eigen): the very archives sherpa-onnx's CMake files pin, read from that verified source tree and checked against
#     the SHA-256 they pin. Where none of the archive's links can be reached, the script takes the same release from git
#     instead and checks its commit id (pinned below), and MANIFEST.tsv says so;
#   - ONNX Runtime 1.28.2 (the version sherpa-onnx's Android build links; its prebuilt library is csukuangfj's
#     onnxruntime-libs build), as `git archive` of the v1.28.2 tag's commit;
#   - mt/: slimt, ruy, cpuinfo and SentencePiece exactly as tools/build_robotmt.sh takes them (the `git archive` tar of
#     each pinned commit, checked against the SHA-256 that script pins; the pins are read from the script itself);
#   - llama/: llama.cpp and KleidiAI exactly as tools/build_llama.sh takes them (llama.cpp's `git archive` tar of the
#     tag's commit, checked against its pinned SHA-256; KleidiAI's release tarball, or the tag's tar when that can't be
#     downloaded, each checked against its pin; the pins are read from that script);
#   - wiki/: libzim, Xapian, Zstandard, XZ Utils (liblzma), ICU and its data, and Meson (which builds libzim), exactly as
#     tools/build_libzim.sh takes them: their release archives, checked against the SHA-256 that script pins (the pins and
#     the links are read from it);
#   - libsignal/: libsignal at the commit of the tag tools/fetch_libsignal.sh's pins name (`git archive`, checked by its
#     commit id) and everything it builds from, vendored by `cargo vendor --locked` from its own Cargo.lock: crates checked
#     by cargo and again here against the lock's SHA-256, its two git dependencies (Signal's boring, with BoringSSL, and
#     SparsePostQuantumRatchet) by the commit the lock names. crates.tsv lists them. cargo's own layout for offline builds, and
#     built from with the network cut off ("Building libsignal" in the README below says how);
#   - tor/: tor-android's sources jar for the version third_party/link-carriers.sha256 pins (Tor, OpenSSL, libevent, zlib);
#     its SHA-256 pinned below and equal to Maven Central's .sha256. Tor's, OpenSSL's, libevent's and zlib's versions in it
#     are checked to be the ones in the pinned libtor.so;
#   - lyrebird/: lyrebird at the commit tools/build_lyrebird.sh pins, patched as it patches it, with a vendor/ tree of its Go
#     modules, and Go's source. The Go version and each module's h1: hash are read from the pinned liblyrebird.so's own
#     build information. The tree's files are compared with that commit (go.mod and go.sum differ by the patch), each
#     module's zip from proxy.golang.org is checked against its h1:, and each vendored file is compared with that zip.
#     go-modules.tsv lists them;
#   - onionwrapper/: the sources jars of onionwrapper (GPL-3.0-only), from which Robot's TorRuntime.kt adapts Tor's start-up;
#   - robot/: Robot's own native sources and build scripts at this commit (build.gradle, for the min API; mt/src/main/cpp, brain/src/main/cpp,
#     tools/build_robotmt.sh, tools/build_llama.sh, tools/fetch_third_party.sh, tools/build_sherpa.sh,
#     tools/espeak_bounds_check.sh, third_party/patches, wiki/src/main/cpp and tools/build_libzim.sh, and for Robot to Robot link/build.gradle, TorRuntime.kt, the two
#     fetch scripts and their pins), so the archive builds on its own;
#   - README.md (what it is and how each library is built) and MANIFEST.tsv (every file, where it came from, its pin and
#     its SHA-256).
#
# Usage: tools/make_source_bundle.sh [--version 1.0.0] [--out DIR] [--work DIR] [--without-onnxruntime]
#                                    [--without-llama] [--only-link] [--list] [--check-apk APK]
#   --version  defaults to app/build.gradle's versionName
#   --out      defaults to build/source-bundle (gitignored); never commit the archive
#   --work     a scratch folder (default: a new temporary one, deleted at the end)
#   --without-onnxruntime  leaves out ONNX Runtime's source (MIT, which asks for no source; but sherpa-onnx's library is
#              built to require it, so the GPL's "Corresponding Source" is safest with it, the default)
#   --without-llama  leaves out llama.cpp and KleidiAI (MIT and Apache-2.0, which ask for no source): for a release built
#              without librobot_llama.so, or when size matters. The default keeps them, so the archive is the whole
#              native source of the APK
#   --only-link  only libsignal/, tor/, lyrebird/ and onionwrapper/, for checking those parts: its file name says
#              "link-only" and it is never a release's archive
#   --list     prints every pinned source and where it comes from, and exits: no network, nothing written
#   --check-apk  fails if a native library in that APK has no source named in NATIVE_SOURCES, and exits: no network
# Needs bash, curl, git, sha256sum, md5sum, tar (GNU), gzip, xz, unzip and python3 (3.11 or newer), and the network (GitHub,
# GitLab, Hugging Face, Maven Central, go.dev, proxy.golang.org, static.rust-lang.org, crates.io); on x86-64 Linux, for the
# pinned cargo it downloads (`cargo vendor` needs no compiler). Run it by hand when releasing; nothing runs it in the tests
# (they only read it).
# The archive is about 535 MB (ONNX Runtime's source is 275 MB of it, llama.cpp's 170 MB, libsignal's with its crates 70 MB,
# Tor's 67 MB, lyrebird's with Go 34 MB); attach it and its .sha256 to the GitHub release (README, "Releasing").
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROBOT=$(dirname "$HERE")
VERSION=""
OUT="$ROBOT/build/source-bundle"
WORK=""
WITH_ORT=1
WITH_LLAMA=1
LIST=0
ONLY_LINK=0
CHECK_APK=""
while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --work) WORK="$2"; shift 2 ;;
    --without-onnxruntime) WITH_ORT=0; shift ;;
    --without-llama) WITH_LLAMA=0; shift ;;
    --only-link) ONLY_LINK=1; shift ;;
    --list) LIST=1; shift ;;
    --check-apk) CHECK_APK="$2"; shift 2 ;;
    -h|--help) sed -n '2,/^set -euo/p' "$0" | sed '$d'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[ -n "$VERSION" ] || VERSION=$(sed -n "s/^ *versionName '\([^']*\)'.*/\1/p" "$ROBOT/app/build.gradle" | head -1)
[ -n "$VERSION" ] || { echo "no version: pass --version" >&2; exit 2; }

# --- Pins (what Robot ships: THIRD_PARTY.md). -------------------------------------------------------------------------
SHERPA_REPO=https://github.com/k2-fsa/sherpa-onnx
SHERPA_VERSION=1.13.8
SHERPA_COMMIT=11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf
ORT_REPO=https://github.com/microsoft/onnxruntime
ORT_VERSION=1.28.2
ORT_COMMIT=33ca9628233dc8f002435e868d4c2e9f82766ca1
# Every source the Android build (TTS and speaker clustering on, no binaries, no websocket) compiles into the library:
# name | where sherpa-onnx pins its archive | the CMake variable | git fallback: repository | ref | the ref's commit
DEPS='
espeak-ng|cmake/espeak-ng-for-piper.cmake|espeak_ng|https://github.com/csukuangfj/espeak-ng|ed530aa113046142eb5115cf2fc9157854d0ffe1|ed530aa113046142eb5115cf2fc9157854d0ffe1
piper-phonemize|cmake/piper-phonemize.cmake|piper_phonemize|https://github.com/csukuangfj/piper-phonemize|f3ff95afc03640bc1399e113e83361192a2fafb4|f3ff95afc03640bc1399e113e83361192a2fafb4
kaldi-native-fbank|cmake/kaldi-native-fbank.cmake|kaldi_native_fbank|https://github.com/csukuangfj/kaldi-native-fbank|refs/tags/v1.22.3|b09e686fe2084732ddd30d1ef80acfc0f13eaf01
kaldi-decoder|cmake/kaldi-decoder.cmake|kaldi_decoder|https://github.com/k2-fsa/kaldi-decoder|refs/tags/v0.3.0|62fda2d74246a90db9570e46fb16b012f813fae7
kaldifst|cmake/download-all-deps.py|kaldifst|https://github.com/k2-fsa/kaldifst|refs/tags/v1.8.0|ab5bdd013bdf13921e6aeee77db5722ebf9955fb
openfst|cmake/openfst.cmake|openfst|https://github.com/csukuangfj/openfst|refs/tags/v1.8.5-2026-07-09|f702a6317d600ac953fdbb4978674c3a28b6115c
simple-sentencepiece|cmake/simple-sentencepiece.cmake|simple-sentencepiece|https://github.com/pkufool/simple-sentencepiece|refs/tags/v0.7|62dd423df1d51da5ea06f1c3a046fc04f01b4f39
json|cmake/json.cmake|json|https://github.com/nlohmann/json|refs/tags/v3.12.0|55f93686c01528224f448c19128836e7df245f72
hclust-cpp|cmake/hclust-cpp.cmake|hclust_cpp|https://github.com/csukuangfj/hclust-cpp|refs/tags/2026-02-25|db76039198979ce8d03632ab6ef14fe6accb127c
eigen|cmake/eigen.cmake|eigen|https://gitlab.com/libeigen/eigen.git|refs/tags/5.0.1|bc3b39870ecb690a623a3f49149a358b95c5781d
'

# librobotmt.so's sources: tools/build_robotmt.sh's own pins (name repository commit sha256-of-the-tar directory), read
# from that script so the two can't drift apart.
MT_SOURCES=$(sed -n "/^SOURCES=\$(cat <<'LIST'\$/,/^LIST\$/p" "$HERE/build_robotmt.sh" | sed '1d;$d')
[ "$(grep -c . <<<"$MT_SOURCES")" -ge 4 ] || { echo "couldn't read the pins in tools/build_robotmt.sh" >&2; exit 2; }
# librobot_llama.so's sources: tools/build_llama.sh's own pins, read from that script.
llama_pin() { local v; v=$(sed -n "s/^$1=\(.*\)$/\1/p" "$HERE/build_llama.sh" | head -1); [ -n "$v" ] || { echo "no $1 in tools/build_llama.sh" >&2; exit 2; }; echo "$v"; }
LLAMA_TAG=$(llama_pin LLAMA_TAG); LLAMA_COMMIT=$(llama_pin LLAMA_COMMIT); LLAMA_TAR_SHA256=$(llama_pin LLAMA_TAR_SHA256)
LLAMA_GIT=$(llama_pin LLAMA_GIT)
KLEIDIAI_TAG=$(llama_pin KLEIDIAI_TAG); KLEIDIAI_COMMIT=$(llama_pin KLEIDIAI_COMMIT); KLEIDIAI_GIT=$(llama_pin KLEIDIAI_GIT)
KLEIDIAI_TGZ_SHA256=$(llama_pin KLEIDIAI_TGZ_SHA256); KLEIDIAI_TGZ_MD5=$(llama_pin KLEIDIAI_TGZ_MD5); KLEIDIAI_TAR_SHA256=$(llama_pin KLEIDIAI_TAR_SHA256)
KLEIDIAI_URL="https://github.com/ARM-software/kleidiai/releases/download/$KLEIDIAI_TAG/kleidiai-$KLEIDIAI_TAG-src.tar.gz"
# librobotzim.so's sources: tools/build_libzim.sh's own pins and its fetch lines (archive, link, SHA-256), read from that
# script so the two can't drift apart.
eval "$(grep -E '^(ZSTD|XZ|ICU|XAPIAN|LIBZIM|MESON)_[A-Z_]+=' "$HERE/build_libzim.sh")"
WIKI_FETCH=$(grep -E '^fetch ' "$HERE/build_libzim.sh")
[ "$(grep -c . <<<"$WIKI_FETCH")" -ge 7 ] && [ -n "${LIBZIM_V:-}" ] || { echo "couldn't read the pins in tools/build_libzim.sh" >&2; exit 2; }
ZIM_BUILT_SHA=$(sed -n 's/^BUILT_SHA=//p' "$HERE/build_libzim.sh")
# Robot's own files the archive carries (its native sources and the scripts that build and fetch the native parts).
# Robot to Robot's: the versions and the binaries' pins are read from tools/fetch_libsignal.sh and
# third_party/link-carriers.sha256; below them are only facts those files don't hold (each checked when it's used, so a
# version that moves stops here until these move with it).
LIBSIGNAL_PINS=$(sed -n 's|^PINS="$HERE/third_party/\(libsignal-[^"]*\.sha256\)"$|\1|p' "$HERE/fetch_libsignal.sh")
[ -n "$LIBSIGNAL_PINS" ] || { echo "couldn't read the pins' name in tools/fetch_libsignal.sh" >&2; exit 2; }
LIBSIGNAL_VERSION=${LIBSIGNAL_PINS#libsignal-}; LIBSIGNAL_VERSION=${LIBSIGNAL_VERSION%.sha256}
LIBSIGNAL_REPO=https://github.com/signalapp/libsignal
LIBSIGNAL_COMMIT=b369755533ef5a3fae9b31b989114f22e074dfab # what tag v0.105.2 is
CARGO_VERSION=1.98.1 # libsignal's rust-toolchain file at that commit
CARGO_TAR_SHA256=ea1de9f9e23107d97ee2b41a72c552f34064a593da503789218387aee59f3ba4
CARGO_URL="https://static.rust-lang.org/dist/cargo-$CARGO_VERSION-x86_64-unknown-linux-gnu.tar.xz"
CARRIERS="$ROBOT/third_party/link-carriers.sha256"
MAVEN_CENTRAL=https://repo1.maven.org/maven2
carrier_jar() { grep -oE "org/briarproject/$1/[^/]+/$1-[^/]+\.jar\$" "$CARRIERS" || { echo "no $1 jar in third_party/link-carriers.sha256" >&2; exit 2; }; }
carrier_pin() { sed -nE "s|^([0-9a-f]{64})  $1\$|\1|p" "$CARRIERS"; }
TOR_JAR=$(carrier_jar tor-android); TOR_VERSION=$(basename "$(dirname "$TOR_JAR")")
LYREBIRD_VERSION=$(sed -n 's/^LYREBIRD_VERSION=//p' "$HERE/build_lyrebird.sh")
LYREBIRD_COMMIT=$(sed -n 's/^LYREBIRD_COMMIT=//p' "$HERE/build_lyrebird.sh")
TOR_SOURCES_SHA256=e4b067081d97cc60bd15a646435a80719465607a9977e8220d3a13698aeaed09 # tor-android 0.4.9.14-sources.jar
LYREBIRD_GIT=https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird.git
GO_VERSION=$(sed -n 's/^GO_VERSION=//p' "$HERE/build_lyrebird.sh") # the Go in liblyrebird.so's build information, checked against it
GO_SRC_SHA256=6e7749035cf360f06b2575d162ea79df9fda8ef671889e5c375f8e50777f26bb # go.dev's for go1.27.3.src.tar.gz
ONIONWRAPPER_VERSION=0.1.7 # THIRD_PARTY.md; Robot's TorRuntime.kt adapts its start-up, nothing else of it ships
ONIONWRAPPER_CORE_SHA256=8c2373c4236eed070771bf30ddf37c45fcd63910796f88fbc38c78a5a10b75bd
ONIONWRAPPER_ANDROID_SHA256=049ea14bd3329b441a3bd4ae33d67356c8ca70b5eb094e061566f0f17295c194
LITERTLM_VERSION=$(sed -n "s/.*litertlm-android:\([0-9][0-9.]*\).*/\1/p" "$ROBOT/brain/build.gradle" | head -1)
LITERT_NPU_VERSION=$(sed -n "s/.*litert-npu-runtime-google-tensor:\([0-9][0-9.]*\).*/\1/p" "$ROBOT/brain/build.gradle" | head -1)
GRAPHICS_PATH_VERSION=$(sed -n 's/.*name="graphics-path" version="\([^"]*\)".*/\1/p' "$ROBOT/gradle/verification-metadata.xml" | head -1)
ROBOT_FILES="build.gradle mt/src/main/cpp brain/src/main/cpp tools/build_robotmt.sh tools/build_llama.sh wiki/src/main/cpp tools/build_libzim.sh tools/fetch_third_party.sh tools/make_source_bundle.sh tools/build_sherpa.sh tools/espeak_bounds_check.sh third_party/patches link/build.gradle link/src/main/java/app/heyrobot/r2r/TorRuntime.kt tools/fetch_libsignal.sh tools/fetch_link_deps.sh tools/build_lyrebird.sh third_party/$LIBSIGNAL_PINS third_party/link-carriers.sha256"

# Every native library the APK may hold and where its source is: file name | licence | a path in this archive (a shell
# pattern) or "upstream: ..." for one not carried. A new library needs a row here: --check-apk, verify_apk.sh and every run
# fail until it has one. Licences as THIRD_PARTY.md gives them.
NATIVE_SOURCES="
libsherpa-onnx-jni.so|Apache-2.0, with espeak-ng GPL-3.0-or-later and others linked in|sherpa-onnx-$SHERPA_VERSION.tar.gz
libonnxruntime.so|MIT|onnxruntime-$ORT_VERSION.tar.gz
librobotmt.so|GPL-2.0-or-later (slimt), Apache-2.0, BSD-2-Clause|mt/*.tar
librobot_llama.so|MIT, Apache-2.0|llama/*
librobotzim.so|GPL-2.0-or-later (libzim, Xapian), with Zstandard BSD-3-Clause, liblzma 0BSD and ICU Unicode-3.0|wiki/*
libsignal_jni.so|AGPL-3.0-only|libsignal/libsignal-$LIBSIGNAL_VERSION.tar.gz
libtor.so|BSD-3-Clause, with OpenSSL Apache-2.0, libevent BSD-3-Clause and zlib Zlib|tor/tor-android-$TOR_VERSION-sources.jar
liblyrebird.so|GPL-3.0-or-later, BSD-2-Clause and BSD-3-Clause, with its Go modules' licences|lyrebird/lyrebird-$LYREBIRD_VERSION-vendored.tar.gz
liblitertlm_jni.so|Apache-2.0|upstream: https://github.com/google-ai-edge/LiteRT-LM tag v$LITERTLM_VERSION, whose tree also holds prebuilt libraries; Google publishes no sources jar and doesn't say the AAR's library is built from that tag
libLiteRtDispatch_GoogleTensor.so|Apache-2.0|upstream: https://github.com/google-ai-edge/LiteRT tag v$LITERT_NPU_VERSION, litert/vendors/google_tensor/dispatch; Google publishes no sources jar and doesn't say the AAR's library is built from that tag
libandroidx.graphics.path.so|Apache-2.0|upstream: https://github.com/androidx/androidx graphics/graphics-path/src/main/cpp, with no tag for version $GRAPHICS_PATH_VERSION; its sources jar on Google's Maven holds only the Kotlin
"
native_names() { cut -d'|' -f1 <<<"$NATIVE_SOURCES" | grep .; }
# The names given, one per line on stdin, that have no row: fails if there are any.
no_source_named() {
  local l missing=""
  while read -r l; do [ -z "$l" ] || native_names | grep -qxF "$l" || missing="$missing $l"; done
  [ -z "$missing" ] || { echo "no source named in tools/make_source_bundle.sh's NATIVE_SOURCES for:$missing" >&2; return 1; }
}
# verify_apk.sh's allowlist of libraries: each must have a source named, so no APK is needed to catch a new one.
ALLOWED=$(sed -n "/^extra=/s/.*grep -vxE '\([^']*\)'.*/\1/p" "$HERE/verify_apk.sh" | sed 's/\\\././g' | tr '|' '\n')
[ "$(grep -c . <<<"$ALLOWED")" -ge 10 ] || { echo "couldn't read the allowlist in tools/verify_apk.sh" >&2; exit 2; }
no_source_named <<<"$ALLOWED" || exit 1
if [ -n "$CHECK_APK" ]; then
  names=$(unzip -Z1 "$CHECK_APK" 'lib/*' | grep -E '\.so$' | sed 's|.*/||' | sort -u)
  [ -n "$names" ] || { echo "no native libraries in $CHECK_APK" >&2; exit 1; }
  no_source_named <<<"$names" || exit 1
  echo "every native library in $(basename "$CHECK_APK") has its source named: $(grep -c . <<<"$names")"
  exit 0
fi

if [ "$LIST" = 1 ]; then
  echo "sherpa-onnx $SHERPA_VERSION: $SHERPA_REPO @ $SHERPA_COMMIT"
  while IFS='|' read -r name file var repo ref commit; do
    [ -n "$name" ] && echo "  $name: the archive sherpa-onnx pins in $file (git fallback: $repo $ref = $commit)"
  done <<< "$DEPS"
  [ "$WITH_ORT" = 1 ] && echo "onnxruntime $ORT_VERSION: $ORT_REPO @ $ORT_COMMIT"
  while read -r name repo commit want dir; do echo "mt/$name: $repo @ $commit (tar sha256 $want)"; done <<<"$MT_SOURCES"
  if [ "$WITH_LLAMA" = 1 ]; then
    echo "llama/llama.cpp $LLAMA_TAG: $LLAMA_GIT @ $LLAMA_COMMIT (tar sha256 $LLAMA_TAR_SHA256)"
    echo "llama/kleidiai $KLEIDIAI_TAG: $KLEIDIAI_URL (sha256 $KLEIDIAI_TGZ_SHA256), or $KLEIDIAI_GIT @ $KLEIDIAI_COMMIT (tar sha256 $KLEIDIAI_TAR_SHA256)"
  fi
  while read -r line; do eval "set -- ${line#fetch }"; echo "wiki/$1: $2 (sha256 $3)"; done <<<"$WIKI_FETCH"
  echo "libsignal/ $LIBSIGNAL_VERSION: $LIBSIGNAL_REPO @ $LIBSIGNAL_COMMIT, with its Cargo.lock's crates vendored by cargo $CARGO_VERSION ($CARGO_URL, sha256 $CARGO_TAR_SHA256)"
  echo "tor/ $TOR_VERSION: $MAVEN_CENTRAL/${TOR_JAR%.jar}-sources.jar (sha256 $TOR_SOURCES_SHA256)"
  echo "lyrebird/ $LYREBIRD_VERSION: $LYREBIRD_GIT at $LYREBIRD_COMMIT, with third_party/patches/lyrebird-$LYREBIRD_VERSION-deps.patch and vendor/ from tools/build_lyrebird.sh, with go$GO_VERSION's source (sha256 $GO_SRC_SHA256); its modules come from the pinned liblyrebird.so"
  echo "onionwrapper/ $ONIONWRAPPER_VERSION: core and android sources jars on Maven Central (sha256 $ONIONWRAPPER_CORE_SHA256, $ONIONWRAPPER_ANDROID_SHA256)"
  echo "robot/: $ROBOT_FILES"
  echo "Native libraries and their source:"
  while IFS='|' read -r l lic where; do [ -n "$l" ] && echo "  $l ($lic): $where"; done <<<"$NATIVE_SOURCES"
  exit 0
fi
for tool in curl git sha256sum md5sum tar gzip xz unzip python3; do command -v "$tool" >/dev/null || { echo "needs $tool" >&2; exit 2; }; done

NAME="robot-${VERSION}-third-party-sources"
[ "$ONLY_LINK" = 0 ] || NAME="$NAME-link-only"
# Never in /tmp by default (often memory): the unpacked crates and modules alone come to gigabytes.
mkdir -p "$OUT"
if [ -z "$WORK" ]; then WORK=$(mktemp -d "$OUT/work.XXXXXX"); trap 'rm -rf "$WORK"' EXIT; else mkdir -p "$WORK"; fi
STAGE="$WORK/$NAME"
rm -rf "$STAGE" "$WORK/git"
mkdir -p "$STAGE/deps" "$STAGE/mt" "$STAGE/robot" "$WORK/git" "$OUT"
MANIFEST="$STAGE/MANIFEST.tsv"
printf 'file\tsha256\tsource\tpin\tchecked\n' > "$MANIFEST"
say() { echo "== $*" >&2; }
sha() { sha256sum "$1" | cut -d' ' -f1; }

# git archive of [commit] of [repo] (fetched by [ref]), checked by its commit id; written to [out] as tar.gz.
git_archive() {
  local repo=$1 ref=$2 commit=$3 prefix=$4 out=$5 dir
  dir="$WORK/git/$(basename "$out" .tar.gz)"
  rm -rf "$dir"; git init -q "$dir"
  git -C "$dir" fetch -q --depth 1 "$repo" "$ref"
  local got; got=$(git -C "$dir" rev-parse 'FETCH_HEAD^{commit}')
  [ "$got" = "$commit" ] || { echo "$repo $ref is $got, not the pinned $commit" >&2; exit 1; }
  git -C "$dir" archive --format=tar --prefix="$prefix/" "$commit" | gzip -n -9 > "$out"
  rm -rf "$dir"
}

# The uncompressed `git archive --format=tar [--prefix=<prefix>/] <commit>` of [repo] (fetched by [ref]), checked by its
# commit id and by the SHA-256 Robot's build script pins for that very tar; written to [out]. [prefix] "-": none.
git_tar() {
  local repo=$1 ref=$2 commit=$3 prefix=$4 want=$5 out=$6 dir got
  dir="$WORK/git/$(basename "$out" .tar)"
  rm -rf "$dir"; git init -q "$dir"
  git -C "$dir" fetch -q --depth 1 "$repo" "$ref"
  got=$(git -C "$dir" rev-parse 'FETCH_HEAD^{commit}')
  [ "$got" = "$commit" ] || { echo "$repo $ref is $got, not the pinned $commit" >&2; exit 1; }
  if [ "$prefix" = - ]; then git -C "$dir" archive --format=tar "$commit" > "$out"
  else git -C "$dir" archive --format=tar --prefix="$prefix/" "$commit" > "$out"; fi
  got=$(sha "$out")
  [ "$got" = "$want" ] || { echo "SHA-256 MISMATCH $(basename "$out"): $got != $want" >&2; exit 1; }
  rm -rf "$dir"
}

# The SHA-256 of the library [2] this machine built into module [1]'s jniLibs for the APK, or what to say when it isn't there.
built_sha() { if [ -f "$ROBOT/$1/src/main/jniLibs/arm64-v8a/$2" ]; then echo "\`$(sha "$ROBOT/$1/src/main/jniLibs/arm64-v8a/$2")\` (the copy in $1/src/main/jniLibs when this archive was made)"; else echo "not built on the machine that made this archive"; fi; }

# A file on Maven Central, checked against its pin and Central's own .sha256.
central_file() {
  local rel=$1 want=$2 out=$3 got published
  curl -fsSL --retry 2 --connect-timeout 20 --max-time 1800 -o "$out" "$MAVEN_CENTRAL/$rel"
  got=$(sha "$out")
  [ "$got" = "$want" ] || { echo "SHA-256 MISMATCH $rel: $got != $want" >&2; exit 1; }
  published=$(curl -fsSL --retry 2 --connect-timeout 20 --max-time 120 "$MAVEN_CENTRAL/$rel.sha256" | tr -d ' \n' | cut -c1-64)
  [ "$published" = "$want" ] || { echo "Central's $(basename "$rel").sha256 says $published, the pin $want" >&2; exit 1; }
}

# The executable the APK ships, out of its pinned jar (third_party/maven-link when it's there, else Central), both checked.
carrier_binary() {
  local rel=$1 lib=$2 out=$3 jar got
  jar="$WORK/$(basename "$rel")"
  if [ -f "$ROBOT/third_party/maven-link/$rel" ]; then cp "$ROBOT/third_party/maven-link/$rel" "$jar"
  else curl -fsSL --retry 2 --connect-timeout 20 --max-time 1800 -o "$jar" "$MAVEN_CENTRAL/$rel"; fi
  got=$(sha "$jar")
  [ "$got" = "$(carrier_pin "$rel")" ] || { echo "$rel is $got, not its pin in third_party/link-carriers.sha256" >&2; exit 1; }
  unzip -p "$jar" "arm64-v8a/$lib" > "$out"
  got=$(sha "$out")
  [ "$got" = "$(carrier_pin "lib/arm64-v8a/$lib")" ] || { echo "$lib is $got, not its pin in third_party/link-carriers.sha256" >&2; exit 1; }
  rm -f "$jar"
}

row() { printf '%s\t%s\t%s\t%s\t%s\n' "$1" "$(sha "$STAGE/$1")" "$2" "$3" "$4" >> "$MANIFEST"; }

# The checks Python does better than shell: `lock` (the vendored crates against Cargo.lock) and `lyrebird` (the vendored
# tree against the commit and the modules liblyrebird.so's build information names).
cat > "$WORK/bundle_check.py" <<'PY'
import base64, hashlib, io, json, re, sys, tarfile, time, tomllib, urllib.request, zipfile

def fail(msg):
    print(msg, file=sys.stderr)
    sys.exit(1)

def lock(src, out):
    with open(f"{src}/Cargo.lock", "rb") as f:
        packages = tomllib.load(f)["package"]
    crates = gits = 0
    with open(out, "w") as f:
        f.write("crate\tversion\tsha256\tsource\n")
        for p in packages:
            source = p.get("source")
            if not source:
                continue  # one of libsignal's own, in its source tree
            with open(f"{src}/vendor/{p['name']}-{p['version']}/.cargo-checksum.json") as c:
                got = json.load(c).get("package")
            if got != p.get("checksum"):
                fail(f"{p['name']} {p['version']}: vendored {got}, Cargo.lock {p.get('checksum')}")
            crates, gits = (crates + 1, gits) if got else (crates, gits + 1)
            f.write(f"{p['name']}\t{p['version']}\t{got or '-'}\t{source}\n")
    print(f"{crates} crates by Cargo.lock's SHA-256, {gits} packages from git by its commit", file=sys.stderr)

def download(url):
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=120) as r:
                return r.read()
        except OSError as e:
            if attempt == 2:
                fail(f"{url}: {e}")
            time.sleep(5)

def escape(s):
    return re.sub("[A-Z]", lambda m: "!" + m.group().lower(), s)

def h1(z):
    names = sorted(n for n in z.namelist() if not n.endswith("/"))
    lines = "".join(f"{hashlib.sha256(z.read(n)).hexdigest()}  {n}\n" for n in names)
    return "h1:" + base64.b64encode(hashlib.sha256(lines.encode()).digest()).decode()

def lyrebird(binary, src_tgz, commit_tgz, go_version, out):
    with open(binary, "rb") as f:
        data = f.read()
    gos = set(re.findall(rb"go1\.\d+\.\d+", data))
    if gos != {f"go{go_version}".encode()}:
        fail(f"liblyrebird.so names Go {gos}, not {go_version}")
    # path -> (the path its zip has, version, h1:); a "=>" line replaces the dep before it.
    modules, last = {}, None
    for kind, path, version, sum_ in re.findall(rb"^(dep|=>)\t([^\t\n]+)\t([^\t\n]+)(?:\t([^\t\n]*))?$", data, re.M):
        path, version, sum_ = path.decode(), version.decode(), sum_.decode()
        if kind == b"dep":
            last = path
            modules[path] = (path, version, sum_, version)
        else:
            modules[last] = (path, version, sum_, modules[last][3])
    if not modules:
        fail("no Go modules in liblyrebird.so's build information")
    src = {}
    with tarfile.open(src_tgz) as t:
        for m in t.getmembers():
            if m.isfile():
                src[m.name.split("/", 1)[1]] = t.extractfile(m).read()
    names = list(src)
    # The archive's lyrebird is that commit's tree, but for go.mod and go.sum, which the patch moves.
    tree = {}
    with tarfile.open(commit_tgz) as t:
        for m in t.getmembers():
            if m.isfile():
                tree[m.name.split("/", 1)[1]] = t.extractfile(m).read()
    for name, content in tree.items():
        if name not in src:
            fail(f"{name} is in the commit, not in the archive")
        elif src[name] != content and name not in ("go.mod", "go.sum"):
            fail(f"{name} differs between the commit and the archive")
    extra = [n for n in names if not n.startswith("vendor/") and n not in tree]
    if extra:
        fail(f"in the archive, not in the commit: {extra[:5]}")
    # vendor/ names each module at the version the binary has.
    listed = src["vendor/modules.txt"].decode()
    for path, (zip_path, version, _, asked) in modules.items():
        line = f"# {path} {asked} => {zip_path} {version}\n" if zip_path != path else f"# {path} {version}\n"
        if line not in listed:
            fail(f"vendor/modules.txt lacks {line.strip()}")
    # Each vendored file, against that module's zip, itself checked against the binary's h1:.
    by_module, unlinked = {}, set()
    for name in names:
        if not name.startswith("vendor/") or name == "vendor/modules.txt":
            continue
        rel = name[len("vendor/"):]
        owner = max((m for m in modules if rel.startswith(m + "/")), key=len, default=None)
        if owner:
            by_module.setdefault(owner, []).append(rel)
        else:
            unlinked.add(rel)
    compared = 0
    with open(out, "w") as f:
        f.write("module\tversion\th1\treplaced by\tvendored files compared\n")
        for path, (zip_path, version, sum_, _) in sorted(modules.items()):
            if path not in by_module:
                fail(f"{path} is in the binary, with no vendored files")
            url = f"https://proxy.golang.org/{escape(zip_path)}/@v/{escape(version)}.zip"
            z = zipfile.ZipFile(io.BytesIO(download(url)))
            if h1(z) != sum_:
                fail(f"{zip_path} {version}: h1 {h1(z)}, the binary {sum_}")
            root = f"{zip_path}@{version}/"
            for rel in by_module[path]:
                inside = root + rel[len(path) + 1:]
                try:
                    same = z.read(inside) == src["vendor/" + rel]
                except KeyError:
                    fail(f"vendor/{rel} isn't in {zip_path}@{version}'s zip")
                if not same:
                    fail(f"vendor/{rel} differs from {zip_path}@{version}'s zip")
                compared += 1
            f.write(f"{path}\t{version}\t{sum_}\t{zip_path if zip_path != path else '-'}\t{len(by_module[path])}\n")
    print(f"{len(modules)} Go modules by the binary's h1:, {compared} vendored files the same as their zips, "
          f"{len(tree)} files of the commit checked, {len(unlinked)} vendored files outside any linked module", file=sys.stderr)
    for rel in sorted({"/".join(r.split("/")[:3]) for r in unlinked}):
        print(f"  not linked: {rel}", file=sys.stderr)

if sys.argv[1] == "lock":
    lock(sys.argv[2], sys.argv[3])
else:
    lyrebird(*sys.argv[2:7])
PY

# --- Robot to Robot: libsignal, Tor, lyrebird, onionwrapper. ------------------------------------------------------------
LIBSIGNAL_SO_PIN=$(sed -nE 's|^([0-9a-f]{64})  lib/arm64-v8a/libsignal_jni\.so$|\1|p' "$ROBOT/third_party/$LIBSIGNAL_PINS")
LINK_TABLE=""
link_sources() {
  mkdir -p "$STAGE/libsignal" "$STAGE/tor" "$STAGE/lyrebird" "$STAGE/onionwrapper"
  # libsignal, by the tag's commit, and what its Cargo.lock names, vendored by the cargo of its own rust-toolchain file.
  say "libsignal $LIBSIGNAL_VERSION ($LIBSIGNAL_COMMIT)"
  local out="libsignal/libsignal-$LIBSIGNAL_VERSION.tar.gz" src="$WORK/libsignal-src" tool
  git_archive "$LIBSIGNAL_REPO" "refs/tags/v$LIBSIGNAL_VERSION" "$LIBSIGNAL_COMMIT" "libsignal-$LIBSIGNAL_VERSION" "$STAGE/$out"
  row "$out" "$LIBSIGNAL_REPO" "tag v$LIBSIGNAL_VERSION = commit $LIBSIGNAL_COMMIT (tools/fetch_libsignal.sh's version)" "git commit id"
  rm -rf "$src"; mkdir -p "$src"
  tar -xzf "$STAGE/$out" -C "$src" --strip-components=1
  [ "$(tr -d ' \n' < "$src/rust-toolchain")" = "$CARGO_VERSION" ] || { echo "libsignal's rust-toolchain isn't $CARGO_VERSION: update CARGO_VERSION and its pin" >&2; exit 1; }
  say "cargo $CARGO_VERSION, to vendor libsignal's crates"
  [ "$(uname -sm)" = "Linux x86_64" ] || { echo "the pinned cargo is for x86-64 Linux" >&2; exit 2; }
  tool="$WORK/cargo.tar.xz"
  curl -fsSL --retry 2 --connect-timeout 20 --max-time 1800 -o "$tool" "$CARGO_URL"
  [ "$(sha "$tool")" = "$CARGO_TAR_SHA256" ] || { echo "SHA-256 MISMATCH $CARGO_URL" >&2; exit 1; }
  rm -rf "$WORK/cargo"; mkdir -p "$WORK/cargo"
  tar -xJf "$tool" -C "$WORK/cargo" --strip-components=1; rm -f "$tool"
  (cd "$src" && CARGO_HOME="$WORK/cargo-home" "$WORK/cargo/cargo/bin/cargo" vendor --locked --versioned-dirs vendor) > "$STAGE/libsignal/cargo-config.toml"
  grep -q 'directory = "vendor"' "$STAGE/libsignal/cargo-config.toml" || { echo "cargo vendor wrote no source replacement" >&2; exit 1; }
  python3 -I "$WORK/bundle_check.py" lock "$src" "$STAGE/libsignal/crates.tsv"
  out="libsignal/libsignal-$LIBSIGNAL_VERSION-vendor.tar.gz"
  tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner -C "$src" -cf - vendor | gzip -n -9 > "$STAGE/$out"
  row "$out" "crates.io and Signal's git repositories, by libsignal's Cargo.lock" "Cargo.lock at commit $LIBSIGNAL_COMMIT; libsignal/crates.tsv lists each" "cargo vendor --locked, and each crate's sha256 against Cargo.lock"
  row libsignal/crates.tsv "made here from Cargo.lock" "-" "-"
  row libsignal/cargo-config.toml "cargo vendor's output" "-" "-"
  rm -rf "$src" "$WORK/cargo" "$WORK/cargo-home"
  LINK_TABLE="| libsignal $LIBSIGNAL_VERSION | \`libsignal/libsignal-$LIBSIGNAL_VERSION.tar.gz\` | \`git archive\` of tag v$LIBSIGNAL_VERSION (commit \`$LIBSIGNAL_COMMIT\`) |
| libsignal's crates, with Signal's boring (BoringSSL inside) and SparsePostQuantumRatchet | \`libsignal/libsignal-$LIBSIGNAL_VERSION-vendor.tar.gz\`, \`libsignal/crates.tsv\`, \`libsignal/cargo-config.toml\` | \`cargo vendor --locked\` from its Cargo.lock, each crate against the lock's SHA-256, git ones by the lock's commit |
"
  # Tor: the sources jar, and the versions in it against the very executable the APK ships.
  say "Tor $TOR_VERSION"
  local bin="$WORK/libtor.so" jar="tor/tor-android-$TOR_VERSION-sources.jar" v
  carrier_binary "$TOR_JAR" libtor.so "$bin"
  central_file "${TOR_JAR%.jar}-sources.jar" "$TOR_SOURCES_SHA256" "$STAGE/$jar"
  [ "$(unzip -p "$STAGE/$jar" tor/configure.ac | sed -n 's/^AC_INIT(\[tor\],\[\(.*\)\])$/\1/p')" = "$TOR_VERSION" ] || { echo "the sources jar isn't Tor $TOR_VERSION" >&2; exit 1; }
  v=$(unzip -p "$STAGE/$jar" openssl/VERSION.dat | sed -nE 's/^(MAJOR|MINOR|PATCH)=//p' | paste -sd.)
  LC_ALL=C grep -aqF "OpenSSL $v" "$bin" || { echo "libtor.so doesn't name the sources jar's OpenSSL $v" >&2; exit 1; }
  v=$(unzip -p "$STAGE/$jar" zlib/zlib.h | sed -n 's/^#define ZLIB_VERSION "\(.*\)"$/\1/p')
  LC_ALL=C grep -aqF "$v" "$bin" || { echo "libtor.so doesn't name the sources jar's zlib $v" >&2; exit 1; }
  v=$(unzip -p "$STAGE/$jar" libevent/configure.ac | sed -n 's/^AC_INIT(libevent,\(.*\))$/\1/p')
  LC_ALL=C grep -aqF "$v" "$bin" || { echo "libtor.so doesn't name the sources jar's libevent $v" >&2; exit 1; }
  rm -f "$bin"
  row "$jar" "$MAVEN_CENTRAL/${TOR_JAR%.jar}-sources.jar" "tor-android $TOR_VERSION: Tor, OpenSSL, libevent, zlib" "sha256 (pinned, and Central's .sha256); versions against the pinned libtor.so"
  LINK_TABLE="$LINK_TABLE| Tor $TOR_VERSION with OpenSSL, libevent and zlib | \`$jar\` | Briar's sources jar for the build the APK takes \`libtor.so\` from |
"
  # lyrebird: the tree tools/build_lyrebird.sh builds, proved against what the pinned executable says it was built from.
  say "lyrebird $LYREBIRD_VERSION"
  bin="$ROBOT/third_party/lyrebird/liblyrebird.so"; src="lyrebird/lyrebird-$LYREBIRD_VERSION-vendored.tar.gz"
  [ -f "$bin" ] || { echo "no $bin: run tools/build_lyrebird.sh" >&2; exit 1; }
  [ "$(sha "$bin")" = "$(carrier_pin lib/arm64-v8a/liblyrebird.so)" ] || { echo "$bin isn't its pin in third_party/link-carriers.sha256" >&2; exit 1; }
  "$HERE/build_lyrebird.sh" --vendor-to "$STAGE/$src" > /dev/null
  git_archive "$LYREBIRD_GIT" "$LYREBIRD_COMMIT" "$LYREBIRD_COMMIT" "lyrebird" "$WORK/lyrebird-commit.tar.gz"
  python3 -I "$WORK/bundle_check.py" lyrebird "$bin" "$STAGE/$src" "$WORK/lyrebird-commit.tar.gz" "$GO_VERSION" "$STAGE/lyrebird/go-modules.tsv"
  rm -f "$WORK/lyrebird-commit.tar.gz"
  row "$src" "$LYREBIRD_GIT" "lyrebird $LYREBIRD_VERSION: commit $LYREBIRD_COMMIT, with third_party/patches/lyrebird-$LYREBIRD_VERSION-deps.patch and vendor/ (the modules read from the pinned liblyrebird.so)" "files against the commit; vendored files against each module's zip and its h1:"
  row lyrebird/go-modules.tsv "made here from liblyrebird.so's build information" "-" "-"
  out="lyrebird/go$GO_VERSION.src.tar.gz"
  curl -fsSL --retry 2 --connect-timeout 20 --max-time 1800 -o "$STAGE/$out" "https://go.dev/dl/go$GO_VERSION.src.tar.gz"
  [ "$(sha "$STAGE/$out")" = "$GO_SRC_SHA256" ] || { echo "SHA-256 MISMATCH $out" >&2; exit 1; }
  row "$out" "https://go.dev/dl/go$GO_VERSION.src.tar.gz" "go$GO_VERSION, the version in liblyrebird.so's build information" "sha256 (pinned, go.dev's)"
  LINK_TABLE="$LINK_TABLE| lyrebird $LYREBIRD_VERSION (commit \`$LYREBIRD_COMMIT\`) and its Go modules | \`$src\`, \`lyrebird/go-modules.tsv\` | The tree \`tools/build_lyrebird.sh\` builds \`liblyrebird.so\` from, with \`vendor/\`; checked against that library's own build information |
| Go $GO_VERSION (its standard library is linked into \`liblyrebird.so\`) | \`$out\` | go.dev's source tarball |
"
  # onionwrapper: Robot's TorRuntime.kt adapts its start-up under the GPL; no other part of it ships.
  say "onionwrapper $ONIONWRAPPER_VERSION"
  local a want
  for a in core android; do
    [ "$a" = core ] && want=$ONIONWRAPPER_CORE_SHA256 || want=$ONIONWRAPPER_ANDROID_SHA256
    out="onionwrapper/onionwrapper-$a-$ONIONWRAPPER_VERSION-sources.jar"
    central_file "org/briarproject/onionwrapper-$a/$ONIONWRAPPER_VERSION/onionwrapper-$a-$ONIONWRAPPER_VERSION-sources.jar" "$want" "$STAGE/$out"
    row "$out" "$MAVEN_CENTRAL/org/briarproject/onionwrapper-$a/$ONIONWRAPPER_VERSION/" "onionwrapper $ONIONWRAPPER_VERSION" "sha256 (pinned, and Central's .sha256)"
  done
  LINK_TABLE="$LINK_TABLE| onionwrapper $ONIONWRAPPER_VERSION (GPL-3.0-only; Robot's \`TorRuntime.kt\` adapts its start-up) | \`onionwrapper/\` | its sources jars on Maven Central |
"
}
link_sources

# Every library's source is really in the archive, or named upstream: rows in MANIFEST.tsv, and a table for the README.
NATIVE_TABLE=""
native_rows() {
  local l lic where
  while IFS='|' read -r l lic where; do
    [ -n "$l" ] || continue
    case "$where" in
      upstream:*) ;;
      *)
        if [ "$ONLY_LINK" = 1 ]; then case "$l" in libsignal_jni.so|libtor.so|liblyrebird.so) ;; *) continue ;; esac
        elif [ "$WITH_ORT" = 0 ] && [ "$l" = libonnxruntime.so ]; then where="upstream: $ORT_REPO tag v$ORT_VERSION, left out with --without-onnxruntime"
        elif [ "$WITH_LLAMA" = 0 ] && [ "$l" = librobot_llama.so ]; then where="upstream: $LLAMA_GIT tag $LLAMA_TAG, left out with --without-llama"
        else compgen -G "$STAGE/$where" >/dev/null || { echo "$l: its source $where isn't in the archive" >&2; exit 1; }; fi ;;
    esac
    printf '%s\t%s\t%s\t%s\t%s\n' "lib/arm64-v8a/$l (in the APK)" "-" "$where" "$lic" "source named" >> "$MANIFEST"
    NATIVE_TABLE="$NATIVE_TABLE| \`$l\` | $lic | \`$where\` |
"
  done <<<"$NATIVE_SOURCES"
}

# The archive and its SHA-256 (reproducible: sorted, no owners, no times).
pack() {
  say "packing"
  TGZ="$OUT/$NAME.tar.gz"
  tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner -C "$WORK" -cf - "$NAME" | gzip -n -9 > "$TGZ"
  (cd "$OUT" && sha256sum "$NAME.tar.gz" > "$NAME.tar.gz.sha256")
  rm -rf "$STAGE" "${SRC:-}"
  say "done: $TGZ ($(du -h "$TGZ" | cut -f1))"
  cat "$OUT/$NAME.tar.gz.sha256"
}

if [ "$ONLY_LINK" = 1 ]; then
  native_rows
  cat > "$STAGE/README.md" <<EOF
# Robot to Robot's sources only, for checking this script's parts

Not a release's archive. Made by Robot's \`tools/make_source_bundle.sh --only-link\`.

| Component | File | How it was taken |
|---|---|---|
${LINK_TABLE}
EOF
  pack
  exit 0
fi

# --- sherpa-onnx itself: its source, and from it the pins of everything it compiles in. ---------------------------
say "sherpa-onnx $SHERPA_VERSION ($SHERPA_COMMIT)"
SHERPA_TGZ="$STAGE/sherpa-onnx-$SHERPA_VERSION.tar.gz"
git_archive "$SHERPA_REPO" "$SHERPA_COMMIT" "$SHERPA_COMMIT" "sherpa-onnx-$SHERPA_VERSION" "$SHERPA_TGZ"
printf '%s\t%s\t%s\t%s\t%s\n' "$(basename "$SHERPA_TGZ")" "$(sha "$SHERPA_TGZ")" "$SHERPA_REPO" "commit $SHERPA_COMMIT (tag v$SHERPA_VERSION)" "git commit id" >> "$MANIFEST"
SRC="$WORK/sherpa-src"
rm -rf "$SRC"; mkdir -p "$SRC"
tar -xzf "$SHERPA_TGZ" -C "$SRC" --strip-components=1

# The ONNX Runtime the Android build links: its version must be the pinned one.
grep -q "onnxruntime-libs/releases/download/v$ORT_VERSION/onnxruntime-android-$ORT_VERSION.zip" "$SRC/cmake/onnxruntime-android-aarch64.cmake" \
  || { echo "sherpa-onnx's Android build no longer links ONNX Runtime $ORT_VERSION: update the pins" >&2; exit 1; }
ORT_PREBUILT_SHA=$(sed -n 's/^set(onnxruntime_HASH "SHA256=\([0-9a-f]\{64\}\)")/\1/p' "$SRC/cmake/onnxruntime-android-aarch64.cmake")

# The value of set(<var>_<KEY> "...") in a CMake file.
cmake_value() { sed -n "s/^ *set(${2}_${3} *\"\([^\"]*\)\")/\1/p" "$SRC/$1" | head -1; }

DEPS_TABLE=""
while IFS='|' read -r name file var repo ref commit; do
  [ -n "$name" ] || continue
  if [ "$file" = cmake/download-all-deps.py ]; then
    # kaldi-decoder's own CMake fetches kaldifst; sherpa-onnx pins the same archive here for offline builds.
    block=$(grep -A4 "\"$var\"," "$SRC/$file")
    url=$(echo "$block" | grep -o 'https://[^"]*' | head -1); url2=""
    want=$(echo "$block" | grep -o '[0-9a-f]\{64\}' | head -1)
  else
    url=$(cmake_value "$file" "$var" URL); url2=$(cmake_value "$file" "$var" URL2)
    want=$(cmake_value "$file" "$var" HASH | sed 's/^SHA256=//')
  fi
  [ -n "$url" ] && [ ${#want} -eq 64 ] || { echo "no pinned archive for $name in $file" >&2; exit 1; }
  say "$name: $url"
  got=""
  for u in "$url" "$url2" "${url2/hf-mirror.com/huggingface.co}"; do
    [ -n "$u" ] || continue
    base=$(basename "${u%%\?*}")
    case "$base" in "$name"*) ;; *) base="$name-${base#v}" ;; esac
    dest="$STAGE/deps/$base"
    if curl -fsSL --retry 2 --connect-timeout 20 --max-time 1800 -o "$dest" "$u" 2>/dev/null && [ "$(sha "$dest")" = "$want" ]; then got="$dest"; break; fi
    rm -f "$dest"
  done
  if [ -n "$got" ]; then
    printf '%s\t%s\t%s\t%s\t%s\n' "deps/$(basename "$got")" "$want" "$url" "sha256 $want in sherpa-onnx's $file" "sha256 (pinned)" >> "$MANIFEST"
    DEPS_TABLE="$DEPS_TABLE| $name | \`deps/$(basename "$got")\` | the archive sherpa-onnx pins (SHA-256 checked) |
"
  else
    # Every link failed: the same release from git, checked by its commit id.
    say "$name: archive unreachable, taking $repo $ref from git"
    out="$STAGE/deps/$name-git-${commit:0:12}.tar.gz"
    git_archive "$repo" "$ref" "$commit" "$name-${commit:0:12}" "$out"
    printf '%s\t%s\t%s\t%s\t%s\n' "deps/$(basename "$out")" "$(sha "$out")" "$repo" "$ref = commit $commit (the same source as the archive sherpa-onnx pins, $url, sha256 $want, which this machine couldn't download)" "git commit id" >> "$MANIFEST"
    DEPS_TABLE="$DEPS_TABLE| $name | \`deps/$(basename "$out")\` | \`git archive\` of $ref (commit \`$commit\`); sherpa-onnx's pinned archive \`$url\` (SHA-256 \`$want\`) holds the same source |
"
  fi
done <<< "$DEPS"

# --- ONNX Runtime. ------------------------------------------------------------------------------------------------------
if [ "$WITH_ORT" = 1 ]; then
  say "onnxruntime $ORT_VERSION ($ORT_COMMIT)"
  ORT_TGZ="$STAGE/onnxruntime-$ORT_VERSION.tar.gz"
  git_archive "$ORT_REPO" "refs/tags/v$ORT_VERSION" "$ORT_COMMIT" "onnxruntime-$ORT_VERSION" "$ORT_TGZ"
  printf '%s\t%s\t%s\t%s\t%s\n' "$(basename "$ORT_TGZ")" "$(sha "$ORT_TGZ")" "$ORT_REPO" "tag v$ORT_VERSION = commit $ORT_COMMIT" "git commit id" >> "$MANIFEST"
  ORT_ROW="| ONNX Runtime $ORT_VERSION | \`onnxruntime-$ORT_VERSION.tar.gz\` | \`git archive\` of tag v$ORT_VERSION (commit \`$ORT_COMMIT\`) |"
else
  ORT_ROW="| ONNX Runtime $ORT_VERSION | not in this archive | $ORT_REPO, tag v$ORT_VERSION (commit \`$ORT_COMMIT\`) |"
fi

# --- librobotmt.so: slimt and its parts, as tools/build_robotmt.sh takes them. -----------------------------------------
MT_TABLE=""
while read -r name repo commit want dir; do
  say "mt/$name ($commit)"
  out="$STAGE/mt/$name-${commit:0:12}.tar"
  git_tar "$repo" "$commit" "$commit" - "$want" "$out"
  printf '%s\t%s\t%s\t%s\t%s\n' "mt/$(basename "$out")" "$want" "$repo" "commit $commit (tools/build_robotmt.sh; unpacked at $dir in slimt's tree)" "git commit id and sha256 (pinned)" >> "$MANIFEST"
  MT_TABLE="$MT_TABLE| $name | \`mt/$(basename "$out")\` | $repo at \`$commit\`, unpacked at \`$dir\` of slimt's tree |
"
done <<<"$MT_SOURCES"

# --- librobot_llama.so: llama.cpp and KleidiAI, as tools/build_llama.sh takes them. -------------------------------------
LLAMA_TABLE=""
if [ "$WITH_LLAMA" = 1 ]; then
  mkdir -p "$STAGE/llama"
  say "llama.cpp $LLAMA_TAG ($LLAMA_COMMIT)"
  out="$STAGE/llama/llama.cpp-$LLAMA_TAG.tar"
  git_tar "$LLAMA_GIT" "refs/tags/$LLAMA_TAG" "$LLAMA_COMMIT" "llama.cpp-$LLAMA_TAG" "$LLAMA_TAR_SHA256" "$out"
  printf '%s\t%s\t%s\t%s\t%s\n' "llama/$(basename "$out")" "$LLAMA_TAR_SHA256" "$LLAMA_GIT" "tag $LLAMA_TAG = commit $LLAMA_COMMIT (tools/build_llama.sh)" "git commit id and sha256 (pinned)" >> "$MANIFEST"
  LLAMA_TABLE="| llama.cpp $LLAMA_TAG (with ggml) | \`llama/$(basename "$out")\` | \`git archive\` of tag $LLAMA_TAG (commit \`$LLAMA_COMMIT\`), the tar tools/build_llama.sh checks |
"
  say "KleidiAI $KLEIDIAI_TAG"
  out="$STAGE/llama/kleidiai-$KLEIDIAI_TAG-src.tar.gz"
  if curl -fsSL --retry 2 --connect-timeout 20 --max-time 1800 -o "$out" "$KLEIDIAI_URL" 2>/dev/null && [ "$(sha "$out")" = "$KLEIDIAI_TGZ_SHA256" ] \
      && [ "$(md5sum "$out" | cut -d' ' -f1)" = "$KLEIDIAI_TGZ_MD5" ]; then
    printf '%s\t%s\t%s\t%s\t%s\n' "llama/$(basename "$out")" "$KLEIDIAI_TGZ_SHA256" "$KLEIDIAI_URL" "release $KLEIDIAI_TAG (llama.cpp's ggml-cpu CMake names it)" "sha256 and md5 (pinned)" >> "$MANIFEST"
    LLAMA_TABLE="$LLAMA_TABLE| KleidiAI $KLEIDIAI_TAG | \`llama/$(basename "$out")\` | its release tarball, the one llama.cpp's CMake fetches (SHA-256 and MD5 checked) |
"
  else
    rm -f "$out"
    say "KleidiAI: release tarball unreachable, taking $KLEIDIAI_TAG from git"
    out="$STAGE/llama/kleidiai-$KLEIDIAI_TAG.tar"
    git_tar "$KLEIDIAI_GIT" "refs/tags/$KLEIDIAI_TAG" "$KLEIDIAI_COMMIT" "kleidiai-$KLEIDIAI_TAG" "$KLEIDIAI_TAR_SHA256" "$out"
    printf '%s\t%s\t%s\t%s\t%s\n' "llama/$(basename "$out")" "$KLEIDIAI_TAR_SHA256" "$KLEIDIAI_GIT" "tag $KLEIDIAI_TAG = commit $KLEIDIAI_COMMIT (the release tarball couldn't be downloaded)" "git commit id and sha256 (pinned)" >> "$MANIFEST"
    LLAMA_TABLE="$LLAMA_TABLE| KleidiAI $KLEIDIAI_TAG | \`llama/$(basename "$out")\` | \`git archive\` of tag $KLEIDIAI_TAG (commit \`$KLEIDIAI_COMMIT\`), the same source as its release tarball |
"
  fi
fi

# --- librobotzim.so: libzim and what it links, as tools/build_libzim.sh takes them (its own copy when it has one). ------
WIKI_TABLE=""
mkdir -p "$STAGE/wiki"
while read -r line; do
  eval "set -- ${line#fetch }"
  say "wiki/$1"
  if [ -f "$ROBOT/wiki/native/src/$1" ] && [ "$(sha "$ROBOT/wiki/native/src/$1")" = "$3" ]; then cp "$ROBOT/wiki/native/src/$1" "$STAGE/wiki/$1"
  else curl -fsSL --retry 2 --connect-timeout 20 --max-time 1800 -o "$STAGE/wiki/$1" "$2"; fi
  [ "$(sha "$STAGE/wiki/$1")" = "$3" ] || { echo "wiki/$1: sha256 $(sha "$STAGE/wiki/$1"), tools/build_libzim.sh pins $3" >&2; exit 1; }
  printf '%s\t%s\t%s\t%s\t%s\n' "wiki/$1" "$3" "$2" "release archive (tools/build_libzim.sh)" "sha256 (pinned)" >> "$MANIFEST"
  WIKI_TABLE="$WIKI_TABLE| ${1%%.t*} | \`wiki/$1\` | its release archive, $2, the one tools/build_libzim.sh checks |
"
done <<<"$WIKI_FETCH"

# --- Robot's own native sources and build scripts, at this commit. -------------------------------------------------------
ROBOT_COMMIT=$(git -C "$ROBOT" rev-parse HEAD 2>/dev/null || echo unknown)
ROBOT_TGZ="$STAGE/robot/robot-native-${ROBOT_COMMIT:0:12}.tar.gz"
# shellcheck disable=SC2086
(cd "$ROBOT" && tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner -cf - $ROBOT_FILES) | gzip -n -9 > "$ROBOT_TGZ"
[ -z "$(git -C "$ROBOT" status --porcelain -- $ROBOT_FILES 2>/dev/null)" ] && ROBOT_STATE="as committed" || ROBOT_STATE="with uncommitted changes"
printf '%s\t%s\t%s\t%s\t%s\n' "robot/$(basename "$ROBOT_TGZ")" "$(sha "$ROBOT_TGZ")" "Robot's repository" "commit $ROBOT_COMMIT, $ROBOT_STATE: $ROBOT_FILES" "-" >> "$MANIFEST"
MT_SHIPPED=$(built_sha mt librobotmt.so)
SHERPA_SHIPPED=$(built_sha voice libsherpa-onnx-jni.so)
LLAMA_SHIPPED=$(built_sha brain librobot_llama.so)

native_rows

# --- What Robot ships, and how it is built. ----------------------------------------------------------------------------
cat > "$STAGE/README.md" <<EOF
# Source of the third-party native code in Robot $VERSION

Robot is free software under the GNU GPL version 3. Its own source is in its repository at tag v$VERSION. Robot's APK also
contains native code from outside it: ONNX Runtime's prebuilt \`libonnxruntime.so\` and 17 compiled espeak-ng data files;
and four libraries Robot builds from pinned third-party source, \`libsherpa-onnx-jni.so\` (sherpa-onnx, into which
espeak-ng, GPL-3.0-or-later and changed by Robot's patch, and the libraries below are statically linked), \`librobotmt.so\` (slimt,
GPL-2.0-or-later, with ruy, cpuinfo and SentencePiece) \`librobotzim.so\` (libzim and Xapian, GPL-2.0-or-later, with Zstandard, liblzma and ICU) and, when built, \`librobot_llama.so\` (llama.cpp and KleidiAI).
For Robot to Robot it holds three more: \`libsignal_jni.so\` (libsignal, AGPL-3.0-only; Signal's build, Robot only strips its
debug symbols), \`libtor.so\` (Tor with OpenSSL, libevent and zlib; Briar's build, unchanged) and \`liblyrebird.so\` (lyrebird and
its Go modules, GPL-3.0-or-later, BSD and others; Robot's own build). "Every native library" below says where each one's source is.
This archive is their complete corresponding source, with the scripts that build them, offered alongside Robot
$VERSION's APK, at the same place, for as long as that APK is offered (GPL-3.0 section 6). Each component keeps its own licence, in
its own source; Robot's Settings › About › Licences lists them all.

## What Robot ships (sha256)

| File in the APK | sha256 | From |
|---|---|---|
| \`lib/arm64-v8a/libsherpa-onnx-jni.so\` | $SHERPA_SHIPPED | built by Robot's \`tools/build_sherpa.sh\` from the sources below, with Robot's \`third_party/patches/espeak-ng-ed530aa1-robot.patch\` applied to espeak-ng |
| \`lib/arm64-v8a/libonnxruntime.so\` | \`33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1\` | sherpa-onnx v$SHERPA_VERSION's release \`sherpa-onnx-v$SHERPA_VERSION-android.tar.bz2\` (sha256 \`2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44\`): ONNX Runtime $ORT_VERSION as built by csukuangfj/onnxruntime-libs (\`onnxruntime-android-$ORT_VERSION.zip\`, sha256 \`$ORT_PREBUILT_SHA\`, pinned in sherpa-onnx's \`cmake/onnxruntime-android-aarch64.cmake\`) |
| \`assets/espeak-ng-data/\` (17 files) | listed in Robot's THIRD_PARTY.md | sherpa-onnx's \`tts-models/espeak-ng-data.tar.bz2\` (sha256 \`4135ccf82e1f40613491c0874d4945ae9e9c7840933d8e25a6f9e003d9ebf533\`), unchanged |
| \`lib/arm64-v8a/librobotmt.so\` | $MT_SHIPPED | built by Robot's \`tools/build_robotmt.sh\` from \`mt/\` and Robot's \`mt/src/main/cpp\` (below) |
| \`lib/arm64-v8a/librobot_llama.so\` (only in a build that has it) | $LLAMA_SHIPPED | built by Robot's \`tools/build_llama.sh\` from \`llama/\` and Robot's \`brain/src/main/cpp\` (below) |
| \`lib/arm64-v8a/librobotzim.so\` | \`$ZIM_BUILT_SHA\` (tools/build_libzim.sh's pin, which verify_apk.sh holds every release to) | built by Robot's \`tools/build_libzim.sh\` from \`wiki/\` and Robot's \`wiki/src/main/cpp\` (below) |
| \`lib/arm64-v8a/libsignal_jni.so\` | \`$LIBSIGNAL_SO_PIN\` | Signal's own build, the arm64 library in \`org.signal:libsignal-android:$LIBSIGNAL_VERSION\`, stripped by Robot's \`link/build.gradle\` (\`llvm-strip --strip-unneeded\`) |
| \`lib/arm64-v8a/libtor.so\` | \`$(carrier_pin lib/arm64-v8a/libtor.so)\` | Briar's build, \`org.briarproject:tor-android:$TOR_VERSION\`, unchanged |
| \`lib/arm64-v8a/liblyrebird.so\` | \`$(carrier_pin lib/arm64-v8a/liblyrebird.so)\` | Robot's build of lyrebird $LYREBIRD_VERSION, \`tools/build_lyrebird.sh\`, reproducible |

Robot also copies sherpa-onnx's Kotlin API files unchanged (from \`sherpa-onnx/kotlin-api/\` in the source below); they are
in Robot's own repository.

## In this archive

| Component | File | How it was taken |
|---|---|---|
| sherpa-onnx v$SHERPA_VERSION | \`sherpa-onnx-$SHERPA_VERSION.tar.gz\` | \`git archive\` of commit \`$SHERPA_COMMIT\` (the tag) |
${DEPS_TABLE}${ORT_ROW}
${MT_TABLE}${LLAMA_TABLE}${WIKI_TABLE}${LINK_TABLE}| Robot's own native code and build scripts | \`robot/$(basename "$ROBOT_TGZ")\` | Robot's repository at commit \`$ROBOT_COMMIT\` ($ROBOT_STATE): $ROBOT_FILES |

\`MANIFEST.tsv\` has every file's SHA-256, where it came from and the pin it was checked against.

## Every native library, and where its source is

The release's script fails if an APK holds a library that isn't in this table.

| Library | Licence | Its source |
|---|---|---|
${NATIVE_TABLE}
Those marked upstream are Apache-2.0, which asks for no source, and aren't carried here.

## Building libsherpa-onnx-jni.so

Unpack \`robot/robot-native-*.tar.gz\` into Robot's source tree (or use the repository at tag v$VERSION) and run
\`tools/build_sherpa.sh\`. It takes sherpa-onnx's \`build-android-arm64-v8a.sh\`'s options (shared libraries on, TTS and speaker
diarization on, JNI on, \`-DANDROID_PLATFORM=android-21\`), Android NDK 27.1.12297006 and CMake 3.22.1, and builds espeak-ng from
the pinned archive with Robot's \`third_party/patches/espeak-ng-ed530aa1-robot.patch\` applied (the patch's first lines say what
it changes, and \`tools/espeak_bounds_check.sh\` shows it). sherpa-onnx's CMake files
(\`cmake/*.cmake\`) fetch each dependency above at the pinned archive and SHA-256. To build offline, put sherpa-onnx's
pinned archives in \`~/Downloads\` (or next to \`CMakeLists.txt\`), where those files look first: the files in \`deps/\`
marked "the archive sherpa-onnx pins" are those, byte for byte; the ones taken with \`git archive\` hold the same source
(the same commit) in another archive, so CMake's hash check needs the pinned archive, or its \`URL_HASH\` changed to
theirs. The script downloads the prebuilt ONNX Runtime it links (csukuangfj/onnxruntime-libs v$ORT_VERSION);
\`onnxruntime-$ORT_VERSION.tar.gz\` is its source (its own dependencies are pinned in its \`cmake/deps.txt\`). Whether that prebuilt library carries patches over the upstream
tag is not stated by its publisher.

espeak-ng's data files (\`phontab\`, \`phonindex\`, \`phondata\`, \`intonations\`, \`en_dict\`, \`fr_dict\` and the
language files) are compiled from espeak-ng's \`phsource/\` and \`dictsource/\`, in the espeak-ng source above (\`make\` in
espeak-ng builds them).

## Building librobotmt.so (slimt)

slimt is GPL-2.0-or-later (used under GPL-3.0) and holds MPL-2.0 code from browsermt/bergamot-translator and
browsermt/marian-dev and Apache-2.0 code from browsermt/ssplit; ruy and SentencePiece are Apache-2.0, cpuinfo is
BSD-2-Clause. Unpack \`robot/robot-native-*.tar.gz\` into Robot's source tree (or use the repository at tag v$VERSION) and
run \`tools/build_robotmt.sh\`: it fetches the four trees in \`mt/\` (the same tars, checked against the same SHA-256),
unpacks each where slimt's \`.gitmodules\` puts it (ruy at \`3rd-party/ruy\`, cpuinfo at
\`3rd-party/ruy/third_party/cpuinfo\`, SentencePiece at \`3rd-party/sentencepiece\`), and builds Robot's
\`mt/src/main/cpp\` with them (Android NDK 27.1.12297006, CMake 3.22.1, \`ANDROID_PLATFORM=android-34\`, \`c++_static\`,
\`-O3 -ffp-contract=off\`, slimt's NEON float code off) into one library. To build without the network, unpack the tars
yourself into \`mt/native/src/slimt\` at those places and run the script's build step (read the script: it is short).

## Building librobot_llama.so (llama.cpp)

llama.cpp and ggml are MIT, KleidiAI is Apache-2.0. Run Robot's \`tools/build_llama.sh\`: it fetches llama.cpp (the tar in
\`llama/\`, the same SHA-256) and KleidiAI (the release tarball in \`llama/\`, or the tag's tar), and builds Robot's
\`brain/src/main/cpp\` with them (Android NDK 27.1.12297006, \`ANDROID_PLATFORM=android-34\`, \`c++_static\`, CPU backend
only, KleidiAI kernels on). Without the network, unpack them into \`build-native/src/\` as the script names them
(\`llama.cpp-$LLAMA_TAG\`, \`kleidiai-$KLEIDIAI_TAG\`) with the \`.robot-checked\` mark it writes, and run it again.
$( [ "$WITH_LLAMA" = 1 ] || echo "This archive was made with --without-llama: llama.cpp ($LLAMA_GIT, tag $LLAMA_TAG, commit \`$LLAMA_COMMIT\`) and KleidiAI ($KLEIDIAI_GIT, tag $KLEIDIAI_TAG) are not in it." )

## Building librobotzim.so (libzim)

libzim and Xapian are GPL-2.0-or-later (used under GPL-3.0), Zstandard is BSD-3-Clause (or GPL-2.0-only), XZ Utils'
liblzma is 0BSD and ICU is under the Unicode licence v3; Meson (Apache-2.0) only builds libzim and isn't in the APK. Run
Robot's \`tools/build_libzim.sh\`: it fetches the archives in \`wiki/\` (the same SHA-256), cuts ICU's data with
\`wiki/src/main/cpp/icu-filter.json\` and builds Robot's \`wiki/src/main/cpp\` with them, all static (Android NDK
27.1.12297006, \`ANDROID_PLATFORM=android-34\`). Without the network, copy them into \`wiki/native/src/\` and run it: it
fetches only what isn't there.

## Building libsignal

Robot doesn't build libsignal: it takes Signal's published library and strips it (\`link/build.gradle\`, its \`libsignalJni\`
task). It does build from here with the network cut off: this was done for v0.105.2 on x86-64 Linux (\`unshare -rn\`, an
empty \`CARGO_HOME\`), about 100 minutes on two cores at low priority and 3.5 GB of disk.

This archive holds the sources and every crate. It doesn't hold the tools, which must be installed first: Rust
$CARGO_VERSION (\`rust-toolchain\`; the standalone \`rust\` and \`rust-std-$CARGO_VERSION-aarch64-linux-android\` installers
from static.rust-lang.org need no rustup), the Android NDK (\`java/android/build.gradle\` names 28.0.13004108; the build
above used 27.1.12297006), and cmake, make, clang with libclang, protoc and python3.

\`\`\`
tar -xzf libsignal/libsignal-$LIBSIGNAL_VERSION.tar.gz
cd libsignal-$LIBSIGNAL_VERSION
tar -xzf ../libsignal/libsignal-$LIBSIGNAL_VERSION-vendor.tar.gz   # makes vendor/
cat ../libsignal/cargo-config.toml >> .cargo/config.toml
export CARGO_NET_OFFLINE=true CARGO_HOME="\$PWD/.cargo-home" ANDROID_NDK_HOME=/path/to/ndk
mkdir -p "\$CARGO_HOME/registry/src"   # bin/build_helpers.py stops if it's missing
java/build_jni.sh android-arm64        # java/android/src/main/jniLibs/arm64-v8a/libsignal_jni.so
\`\`\`

\`cargo fetch\` fails at the start of that script without the network, and the script carries on. To compare with what
Robot ships, run \`llvm-strip --strip-unneeded\` on the result.

The result isn't Signal's bytes: the same 660 exported symbols and the same layout, 9,405,632 bytes stripped against
9,390,016, different hashes. The differences seen are the compiler (Signal's library says NDK r28's clang 19, the build
above r27's clang 18) and the paths in its panic messages (\`vendor/\` here, cargo's registry there, and the build folder).
Nobody has reproduced Signal's published bytes: the tag, its Cargo.lock and the pins are what ties them to this source.

## Tor and lyrebird

Briar builds Tor (https://code.briarproject.org/briar/tor-reproducer). \`tor/\` holds Tor, OpenSSL, libevent and zlib as that
build took them. \`lyrebird/\` holds lyrebird with its Go modules in \`vendor/\`, so \`go build -mod=vendor ./cmd/lyrebird\` needs
no network, with the Go source of the version it was built with. Robot's \`tools/build_lyrebird.sh\`, in \`robot/\` here, is the
exact build, with its flags, and gets the pinned bytes. This archive's script compares that source with the commit and with
every module's zip by the hash the shipped executable holds; it rebuilds neither.

Made by Robot's \`tools/make_source_bundle.sh\`.
EOF

pack
