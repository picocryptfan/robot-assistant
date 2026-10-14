package app.heyrobot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.0.1: where model files are trusted (core ModelMove does the copying and is tested there). ModelStore keeps models in
 * internal storage, copies a finished download in while hashing it, moves a 1.0 model in once with its mark rewritten
 * before the outside copy goes (never while it is loaded), and both engines hash a file still outside before each load.
 */
class ModelPlaceTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private fun code(path: String) = Source.strip(File(root, path).readText(), keepStrings = true, kotlin = true)
    private val store = code("app/src/main/java/app/heyrobot/sys/ModelStore.kt")
    private fun fn(src: String, name: String) = src.substringAfter("fun $name(").substringBefore("\n    }\n")

    @Test fun modelsLiveInsideAndDownloadsAreCopiedInAsTheyAreChecked() {
        assertTrue(store.contains("fun dir(ctx: Context): File = File(ctx.filesDir, \"models\")"))
        // DownloadManager can only write outside: its destination stays there, and the check copies it in.
        assertTrue(fn(store, "startDownload").contains(".setDestinationInExternalFilesDir(ctx, null, m.file)"))
        val verify = fn(store, "verify")
        assertTrue(verify, verify.contains("ModelMove.copyIn(src, File(dir(ctx), src.name), if (m.builtIn) m.sha256 else null"))
        // Your own file is imported straight in.
        assertTrue(fn(store, "import").contains("File(dir(ctx), m.file)"))
    }

    @Test fun theOutsideCopyGoesOnlyAfterTheInsideOneIsMarked() {
        val move = fn(store, "moveIn")
        val moved = move.substringAfter("is ModelMove.Result.Moved ->").substringBefore("is ModelMove.Result.Mismatch")
        assertTrue(moved, moved.indexOf("writeAtomic(verifiedMark(ctx, m)") in 0 until moved.indexOf("return tidy(ctx, m)"))
        assertTrue(moved, moved.indexOf("writeAtomic(ownRecord(ctx)") in 0 until moved.indexOf("return tidy(ctx, m)"))
        val tidy = fn(store, "tidy")
        assertTrue(tidy, tidy.contains("if (outside(ctx, checked) || loadedNow(ctx) == m) return false"))
        // A copy whose bytes aren't the checked ones: the outside file is checked again before anything loads it.
        assertTrue(move.substringAfter("is ModelMove.Result.Mismatch ->").contains("verifiedMark(ctx, m).delete()"))
    }

    @Test fun aFileStillOutsideIsHashedBeforeEachLoad() {
        for (e in listOf("GemmaEngine", "LlamaEngine")) {
            val load = code("brain/src/main/java/app/heyrobot/brain/$e.kt")
            assertTrue(e, load.contains("if (ModelMove.hashBeforeLoad(f, ctx.filesDir)) {"))
            assertTrue(e, load.contains("if (want == null || got != want) return failed(req, LoadFailure.NOT_ALLOWED, LoadCheck.CHANGED)"))
        }
        assertTrue(code("brain/src/main/java/app/heyrobot/brain/LlamaEngine.kt").contains("ModelMove.sha256(channel)"))
    }
}
