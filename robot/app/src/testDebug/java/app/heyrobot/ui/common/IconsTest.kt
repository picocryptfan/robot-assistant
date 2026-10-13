package app.heyrobot.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** RIcons: every icon builds, the Material Symbols on their 960-unit grid, and each one is what tools/symbols.py wrote. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class IconsTest {
    @get:Rule val rule = createComposeRule()

    /** Every RIcons property, by its getter (they are lazy). */
    private val all: List<Pair<String, ImageVector>> = RIcons::class.java.declaredMethods
        .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == ImageVector::class.java }
        .sortedBy { it.name }
        .map { it.name.removePrefix("get") to it.invoke(RIcons) as ImageVector }

    @Test fun everyIconBuildsAt24Dp() {
        assertTrue(all.size.toString(), all.size >= 50)
        for ((name, v) in all) {
            assertEquals(name, 24.dp, v.defaultWidth)
            assertEquals(name, 24.dp, v.defaultHeight)
            assertTrue(name, v.root.size > 0)
        }
    }

    @Test fun theSymbolsSitOnTheirGrid() {
        for ((name, v) in all.filter { it.first != "Robot" }) {
            assertEquals(name, 960f, v.viewportWidth)
            assertEquals(name, 960f, v.viewportHeight)
            // The SVG paths are drawn from y = -960: one group moves them onto the canvas.
            val g = v.root.single() as VectorGroup
            assertEquals(name, 960f, g.translationY)
            assertTrue(name, g.size > 0)
        }
        assertEquals(48f, RIcons.Robot.viewportWidth)
    }

    @Test fun iconsAreTheOnesTheScriptWrites() {
        val root = File(System.getProperty("robot.rootDir")!!)
        val script = File(root, "tools/symbols.py").readText()
        val listed = Regex("""\("(\w+)", "(\w+)", """).findAll(script).map { it.groupValues[1] to it.groupValues[2] }.toList()
        val src = File(root, "app/src/main/java/app/heyrobot/ui/common/Icons.kt").readText()
        val written = Regex("""val (\w+) by lazy \{ symbol\("(\w+)"""").findAll(src).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(listed, written)
        assertEquals(all.map { it.first }.filter { it != "Robot" }.sorted(), written.map { it.first }.sorted())
        // Only symbols, and the classic orb's glyph; no network address in the app's own code.
        assertTrue(!src.contains("https://"))
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Test fun sheet() {
        rule.show {
            FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((name, v) in all) {
                    Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(v, name)
                        Text(name, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
                    }
                }
            }
        }
        rule.shot("icons")
    }
}
