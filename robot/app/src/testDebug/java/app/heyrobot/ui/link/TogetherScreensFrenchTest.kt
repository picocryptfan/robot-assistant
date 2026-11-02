// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.link

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** L11: the shared folders and group plans pages in Québec French, with their goldens. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
class TogetherScreensFrenchTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()
    private fun waitFor(text: String) = rule.waitUntil(5_000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun folders() {
        rule.show { FoldersScreen(TogetherFixtures.port(), log.actions, SnackbarHostState()) {} }
        waitFor("Trip photos")
        rule.onNodeWithText("jusqu’à 500 fichiers et 1 Gio par dossier", substring = true).assertExists()
        rule.shot("link_folders_fr")
    }

    @Test fun oneFolder() {
        rule.show { FolderScreen(TogetherFixtures.port(), TogetherFixtures.SAM, TogetherFixtures.FOLD, log.actions, SnackbarHostState()) {} }
        waitFor("beach.jpg")
        rule.onNodeWithText("Retirer").assertExists()
        rule.shot("link_folder_fr")
    }

    @Test fun aNewFolder() {
        rule.show { FolderNewScreen(TogetherFixtures.port(), log.actions, SnackbarHostState()) {} }
        waitFor("Nom du dossier")
        rule.shot("link_folder_new_fr")
    }

    @Test fun groups() {
        rule.show { GroupsScreen(TogetherFixtures.port(), log.actions, SnackbarHostState()) {} }
        waitFor("Dinner")
        rule.scrollToText("Robot ne peut pas vérifier ça.")
        rule.shot("link_groups_fr")
    }

    @Test fun oneGroup() {
        rule.show { GroupScreen(TogetherFixtures.port(), TogetherFixtures.GROUP, log.actions, SnackbarHostState()) {} }
        waitFor("Moments qui conviennent")
        rule.shot("link_group_fr")
    }

    @Test fun aNewGroup() {
        rule.show { GroupNewScreen(TogetherFixtures.port(), log.actions, SnackbarHostState()) {} }
        waitFor("C’est quoi")
        rule.shot("link_group_new_fr")
    }
}
