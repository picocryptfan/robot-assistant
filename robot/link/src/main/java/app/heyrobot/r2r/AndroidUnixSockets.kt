// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

// :LINK PROCESS. 1.5, Robot to Robot (job L3): filesystem unix sockets for Tor, on the phone. Tor's control and SOCKS
// sockets and the onion services' targets are files in :link's private folder (0700), so no other app can reach them; never
// the abstract namespace (any app could connect there) and never a TCP port. Whether SELinux lets an app make socket files
// in its own folder and Tor (its child) connect to them on GrapheneOS: checked on the phone (L3's phone checks).

class AndroidUnixSockets : UnixSockets {
    override fun connect(path: File): Duplex {
        val s = LocalSocket(LocalSocket.SOCKET_STREAM)
        try {
            s.connect(LocalSocketAddress(path.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            return LocalDuplex(s)
        } catch (_: IOException) {
            try { s.close() } catch (_: IOException) {}
            throw IOException()
        }
    }

    override fun listen(path: File): UnixServer {
        path.delete()
        val bound = LocalSocket(LocalSocket.SOCKET_STREAM)
        try {
            bound.bind(LocalSocketAddress(path.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            Os.chmod(path.absolutePath, 0x180) // 0600: this app alone
            return LocalServer(LocalServerSocket(bound.fileDescriptor), bound, path)
        } catch (_: Exception) {
            try { bound.close() } catch (_: IOException) {}
            path.delete()
            throw IOException()
        }
    }

    override fun makePrivate(dir: File) {
        try { Os.chmod(dir.absolutePath, 0x1C0) } catch (_: Exception) { throw IOException() } // 0700
    }

    /** Today H7b: the stream's own descriptor, so :link can hand a feed's stream through Tor to the process that fetches it. */
    interface Described { val descriptor: java.io.FileDescriptor }

    private class LocalDuplex(private val s: LocalSocket) : Duplex, Described {
        override val descriptor: java.io.FileDescriptor get() = s.fileDescriptor
        override val input: InputStream = s.inputStream
        override val output: OutputStream = s.outputStream
        override fun readTimeout(ms: Int) { s.soTimeout = ms }
        override fun close() { try { s.close() } catch (_: IOException) {} }
    }

    private class LocalServer(private val server: LocalServerSocket, private val bound: LocalSocket, private val path: File) : UnixServer {
        override fun accept(): Duplex = LocalDuplex(server.accept())

        override fun close() {
            // A thread blocked in accept() wakes only once the socket is shut down; closing alone may leave it waiting.
            try { Os.shutdown(server.fileDescriptor, OsConstants.SHUT_RDWR) } catch (_: Exception) {}
            try { server.close() } catch (_: IOException) {}
            try { bound.close() } catch (_: IOException) {}
            path.delete()
        }
    }
}
