// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.HelperName
import app.heyrobot.core.HelperRouter
import app.heyrobot.core.RoutineRouter
import app.heyrobot.core.feedback.FeedbackRouter
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.lock.LockRouter
import app.heyrobot.core.messages.MessagesRouter
import app.heyrobot.core.ConversationRouter
import app.heyrobot.core.skills.SkillFixtures
import app.heyrobot.core.skills.SkillRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * files-1.4-plan.md §9 and §11 (FilesRouterTest), job F8: every phrase in English and Québec French, the misfires that
 * must stay someone else's (free_storage, notes, memory, skills and routines, the web, helpers, everyday talk), the routers
 * before it leaving its phrases alone, and the file a phrase names. RoutingCorpusTest proves the routers after it unchanged.
 */
class FilesRouterTest {
    private fun r(t: String, recent: Boolean = false) = FilesRouter.route(t, recent, yours = listOf("House", "Car"))

    @Test fun fileThisIsF5s() {
        for (t in listOf("file this", "Hey Robot, file this!", "save this to my files", "keep this receipt", "classe ça", "range ce reçu", "garde ça dans mes fichiers"))
            assertTrue(t, r(t) is FilesCommand.File)
        assertEquals(FileThis.Ask.ANYWAY, (r("file it anyway") as FilesCommand.File).heard.ask)
    }

    @Test fun rules() {
        fun rule(t: String, kind: FileKind, merchant: String, drawer: String, lang: Lang = Lang.EN) = assertEquals(t, FilesCommand.Rule(kind, merchant, drawer, lang), r(t))
        rule("Esso receipts go under Car", FileKind.RECEIPT, "Esso", "Car")
        rule("esso receipts go in car", FileKind.RECEIPT, "esso", "car")
        rule("put Esso receipts in Car", FileKind.RECEIPT, "Esso", "Car")
        rule("Put receipts from Esso in Car.", FileKind.RECEIPT, "Esso", "Car")
        rule("always file Hydro bills under House", FileKind.BILL, "Hydro", "House")
        rule("Robot, always file Hydro-Québec bills under the House drawer, please", FileKind.BILL, "Hydro-Québec", "House")
        rule("file Canadian Tire receipts in the Car folder from now on", FileKind.RECEIPT, "Canadian Tire", "Car")
        rule("Costco invoices go into Business", FileKind.BILL, "Costco", "Business")
        rule("Air Canada tickets go in Trips", FileKind.TICKET, "Air Canada", "Trips")
        rule("les reçus d'Esso vont dans Auto", FileKind.RECEIPT, "Esso", "Auto", Lang.FR)
        rule("Mets les reçus d’Esso dans Auto.", FileKind.RECEIPT, "Esso", "Auto", Lang.FR)
        rule("mets les reçus de Costco dans l'auto", FileKind.RECEIPT, "Costco", "auto", Lang.FR)
        rule("classe toujours les factures d'Hydro-Québec dans Maison", FileKind.BILL, "Hydro-Québec", "Maison", Lang.FR)
        rule("range les factures de Vidéotron dans le tiroir Maison", FileKind.BILL, "Vidéotron", "Maison", Lang.FR)
    }

    @Test fun moves() {
        val m = r("move the Costco receipt to Taxes") as FilesCommand.Move
        assertEquals(Query(FileKind.RECEIPT, "costco"), m.query)
        assertEquals("Taxes", m.drawer)
        assertEquals(Query(FileKind.BILL, "hydro"), (r("put my hydro bill in the House drawer") as FilesCommand.Move).query)
        val fr = r("déplace le reçu de Costco dans Impôts") as FilesCommand.Move
        assertEquals(Query(FileKind.RECEIPT, "costco"), fr.query)
        assertEquals("Impôts", fr.drawer)
        assertEquals(Lang.FR, fr.lang)
        // "it": only when the panel just filed or moved one.
        assertNull(r("move it to Taxes"))
        assertEquals(FilesCommand.Move(null, "Taxes", Lang.EN), r("move it to Taxes", recent = true))
        assertEquals(FilesCommand.Move(null, "Impôts", Lang.FR), r("déplace-le dans Impôts", recent = true))
    }

    @Test fun finds() {
        assertEquals(FilesCommand.Find(Query(FileKind.RECEIPT, "costco", month = 8), Lang.EN), r("find my Costco receipt from August"))
        assertEquals(FilesCommand.Find(Query(FileKind.BILL, "hydro"), Lang.EN), r("show my hydro bills"))
        assertEquals(FilesCommand.Find(Query(FileKind.DOCUMENT, "lease"), Lang.EN), r("where's my lease"))
        assertEquals(FilesCommand.Find(Query(FileKind.TAX_SLIP, "", year = 2025), Lang.EN), r("show me my 2025 tax slips"))
        assertEquals(FilesCommand.Find(Query(null, "warranty card"), Lang.EN).lang, r("find my warranty card in my files")!!.lang)
        assertEquals(FilesCommand.Find(Query(FileKind.RECEIPT, "costco", month = 8), Lang.FR), r("trouve mon reçu de Costco d'août"))
        assertEquals(FilesCommand.Find(Query(FileKind.BILL, "hydro"), Lang.FR), r("montre mes factures d'Hydro"))
        assertEquals(FilesCommand.Find(Query(FileKind.DOCUMENT, "bail"), Lang.FR), r("où est mon bail"))
    }

    @Test fun inboxAndDelete() {
        assertEquals(FilesCommand.Inbox(Lang.EN), r("What's in my Inbox?"))
        assertEquals(FilesCommand.Inbox(Lang.FR), r("qu'est-ce qu'il y a dans ma boîte de réception"))
        assertEquals(FilesCommand.Delete(Query(FileKind.RECEIPT, "costco"), Lang.EN), r("delete the Costco receipt"))
        assertEquals(FilesCommand.Delete(Query(FileKind.RECEIPT, "costco"), Lang.EN), r("remove the Costco receipt from my files"))
        assertEquals(FilesCommand.Delete(Query(FileKind.RECEIPT, "costco"), Lang.FR), r("efface le reçu de Costco"))
        assertEquals(FilesCommand.Delete(Query(FileKind.BILL, "hydro"), Lang.FR), r("supprime ma facture d'Hydro"))
    }

    /**
     * F13: everyday Québec French for each command (RoutingCorpusTest's corpus-f13 proves the other routers unchanged), and
     * the guards they keep: "it" only after a file was just filed and never before an article (« mets-le dans le coffre »),
     * find only with « mon », « ma » or « mes », « mis » only before « en » or « chez », never the tax return itself.
     */
    @Test fun quebecFrench() {
        val today = LocalDate.of(2026, 10, 22)
        fun q(t: String, recent: Boolean = false) = FilesRouter.route(t, recent, yours = listOf("House", "Car", "Auto"), today = today)
        // Find.
        assertEquals(FilesCommand.Find(Query(FileKind.RECEIPT, "costco"), Lang.FR), q("sors-moi mon reçu de Costco"))
        assertEquals(FilesCommand.Find(Query(FileKind.BILL, "hydro"), Lang.FR), q("affiche mes factures d'Hydro"))
        assertEquals(FilesCommand.Find(Query(FileKind.DOCUMENT, "bail"), Lang.FR), q("il est où mon bail?"))
        assertEquals(FilesCommand.Find(Query(FileKind.DOCUMENT, "location contrat"), Lang.FR), q("c'est où mon contrat de location"))
        assertEquals(FilesCommand.Find(Query(FileKind.TAX_SLIP, "t4"), Lang.FR), q("où est-ce que j'ai mis mon feuillet T4"))
        assertEquals(FilesCommand.Find(Query(FileKind.RECEIPT, "canadian tire"), Lang.FR), q("Peux-tu trouver mon reçu de Canadian Tire?"))
        // File this.
        for (t in listOf("sauve ça dans mes fichiers", "classe-moi ça", "garde cette facture", "mets ça dans mes fichiers"))
            assertEquals(t, FilesCommand.File(FileThis.Heard(FileThis.Ask.FILE, Lang.FR)), q(t))
        // FX2 (R5): typed without accents or hyphens, the same words.
        for (t in listOf("classe moi ca", "Classe-moi ca s'il te plait", "range ca", "sauve ca dans mes fichiers", "GARDE CETTE FACTURE"))
            assertEquals(t, FilesCommand.File(FileThis.Heard(FileThis.Ask.FILE, Lang.FR)), q(t))
        assertEquals(FilesCommand.File(FileThis.Heard(FileThis.Ask.ANYWAY, Lang.FR)), q("classe le quand meme"))
        // Move, and "it" only right after a filing, never before an article or to a place that isn't a drawer.
        assertEquals(FilesCommand.Move(Query(FileKind.RECEIPT, "costco"), "Impôts", Lang.FR), q("bouge le reçu de Costco dans Impôts"))
        assertEquals(FilesCommand.Move(null, "Impôts", Lang.FR), q("bouge-le dans Impôts", recent = true))
        assertEquals(FilesCommand.Move(null, "Auto", Lang.FR), q("mets-le dans Auto", recent = true))
        assertEquals(FilesCommand.Move(null, "Auto", Lang.FR), q("mets-le dans le tiroir Auto", recent = true))
        assertNull(q("bouge-le dans Impôts"))
        for (t in listOf("mets-le dans le coffre", "mets-le dans l'auto", "bouge-le dans la cuisine", "mets-le à gauche", "range-la dans ma sacoche"))
            assertNull(t, q(t, recent = true))
        // A rule.
        assertEquals(FilesCommand.Rule(FileKind.RECEIPT, "Esso", "Auto", Lang.FR), q("les reçus d'Esso, c'est dans Auto"))
        // FX2 (files-1.4-review.md R1), deliberately: « ça va dans » now names a drawer that exists, so this needs a Maison
        // drawer (F13 had it make one). Without it, the words are talk (the model's), as « c'est dans la boîte à gants » is.
        assertEquals(FilesCommand.Rule(FileKind.BILL, "Bell", "Maison", Lang.FR),
            FilesRouter.route("les factures de Bell, ça va dans Maison", yours = listOf("House", "Car", "Auto", "Maison"), today = today))
        assertNull(q("les factures de Bell, ça va dans Maison"))
        // « toutes les » as « tous les » (R5); the « vont dans » and « mets » forms still make a drawer your words name.
        assertEquals(FilesCommand.Rule(FileKind.BILL, "Bell", "Maison", Lang.FR), q("mets toutes les factures de Bell dans Maison"))
        assertEquals(FilesCommand.Rule(FileKind.BILL, "Hydro", "Maison", Lang.FR), q("toutes les factures d'Hydro vont dans Maison"))
        // The tax pack and the expense report; never the tax return.
        assertEquals(FilesCommand.Pack(PackKind.TAX, 2025, Lang.FR), q("fais mon dossier d'impôt 2025"))
        assertEquals(FilesCommand.Pack(PackKind.TAX, null, Lang.FR), q("sors-moi mon dossier d'impôts"))
        assertEquals(FilesCommand.Pack(PackKind.TAX, 2025, Lang.FR), q("prépare mes papiers d'impôts de l'an passé"))
        assertEquals(FilesCommand.Pack(PackKind.EXPENSES, 2026, Lang.FR), q("fais mon rapport de dépenses d'affaires 2026"))
        for (t in listOf("fais mon rapport d'impôts", "fais mes impôts de 2025", "prépare ma déclaration de revenus")) assertNull(t, q(t))
        // Spending: « gaz » is gasoline, « gaz naturel » a bill; « mis » only before « en » or « chez ».
        assertEquals("fuel", (q("combien j'ai mis en gaz en septembre") as FilesCommand.Spend).query.category)
        assertEquals("fuel", (q("combien j'ai dépensé en gaz ce mois-ci") as FilesCommand.Spend).query.category)
        assertEquals("Costco", (q("combien j'ai mis chez Costco le mois passé") as FilesCommand.Spend).query.merchant)
        assertEquals("utilities", FileSearch.categoryOf("gaz naturel"))
        assertEquals("utilities", FileSearch.categoryOf("natural gas"))
        assertEquals("fuel", FileSearch.categoryOf("gas"))
        for (t in listOf("combien j'ai mis de temps pour venir", "combien coûte le gaz à Montréal")) assertNull(t, q(t))
        // FX2 (R2): « mis en X » only when X is a category Robot knows, or only days.
        for (t in listOf("combien j'ai mis en REER cette année", "combien j'ai mis en banque", "combien j'ai mis en épargne ce mois-ci", "combien j'ai mis en temps"))
            assertNull(t, q(t))
        assertEquals("groceries", (q("combien j'ai mis en épicerie en août") as FilesCommand.Spend).query.category)
        assertEquals(FilesCommand.Spend::class, q("combien j'ai mis en tout ce mois-ci")!!::class)
        // What's due, and the Inbox.
        for (t in listOf("j'ai-tu des factures à payer cette semaine", "qu'est-ce que j'ai comme factures à payer cette semaine", "quels comptes je dois payer cette semaine"))
            assertEquals(t, FilesCommand.Upcoming(setOf(DateKind.DUE), Span.THIS_WEEK, "", Lang.FR), q(t))
        assertEquals(FilesCommand.Upcoming(setOf(DateKind.DUE), Span.ANY, "hydro", Lang.FR), q("c'est quand que je dois payer ma facture d'Hydro"))
        for (t in listOf("qu'est-ce qui attend dans ma boîte de réception", "est-ce qu'il y a quelque chose dans ma boîte de réception"))
            assertEquals(t, FilesCommand.Inbox(Lang.FR), q(t))
        // Everyday talk stays someone else's.
        for (t in listOf("il est où mon téléphone", "retrouve mon téléphone", "affiche la météo", "sors les vidanges", "montre mes comptes",
            "quels comptes Instagram je suis", "j'ai-tu des messages", "sauve ça dans mes notes", "classe-moi ces chansons par artiste", "bouge à gauche"))
            assertNull(t, q(t, recent = true))
    }

    /** §9's misfires, and everyday talk with a files word in it: none of these is Robot Files'. */
    @Test fun misfires() {
        val never = listOf(
            // free_storage
            "delete big files", "delete large files", "delete old files", "free up space", "free up storage", "clean up my storage",
            "efface les gros fichiers", "libère de l'espace", "libère de la mémoire", "free up memory",
            // notes, memory
            "save this note", "save that note", "save that to my notes", "remember that Esso receipts go in Car", "remember that my receipts are in the car",
            "souviens-toi que les reçus d'Esso vont dans Auto", "enregistre ça", "garde ça en tête", "delete the last note", "efface ma dernière note",
            // skills and routines
            "delete my evening routine", "run my evening routine", "teach a skill called receipts: open the camera", "lance ma routine du soir",
            // the web, places, things
            "find a pharmacy", "find a pharmacy near me", "find the nearest gas station", "find me a recipe for pasta", "find the cheapest tickets to Montreal",
            "find tickets for the concert", "where is the closest Costco", "trouve une pharmacie", "search the web for tax slips", "look up Esso receipts online",
            // helpers
            "ask Jarvis where my receipts go", "ask Claude to file my taxes",
            // everyday talk
            "file a bug", "file a complaint", "file my taxes", "file my taxes by April", "how do I file my taxes", "file a claim with my insurance",
            "move the meeting to 3", "move my dentist appointment to Friday", "move it to the left", "move the car", "put the milk in the fridge",
            "put it on my calendar", "put my receipts in the car", "put the receipts in the glovebox", "I think Costco receipts go in the car",
            "receipts are annoying", "I hate filing paperwork", "my taxes are due soon", "bills bills bills", "I lost my receipt", "the Costco receipt is in the car",
            "I need to pay the Hydro bill", "can you read this receipt", "what does this receipt say", "find my phone", "find my keys", "where's my car",
            "show my calendar", "show my notes", "open my files", "open the Files app", "share this file with Sam", "rename the file", "check my inbox",
            "what's in my email inbox", "mets le lait dans le frigo", "trouve mon téléphone", "où est mon auto", "déplace la réunion à 15 h", "range ta chambre",
            "classe les factures", "j'ai perdu mon reçu", "qu'est-ce qu'il y a dans mes courriels", "delete the alarm", "delete my old photos",
            // F10 takes spending and due dates (FileMoneyTest): its misfires are these.
            "how much does gas cost in Montreal", "what's due on my calendar this week", "remind me to pay the hydro bill on Friday",
            "cancel my Netflix subscription", "annule mon abonnement Netflix", "combien coûte l'essence à Montréal",
        )
        for (t in never) assertNull(t, r(t))
        // FX2 (files-1.4-review.md R1): asking where things go, or saying they're somewhere that isn't a drawer, is never a
        // rule (it saved one and made a drawer "Quel tiroir"), in any form, English included; with drawers of every name.
        val any = listOf("House", "Car", "Auto", "Maison", "Quel tiroir", "Which", "What", "Lequel", "Boîte à gants")
        for (t in listOf("les factures d'Hydro, c'est dans quel tiroir?", "les factures de Bell, ça va dans quel tiroir", "les reçus d'Esso, ça s'en va dans lequel",
                "mets les reçus d'Esso dans quel tiroir", "les reçus d'Esso vont dans quelle boîte", "Esso receipts go in which drawer",
                "put Esso receipts in what folder", "receipts from Esso go in which drawer", "toutes les factures de Bell vont dans laquelle")) {
            assertNull(t, r(t))
            assertNull(t, FilesRouter.route(t, yours = any))
        }
        assertNull(r("les reçus de Costco, c'est dans la boîte à gants"))
        assertNull(r("les factures d'Hydro, ça s'en va dans le garage"))
        // "move it…" without a file on the panel's mind; a move to a drawer that doesn't exist.
        assertNull(r("move it to Taxes"))
        assertNull(r("move it to the left", recent = true))
        // With a drawer called Left, "move it to the left" is still everyday speech; the drawer needs its bare name or "drawer".
        val left = listOf("Left", "Gauche")
        assertNull(FilesRouter.route("move it to the left", recent = true, yours = left))
        assertEquals(FilesCommand.Move(null, "Left", Lang.EN), FilesRouter.route("move it to Left", recent = true, yours = left))
        assertEquals(FilesCommand.Move(null, "Left", Lang.EN), FilesRouter.route("move it to the Left drawer", recent = true, yours = left))
        assertNull(FilesRouter.route("déplace-le dans la gauche", recent = true, yours = left))
        assertEquals(FilesCommand.Move(null, "Gauche", Lang.FR), FilesRouter.route("déplace-le dans Gauche", recent = true, yours = left))
        assertNull(r("move the Costco receipt to the glovebox"))
        assertNull(r("mets le reçu dans la boîte à gants"))
        assertNull(FilesRouter.route("put my hydro bill in the House drawer"))
        // Only the kind: talk, even with drawers called Car and House (a move or a delete needs a word more than its kind).
        for (t in listOf("put my receipt in the car", "put the bill in the house", "move the receipt to the car", "mets le reçu dans l'auto",
                "range la facture dans la maison", "delete the receipt", "efface la facture"))
            assertNull(t, FilesRouter.route(t, recent = true, yours = listOf("Car", "House", "Auto", "Maison")))
    }

    /** The routers before it in the panel's order (D15) leave its phrases alone, so they reach it. */
    @Test fun theRoutersBeforeItLeaveItsPhrasesAlone() {
        val now = ZonedDateTime.parse("2026-10-22T10:00:00-04:00[America/Toronto]")
        val phrases = listOf("Esso receipts go under Car", "put receipts from Esso in Car", "always file Hydro bills under House",
            "move the Costco receipt to Taxes", "find my Costco receipt from August", "show my hydro bills", "where's my lease", "what's in my Inbox",
            "delete the Costco receipt", "les reçus d'Esso vont dans Auto", "mets les reçus d'Esso dans Auto", "déplace le reçu de Costco dans Impôts",
            "trouve mon reçu de Costco d'août", "montre mes factures d'Hydro", "où est mon bail", "qu'est-ce qu'il y a dans ma boîte de réception",
            "efface le reçu de Costco")
        for (t in phrases) {
            assertTrue(t, r(t) != null)
            assertNull(t, MessagesRouter.route(t))
            assertNull(t, ConversationRouter.route(t, running = false))
            assertNull(t, FeedbackRouter.route(t))
            assertNull(t, LockRouter.route(t, now, listOf("Bedtime")))
            assertNull(t, RoutineRouter.route(t, now, listOf("Evening routine")))
            assertNull(t, SkillRouter.route(t, listOf(SkillFixtures.headingHome(), SkillFixtures.pasta())))
            assertNull(t, HelperRouter.route(t, listOf(HelperName.CLAUDE)))
        }
    }

    @Test fun drawersByName() {
        val yours = listOf("Car", "Maison")
        assertEquals(Drawers.TAXES, FilesRouter.drawer("Taxes", yours))
        assertEquals(Drawers.TAXES, FilesRouter.drawer("impôts", yours))
        assertEquals(Drawers.TAXES, FilesRouter.drawer("Impots", yours))
        assertEquals(Drawers.TRIPS, FilesRouter.drawer("trips", yours))
        assertEquals(Drawers.TRIPS, FilesRouter.drawer("Voyages et événements", yours))
        assertEquals(Drawers.MEDICAL, FilesRouter.drawer("Santé", yours))
        assertEquals("Car", FilesRouter.drawer("the car", yours))
        assertEquals("Maison", FilesRouter.drawer("maison", yours))
        assertNull(FilesRouter.drawer("Glovebox", yours))
        assertEquals("Glovebox", FilesRouter.newDrawer("glovebox"))
        assertEquals("Auto", FilesRouter.newDrawer("l'auto"))
    }

    private var n = 0
    private fun file(kind: FileKind, merchant: String?, date: String, name: String = "f$n"): FileEntry {
        val id = FileId.of("%032x".format(++n))!!
        return FileEntry(id, name, kind, FileState.FILED, Provenance(FileSource.SHARE, at = n * 1_000L, size = 10, sha256 = "a".repeat(64)),
            drawer = Drawers.of(kind), fields = listOfNotNull(merchant?.let { "merchant" to it }, "date" to date).toMap().mapValues { FileField(it.value, FieldBy.READ) })
    }

    @Test fun theFileAPhraseNames() {
        val costcoAug = file(FileKind.RECEIPT, "Costco Wholesale", "2026-08-14", "2026-08-14 Costco 84.12 CAD")
        val costcoSep = file(FileKind.RECEIPT, "Costco Wholesale", "2026-09-02", "2026-09-02 Costco 12.00 CAD")
        val esso = file(FileKind.RECEIPT, "Esso", "2026-08-20", "2026-08-20 Esso 50.00 CAD")
        val hydro = file(FileKind.BILL, "Hydro-Québec", "2026-09-01", "2026-09 Hydro-Québec 142.00 CAD")
        val idx = FileIndex(listOf(costcoAug, costcoSep, esso, hydro))
        val z = ZoneOffset.UTC
        val aug = (r("find my Costco receipt from August") as FilesCommand.Find).query
        assertEquals(listOf(costcoAug), FileFind.find(idx, aug, zone = z))
        val all = (r("delete the Costco receipt") as FilesCommand.Delete).query
        val two = FileFind.find(idx, all, zone = z)
        assertEquals(setOf(costcoAug, costcoSep), two.toSet())
        // Two match: a move or a delete by voice doesn't pick one.
        assertNull(FileFind.one(two, all))
        assertEquals(esso, FileFind.one(FileFind.find(idx, Query(FileKind.RECEIPT, "esso"), zone = z), Query(FileKind.RECEIPT, "esso")))
        assertEquals(listOf(hydro), FileFind.find(idx, (r("montre mes factures d'Hydro") as FilesCommand.Find).query, zone = z))
        // Every receipt, newest first, when no words narrow it.
        assertEquals(listOf(costcoSep, esso, costcoAug), FileFind.find(idx, Query(FileKind.RECEIPT, ""), zone = z))
        assertTrue(FileFind.find(idx, Query(FileKind.RECEIPT, "walmart"), zone = z).isEmpty())
        assertEquals(LocalDate.of(2026, 8, 14), FileViews.day(costcoAug, z))
    }

    @Test fun noWordsInToString() {
        for (t in listOf("Esso receipts go under Car", "move the Costco receipt to Taxes", "find my Costco receipt from August", "delete the Costco receipt")) {
            val s = r(t).toString()
            assertTrue(s, !s.contains("Esso", ignoreCase = true) && !s.contains("Costco", ignoreCase = true) && !s.contains("Car"))
        }
    }
}
