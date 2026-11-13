#!/bin/bash
# libzim_fd.sh patch|check <libzim source dir> <work dir>: Robot's one change to libzim 9.3.0 (fix-wiki-rc2), applied by
# build_libzim.sh. libzim opens a part given as a descriptor by the name "/dev/fd/N", a new open() of Robot's file, which
# the isolated :wiki process (its own uid, no rights to Robot's files) is refused: every copy answered "not a ZIM". The
# patch makes FS::openFile("/dev/fd/N") a dup of N instead (FilePart and the Xapian index both come through it). check
# builds wiki/src/main/cpp/libzim_fd_check.cpp against the source with the host compiler and runs it: it fails unpatched.
set -euo pipefail
MODE="${1:?patch or check}"; SRC="${2:?libzim source dir}"; W="${3:?work dir}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
case "$MODE" in
patch) python3 - "$SRC/src/fs_unix.cpp" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = '  int fd = open(filepath.c_str(), O_RDONLY);\n'
new = ('  // Robot (tools/libzim_fd.sh): "/dev/fd/N" is a dup of N, never a reopen by name, which the isolated :wiki process\n'
       '  // is refused.\n'
       '  int fd = filepath.compare(0, 8, "/dev/fd/") == 0\n'
       '      ? fcntl(std::stoi(filepath.substr(8)), F_DUPFD_CLOEXEC, 0)\n'
       '      : open(filepath.c_str(), O_RDONLY);\n')
assert s.count(old) == 1, "libzim's FS::openFile changed: Robot's descriptor patch no longer applies"
open(p, 'w').write(s.replace(old, new))
PY
  ;;
check)
  mkdir -p "$W/include/zim"
  printf '#pragma once\n#define LIBZIM_VERSION "check"\n' > "$W/include/zim/zim_config.h"  # meson writes the real one later
  c++ -std=c++17 -O1 -I"$SRC/src" -I"$SRC/include" -I"$W/include" -o "$W/libzim_fd_check" "$HERE/wiki/src/main/cpp/libzim_fd_check.cpp" "$SRC/src/fs_unix.cpp"
  "$W/libzim_fd_check" "$W" ;;
*) echo "usage: libzim_fd.sh patch|check <libzim source dir> <work dir>" >&2; exit 2 ;;
esac
