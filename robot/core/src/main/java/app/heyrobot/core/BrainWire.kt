package app.heyrobot.core

import android.os.Bundle
import android.os.Message
import android.os.SharedMemory
import android.system.OsConstants
import java.nio.ByteBuffer

// Android side of BrainProtocol: Messenger messages carrying the JSON payload and, for pictures, a
// SharedMemory region (so image bytes never go through a file).

object BrainWire {
    fun message(what: Int, reqId: Int, payload: BrainMsg, image: SharedMemory? = null): Message =
        Message.obtain(null, what).apply {
            arg1 = reqId
            data = Bundle().apply {
                putString(BrainProtocol.KEY_JSON, payload.toJson().toString())
                image?.let { putParcelable(BrainProtocol.KEY_IMAGE, it) }
            }
        }

    fun decode(m: Message): BrainMsg? = BrainMsg.decode(m.what, m.data?.getString(BrainProtocol.KEY_JSON))

    /** Copies [bytes] into a new read-only SharedMemory region (the caller closes it after sending). */
    fun share(bytes: ByteArray): SharedMemory {
        val mem = SharedMemory.create("robot-image", bytes.size)
        val buf = mem.mapReadWrite()
        try { buf.put(bytes) } finally { SharedMemory.unmap(buf) }
        mem.setProtect(OsConstants.PROT_READ)
        return mem
    }

    /** The image bytes of a message, or null; closes the region. */
    fun takeImage(m: Message): ByteArray? {
        val mem = try {
            m.data?.let { b ->
                b.classLoader = SharedMemory::class.java.classLoader
                b.getParcelable(BrainProtocol.KEY_IMAGE, SharedMemory::class.java)
            }
        } catch (_: Exception) { null } ?: return null
        return try {
            val buf: ByteBuffer = mem.mapReadOnly()
            try { ByteArray(buf.remaining()).also { buf.get(it) } } finally { SharedMemory.unmap(buf) }
        } catch (_: Exception) {
            null
        } finally {
            mem.close()
        }
    }
}
