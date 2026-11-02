// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import app.heyrobot.assistant.connect.ConnKeyRead
import app.heyrobot.assistant.connect.ConnKeys
import app.heyrobot.core.SecretRead
import app.heyrobot.core.connect.ConnUrl
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.termux.SecretMask
import app.heyrobot.core.web.SearchService
import app.heyrobot.sys.ConnFiles
import app.heyrobot.sys.ConnKeyFiles
import app.heyrobot.sys.HelperKeyFiles
import app.heyrobot.sys.SettingsStore
import app.heyrobot.sys.WebKeyFiles

// VOICE PROCESS (1.0, left over from the 1.0 audit). Robot's own secrets by exact value, for TermuxPolicy only: a Termux
// command or script argument holding one is refused with its reason ("This holds one of Robot's own keys…"), not only one
// shaped like a key. Each helper's API key and each web search service's key: the keys the :voice process already reads
// for its helpers and web search (their KeystoreSecret stores), and (Skills connections, C3) every saved part of each
// connection's key: its token, ntfy's topic, a header's value, and an address that is itself the key, whole and its path
// (where its secret is). The updater's GitHub token is never decrypted here (only the main process's GitHubClient reads
// it): a GitHub token is refused by its shape (SecretMask). Read just before a Termux call is prepared or run, held in
// memory for that call only, never logged, shown, saved or sent anywhere.

object TermuxSecrets {
    /** Every saved key Robot can read now (one it can't read just now is skipped: it is still refused by its shape). */
    fun own(ctx: Context): List<String> {
        val out = ArrayList<String>()
        fun add(r: SecretRead) {
            val v = (r as? SecretRead.Present)?.value ?: return
            if (v.length >= SecretMask.MIN_OWN) out += v
        }
        val helpers = (listOf(HelperConfig.CLAUDE_ID) + try { SettingsStore.load(ctx).helpers.map { it.id } } catch (_: Exception) { emptyList() }).distinct()
        for (id in helpers) if (HelperKeyFiles.saved(ctx, id)) add(HelperKeys.read(ctx, id))
        for (service in SearchService.entries) if (WebKeyFiles.saved(ctx, service)) add(WebKeys.read(ctx, service))
        val connections = try { ConnFiles.store(ctx).connections() } catch (_: Exception) { emptyList() }
        for (c in connections) {
            if (c.secrets.isEmpty() || !ConnKeyFiles.saved(ctx, c.id)) continue
            val s = (ConnKeys.read(ctx, c.id) as? ConnKeyRead.Present)?.secrets ?: continue
            val values = s.parts.mapNotNull { s[it] } + listOfNotNull(s.address?.let { ConnUrl.parse(it, whole = true)?.path })
            for (v in values) if (v.length >= SecretMask.MIN_OWN) out += v
        }
        return out
    }
}
