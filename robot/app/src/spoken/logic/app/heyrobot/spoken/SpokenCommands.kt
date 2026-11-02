// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.spoken

import org.json.JSONArray
import org.json.JSONObject

/** What the commands reach in the running app (SpokenHarness on a phone, a stand-in in the tests). */
interface SpokenPort {
    /** The kinds of the cards waiting now, or null if that can't be read. */
    fun cards(): List<String>?
    /** The panel shows Robot listening, the sign a person goes by, for a question or a card's answer. */
    fun listening(): Boolean
    fun listenModel(): Boolean
    fun gemmaModel(): Boolean
    fun resetSettings()
    /** False when it's not on the short list. */
    fun setSetting(name: String?, value: String?): Boolean
    /** Null when the panel was asked to open, else why it can't. */
    fun open(how: String): String?
    fun clip(name: String): ShortArray?
    /** Cancels a waiting card, never confirms it, and ends the conversation. */
    fun end()
    /** Starts importing and returns how many files will be tried. */
    fun importModels(): Int
    /** The recorder holds the microphone: no clip plays. */
    val recording: Boolean
}

/** The protocol's commands: every one gets exactly one ack, as soon as it's taken or refused (tools/spoken/README.md). */
class SpokenCommands(private val port: SpokenPort, private val results: SpokenResults, private val feed: SpokenFrameFeed) {
    fun handle(seq: Int, cmd: String?, arg: String?, value: String?) {
        try {
            when (val why = run(seq, cmd, arg, value)) {
                null -> {}
                else -> results.refuse(seq, why)
            }
        } catch (t: Throwable) {
            // Nothing was confirmed: a clip not played and a card left waiting are the safe side of a failure.
            results.refuse(seq, t.javaClass.simpleName)
        }
    }

    /** Null when it acked. */
    private fun run(seq: Int, cmd: String?, arg: String?, value: String?): String? = when (cmd) {
        "status" -> done(seq, JSONObject().put("protocol", SpokenProtocol.VERSION)
            .put("models", JSONObject().put("listen", port.listenModel()).put("gemma", port.gemmaModel()))
            .put("cards", port.cards()?.let { JSONArray(it) } ?: JSONObject.NULL)
            .put("listening", port.listening()))
        "reset" -> { feed.clear(); port.end(); port.resetSettings(); done(seq) }
        "set" -> if (port.setSetting(arg, value)) done(seq) else "not on the short list"
        "open" -> if (arg != "wake" && arg != "button") "no such way" else port.open(arg) ?: done(seq)
        "play" -> play(seq, arg)
        "end" -> { feed.clear(); port.end(); done(seq) }
        "import-models" -> done(seq, JSONObject().put("files", port.importModels()))
        else -> "no such command"
    }

    private fun play(seq: Int, name: String?): String? {
        if (!SpokenProtocol.clipName(name)) return "not a clip's name"
        name!!
        if (port.recording) return "the recorder has the microphone"
        if (!SpokenCards.allows(name, port.cards())) return "a card that isn't harmless is showing"
        val clip = port.clip(name) ?: return "no such clip"
        feed.enqueue(name, clip)
        return done(seq)
    }

    private fun done(seq: Int, data: JSONObject? = null): String? {
        results.ack(seq, data)
        return null
    }
}
