package app.heyrobot.assistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import app.heyrobot.core.Addresses
import app.heyrobot.core.CheogramPick
import app.heyrobot.core.CheogramRoute
import app.heyrobot.core.ContactMatcher
import app.heyrobot.core.People
import app.heyrobot.core.PeopleEdit
import app.heyrobot.core.PickOutcome
import app.heyrobot.core.RLog
import app.heyrobot.core.TextCommand
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.sys.CheogramIntents
import app.heyrobot.sys.HandOffApps
import app.heyrobot.sys.PeopleFiles
import app.heyrobot.ui.theme.RobotTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// VOICE PROCESS. "Pick in Cheogram" (docs/people.md "Ways in" 1), for a text to someone no contact fits. This invisible
// activity opens Cheogram's contact list for a result: its "Create shortcut" screen (ACTION_CREATE_SHORTCUT on
// ShortcutActivity), whose answer Robot only reads, so nothing goes on the home screen and its icon is never looked at.
// It then offers to remember the person under the name you said ("Remember as Mom?"), and opens their chat in the right
// account with the message in the box. Nothing is sent: you press Send in Cheogram. When the list doesn't open or
// nobody is picked, Cheogram's share screen is offered instead ("Choose in Cheogram"). The voice log gets the outcome's
// code, never who.

class CheogramPickActivity : ComponentActivity() {
    private sealed class Step {
        /** Cheogram's list is open (or about to be). */
        data object Waiting : Step()
        /** "Remember as Mom?" before the chat opens. */
        data class Remember(val pick: CheogramPick) : Step()
        /** Nobody usable came back: Cheogram's share screen instead? [title] says why. */
        data class Nobody(val title: String) : Step()
    }

    private var step by mutableStateOf<Step>(Step.Waiting)

    private val pkg: String? get() = intent.getStringExtra(EXTRA_PACKAGE)?.takeIf { HandOffApps.installed(this, it) } ?: HandOffApps.cheogram(this)
    private val spoken: String get() = intent.getStringExtra(EXTRA_NAME).orEmpty().trim()
    private val message: String get() = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()
    /** Settings › People › "Remember who I pick", as it was when the card was tapped. */
    private val rememberOn: Boolean get() = intent.getBooleanExtra(EXTRA_REMEMBER, true)

    private val list = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> picked(r.resultCode, r.data) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RobotTheme { Dialogs() } }
        step = restore(savedInstanceState) ?: Step.Waiting
        if (savedInstanceState == null) openList()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        when (val s = step) {
            is Step.Remember -> outState.putStringArray(STATE_PICK, arrayOf(s.pick.name, s.pick.jid, s.pick.account))
            is Step.Nobody -> outState.putString(STATE_NOBODY, s.title)
            Step.Waiting -> {}
        }
    }

    private fun restore(b: Bundle?): Step? {
        b ?: return null
        b.getStringArray(STATE_PICK)?.let { a -> if (a.size == 3 && a[0] != null && a[1] != null) return Step.Remember(CheogramPick(a[0], a[1], a[2])) }
        return b.getString(STATE_NOBODY)?.let { Step.Nobody(it) }
    }

    private fun openList() {
        val p = pkg ?: return nobody(PickOutcome.NO_LIST, "Cheogram isn't installed")
        try {
            list.launch(CheogramIntents.pick(p))
        } catch (_: ActivityNotFoundException) {
            nobody(PickOutcome.NO_LIST, "Cheogram's contact list didn't open")
        } catch (_: SecurityException) {
            nobody(PickOutcome.NO_LIST, "Cheogram's contact list didn't open")
        }
    }

    private fun nobody(outcome: PickOutcome, title: String) {
        VoiceLog.add(VoiceEvent.CHEOGRAM_PICK, outcome)
        step = Step.Nobody(title)
    }

    /** Cheogram's answer: someone, a cancel, or something Robot can't use. */
    private fun picked(code: Int, data: Intent?) {
        val pick = if (code == RESULT_OK) CheogramIntents.picked(data) else null
        when {
            pick == null && code == RESULT_OK -> nobody(PickOutcome.NO_ADDRESS, "Cheogram didn't say who was picked")
            pick == null -> nobody(PickOutcome.CANCELLED, "Nobody was picked")
            !Addresses.isJid(pick.jid) -> nobody(PickOutcome.NO_ADDRESS, "Robot can't open a chat with that address")
            !rememberOn -> {
                VoiceLog.add(VoiceEvent.CHEOGRAM_PICK, PickOutcome.PICKED, if (pick.account != null) 1 else 0)
                openChat(pick)
            }
            else -> lifecycleScope.launch {
                // Already known by the name you said: nothing to ask (a missing account is added, quietly).
                val known = withContext(Dispatchers.IO) {
                    PeopleFiles.store(applicationContext).list().any { p ->
                        p.confirmed && p.xmpp.any { it.jid.equals(pick.jid, ignoreCase = true) } &&
                            (People.sameName(p.name, People.aliasName(spoken)) || p.aliases.any { ContactMatcher.sameAlias(it, spoken) })
                    }
                }
                if (known) {
                    VoiceLog.add(VoiceEvent.CHEOGRAM_PICK, PickOutcome.PICKED, if (pick.account != null) 1 else 0)
                    remember(pick)
                } else {
                    step = Step.Remember(pick)
                }
            }
        }
    }

    /** Saves the pick in People ([PeopleEdit.rememberPick]), then opens the chat. */
    private fun remember(pick: CheogramPick) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                PeopleFiles.store(applicationContext).update { PeopleEdit.rememberPick(it, spoken, pick, System.currentTimeMillis()).first }
                    ?: RLog.w("people_save")
            }
            openChat(pick)
        }
    }

    /** The chat in Cheogram with the message in its box: in the account it was picked from, else by its link. */
    private fun openChat(pick: CheogramPick) {
        val p = pkg
        val routes = if (p == null) emptyList() else listOfNotNull(
            pick.account?.let { CheogramRoute.ACCOUNT to CheogramIntents.chat(p, pick.jid, it, message) },
            CheogramRoute.LINK to CheogramIntents.link(p, pick.jid, message),
        )
        val i = routes.indexOfFirst { start(it.second) }
        if (i >= 0) VoiceLog.add(VoiceEvent.CHEOGRAM_HANDOFF, routes[i].first, if (i > 0) 1 else 0)
        else Toast.makeText(this, "Cheogram didn't open.", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun shareInstead() {
        val p = pkg
        if (p != null && start(CheogramIntents.share(p, message))) VoiceLog.add(VoiceEvent.CHEOGRAM_HANDOFF, CheogramRoute.SHARE, 1)
        else Toast.makeText(this, "Cheogram didn't open.", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun start(i: Intent): Boolean {
        if (i.resolveActivity(packageManager) == null) return false
        return try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
    }

    @Composable
    private fun Dialogs() {
        // Back closes it, and nothing opens; a tap outside does nothing (the message would be lost by accident).
        val props = DialogProperties(dismissOnClickOutside = false)
        when (val s = step) {
            Step.Waiting -> {}
            is Step.Remember -> {
                // "my mom" is kept (and shown) as "mom".
                val asked = TextCommand.display(People.aliasName(spoken))
                val same = spoken.isEmpty() || People.sameName(People.aliasName(spoken), s.pick.name)
                AlertDialog(
                    onDismissRequest = { finish() },
                    properties = props,
                    title = {
                        Text(buildAnnotatedString {
                            append(if (same) "Remember " else "Remember as ")
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(if (same) s.pick.name else asked) }
                            append("?")
                        })
                    },
                    text = {
                        Text(buildAnnotatedString {
                            if (!same) {
                                append("Cheogram calls them ")
                                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.pick.name) }
                                append(". ")
                            }
                            append("Robot keeps this in People, on your phone only, so next time it opens their chat directly.")
                        })
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            VoiceLog.add(VoiceEvent.CHEOGRAM_PICK, PickOutcome.PICKED, if (s.pick.account != null) 1 else 0, 1)
                            remember(s.pick)
                        }) { Text("Remember") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            VoiceLog.add(VoiceEvent.CHEOGRAM_PICK, PickOutcome.PICKED, if (s.pick.account != null) 1 else 0)
                            openChat(s.pick)
                        }) { Text("Not now") }
                    },
                )
            }
            is Step.Nobody -> AlertDialog(
                onDismissRequest = { finish() },
                properties = props,
                title = { Text(s.title) },
                text = { Text("Choose who to text in Cheogram's share screen instead? The message goes with it, and nothing is sent until you press Send.") },
                confirmButton = { TextButton(onClick = ::shareInstead) { Text("Choose in Cheogram") } },
                dismissButton = { TextButton(onClick = { finish() }) { Text("Cancel") } },
            )
        }
    }

    companion object {
        private const val EXTRA_PACKAGE = "cheogram"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_MESSAGE = "message"
        private const val EXTRA_REMEMBER = "remember"
        private const val STATE_PICK = "pick"
        private const val STATE_NOBODY = "nobody"

        /**
         * Opens Cheogram's list ([pkg]) to pick who the text to [name] (as you said it) goes to, with [message].
         * [remember]: offer to remember them ("Remember who I pick").
         */
        fun intent(ctx: Context, pkg: String, name: String, message: String, remember: Boolean): Intent = Intent(ctx, CheogramPickActivity::class.java)
            .putExtra(EXTRA_PACKAGE, pkg).putExtra(EXTRA_NAME, name).putExtra(EXTRA_MESSAGE, message).putExtra(EXTRA_REMEMBER, remember)
    }
}
