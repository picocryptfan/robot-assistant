// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.core.files.PendingDeletes

// MAIN PROCESS and :voice, one holder each (1.4 FX2, files-1.4-review.md K1; core PendingDeletes): the deletes you tapped,
// waiting 10 seconds for their Undo. The main app's serves the Files tab and Settings › Files' clean-up suggestions; :voice's
// the panel's delete card. Each deletes through its own process's vault, as before; what waits goes through at screen-off
// (RobotFiles' hook), when its screen goes or Robot goes to the background, and when the panel closes.

object FileDeletes {
    /** This process's deletes waiting for their Undo. */
    val here: PendingDeletes by lazy { PendingDeletes() }
}
