// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.feedback

import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.4, report a problem (RP1): "Hide names and numbers" in my answer and the tool line, English and French. */
class ReportHideTest {
    private fun hide(s: String, names: List<String> = emptyList(), numbers: List<String> = emptyList(), addresses: List<String> = emptyList()) =
        ReportHide.hide(s, names, numbers, addresses)

    @Test fun namesAndTheirParts() {
        val names = listOf("Jane Doe", "Ann-Marie Roy")
        assertEquals("I texted [name] about [name]'s party.", hide("I texted Jane Doe about Jane's party.", names))
        assertEquals("Calling [name] and [name], not marie.", hide("Calling ANN-MARIE ROY and Marie, not marie.", names))
        // Whole words only.
        assertEquals("Annual dues for Janet.", hide("Annual dues for Janet.", listOf("Ann", "Jane")))
        // Regex characters in a name are just characters; one letter is too short to hide.
        assertEquals("Ask [name] or J today.", hide("Ask J. (Jim) or J today.", listOf("J. (Jim)", "J")))
    }

    @Test fun namesInFrench() {
        val names = listOf("Hélène Tremblay", "Éloïse")
        assertEquals("J'ai appelé [name] au [number].", hide("J'ai appelé Hélène Tremblay au 902 555-0123.", names))
        assertEquals("Le message d'[name] à [name].", hide("Le message d'HÉLÈNE à Eloise.", names))
        assertEquals("Rendez-vous à 14 h 30 avec [name].", hide("Rendez-vous à 14 h 30 avec Tremblay.", names))
    }

    @Test fun namesMatchWithoutAccentsCaseOrApostropheKind() {
        assertEquals("Call [name] and [name] now.", hide("Call Rene and HELENE now.", listOf("René Lévesque", "Hélène")))
        assertEquals("Texted [name] and [name].", hide("Texted SEAN O’BRIEN and O’Brien.", listOf("Sean O'Brien")))
        assertEquals("J'ai écrit à [name] et à [name].", hide("J'ai écrit à Rene et à Heloise.", listOf("René", "Héloïse")))
        // An accent typed as its own mark goes with the name.
        assertEquals("Appelle [name].", hide("Appelle René.", listOf("René")))
    }

    @Test fun commonWordsStay() {
        val names = listOf("Marie de la Tour", "Will", "May Chen", "Home", "Mom")
        assertEquals("Le rendez-vous de la semaine avec [name], près de la tour.", hide("Le rendez-vous de la semaine avec Marie, près de la tour.", names))
        assertEquals("[name] a appelé.", hide("MARIE DE LA TOUR a appelé.", names))
        assertEquals("Will it rain? I will call home, then mom.", hide("Will it rain? I will call home, then mom.", names))
        assertEquals("I texted [name] and [name].", hide("I texted Will and May Chen.", names))
    }

    @Test fun numbersThatLookLikeOne() {
        assertEquals("Call [number] now", hide("Call +1 (902) 555-0123 now"))
        assertEquals("Call [number] now", hide("Call 902.555.0123 now"))
        assertEquals("Your code is [number].", hide("Your code is 4821."))
        assertEquals("Set for [number] at 7:30", hide("Set for 2026-11-08 at 7:30"))
        assertEquals("(see [number])", hide("(see 1234)"))
        assertEquals("It's 12.50, 3 timers, 1.5 GB, 21 days, [number] pas.", hide("It's 12.50, 3 timers, 1.5 GB, 21 days, 10 000 pas."))
        assertEquals("Le [number] est fermé ; [number] → 3 (4 minuteurs)", hide("Le 902 555 0123 est fermé ; 1 234 567 → 3 (4 minuteurs)"))
    }

    @Test fun moreNumberShapes() {
        assertEquals("Appelle le [number].", hide("Appelle le 06/12/34/56/78."))
        assertEquals("Call [number] now", hide("Call 902 – 555 – 0123 now"))
        assertEquals("Call [number] now", hide("Call 902  555  0123 now"))
        assertEquals("Call [number].", hide("Call nine oh two, five five five, oh one two three."))
        assertEquals("Compose le [number].", hide("Compose le neuf zéro deux cinq cinq cinq zéro un deux trois."))
        assertEquals("One or two, three.", hide("One or two, three."))
    }

    @Test fun everyRunOfThreeDigitsHides() {
        // A year-like PIN or door code, a date, digits stuck to letters (an account, a code): only short numbers stay.
        assertEquals("Error E[number] at [number]p in [number], on [number], version 1.3.0, 12 h 30.", hide("Error E1042 at 1080p in 2026, on 2026-11-08, version 1.3.0, 12 h 30."))
        assertEquals("Erreur E[number] le [number] ; [number] reste caché.", hide("Erreur E1042 le 2026-11-08 ; 4821 reste caché."))
        assertEquals("My PIN is [number], account AC[number], 3 days.", hide("My PIN is 2019, account AC12345678, 3 days."))
        assertEquals("Mon code est [number], compte FR[number], 3 jours.", hide("Mon code est 2019, compte FR7630006000011234567890189, 3 jours."))
        assertEquals("Call [number], or [number].", hide("Call 555 0123, or 555."))
        assertEquals("Appelle le [number] ou le [number].", hide("Appelle le 555 0123 ou le 555."))
    }

    @Test fun numbersSaidInGroups() {
        assertEquals("Call [number].", hide("Call five five five, oh one two three."))
        assertEquals("Call [number] now.", hide("Call five fifty-five, oh one two three now."))
        assertEquals("Call [number].", hide("Call double five five."))
        assertEquals("Appelle le [number].", hide("Appelle le cinq cinquante-cinq, zéro un deux trois."))
        assertEquals("Appelle le [number].", hide("Appelle le cinq cinq cinq."))
        // Two number words stay: "twenty five minutes", "vingt-cinq minutes".
        assertEquals("Twenty five minutes, then vingt-cinq minutes.", hide("Twenty five minutes, then vingt-cinq minutes."))
    }

    @Test fun givenNumbersAnyLength() {
        assertEquals("Call [number], not 21 days.", hide("Call 211, not 21 days.", numbers = listOf("211")))
        // A given number's last 5 or 6 digits, in groups too short to hide alone.
        assertEquals("Texted [number].", hide("Texted 55-01-99.", numbers = listOf("+1 902-555-0199")))
        assertEquals("Texted 55-01-99.", hide("Texted 55-01-99."))
        assertEquals("Ok 55 01.", hide("Ok 55 01.", numbers = listOf("+1 902-555-0199")))
    }

    @Test fun addresses() {
        assertEquals("Mailed [address] at [address].", hide("Mailed jane@example.com at 1234 Doe St.", listOf("Doe"), addresses = listOf("Jane@Example.com")))
        assertEquals("Envoyé à [address].", hide("Envoyé à 12, rue des Érables.", addresses = listOf("12, rue des Érables")))
    }

    @Test fun addressesByTheirShape() {
        assertEquals("Write to [address] or [address].", hide("Write to jroy@videotron.ca or Marc.Roy+robot@exemple.qc.ca."))
        assertEquals("It's at [address], Halifax NS [address].", hide("It's at 12 Maple St, Halifax NS B3H 4R2."))
        assertEquals("Postal code [address], ZIP IL [address], [address].", hide("Postal code b3h4r2, ZIP IL 62704-1234, SW1A 1AA."))
        assertEquals("C'est au [address], près du parc.", hide("C'est au 45 rue Saint-Denis, près du parc."))
        assertEquals("Livré au [address].", hide("Livré au 12, rue des Érables."))
        assertEquals("Le [address] à Montréal.", hide("Le 1200 boulevard René-Lévesque à Montréal."))
        assertEquals("3 timers on Maple Street.", hide("3 timers on Maple Street."))
        assertEquals("It's a 5 minute drive, 2 minutes by the way.", hide("It's a 5 minute drive, 2 minutes by the way."))
    }

    @Test fun streetAddressesInLowerCase() {
        // Speech to text and typing often write no capitals.
        assertEquals("directions to [address], please", hide("directions to 12 maple street, please"))
        assertEquals("it's at [address] near the park", hide("it's at 1234 doe st near the park"))
        assertEquals("itinéraire vers le [address], s'il te plaît", hide("itinéraire vers le 45 rue saint-denis, s'il te plaît"))
        assertEquals("livré au [address] à montréal", hide("livré au 12, rue des érables à montréal"))
        assertEquals("3 timers on maple street, 2 minuteurs rue principale.", hide("3 timers on maple street, 2 minuteurs rue principale."))
        assertEquals(ReportHide.Turn("go to [address]", null, null), ReportHide.hide(ReportHide.Turn("go to 12 maple street", null, null)))
    }

    @Test fun givenAddressesLineByLine() {
        val home = "12 Maple Street\nHalifax, NS\nCanada"
        // Each line hides on its own; the whole address too, written on one line.
        assertEquals("Home is [address], [address].", hide("Home is 12 Maple Street, Halifax NS.", addresses = listOf(home)))
        assertEquals("Home is [address].", hide("Home is 12 Maple Street Halifax NS Canada.", addresses = listOf(home)))
        assertEquals("Envoyé à [address] (pas Halifax, Canada).", hide("Envoyé à 12 Maple   Street (pas Halifax, Canada).", addresses = listOf(home)))
    }

    @Test fun whatYouSaidIsHiddenToo() {
        val t = ReportHide.Turn(said = "call marie at 902 555 0123 and tell will", answer = "Calling Marie Roy.", tool = "call(Marie Roy, +19025550123)")
        assertEquals(ReportHide.Turn("call [name] at [number] and tell will", "Calling [name].", "call([name], [number])"),
            ReportHide.hide(t, names = listOf("Marie Roy", "Will"), numbers = listOf("+19025550123")))
        assertEquals(ReportHide.Turn("appelle [name]", null, null), ReportHide.hide(ReportHide.Turn("appelle hélène", null, null), listOf("Hélène")))
        assertEquals(ReportHide.Turn(null, "Il fait beau.", null), ReportHide.hide(ReportHide.Turn(null, "Il fait beau.", null)))
    }

    @Test fun nothingGivenNothingNumberish() {
        assertEquals("The weather is fine.", hide("The weather is fine."))
        assertEquals("", hide("", listOf("Jane")))
        assertEquals("[name] [number]", hide("Jane 5550123", listOf(" Jane ", ""), listOf("", "abc")))
    }

    private fun said(s: String, names: List<String>) = ReportHide.hide(ReportHide.Turn(s, null, null), names).said

    @Test fun ordinaryWordsStayWhereTheyArentTheName() {
        val names = listOf("Mon amour", "Grand-maman", "Lucie Côté", "Tom Day", "Max")
        assertEquals("appelle mon frère", said("appelle mon frère", names))
        assertEquals("appelle [name]", said("appelle mon amour", names))
        assertEquals("grand merci, appelle [name]", said("grand merci, appelle grand maman", names))
        assertEquals("à côté de [name]", said("à côté de lucie", names))
        assertEquals("what day is it? call [name], then [name] [name]", said("what day is it? call tom day, then day tom", names))
        assertEquals("set the volume to max and text [name], écris à [name]", said("set the volume to max and text max, écris à max", names))
        assertEquals("call my mom", said("call my mom", listOf("Mom")))
        // My answer keeps an everyday word that opens a sentence, and hides it inside one.
        assertEquals("Mon alarme sonne. Puis j'ai appelé [name].", hide("Mon alarme sonne. Puis j'ai appelé Côté.", names))
    }

    @Test fun namesInScriptsWithoutCapitals() {
        val names = listOf("محمد", "王小明", "דוד כהן")
        assertEquals("Calling [name].", hide("Calling محمد.", names))
        assertEquals("I texted [name].", hide("I texted דוד.", names))
        assertEquals("正在给[name]打电话。", hide("正在给王小明打电话。", names))
        assertEquals("给[name]打电话", said("给王小明打电话", names))
    }

    // A date said or answered can point at someone (a birthday, an appointment): it hides, its time of day stays. The dates
    // Robot's own code writes (the report's time, log stamps) never go through ReportHide.
    @Test fun datesHideTheirTimeOfDayStays() {
        for ((d, h) in listOf("2026-11-08 14:30" to "[number]:30", "2026-11-09T14:00" to "[number]:00", "2026-11-09T14:00:05" to "[number]:00:05",
            "08/11/2026" to "[number]", "11/8/2026" to "[number]", "2026/11/08" to "[number]", "11/8" to "[number]", "Nov 8 2026" to "Nov [number] [number]",
            "Nov 8, 2026" to "Nov [number], [number]", "14:30" to "14:30"))
            assertEquals(d, "Set for $h.", hide("Set for $d."))
        assertEquals("Call [number] on Nov [number] [number].", hide("Call 902 555 0123 on Nov 8 2026."))
    }

    @Test fun aDayNextToItsMonthHides() {
        for ((d, h) in listOf("my birthday is March 3rd" to "my birthday is March [number]", "on 3 March" to "on [number] March",
            "the third of may" to "the [number] of may", "march twenty-first" to "march [number]", "le 1er mai" to "le [number] mai",
            "le trois mars" to "le [number] mars", "Dec. 25" to "Dec. [number]"))
            assertEquals(d, h, ReportHide.hide(d, anyCase = true))
        // Said, answered and in the tool line alike.
        assertEquals(ReportHide.Turn("my appointment is june [number]", "It's on [number] at 9:30.", "calendar(date: [number])"),
            ReportHide.hide(ReportHide.Turn("my appointment is june 4", "It's on 2026-06-04 at 9:30.", "calendar(date: 2026-06-04)")))
    }

    /** fix-report-card-d: a day said with no month ("on the 3rd") hides too; an ordinal that isn't a day stays. */
    @Test fun aDayWithNoMonthHides() {
        for ((d, h) in listOf("remind me on the 3rd" to "remind me on the [number]", "it's due the 21st." to "it's due the [number].",
            "on the third" to "on the [number]", "by the twenty-first, please" to "by the [number], please",
            "c'est le 3." to "c'est le [number].", "avant le 1er" to "avant le [number]", "le 2e" to "le [number]",
            "the first thing I do" to "the first thing I do", "for the third time" to "for the third time"))
            assertEquals(d, h, ReportHide.hide(d, anyCase = true))
    }

    /** review-develop-14-b L3: the day-with-no-month forms it missed, English and French; ordinary numbers and words stay. */
    @Test fun moreDaysWithNoMonthHide() {
        for ((d, h) in listOf("Tuesday the third" to "Tuesday the [number]", "see you Friday the thirteenth." to "see you Friday the [number].",
            "the third time on Tuesday" to "the third time on Tuesday", "I have 3 cats" to "I have 3 cats"))
            assertEquals(d, h, ReportHide.hide(d, anyCase = true))
        for ((d, h) in listOf("rappelle-moi le premier" to "rappelle-moi le [number]", "c'est le trois." to "c'est le [number].",
            "le vingt et un, à midi" to "le [number], à midi", "la 1re fois" to "la [number] fois", "la 1ère" to "la [number]",
            "le 3ème" to "le [number]", "le 3ième" to "le [number]", "la 2de" to "la [number]", "du 3 au 5" to "du [number] au [number]",
            "du 1er au trois" to "du [number] au [number]", "jusqu'au 3" to "jusqu'au [number]", "jusqu’au 30." to "jusqu’au [number].",
            "lundi 3" to "lundi [number]", "mardi le 4" to "mardi le [number]",
            "le premier ministre" to "le premier ministre", "j'ai 3 chats" to "j'ai 3 chats", "monte au 5" to "monte au 5",
            "lundi 3 h" to "lundi 3 h", "de 3 à 5 pommes" to "de 3 à 5 pommes", "le dix-sept" to "le [number]"))
            assertEquals(d, h, ReportHide.hide(d, anyCase = true))
    }

    @Test fun givenNumbersAndYearsHide() {
        assertEquals("The door code is [number]; built in [number].", hide("The door code is 2041; built in 1987.", numbers = listOf("2041")))
    }

    @Test fun numberShapesThatLeaked() {
        for (n in listOf("902\u2011555\u20110123", "902_555_0123", "902, 555, 0123", "902\u2009555\u20090123", "902\u2007555 0123", "902-555-0123x45", "٩٠٢ ٥٥٥ ٠١٢٣", "９０２-５５５-０１２３"))
            assertEquals(n, "Call [number].", hide("Call $n."))
        assertEquals("E[number] at [number]p, 3, 4 and 5.", hide("E1042 at 1080p, 3, 4 and 5."))
    }

    @Test fun theToolLinesArgumentsHideToo() {
        val names = listOf("Grace", "Rose Tremblay", "Marie")
        fun tool(s: String) = ReportHide.hide(ReportHide.Turn(null, null, s), names).tool
        assertEquals("call(name: [name])", tool("call(name: Grace)"))
        assertEquals("""send_text({"to":"[name]", "body":"[name]?"})""", tool("""send_text({"to":"Rose", "body":"marie?"})"""))
    }

    @Test fun addressesSaidAndTheirLookalikes() {
        assertEquals("go to [address], or [address], write [address], mail [address]",
            said("go to 12 maple street, or 45 rue saint-denis, write jroy at videotron dot ca, mail b three h four r two", emptyList()))
        assertEquals("in 2 hours on the highway", said("in 2 hours on the highway", emptyList()))
        val looks = "Move 3 Google Drive files to the Top 10 Best Place list, a PS5 1TB and an M2 8GB card."
        assertEquals(looks, hide(looks))
        assertEquals("Send it to [address], or [address].", hide("Send it to 12 Lakeview Drive, or SW1A 1AA."))
    }

    @Test fun numberWordsInPairsAnyCaseAndSpokenStreets() {
        fun said(s: String) = ReportHide.hide(s, anyCase = true)
        assertEquals("born in [number]", said("born in nineteen eighty"))
        assertEquals("code [number]", said("code twenty nineteen"))
        assertEquals("a timer for twenty five minutes", said("a timer for twenty five minutes"))
        assertEquals("[number] please", said("Nine oh two please"))
        assertEquals("call [number]", said("call NINE OH TWO FIVE FIVE FIVE"))
        assertEquals("[number]", said("neuf zéro deux"))
        assertEquals("I live at [address]", said("I live at 12 high street"))
        assertEquals("[address], near the park", said("5 north road, near the park"))
        assertEquals("the 12 high street shops", said("the 12 high street shops"))
    }

    @Test fun anEverydayStreetHidesBeforeAQuestionMarkAnExclamationOrALinkingWord() {
        assertEquals("how far is [address]?", sa("how far is 12 main street?"))
        assertEquals("it's at [address]!", sa("it's at 12 high street!"))
        assertEquals("go to [address] and then home", sa("go to 12 high street and then home"))
        assertEquals("at [address]: the blue door", sa("at 12 high street: the blue door"))
        assertEquals("c'est où, [address] ?", sa("c'est où, 45 rue des érables ?"))
        assertEquals("is it [address]?", hide("is it 12 Ocean Drive?"))
        // A word that isn't a linking word still keeps an everyday name: "the 12 high street shops" above.
        assertEquals("top 10 best street food", sa("top 10 best street food"))
    }

    @Test fun anEmailSaidAloudHidesWithItsDotTypedOrSaidInBothParts() {
        assertEquals("write [address]", sa("write jroy at gmail.com"))
        assertEquals("write [address] today", sa("write jean dot roy at gmail dot com today"))
        assertEquals("écris à [address]", sa("écris à jean point roy arobase videotron point ca"))
        assertEquals("écris à [address]", sa("écris à jroy arobase videotron.ca"))
        // "at" between words with no domain stays.
        assertEquals("meet jen at noon", sa("meet jen at noon"))
    }

    private fun sa(s: String) = ReportHide.hide(s, anyCase = true)

    /** Item 9: "may" next to a number said as a word isn't a date; with digits or an ordinal it still is (EN and FR). */
    @Test fun mayNextToANumberWordIsNoDate() {
        for (s in listOf("No one may enter", "no one may enter", "May one ask why", "two may come", "Personne ne peut entrer en mai"))
            for (any in listOf(true, false)) assertEquals(s, s, ReportHide.hide(s, anyCase = any))
        for ((d, h) in listOf("on May 3" to "on May [number]", "3 May" to "[number] May", "the third of May" to "the [number] of May",
            "May the 1st" to "May the [number]", "le 3 mai" to "le [number] mai", "le 1er mai" to "le [number] mai"))
            assertEquals(d, h, ReportHide.hide(d, anyCase = true))
    }

    // A one-word name that is a particle (Al, Del, Van) hides as an everyday word does; a 2-letter part (Jo) hides alone.
    @Test fun particleNamesAndShortPartsHide() {
        val names = listOf("Al", "Del", "Van", "Bin", "Dos", "Jo Smith")
        assertEquals("Texting [name].", hide("Texting Al.", names))
        assertEquals("I met [name], [name], [name] and [name].", hide("I met Del, Van, Bin and Dos.", names))
        // The everyday word's case rule: a lower-case word stays. Al is no word, so it hides wherever it stands.
        assertEquals("[name] dente, the pasta is van-fresh.", hide("Al dente, the pasta is van-fresh.", names))
        assertEquals("text [name]", said("text al", names))
        assertEquals("texting [name]", said("texting al", names))
        assertEquals("the [name] dente pasta", said("the al dente pasta", names))
        assertEquals("call(name: [name])", ReportHide.hide(ReportHide.Turn(null, null, "call(name: Al)"), names).tool)
        assertEquals("[name] is late, ask [name].", hide("Jo is late, ask Jo.", names))
        assertEquals("call [name] now", said("call jo now", names))
        // A particle inside a longer name still doesn't hide alone.
        assertEquals("I asked Van about Vermeer.", hide("I asked Van about Vermeer.", listOf("Jan van der Berg")))
    }

    // A given email or XMPP address hides whole, where the email pattern can't see it (no dot, an apostrophe).
    @Test fun givenEmailAndXmppAddressesHideWhole() {
        val addresses = listOf("zeb@localhost", "o'brien@x.org", "Mo@chat")
        assertEquals("Write to [address] or [address], and [address].", hide("Write to zeb@localhost or o’brien@x.org, and mo@chat.", addresses = addresses))
        assertEquals("send [address] a note", ReportHide.hide(ReportHide.Turn("send zeb@localhost a note", null, null), addresses = addresses).said)
        // A one-word line with no digit and no @ (a city) still doesn't hide alone.
        assertEquals("Halifax is cold.", hide("Halifax is cold.", addresses = listOf("Halifax")))
    }

    // A nickname that is an everyday word (Bug, Coach, Tonton) hides only where it stands as the name; in the tool line an
    // everyday one-word name hides only as a name argument's value.
    @Test fun everydayNicknamesHideOnlyAsTheName() {
        val names = listOf("Bug", "Coach", "Tonton", "Max", "Grace", "Timer", "Uncle")
        assertEquals("there's a bug in the timer", said("there's a bug in the timer", names))
        assertEquals("text [name]", said("text bug", names))
        assertEquals("mon tonton arrive, appelle [name]", said("mon tonton arrive, appelle tonton", names))
        assertEquals("Coach said yes. Ask [name] about it.", hide("Coach said yes. Ask Coach about it.", names))
        fun tool(s: String) = ReportHide.hide(ReportHide.Turn(null, null, s), names).tool
        assertEquals("set_volume(level: max)", tool("set_volume(level: max)"))
        assertEquals("set_timer · DONE", tool("set_timer · DONE"))
        assertEquals("call(name: [name])", tool("call(name: Max)"))
        assertEquals("call(contact: [name])", tool("call(contact: Uncle)"))
        assertEquals("""send_text({"to":"[name]", "body":"[name], max?"})""", tool("""send_text({"to":"max", "body":"grace, max?"})"""))
    }

    // A nickname that is also a given name (Mo, Nana, Coco) hides everywhere, as any one-word name does.
    @Test fun nicknamesThatAreGivenNamesHideEverywhere() {
        val names = listOf("Mo", "Nana", "Coco", "Pepe", "Bae", "Nan")
        assertEquals("[name] is on her way.", hide("Mo is on her way.", names))
        assertEquals("[name] called.", hide("Mo called.", names))
        assertEquals("tell [name] [name] is here", said("tell mo nana is here", names))
        assertEquals("[name] and [name] said hi to [name] and [name]", said("coco and pepe said hi to bae and nan", names))
    }

    // A street name with a nickname word in it is an address in what you said, wherever the address ends.
    @Test fun streetWithANicknameWordHides() {
        assertEquals("meet me at [address] beside the park", said("meet me at 12 sugar creek road beside the park", emptyList()))
        assertEquals("i'm at [address] beside the rink", said("i'm at 40 coach hill road beside the rink", emptyList()))
    }

    // A particle that is a longer name's first word (Al Smith, Van Jones) hides alone; one later in a name doesn't.
    @Test fun aParticleFirstInANameHidesAlone() {
        val names = listOf("Al Smith", "Van Jones", "Jan van der Berg", "Marie de la Tour")
        assertEquals("Texting [name].", hide("Texting Al.", names))
        assertEquals("I met [name] today.", hide("I met Van today.", names))
        assertEquals("text [name]", said("text al", names))
        assertEquals("the van is late", said("the van is late", names))
        assertEquals("Ask De about Der Spiegel.", hide("Ask De about Der Spiegel.", names))
    }

    // A one-word name that is a particle but no English or French word (Al, Del, Ibn, Von) hides as any name does.
    @Test fun particleNamesThatAreNoWordsHide() {
        val names = listOf("Al", "Del", "Ibn", "Von")
        assertEquals("[name] said yes.", hide("Al said yes.", names))
        assertEquals("[name] called.", hide("Von called.", names))
        assertEquals("tell [name] and meet [name] at noon", said("tell al and meet del at noon", names))
        assertEquals("ask [name]", said("ask ibn", names))
    }

    // "calling" stands before a name as "call" does.
    @Test fun callingStandsBeforeAName() {
        val names = listOf("Max", "Grace")
        assertEquals("calling [name] now", said("calling max now", names))
        assertEquals("max volume, calling [name]", said("max volume, calling grace", names))
    }
}
