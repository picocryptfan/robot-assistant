package app.heyrobot.ui.common

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.0 audit (secrets-memory-private-items-not-secure): Settings › Memory's window is FLAG_SECURE while it shows (and only
 * then), and its text fields ask the keyboard for no personalized learning.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecureTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun secure() = rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE

    @Test fun theWindowIsSecureOnlyWhileTheScreenShows() {
        var shown by mutableStateOf(true)
        rule.setContent { if (shown) SecureWindow() }
        rule.waitForIdle()
        assertNotEquals(0, secure())
        shown = false
        rule.waitForIdle()
        assertEquals(0, secure())
    }

    @Test fun textFieldsAskForNoPersonalizedLearning() {
        rule.setContent {
            NoImeLearning {
                var t by remember { mutableStateOf("") }
                OutlinedTextField(t, { t = it }, label = { Text("What Robot remembers") }, modifier = Modifier.testTag("f"))
            }
        }
        rule.onNodeWithTag("f").performClick()
        rule.waitForIdle()
        val info = EditorInfo()
        rule.runOnIdle { composeView(rule.activity.window.decorView)!!.onCreateInputConnection(info) }
        assertNotEquals(0, info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertEquals(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, noLearning(0))
    }

    private fun composeView(v: View): View? {
        if (v.javaClass.simpleName == "AndroidComposeView") return v
        if (v is ViewGroup) for (i in 0 until v.childCount) composeView(v.getChildAt(i))?.let { return it }
        return null
    }
}
