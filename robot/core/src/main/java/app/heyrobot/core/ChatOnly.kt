package app.heyrobot.core

import java.text.Normalizer
import java.util.Locale

// Pure. A model loaded with less than 4,096 tokens of context (0.4.3, your own file built for 2,048 to 4,095: MedGemma
// 1.5 4B's LiteRT-LM files are built for 2,048) runs as chat only: a compact prompt (Prompt.chatSystem), no tools, its
// history trimmed to the smaller context (ContextWindow), any call it makes anyway dropped (:brain's
// BrainPolicy.acceptsCalls, and [ChatOnlyTurn] here). A request clearly worded as an action gets Robot's own answer
// ([REPLY]) instead of the model's, with an offer to open Settings › Model ([NOTICE]); Robot's routes that need no model
// (texting, the helpers' phrases) work as they always do, before any of this.

object ChatOnly {
    /** Robot's answer when you ask a chat-only model to act: what it can't do, and where to choose a model that can. */
    const val REPLY = "This model can only chat: it can't do things on the phone (timers, alarms, texts, notes, the calendar, maps) " +
        "or look anything up. To have Robot do that, choose Fast in Settings › Model."

    /** The same, as the voice says it (no "›"). */
    val SPOKEN: String = REPLY.replace(" › ", ", ")

    /** The notice under it, whose button opens Settings › Model: an offer, never a switch made for you. */
    const val NOTICE = "For actions, choose Fast in Settings › Model."

    /**
     * The action [question] clearly asks for, or null: only a request worded as one from its start ("set a timer…",
     * "remind me to…", "open Signal", "turn on the flashlight", "add … to my calendar", "navigate to…"), after "hey
     * robot", "please", "can you" and the like; or a question only the phone's tools can answer ("what's the weather",
     * "what's on my calendar", "what's Mom's number", "how much battery…", "where is the nearest…"). Kept narrow on
     * purpose: anything else goes to the model, which is told it can't act, so a question about an open wound, light
     * sensitivity or cold weather and asthma is answered, never turned away. A command that ends with a question mark
     * counts only when asked politely ("can you set a timer?"): "Note that I'm diabetic: can I eat this?" is a question.
     */
    fun actionAsked(question: String): ToolName? {
        val raw = question.trim()
        val social = strip(words(raw), SOCIAL)
        val request = strip(social.words, REQUEST)
        val polite = social.polite || request.polite || words(raw).lastOrNull() == "please"
        val a = social.words.joinToString(" ")
        val b = request.words.joinToString(" ")
        // Questions only the phone's tools (or the internet) can answer: whatever the punctuation.
        for ((tool, rx) in QUESTIONS) if (rx.containsMatchIn(a) || rx.containsMatchIn(b)) return tool
        if (raw.endsWith("?") && !polite) return null
        for ((tool, rx) in COMMANDS) if (rx.containsMatchIn(b)) return tool
        return null
    }

    private class Stripped(val words: List<String>, val polite: Boolean)

    /** Drops the [fillers] (single words or phrases) at the start, again and again; polite when one of [POLITE] went. */
    private fun strip(w: List<String>, fillers: List<List<String>>): Stripped {
        var i = 0
        var polite = false
        while (i < w.size) {
            val f = fillers.firstOrNull { f -> f.size <= w.size - i && f.indices.all { w[i + it] == f[it] } } ?: break
            if (f.joinToString(" ") in POLITE) polite = true
            i += f.size
        }
        return Stripped(w.subList(i, w.size), polite)
    }

    private val ELISION = Regex("""\b([dljnsmtc]|qu)['’](?=\p{L})""")

    /** Lower case, no accents, French elision split ("l'appli"), "what's" → "whats", words only. */
    private fun words(s: String): List<String> = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
        .replace(ELISION, "$1 ").replace("'", "").replace("’", "")
        .split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    private fun phrases(vararg p: String): List<List<String>> = p.map { it.split(' ') }.sortedByDescending { it.size }

    /** Greetings and glue that can open any sentence. */
    private val SOCIAL = phrases("hey robot", "ok robot", "okay robot", "hi robot", "robot", "hey", "ok", "okay", "alright", "right", "so",
        "now", "just", "also", "and", "then", "please", "pls", "plz", "kindly", "quickly", "quick", "oh", "um", "uh")

    /** The ways of asking: "can you", "i want you to", "peux-tu"… */
    private val REQUEST = phrases("can you please", "could you please", "would you please", "will you please", "can you", "could you",
        "would you", "will you", "can u", "would you mind", "i want you to", "i need you to", "i would like you to", "id like you to",
        "go ahead and", "please", "for me", "est ce que tu peux", "est ce que vous pouvez", "tu peux", "peux tu", "pourrais tu",
        "pouvez vous", "s il te plait", "s il vous plait", "stp", "svp", "merci de")

    private val POLITE = setOf("please", "pls", "plz", "kindly", "can you please", "could you please", "would you please", "will you please",
        "can you", "could you", "would you", "will you", "can u", "would you mind", "i want you to", "i need you to", "i would like you to",
        "id like you to", "go ahead and", "est ce que tu peux", "est ce que vous pouvez", "tu peux", "peux tu", "pourrais tu", "pouvez vous",
        "s il te plait", "s il vous plait", "stp", "svp", "merci de")

    private const val DAY = "monday|tuesday|wednesday|thursday|friday|saturday|sunday|lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche"
    /**
     * When a calendar question asks about: "today", "this week", "next Monday", "Friday". Never "this" or "next" alone:
     * "what's on this X-ray?" and "what do I have on my arm?" are questions about a photo or the body.
     */
    private const val WHEN = "today|tomorrow|tonight|this (morning|afternoon|evening|week|weekend|month)|next (week|weekend|month|$DAY)|$DAY"
    /** The same in French: "qu'est-ce que j'ai de prévu demain", never "qu'est-ce que j'ai au bras". */
    private const val QUAND = "de prevu|prevu|au programme|aujourd ?hui|demain|ce (soir|matin|midi|week end|weekend)|cette (semaine|apres midi)|$DAY"
    /** "open wound", "close the cut", "open-angle glaucoma": a body, a wound or a diagnosis after open or close is never an app. */
    private const val NOT_APP = "(?!(?:the |a |an |my |your )?(?:wounds?|cuts?|fractures?|sores?|heart|surgery|mouth|eyes?|skin|blisters?|bites?|" +
        "lesions?|incisions?|gap|up|wide|angle|pneumothorax|reduction|airways?|pores?|bowels?|chest|abdomen|ulcers?|abscess(?:es)?|sinuses|" +
        "nose|jaw|fist|hands?|plaie|blessure|bouche|yeux)\\b)"
    /**
     * A word that can start a person's name before "'s number": never an article, a possessive or a word that describes a
     * count ("what is the white cells number", "a normal platelets number" are questions about blood tests).
     */
    private const val NAME = "(?!(?:the|a|an|this|that|these|those|my|your|his|her|its|our|their|normal|average|usual|typical|healthy|good|" +
        "bad|high|low|white|red|blood)\\b)\\S+"
    /** A verb after "directions to": "directions to take metformin" are instructions, not a route. */
    private const val HOW_TO = "(?!(?:take|use|apply|make|do|follow|treat|prevent|stop|reduce|lower|clean|dress|inject|mix|store|give|get|" +
        "administer|prepare|swallow|inhale|insert|change|remove|wash|measure|check|calculate|keep)\\b)"
    /** After "forecast for" or "temperature in": a person or a body, never a place ("the forecast for recovery"). */
    private const val NOT_PLACE = "(?!(?:a|an|the|my|your|his|her|their|recovery|healing|survival|cancer|patients?|people|someone|children|" +
        "kids|adults|babies|infants|pregnancy|fever)\\b)"

    /** Requests worded as an action from their first word (after the fillers). */
    private val COMMANDS: List<Pair<ToolName, Regex>> = listOf(
        ToolName.SET_TIMER to """^(set|start|put|make|create|run)( up| on)?( (a|an|the|my|another|one))?( \S+){0,2} (timer|countdown)\b""",
        ToolName.SET_TIMER to """^(timer|countdown) (for|of|on)\b|^\S+ (minutes?|mins?|seconds?|secs?|hours?) (timer|countdown)\b""",
        ToolName.SET_TIMER to """^(mets|mettre|lance|lancer|demarre|demarrer|programme|regle)( moi)?( un| le| une)?( \S+){0,2} (minuteur|minuterie|chrono|chronometre)\b""",
        ToolName.SET_ALARM to """^(set|put|make|create)( up)?( (a|an|the|my|another))?( \S+){0,2} (alarms?|wake up call)\b|^wake me\b|^alarm (for|at|on|in)\b""",
        ToolName.SET_ALARM to """^(reveille|reveiller)( moi)?\b|^(mets|programme|regle)( moi)?( une| un| l| le)? (alarme|reveil)\b""",
        ToolName.ADD_EVENT to """^remind me (to|at|in|on|tomorrow|tonight|today|this|next|every|before)\b|^rappelle moi (de|d|a|demain|ce|cet|$DAY)\b""",
        ToolName.ADD_EVENT to """^(add|put|create|set up)\b.*\b((to|in|on|into|onto) (my |the )?(calendar|agenda)|(a|an|the|my) (calendar entry|event|reminder|meeting))\b|^make (a |an )?(calendar entry|event|reminder|meeting)\b""",
        ToolName.ADD_EVENT to """^schedule (a|an|my|me|it|this|that)\b|^add\b.*\b(at \d|\d ?(am|pm)\b|\d:\d\d|on ($DAY)|(tomorrow|tonight) at)\b""",
        ToolName.ADD_EVENT to """^(ajoute|ajouter|mets|cree|creer|programme|planifie)\b.*\b(calendrier|agenda|rendez vous|rdv|evenement|reunion|rappel)\b""",
        ToolName.ADD_NOTE to """^(?!write (a|an) (note|memo) (to|for)\b)(take|make|write|jot|add|create|save)( down)?( (a|an|the|this|that|my))? (note|memo)\b|^(note|jot)( down| that| this| it)\b""",
        ToolName.ADD_NOTE to """^(write|put) (that|this|it) down\b|^write down\b|^(add|put)\b.*\b(to|on|in|onto) (my|the) (\S+ )?(list|notes)\b""",
        ToolName.ADD_NOTE to """^(prends|prendre|fais|ecris|cree)( une)? note\b|^note (que|ca|cela)\b|^retiens (que|ca)\b|^(ajoute|mets)\b.*\b(liste|notes)\b""",
        ToolName.SEND_TEXT to """^(text|sms|txt|msg|dm) (?!(neck|thumb|claw)\b)\S+|^message (\S+ ){1,3}(that|to|saying|i|im|we)\b|^let (?!(me|us)\b)\S+( \S+){0,2} know\b|^ping \S+""",
        ToolName.SEND_TEXT to """^(send|shoot|drop)( \S+){0,3} (a |an )?(text|message|sms|msg)\b|^send (this|that|it|these|those|the photo|the picture|my location)( \S+)? to\b""",
        ToolName.SEND_TEXT to """^tell (?!(me|us|what|whether|if|how|why|who|when|where|which|the|a|apart|them|him|her|you|it|about|more)\b)\S+ (that|to|i|im|we|were|my|about|ill)\b""",
        ToolName.SEND_TEXT to """^(envoie|envoyer|ecris|ecrire)( \S+){0,3} (texto|sms|message|mot)\b|^(dis|ecris|reponds) a \S+|^previens \S+""",
        ToolName.DRAFT_EMAIL to """^(email|e mail|mail) (?!order\b)\S+|^(send|write|draft|compose)( \S+){0,3} (an |a )?(email|e mail|mail)\b|^reply to \S+""",
        ToolName.DRAFT_EMAIL to """^(envoie|envoyer|ecris|ecrire)( \S+){0,3} (mail|courriel|email)\b""",
        ToolName.OPEN_APP to """^(open|launch)( up)? $NOT_APP(the |my )?\S+( \S+)?( app| application)?$|^(start|run) (the )?\S+( \S+)? (app|application)$""",
        ToolName.OPEN_APP to """^(ouvre|ouvrir|lance|lancer|demarre)( l| le| la| les| mon| ma)? $NOT_APP\S+( \S+)?( appli| application| app)?$""",
        ToolName.MAPS_NAVIGATE to """^(navigate|navigation|directions?|route)( me)? (to $HOW_TO\S|home\b|back\b)|^(take|get|drive|bring|guide|walk) me (to|home|back)\b""",
        ToolName.MAPS_NAVIGATE to """^(emmene|amene|guide|conduis) moi\b|^itineraire (vers|pour|jusqu)\b""",
        ToolName.MAPS_SEARCH to """^(find|show|search|look for|look up)( for| me)?\b.*\b(nearest|closest|near me|nearby|around here|close by|on the map|on a map|in maps)\b""",
        ToolName.MAPS_SEARCH to """^(trouve|cherche|montre)( moi)?\b.*\b(plus proche|pres d|autour d)""",
        ToolName.FLASHLIGHT to """^(turn|switch|put|flip)( the| my)? (flashlight|torch|flash|light|lamp|lights)( on| off)\b|^(turn|switch|put|flip) (on|off) (the |my )?(flashlight|torch|flash|light|lamp|lights)\b""",
        ToolName.FLASHLIGHT to """^(flashlight|torch)( on| off)?$|^lights? (on|off)$|^(allume|eteins|allumer|eteindre)( la| le| ma| mon)? (lampe|torche|flash|lumiere)\b""",
        ToolName.FREE_STORAGE to """^(free|clean|clear)( up)? (some )?(space|storage)\b|^(free|clean|clear) up (my |the )?phone\b|^(clear|delete|empty) (the |my )?(cache|caches|downloads|trash)\b""",
        ToolName.FREE_MEMORY to """^(free|clear)( up)? (some )?(memory|ram)\b|^(close|kill|quit|stop|force stop|force close)( the)? \S+( \S+)? (app|application)$|^force (stop|close) \S+""",
        ToolName.FREE_STORAGE to """^(libere|liberer|nettoie|nettoyer|vide|vider)\b.*\b(espace|stockage|memoire|cache|telephone)\b|^(ferme|fermer|quitte)( l| le| la)? \S+ (appli|application)$""",
    ).map { (t, p) -> t to Regex(p) }

    /** Questions only the phone's tools can answer (the weather, your calendar, contacts, notes, the phone, the nearest place). */
    private val QUESTIONS: List<Pair<ToolName, Regex>> = listOf(
        ToolName.WEATHER to """^(whats|what is|hows|how is|what will|whats going to be)( be)? the (weather\b|forecast( (in|at|on|today|tomorrow|tonight|this|next|like)\b| for $NOT_PLACE|$)|""" +
            """temperature( (outside|out|today|tomorrow|tonight|now|right now|here|there|this (morning|afternoon|evening|week|weekend))\b| in $NOT_PLACE\S+( \S+)?$|$))|""" +
            """^weather( for| in| at| today| tomorrow| tonight| this| on| next|$)|^forecast( (in|at|on|today|tomorrow|tonight|this|next)\b| for $NOT_PLACE|$)""",
        ToolName.WEATHER to """^(check|get|give me|tell me|show me|read me)( the)? (weather|forecast)\b|^(will|is) it (going to )?(rain|snow|storm)\b|^do i need (an |a )?(umbrella|coat|jacket)\b""",
        ToolName.WEATHER to """^(how hot|how cold|how warm) (is it|will it be)\b(?! (safe|ok|okay|alright|dangerous|normal|healthy|too|bad|good|for|to|when|if)\b)|""" +
            """^quel temps( (fait il|fera t il|va t il faire|il fait|il va faire|il fera|est prevu|prevu|aujourd ?hui|demain|ce (soir|matin|week end|weekend)|""" +
            """cette semaine|a \S+|$DAY)\b|$)|^(quelle meteo|meteo)\b|^(va t il|est ce qu il va|il va) (pleuvoir|neiger)\b""",
        ToolName.READ_CALENDAR to """^(whats|what is|whats on|what do i have|do i have anything|anything) (on )?(my )?(calendar|agenda|schedule)\b|^(whats|what is) on (for )?($WHEN)\b""",
        ToolName.READ_CALENDAR to """^what do i have (going on |on |planned |scheduled )?($WHEN)\b|^what do i have (going )?on$|^(am i|are we) (free|busy|available)( today| tomorrow| tonight| this| next| on| at| $DAY|$)|^when is my next (appointment|meeting|event)\b""",
        ToolName.READ_CALENDAR to """^(check|read|show|open)( me)? my (calendar|agenda|schedule|appointments)\b|^(qu est ce que j ai|j ai quoi) ($QUAND)\b|^quoi de prevu\b|^(suis je|je suis) (libre|occupe)\b""",
        ToolName.LOOKUP_CONTACT to """^(whats|what is)( my)?( $NAME)? ${NAME}s (phone number|number|phone|email address|email|e mail|address|birthday)\b""",
        ToolName.LOOKUP_CONTACT to """^(look up|lookup|find|get|give me|show me)( me)?( my)?( $NAME)? ${NAME}s (phone number|number|phone|email address|email|address)\b""",
        ToolName.FIND_NOTES to """^(find|show|read|search|open|list|check)( me)? (my )?notes\b|^(what|whats) (are|is)? ?(in |on )?my (notes|\S+ list|list)\b|^what did i (note|write down|save)\b""",
        ToolName.FIND_NOTES to """^(montre|lis|cherche|trouve)( moi)? (mes )?notes\b""",
        ToolName.PHONE_STATUS to """^how much (battery|charge|storage|free space)\b|^(whats|what is) (my |the )?(battery|storage|free space)\b|^(battery|storage) (level|left|status|percentage)\b""",
        ToolName.PHONE_STATUS to """^combien (de batterie|d espace|de stockage)\b""",
        ToolName.MAPS_SEARCH to """^(where is|wheres) (the )?(nearest|closest)\b|^how do i get to (the )?(nearest|closest)\b|^(is there|are there) (a |an |any )?\S+( \S+)? (near me|nearby|around here|close by)\b""",
        ToolName.MAPS_SEARCH to """^ou est (le |la |l )?plus proche\b|^(ou est|ou se trouve) (le |la |l )?\S+ le plus proche\b""",
    ).map { (t, p) -> t to Regex(p) }
}

/**
 * A chat-only model's side of a turn: whatever it emits, no call ever reaches the approval gate (no tool was described
 * to it, and :brain drops calls at [ToolMode.CHAT_ONLY] too). A reply that ends in calls ends there, its text kept;
 * nothing is read as a text call either.
 */
class ChatOnlyTurn(private val inner: TurnModel) : TurnModel {
    override suspend fun ask(): ModelOutcome = plain(inner.ask())
    override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome = plain(inner.answer(results))
    override fun close() = inner.close()

    private fun plain(o: ModelOutcome): ModelOutcome = when (o) {
        is ModelOutcome.Calls -> {
            // The conversation waits for tool results that will never come: it is rebuilt next time.
            inner.close()
            ModelOutcome.Done(DoneReason.END)
        }
        is ModelOutcome.Done -> if (o.textCalls) o.copy(textCalls = false) else o
        is ModelOutcome.Failed -> o
    }
}
