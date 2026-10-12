package app.heyrobot.sys

import android.content.Context
import app.heyrobot.core.SecretRead
import app.heyrobot.update.UpdateToken

// Main and voice processes (1.0). The updater's GitHub token by value, for one use outside the updater: the :voice
// process's TermuxSecrets compares a Termux command with it, so a command holding it is refused (TermuxPolicy). It is
// never sent, shown or logged from here; only GitHubClient puts it in a request (ProcessIsolationTest).

object OwnKeys {
    /** The updater's token, or none saved (one that can't be read just now: Unavailable, kept). Background thread. */
    fun updateToken(ctx: Context): SecretRead = UpdateToken.read(ctx)
}
