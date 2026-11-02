// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.spoken

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The spoken tests' commands from the computer (tools/spoken/README.md, "The protocol"). The manifest requires DUMP of the sender,
 * and a sender that names itself as anything but the adb shell is refused here. A test build only: verify_apk.sh looks for [MARKER] in a release's dex.
 */
class SpokenReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val seq = intent.getIntExtra("seq", -1)
        val harness = SpokenHarness.start(ctx.applicationContext)
        if (!SpokenAssumptions.senderAllowed(sentFromUid)) return harness.results.refuse(seq, "$MARKER: not the adb shell")
        harness.commands.handle(seq, intent.getStringExtra("cmd"), intent.getStringExtra("arg"), intent.getStringExtra("value"))
    }

    companion object {
        const val MARKER = "robot-spoken-harness"
    }
}
