# Changelog

What changed in each version of Robot, newest first. Each version's full notes, with what was checked on a phone, are in
[`robot/README.md`](robot/README.md) ("What's new in …" and the feature sections). Versions before 1.0 were test
builds.

## 1.3.0 (2026-11-08)

Skills 1.3. Nothing new runs by itself and no new permission is asked.

- MCP servers as connections (Settings › Skills › Connections › MCP server). Home Assistant's, or one you run in Termux
  on this phone. The address must be on your home network, a Tailscale address, or on the phone with a token; Robot
  refuses a public address, even https. Its token goes only over https with a certificate Android trusts, at home too;
  only a server on this phone may use plain http, and it is marked Not encrypted (on this phone). Each tool it lists starts off, and you turn it on with your own label, a level
  (Ask first, or Just do it for a tool that only reads and that the server says only reads) and marks for money,
  security and telling people. A tool the server changes turns itself off with what changed. A call's arguments are
  checked before they're sent, and one longer than its card can show is refused. The server's descriptions are shown to
  you and never reach a model.
- Plans. "Text Sam I'm late, then set a timer for 10 minutes and tell me the weather", or "plan this: …": a card lists
  the steps; reads run at once, writes wait. Do the rest answers the plan's cards; Step by step shows each. A message,
  money or security step always gets its own card. A step that fails ends the plan and says what didn't run. Settings ›
  Skills › Plans: all at once or step by step, and most steps (3 to 8, 5 by default). "Make that a plan?" is offered
  after a question with too many actions at once.
- Drafts. "Make a skill that turns off the lights when I say good night": Robot's model writes a draft card; a step it
  can't check is left out and named. Saving is a tap, at Ask first, with no trigger. A draft made after a web page, a
  message or your screen says so, can't be Just do it and gets no trigger until you edit it in Settings.
- Let a helper plan or draft. A card shows exactly what would go, the helper and its cost; nothing goes until you tap
  Send. In car mode it waits until you've stopped, and gives up after 30 minutes.
- Sharing (Settings › Skills › Sharing). Export a skill as a signed `.robotskill` file with this phone's key; import one
  from a file or share it to Robot. An import shows who signed it, its steps and the connections it needs, and always
  starts off. A changed file is refused. Keys you trust only show their name.
- Your routines are skills too (Settings › Skills shows "Starts by itself"). They look the same in Settings ›
  Routines and fire as before; going back to 1.2 keeps them. A routine you changed in 1.2 changes its own skill when you
  come back; one you deleted there turns its skill off.
- Back up and restore. Your skills (your routines' too), your plan settings, the keys you trust and your MCP tools'
  choices go in with the Skills tick; the last two come back only from a backup you mark as your own, and a server's
  tool choices only with that server. Connections go in without their tokens, and helper keys and tokens only if you
  tick Keys and private codes. Your signing key never leaves the phone.
- French for all of it.
- The monthly backup reminder no longer goes away when you start a phone lock for someone else, and a pairing's "a file
  didn't arrive" or "a folder is no longer shared" notice no longer replaces their answer or a list notice: each had a
  number another notice used.

## 1.2.7 (2026-11-08)

- A question typed right after an answer with a list card (say, "Yes, Shopping has milk." and the list, or « Oui, le lait
  est sur la liste. ») now shows, with its reply, in English and French. Before, the panel stayed on the card and both
  landed below it, out of sight.
- A file shared to Robot from another app is no longer left out as "stopped sending" when it's Robot that is slow to
  start the copy (a slow or busy phone, or your first file). The wait for the sending app now starts when Robot first
  asks it for bytes.

## 1.2.6 (2026-11-07)

- Today, a home page that shows your day instead of Getting started: what's on now and next, your lists, reminders,
  loose ends in Robot Files and requests from other Robots, and a note you can add to. You turn it on from a one-time
  offer or in Settings › Display, and order its middle parts by dragging or by voice ("put my calendar first").
- "How's my day" says it. The morning brief, with Today on, follows the same order.
- A Calendar tab replaces History on the tab bar and shows the next 14 days, read only from the phone's calendars. The
  clock icon at the top of Today opens History, as "show my history" does. "Show my history" and "show my calendar" now
  open the page and the panel closes behind it. Before, they answered and opened nothing.
- Private events live only in Robot, sealed on the phone under a key that needs it unlocked, and never go into your
  calendar app or Android's backups. Add an event asks where it goes. Start over keeps an unreadable file aside.
- Today and the Calendar tab read the calendar once on opening and again on a change, not on a timer; a minute tick only
  updates "now" and when to leave.
- With Today off, "show my calendar" goes to the model with the calendar tools, as in 1.2. By voice alone, with Today
  on, it says your day and opens nothing.
- Say "put my calendar first" or « mets ma note en premier » and Today follows within a second, without leaving the tab.
- Today's page and private events come back from a backup only when you mark it as your own.
- A weather line on Today, off until you turn it on in Settings › Display. It asks Open-Meteo for the forecast only when
  you tap Get today's forecast or Updated: never when Today opens or on a timer. The forecast is kept on the phone, outside
  backups, and deleted when you turn the line or Today off.
- A Robot today widget for the home screen, and a quiet summary on the lock screen (counts and times, off until you turn it
  on in Settings › Display). A private event shows as "a private event" on the widget, and is never counted or timed on the
  lock screen. Both go away with Today off. Read my day after my morning alarm is the morning brief, started by an alarm.
- History keeps "a private event" in place of a private title a reply said. The brief never says your lists or note
  while the phone is locked.
- News on Today, off until you add a feed in Settings › Display › News on Today (RSS or Atom, up to five; Robot suggests
  none). By default Robot fetches them once a day from 5:30 in the morning, on Wi-Fi and not in Battery Saver; a morning
  it can't fetch is caught up once, when it can (back on Wi-Fi, or when you open Today), never before 5:30. You can choose
  when you open Today (at most once an hour) or only when you ask, and directly or only through Tor (with Robot to Robot
  on; nothing goes out without it). Neither the daily fetch nor an open uses mobile data unless you turn on "Use mobile
  data for news"; tapping the line's time fetches them then, on any network. Feeds are read in a separate process with no network access, and the headlines are
  kept 7 days, outside backups. The morning brief says them (on a locked phone only at Everything), "read me the news"
  says them once the phone is unlocked, and each fetch is listed in What went online, under News.
- A feed on your own server: plain http only on your own network, and a feed address carrying a token kept encrypted,
  shown masked, left out of logs, History and backups, and sent only over https with a valid certificate, except to a
  server on the phone itself, which the setting marks "Not encrypted (on this phone)". With Tor chosen, a feed on your own network goes directly, and the setting says so.
- A confirm card is in the language you spoke: say « annule mon alarme » with English menus and its question and buttons
  are in French. Before, they were in the menus' language.
- A typed question on a phone on silent or in Do Not Disturb is answered on screen only, with a notice once; vibrate still
  speaks, and so do Speak replies on Always, headphones, the car, asking by voice and your own tap on the speaker button.
- Routines and the morning brief stay quiet on silent or in Do Not Disturb and show their words in a notification
  instead, unless the sound goes to headphones or the car. A brief started by turning off your alarm still speaks.
- When the daily check finds a new version, Robot says so at once, on Wi-Fi or mobile data, instead of waiting up to two
  days for Wi-Fi; once the download on Wi-Fi is checked, the same notification says it's ready to install. Settings ›
  About › Updates says when Robot can't post notifications and opens the switch.
- Messages: a work profile's copy of a messaging app you allowed is never read, counted or announced, and Robot learns
  no names from a work profile's Cheogram.
- Settings › About › Support Robot lists the crypto addresses only; the Ko-fi card and its browser button are gone.

## 1.2.5 (2026-11-07)

- After Robot answers, a reply above a tall card (a list of three items, say) shows from its first line; before, the
  panel showed the card's foot and left the reply out of sight above it.
- "Clear ticked items from shopping" clears them: ticked, checked, done and crossed-off items all clear, with or without
  the word "list", in English and French (« efface les éléments cochés de l'épicerie »).

## 1.2.4 (2026-11-06)

- A Car mode button on the home screen, and a Car mode shortcut when you long-press Robot's icon, open car mode with no
  car connected, for a ride in a rental or a car without Bluetooth. It stays on until you end it (or after four hours);
  no car leaving ends it.
- "Delete the note about the gate code" (« supprime la note sur le code de la barrière ») deletes it: Robot shows a card
  naming the note and says its name aloud (several that fit: it asks which), deletes it on your yes or tap, and offers
  Undo that brings it back whole while Robot keeps running. Before, Robot only searched your notes.
- "Cancel my alarm" turns the alarm off through your clock app, where it takes the request: Robot asks first about your
  next alarm (the only one Android shows it), or goes straight to the time you said ("cancel my 6:30 am alarm"), then
  checks it went. A time with no a.m. or p.m. that only fits your next alarm at the other half of the day ("cancel my 7
  alarm" when the next is at 7 PM) gets the card first. When it didn't, or Robot can't tell, your clock's alarms open with one line saying why. Before, Robot
  only offered to open the clock app.
- "What alarms do I have", "when is my next alarm" and "is my alarm set" (« quelles alarmes j'ai ») get Robot's own answer: your
  next alarm, the only one Android shows it, and that your clock app has the others. Before, Robot only offered to open the clock app.
- Car mode started from its shortcut doesn't start again when you reopen Robot from your recent apps.
- The Notes list, a note and its editor are kept out of screenshots and screen recordings, as your other private pages are.
- Robot to Robot: each push post starts a fresh secure connection (none is resumed), a push server's
  address is checked after it's looked up, so push servers on your own network are now refused, and a pairing that's
  Anonymous never falls back to a plain post. Tor is noticed when it ends on its own and started again, at most three
  times an hour, then it waits; it starts at once when Robot to Robot is needed again.
- A clip you pick for the Transcription check is refused over 25 MB and decoded in memory, with the same limits as a
  shared voice message, up to its first 60 seconds.
- Settings › About › Updates: "Allow Robot to install updates" shows Done as soon as you come back from Android's page,
  also in split screen, and What is checked says Android installs without asking you when Install updates without
  asking is on. Before, it always said Android's window asks you to confirm.
- "Where's my car" (« où est ma voiture ») answers from what you asked Robot to remember, in Robot's own words.
  "Where did I park" with nothing saved says how to save it; "where's my lease" still goes to Robot Files. Before, the
  model said it had no access to your car even with the fact saved.
- "And tomorrow?" after a question about the weather gets tomorrow's forecast straight away. Before, it waited on a card
  for your yes.
- Right after Robot has talked about a list, "take the milk off it", "milk is done" and "is milk on it" (« enlève le lait
  de celle-là », « le lait, c'est fait », « le lait est-tu dessus ») mean that list, so you don't have to name it again.
  When the milk isn't on that list, Robot asks which list instead of guessing. Once you've asked about anything else,
  "it" no longer means a list.
- Undo on a card for a list, a memory or a deleted note stays on the card when it can't work yet (the phone is locked,
  or the change couldn't be saved): the card says why, and you can tap Undo again once you unlock. Before, the button
  went away and the change stayed.

## 1.2.3 (2026-11-05)

- A fact you asked Robot to remember is found when you ask about it in other words ("what do I like to eat most" for
  "my favourite food is pad thai"), while memory holds 8 items or fewer. Before, the two shared no word and the model
  answered without the note.
- "How many items are on my grocery list" (« combien d'items il y a sur ma liste d'épicerie ») counts the Shopping
  list. Before, the model tried to open an app called Grocery List.
- "Read me my last note" (« lis-moi ma dernière note ») reads the newest note. Before, Robot searched for the word "last".
- A note you dictate ("take a note that …", "jot down …", « prends en note que … ») is saved in your words, every time.
  Before, the model sometimes reworded it, or answered without saving it.
- "Remember I parked the car on level 3" is kept like "remember my …". Before, it went to the model and wasn't saved.
- An answer to a question about you no longer says it saved something when it saved nothing, on screen or aloud.
- General knowledge is answered again ("how many people live in Halifax", in English and French; figures may be dated).
  Before, the model said it had no real-time information. News, prices and scores are still declined.
- "How much time is left on my timer" is answered for a timer Robot set. Robot can't see timers set in your clock app.
  "Cancel the timer" opens the clock's timers and says why: Android lets no app stop a running timer.
- Robot's record of the timers it set is deleted 12 hours after the last one ends, and with History › Delete all,
  Memory › Delete all, Start over and a restore's Replace of History. Before, the last timers stayed on the phone for good.

## 1.2.2 (2026-11-04)

- "Open car mode", « ouvre le mode voiture », "carmode" and other ways of asking now start car mode, as "car mode"
  and "start car mode" always did. Before, Robot sent them to the model, which answered that it has no car mode. The
  matching ways to end it ("close car mode", « ferme le mode voiture » and the like) work too. With car mode already
  on, Robot says so.
- A fact you asked Robot to remember is found when you ask about it, even while memory holds only one or a few items.
  Before, with so few items the question missed it and the model answered without it. In English and French.

## 1.2.1 (2026-11-03)

- Car mode with no car or headset connected: "hey robot" and Talk listen on the phone. Before, "hey robot" opened the
  panel without listening and Talk closed it at once.
- The car screen's media card shows the album art as its background, under a dark layer that keeps the words readable.
- In split screen, a long song name shows whole, on as many lines as fit and smaller if it must; the artist gives way.
- Settings opened from car mode: Back returns to car mode instead of Robot's home.
- "Cancel the timer" asks your clock app to stop a ringing timer, then opens its timers to cancel a running one, which
  Android doesn't let an app do. Locked, Robot says to unlock and open the clock; in car mode, a headset session or
  after "hey robot", it opens nothing and says so.
- A card's result shows once, on the card, not again in the reply above it. While a card waits for your answer,
  TalkBack reads its line.
- The Robot card's header has a button that transcribes a conversation, as saying "transcribe this conversation"
  does. Camera, Photos and File keep each icon and word on one line, side by side when they fit.
- A notification says when Robot stops being your assistant while the phone is on, about a minute later, and opens the
  setting. It already came after a restart or update.
- Robot to Robot's Start over also resets all its settings, your name and your own Tor bridge lines included.

## 1.2.0 (2026-11-02)

The full phone pass for this release isn't finished; 1.2.1 follows with its fixes. The release notes' Known limits say what's still open.

- If your model never moved into Robot's private storage after an update from 1.0 because there wasn't room, it won't load until there's room for a second copy of it (its size plus 0.5 GB). Free that space and open Robot: it moves on its own, and Settings › Model says how much it needs. A model file you imported yourself still loads, checked in full each time.
- Wait before answering (Settings › Voice) starts at Long (1.6 s) on a new install. If you already use Robot, yours stays as it was.
- The car screen, rebuilt: half for your map, the media player or messages, a quarter for media buttons, directions or
  your latest message, and one row of big buttons: Talk, a button you choose (your music app, Maps,
  navigate home or any app), Apps and Settings. The home directions button is gone unless you pick it. Your map runs beside Robot in Android's
  split screen, one tap away; you drag the bar to resize. Choose it all in Settings › Car mode › Car screen. The screen now
  follows your light or dark theme. Message panels only show: to reply, tap Talk, and the usual read-back and "send" apply.
- Lists: shopping and to-do lists you keep by voice. "Add oat milk to the shopping list", "what's on the packing list",
  "is oat milk on my list" ("milk" finds oat milk, never milk thistle; a yes names what it found; with no list named it
  looks at every list), "take eggs off" (it ticks; nothing said aloud deletes an item), "put eggs back on",
  "make a camping list", "clear the done items", "undo that", in English and Québec French (« ajoute du lait d'avoine à
  ma liste d'épicerie », « enlève les œufs »). Robot's own code hears these and answers; no model runs for them. Shopping
  and To-do are there from the start, and a new list is made the first time you name it. Lists sit beside Notes in the
  Notes tab: tick boxes, Done, edit, Move to top, Move to another list, Delete with Undo, Rename, Share as text, and
  Make a list from this note. After a read, the panel shows a card with tick boxes.
- Lists are encrypted on the phone the way memory is, under an Android Keystore key that needs the phone unlocked, and
  never go online. While the phone is locked you can still add by voice ("Add to lists while locked", on by default):
  the words wait in a small encrypted journal until you unlock, and each item is marked "Added while the phone was
  locked". Hearing and ticking a list on the lock screen is a switch, off by default. During a phone lock for someone
  else, Robot changes and reads no list. Settings › Lists holds both switches, the storage, Delete all lists, and Start
  over or Restore from a backup if the key is gone.
- A home-screen widget, Robot list: one list per widget, its open items with tick boxes, and how many are done. It only
  ticks. Its items go when the screen goes off and come back when you unlock. Your home-screen app draws it, so it sees
  that list's open items. Which list a widget shows stays on this phone: after Robot's data is cleared, the widget says
  a backup doesn't keep it and asks you to pick again. During a phone lock the widget and its list picker say "Robot is
  locked", and the picker is under the lock's cover, as Robot Files' share window now is too.
- History keeps what you say to Robot, but not what Robot reads back from a list: "Robot read Shopping: 5 items", or
  "Robot answered from your lists". Something that looks like a PIN or a card number isn't added, and that question is
  kept as private. When a conversation read a list, later actions by Robot's model ask first, and what Robot read back
  goes to a helper only on a card you tap.
- Robot's model can add to a list ("add what I need for pancakes"): with a card you tap, unless every item is your own
  words. It never reads a list.
- Backups carry your lists (a Lists row, on by default). Someone else's lists arrive beside yours, marked "From a
  backup that isn't yours", and never turn their looser lock-screen switches on here. Robot 1.1.1 and older skip Lists
  in a backup and restore the rest. Backups now name each part in words, so a later Robot that can't restore a part says
  which one.
- Attach a PDF, a picture or a text file to a question in Robot's panel. It's read on the phone and kept only for
  that conversation, unless you file it.
- One app first's notice, the cover and Settings › Phone lock now count your minutes in the app as they pass. The cover
  says time counts only while that app is open.
- The panel's buttons follow the language you chose for the menus, even when your phone is in another language. When
  you change the menus' language, the panel follows the next time it opens, without a restart. With French menus, a
  text's card title and its « Texto à Sam. » line are in French too.
- Lists in Québec French also hear « as-tu du lait sur ma liste » and « on a-tu du beurre sur ma liste ». Asking about
  an item you ticked off now starts with a yes: "Yes, Shopping has eggs, ticked off." (« Oui, c'est coché … »).
- After you cancel a text's card, the panel no longer shows "Texting Sam." above it.
- Robot no longer drops a question it showed you while you spoke. When the last pass over your words found none, Robot
  said "Sorry, I didn't catch that."; now the words you watched appear are the question, and the voice log says so
  ("Used the words shown: the last pass found none").
- Robot offers the helper after more kinds of "I don't have that" answers ("I don't have specific, up-to-the-minute
  documents for…", « je n'ai pas de données récentes… »), and when its answer only sends you to an official website
  "for the most accurate information". An answer that answers and then names a website for details is still an
  answer: it no longer brings the helper by itself.
- Car mode shows what's playing and brings back your directions. The car screen's music tile names the app that's playing and
  its song (with notification access; without it, your music app and its song), shown there only, with Previous, Play or Pause and Next; the maps button reads "Back to directions" once Robot opened a route
  this drive. Pick your maps app in Settings › Actions › Maps: every route Robot opens goes to it, beside the car
  screen. In split screen, the car screen keeps the music tile and Talk first.

- Skills connections: a skill's step can now reach your own server, hand a step to an app you pick, or run one of your
  Termux scripts with typed values. Set each one up once in Settings › Skills › Connections: Home Assistant (turn
  things on and off, read a state, run a script), ntfy (send a notification), a webhook (one request you declare), a
  routing server you run (OSRM, Valhalla or GraphHopper) for "about 25 min" travel times, and app hand-offs that open
  an app you picked with the words you set. Typed Termux scripts declare up to 3 values with `# param:` lines.
- Settings › Skills gains Add a skill, and a skill's page gains Add a step, a page for each step (a value, a question,
  words with a value inside, or an earlier step's answer; Only if…; If it doesn't work), and computed Effects, Risk and
  Test lines. Test is a dry run: nothing is sent.
- Every connection that holds a key, a token, an ntfy topic or a secret address is https only, with a certificate
  Android trusts (Tailscale HTTPS, your own domain or Home Assistant Cloud). Plain http works only on your own network,
  with nothing secret. Keys are pasted in a window of their own, kept encrypted on the phone, and go only to their own
  connection.
- The first time a skill reaches a new host, a card asks: Allow for this connection, Just this time, or No (a tap). A
  step that locks, unlocks or opens something, deletes, can spend money, runs code Robot can't see (scripts,
  automations, scenes, groups) or carries words Robot's model wrote always takes a tap. Connection steps never run on
  a locked phone or from a routine. A step that changed after its card showed doesn't run: "This step changed after
  its card showed, so Robot didn't run it. Ask again."
- Backups gain Connections (on by default) and connection keys (only with Keys and private codes). Robot 1.1.1 and
  older skip both and restore the rest.
- Robot now reads whether the phone is on Wi-Fi, Ethernet or a VPN, only to say "Robot can't reach your Home Assistant
  off your home network." instead of trying a home connection on mobile data.
- A spoken yes or no to a card now waits as long as Settings › Voice › Wait before answering says (1.6 s on Long),
  about a second slower than before, so a pause in your answer doesn't send it.
- "Play Text Me" and other song titles with "text" in them no longer search your music app unless you say music
  ("play a song called Text Me").
- Robot's listening sheet hides other apps' floating windows while it is up. This adds the HIDE_OVERLAY_WINDOWS
  permission, which Android grants at install with no prompt.
- Robot to Robot: Settings › Robot to Robot › Push app lets you choose which push app delivers to Robot, or None; only your
  tap saves it. A shared folder's limits (500 files, 1 GiB) now count everything the other person ever sent into it, so
  a folder in use for a long time can stop taking their files until one of you leaves and invites again. A list someone
  shares with you shows what they removed or changed under "Removed or changed by someone else", with Put back. A
  pairing made by photographing a code now says "Verified with a photo of their code".
- Clock app (under the alarm options on the morning brief's page and on a routine's page): pick which Clock "When I turn
  off my alarm" goes by, the phone's default first. GrapheneOS Clock works as before. For another Clock Robot goes by the
  alarm's sound stopping, without using the microphone, and only for that Clock's own next alarm; a timer or another
  app's alarm starts nothing, and an alarm that only vibrates isn't seen. A Clock that can't be followed says so and offers
  "Start at a set time instead". This is unproven on Google Clock. Your alarm, timer and show requests go to the Clock you
  chose when it takes them.
- Battery Saver now pauses the all-day wake word and says so: a quiet notification while it lasts (none of your words in it),
  a row on the wake word page, and a note in Listening. The corner swipe still opens Robot.
- A conversation nobody added to for 30 minutes now ends and is saved, paused or not, so a forgotten one can't keep the
  phone awake until the battery runs out.
- A model that Android took memory from between answers no longer counts toward three problems an hour, and a reclaim
  during a load moves the Fast model off the GPU only the second time in an hour. A failed save in Settings or History now
  says so, once until a save works.
- The voice log keeps up to 20 of each failure and 100 in all after the 300 newest events have moved on; numbers only.
- Call set to Off still opens the phone's dialer with an emergency number in it (you tap Call); every other number is still
  refused. During a lock with no session, 112 and 911 and the emergency numbers of where the phone is (Android's list, your
  region, the device's) get the emergency dialer. When a lock starts, Robot's panel closes and clears its conversation; a
  call starting takes the cover off the call's screen; and Robot's routine notices and a skill's Continue notice stay out of
  the shade during a lock for someone else.
- Robot Files: a refund counts as money back. A total written as money taken off ("-45.00", "(45.00)") or a line that is
  only "Refund 45.00" comes off your spending ("That's after 1 refund.", and when the sum is below zero, "so below zero means more back than
  spent"), the tax
  pack and the file's page; the total's edit box starts at "-45.00" and you decide by taking the minus off or typing it.
  Files you filed before keep their check.
- A restore that stops part way now says so, finished parts read restored and the rest "not restored, nothing of it
  changed". Keys with no connection or helper here, files that could not be read when the backup was made, a refused write
  in Phone lock, Everyday or Robot to Robot, and pairings that come back paused are each said in the result. A second restore
  keeps pairings still waiting, a backup without reminders leaves yours, and notes from someone else's backup no longer
  replace yours. The "kept safer" preview counts only what the restore will change.
- Robot to Robot: when a change to a shared list doesn't fit on your phone (full lists), you get a notification and a row
  at the top of the list's page with Dismiss. The other phone isn't told: its copy and yours differ until it makes the
  change again. A folder row now shows the same count as the folder ("2 of 500 files"). A restart while the phone is locked
  no longer gives one person another 100 items. A Start over that is cut part way opens as damaged, not as a Robot with part
  of its pairings. A photo or video you sent from Files no longer goes out under the camera's dated name.
- Every pop-up menu in Robot refuses taps while another app's window is over it, as dialogs do. Robot's vibrate
  permission, which it never used, is gone. In the car, words cut at the 30-second limit are held only where the box to
  answer stays, and the voice log names that end.
- Phone lock's cover comes back about a second after you go Back from Settings › Phone lock into Robot's other
  settings, and when the 5 minutes of changes run out with Robot open; in 1.1.1 it waited for another app or screen.
  A cover that stays up keeps its minutes left, its app buttons, its end and who the lock is for current (in 1.1.0 and
  1.1.1 it kept what it first showed). The Robot Files icon and the backup window are covered too. If Robot can't read
  Phone lock's files, it assumes a lock may be on and lets the cover decide.
- Every word longer than 40 characters is cut before it reaches the voice engine, which closes a known crash in
  espeak-ng's text reader (CVE-2023-49992). The engine itself is now built with a patch that adds espeak-ng's own fix for CVE-2023-49990 to 49994.

## 1.1.1 (2026-10-23)

- During a phone lock, asking Robot to call an emergency number (typed, or said outside car mode) opens the phone's own
  emergency dialer with the number filled in; you tap Call. In 1.1.0 it answered "Unlock to do this". The same on
  Android's lock screen. When a call you typed opens a dialer, Robot's panel steps aside.
- Phone lock's edit session ends when you leave Robot by Home, Recents, the power button or another app, and at
  screen-off; a code pad left unfinished closes. During a lock nothing is put over Android's lock screen.

## 1.1.0 (2026-10-23)

Everything built since 1.0.1, in one release: Robot Files, the groundwork for other Android phones, Québec French,
Phone lock, Back up and restore, Skills, a few settings, fixes, web search working on phones again, and automatic
update checks that now run. It updates 1.0.1 in place.

- Robot Files, a private filing cabinet on the phone. The Files tab takes the Model tab's place (the model's page is now Settings › Model, at the top of Settings), and a second icon, Robot Files, opens it. Add a file from any app's share sheet (Robot Files), scan paper with the camera (several pages into one PDF, corners set by hand), say "file this" about a photo or the screen you showed Robot, or tap Save to Robot Files on a transcript. Robot reads each file on the phone (the text layer first, then pictures with its own model) and files it in a drawer: Receipts, Bills, Documents, Medical, Taxes, Trips and events, Photos and media, Conversations, or one of yours. A file it isn't sure about waits in Inbox with a question; a total it can't check against the subtotal and taxes is marked "Check the total"; a file that could install or run something stays in Inbox, unread, with a warning. After each filing: Undo and Move. Moves and spoken rules ("Esso receipts go under Car") become filing rules kept in Memory. Each file is encrypted with its own key, sealed by an Android Keystore key that works only while the phone is unlocked; nothing in Robot Files shows or is said on the lock screen. By voice: find a file, ask about one, spending by category, merchant or month (added up in code), bills due, returns, warranties and expiry dates, subscriptions, in English and French. Reminders from files go in your brief or, after a card you tap, your calendar. A tax pack (one year's tax slips and tax files) and an expense report (files tagged Business) export copies and a table of their details to a folder you pick; those copies aren't encrypted. Share and Open with another app hand over a one-use copy, without a photo's location while Strip location from photos I send is on (the default).
- Settings › Files: Robot Files on or off, a filing level for each kind (File it on its own, Ask me first, Off), Read text in images, Strip location from photos I send, reminders from files, the morning brief, clean-up suggestions (deleting always asks, with 10 seconds to undo), the backup reminder, and Delete them all.
- Travel mode (Settings › Files): the drawers you choose are hidden everywhere in Robot (Files, the panel, History, Memory's rules, the brief, backups) until you confirm with the phone's PIN, pattern or password, never a fingerprint or face. Shown drawers hide again after 10 minutes, when you leave Robot or when the screen goes off. A privacy screen, not encryption. Robot asks for USE_BIOMETRIC, one of its two new permissions, for that confirm screen.
- Backups carry your files: a Files row on Back up now's list (on by default, with Videos under it). Each file goes in inside the backup's encryption and is sealed again under the new phone's key on restore. Files from a backup that isn't yours arrive in Inbox without tags, people or reminders, and that backup can't turn Robot Files or reading text in images back on. While travel mode is on, backups and restores leave Robot Files out, and History too while it holds answers from your files.
- Other Android phones (none tested yet): Settings › Model, the problem report and the feedback report show the chip as Android names it, and name Snapdragon, Exynos and MediaTek chips. Robot checks the GPU (or a beta TPU build) with three short questions on the phone before your first question, once per Android build, and moves to the CPU only for a real fault. On the CPU, Robot reads back fewer earlier exchanges, and on a slow CPU gives its model shorter instructions. The setup guide and Settings › Model warn when answers will be slow or memory is tight, with buttons for what helps. Settings › Model shows speeds measured on your phone after 3 answers. The system voice is named from the phone's own text-to-speech engine; ADB fixes says "Not needed" for the recognizer fix when another recognizer is set; on a phone without the GrapheneOS Clock app, a routine that starts after your alarm says it won't start; and Phone lock keeps app stores and the phone's own file, care and wellbeing apps covered during a lock, whatever a lock lists.
- Web search works on phones again. In 1.0.x, one of Robot's patterns (keeping spelled-out e-mail addresses out of searches) had a bare `}` that Android's regex engine refuses, though the JVM the tests run on accepts it, so Robot's web search failed every time on a phone, and so did a browser search with words Robot's model wrote. A new test checks every regex in Robot for what Android's engine is known to refuse. Before 1.1.0 shipped, the phone found the same kind of pattern in Robot Files' reading; it's fixed, and the test knows that kind too. Robot's logs now name a failure's causes by their class, never their message.
- Automatic update checks run. In 1.0.x, Android refused to schedule the daily check for updates, the download on Wi-Fi and Termux's weekly check, so they never ran by themselves, though their switches showed On; only Check now worked. Android requires ACCESS_NETWORK_STATE before a scheduled job can wait for the network, and 1.0.x didn't declare it. 1.1.0 does: a normal permission, granted at install, that lets an app see whether the phone is online. Robot uses it for these jobs and nothing else. A phone on 1.0.x won't find 1.1.0 by itself: open Settings › About › Updates and tap Check now.
- Québec French. Settings › Language sets the menus (the phone's language, English or French) and the language Robot answers in. When you set that language, Robot's model is told to answer in it too; its answers can still drift, and a web answer is in English or French, picked from your question's words, whatever the setting. Screens, cards, notifications and what Robot says are in French, written with « tu »; the voice log and a bug report's diagnostic lines stay in English. Robot still works in English and Québec French only.
- Phone lock (Settings › Phone lock, off until you set it up). During a lock, every app but emergency calls, incoming calls, Clock and Robot is covered. Three kinds: Time of day, After the alarm (GrapheneOS Clock) and One app first, with a Daily limit. Locks are for you or for someone else, who needs a lock code to change them. Calls to the People you choose, Maps and a music app can stay open during a lock. The lock covers the dialer, so a call to one of those People goes from the call card's Call (with calls on Ask first, the default), with the Phone permission that lock's People row asks for; an emergency number you ask Robot for during a lock opens the phone's own emergency dialer, which the lock never covers, as the cover's Emergency call button does. You can also lock by voice ("lock the phone until 7", also in French). A second lock by voice never makes the one that's on end sooner, and during a lock for someone else nothing starts or changes one by voice: only the lock code does. If the phone's storage is full, locks keep working and a notification says Phone lock couldn't save its state. A passing Keystore or storage error never deletes the lock code, and a new code takes over only once it's saved and checked; until then the old one works. It runs in a small process of its own (`:lock`) and uses one Android accessibility service that sees only which app is in front, never what is on the screen. One app first and Daily limit also read Android's usage access, to count your time. It is a habit, not a wall: Settings › Phone lock › What a lock can't stop lists what it can't do at this strength, such as a restart in safe mode.
- Back up and restore (Settings › Back up and restore). Back up now saves one encrypted file, protected by a passphrase you choose or Robot generates, to a place you pick. You choose what goes in; keys and your voiceprint are off unless you tick them. Robot never keeps the passphrase and never uploads the file. Restore checks the whole file, shows a preview, and writes each category whole or not at all. A file that isn't your own restores with the safer settings: a helper you have never moves to another company, address or model maker, a new helper arrives off with no key, someone you confirmed in People never changes in Add to what's here (the default), Start from the lock screen stays off when it's off here, an update this phone hasn't skipped stays offered, and its brief reminders don't come back. A category that was empty when the preview opened keeps what you added since (not yet Phone lock's locks). Replace numbers the backup's headsets and cars after this phone's, so a routine here never waits for another device. A monthly reminder is off by default. The setup guide offers Restore from a backup on a new phone's first page.
- Skills (Settings › Skills; « compétence » in French). Teach Robot a short routine by voice ("teach a skill called heading home: text Sam I'm on my way, then open the route home") or say "save that as a skill" after Robot did the steps. Then say its name. Ask what a skill does, list your skills, or delete one. Each step still shows its own card, and skills never run on a locked phone.
- Robot's model picks skills (Settings › Skills › Let Robot's model pick skills, on by default). When your words fit up to 3 of your skills, Robot's model on the phone can pick one and fill its questions. If your words didn't name it, Robot asks "Did you mean …?" first. Values the model wrote are marked on the card, and you can change them. A skill that texts or emails someone shows its card until the model filled it right 9 of the last 10 times; a riskier one always asks for a tap. The model is told a skill's name, a one-line summary of its kinds of steps and its questions, never the steps or their fixed values. How often the model filled each skill right stays on the phone, isn't backed up and starts over after a restore; the switch is backed up with your skills.
- Security updates to two libraries Robot ships, Jackson (2.21.8) and jsoup (1.23.2), and a newer Android build toolchain. Material 3 stays at 1.3.2 on purpose, so Robot's look doesn't change.
- Wait before answering (Settings › Voice): how long Robot waits after you stop talking before it takes your question as finished. Short (0.6 s), Normal (1 s, the default), Long (1.6 s) or Very long (2.5 s), instead of a fixed half second. Holding the mic button keeps Robot listening until you let go, and the headset's and car mode's follow-up window waits for a question that is under way.
- Robot pauses your music or video while you ask, and starts it again after the answer: from the moment Robot listens until the answer is said or shown, you stop it or close the panel, nothing was heard, or a headset's follow-up window closes. If a turn hangs, the music comes back after a minute with nothing heard or said. A long answer read aloud pauses other sound too, instead of only lowering it. During a call nothing changes.
- Support Robot (Settings › About › Support Robot): Ko-fi, then five crypto addresses, each with Copy and a QR code Robot draws itself. It makes no network call until you tap a link.
- In French, Robot's two built-in models are called « Rapide » and « Plus futé », and Uncensored is « Sans censure ».
- Calendar events and calendar questions understand French dates and times (« vendredi à midi », « demain de 9 h à 10 h 30 », « lundi en huit », « la fin de semaine »), whatever language Robot answers in. Before, Robot read only English ones.
- Ask Robot to remind you on a date and it adds it to your calendar with an alert. It no longer sets an alarm you didn't give a time for, and for a timer, an alarm, an event or a note its reply is the card's own words, so it can't promise a day or time the card doesn't show. "Remind me to call the bank on the twentieth of November" (or « rappelle-moi d'appeler la banque le vingt novembre ») shows add_event's card, which always asks first and names the calendar set in Settings › People, notes and calendar › Calendar for new events: an all-day event with an alert at 9:00, or the event at the time you said with an alert then. An alarm runs only for a time you said, on the day it rings; for another day Robot offers the calendar reminder instead. After a timer, an alarm, an event or a note, Robot says what its card says in its own words. Dates like "the twentieth of November", "November 20th", "the 20th" and « le vingtième novembre » now read.
- "Remind me" or "remind me about it" (« rappelle-moi ça ») with nothing else, at the start of a conversation, makes Robot ask "What should I remind you about, and when?" instead of its model saving a note of your words.
- "Cancel my 6:30 alarm", "stop the timer" (« annule ma minuterie »): Android lets an app set alarms and timers, not cancel them, so Robot says it can't and offers your clock app on a card that only a tap opens. Before, the model could take the words as a request to set one.
- « Ajoute un film ce soir de 22 h à 1 h » set a one-minute timer. French parts of the day (« ce soir », « matin »), noon, midnight and times written « 22 h » now count as calendar words, so Robot's model gets the calendar's tools and can add the event. The model still picks the tool: when your words ask for an event or name a time range and it picks a timer or an alarm you didn't name, its card asks first.
- Esc on a hardware keyboard works like Back on Robot's panel: a press hides the on-screen keyboard if it's showing, or else closes the panel. Before, it left the panel unable to open again until Robot was force-stopped.
- Robot's voice says clock times right. In French, « 7 h 02 » is said « 7 heures 2 », « 12 h 30 » and the model's « 12:30 » or « 12h30 » « 12 heures 30 », « 1 h » « une heure », « 0 h 15 » « minuit 15 »; before, the voice read the « h » as a letter (« sept ache zéro deux ») and the colon as a decimal comma (« douze virgule trente »). In English, "7:02" is said "seven oh two" and "7:00" "seven o'clock", not "seven zero two" and "seven zero zero". The screen shows times as before.
- Bouncy Castle's Argon2 and BLAKE2b sources (MIT licence, listed on Settings › About › Licences) are copied into Robot for the backup file's passphrase; the rest of Bouncy Castle is not in the APK. Every source file of Robot's own now carries its GPL-3.0-only licence line.
- With Automatic help, Robot answers from its own notes first and asks the helper only when its answer is missing or unsure.
- Conversation mode: a paused transcript resumes only where a new one could start, so on the lock screen only with Start from the lock screen on, and never during a lock for someone else. The summary screen, Your voice and the Transcription check look at the phone's lock again at every button, and a summary or a share to a helper's app waits until you unlock. The summary screen sends nothing of a transcript deleted meanwhile, and shows its card again if the helper's settings changed since. Recents keeps no picture of a transcript or a summary.
- Replace names, on a helper's summary card, now covers every name you gave a speaker: earlier names, names from merged speakers, and each word of a full name. A name word is replaced wherever it appears in the transcript, even as an everyday word; a particle like « de » alone is left, and a word two speakers' names share becomes "someone".
- A reply you stopped in its 3 seconds stays a card: a tap sends it, or a spoken yes only with Answer reply cards by voice on. A reply card takes a yes or a no only while it's the last thing Robot asked about.
- A messaging address is taken for one of your People by its number only at the SMS gateway Robot texts through, for the panel's names, replies and the message quarantine. The same number at another server isn't that person there.
- A helper's card says your question goes with today's date and your country. "Free up memory" says how many recent apps are likely in memory and names them only on the card's buttons. Web search's consent names every recipient: the service, Wikipedia for a who, what or where question, Open Library for a question about books, and the result pages this phone may open. A place named for the weather goes to Open-Meteo only when it passes the nearest place's check (no address, number or long run of words). A routine that's off runs only after a tap on its card. An answer from Robot Files counts as personal: a helper gets it only through a card you tap, and a car or headset session treats it as personal from its first sentence.
- Fixes: when you cancel an action, Robot says nothing was done and removes any sentence that claims otherwise; the weather for a named place works with a day ("Paris tomorrow"); an email to someone not in your contacts isn't reported as drafted; memories you pinned count as personal for helpers; the panel's text field stays above the keyboard in landscape; a key Robot won't send over plain http says so; Settings › Memory shows what you change from the panel; after a refused message, a conversation with your own GGUF model goes on.
- PRIVACY.md describes Robot Files, travel mode, files in backups, the speed and GPU-check records kept on the phone, the accessibility service, usage access for screen time, backups, skills (and what Robot's model is told about them), the Support page, the two new permissions, the date and country a helper's first message carries, up to 5 result pages a web search may open (3 by default), that GitHub receives a feedback issue's text when its page opens (Send feedback's note says so too), and the Phone permission for a phone lock's call card; SECURITY.md covers Robot Files' encryption, its isolated reader, the share target, the accessibility service and backups.

## Moving from 0.9 to 1.0

The full steps are in the [README](README.md#moving-from-09-to-10). In short: install 1.0 beside 0.9 (0.9 looks for updates in the old private repository, so it won't offer it: install 1.0 by hand from the release page); in 0.9 export People (Settings › People, notes
and calendar › People › More options › Export (.vcf)) and import that file in 1.0 (Import contacts file (.vcf)); export
Memory too (Settings › Memory › Export) and import it in 1.0 (Settings › Memory › Import, ticking "These are my own
memories" for your own file), then delete the file; let 1.0 download its models again, or import Robot's model file (the
model › Import a file); make 1.0 your digital assistant; uninstall 0.9; then do the ADB fixes again in 1.0. Settings,
History, notes, transcripts, keys and routines don't move: share or save what you want to keep first.

## 1.0.1 (2026-10-15)

Fixes from my own security review after 1.0, and an APK that's easier to check. It updates 1.0.0 in place. After the
update, open Robot once: it moves its model into its own storage, and until it has, each model load takes 10 to 30
seconds longer.

- A key never goes over plain http. If a helper or your own search server has an http:// address and a key, Robot refuses before connecting and tells you to use an https address or remove the key. Your key stays saved. A server on your own network with no key works as before.
- Robot's model lives in its own private storage, which only Robot can write. A new download is copied in as it's checked. A model from 1.0 moves in once, the first time you open Robot after updating, when there's room for a second copy (the model's size plus 0.5 GB). Until it has moved, Robot checks the whole file before each load. Your own model file is imported the same way.
- After Robot reads something in a conversation (a web page, a helper's answer, the screen or a photo), every action that writes or opens something asks first, even when you asked for it. Text you didn't type could have chosen its details. Settings › Safety & privacy › Safety cards has the other choices.
- The headset button's way into Robot over the lock screen opens only for your headset, your car or Robot itself, never for another app.
- Termux's read-only commands: a `find` that follows links (`-L`, `-H`) shows its card first, since a link can lead out of your home folder.
- Your own GGUF model: a message that holds one of the model's control texts is refused before the model reads it. The rest of that conversation fails too; start a new one.
- The APK no longer carries Google's encrypted dependency block, and the llama.cpp library builds byte for byte the same in any folder. The source builds with a pinned Gradle (8.14.6), and every library it downloads is checked against a recorded SHA-256.
- Send feedback's "Open a GitHub issue" opens the bug report or idea form with your report filled in.

## 1.0.0 (2026-10-13)

Setup and updates:

- New app ID, `ai.robot_app` (after Robot's domain, robot-app.ai; it was `app.heyrobot`). 1.0 installs as a new app beside 0.9, which can't update to it. The ADB fixes and Robot's web requests name the new ID.
- Robot now checks for updates in the public repository, `picocryptfan/robot-assistant`. 0.9 looks in the old private one, so you install 1.0 by hand.
- A setup guide runs on first launch. It walks through each feature: what it does, what stays on the phone, what goes online, and where the setting is. It starts with ten examples of what Robot can do, checks your phone and offers its downloads. Nothing is turned on unless you choose it, and Android asks for a permission only when you tap Allow. Skip this step keeps the default. Finish later resumes from Home. Settings › About › Setup guide runs it again.
- Downloads and web requests name Robot and its version (`Robot/<version>`) instead of Android's default, which names your phone model. A download over 200 MB asks first whether to wait for Wi-Fi or download anyway.

Features:

- Ask a helper. Say "ask the helper …", "ask a helper …" or "ask my helper …" (« demande à l'assistant … », « demande à un assistant … ») and Robot asks the helper it checks with (Settings › Helpers › When Robot needs help › Ask). The old spoken phrase still works.
- Helpers work with any AI company. With your API key Robot asks it itself; without one, a helper whose company has an Android app opens that app with your question, and you press Send there. No helper is set up until you add one. Each helper answers to its own name, which you can change ("ask Jarvis …"). "Summarize with a stronger AI" can use any helper with an API key, with that helper's model, price and spending caps.
- Search in your browser with nothing to set up: "search DuckDuckGo for …" or « cherche sur Startpage … » opens the engine in your browser (Startpage by default, or Brave Search, Mojeek, Qwant, Ecosia, Google or Bing). Robot never reads the results. Web search also gains DuckDuckGo's Instant Answers and a short list of public SearXNG servers. Settings › Web search and the setup guide show every engine and service with a High, Medium or Low privacy level.
- Memory import. Settings › Memory › Import reads a memory export from 0.9 or 1.0 and shows what it would add before anything is written. Items you already have, and any that hold a password, a PIN, a code or a card number, are skipped. Imported items count as something Robot read, so actions they lead to ask first, until you say they are yours.
- Car mode doesn't hold or refuse anything while you drive, since a passenger may be the one asking. Opening an app, drafts, new texts, a helper's app and replies that need a tap work as they do outside the car, and a call or reply Robot can't finish by voice shows its card on the screen. Settings › Car mode › New texts while driving and the "From your drive" list are gone. Lock-screen rules and consent cards are the same as outside the car.
- Termux: a weekly check for Termux updates (Robot never downloads or installs it), Termux paused after an update until you trust that version, and the ADB fixes without a computer, through Shizuku. A command or argument holding one of Robot's own keys is refused.

Look and wording:

- The assistant panel always uses the classic look (round mic, chat bubbles), and History shows conversations the same way. The Classic look switch in Settings › Display is gone.
- New icon: a robot head shaped as a speech bubble, on blue, with a themed version and the same head in notifications. The robot on the panel shows whether it is listening, thinking or speaking.
- Explanations describe the kind of app ("your messaging app", "your maps app") instead of naming one. A name stays only on a row or step that checks, opens or sets up that app.
- Settings › Voice and the guide say once that Robot works in English and Québec French, and only writes down and reads other languages.
- The Home screen's corner-swipe tip no longer says Robot sees the screen on the lock screen.
- "Robot closed unexpectedly": after the app or the panel crashes or stops responding, Robot says so the next time you open it and offers Send feedback with a report of codes only.
- Accessibility: larger touch targets on transcript chips, the consent tick box as one TalkBack stop, the "More" menu drawn with a "more" icon, a higher-contrast "not ready" button, and the panel's answer read by TalkBack once it is complete when replies aren't spoken.
- Trademark marks: Settings › About and the setup guide's first page show Robot™, About ends with "Robot™ and Robot Assistant™ are trademarks of the Robot project.", and the README and these notes carry the same mark. The launcher name, spoken text and notifications are unchanged.

Security:

- Another app on screen can no longer open Robot's panel with instructions of its own.
- A conversation begun on the lock screen never comes back locked once you used it unlocked, and never steers your unlocked one. The next unlocked "hey robot" starts afresh, unless Robot asked you to unlock and you come back within 2 minutes. Then the locked turns stay as quoted information, never as your words. Continuing one from History shows its earlier turns as said while the phone was locked.
- A Termux command run through Shizuku's `rish` shows "Runs with ADB's rights through Shizuku" and runs only when you tap, never on a spoken yes.
- When you say "search in my browser for …", your words open without a card only in the engine you named, or your default. A search for "that dentist" or « ce resto » lets the model write the words and shows them on a card first.
- Termux's safety rules now apply to every call, and `git` left the list of commands that may skip the card.
- Your calendar read out in the brief and a note's text count as content Robot didn't write, so actions and searches they lead to ask first. Memory kept from something Robot read stays content when it comes back, and a message sender's name is never taken as your words.
- A "remember that …" Robot refused isn't kept in History.
- The brief's, Ready cards' and routine summaries' notifications are hidden on the lock screen.
- Settings › Memory and a private answer can't be captured, and Memory's fields don't teach your keyboard.
- A web search names Wikipedia and Open Library on its card, its answer's "Sent to" line and History when they get your words.
- Your own `.litertlm` file is checked strictly before it is read, and importing one warns you first. The model process's replies have a size cap.
- On the locked phone nothing makes, deletes or lists a routine or adds a brief reminder.
- A contact's number keeps its "+" when dialled, and the locked car session calls only People and contacts.
- Unlocking the phone now reaches Robot (first-unlock routines, the brief finished at the unlock, car mode). Fake Clock alarms can no longer flood the voice log.

Building:

- The source bundle for the GPL code now also holds slimt and its parts, llama.cpp and KleidiAI, and Robot's own native sources. `tools/repro_check.sh` builds the APK twice and compares the two. Build-tools are pinned to 35.0.0.
- The public README, CONTRIBUTING, CHANGELOG, issue templates, PRIVACY and SECURITY.

## 0.9.0

- **Web search**, off until you set it up: one service you choose (your own SearXNG, Brave, Kagi, Mojeek or a public
  SearXNG), "search the web for …", answers from up to three result pages with numbered sources. Every query is checked
  before it leaves; what Robot remembers never goes to a search service.
- **Termux agent**, off until you set it up: "run backup" runs your script in `~/robot`; a short command the model writes
  is shown on a Run / Edit / Cancel card first, runs under a time limit, and its output is cleaned of secrets.
- Web answers and command output count as content Robot didn't write: later actions ask first, and after a message every
  search and command is a card only a tap answers. Routines never search or run Termux.
- Tool groups give way so a question always keeps room in the model's context.

## 0.8.0

- **Routines and a morning brief**: fixed lists of steps that start at a time, when you turn off an alarm, at the first
  unlock after a time, when your headset or car connects, when charging starts, or when you ask; the brief says the
  time, the weather, today's calendar and your reminders.
- **Headset mode**: your headset's voice button opens Robot, which listens and answers through the headset.
- **Car mode**: when your car connects over Bluetooth (once you added it), Robot talks through the car with big buttons
  on the screen and short answers; what needs the screen waits until you park.
- **New messages, announced** in the headset and the car, when you turn that on.
- Pixel 11 (Tensor G6) TPU build in beta; a smaller APK (compressed dex files).

## 0.7.0

- **Memory**: "remember that …", "forget that", "what do you know about me?", kept encrypted on the phone; private items
  (passwords, codes) are never spoken without your say-so or sent to a helper.
- **Messages**: reads, summarizes, translates and answers the messages of the apps you choose, from their
  notifications, in memory only; a reply goes after you tap Send.
- Nothing from a message becomes memory by itself.
- **Send feedback**: a bug report or an idea, with only the diagnostics you tick, sent by you through the app you pick.
- Model problems: the last 10, with a report you can copy or share, including a native crash's tombstone as codes.

## 0.6.0

- **Conversation mode**: transcribes a conversation in French and English on the phone, with speaker labels.
- **Fast translation engine** (Mozilla Bergamot fr↔en, run by slimt), the interpreter (face to face, aloud) and summaries.
- **Helpers**: the paid AI helper became Settings › Helpers: any company's API with your key, or a model on your own
  computer.
- **Updates**: Settings › About › Updates finds Robot's new releases on GitHub and installs them after checking each one.
- Engine hardening: llama.cpp in its own isolated process.

## 0.5.0

Built conversation mode and the fast translation engine; released as part of 0.6.0.

## 0.4.7

- Fast on every phone: the general Gemma 4 E2B build on the CPU or GPU for Pixels without a Tensor G5 (Pixel 9a and
  others); Settings › Model shows the phone and its chip.

## 0.4.6

- Fixes from the 0.4.4 phone tests: the interrupt sensitivity is saved as tapped, "stop" ends speech reliably, and more.

## 0.4.5

- **GGUF models**: your own `.gguf` file, run by llama.cpp on the CPU; "Let the model think first" switch.
- **Settings › About › Licences**: every part of Robot, its licence and full text; the source links.
- A choice of Nominatim server for the nearest place; the uncensored model's terms to accept before its download.
- The look began to move to stock Android (Material 3), with the classic look kept as an option.

## 0.4.4 and 0.4.3

- Short-context models (2,048 to 4,095 tokens), such as MedGemma, run as chat only.
- 0.4.4: test build of the GGUF work.

## 0.4.2 and 0.4.1

- **Model choice**: Fast stays the default; Smarter (Gemma 4 E4B), Uncensored, or your own model file, each falling
  back to Fast with a reason when it isn't ready.

## 0.4.0

- **Weather** (Open-Meteo), **phone status** (storage, battery, memory) and **freeing space**.
- **The nearest place** online (OpenStreetMap's Nominatim), for Organic Maps' route, asked first by default.
- **Safety & privacy**: every rule about when Robot asks first is a switch.
- **People**: Robot's own list, so "text Mom" works for someone only in Cheogram or Proton Mail.

## 0.3.1 and 0.3.0

- **Paid AI helper**: hand a question to a paid AI service through its API with your own key, or open it in that
  service's app, only when you ask or allow it.

## 0.2.3

- Voice reliability, wake phrases of your own, and texting fixes; the voice log.

## 0.2.0 to 0.2.2

- Drafts texts (Cheogram) and emails (Proton Mail), keeps and finds notes, reads and adds calendar events, looks up
  contacts; Cheogram-only people.

## 0.1.x

- The first daily-usable Robot: "hey robot" and the corner swipe, speech recognition (Parakeet) and spoken replies
  (Kokoro) on the phone, Gemma 4 E2B on the Tensor G5's TPU.
