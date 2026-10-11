package app.heyrobot.core

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pure. free_memory and free_storage (0.4.0): what Robot can really do about memory and storage on Android 14 and later,
// and the words for it. Memory: Robot unloads its own model (about 2 GB) once the answer that asked for it has ended;
// it can't stop other apps (since Android 14 killBackgroundProcesses only reaches the caller's own processes), so for an
// app you name it opens that app's info page, where you tap Force stop. Storage: Android's own "Free up space" screen,
// an app's info page to clear its cache, a large photo or video deleted through Android's own confirmation, or Robot's
// cache. Every step that deletes or stops shows a card first. A file's name is shown on the card only: never saved,
// never sent to the model or to Claude, never logged.

/** free_storage's ways, as its `what` argument names them. */
enum class StorageWay {
    /** Android's "Free up space" screen (StorageManager.ACTION_MANAGE_STORAGE, else storage settings). */
    ANDROID,
    /** An app's info page, to clear its cache: the card lists the apps using the most space (Usage access). */
    APPS,
    /** A large photo or video, deleted after Android's own confirmation (the media permission). */
    FILES,
    /** Robot's own cache. */
    ROBOT;

    companion object {
        /** Anything else, or nothing, is Android's own screen: it only opens. */
        fun of(text: String?): StorageWay {
            val t = FreeUp.plain(text.orEmpty())
            return when {
                Regex("""\brobots?\b""").containsMatchIn(t) -> ROBOT
                Regex("""\b(files?|photos?|pictures?|videos?|media|large|big|downloads?|fichiers?|images?|videos?)\b""").containsMatchIn(t) -> FILES
                Regex("""\b(apps?|applications?|cache|caches)\b""").containsMatchIn(t) -> APPS
                else -> ANDROID
            }
        }
    }
}

/** A photo or video in shared storage (MediaStore): [uri] is its content URI, [name] shown on the card only. */
data class MediaFile(val uri: String, val name: String, val bytes: Long, val date: Long?, val video: Boolean)

object FreeUp {
    /** Recently used apps listed at most. */
    const val RECENT_APPS = 5
    /** Large files offered at most. */
    const val LARGE_FILES = 5

    /**
     * Messaging apps, never listed among the apps you could stop (only one you name): Cheogram, Signal and Molly,
     * Conversations, WhatsApp, Telegram, Element, SimpleX, Briar, Threema, Session. The default SMS app is added by the app.
     */
    val MESSAGING = setOf("com.cheogram.android", "com.cheogram.android.playstore", "org.thoughtcrime.securesms", "im.molly.app",
        "eu.siacs.conversations", "com.whatsapp", "org.telegram.messenger", "org.thunderdog.challegram", "im.vector.app",
        "chat.simplex.app", "org.briarproject.briar.android", "ch.threema.app", "network.loki.messenger")

    /** free_memory's app argument, or null when it means Robot's own model (or nothing at all). */
    fun appOrNull(text: String?): String? {
        val t = plain(text.orEmpty())
        if (t.isEmpty() || t in OWN) return null
        return text?.replace(Regex("\\s+"), " ")?.trim()
    }

    private val OWN = setOf("robot", "robots model", "robot model", "the model", "model", "gemma", "yourself", "itself", "memory", "ram",
        "all", "everything", "background", "background apps", "apps", "other apps", "none")

    /** A card button for a file: "IMG_2041.jpg · 12 MB · Mar 1, 2026" (the name cut to 40 characters). */
    fun fileChoice(f: MediaFile, zone: ZoneId): String =
        listOfNotNull(NoteSearch.cut(f.name.replace(Regex("\\s+"), " ").trim().ifEmpty { if (f.video) "A video" else "A photo" }, 40),
            PhoneStatus.size(f.bytes), f.date?.let { day(it, zone) }).joinToString(" · ")

    /** What the card says (and saves) once a file is picked: no name. "Delete a video (1.2 GB, Mar 1, 2026)". */
    fun fileSummary(f: MediaFile, zone: ZoneId): String =
        "Delete a ${if (f.video) "video" else "photo"} (" + listOfNotNull(PhoneStatus.size(f.bytes), f.date?.let { day(it, zone) }).joinToString(", ") + ")"

    /** A card button for an app: "Signal · 12 GB, cache 1.2 GB". */
    fun appChoice(a: AppSize): String = "${NoteSearch.cut(a.label.replace(Regex("\\s+"), " ").trim(), 30)} · ${PhoneStatus.size(a.bytes)}" +
        if (a.cacheBytes >= 1_000_000) ", cache ${PhoneStatus.size(a.cacheBytes)}" else ""

    /** "Chrome, YouTube and Maps". */
    fun names(labels: List<String>): String = when (labels.size) {
        0 -> ""
        1 -> labels[0]
        else -> labels.dropLast(1).joinToString(", ") + " and " + labels.last()
    }

    /** The free_memory card once Robot's model is unloaded: what Android reports came back. */
    fun freedLine(before: Long, after: Long): String {
        val freed = after - before
        return if (freed >= 100_000_000) "Freed about ${PhoneStatus.size(freed)}: ${PhoneStatus.size(after)} of memory available now."
        else "Robot's model is unloaded. Android hasn't counted the memory as free yet (${PhoneStatus.size(after)} available)."
    }

    /** The unload card's line until the answer ends; then it says what came back ([freedLine]). */
    const val UNLOADING = "Robot's model unloads when this answer ends (about 2 GB)."

    /** What the model is told about freeing memory: what Robot did, and what Android does by itself. */
    const val MEMORY_FOR_MODEL = "Robot's own model (about 2 GB) is unloaded as soon as this answer ends and loads again for the next question. " +
        "Android frees memory by itself when it needs it. Robot can't stop other apps (Android doesn't allow it), but it can open an app's " +
        "info page where the person taps Force stop: that helps most when one app misbehaves, and the app may start again."

    private val DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    private fun day(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).format(DAY)

    fun plain(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
        .replace("'", "").replace("’", "").replace(Regex("[^a-z0-9]+"), " ").trim()
}
