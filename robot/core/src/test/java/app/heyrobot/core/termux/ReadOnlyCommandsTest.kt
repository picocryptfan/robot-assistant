package app.heyrobot.core.termux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The read-only allowlist (B7.3): every allowed form passes; every forbidden flag, redirect, expansion, private path and pipe target fails. */
class ReadOnlyCommandsTest {
    private fun ok(s: String) = CommandAnalyzer.analyze(s).readOnly

    @Test fun allowedForms() {
        for (s in listOf(
            "pwd", "whoami", "id", "date", "date +%F", "uptime", "uname -a", "nproc", "free -h", "df -h", "arch", "hostname", "hostname -I",
            "ls", "ls -la ~", "ls ~/notes", "ls *.txt", "ls \$HOME/notes", "ls \"\$HOME\"/x", "du -sh ~", "stat notes.md", "file x.bin", "wc -l notes.md",
            "head -n 5 notes.md", "tail -n 20 ~/logs/sync.log", "cat notes.md", "cat",
            "find ~ -name '*.md'", "find ~/notes -type f -mtime -1",
            "grep -n todo notes.md", "grep -rn TODO ~/projects", "grep -e x -e y notes.md",
            "ps", "ps -eo pid,pcpu,comm --sort=-pcpu | head -5", "ps aux | grep ssh", "ps -ef", "top -b -n 1", "top -bn1", "ls ~/storage/shared",
            "which git", "type ls", "command -v git",
            "pkg list-installed", "dpkg -l", "apt list --installed",
            "termux-battery-status", "termux-audio-info", "termux-camera-info", "termux-tts-engines",
            "echo hello | wc -c", "printf 'a\\nb\\n' | sort | uniq", "cat notes.md | grep x | sort | uniq -c | head", "ls | cut -c1-10", "du -sh ~ | tail -n 1",
        )) assertTrue("'$s' should be allowed", ok(s))
    }

    @Test fun forbiddenForms() {
        for (s in listOf(
            // 1.0 audit (native-02): never git, not even status or log: a repository's own config can name programs git runs.
            "git status", "git log -n 5", "git log -5 --oneline", "git log --max-count=3", "git diff --stat", "git branch", "git branch -a", "git show --stat",
            "git -C ~/notes status", "git -C ~/notes log -n 3 --oneline", "git --no-pager status",
            // Never on the list.
            "env", "printenv", "set", "history", "export", "declare -p", "echo hi", "printf x", "cat \$PREFIX/etc/motd",
            // Lists, background, redirections, expansions, here-docs, comments.
            "ls; pwd", "ls && pwd", "ls || pwd", "ls &", "ls > out", "ls 2>/dev/null", "cat < notes.md", "echo \$(ls)", "echo `ls` | wc", "ls \$PATH",
            "ls \${HOME:-x}", "cat <<EOF\nx\nEOF", "ls # comment", "FOO=1 ls", "(ls)", "{ ls; }", "ls \$'x'", "cat ~/x\"\$(id)\"",
            // Paths outside ~ and \$PREFIX, private files, hidden files' content, globs for content.
            "ls /", "cat /etc/hosts", "cat ../../../../etc/hosts", "ls /sdcard", "cat ~/.ssh/config", "ls ~/.ssh", "cat ~/.bash_history", "cat ~/.bashrc",
            "head ~/.gitconfig", "cat *.md", "cat ~/notes/*", "ls ~/.*", "cat id_rsa", "cat server.pem", "cat .env", "du -sh /data",
            // Flags that write, follow, run or reach out.
            "tail -f log", "tail -F log", "tail --follow=name log", "date -s 10:00", "date --set=x", "hostname evil", "file -C -m x", "du --files0-from=f",
            "find ~ -delete", "find ~ -exec cat {} \\;", "find ~ -fprint out", "find / -name x", "grep -r token ~", "grep -r x", "grep -r x /",
            "grep -f /etc/passwd x", "top", "top -b", "sort -o out f", "ls | sort --compress-program=sh", "ls | uniq in out", "ls | tee out",
            "git log", "git diff", "git show", "git branch new", "git branch -D x", "git remote -v", "git -c core.pager=sh log -n 1", "git log -n 5 --output=x",
            "git diff --stat --ext-diff", "git -C / status", "git -C ~/.ssh status", "git pull", "git status && git pull",
            "pkg install x", "pkg list-all", "apt list", "dpkg -i x", "termux-battery-status x", "termux-location", "termux-sms-list",
            "echo hi | sh", "echo hi | xargs rm", "ls | head | sh", "ls | cat", "ls | less",
            // Environments, files named through git objects, your shared storage.
            "ps e", "ps auxe", "ps -o pid,environ", "git show --stat HEAD:.env", "git log -n 1 HEAD:notes.md", "cat ~/storage/shared/Documents/a.txt",
            "head ~/storage/downloads/x",
            // Wrappers and paths to programs.
            "sudo ls", "nice ls", "command ls", "/system/bin/ls", "./ls", "busybox ls",
            // Hidden characters.
            "ls​", "lѕ",
        )) assertFalse("'${CommandAnalyzer.visible(s)}' shouldn't be allowed", ok(s))
    }
}
