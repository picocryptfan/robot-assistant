// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.car

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

// fix-car-media-pick: once a music app was yours, nothing on the car screen opened the list again (a tap goes as
// CarMusicChoice.of says: your app, never the list). A hold on the music tile opens it; this fails if the hold goes.
class CarMusicPickTest {
    @Test fun aHoldOnTheMusicTileOpensTheList() {
        val screen = File("src/main/java/app/heyrobot/ui/car/CarModeScreen.kt").readText()
        assertTrue(Regex("combinedClickable\\([^\\n]*onLongClick = a\\.chooseMusic\\)").containsMatchIn(screen))
        val activity = File("src/main/java/app/heyrobot/assistant/car/CarModeActivity.kt").readText()
        assertTrue("chooseMusic = { chooseMusic() }" in activity)
    }
}
