// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import android.os.Build
import app.heyrobot.core.BrainModel
import app.heyrobot.core.LoadPlan
import app.heyrobot.core.ModelBackend
import app.heyrobot.core.RLog
import app.heyrobot.core.SpeedKey
import app.heyrobot.core.SpeedLog
import app.heyrobot.core.SpeedSample
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File

// Voice and main processes (1.2, design Step 5). How fast the model really is on this phone (core SpeedLog): the voice
// process adds each panel reply's numbers (BrainKeeper, its only writer), Settings › Model reads them. Numbers only, in
// filesDir/robot; no backup category names the file, and nothing here sends anything.

object SpeedFiles {
    private fun file(ctx: Context) = File(Paths.dir(ctx), SpeedLog.FILE)

    /** The log as it is, or null. Any process. */
    fun read(ctx: Context): String? = readOrNull(file(ctx))

    /** The key for [model] on [backend] on this phone now: its file's hash (your own file's from its check) and Android's build. */
    fun key(ctx: Context, model: BrainModel, backend: ModelBackend): SpeedKey? =
        SpeedKey.of(model, backend, if (model == BrainModel.OWN) ModelStore.own(ctx)?.sha256 else model.sha256, Build.FINGERPRINT)

    /** [VOICE] A panel reply on [plan] measured [s]. */
    @Synchronized fun add(ctx: Context, plan: LoadPlan, s: SpeedSample) {
        try {
            val k = key(ctx, plan.model, plan.backend) ?: return
            writeAtomic(file(ctx), SpeedLog.add(read(ctx), k, s))
        } catch (t: Throwable) {
            RLog.w("speeds_save", t)
        }
    }
}
