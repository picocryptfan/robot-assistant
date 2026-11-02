// SPDX-License-Identifier: GPL-3.0-only
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

    /** F57: a move finishing under a load isn't recorded as the GPU or the TPU failing, and the new plan loads. */
    @Test fun aChangedFileIsNeverTheDevicesFailure() {
        val link = code("app/src/main/java/app/heyrobot/assistant/BrainLink.kt")
        assertTrue(link.contains("if (gpu && ModelChoice.deviceFailure(failure) && !forgiven) {"))
        assertTrue(link.contains("if (m.beta && plan.backend == ModelBackend.TPU && ModelChoice.deviceFailure(failure) && !forgiven) {"))
        val load = fn(link, "loadInto")
        assertTrue(load.indexOf("if (failure == LoadFailure.FILE_CHANGED) {") in 0 until load.indexOf("if (gpu && ModelChoice.deviceFailure(failure) && !forgiven) {"))
        val engine = code("brain/src/main/java/app/heyrobot/brain/GemmaEngine.kt")
        assertTrue(engine.indexOf("return failed(req, LoadFailure.FILE_CHANGED, LoadCheck.changedText)") in 0 until engine.indexOf("LoadCheck.refuseText("))
    }

    /** F18, F56: LiteRT-LM opens a file by its path, so one outside internal storage never reaches it, and with it no cache beside it. */
    @Test fun liteRtLmNeverGetsAFileOutside() {
        val engine = code("brain/src/main/java/app/heyrobot/brain/GemmaEngine.kt")
        val refused = engine.indexOf("if (ModelMove.hashBeforeLoad(f, ctx.filesDir)) return failed(req, LoadFailure.FILE_CHANGED, LoadCheck.outsideText(req.model))")
        assertTrue(refused in 0 until engine.indexOf("Engine(config("))
        assertTrue(!engine.contains("ModelMove.sha256("))
        val plan = fn(code("app/src/main/java/app/heyrobot/sys/ModelStore.kt"), "plan")
        assertTrue(plan, plan.contains("if (outside(ctx, File(path))) return ModelAvailability.NEEDS_MOVE to null"))
        assertTrue(plan, plan.contains("r.format != ModelFormat.GGUF && outside(ctx, f)"))
    }

    /** F58: a move leaves no cache for the space check to take for the new copy's, and clears it before the mark names that copy. */
    @Test fun aMoveClearsTheCacheOfTheCopyItLeft() {
        val store = code("app/src/main/java/app/heyrobot/sys/ModelStore.kt")
        val moved = fn(store, "moveIn").substringAfter("is ModelMove.Result.Moved ->")
        assertTrue(moved, moved.indexOf("clearStaleCache(ctx, m)") in 0 until moved.indexOf("writeAtomic(verifiedMark(ctx, m)"))
        assertTrue(fn(store, "verify").contains("is ModelMove.Result.Moved -> { f = r.file; sha = r.sha256; clearStaleCache(ctx, m) }"))
        val engine = code("brain/src/main/java/app/heyrobot/brain/GemmaEngine.kt")
        assertTrue(engine.contains("ModelFiles.cachedBytes(req.backend, cacheDir.walkTopDown()"))
    }

    /** It can't load until it moves, so the app doesn't wait for its next opening once there's room. */
    @Test fun aModelWaitingForRoomMovesOnceThereIs() {
        val main = code("app/src/main/java/app/heyrobot/ui/MainActivity.kt")
        assertTrue(main.contains("it.outside && it.moveNeed == 0L && it.model !in moveTried"))
    }

    /** llama.cpp gets the very descriptor that was hashed. */
    @Test fun aGgufFileStillOutsideIsHashedThroughItsDescriptor() {
        val load = code("brain/src/main/java/app/heyrobot/brain/LlamaEngine.kt")
        assertTrue(load.contains("if (ModelMove.hashBeforeLoad(f, ctx.filesDir)) {"))
        assertTrue(load.contains("ModelMove.sha256(channel)"))
    }

    // F52: a copy cut short holds space the next move frees first, so it counts as free.
    @Test fun aLeftoverPartDoesNotHoldAMoveBack() {
        assertTrue(fn(store, "moveNeed").contains("ModelMove.freeForMove(freeBytes(ctx), File(dir(ctx), checked.name))"))
    }
}
