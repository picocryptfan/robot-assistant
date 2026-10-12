package app.heyrobot.core

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pure. free_memory and free_storage (0.4.0): what Robot can really do about memory and storage on Android 14 and later,
// and the words for it. Memory: Robot unloads its own model (about 2 GB; 4 GB for an E4B, 0.4.1) once the answer that asked for it has ended;
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
                // "big apps" are apps; "big" or "large" alone are files.
                Regex("""\b(apps?|applications?|applis?)\b""").containsMatchIn(t) -> APPS
                Regex("""\b(files?|photos?|pictures?|videos?|media|large|big|biggest|largest|downloads?|fichiers?|images?)\b""").containsMatchIn(t) -> FILES
                Regex("""\b(cache|caches)\b""").containsMatchIn(t) -> APPS
                else -> ANDROID
            }
        }
    }
}

/**
 * 0.7.0: an app used lately (Usage access) that is likely still in memory, as "free up memory" offers it: its name, its
 * package (for its App info), when it was last used and how long it was on screen today.
 */
data class RecentApp(val label: String, val pkg: String, val lastUsed: Long, val foregroundMs: Long = 0)

/** A photo or video in shared storage (MediaStore): [uri] is its content URI, [name] shown on the card only. */
data class MediaFile(val uri: String, val name: String, val bytes: Long, val date: Long?, val video: Boolean)

object FreeUp {
    /** Recently used apps listed at most. */
    const val RECENT_APPS = 5
    /** 0.7.0: how far back "free up memory" looks for apps still likely in memory (Android drops the oldest first). */
    const val RECENT_MS = 3 * 3_600_000L
    /** Large files offered at most. */
    const val LARGE_FILES = 5

    /**
     * Messaging apps, never listed among the apps you could stop (only one you name): Cheogram, Signal and Molly,
     * Conversations, WhatsApp, Telegram, Element, SimpleX, Briar, Threema, Session. The default SMS app is added by the app.
     */
    val MESSAGING = setOf("com.cheogram.android", "com.cheogram.android.playstore", "org.thoughtcrime.securesms", "im.molly.app",
        "eu.siacs.conversations", "com.whatsapp", "org.telegram.messenger", "org.thunderdog.challegram", "im.vector.app",
        "chat.simplex.app", "org.briarproject.briar.android", "ch.threema.app", "network.loki.messenger")

    /**
     * free_memory's app argument, or null when it means Robot's own model (or nothing at all). "Robot" alone is an app
     * name ("Stop Robot"), which the app refuses; "Robot's model" or "Robot's memory" is the model.
     */
    fun appOrNull(text: String?): String? {
        val t = plain(text.orEmpty())
        if (t.isEmpty() || t in OWN) return null
        return text?.replace(Regex("\\s+"), " ")?.trim()
    }

    private val OWN = setOf("robots model", "robot model", "robots memory", "robot memory", "the model", "model", "gemma", "your model",
        "yourself", "itself", "memory", "ram",
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

    /** Robot among the largest apps: most of it is its models, which clearing its storage would delete. */
    const val ROBOT_LABEL = "Robot (models, don't clear)"

    /** A picked file's card until Android's confirmation answers; then [DELETED] or [KEPT]. */
    const val DELETE_WAITING = "Waiting for your answer in Android's confirmation: it's deleted only if you allow it there."
    const val DELETED = "Deleted."
    const val KEPT = "Not deleted: you didn't allow it."

    /** The unload card's line until the answer ends; then it says what came back ([freedLine]). */
    const val UNLOADING = "Robot's model unloads when this answer ends (about 2 GB, 4 GB for an E4B model)."

    /** What the model is told about freeing memory: what Robot did, and what Android does by itself. */
    const val MEMORY_FOR_MODEL = "Robot's own model (about 2 GB, or 4 GB for an E4B model) is unloaded as soon as this answer ends and loads again for the next question. " +
        "Android frees memory by itself when it needs it. Robot can't stop other apps (Android doesn't allow it), but it can open an app's " +
        "info page where the person taps Force stop: that helps most when one app misbehaves, and the app may start again."

    // ------------------------------------------------------------------ 0.7.0: "free up memory", with recommendations

    /**
     * "Free up memory", "close apps", "my phone is slow", « libère de la mémoire », « ferme des applis », « mon téléphone
     * est lent »: Robot answers itself, before the model, with what Android reports and what can be done (the tester's
     * 0.6.0 report: the model gave no recommendations). Anchored at the start, with only filler in front; "free up space"
     * or "storage" is free_storage's, and Robot's own Memory ("forget …") is memory's. Null: not this; true: asked in French.
     */
    fun asked(text: String): Boolean? {
        var t = plain(text)
        while (true) {
            val m = LEAD.find(t) ?: break
            if (m.value.isEmpty()) break
            t = t.substring(m.value.length).trim()
        }
        t = t.replace(TAIL, "").trim()
        return when {
            ASK_EN.any { it.matches(t) } -> false
            ASK_FR.any { it.matches(t) } -> true
            else -> null
        }
    }

    private val LEAD = Regex("""^(?:hey robot|ok robot|robot|hey|please|can you|could you|would you|will you|help me|i want to|i need to|id like to|""" +
        """peux tu|pourrais tu|tu peux|est ce que tu peux|aide moi a|je veux|jaimerais|sil te plait|svp)\b\s*""")
    private val TAIL = Regex("""\s*\b(?:please|for me|now|sil te plait|svp|stp|pour moi|maintenant)$""")
    private val ASK_EN = listOf(
        Regex("""^(?:free|free up|clear up|clean up|release|get back|make)(?: some| more| up)? (?:memory|ram|phone memory)(?: on (?:my|the) phone)?$"""),
        Regex("""^(?:close|quit|kill|stop|shut down|shut)(?: some| all| my| the| other| unused)*(?: background| running| open| other)* (?:apps|applications)$"""),
        Regex("""^(?:my |the )?phone (?:is|feels|seems|got|is getting|is running) (?:so |really |very )?(?:slow|sluggish|laggy)$"""),
        Regex("""^what(?:s| is) (?:using|taking|eating|hogging) (?:all |up )?(?:my |the )?(?:memory|ram)$"""),
        Regex("""^(?:im|i am|my phone is|the phone is) (?:low|running low|almost out|out) (?:on|of) (?:memory|ram)$"""),
    )
    private val ASK_FR = listOf(
        Regex("""^(?:libere|liberer|libere moi|vide|recupere|recuperer|fais|faire)(?: un peu| de la| la| plus)*(?: de)? (?:memoire|ram|memoire vive)(?: du telephone| sur mon telephone)?$"""),
        Regex("""^(?:ferme|fermer|ferme moi|quitte|arrete|tue)(?: des| les| mes| quelques| toutes les| tous les| les autres)* (?:applis|applications|apps)(?: ouvertes| en arriere plan| inutiles)?$"""),
        Regex("""^(?:mon |le )?(?:telephone|cell|cellulaire) (?:est|devient|est vraiment|est tellement) (?:lent|lente|tres lent)$"""),
        Regex("""^(?:quest ce qui|quest ce quil y a qui|quoi) (?:utilise|prend|bouffe|mange) (?:toute )?(?:la |ma )?(?:memoire|ram)$"""),
        Regex("""^(?:je manque|il me manque|jai plus|jai pu|jai pas assez) (?:de )?(?:memoire|ram)$"""),
    )

    /**
     * The apps of [apps] likely still in memory, the ones to stop first: used in the last [RECENT_MS], the most recently
     * used first (Android keeps those cached longest), then the longer on screen; [n] at most, each app once.
     */
    fun likelyInMemory(apps: List<RecentApp>, now: Long, n: Int = RECENT_APPS): List<RecentApp> =
        apps.filter { now - it.lastUsed in 0..RECENT_MS }.groupBy { it.pkg }.map { (_, v) -> v.maxBy { it.lastUsed } }
            .sortedWith(compareByDescending<RecentApp> { it.lastUsed }.thenByDescending { it.foregroundMs }).take(n)

    /** "5 min ago", "il y a 5 min", "2 h ago". */
    fun ago(ms: Long, fr: Boolean): String {
        val min = (ms.coerceAtLeast(0) / 60_000L).toInt()
        val v = when {
            min < 1 -> if (fr) "à l'instant" else "just now"
            min < 60 -> "$min min"
            else -> "${min / 60} h" + (min % 60).let { if (it >= 5) " ${it.toString().padStart(2, '0')}" else "" }
        }
        return if (min < 1) v else if (fr) "il y a $v" else "$v ago"
    }

    /** A size as Québec French writes it: "2,1 Go", "340 Mo". */
    fun sizeFr(bytes: Long): String = PhoneStatus.size(bytes).replace(" GB", " Go").replace(" MB", " Mo").replace(" KB", " ko").replace('.', ',')

    private fun size(bytes: Long, fr: Boolean) = if (fr) sizeFr(bytes) else PhoneStatus.size(bytes)

    /** "5.1 GB of 12 GB of memory is free (Android says memory is low)." · « 5,1 Go libres sur 12 Go de mémoire. » */
    fun memoryNow(m: MemoryFacts, fr: Boolean): String =
        if (fr) "${size(m.available, true)} libres sur ${size(m.total, true)} de mémoire" + (if (m.low) " (Android dit que la mémoire est basse)" else "") + "."
        else "${size(m.available, false)} of ${size(m.total, false)} of memory is free" + (if (m.low) " (Android says memory is low)" else "") + "."

    /** The card's first button: Robot's model, unloaded when the answer ends ([modelBytes]: about how much it holds; null: not loaded). */
    fun unloadChoice(modelBytes: Long?, fr: Boolean): String = when {
        modelBytes == null -> if (fr) "Décharger le modèle de Robot (pas chargé en ce moment)" else "Unload Robot's model (not loaded now)"
        fr -> "Décharger le modèle de Robot · environ ${size(modelBytes, true)}"
        else -> "Unload Robot's model · about ${size(modelBytes, false)}"
    }

    /** An app's button: "YouTube · used 5 min ago · 40 min on screen today", its App info opens (Force stop is there). */
    fun appChoice(a: RecentApp, now: Long, fr: Boolean): String {
        val name = NoteSearch.cut(a.label.replace(Regex("\\s+"), " ").trim(), 30)
        val on = (a.foregroundMs / 60_000L).toInt()
        return if (fr) "$name · utilisée ${ago(now - a.lastUsed, true)}" + (if (on >= 1) " · $on min à l'écran aujourd'hui" else "")
        else "$name · used ${ago(now - a.lastUsed, false)}" + (if (on >= 1) " · $on min on screen today" else "")
    }

    /** Without Usage access, the button that opens Android's page for it. */
    fun usageAccessChoice(fr: Boolean): String =
        if (fr) "Activer l'accès aux données d'utilisation, pour voir les applis utilisées récemment" else "Turn on Usage access, to see the apps you used lately"

    /** The card's line while it waits: what its buttons do. */
    fun pickNote(fr: Boolean): String = if (fr) "Touche un choix : le modèle de Robot se décharge à la fin de cette réponse; une appli ouvre sa page Infos sur l'appli, " +
        "où tu touches Forcer l'arrêt. Robot n'arrête jamais une appli lui-même."
        else "Tap one: Robot's model unloads when this answer ends; an app opens its App info, where you tap Force stop. Robot never stops an app itself."

    /**
     * What Robot says to "free up memory": how much memory is free, what unloading its model gives, the apps used lately
     * that may still be in memory ([recent]; null: no Usage access) and how to stop one, and that Android frees memory by
     * itself. Robot can't stop another app (Android 14 and later): it opens the app's info, where you tap Force stop.
     */
    fun advice(m: MemoryFacts, modelBytes: Long?, recent: List<RecentApp>?, now: Long, fr: Boolean): String {
        val parts = ArrayList<String>()
        parts += memoryNow(m, fr)
        parts += when {
            modelBytes == null && fr -> "Le modèle de Robot n'est pas chargé en ce moment."
            modelBytes == null -> "Robot's model isn't loaded now."
            fr -> "Décharger le modèle de Robot libère environ ${size(modelBytes, true)} tout de suite; il se recharge à la prochaine question."
            else -> "Unloading Robot's model frees about ${size(modelBytes, false)} at once; it loads again for your next question."
        }
        parts += when {
            recent == null && fr -> "Avec l'accès aux données d'utilisation, Robot pourrait aussi nommer les applis que tu as utilisées récemment, pour en arrêter une : touche le bouton pour l'activer."
            recent == null -> "With Usage access, Robot could also name the apps you used lately, so you can stop one: tap the button to turn it on."
            recent.isEmpty() && fr -> "Aucune autre appli n'a servi dans les 3 dernières heures."
            recent.isEmpty() -> "No other app was used in the last 3 hours."
            fr -> "Les applis utilisées récemment, sans doute encore en mémoire : ${namesFr(recent.map { it.label })}. Touches-en une pour ouvrir sa page Infos sur l'appli, où tu peux forcer l'arrêt."
            else -> "Apps you used lately that are likely still in memory: ${names(recent.map { it.label })}. Tap one to open its App info, where you can force stop it."
        }
        parts += if (fr) "Robot n'arrête jamais une appli lui-même, et Android libère de la mémoire tout seul quand il en a besoin."
            else "Robot never stops an app itself, and Android frees memory by itself when it needs to."
        return parts.joinToString(" ")
    }

    /** On the lock screen "free up memory" says only what is free: which apps you used stays private until you unlock. */
    fun unlockToFree(fr: Boolean): String = if (fr) "Déverrouille ton téléphone pour voir ce que tu peux décharger ou arrêter."
        else "Unlock your phone to see what you can unload or stop."

    /** « Chrome, YouTube et Maps ». */
    fun namesFr(labels: List<String>): String = when (labels.size) {
        0 -> ""
        1 -> labels[0]
        else -> labels.dropLast(1).joinToString(", ") + " et " + labels.last()
    }

    private val DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    private fun day(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).format(DAY)

    fun plain(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
        .replace("'", "").replace("’", "").replace(Regex("[^a-z0-9]+"), " ").trim()
}
