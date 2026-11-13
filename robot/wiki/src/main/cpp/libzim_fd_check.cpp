// SPDX-License-Identifier: GPL-3.0-only
// Host check for Robot's libzim patch (tools/libzim_fd.sh): libzim names a part opened from a descriptor "/dev/fd/N" and
// opens that name again (FilePart, and the Xapian index in getDbFromAccessInfo). In the isolated :wiki process that
// reopen is refused (another uid, Robot's file is 0600), so every copy read as "not a ZIM" (fix-wiki-rc2). Here a file
// this user can no longer open (mode 000) stands in for it: its descriptor must still be readable through openFile.
#include "fs_unix.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <sys/stat.h>
#include <unistd.h>

int main(int argc, char** argv) {
    if (argc < 2) return 2;
    std::string path = std::string(argv[1]) + "/fdcheckXXXXXX";
    int fd = mkstemp(&path[0]);
    if (fd < 0 || write(fd, "ZIM\x04", 4) != 4 || fchmod(fd, 0) != 0) { std::perror("setup"); return 2; }
    if (geteuid() != 0 && open(path.c_str(), O_RDONLY) >= 0) { std::fprintf(stderr, "setup: mode 000 still opens\n"); return 2; }
    int rc = 1;
    try {
        zim::unix::FD h = zim::unix::FS::openFile(zim::getFilePathFromFD(fd));
        char b[4] = {0};
        h.readAt(b, zim::zsize_t(4), zim::offset_t(0));
        rc = std::memcmp(b, "ZIM\x04", 4) == 0 && h.getSize().v == 4 ? 0 : 1;
        std::fprintf(stderr, rc == 0 ? "ok  a descriptor is read without reopening its file\n" : "FAIL wrong bytes through the descriptor\n");
    } catch (const std::exception& e) {
        std::fprintf(stderr, "FAIL libzim reopens the descriptor's file by name (refused in the :wiki process)\n");
    }
    unlink(path.c_str());
    return rc;
}
