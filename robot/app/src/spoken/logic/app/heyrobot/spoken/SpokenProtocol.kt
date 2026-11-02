// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.spoken

import java.io.File

/**
 * The test build's side of tools/spoken/spoken.json. SpokenHarnessTest holds these to that file, as the receiver's lock must
 * match the runner's.
 */
object SpokenProtocol {
    const val VERSION = 1
    const val RESULTS = "spoken/results.jsonl"
    const val CLIPS = "spoken/clips"
    const val RECORDINGS = "spoken/recordings"

    /** A clip's name, as tools/spoken/clips.py makes it: nothing else is ever opened, so no path can come in with it. */
    private val CLIP_NAME = Regex("""(say|no)-[0-9a-f]{16}\.s16""")
    const val MAX_CLIP_BYTES = 60 * 16_000 * 2

    /** What a clip may be called; only a `no-` clip plays while a card that isn't harmless shows. */
    fun clipName(name: String?): Boolean = name != null && CLIP_NAME.matches(name)
    fun isNo(clip: String): Boolean = clip.startsWith("no-")

    /** spoken.json's harmlessCards. A kind that isn't here, known or not, stops every clip but a no. */
    val HARMLESS_CARDS: Set<String> = setOf("flashlight", "lookup_contact",
        "add_note", "add_to_list", "find_notes", "read_calendar", "weather", "phone_status", "remember", "find_files", "read_file", "calculate")

    /** s16le, 16 kHz, mono, read whole. Null when it isn't an even number of bytes or is longer than a minute. */
    fun readClip(f: File): ShortArray? {
        if (!f.isFile || f.length() > MAX_CLIP_BYTES || f.length() % 2 != 0L) return null
        val b = f.readBytes()
        return ShortArray(b.size / 2) { i -> ((b[2 * i + 1].toInt() shl 8) or (b[2 * i].toInt() and 0xff)).toShort() }
    }
}

/**
 * Every guess the phone hasn't confirmed (ST1 checks them), in one place, so a wrong one is a small change here:
 *
 * - Settled on a Pixel 8, Android 17 (ST1): `am broadcast` from the adb shell reaches the receiver with getSentFromUid() -1, since
 *   Android shows only a sender that opted in and `am` can't, so [senderAllowed] takes a hidden sender. The manifest's DUMP is
 *   what keeps apps out: an ordinary app's broadcast never arrives. A sender that names itself is refused unless it's the shell.
 * - [IMPORT]: where adb can leave models: `adb exec-in run-as <the build> sh -c 'cat > files/spoken/import/…'`, as the clips go.
 *
 * On the computer's side, tools/spoken/run.py's Role assumes `cmd role add-role-holder` works from adb on Android 17.
 */
object SpokenAssumptions {
    const val SHELL_UID = 2000
    const val HIDDEN_UID = -1
    const val IMPORT = "spoken/import"

    fun senderAllowed(sentFromUid: Int): Boolean = sentFromUid == SHELL_UID || sentFromUid == HIDDEN_UID
}
