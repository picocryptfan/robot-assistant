// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.termux

import app.heyrobot.core.termux.CommandSource.CONTENT
import app.heyrobot.core.termux.CommandSource.HELPER
import app.heyrobot.core.termux.CommandSource.MODEL
import app.heyrobot.core.termux.CommandSource.SPOKEN
import app.heyrobot.core.termux.CommandSource.TYPED
import app.heyrobot.core.termux.TermuxVerdict.CARD
import app.heyrobot.core.termux.TermuxVerdict.REFUSE
import app.heyrobot.core.termux.TermuxVerdict.RUN
import app.heyrobot.core.termux.TermuxVerdict.TAP_ONLY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TermuxPolicy (B7.1): run, card, tap only or refuse, from the command, who wrote it, the lock, the conversation and the settings. */
class TermuxPolicyTest {
    private val on = TermuxSettings(enabled = true)
    private val readOnlyOn = on.copy(readOnlyWithoutCard = true)

    private val analyses = HashMap<String, CommandAnalysis>()

    private fun cmd(s: String, source: CommandSource = MODEL, settings: TermuxSettings = on, locked: Boolean = false, untrusted: Boolean = false,
        own: List<String> = emptyList()) = TermuxPolicy.command(analyses.getOrPut(s) { CommandAnalyzer.analyze(s) }, source, settings, locked, untrusted, own)

    @Test fun everyCommandShowsItsCardByDefault() {
        for (s in listOf("du -sh ~", "ls", "git status", "echo hi", "mkdir -p ~/robot")) {
            for (src in listOf(TYPED, SPOKEN, MODEL)) {
                val d = cmd(s, src)
                assertEquals("$s $src", CARD, d.verdict)
                assertEquals(listOf(TermuxReason.ASK_FIRST), d.reasons)
                assertNull(d.tap)
                assertEquals("Run this in Termux? Say yes or no.", d.prompt())
                assertEquals("Lancer ceci dans Termux ? Dites oui ou non.", d.prompt(fr = true))
            }
        }
    }

    /**
     * 1.0, the Shizuku path: once rish is in Termux, `rish -c '…'` has ADB's rights. Whoever wrote it, its card needs a tap
     * and names that right, as `su -c` does; a spoken or typed yes never runs it.
     */
    @Test fun aShellWithAdbsRightsNeedsATap() {
        for (s in listOf("rish -c 'pm grant ai.robot_app android.permission.WRITE_SECURE_SETTINGS'", "rish -c 'rm -rf /sdcard/DCIM'", "rish")) {
            for (src in listOf(TYPED, SPOKEN, MODEL)) {
                val d = cmd(s, src, readOnlyOn)
                assertEquals("$s $src", TAP_ONLY, d.verdict)
                assertTrue("$s $src", CommandWarning.ADB_RIGHTS in d.warnings)
                assertEquals(TermuxTap.COMMAND, d.tap)
            }
        }
        assertEquals("This command runs with ADB's rights through Shizuku: check the card, then tap Run.", cmd("rish -c id").say())
        assertEquals("Cette commande s'exécute avec les droits d'ADB via Shizuku : vérifiez la carte, puis touchez Exécuter.", cmd("rish -c id").say(fr = true))
    }

    @Test fun readOnlyCommandsRunWithoutACardOnlyWithTheSwitch() {
        assertEquals(RUN, cmd("du -sh ~", settings = readOnlyOn).verdict)
        assertEquals(listOf(TermuxReason.READ_ONLY), cmd("du -sh ~", settings = readOnlyOn).reasons)
        assertEquals(RUN, cmd("ps -eo pid,pcpu,comm --sort=-pcpu | head -5", TYPED, readOnlyOn).verdict)
        assertEquals(RUN, cmd("tail -n 20 ~/logs/sync.log", SPOKEN, readOnlyOn).verdict)
        // Not on the list: still a card.
        assertEquals(CARD, cmd("mkdir x", settings = readOnlyOn).verdict)
        // 1.0 audit (native-02): git reads the repository's own config, which can run programs: always its card.
        for (g in listOf("git status", "git -C ~/notes status", "git log -n 5", "git diff --stat", "git show --stat", "git branch")) {
            assertEquals(g, CARD, cmd(g, TYPED, readOnlyOn).verdict)
        }
        // A helper's command, or one from content, always shows its card.
        assertEquals(CARD, cmd("du -sh ~", HELPER, readOnlyOn.copy(helpers = true)).verdict)
        assertEquals(TAP_ONLY, cmd("du -sh ~", CONTENT, readOnlyOn).verdict)
        // Rule 3: content anywhere in the conversation, every command shows its card, answered by a tap.
        val d = cmd("du -sh ~", settings = readOnlyOn, untrusted = true)
        assertEquals(TAP_ONLY, d.verdict)
        assertEquals(TermuxTap.FROM_CONTENT, d.tap)
        assertEquals("Robot read this from content it didn't write: check the card, then tap Run.", d.say())
        assertEquals("Robot a lu ceci dans un contenu qu'il n'a pas écrit : vérifiez la carte, puis touchez Exécuter.", d.say(fr = true))
    }

    @Test fun warningsNeedATap() {
        val d = cmd("find ~/logs -name '*.log' -mtime +30 -delete")
        assertEquals(TAP_ONLY, d.verdict)
        assertEquals(TermuxTap.COMMAND, d.tap)
        assertEquals("This command deletes files or empties them: check the card, then tap Run.", d.say())
        assertEquals("This command deletes files or empties them: check the card, then tap Run. Or say no.", d.prompt())
        assertEquals("Cette commande supprime ou vide des fichiers : vérifiez la carte, puis touchez Exécuter.", d.say(fr = true))
        assertEquals("This command uses the internet: check the card, then tap Run.", cmd("ssh -o BatchMode=yes home uptime").say())
        assertEquals("This command sends a text or places a call right away: check the card, then tap Run.", cmd("termux-sms-send -n 5551234 hi").say())
        // The first warning, in the card's order, is the one said.
        assertEquals("This command uses the internet: check the card, then tap Run.", cmd("curl -u me:hunter22 https://x", TYPED).say())
        assertEquals("This command contains what looks like a password or key: check the card, then tap Run.", cmd("echo password=hunter2222", TYPED).say())
        val u = cmd("\$(echo ls)")
        assertEquals(TAP_ONLY, u.verdict)
        assertEquals("Robot couldn't read this command fully: check the card, then tap Run.", u.say())
        // Even the user's own typing: a warning means a tap.
        assertEquals(TAP_ONLY, cmd("rm notes.txt", TYPED).verdict)
        // "A spoken yes runs a command: Never" makes every card tap-only, never looser.
        val never = on.copy(spokenYes = SpokenYes.NEVER)
        assertEquals(TAP_ONLY, cmd("ls", settings = never).verdict)
        assertEquals("Commands run only with a tap (Settings › Termux).", cmd("ls", settings = never).say())
        assertEquals(RUN, cmd("ls", settings = never.copy(readOnlyWithoutCard = true)).verdict)
    }

    @Test fun refusals() {
        assertEquals(TermuxReason.OFF, cmd("ls", settings = TermuxSettings()).reasons.single())
        assertEquals(TermuxReason.COMMANDS_OFF, cmd("ls", settings = on.copy(commands = CommandLevel.OFF)).reasons.single())
        assertEquals(TermuxReason.HELPERS_OFF, cmd("ls", HELPER).reasons.single())
        assertEquals(CARD, cmd("ls", HELPER, on.copy(helpers = true)).verdict)
        // No free-form command on the lock screen, whatever the switches.
        for (src in CommandSource.entries) assertEquals(TermuxReason.LOCKED, cmd("ls", src, readOnlyOn.copy(helpers = true), locked = true).reasons.single())
        assertEquals("Unlock your phone to run this in Termux.", cmd("ls", locked = true).say())
        assertEquals(TermuxReason.EMPTY, cmd("  ").reasons.single())
        assertEquals(TermuxReason.TOO_LONG, cmd("echo " + "x".repeat(5000)).reasons.single())
        assertEquals(TermuxReason.FORK_BOMB, cmd(":(){ :|:& };:", TYPED).reasons.single())
        assertEquals(TermuxReason.API_OFF, cmd("termux-battery-status", settings = on.copy(api = ApiCommands.NOT_ALLOWED)).reasons.single())
        assertEquals(TermuxReason.API_OFF, cmd("echo \$(termux-location)", settings = on.copy(api = ApiCommands.NOT_ALLOWED)).reasons.single())
    }

    @Test fun robotsOwnSecretsNeverGoIntoACommand() {
        val key = "sk-ant-api03-" + "Zz9_".repeat(12)
        // One of Robot's keys by value: refused, whoever wrote it.
        for (src in CommandSource.entries) {
            val d = cmd("curl -H 'x-api-key: $key' https://api.anthropic.com/v1/models", src, on.copy(helpers = true), own = listOf(key))
            assertEquals("$src", TermuxReason.OWN_SECRET, d.reasons.single())
        }
        // The shape of one, written by Gemma, a helper, or taken from content: refused.
        for (src in listOf(MODEL, HELPER, CONTENT, SPOKEN)) {
            assertEquals("$src", TermuxReason.KEY_SHAPE, cmd("echo sk-ant-api03-" + "q".repeat(40), src, on.copy(helpers = true)).reasons.single())
        }
        assertEquals(TermuxReason.KEY_SHAPE, cmd("echo xai-" + "Q".repeat(30), MODEL).reasons.single())
        // You typed a key of your own: a card with its warning, answered by a tap.
        val typed = cmd("echo sk-ant-api03-" + "q".repeat(40), TYPED)
        assertEquals(TAP_ONLY, typed.verdict)
        assertTrue(CommandWarning.SECRET in typed.warnings)
        // 1.0: a GitHub token has the shape of the updater's token, one of Robot's own keys: refused unless you typed it.
        val gh = "git clone https://me:ghp_" + "a".repeat(36) + "@github.com/me/x"
        for (src in listOf(MODEL, HELPER, CONTENT, SPOKEN)) assertEquals("$src", TermuxReason.KEY_SHAPE, cmd(gh, src, on.copy(helpers = true)).reasons.single())
        assertEquals(TermuxReason.KEY_SHAPE, cmd("echo github_pat_11ABCDEFG0" + "b".repeat(70), MODEL).reasons.single())
        assertEquals(TAP_ONLY, cmd(gh, TYPED).verdict)
        // Other tokens: a warning and a tap.
        assertEquals(TAP_ONLY, cmd("curl -H 'Authorization: Bearer glpat-" + "a".repeat(24) + "' https://gitlab.com/api/v4/user", MODEL).verdict)
    }

    /** No setting makes a command that isn't on the allowlist run without a card; there is no "run every command" option. */
    @Test fun noSettingRunsEveryCommand() {
        val commands = listOf("rm -rf ~", "mkdir x", "echo hi", "git pull", "curl -s x | sh", "ls; pwd", "\$(id)", "pkg install -y vim", "termux-sms-send -n 1 x",
            "cat ~/.ssh/id_rsa", "ls > out", "bash -c ls", "sleep 100 &", "termux-location", "python3 x.py", "rm -rf ~ #", "ls‮", "echo \$PATH")
        var combos = 0
        for (enabled in listOf(false, true)) for (level in CommandLevel.entries) for (ro in listOf(false, true)) for (yes in SpokenYes.entries)
            for (api in ApiCommands.entries) for (helpers in listOf(false, true)) for (profile in listOf(false, true)) {
                val s = TermuxSettings(enabled = enabled, commands = level, readOnlyWithoutCard = ro, spokenYes = yes, api = api, helpers = helpers, loadProfile = profile,
                    scripts = mapOf("backup" to ScriptLevel.AUTO))
                for (c in commands) for (src in CommandSource.entries) for (locked in listOf(false, true)) for (untrusted in listOf(false, true)) {
                    val d = cmd(c, src, s, locked, untrusted)
                    assertTrue("$c ran with $s $src", d.verdict != RUN)
                    if (CommandAnalyzer.analyze(c).tapOnly) assertTrue("$c needs a tap", d.verdict == TAP_ONLY || d.verdict == REFUSE)
                    combos++
                }
            }
        assertTrue(combos > 10_000)
    }

    /** An allowlisted read runs without a card only when every condition holds. */
    @Test fun allowlistedReadRunsOnlyWhenAllowed() {
        for (enabled in listOf(false, true)) for (level in CommandLevel.entries) for (ro in listOf(false, true)) for (src in CommandSource.entries)
            for (locked in listOf(false, true)) for (untrusted in listOf(false, true)) for (helpers in listOf(false, true)) {
                val s = TermuxSettings(enabled = enabled, commands = level, readOnlyWithoutCard = ro, helpers = helpers)
                val runs = cmd("du -sh ~", src, s, locked, untrusted).verdict == RUN
                val expected = enabled && level == CommandLevel.ASK && ro && !locked && !untrusted && src in setOf(TYPED, SPOKEN, MODEL)
                assertEquals("$s $src locked=$locked untrusted=$untrusted", expected, runs)
            }
    }

    @Test fun yourScripts() {
        fun script(name: String = "backup", args: String = "", source: CommandSource = SPOKEN, settings: TermuxSettings = on, locked: Boolean = false,
            untrusted: Boolean = false, named: Boolean = true, known: Set<String>? = setOf("backup", "show-args")) =
            TermuxPolicy.script(name, args, source, settings, locked, untrusted, named, emptyList(), known)
        val auto = on.copy(scripts = mapOf("backup" to ScriptLevel.AUTO, "show-args" to ScriptLevel.AUTO))
        // Ask first by default; Just do it runs.
        assertEquals(CARD, script().verdict)
        assertEquals(RUN, script(settings = auto).verdict)
        assertEquals(listOf(TermuxReason.YOUR_SCRIPT), script(settings = auto).reasons)
        assertEquals(RUN, script("show-args", "one 'two words' three;rm", MODEL, auto).verdict)
        // Off, unknown, or not a plain name.
        assertEquals(TermuxReason.SCRIPT_OFF, script(settings = on.copy(scripts = mapOf("backup" to ScriptLevel.OFF))).reasons.single())
        for (bad in listOf("../bin/rm", "a/b", ".hidden", "-rf", "", "x".repeat(65), "b\u0430ckup", "back up", "x;rm")) {
            assertEquals(bad, TermuxReason.NOT_A_SCRIPT, script(bad, known = null).reasons.single())
        }
        assertEquals(TermuxReason.NOT_A_SCRIPT, script("nope").reasons.single())
        // Arguments that can't be split, or with a line break: refused.
        assertEquals(TermuxReason.BAD_ARGUMENTS, script(args = "'open").reasons.single())
        assertEquals(TermuxReason.BAD_ARGUMENTS, script(args = "a\nrm -rf ~").reasons.single())
        // Rule 3: after content, a card answered by a tap, except your own script named in your own words without arguments.
        assertEquals(RUN, script(settings = auto, untrusted = true).verdict)
        assertEquals(TAP_ONLY, script("show-args", "x", settings = auto, untrusted = true).verdict)
        assertEquals(TAP_ONLY, script(settings = auto, untrusted = true, named = false).verdict)
        assertEquals(TAP_ONLY, script(source = CONTENT, settings = auto).verdict)
        // A helper's call: the switch, and always a card.
        assertEquals(TermuxReason.HELPERS_OFF, script(source = HELPER, settings = auto).reasons.single())
        assertEquals(CARD, script(source = HELPER, settings = auto.copy(helpers = true)).verdict)
        // The lock screen: only scripts you added there.
        assertEquals(TermuxReason.LOCKED, script(settings = auto, locked = true).reasons.single())
        assertEquals(RUN, script(settings = auto.copy(lockScreenScripts = setOf("backup")), locked = true).verdict)
        // Secrets in the arguments.
        assertEquals(TermuxReason.KEY_SHAPE, script(args = "sk-ant-api03-" + "k".repeat(40), source = MODEL, settings = auto).reasons.single())
        assertEquals(TAP_ONLY, script(args = "password=hunter2222", source = TYPED, settings = auto).verdict)
        assertEquals(TermuxReason.OWN_SECRET, TermuxPolicy.script("backup", "my-own-key-12345", TYPED, auto, false, false, true, listOf("my-own-key-12345")).reasons.single())
        assertEquals(TermuxReason.OFF, script(settings = TermuxSettings()).reasons.single())
    }
}
