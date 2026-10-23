// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.PhraseWire
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1 (WP7): the model's and the voice models' words in Québec French (core ModelStrings, VoiceStrings), their English
 * unchanged, and what crosses processes or is kept in a file (a file's check, a download's failure, the speed test's step)
 * carried without a language.
 */
class ModelFrenchTest {
    private val fr = Langs.of(Lang.FR)
    private fun Phrase.fr(): String = render(fr)
    private val nb = ' '
    private val fine = ' '

    @Test fun theModelsKeepTheirNames() {
        assertEquals("Rapide", BrainModel.FAST.titleText.fr())
        assertEquals("Rapide (bêta)", BrainModel.FAST_G6.titleText.fr())
        assertEquals("Plus futé, plus lent", BrainModel.SMARTER.titleText.fr())
        assertEquals("Smarter, slower", BrainModel.SMARTER.title)
        assertEquals("Ton propre fichier de modèle", BrainModel.OWN.titleText.fr())
        assertEquals("Your own model file", BrainModel.OWN.title)
        assertTrue(BrainModel.UNCENSORED.aboutText.fr(), BrainModel.UNCENSORED.aboutText.fr().contains("comme le disent Réglages › Actions et Sécurité et confidentialité"))
    }

    /** Fix 1.1: the voice models and the Siwis voice say what 1.1 does: it hears French, and Siwis reads French replies. */
    @Test fun theVoiceLinesSayWhat11Does() {
        assertEquals("NVIDIA Parakeet TDT 0.6B v3 (int8) turns what you say into text, on this phone, in English or French.", VoiceBundle.STT.aboutText.en())
        assertEquals("NVIDIA Parakeet TDT 0.6B v3 (int8) transforme ce que tu dis en texte, sur ce téléphone, en anglais ou en français.", VoiceBundle.STT.aboutText.fr())
        assertEquals("Kokoro 82M v1.0 reads replies aloud in a natural voice: “Heart” in English, “Siwis” in French. " +
            "Siwis reads French replies when Robot's voice is Heart or Siwis.", VoiceBundle.KOKORO.aboutText.en())
        assertEquals("Kokoro 82M v1.0 lit les réponses à voix haute d’une voix naturelle$nb: «${nb}Heart$nb» en anglais, «${nb}Siwis$nb» en français. " +
            "Siwis lit les réponses en français quand la voix de Robot est Heart ou Siwis.", VoiceBundle.KOKORO.aboutText.fr())
        assertEquals("French, natural (Kokoro): it reads French replies with Heart too and, picked here, reads English with French pronunciation",
            VoiceChoice.SIWIS.detailText.en())
        assertEquals("Français, naturelle (Kokoro)$nb: elle lit aussi les réponses en français avec Heart et, choisie ici, lit l’anglais avec une prononciation française",
            VoiceChoice.SIWIS.detailText.fr())
    }

    @Test fun whyRobotUsesFastInFrench() {
        assertEquals("«${nb}Plus futé, plus lent$nb» n’est pas encore sur ce téléphone, alors Robot utilise Rapide. Télécharge-le dans l’appli Robot › Réglages › Modèle.",
            ModelChoice.fallbackPhrase(BrainModel.SMARTER, FallbackWhy.NOT_DOWNLOADED).fr())
        assertEquals("“Smarter, slower” isn't on this phone yet, so Robot is using Fast. Download it in the Robot app › Settings › Model.",
            ModelChoice.fallbackText(BrainModel.SMARTER, FallbackWhy.NOT_DOWNLOADED))
        val none = ModelChoice.decide(BrainModel.UNCENSORED, ModelAvailability.NOT_DOWNLOADED, ModelAvailability.NOT_DOWNLOADED, false)
            as ModelChoice.Decision.NoLoad
        assertEquals("“Uncensored, slower” isn't on this phone yet, and Fast isn't on this phone either. Open the Robot app › Settings › Model.", none.problem)
        assertEquals("«${nb}Sans censure, plus lent$nb» n’est pas encore sur ce téléphone, et Rapide n’est pas sur ce téléphone non plus. Ouvre l’appli Robot › Réglages › Modèle.",
            none.text.fr())
        // Equality stays the English problem's, as before 1.1.
        assertEquals(ModelChoice.Decision.NoLoad(ModelChoice.STOPPING, false),
            ModelChoice.afterFailure(BrainModel.SMARTER, LoadFailure.STOPPING, ModelAvailability.READY))
    }

    @Test fun aDownloadsStateAndSpaceInFrench() {
        assertEquals("3,11${nb}Go", ModelLogic.gbText(3_113_545_589L).fr())
        assertEquals("3.11 GB", ModelLogic.gb(3_113_545_589L))
        assertEquals("1,56${nb}Go sur 3,11${nb}Go (49${nb}%)", ModelLogic.downloadPhrase(BrainModel.FAST, Download.STATUS_RUNNING, 0, 1_556_772_794L).fr())
        assertEquals("Le téléchargement a échoué${nb}: le téléphone a manqué d’espace. Touche Télécharger pour réessayer.",
            ModelLogic.downloadPhrase(BrainModel.FAST, Download.STATUS_FAILED, 1006, 0).fr())
        val space = ModelLogic.spaceProblemText(BrainModel.SMARTER, 2_000_000_000L, 0)!!
        assertTrue(space.fr(), space.fr().startsWith("Pas assez d’espace libre${nb}: Gemma 4 E4B (CPU) a besoin de 7,26${nb}Go (le fichier de 3,66${nb}Go"))
        assertTrue(space.fr(), space.fr().endsWith("et il y a 2,00${nb}Go de libre."))
        assertEquals("Ce fichier n’est pas le modèle${nb}: il fait 1${nb}000 octets et gemma-4-E2B-it_Google_Tensor_G5.litertlm en fait 3${nb}113${nb}545${nb}589.",
            ModelLogic.refuseImportText(BrainModel.FAST, ModelStatus.MISSING, 1000, 10_000_000_000L, 0)!!.fr())
        assertEquals("That file isn't the model: it has 1,000 bytes and gemma-4-E2B-it_Google_Tensor_G5.litertlm has 3,113,545,589.",
            ModelLogic.refuseImport(BrainModel.FAST, ModelStatus.MISSING, 1000, 10_000_000_000L, 0))
        assertEquals("Téléchargé, pas encore vérifié", ModelLogic.headlineText(ModelStatus.UNVERIFIED, BrainModel.FAST).fr())
    }

    @Test fun whyBrainRefusesAFileInFrench() {
        val p = Phone("Google", "Pixel 9a", "Google", "Tensor G4", 8_000_000_000L)
        assertEquals("Ce téléphone n’a pas de TPU que Robot peut utiliser${nb}: les versions TPU fonctionnent sur un Tensor G5 (Pixel 10) et, en bêta, sur un Tensor G6 (Pixel 11).",
            LoadCheck.refuseText(BrainModel.FAST, ModelBackend.TPU, "fp", null, null, phone = p)!!.fr())
        assertEquals(LoadCheck.NO_TPU, LoadCheck.refuse(BrainModel.FAST, ModelBackend.TPU, "fp", null, null, phone = p))
        // Sent from :brain as its id and arguments, said by the app in its own language.
        val wire = PhraseWire.encode(LoadCheck.changedText)
        assertEquals("Le fichier du modèle a changé depuis que Robot l’a vérifié. Ouvre l’appli Robot › Réglages › Modèle pour le vérifier de nouveau.",
            PhraseWire.decode(wire)!!.fr())
    }

    @Test fun aGgufFilesCheckInFrenchAndKeptWithoutALanguage() {
        val r = GgufReport(readable = true, version = 3, architecture = "qwen3", name = "Qwen3 4B", fileType = 15, contextLength = 40_960,
            parameters = 4_000_000_000L, tokenizer = "gpt2", chatTemplate = true, templateThinking = true, fileBytes = 2_500_000_000L)
        val lines = r.linesText().map { it.fr() }
        assertEquals("Fichier GGUF, version 3${nb}: «${nb}Qwen3 4B$nb» (architecture qwen3, environ 4,0 milliards de paramètres).", lines[0])
        assertEquals("GGUF file, version 3: “Qwen3 4B” (architecture qwen3, about 4.0 billion parameters).", r.lines()[0])
        assertEquals("Quantification${nb}: Q4_K_M.", lines[1])
        assertEquals("Contexte${nb}: 40${nb}960 jetons (Robot en utilise 4${nb}096).", lines[2])
        assertTrue(lines.toString(), lines.any { it.startsWith("Il peut réfléchir avant de répondre${nb}: Réglages › Modèle › «${nb}Laisser le modèle réfléchir d’abord$nb»") })
        val bad = GgufReport(readable = false, problemText = app.heyrobot.core.i18n.ModelStrings.ggufUnreadableDetail("detail" to "a tensor too large"))
        val again = GgufReport.fromJson(JSONObject(bad.toJson().toString()))!!
        assertEquals("It isn't a GGUF model file Robot can read (a tensor too large).", again.problem)
        assertEquals("Ce n’est pas un fichier de modèle GGUF que Robot peut lire (détail technique${nb}: a tensor too large).", again.unusableText!!.fr())
        // A record written before 1.1: its English, as it is.
        val old = GgufReport.fromJson(JSONObject().put("readable", false).put("problem", "Robot couldn't read the file."))!!
        assertEquals("Robot couldn't read the file.", old.unusableText!!.fr())
    }

    @Test fun aModelProblemInFrench() {
        val p = ModelProblem(1L, BrainModel.GENERAL, ModelBackend.GPU, ProblemStage.WRITING, null, BrainModel.GENERAL, ModelBackend.CPU)
        assertEquals("Rapide s’est arrêté sur le GPU, alors Robot est passé au CPU.", p.headlineText().fr())
        assertEquals("Fast stopped on the GPU, so Robot switched to the CPU.", p.headline())
        assertEquals(":brain · écriture de la réponse · Rapide sur le GPU", ProblemList.rowText(p).fr())
        val f = GpuFailure(BrainModel.GENERAL, 1L, ProblemStage.WRITING, LoadFailure.ENGINE)
        assertEquals("Le GPU a échoué pendant l’écriture de la réponse (erreur du moteur)", f.titleText.fr())
        @Suppress("DEPRECATION")
        assertEquals("failed while writing the answer (engine error)", f.what)
    }

    @Test fun thisPhoneAndItsSpeedsInFrench() {
        val p = Phone("Google", "Pixel 7", "Google", "Tensor G2", 8_000_000_000L)
        assertEquals("Google ne publie aucune version TPU de Gemma pour le Tensor G2${nb}: Rapide est la version générale de Gemma 4 E2B, sur le GPU (recommandé) ou le CPU.",
            Phones.tpuLineText(p).fr())
        val e = Speeds.of(BrainModel.GENERAL, ModelBackend.CPU, p)!!
        assertTrue(e.sourceText.fr(), e.sourceText.fr().contains("Google a mesuré 557 jetons par seconde lus et 46,9 écrits"))
        assertTrue(e.sourceText.fr(), e.sourceText.fr().endsWith("Un Tensor G2 est plus ancien qu’un Tensor G4${nb}: attends-toi à ce qu’il soit plus lent que ça."))
        assertTrue(e.source, e.source.endsWith("(its model card). A Tensor G2 is older than a Tensor G4: expect it to be slower than this."))
        assertEquals("Téléphone inconnu", Phone("", "", "", "", 0).nameText.fr())
    }

    @Test fun theVoiceModelsInFrench() {
        assertEquals("Reconnaissance vocale", VoiceBundle.STT.titleText.fr())
        assertEquals("670${nb}Mo", VoiceModelLogic.sizeText(VoiceBundle.STT.bytes).fr())
        assertEquals("670 MB", VoiceModelLogic.size(VoiceBundle.STT.bytes))
        // The fast translation engine's sizes are the voice models' (1.1).
        assertEquals(VoiceModelLogic.sizeText(52_000_000L).fr(), MtModelLogic.sizeText(52_000_000L).fr())
        assertEquals("Téléchargé, installation à suivre", VoiceModelLogic.headlineText(ModelStatus.UNVERIFIED).fr())
        assertEquals("Vérification et installation${nb}: 40${nb}%", VoiceModelLogic.installPhrase(40).fr())
        assertEquals("Télécharger 1,02${nb}Go${fine}?", DownloadPolicy.titleText(1_020_000_000L).fr())
        assertEquals("Download 1.02 GB?", DownloadPolicy.title(1_020_000_000L))
        assertEquals("Prêt (se charge à la première utilisation)", EngineState.IDLE.textPhrase().fr())
    }

    @Test fun theSpeedTestsStepCrossesWithoutALanguage() {
        val b = VoiceBench(BenchState.FAILED, app.heyrobot.core.i18n.VoiceStrings.engineFailed(), 1L)
        val again = VoiceBench.fromJson(b.toJson().toString())!!
        assertEquals("Couldn't start. Robot uses the stand-in for now.", again.step)
        assertEquals("N’a pas pu démarrer. Robot utilise le remplaçant pour l’instant.", again.stepText.fr())
        val lines = VoiceBenchLogic.linesText(VoiceBench(BenchState.DONE, "", 1, SttBench(3000, 5330, 400, 300, 94), emptyList(), 1300))
        assertEquals("Reconnaissance vocale${nb}: 5,3${nb}s de parole transcrites en 0,40${nb}s (facteur temps réel 0,08), 94${nb}% des mots justes. Le chargement a pris 3,0${nb}s.",
            lines[0].fr())
    }

    @Test fun brainsLoadFailureCrossesWithoutALanguage() {
        val why = app.heyrobot.core.i18n.VoiceStrings.engineNoCacheSpace("size" to app.heyrobot.core.i18n.VoiceStrings.gbOne("int" to "2", "frac" to "5"))
        val sent = BrainMsg.Loaded(false, false, 0, why.en(), BrainModel.SMARTER, ModelBackend.CPU, LoadFailure.NO_SPACE, ModelFormat.LITERT_LM, why)
        val got = BrainMsg.decode(BrainProtocol.EV_LOADED, sent.toJson().toString())
        assertEquals("There isn't enough free space for the cache it writes on its first load (about 2.5 GB).", (got as BrainMsg.Loaded).error)
        assertEquals("Il n’y a pas assez d’espace libre pour le cache qu’il écrit à son premier chargement (environ 2,5${nb}Go).", got.errorText!!.fr())
        // From an older :brain: its English, as it is.
        assertEquals("Out of memory.", BrainMsg.Loaded(false, false, 0, "Out of memory.").errorText!!.fr())
    }

    @Test fun theBargeInTestsFailureCrossesWithoutALanguage() {
        val r = BargeTestResult(BenchState.FAILED, false, 1L, BargeIn.ECHO_CANCEL, Playback.ASSISTANT)
            .withNote(app.heyrobot.core.i18n.VoiceStrings.sttMicBusy())
        val again = BargeTestResult.fromJson(r.toJson().toString())!!
        assertEquals("Another app is using the microphone.", again.note)
        assertEquals("Une autre appli utilise le micro.", again.noteText!!.fr())
    }
}
