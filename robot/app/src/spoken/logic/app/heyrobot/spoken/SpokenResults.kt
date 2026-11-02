// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.spoken

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** files/spoken/results.jsonl: one JSON object a line, each written in one go. The runner deletes the file before each test. */
class SpokenResults(private val file: File) {
    @Synchronized fun line(o: JSONObject) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { it.write((o.toString() + "\n").toByteArray(Charsets.UTF_8)) }
    }

    fun ack(seq: Int, data: JSONObject? = null) = line(JSONObject().put("type", "ack").put("seq", seq).put("ok", true).also { o -> data?.let { o.put("data", it) } })

    fun refuse(seq: Int, why: String) = line(JSONObject().put("type", "ack").put("seq", seq).put("ok", false).put("why", why))
}
