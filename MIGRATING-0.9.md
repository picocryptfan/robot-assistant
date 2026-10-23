# Moving from 0.9 to 1.0

Robot 1.0 has a new app ID, `ai.robot_app` (0.9 and the test builds before it were `app.heyrobot`), so Android installs
it as a new app beside 0.9, not as an update, and nothing you have in 0.9 moves by itself. 0.9 (and 1.0.0-rc1 and rc2)
check the old private repository for updates, so they won't offer you 1.0: you install it by hand from the release page.
Both apps are called Robot; long-press an icon › App info shows which version it is.

1. Install 1.0 from the [release page](https://github.com/picocryptfan/robot-assistant/releases), beside 0.9,
   and check its certificate as in [Install](README.md#install): it is the same key.
2. People. In 0.9: Settings › People, notes and calendar › People › More options (⋮) › Export (.vcf), and save
   `Robot People.vcf`. In 1.0: the same page › More options › Import contacts file (.vcf), pick that file, then Import.
   It holds the people you confirmed, with their names, nicknames, numbers, emails and XMPP addresses. People Robot
   learned from notifications that you haven't confirmed, and which number or address Robot uses first for someone, don't
   come along.
3. Memory. In 0.9: Settings › Memory › Export › Choose a file, and save `robot-memory.json` (it isn't encrypted).
   In 1.0, unlocked: Settings › Memory › Import, pick that file, and check the preview: what it adds, what is already
   there, and what it skips (a password, a PIN, a code or a card number is never imported: add those yourself with
   Settings › Memory › Add something, where they stay private). Tick "These are my own memories, from my own export"
   when it is your own file, then tap Import. Without the tick, Robot treats the imported items as something it read
   (an action they lead to asks first) until you open one in Settings › Memory and tap This is mine. Then let Robot
   delete the file (it offers to), or delete it yourself.
4. Models. 1.0 downloads its models again, from its first screen (or Settings › Model, Settings › Voice and Settings ›
   Conversation; before 1.1, Settings › Model was the Model tab). Robot's model can come from a file you already have
   instead: Settings › Model › the model › Import a file
   copies it and checks its SHA-256 as a download is. The copy 0.9 downloaded is in 0.9's own folder, which the file
   picker can't open; with adb you can copy it out first (`adb shell ls /sdcard/Android/data/app.heyrobot/files/`, then
   `adb shell cp /sdcard/Android/data/app.heyrobot/files/<file> /sdcard/Download/`), import it, and delete the copy in
   Download afterwards. The voices, speech recognition and the translation engine always download again.
5. Make 1.0 your digital assistant in Settings › Apps › Default apps › Digital assistant app, and pick Robot. Both are
   listed as Robot: in 1.0, Settings › Assistant setup says "Android lists Robot as your assistant" once you picked the
   right one; if it doesn't, pick the other.
6. Uninstall 0.9: long-press its icon › App info (it says version 0.9.0) › Uninstall. This deletes everything 0.9
   kept.
7. Do the ADB fixes again in 1.0 (Settings › Advanced › ADB fixes), with a computer or with Shizuku. 0.9's were for
   its app ID, so both show "Not done", and their commands now name `ai.robot_app`. Until then Android may stop using
   Robot as the assistant after a restart.

What doesn't move: your settings, History, notes, transcripts, the keys of your helpers and web search, routines,
headsets and cars, the Termux set-up, the updater's access token, your voiceprint, and Android's permissions and special
accesses (Robot asks again as you turn things on). Before step 6, share the notes you want to keep (a note › Share) and
save the transcripts you want (in 0.9: a transcript › the gear button › Save as Markdown (.md) or Save as text). If Obtainium follows
Robot for you, it follows 0.9's app ID: remove that entry and add the repository again.
