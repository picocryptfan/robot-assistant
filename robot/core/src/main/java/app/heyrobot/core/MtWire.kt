// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import android.os.Bundle
import android.os.Message

// Android side of MtProtocol: Messenger messages carrying the JSON payload (lines of text only; the :mt process reads
// its model files itself, from the paths core MtBundle gives).

object MtWire {
    fun message(what: Int, reqId: Int, payload: MtMsg): Message = Message.obtain(null, what).apply {
        arg1 = reqId
        data = Bundle().apply { putString(MtProtocol.KEY_JSON, payload.toJson().toString()) }
    }

    fun decode(m: Message): MtMsg? = MtMsg.decode(m.what, m.data?.getString(MtProtocol.KEY_JSON))
}
