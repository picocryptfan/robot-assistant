// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.helpers

import app.heyrobot.core.HelperEffort
import app.heyrobot.core.HelperEffortSetting
import app.heyrobot.core.AnthropicModel
import app.heyrobot.core.AskRoute
import app.heyrobot.core.HelperVoices
import app.heyrobot.core.LogCode
import app.heyrobot.core.enumOr
import app.heyrobot.core.i18n.HelperStrings
import app.heyrobot.core.i18n.Phrase
import org.json.JSONArray
import org.json.JSONObject

// Pure. Helpers (0.6.0): any AI Robot can hand a question to, on your say. Claude through the Anthropic API, another
// company's model through its OpenAI-compatible API, a model on your own computer, or (1.0.0) without a key, the
// company's own app on this phone (Claude, ChatGPT, Grok, Gemini, Mistral, DeepSeek), opened with the question. Each is a
// [HelperConfig] in settings, made from a [Preset]: how to reach it (one of three wire formats, [Protocol]), the small
// ways the company's API differs from the common one ([Quirks], data rather than code paths), its models with what they
// can do, and the words Settings uses. Nothing here touches the network: the clients live in the :helpers module, which
// only the :voice process loads.

/** The wire format a helper speaks. */
enum class Protocol { ANTHROPIC, CHAT, RESPONSES }

/** Who answers, as a code for the voice log (never a name or an address). */
enum class HelperKind(override val id: Int, override val title: String) : LogCode {
    ANTHROPIC(1, "Anthropic"), OPENAI(2, "OpenAI"), GOOGLE(3, "Google"), MISTRAL(4, "Mistral"), XAI(5, "xAI"), DEEPSEEK(6, "DeepSeek"),
    GROQ(7, "Groq"), OPENROUTER(8, "OpenRouter"), TOGETHER(9, "Together"), HOME(10, "your own server"), OTHER(11, "another company"),
}

/** How a provider takes "how hard it thinks" (Robot's Low, Medium, High). */
enum class EffortStyle {
    /** Not sent. */
    NONE,
    /** `reasoning_effort`: low, medium, high (OpenAI Chat, Google, Groq's gpt-oss, home servers that accept it). */
    REASONING_EFFORT,
    /** Mistral thinks fully or not at all: Low is `none`, Medium and High are `high`. */
    MISTRAL,
    /** DeepSeek: low, high, max. */
    DEEPSEEK,
    /** OpenRouter: `reasoning: {effort}`. */
    OPENROUTER,
    /** The Responses API: `reasoning: {effort}`. */
    RESPONSES;

    /** The wire value for [e], or null when nothing is sent. */
    fun wire(e: HelperEffort): String? = when (this) {
        NONE -> null
        MISTRAL -> if (e == HelperEffort.LOW) "none" else "high"
        DEEPSEEK -> when (e) {
            HelperEffort.LOW -> "low"
            HelperEffort.MEDIUM -> "high"
            HelperEffort.HIGH -> "max"
        }
        else -> e.wire
    }
}

/** How a provider searches the web for a helper (null when it can't through this connection). */
enum class SearchStyle { NONE, ANTHROPIC, RESPONSES, OPENROUTER }

/**
 * The ways a provider's API differs from the common Chat Completions request: the name of the length parameter,
 * whether usage needs `stream_options` to arrive, whether tools may be `strict`, whether `tool_choice` is taken, how
 * effort and web search are sent, whether it reports each request's cost (OpenRouter) and can be told to use only
 * providers that keep nothing ([zdr]). [home]: a server on your network (longer waits for a cold model, a fast fail
 * on a wrong address). Anything a server still refuses with a 400 is dropped once and remembered (adaptive extras).
 */
data class Quirks(
    val lengthParam: String = "max_tokens",
    val streamUsage: Boolean = true,
    val strictTools: Boolean = false,
    val toolChoice: Boolean = true,
    val effort: EffortStyle = EffortStyle.REASONING_EFFORT,
    val search: SearchStyle = SearchStyle.NONE,
    val reportsCost: Boolean = false,
    val zdr: Boolean = false,
    val home: Boolean = false,
)

/**
 * A model Robot knows about: [tools] it can use Robot's actions, [search] it can search the web through this
 * connection, [vision] it can see pictures. [effort]: overrides the preset's [Quirks.effort] for this model.
 */
data class CatalogModel(
    val id: String,
    val title: String,
    val detailText: Phrase,
    val tools: Boolean = true,
    val search: Boolean = false,
    val vision: Boolean = false,
    val effort: EffortStyle? = null,
) {
}

/**
 * A kind of helper you can add. [defaultName]: the name you call it by ("ChatGPT"); [spoken]: other ways the name is
 * written by speech recognition that mean it exactly ("chat gpt"), [misheard]: words Parakeet produces for it that count
 * only after a command verb ("cloud" for Claude). [baseUrl]: the company's API (null: the SDK's own for Anthropic, yours
 * for a home server or another company). [billingText], [dataUseText] and [searchNoteText] are Settings' words (1.1:
 * without a language yet, shown in the menus'); [keyWhere] is the company site's own page, in its own words.
 * [companyText] and [titleText]: the company and the kind of helper, by name. [defaultName] stays as written in every
 * language (it becomes your helper's name). [keyPrefixes]: the usual start of a key, for a warning only (a different
 * start never blocks a key).
 */
data class Preset(
    val id: String,
    val kind: HelperKind,
    val companyText: Phrase,
    val titleText: Phrase,
    val defaultName: String,
    val protocol: Protocol,
    val baseUrl: String?,
    val quirks: Quirks,
    val models: List<CatalogModel>,
    val keyRequired: Boolean = true,
    val keyWhere: String? = null,
    val keyPrefixes: List<String> = emptyList(),
    val billingText: Phrase? = null,
    val dataUseText: Phrase? = null,
    val searchNoteText: Phrase? = null,
    /**
     * The company's own Android app Robot can hand a question to, by package (1.0.0: each checked against its Play Store
     * listing on 13 Oct 2026). Without a key, questions open it ([HelperSettings.route]); you press Send there.
     */
    val app: String? = null,
    val spoken: List<String> = emptyList(),
    val misheard: List<String> = emptyList(),
    /** Search is on when you add the helper (only where search sends nothing to a third party). */
    val searchByDefault: Boolean = false,
) {
    val home: Boolean get() = kind == HelperKind.HOME


    /** You type the address (your server, or another company's OpenAI-compatible API). */
    val needsAddress: Boolean get() = baseUrl == null && protocol != Protocol.ANTHROPIC
    val defaultModel: String get() = models.firstOrNull()?.id.orEmpty()

    fun model(id: String): CatalogModel? = models.firstOrNull { it.id == id }

    /** Web search can work through this connection at all. */
    val canSearch: Boolean get() = quirks.search != SearchStyle.NONE
}

object Presets {
    /** Kept as it was: the Ask Claude settings, the Anthropic SDK, the Claude app. */
    val ANTHROPIC = Preset(
        "anthropic", HelperKind.ANTHROPIC, Phrase.plain("Anthropic"), Phrase.plain("Anthropic (Claude)"), "Claude", Protocol.ANTHROPIC, null, // l10n: not text (company and product names)
        Quirks(search = SearchStyle.ANTHROPIC, strictTools = true, effort = EffortStyle.NONE),
        AnthropicModel.entries.map { CatalogModel(it.id, it.title, it.detailText, tools = true, search = true, vision = true) },
        keyWhere = "console.anthropic.com › API keys", keyPrefixes = listOf("sk-ant-"), // l10n: not text (the company site's own page)
        billingText = HelperStrings.billingAnthropic(),
        app = "com.anthropic.claude", misheard = listOf("cloud", "clod", "clawed", "claud", "klode", "clyde"), searchByDefault = true,
    )

    /**
     * OpenAI through the Responses API: its GPT-6 models call tools only there (OpenAI's model pages, 2026-10-10), and web
     * search outside it needs a separate model that always searches.
     */
    val OPENAI = Preset(
        "openai", HelperKind.OPENAI, Phrase.plain("OpenAI"), Phrase.plain("OpenAI (GPT)"), "ChatGPT", Protocol.RESPONSES, "https://api.openai.com/v1", // l10n: not text (company and product names)
        Quirks(lengthParam = "max_output_tokens", strictTools = true, effort = EffortStyle.RESPONSES, search = SearchStyle.RESPONSES),
        listOf(
            CatalogModel("gpt-6.1-sol", "GPT-6.1 Sol", HelperStrings.modelGptSol(), search = true, vision = true), // l10n: not text (a model's name)
            CatalogModel("gpt-6-astra", "GPT-6 Astra", HelperStrings.modelGptAstra(), search = true, vision = true), // l10n: not text (a model's name)
            CatalogModel("gpt-6-luna", "GPT-6 Luna", HelperStrings.modelGptLuna(), search = true, vision = true), // l10n: not text (a model's name)
        ),
        keyWhere = "platform.openai.com › API keys", keyPrefixes = listOf("sk-"), // l10n: not text (the company site's own page)
        billingText = HelperStrings.billingOpenAi(), spoken = listOf("chatgpt", "chat gpt", "chat g p t", "gpt", "g p t"),
        searchByDefault = true, app = "com.openai.chatgpt",
    )

    val GEMINI = Preset(
        "gemini", HelperKind.GOOGLE, Phrase.plain("Google"), Phrase.plain("Google (Gemini)"), "Gemini", Protocol.CHAT, "https://generativelanguage.googleapis.com/v1beta/openai", // l10n: not text (company and product names)
        Quirks(),
        listOf(
            CatalogModel("gemini-3.8-flash", "Gemini 3.8 Flash", HelperStrings.modelGeminiFlash(), vision = true), // l10n: not text (a model's name)
            CatalogModel("gemini-3.1-pro-preview", "Gemini 3.1 Pro (preview)", HelperStrings.modelGeminiPro(), vision = true), // l10n: not text (a model's name)
            CatalogModel("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", HelperStrings.modelGeminiLite(), vision = true), // l10n: not text (a model's name)
        ),
        keyWhere = "aistudio.google.com › Get API key", keyPrefixes = listOf("AIza"), // l10n: not text (the company site's own page)
        billingText = HelperStrings.billingGemini(),
        dataUseText = HelperStrings.dataUseGemini(),
        searchNoteText = HelperStrings.searchNoteGemini(), app = "com.google.android.apps.bard",
    )

    val MISTRAL = Preset(
        "mistral", HelperKind.MISTRAL, Phrase.plain("Mistral AI"), Phrase.plain("Mistral"), "Mistral", Protocol.CHAT, "https://api.mistral.ai/v1", // l10n: not text (company and product names)
        Quirks(streamUsage = false, effort = EffortStyle.MISTRAL),
        listOf(
            CatalogModel("mistral-medium-3-5", "Mistral Medium 3.5", HelperStrings.modelMistralMedium()), // l10n: not text (a model's name)
            CatalogModel("mistral-large-4-0", "Mistral Large 4.0", HelperStrings.modelMistralLarge()), // l10n: not text (a model's name)
            CatalogModel("mistral-small-latest", "Mistral Small", HelperStrings.modelMistralSmall()), // l10n: not text (a model's name)
        ),
        keyWhere = "console.mistral.ai › API keys", // l10n: not text (the company site's own page)
        billingText = HelperStrings.billingMistral(),
        searchNoteText = HelperStrings.searchNoteMistral(),
        // Its app was called Le Chat until 2026 (now Vibe by Mistral); the package stayed.
        app = "ai.mistral.chat",
    )

    val DEEPSEEK = Preset(
        "deepseek", HelperKind.DEEPSEEK, Phrase.plain("DeepSeek"), Phrase.plain("DeepSeek"), "DeepSeek", Protocol.CHAT, "https://api.deepseek.com",
        Quirks(effort = EffortStyle.DEEPSEEK),
        listOf(
            CatalogModel("deepseek-flash", "DeepSeek Flash", HelperStrings.modelDeepSeekFlash(), vision = true), // l10n: not text (a model's name)
            CatalogModel("deepseek-v4-pro", "DeepSeek V4 Pro", HelperStrings.modelDeepSeekPro()), // l10n: not text (a model's name)
        ),
        keyWhere = "platform.deepseek.com › API keys", keyPrefixes = listOf("sk-"), // l10n: not text (the company site's own page)
        billingText = HelperStrings.billingDeepSeek(),
        searchNoteText = HelperStrings.searchNoteDeepSeek(), spoken = listOf("deepseek", "deep seek"), app = "com.deepseek.chat",
    )

    val GROQ = Preset(
        "groq", HelperKind.GROQ, Phrase.plain("Groq"), Phrase.plain("Groq"), "Groq", Protocol.CHAT, "https://api.groq.com/openai/v1",
        Quirks(lengthParam = "max_completion_tokens"),
        listOf(
            CatalogModel("openai/gpt-oss-120b", "GPT-OSS 120B", HelperStrings.modelGptOss120()), // l10n: not text (a model's name)
            CatalogModel("openai/gpt-oss-20b", "GPT-OSS 20B", HelperStrings.modelGptOss20()), // l10n: not text (a model's name)
            CatalogModel("qwen/qwen3.8-27b", "Qwen 3.8 27B", HelperStrings.modelQwen(), vision = true, effort = EffortStyle.NONE), // l10n: not text (a model's name)
        ),
        keyWhere = "console.groq.com › API Keys", keyPrefixes = listOf("gsk_"), // l10n: not text (the company site's own page)
        searchNoteText = HelperStrings.searchNoteGroq(),
    )

    val OPENROUTER = Preset(
        "openrouter", HelperKind.OPENROUTER, Phrase.plain("OpenRouter"), HelperStrings.titleOpenRouter(), "OpenRouter", Protocol.CHAT, "https://openrouter.ai/api/v1",
        Quirks(streamUsage = false, strictTools = true, effort = EffortStyle.OPENROUTER, search = SearchStyle.OPENROUTER, reportsCost = true, zdr = true),
        emptyList(),
        keyWhere = "openrouter.ai › Keys", keyPrefixes = listOf("sk-or-"), // l10n: not text (the company site's own page)
        billingText = HelperStrings.billingOpenRouter(),
        dataUseText = HelperStrings.dataUseOpenRouter(),
        spoken = listOf("openrouter", "open router"),
    )

    /** xAI through the Responses API (its web search is only there; it calls Chat Completions "legacy"). */
    val XAI = Preset(
        "xai", HelperKind.XAI, Phrase.plain("xAI"), Phrase.plain("xAI (Grok)"), "Grok", Protocol.RESPONSES, "https://api.x.ai/v1", // l10n: not text (company and product names)
        Quirks(lengthParam = "max_output_tokens", effort = EffortStyle.RESPONSES, search = SearchStyle.RESPONSES),
        listOf(CatalogModel("grok-4.7", "Grok 4.7", HelperStrings.modelGrok(), search = true, vision = true)), // l10n: not text (a model's name)
        keyWhere = "console.x.ai › API keys", keyPrefixes = listOf("xai-"), searchByDefault = true, app = "ai.x.grok", // l10n: not text (the company site's own page)
    )

    val HOME = Preset(
        // Its default name stays as written in every language: it becomes your helper's name.
        "home", HelperKind.HOME, HelperStrings.companyHome(), HelperStrings.titleHome(), "My server", Protocol.CHAT, null, // l10n: not text (a default name, kept in every language)
        Quirks(toolChoice = false, home = true), emptyList(), keyRequired = false,
        searchNoteText = HelperStrings.searchNoteHome(),
    )

    /**
     * 1.0.0: called "My AI" when its address gives no name ([HelperNames.forNew]); "Helper" before, which is now how you ask
     * whichever helper Robot checks with, so a name nobody may pick ([HelperNames.check] lets one saved before keep it).
     */
    val OTHER = Preset(
        "compatible", HelperKind.OTHER, HelperStrings.companyOther(), HelperStrings.titleOther(), "My AI", Protocol.CHAT, null, // l10n: not text (a new helper's default name, yours to change)
        Quirks(toolChoice = false), emptyList(), keyRequired = false,
        searchNoteText = HelperStrings.searchNoteOther(),
    )

    /** [OTHER]'s name before 1.0.0, a default rather than a name you chose (the diagnostics keep it). */
    const val OTHER_FORMER_NAME = "Helper"

    /** In the order "Add a helper" lists them. */
    val ALL: List<Preset> = listOf(ANTHROPIC, OPENAI, GEMINI, MISTRAL, DEEPSEEK, GROQ, OPENROUTER, XAI, HOME, OTHER)

    fun of(id: String?): Preset? = ALL.firstOrNull { it.id == id }
}

/**
 * One helper as you set it up (settings.json "helpers", schema 9). [enabled] null: on. [route]: the company's app or its
 * API, for a preset with an app (null: the app without a key, the API with one). [search], [actions] (it may ask Robot to
 * do things, always behind a card), [locked] (works on the lock screen), [trust] (trusted with personal content, off by
 * default for every helper: see [HelperTrust]), [baseUrl] (yours, for a home server or another company), [budgetCents]
 * (its own daily cap, null: only the budget for all helpers), [prices] with the model they were entered for ([pricesFor]),
 * [zdr] (OpenRouter: only providers that keep nothing), [aliases] (other ways you say its name).
 */
data class HelperConfig(
    val id: String,
    val preset: String,
    val name: String,
    val model: String,
    val enabled: Boolean? = null,
    val route: AskRoute? = null,
    val effort: HelperEffortSetting = HelperEffortSetting.AUTO,
    val search: Boolean = false,
    val actions: Boolean = true,
    val voice: String = HelperVoices.DEFAULT,
    val locked: Boolean = false,
    val trust: Boolean = false,
    val baseUrl: String? = null,
    val budgetCents: Int? = null,
    val prices: Rates? = null,
    val pricesFor: String? = null,
    val zdr: Boolean = true,
    val aliases: List<String> = emptyList(),
) {
    val presetOf: Preset get() = Presets.of(preset) ?: Presets.OTHER
    val on: Boolean get() = enabled ?: true
    val home: Boolean get() = presetOf.home
    val kind: HelperKind get() = presetOf.kind
    val catalogModel: CatalogModel? get() = presetOf.model(model)

    /** Web search will be asked for: on, and possible with this connection and model. */
    val searching: Boolean get() = search && presetOf.canSearch && (catalogModel?.search ?: (presetOf.quirks.search == SearchStyle.OPENROUTER))

    /** Your prices, when they were entered for the model in use. */
    val yourPrices: Rates? get() = prices?.takeIf { pricesFor == null || pricesFor == model }

    /** Where this helper's price comes from now (see [Pricing]). */
    val price: HelperPrice get() = when {
        yourPrices != null -> HelperPrice.Yours(yourPrices!!)
        home -> HelperPrice.Free
        presetOf.quirks.reportsCost -> HelperPrice.Reported(PriceTable.of(model))
        PriceTable.of(model) != null -> HelperPrice.Published(PriceTable.of(model)!!)
        else -> HelperPrice.Unknown
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("preset", preset)
        put("name", name)
        put("model", model)
        enabled?.let { put("enabled", it) }
        route?.let { put("route", it.name) }
        put("effort", effort.name)
        put("search", search)
        put("actions", actions)
        put("voice", voice)
        put("locked", locked)
        put("trust", trust)
        baseUrl?.let { put("baseUrl", it) }
        budgetCents?.let { put("budgetCents", it) }
        prices?.let { put("prices", it.toJson()) }
        pricesFor?.let { put("pricesFor", it) }
        if (presetOf.quirks.zdr) put("zdr", zdr)
        if (aliases.isNotEmpty()) put("aliases", JSONArray(aliases))
    }

    companion object {
        const val CLAUDE_ID = "claude"
        private val ID = Regex("[a-z0-9_]{1,16}")

        /**
         * Claude as installs before 1.0.0 had it (on; the Claude app without a key, the API with one): kept for settings
         * files from before 0.6.0 (RobotSettings.legacyClaude). 1.0.0: a new install has no helper.
         */
        fun claude(): HelperConfig = HelperConfig(CLAUDE_ID, Presets.ANTHROPIC.id, Presets.ANTHROPIC.defaultName, AnthropicModel.DEFAULT.id, search = true)

        /** A new helper of [preset] ([id]: unused; [voice]: the first voice nobody else uses). */
        fun of(preset: Preset, id: String, voice: String, name: String = preset.defaultName, baseUrl: String? = null): HelperConfig =
            HelperConfig(id, preset.id, name, preset.defaultModel, search = preset.searchByDefault && preset.canSearch, voice = voice, baseUrl = baseUrl)

        /** An id not in [taken], from [random] (letters and digits, so it is safe in a file name). */
        fun newId(taken: Set<String>, random: (Int) -> Int): String {
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            while (true) {
                val id = "h" + (1..6).map { chars[random(chars.length)] }.joinToString("")
                if (id !in taken) return id
            }
        }

        /** A saved helper, or null when it can't be one (the entry is dropped, never fatal). */
        fun fromJson(o: JSONObject?): HelperConfig? {
            o ?: return null
            val id = o.optString("id").takeIf { ID.matches(it) } ?: return null
            val preset = Presets.of(o.optString("preset")) ?: return null
            val name = HelperNames.clean(o.optString("name")).ifEmpty { preset.defaultName }
            val model = o.optString("model").trim().take(MAX_MODEL).ifEmpty { preset.defaultModel }
            val base = o.optString("baseUrl").trim().takeIf { it.isNotEmpty() }
            // A server or another company without its address can't be reached: dropped rather than guessed.
            if (preset.needsAddress && base == null) return null
            return HelperConfig(
                id = id, preset = preset.id, name = name,
                model = if (preset.protocol == Protocol.ANTHROPIC) AnthropicModel.of(model).id else model,
                enabled = if (o.has("enabled")) o.optBoolean("enabled") else null,
                route = if (preset.app != null) enumValues<AskRoute>().firstOrNull { it.name == o.optString("route") } else null,
                effort = enumOr(o.optString("effort"), HelperEffortSetting.AUTO),
                search = o.optBoolean("search", preset.searchByDefault),
                actions = o.optBoolean("actions", true),
                voice = HelperVoices.of(o.optString("voice")),
                locked = o.optBoolean("locked", false),
                trust = o.optBoolean("trust", false),
                baseUrl = if (preset.needsAddress) base else null,
                budgetCents = if (o.has("budgetCents") && !o.isNull("budgetCents")) o.optInt("budgetCents").coerceIn(0, 100_000) else null,
                prices = Rates.fromJson(o.optJSONObject("prices")),
                pricesFor = o.optString("pricesFor").takeIf { it.isNotEmpty() },
                zdr = o.optBoolean("zdr", true),
                aliases = o.optJSONArray("aliases")?.let { a -> (0 until a.length()).map { HelperNames.clean(a.optString(it)) }.filter { it.isNotEmpty() } }.orEmpty().take(MAX_ALIASES),
            )
        }

        const val MAX_MODEL = 200
        const val MAX_ALIASES = 8

        /** Saved helpers: damaged entries dropped, and a repeated id kept once (the first). */
        fun listFromJson(a: JSONArray): List<HelperConfig> {
            val out = LinkedHashMap<String, HelperConfig>()
            for (i in 0 until a.length()) fromJson(a.optJSONObject(i))?.let { if (it.id !in out) out[it.id] = it }
            return out.values.toList()
        }
    }
}

/** What a name check found: fine, a problem (the name can't be saved), or a warning (it can, but you should know). */
sealed class NameCheck {
    data object Ok : NameCheck()

    /** [text]: why, as words without a language yet (1.1: the name field shows it in the menus' language); [why] its English. */
    data class Problem(val text: Phrase) : NameCheck()

    data class Warning(val text: Phrase) : NameCheck()
}

/** Names and the words around them: what a helper may be called (pure, tested). */
object HelperNames {
    /** Letters, digits and single spaces, at most three words; anything else is taken out. */
    fun clean(s: String): String = s.replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim().split(' ').filter { it.isNotEmpty() }.take(3)
        .joinToString(" ").take(40)

    /** Words that are commands of their own: a helper called one of them would turn every "thanks" or "stop" into a name. */
    private val COMMANDS = setOf("stop", "thanks", "thank", "back", "yes", "no", "cancel", "okay", "ok", "bye", "goodbye", "ask", "get", "go", "read",
        "merci", "oui", "non", "annule", "demande", "help", "wait", "not", "you", "me", "it", "the", "a", "an",
        // 1.0.0: "ask the helper …" and « demande à l'assistant … » mean the helper Robot checks with, never one called that.
        "helper", "assistant", "lassistant")

    /** 1.1: [COMMANDS] for Skills' name check (core skills SkillNames): a skill can't be called a command word either. */
    val commandWords: Set<String> get() = COMMANDS

    /** The command words 1.0.0 added, which a helper may already have been called. */
    private val SINCE_1_0 = setOf("helper", "assistant", "lassistant")

    /** Second-level labels under a two-letter country code ("example.co.uk"): the name is the label before them. */
    private val SECOND_LEVEL = setOf("co", "com", "org", "net", "ac", "gov", "edu", "ne", "or")

    /**
     * A name for another company's helper from its address: "https://api.example.com/v1" → "Example", "openrouter.ai" →
     * "Openrouter". Null when the address gives none (an IP address, a label under three letters).
     */
    fun fromAddress(address: String): String? {
        val host = LanRule.normalize(address)?.host ?: return null
        if (LanRule.literal(host) != null) return null
        val labels = host.split('.').filter { it.isNotEmpty() }
        var main = labels.dropLast(1)
        if (main.size >= 2 && main.last() in SECOND_LEVEL && labels.last().length == 2) main = main.dropLast(1)
        val label = main.lastOrNull() ?: labels.firstOrNull() ?: return null
        val name = clean(label.replace('-', ' '))
        if (name.count { it.isLetter() } < 3) return null
        return name.replaceFirstChar { it.titlecase(java.util.Locale.ROOT) }
    }

    /**
     * The name a new helper of [p] gets ([id], in [s]): for another company, the one in its [address] when it has one, else
     * the preset's: the first of each, then "… 2" to "… 9", that [check] doesn't refuse. You can rename it on its page.
     */
    fun forNew(p: Preset, id: String, s: app.heyrobot.core.RobotSettings, wakeWords: Set<String>, address: String? = null): String {
        val bases = listOfNotNull(address?.takeIf { p.kind == HelperKind.OTHER }?.let(::fromAddress), p.defaultName).distinct()
        val tries = bases.flatMap { b -> listOf(b) + (2..9).map { "$b $it" } }
        return tries.firstOrNull { check(it, id, s, wakeWords) !is NameCheck.Problem } ?: p.defaultName
    }

    /** Common words and names that are often said for something else: allowed, with a warning. */
    private val COMMON = setOf("max", "echo", "sage", "nova", "ray", "sky", "alex", "sam", "jo", "joe", "jack", "kim", "lee", "mark", "will", "grace",
        "hope", "joy", "rose", "april", "may", "june", "august", "summer", "river", "star", "sunny", "honey", "buddy", "friend", "bob", "ben")

    /**
     * Whether [name] may be the voice name of helper [id]: one to three words of letters and digits; not "robot", a word
     * of your wake phrases ([wakeWords]), a command word or the filler Robot skips before a command; and not the same
     * name, or one that sounds the same ("Groq" and "Grok"), as another helper's. A common word is allowed with a warning.
     */
    fun check(name: String, id: String, s: app.heyrobot.core.RobotSettings, wakeWords: Set<String>): NameCheck {
        val raw = name.trim()
        if (raw.isEmpty()) return NameCheck.Problem(HelperStrings.nameWords())
        if (raw.any { !(it.isLetterOrDigit() || it == ' ') }) return NameCheck.Problem(HelperStrings.nameLetters())
        val words = app.heyrobot.core.HelperName.words(raw)
        if (words.isEmpty() || words.size > 3) return NameCheck.Problem(HelperStrings.nameWords())
        // A name saved before 1.0.0 made "helper" and "assistant" command words ("Helper", another company's default) keeps
        // working: it is only refused for a new helper or a new name.
        val kept = s.helpers.any { it.id == id && app.heyrobot.core.HelperName.words(it.name) == words }
        for (w in words) {
            if (w == "robot" || w in wakeWords) return NameCheck.Problem(HelperStrings.nameWake("word" to w))
            if (kept && w in SINCE_1_0) continue
            if (w in COMMANDS || w in app.heyrobot.core.HelperRouter.FILLER_WORDS) return NameCheck.Problem(HelperStrings.nameCommand("word" to w))
        }
        for (other in s.helpers) {
            if (other.id == id) continue
            val theirs = app.heyrobot.core.HelperName.of(other)
            if (theirs.forms.any { it == words } || theirs.misheard.any { it == words }) return NameCheck.Problem(HelperStrings.nameTaken("name" to other.name))
            if (words.size == 1 && theirs.forms.any { it.size == 1 && app.heyrobot.core.Phonetic.same(it[0], words[0]) })
                return NameCheck.Problem(HelperStrings.nameSoundsLike("name" to other.name))
        }
        if (words.size == 1 && words[0] in COMMON) return NameCheck.Warning(HelperStrings.nameCommon("name" to raw))
        return NameCheck.Ok
    }
}
