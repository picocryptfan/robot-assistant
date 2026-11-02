// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.link

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.heyrobot.core.Person
import app.heyrobot.core.link.DeskCard
import app.heyrobot.core.link.DeskProposal
import app.heyrobot.core.link.DeskView
import app.heyrobot.core.link.DeskYes
import app.heyrobot.core.link.LinkCapability
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.LinkLevels
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.LinkPreset
import app.heyrobot.core.link.LinkSettings
import app.heyrobot.core.link.PairingPhase
import app.heyrobot.core.link.PairingView
import app.heyrobot.core.link.Verified
import app.heyrobot.core.link.requests.FbAsk
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

/**
 * 1.5 L13: Robot to Robot's screens in Québec French, over the fake :link of [LinkScreensTest]: the words a person agrees to
 * on each (pairing's modes, the safety number's warning, a move, blocking, the permissions' limits, a card), 48 dp targets
 * with the longer words, and a screenshot of each.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
class LinkScreensFrenchTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()
    private val sam = Person(UUID.randomUUID().toString(), "Sam Tremblay")

    private fun view(id: String = LinkIds.newId(), phase: PairingPhase = PairingPhase.ACTIVE, verified: Verified = Verified.NO, person: String? = sam.id,
        mode: LinkMode = LinkMode.PRIVATE, preset: LinkPreset? = null) =
        PairingView(id, phase, mode, 1_790_000_000L, "Sam", person, verified, if (verified == Verified.NO) 0 else 1_790_000_100L, preset,
            LinkLevels.of(preset ?: LinkPreset.SOMEONE_ELSE), false, false, 0, if (phase == PairingPhase.MOVED) "Sam" else "", if (phase == PairingPhase.ACTIVE) 0 else 1_790_000_500L)

    private fun port(vararg views: PairingView) = LinkScreensTest.FakePort(LinkSettings(on = true, name = "Matt")).apply { ready = true; people = listOf(sam); this.views = views.toList() }
    private fun waitFor(text: String) = rule.waitUntil(5_000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    private fun summary(text: String) = rule.onNode(SemanticsMatcher("summary has '$text'") { it.config.getOrNull(SemanticsProperties.StateDescription)?.contains(text) == true })
    private fun noProblems() = assertEquals(emptyList<String>(), rule.touchTargetProblems())

    @Test fun theSettingsPage() {
        rule.show { LinkSettingsScreen(port(view(verified = Verified.IN_PERSON)), log.actions, SnackbarHostState()) {} }
        waitFor("Utiliser Robot à Robot")
        rule.onNodeWithText("Utiliser Robot à Robot").assertExists()
        rule.onNodeWithText("sans serveur Robot entre les deux", substring = true).assertExists()
        rule.shot("link_settings_fr")
        rule.scrollToText("Sam Tremblay")
        noProblems()
        rule.scrollToText("Se jumeler avec quelqu’un")
        rule.scrollToText("Ponts Tor")
        rule.scrollToText("Seulement à partir de")
        summary("9\u00A0h").assertExists()
        rule.scrollToText("Recommencer")
    }

    @Test fun pairingsModesAndWays() {
        rule.show { PairScreen(port(), log.actions, SnackbarHostState(), {}, pollMs = 20) }
        waitFor("Anonyme")
        rule.onNodeWithText("le jumelage utilise le mode anonyme", substring = true).assertExists()
        rule.onNodeWithText("un serveur push voit que les deux téléphones se parlent", substring = true).assertExists()
        rule.shot("link_pair_fr")
        noProblems()
    }

    @Test fun theSafetyNumbersWarning() {
        val id = LinkIds.newId()
        rule.show { SafetyScreen(port(view(id)), id, log.actions, SnackbarHostState()) {} }
        waitFor("12345")
        rule.onNodeWithText("personne n’est entre les deux", substring = true).assertExists()
        rule.onNodeWithText("ne continue pas", substring = true).assertExists()
        rule.shot("link_safety_fr")
        noProblems()
    }

    @Test fun aMoveWaitsForYourAnswerAndBlockingSaysWhatItDoes() {
        val id = LinkIds.newId()
        rule.show { PairingScreen(port(view(id, phase = PairingPhase.MOVED)), id, log.actions, SnackbarHostState(), {}, pollMs = 0) }
        waitFor("dit avoir changé de téléphone")
        rule.onNodeWithText("Compare-le avant d’accepter", substring = true).assertExists()
        rule.onNodeWithText("rien ne passe, dans un sens comme dans l’autre", substring = true).assertExists()
        rule.shot("link_moved_fr")
        noProblems()
        rule.scrollToText("Bloquer")
        rule.onRow("Bloquer").performClick()
        rule.onNodeWithText("sans te le demander", substring = true).assertExists()
    }

    @Test fun foldersAskDetailSaysChangesComeWithNoCard() {
        val id = LinkIds.newId()
        rule.show { PermissionsScreen(port(view(id, preset = LinkPreset.FAMILY_OR_FRIEND)), id, log.actions, SnackbarHostState()) {} }
        waitFor("Moments libres et occupés")
        rule.onRow("Dossiers partagés").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Une carte pour chaque dossier offert", substring = true).assertExists()
    }

    @Test fun thePermissionsLimits() {
        val id = LinkIds.newId()
        rule.show { PermissionsScreen(port(view(id, preset = LinkPreset.FAMILY_OR_FRIEND)), id, log.actions, SnackbarHostState()) {} }
        waitFor("Moments libres et occupés")
        rule.shot("link_permissions_fr")
        rule.onNodeWithText("L’argent reste en dehors de ça", substring = true).assertExists()
        // A row's summary is its state in TalkBack, not its text: Preferences.kt.
        summary("moments occupés seulement, jamais les titres, les lieux ni les personnes").assertExists()
        summary("seulement de 9\u00A0h à 21\u00A0h, quand tu es libre").assertExists()
        noProblems()
        // L16: splitting costs isn't in this release.
        rule.onNodeWithText("Partage des coûts").assertDoesNotExist()
    }

    @Test fun torBridges() {
        rule.show { BridgesScreen(port(), log.actions, SnackbarHostState()) {} }
        waitFor("Ponts Tor")
        rule.onNodeWithText("Ton réseau peut voir que tu utilises Tor", substring = true).assertExists()
        rule.shot("link_bridges_fr")
        noProblems()
    }

    @Test fun requestsCardAndProposal() {
        val v = view(preset = LinkPreset.FAMILY_OR_FRIEND)
        val at = 1_792_000_800L
        val fb = LinkRequest.of(FbAsk(at, at + 6 * 3_600), at, "fr")
        val port = object : LinkPort by port(v) {
            override fun desk() = DeskView(listOf(DeskCard("c1", v.id, at, DeskYes.SEND_BUSY, fb)), emptyList(), listOf(DeskProposal(v.id, LinkCapability.SPLITS)), emptyMap())
        }
        rule.show { RequestsScreen(port, log.actions, SnackbarHostState()) {} }
        waitFor("Robot suggère")
        rule.onNodeWithText("Il ne paie jamais rien", substring = true).assertExists()
        rule.onNodeWithText("Seuls les moments occupés lui seraient renvoyés", substring = true).assertExists()
        rule.onNodeWithText("Envoyer les moments occupés").assertExists()
        rule.shot("link_requests_fr")
        noProblems()
    }
}
