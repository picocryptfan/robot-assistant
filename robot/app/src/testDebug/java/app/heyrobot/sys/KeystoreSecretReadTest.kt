package app.heyrobot.sys

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.assistant.HelperKeys
import app.heyrobot.assistant.HelperLink
import app.heyrobot.core.HelperFailure
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SecretRead
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperKeyFormat
import app.heyrobot.core.helpers.Presets
import app.heyrobot.update.UpdateToken
import java.io.File
import java.io.IOException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every secret's callers: each helper's key in the :voice process (the panel's HelperLink, Test connection, a summary's
 * Claude session) and the updater's token for GitHubClient and the daily job. A read that fails in a way that may pass
 * keeps the file, its masked form and the Keystore key, Robot says it couldn't read it just now, and the next read works;
 * only a read that can never work again removes them, and Robot and Settings then say so. Keys saved by Robot 0.4.x (Ask
 * Claude) and by 0.6.0 (any helper) still decrypt from their files, and one saved now reads as they read it. The Android
 * Keystore isn't in Robolectric, so ordinary AES keys stand in for its keys ([KeystoreSecret.keys]), failing as told.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeystoreSecretReadTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private class FakeKeystore : SecretKeys {
        val held = HashMap<String, SecretKey>()
        val deleted = mutableListOf<String>()
        /** Thrown by every lookup while set: the Keystore failing. */
        var failure: Throwable? = null

        override fun get(alias: String): SecretKey? {
            failure?.let { throw it }
            return held[alias]
        }

        override fun create(alias: String): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { held[alias] = it }

        override fun delete(alias: String) {
            deleted += alias
            held.remove(alias)
        }
    }

    private val keystore = FakeKeystore()
    private val before = KeystoreSecret.keys

    private val claudeKey = "sk-ant-api03-" + "Ab1_-".repeat(18) + "xyzAA"
    private val groqKey = "gsk_" + "Ab1".repeat(16) + "AB12"
    private val homeKey = "my-ollama-key-0042"
    private val token = "github_pat_" + "A1b2_".repeat(16) + "xy"

    private val claude = HelperConfig.claude()
    private val groq = HelperConfig.of(Presets.GROQ, "groq", "am_puck")
    /** Your own server: a key only if it asks for one. */
    private val home = HelperConfig.of(Presets.HOME, "h2", "af_bella", name = "Jarvis", baseUrl = "http://192.168.1.20:11434")
    private val helpers = linkedMapOf(claude to claudeKey, groq to groqKey, home to homeKey)

    private fun mask(h: HelperConfig, k: String) = HelperKeyFormat.mask(h.presetOf, k)

    @Before fun useTheFakeKeystore() {
        KeystoreSecret.keys = keystore
    }

    @After fun restore() {
        keystore.failure = null
        for (h in helpers.keys) {
            HelperKeyFiles.removeAll(ctx, h.id)
            HelperLink.keyChanged(ctx, h.id)
        }
        UpdateToken.delete(ctx)
        KeystoreSecret.keys = before
    }

    /** Failures that may pass: the Keystore busy, restarting or locked, the file unreadable for a moment. */
    private fun transient(): List<Throwable> = listOf(
        KeyStoreException("busy"),
        ProviderException("keystore restarting"),
        UserNotAuthenticatedException(),
        IllegalStateException("keystore"),
        UnrecoverableKeyException("Failed to obtain information about key").initCause(KeyStoreException("system error")),
        IOException("EIO"),
        RuntimeException("unexpected"),
    )

    @Test fun theFilesAndAliasesAreWhere04xAnd060PutThem() {
        val dir = File(ctx.filesDir, "robot")
        // Claude: Ask Claude's files and alias since 0.3.0, kept by 0.6.0.
        assertEquals(File(dir, "claude_key.bin"), HelperKeyFiles.secret(ctx, "claude"))
        assertEquals(File(dir, "claude_key.json"), HelperKeyFiles.meta(ctx, "claude"))
        assertEquals("robot_claude_api_key", HelperKeyFiles.store("claude").alias)
        // Every other helper: its own folder and Keystore key (0.6.0).
        assertEquals(File(dir, "helpers/groq/key.bin"), HelperKeyFiles.secret(ctx, "groq"))
        assertEquals(File(dir, "helpers/groq/key.json"), HelperKeyFiles.meta(ctx, "groq"))
        assertEquals("robot_helper_groq", HelperKeyFiles.store("groq").alias)
        // The updater's token beside them, never in a helper's place.
        assertTrue(UpdateToken.save(ctx, token))
        assertTrue(File(dir, "github_token.bin").isFile && File(dir, "github_token.json").isFile)
        assertTrue(keystore.held.keys == setOf(UpdateToken.ALIAS))
    }

    /** What 0.4.5 (Ask Claude) and 0.6.0 (every helper) wrote: the key file, and the masked form with when it was saved. */
    private fun writeAsBefore(h: HelperConfig, stored: (SecretKey) -> ByteArray, masked: String) {
        val k = keystore.create(HelperKeyFiles.alias(h.id))
        val f = HelperKeyFiles.secret(ctx, h.id)
        f.parentFile!!.mkdirs()
        f.writeBytes(stored(k))
        HelperKeyFiles.meta(ctx, h.id).writeText(JSONObject().put("masked", masked).put("saved", 1_760_000_000_000L).toString())
    }

    @Test fun keysSavedBy04xAnd060StillDecryptAndOneSavedNowReadsAsTheyReadIt() {
        // Robot 0.4.5's Ask Claude key, read as the Claude helper's.
        writeAsBefore(claude, { OldKeyCode.sealAt045(it, claudeKey) }, "sk-ant-…xyzAA")
        assertEquals(SecretRead.Present(claudeKey), HelperKeys.read(ctx, "claude"))
        assertEquals("sk-ant-…xyzAA", HelperKeyFiles.masked(ctx, "claude"))
        // 0.6.0's helpers' keys: Claude's (the same file and alias) and another helper's in its folder.
        writeAsBefore(claude, { OldKeyCode.sealAt060(it, claudeKey) }, mask(claude, claudeKey))
        writeAsBefore(groq, { OldKeyCode.sealAt060(it, groqKey) }, mask(groq, groqKey))
        writeAsBefore(home, { OldKeyCode.sealAt060(it, homeKey) }, mask(home, homeKey))
        for ((h, key) in helpers) {
            assertEquals(h.id, SecretRead.Present(key), HelperKeys.read(ctx, h.id))
            assertTrue(HelperKeyFiles.saved(ctx, h.id) && !HelperKeyFiles.unreadable(ctx, h.id))
            assertEquals(mask(h, key), HelperKeyFiles.masked(ctx, h.id))
        }
        assertEquals("gsk_…AB12", HelperKeyFiles.masked(ctx, "groq"))
        assertTrue(keystore.deleted.isEmpty())
        // Saved now: the file opens with 0.4.5's code (Claude) and 0.6.0's (every helper), under the same Keystore key.
        for ((h, key) in helpers) {
            assertTrue(HelperKeys.save(ctx, h.id, key, mask(h, key)))
            val stored = HelperKeyFiles.secret(ctx, h.id).readBytes()
            val k = keystore.held.getValue(HelperKeyFiles.alias(h.id))
            assertEquals(key, OldKeyCode.openAt060(k, stored))
            if (h.id == "claude") assertEquals(key, OldKeyCode.openAt045(k, stored))
            assertEquals(mask(h, key), JSONObject(HelperKeyFiles.meta(ctx, h.id).readText()).getString("masked"))
        }
        assertEquals(setOf("robot_claude_api_key", "robot_helper_groq", "robot_helper_h2"), keystore.held.keys)
    }

    @Test fun aFailureThatMayPassKeepsEverythingAndTheNextReadWorks() {
        for ((h, key) in helpers) {
            assertTrue(HelperKeys.save(ctx, h.id, key, mask(h, key)))
            val file = HelperKeyFiles.secret(ctx, h.id).readBytes()
            for (t in transient()) {
                keystore.failure = t
                val r = HelperKeys.read(ctx, h.id)
                assertTrue("${h.id} $t: $r", r is SecretRead.Unavailable)
                // Nothing deleted: the file, its masked form, the Keystore key.
                assertTrue(t.toString(), HelperKeyFiles.saved(ctx, h.id) && HelperKeyFiles.secret(ctx, h.id).readBytes().contentEquals(file))
                assertEquals(mask(h, key), HelperKeyFiles.masked(ctx, h.id))
                assertFalse(HelperKeyFiles.unreadable(ctx, h.id))
                assertTrue(keystore.deleted.isEmpty() && keystore.held.containsKey(HelperKeyFiles.alias(h.id)))
                // Once the Keystore answers again, the same key reads.
                keystore.failure = null
                assertEquals(SecretRead.Present(key), HelperKeys.read(ctx, h.id))
            }
        }
    }

    @Test fun aFailureThatWillHappenAgainRemovesItAndSettingsSaysSo() {
        for (h in listOf(claude, groq)) {
            val alias = HelperKeyFiles.alias(h.id)
            val key = helpers.getValue(h)
            val gone = listOf<(FakeKeystore) -> Unit>(
                // Its Keystore key is gone, or was replaced by another.
                { it.held.remove(alias) },
                { it.held[alias] = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() },
                // Android says it can never be used again.
                { it.failure = KeyPermanentlyInvalidatedException() },
                { it.failure = UnrecoverableKeyException("User changed or deleted their auth credentials") },
            )
            for (breakIt in gone) {
                keystore.failure = null
                keystore.deleted.clear()
                assertTrue(HelperKeys.save(ctx, h.id, key, mask(h, key)))
                breakIt(keystore)
                assertTrue(HelperKeys.read(ctx, h.id) is SecretRead.Invalidated)
                assertFalse(HelperKeyFiles.saved(ctx, h.id))
                assertNull(HelperKeyFiles.masked(ctx, h.id))
                assertTrue(HelperKeyFiles.unreadable(ctx, h.id))
                assertEquals(listOf(alias), keystore.deleted)
                assertEquals(SecretRead.Absent, HelperKeys.read(ctx, h.id))
            }
            // A damaged file, whatever the Keystore says.
            keystore.failure = null
            assertTrue(HelperKeys.save(ctx, h.id, key, mask(h, key)))
            HelperKeyFiles.secret(ctx, h.id).writeBytes(byteArrayOf(12, 1, 2, 3))
            keystore.failure = KeyStoreException("busy")
            assertEquals(SecretRead.Invalidated("malformed"), HelperKeys.read(ctx, h.id))
            assertTrue(!HelperKeyFiles.saved(ctx, h.id) && HelperKeyFiles.unreadable(ctx, h.id))
            keystore.failure = null
        }
    }

    @Test fun thePanelKeepsAHelpersKeyAndSaysItCouldntReadItJustNow() {
        val s = RobotSettings()
        // Your server's address names one place, so the key is what the session waits on.
        assertNotNull(HelperFactsStore.load(ctx, home.id).address(home))
        for ((h, key) in helpers) {
            assertTrue(HelperKeys.save(ctx, h.id, key, mask(h, key)))
            HelperLink.keyChanged(ctx, h.id)
        }
        for (t in transient()) {
            keystore.failure = t
            for (h in helpers.keys) {
                // No session this time (none without the key you saved, even for your own server), and the question says
                // why (PanelController.noKey: the helper's keyUnavailable words); the key stays saved for the next one.
                val made = HelperLink.session(ctx, s, h, conversation = false)
                assertTrue("${h.id} $t", made is HelperLink.Made.Not && made.failure == HelperFailure.NO_KEY)
                assertTrue(HelperLink.unavailable(h.id))
                assertFalse(HelperLink.unreadable(h.id))
                assertTrue(HelperLink.keySaved(ctx, h) && HelperKeyFiles.saved(ctx, h.id))
                assertEquals(mask(h, helpers.getValue(h)), HelperKeyFiles.masked(ctx, h.id))
                assertFalse(HelperKeyFiles.unreadable(ctx, h.id))
            }
            // A summary's Claude session: none, and the summary says it couldn't read the key just now.
            assertNull(HelperLink.transcriptSession(ctx, claude))
            assertTrue(HelperLink.unavailable(claude.id))
            assertTrue(keystore.deleted.isEmpty())
        }
        // Its Keystore key gone: removed, and Robot and Settings say so.
        keystore.failure = null
        keystore.held.clear()
        for (h in listOf(claude, groq)) {
            val made = HelperLink.session(ctx, s, h, conversation = false)
            assertTrue(h.id, made is HelperLink.Made.Not && made.failure == HelperFailure.NO_KEY)
            assertFalse(HelperLink.unavailable(h.id))
            assertTrue(HelperLink.unreadable(h.id))
            assertFalse(HelperLink.keySaved(ctx, h) || HelperKeyFiles.saved(ctx, h.id))
            assertTrue(HelperKeyFiles.unreadable(ctx, h.id))
        }
        // Your own server doesn't need one: its key is removed, Settings says so, and it is asked without a key, as before.
        val made = HelperLink.session(ctx, s, home, conversation = false)
        assertTrue(made is HelperLink.Made.Ok)
        HelperLink.done((made as HelperLink.Made.Ok).session)
        assertTrue(HelperLink.unreadable(home.id) && !HelperLink.unavailable(home.id) && HelperKeyFiles.unreadable(ctx, home.id))
        assertEquals(setOf("robot_claude_api_key", "robot_helper_groq", "robot_helper_h2"), keystore.deleted.toSet())
    }

    @Test fun theUpdatersTokenIsKeptWhenItCantBeReadJustNow() {
        assertTrue(UpdateToken.save(ctx, token))
        for (t in transient()) {
            keystore.failure = t
            // GitHubClient's read for the daily check, a download, or Check now.
            assertTrue(t.toString(), UpdateToken.read(ctx) is SecretRead.Unavailable)
            assertTrue(UpdateToken.saved(ctx))
            assertEquals("github_pat_…2_xy", UpdateToken.masked(ctx))
            assertFalse(UpdateToken.unreadable(ctx))
            assertTrue(keystore.deleted.isEmpty())
        }
        keystore.failure = null
        assertEquals(SecretRead.Present(token), UpdateToken.read(ctx))
        // Its key gone: removed, and the Updates page says to add it again.
        keystore.held.remove(UpdateToken.ALIAS)
        assertTrue(UpdateToken.read(ctx) is SecretRead.Invalidated)
        assertFalse(UpdateToken.saved(ctx))
        assertTrue(UpdateToken.unreadable(ctx))
        assertEquals(listOf(UpdateToken.ALIAS), keystore.deleted)
    }
}
