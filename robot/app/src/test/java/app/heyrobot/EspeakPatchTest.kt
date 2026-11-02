package app.heyrobot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The patched espeak-ng stays what ships: tools/build_sherpa.sh applies the patch, verify_apk.sh refuses any other
 * library, the source bundle carries both, THIRD_PARTY.md names the same commit and hash. tools/espeak_bounds_check.sh
 * is what shows each bound stops its crash; it needs a compiler and the network, so it isn't a unit test.
 */
class EspeakPatchTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private fun text(path: String) = File(root, path).readText()
    private val patch by lazy { text("third_party/patches/espeak-ng-ed530aa1-robot.patch") }
    private val build by lazy { text("tools/build_sherpa.sh") }
    private val thirdParty by lazy { text("THIRD_PARTY.md") }
    private fun value(name: String) = Regex("(?m)^$name=(\\S+)").find(build)?.groupValues?.get(1).orEmpty()

    @Test fun thePatchHasEveryBound() {
        val added = patch.lines().filter { it.startsWith("+") }.joinToString("\n")
        for (bound in listOf(
            "j < N_PUNCTLIST-1", // the "B" list, the audit's FA15
            "ix < n_buf-7", "ix < n_buf-5", // the "B" command's writes into the clause buffer
            "ix < n_buf-200", // announcements
            "sizeof(word_buf) - (size_t)(wbuf - word_buf)", // dotted abbreviations
            "wtab_remaining", // the word count passed down
            "plist_start", // the start of the phoneme list
            "plist, phoneme_list, &phdata", // and the one place upstream's fix would change the IPA text
        )) assertTrue(bound, bound in added)
    }

    @Test fun theCommitsAndTheHashAgree() {
        val commit = value("ESPEAK_COMMIT")
        assertEquals(40, commit.length)
        assertTrue(commit in thirdParty)
        assertTrue(commit in patch.lineSequence().first())
        val built = value("BUILT_SHA")
        assertTrue(built, Regex("[0-9a-f]{64}").matches(built))
        assertTrue(built in thirdParty)
    }

    @Test fun verifyApkRefusesAnyOtherLibrary() {
        val verify = text("tools/verify_apk.sh")
        assertTrue("BUILT_SHA" in verify && "build_sherpa.sh" in verify)
        assertTrue("lib/arm64-v8a/libsherpa-onnx-jni.so" in verify)
    }

    @Test fun theSourceBundleCarriesThePatchAndClaimsTheBuiltLibrary() {
        val bundle = text("tools/make_source_bundle.sh")
        for (f in listOf("tools/build_sherpa.sh", "tools/espeak_bounds_check.sh", "third_party/patches")) assertTrue(f, f in bundle)
        assertFalse("3c6492ea" in bundle)
    }
}
