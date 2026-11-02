// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.memory.SecretDetector
import app.heyrobot.core.skills.LinkedHandOff
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.SkillEffects
import app.heyrobot.core.skills.SkillEncode
import app.heyrobot.core.skills.SkillTemplate
import app.heyrobot.core.skills.SkillValue
import app.heyrobot.core.skills.SlotType
import java.util.Locale

// Pure. What a hand-off opens (docs/plans/skills-connections-plan.md §3.5, design §8.3; C7): an app you picked, by its
// package and its pinned signer, asked to do one thing from a short list, with data and extras you set up once in Settings
// › Skills › Connections and slots a skill's step fills. What it hands over is data, never a capability:
//
// - Actions: VIEW, SEND (text/plain), SENDTO, DIAL and MAIN only. None of them acts without the app's own screen, so no
//   hand-off can use a permission Robot holds: never CALL (Robot's own call gate stays the only way to place a call), and
//   `tel:` only with DIAL or VIEW, which open the dialer with the number.
// - Data: an address whose scheme is fixed when you set it up (`https`, `geo`, `tel`, `sms`, `smsto`, `mailto`, or one the
//   app's filter declares; the launch, inside the pinned package, only finds the app when it does). Never a scheme that
//   hands over a file or another intent (content, file, intent, android-app…), never plain http. An https address's host
//   is fixed too: a slot sits only after it. Each slot's value is percent-encoded for its place (SkillEncode.uri).
// - Extras: a string, a whole number or a switch, nothing else: never an Intent, a Parcelable, a Bundle or a URI, and
//   never an extra Android reads as one (STREAM, INTENT, REFERRER…). A string that names a content:// or file:// address
//   is refused, whether you typed it or a slot brought it.
// - The intent the panel sends is explicit: the package always, the component when exactly one of its exported
//   activities matches (HandOffRunner, in the app). It carries no URI grant: no FLAG_GRANT_*, no ClipData.

/** A hand-off's action (design §8.3's allowlist). [android]: the Intent action. */
enum class HandOffAction(val id: String, val android: String) {
    VIEW("view", "android.intent.action.VIEW"), // l10n: not text (Android's action)
    SEND("send", "android.intent.action.SEND"), // l10n: not text (Android's action)
    SENDTO("sendto", "android.intent.action.SENDTO"), // l10n: not text (Android's action)
    DIAL("dial", "android.intent.action.DIAL"), // l10n: not text (Android's action)
    MAIN("main", "android.intent.action.MAIN"); // l10n: not text (Android's action)

    companion object {
        fun of(id: String?): HandOffAction? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One extra: its [name] (an Android extra's name, "android.intent.extra.TEXT"), its [type] (TEXT, INTEGER or YES_NO) and
 * its [value]: for TEXT, words that may hold `{{slot}}`; for INTEGER and YES_NO, exactly `{{slot}}` or a constant ("12",
 * "true").
 */
data class HandOffExtra(val name: String, val type: ParamType, val value: String) {
    override fun toString(): String = "HandOffExtra(${type.id})" // l10n: not text (toString, for the logs)
}

/**
 * What a hand-off asks its app: [action], its [data] address template (none for SEND and MAIN), [mime] (text/plain for
 * SEND only), [extras], and the [params] (slots) a skill's step fills.
 */
data class IntentSpec(
    val action: HandOffAction,
    val data: String? = null,
    val mime: String? = null,
    val extras: List<HandOffExtra> = emptyList(),
    val params: Map<String, OpParam> = emptyMap(),
) {
    override fun toString(): String = "IntentSpec(${action.id})" // l10n: not text (toString, for the logs)
}

/**
 * The intent a hand-off sends, built and checked: [pkg] always set; [action] one of [HandOffAction]'s; [data] encoded;
 * [extras] each a String, a Long or a Boolean; [launcher]: MAIN goes to the app's launcher activity. Nothing else.
 */
class HandOffIntent(val pkg: String, val action: String, val data: String?, val mime: String?, val extras: List<Pair<String, Any>>, val launcher: Boolean) {
    override fun equals(other: Any?): Boolean = other is HandOffIntent && pkg == other.pkg && action == other.action && data == other.data &&
        mime == other.mime && extras == other.extras && launcher == other.launcher
    override fun hashCode(): Int = listOf(pkg, action, data, mime, extras, launcher).hashCode()
    override fun toString(): String = "HandOffIntent(…)" // l10n: not text (toString, for the logs)
}

object IntentSpecs {
    /** The slots an action fills: from its address and its text only where the form shows them, a hidden field's left out. */
    fun slots(action: HandOffAction, data: String, text: String): List<String> {
        val inData = action == HandOffAction.VIEW || action == HandOffAction.SENDTO || action == HandOffAction.DIAL
        val inText = action == HandOffAction.SEND || action == HandOffAction.SENDTO
        return ((if (inData) OpTemplate.names(data).orEmpty() else emptyList()) + (if (inText) OpTemplate.names(text).orEmpty() else emptyList())).distinct()
    }

    const val TEXT_PLAIN = "text/plain" // l10n: not text (a media type)
    const val MAX_DATA = 1000
    const val MAX_EXTRAS = 8
    const val MAX_EXTRA_NAME = 100
    const val MAX_EXTRA_TEXT = 2000

    /** The schemes design §8.3 names; another one works only when the app's filter declares it (the launch checks). */
    val SCHEMES: Set<String> = setOf("https", "geo", "tel", "sms", "smsto", "mailto") // l10n: not text (URI schemes)

    /**
     * Schemes a hand-off never sends, whatever the app declares: they hand over a file or a content provider's data
     * (content, file, android.resource), another intent (intent, android-app), a script or inline data (javascript, data,
     * blob, jar, about), or plain http (an address anyone on the way could change).
     */
    val NEVER_SCHEMES: Set<String> = setOf("content", "file", "android.resource", "intent", "android-app", "javascript", "data", "blob", "jar", // l10n: not text (URI schemes)
        "about", "http", "ftp", "smb", "vnd.android.cursor.item", "vnd.android.cursor.dir") // l10n: not text (URI schemes)

    /** Extras Android or apps read as an Intent, a URI, a stream, a result channel or who sent it: never set, even as text. */
    val NEVER_EXTRAS: Set<String> = setOf("android.intent.extra.STREAM", "android.intent.extra.INTENT", "android.intent.extra.INITIAL_INTENTS", // l10n: not text (extra names)
        "android.intent.extra.ALTERNATE_INTENTS", "android.intent.extra.CHOOSER_TARGETS", "android.intent.extra.REPLACEMENT_EXTRAS", // l10n: not text (extra names)
        "android.intent.extra.RESULT_RECEIVER", "android.intent.extra.REFERRER", "android.intent.extra.REFERRER_NAME", // l10n: not text (extra names)
        "android.intent.extra.CHOSEN_COMPONENT_INTENT_SENDER", "android.intent.extra.CHOOSER_REFINEMENT_INTENT_SENDER", // l10n: not text (extra names)
        "android.intent.extra.CHOOSER_CUSTOM_ACTIONS", "android.intent.extra.CHOOSER_MODIFY_SHARE_ACTION", "android.intent.extra.EXCLUDE_COMPONENTS", // l10n: not text (extra names)
        "android.intent.extra.PACKAGE_NAME", "android.intent.extra.UID", "android.intent.extra.USER", "android.intent.extra.RETURN_RESULT", // l10n: not text (extra names)
        "android.intent.extra.shortcut.INTENT", "android.intent.extra.REMOTE_INTENT_TOKEN") // l10n: not text (extra names)

    /** The slot types a hand-off takes. */
    val SLOT_TYPES: Set<ParamType> = setOf(ParamType.TEXT, ParamType.CHOICE, ParamType.INTEGER, ParamType.NUMBER, ParamType.YES_NO)

    private val SCHEME = Regex("^([a-z][a-z0-9+.-]{0,31}):")
    private val EXTRA_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+){0,10}$")
    /** RFC 3986's characters, a "%" only before two hex digits: what an address's own words may hold. */
    private val URI_WORDS = Regex("^([A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=]|%[0-9A-Fa-f]{2})*$")
    private val HAND_OVER = Regex("(content|file|android\\.resource|intent|android-app)\\s*:", RegexOption.IGNORE_CASE)
    private val CONTROL = Regex("[\\p{Cc}\\u2028\\u2029]")

    /** [s]'s scheme, lower case, or null. */
    fun scheme(s: String?): String? = s?.let { SCHEME.find(it)?.groupValues?.get(1)?.lowercase(Locale.ROOT) }

    /** A string that would hand over a file, a content provider's data or an intent ("content://…"): never an extra. */
    fun handsOver(s: String): Boolean = HAND_OVER.containsMatchIn(s)

    /** [spec] may be saved: every rule of the file's header. [paramFits]: ConnCheck's own rule for an argument. */
    fun valid(spec: IntentSpec, paramFits: (OpParam) -> Boolean): Boolean {
        val p = spec.params
        if (p.size > ConnCheck.MAX_PARAMS || p.keys.any { !OpTemplate.NAME.matches(it) } || p.values.any { it.type !in SLOT_TYPES || !paramFits(it) }) return false
        if (spec.extras.size > MAX_EXTRAS || spec.extras.map { it.name }.distinct().size != spec.extras.size) return false
        val scheme = scheme(spec.data)
        when (spec.action) {
            HandOffAction.VIEW -> if (spec.data == null || spec.mime != null) return false
            HandOffAction.SEND -> if (spec.data != null || spec.mime != TEXT_PLAIN) return false
            HandOffAction.SENDTO -> if (spec.data == null || spec.mime != null || scheme == "tel" || scheme == "https" || scheme == "geo") return false
            HandOffAction.DIAL -> if (scheme != "tel" || spec.mime != null) return false
            HandOffAction.MAIN -> if (spec.data != null || spec.mime != null) return false
        }
        // tel: only opens the dialer (DIAL, or VIEW, which Android sends to the dialer too): never a call Robot places.
        if (scheme == "tel" && spec.action != HandOffAction.DIAL && spec.action != HandOffAction.VIEW) return false
        val used = mutableListOf<String>()
        spec.data?.let { d -> if (!dataFits(d, p, used)) return false }
        for (e in spec.extras) if (!extraFits(e, p, used)) return false
        // Every slot named is one of its params, and every param is used.
        return p.keys.containsAll(used) && used.containsAll(p.keys)
    }

    /** The words [spec] holds as you typed them (SecretDetector reads them: a key never goes in connections.json). */
    fun words(spec: IntentSpec): List<String> = listOfNotNull(spec.data?.let(OpTemplate::words)) +
        spec.extras.filter { it.type == ParamType.TEXT }.mapNotNull { OpTemplate.words(it.value) }

    /** One of [words] looks like a key. */
    fun secret(spec: IntentSpec): Boolean = words(spec).any { SecretDetector.find(it).isNotEmpty() }

    private fun dataFits(d: String, p: Map<String, OpParam>, used: MutableList<String>): Boolean {
        if (d.length > MAX_DATA || CONTROL.containsMatchIn(d)) return false
        val scheme = scheme(d) ?: return false
        if (scheme in NEVER_SCHEMES) return false
        val parts = OpTemplate.parts(d) ?: return false
        // The scheme is fixed: the address starts with words that hold it.
        val first = parts.firstOrNull() as? OpTemplate.Part.Words ?: return false
        if (!first.text.lowercase(Locale.ROOT).startsWith("$scheme:")) return false
        // An https address's host is fixed: "https://host" with its first "/", "?" or "#" in the same words.
        if (scheme == "https") {
            val rest = first.text.substring(scheme.length + 1)
            if (!rest.startsWith("//")) return false
            val host = rest.substring(2).takeWhile { it != '/' && it != '?' && it != '#' }
            if (host.isEmpty() || '@' in host || (host.length == rest.length - 2 && parts.size > 1)) return false
        }
        for (part in parts) when (part) {
            is OpTemplate.Part.Words -> if (!URI_WORDS.matches(part.text)) return false
            is OpTemplate.Part.Slot -> {
                val t = p[part.name]?.type ?: return false
                if (t == ParamType.YES_NO) return false
                used += part.name
            }
        }
        return true
    }

    private fun extraFits(e: HandOffExtra, p: Map<String, OpParam>, used: MutableList<String>): Boolean {
        if (e.name.length > MAX_EXTRA_NAME || !EXTRA_NAME.matches(e.name) || e.name in NEVER_EXTRAS) return false
        val whole = OpTemplate.whole(e.value)
        return when (e.type) {
            ParamType.TEXT -> {
                if (e.value.isEmpty() || e.value.length > MAX_EXTRA_TEXT || '\u0000' in e.value || handsOver(e.value)) return false
                val parts = OpTemplate.parts(e.value) ?: return false
                for (s in parts.filterIsInstance<OpTemplate.Part.Slot>()) {
                    val t = p[s.name]?.type ?: return false
                    if (!SkillEncode.hasWords(t)) return false
                    used += s.name
                }
                true
            }
            ParamType.INTEGER -> if (whole != null) (p[whole]?.type == ParamType.INTEGER).also { if (it) used += whole }
                else e.value.toLongOrNull()?.toString() == e.value
            ParamType.YES_NO -> if (whole != null) (p[whole]?.type == ParamType.YES_NO).also { if (it) used += whole }
                else e.value == "true" || e.value == "false"
            else -> false
        }
    }

    /**
     * The intent [app] sends with [values] (its slots', typed, defaults filled), or null when a value doesn't fit its
     * place: nothing is repaired, nothing is sent.
     */
    fun build(app: HandOffApp, values: Map<String, SkillValue>): HandOffIntent? {
        val spec = app.intent
        val data = spec.data?.let { d ->
            val out = StringBuilder()
            // C12's L5: a slot in the address's path ("https://…/", "app:/…") keeps the path's rules; after "?" or "#", or in
            // an address with no path (sms:, tel:, mailto:, geo:), only a control character is refused.
            var inPath = d.substringAfter(':', "").startsWith("/")
            for (part in OpTemplate.parts(d) ?: return null) when (part) {
                is OpTemplate.Part.Words -> {
                    out.append(part.text)
                    if ('?' in part.text || '#' in part.text) inPath = false
                }
                is OpTemplate.Part.Slot -> {
                    val p = spec.params[part.name] ?: return null
                    val v = values[part.name] ?: return null
                    out.append(SkillEncode.uri(v, SlotType(p.type, p.options), inPath) ?: return null)
                }
            }
            out.toString().takeIf { it.length <= MAX_DATA * 4 && scheme(it) == scheme(d) && scheme(it) !in NEVER_SCHEMES } ?: return null
        }
        val extras = spec.extras.map { e -> e.name to (extra(e, spec.params, values) ?: return null) }
        return HandOffIntent(app.pkg, spec.action.android, data, spec.mime, extras, launcher = spec.action == HandOffAction.MAIN)
    }

    private fun extra(e: HandOffExtra, params: Map<String, OpParam>, values: Map<String, SkillValue>): Any? {
        val whole = OpTemplate.whole(e.value)
        return when (e.type) {
            ParamType.TEXT -> {
                val text = StringBuilder()
                for (part in OpTemplate.parts(e.value) ?: return null) when (part) {
                    is OpTemplate.Part.Words -> text.append(part.text)
                    is OpTemplate.Part.Slot -> {
                        val p = params[part.name] ?: return null
                        val v = values[part.name] ?: return null
                        text.append(SkillEncode.extra(v, SlotType(ParamType.TEXT, emptyList()))?.takeIf { SkillEncode.hasWords(v.type) && p.type in SLOT_TYPES } ?: return null)
                    }
                }
                text.toString().takeIf { it.isNotEmpty() && it.length <= MAX_EXTRA_TEXT && '\u0000' !in it && !handsOver(it) }
            }
            ParamType.INTEGER -> if (whole != null) values[whole]?.let { SkillEncode.extra(it, SlotType(ParamType.INTEGER)) as? Long } else e.value.toLongOrNull()
            ParamType.YES_NO -> if (whole != null) values[whole]?.let { SkillEncode.extra(it, SlotType(ParamType.YES_NO)) as? Boolean } else e.value.toBooleanStrictOrNull()
            else -> null
        }
    }

    /** What [spec] does: it opens an app, and tells people for SEND, SENDTO, `sms:`, `smsto:` and `mailto:` (you still send there). */
    fun effects(spec: IntentSpec): Set<SkillEffect> = SkillEffects.handOff(spec.action.name, scheme(spec.data))

    /** [app] as a skill's checks see it (C1's SkillLinks): its slots, typed, the ones without a default required, and its effects. */
    fun linked(app: HandOffApp): LinkedHandOff = LinkedHandOff(app.intent.params.mapValues { (_, p) -> SlotType(p.type, p.options) }, effects(app.intent),
        required = app.intent.params.filterValues { it.default == null }.keys)

    /** The words a card shows as "with “…”": the first text extra's value, when it has one. */
    fun shownText(i: HandOffIntent): String? = i.extras.firstNotNullOfOrNull { (_, v) -> (v as? String)?.takeIf { it.isNotBlank() } }

    /** A value's text for a card line. */
    fun text(v: Any): String = SkillTemplate.text(v)
}
