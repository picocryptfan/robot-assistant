package app.heyrobot.assistant

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import androidx.core.graphics.drawable.toBitmap
import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Addresses
import app.heyrobot.core.AppEntry
import app.heyrobot.core.AppMatcher
import app.heyrobot.core.CalendarFormat
import app.heyrobot.core.CalendarPick
import app.heyrobot.core.ContactMatcher
import app.heyrobot.core.Durations
import app.heyrobot.core.EmailApp
import app.heyrobot.core.EventPlan
import app.heyrobot.core.LookupFallback
import app.heyrobot.core.Events
import app.heyrobot.core.Executed
import app.heyrobot.core.MailTarget
import app.heyrobot.core.MailTo
import app.heyrobot.core.NoteSearch
import app.heyrobot.core.PhoneNumbers
import app.heyrobot.core.Prepared
import app.heyrobot.core.RLog
import app.heyrobot.core.Read
import app.heyrobot.core.Recipients
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SmsTo
import app.heyrobot.core.TextApp
import app.heyrobot.core.TextTarget
import app.heyrobot.core.TextVia
import app.heyrobot.core.WhenParser
import app.heyrobot.core.XmppLink
import app.heyrobot.sys.Calendars
import app.heyrobot.sys.Contacts
import app.heyrobot.sys.HandOffApps
import app.heyrobot.sys.NotesFiles
import app.heyrobot.sys.Phones
import java.time.ZonedDateTime
import java.util.Locale

// VOICE PROCESS (the app side, never :brain). Runs an action that already passed the approval gate,
// with intents that need no new permissions: AlarmClock (GrapheneOS Clock handles SET_TIMER with
// EXTRA_SKIP_UI silently, verified in phase 0), the launcher's app list, Organic Maps' om:// URIs (geo:
// as a fallback) and CameraManager's torch. Phase 2: texts and emails are handed to Cheogram, the SMS app or
// Proton Mail as drafts (the owner presses Send there), contacts and the calendar are read with the permissions
// the main app asked for, notes go to Robot Notes. The log records the tool and the outcome, never arguments.

class ToolExecutor(private val ctx: Context, private val settings: RobotSettings, private val launch: (Intent) -> Boolean) {
    /**
     * Before the gate and the card: what the action will really do. open_app becomes the installed app it will open
     * (its real label and launcher activity); send_text and draft_email the person, number or address it goes to
     * (or the choices, when several fit); add_event its dates and calendar; read_calendar its day or week. What
     * can't be done is refused here, before any card.
     */
    fun prepare(action: Action): Prepared = try {
        when (action) {
            is Action.OpenApp -> {
                val m = AppMatcher.match(action.name, launcherApps())
                if (m == null) Prepared.Refused(ActionStatus.FAILED, "No app called “${action.name}” is installed.")
                else Prepared.Ready(Action.OpenApp(m.app.label, m.app.key), mustAsk = m.loose)
            }
            is Action.SendText -> prepareText(action)
            is Action.DraftEmail -> prepareEmail(action)
            is Action.LookupContact -> if (Contacts.granted(ctx)) Prepared.Ready(action) else needContacts()
            is Action.AddEvent -> prepareEvent(action)
            is Action.ReadCalendar -> prepareRead(action)
            else -> Prepared.Ready(action)
        }
    } catch (t: Throwable) {
        RLog.w("tool_prepare", t, "tool" to action.tool)
        Prepared.Refused(ActionStatus.FAILED, "Something went wrong.")
    }

    fun run(action: Action): Executed {
        val r = try {
            when (action) {
                is Action.SetTimer -> timer(action.seconds)
                is Action.SetAlarm -> alarm(action.hour, action.minute)
                is Action.OpenApp -> openApp(action)
                is Action.MapsSearch -> maps(action.query, "Opened Organic Maps search")
                is Action.MapsNavigate -> maps(action.destination, "Opened Organic Maps on the destination: tap Route for directions")
                is Action.Flashlight -> torch(action.on)
                is Action.SendText -> text(action)
                is Action.DraftEmail -> email(action)
                is Action.LookupContact -> lookup(action)
                is Action.AddNote -> addNote(action)
                is Action.FindNotes -> findNotes(action)
                is Action.AddEvent -> addEvent(action)
                is Action.ReadCalendar -> readCalendar(action)
            }
        } catch (t: Throwable) {
            RLog.w("tool_run", t, "tool" to action.tool)
            Executed(ActionStatus.FAILED, "Something went wrong.")
        }
        RLog.i("tool_run", "tool" to action.tool, "status" to r.status)
        return r
    }

    /** The launcher icon of the activity [key] names, small, for the confirmation card; null if it has none. */
    fun appIcon(key: String): Bitmap? = try {
        val (pkg, cls) = key.split('/', limit = 2)
        ctx.packageManager.getActivityIcon(ComponentName(pkg, cls)).toBitmap(ICON_PX, ICON_PX)
    } catch (_: Exception) {
        null
    }

    private fun start(i: Intent): Boolean {
        if (i.resolveActivity(ctx.packageManager) == null) return false
        return try { launch(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
    }

    private fun timer(seconds: Long): Executed {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.toInt())
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Robot")
        return if (start(i)) Executed(ActionStatus.DONE, "Timer set for ${Durations.spoken(seconds)}.")
        else Executed(ActionStatus.FAILED, "No clock app on this phone takes timers.")
    }

    private fun alarm(hour: Int, minute: Int): Executed {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Robot")
        return if (start(i)) Executed(ActionStatus.DONE, String.format(Locale.US, "Alarm set for %02d:%02d.", hour, minute))
        else Executed(ActionStatus.FAILED, "No clock app on this phone takes alarms.")
    }

    private fun launcherApps(): List<AppEntry> {
        val pm = ctx.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(q, PackageManager.MATCH_ALL).map { ri ->
            AppEntry(ri.loadLabel(pm).toString(), "${ri.activityInfo.packageName}/${ri.activityInfo.name}")
        }
    }

    private fun openApp(a: Action.OpenApp): Executed {
        // Prepared actions carry the activity the card showed; anything else is resolved strictly (no loose match).
        val entry = a.key?.let { AppEntry(a.name, it) }
            ?: AppMatcher.match(a.name, launcherApps())?.takeIf { !it.loose }?.app
            ?: return Executed(ActionStatus.FAILED, "No app called “${a.name}” is installed.")
        val (pkg, cls) = entry.key.split('/', limit = 2)
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(pkg, cls)
            .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return if (start(i)) Executed(ActionStatus.DONE, "Opened ${entry.label}.", launchedApp = true)
        else Executed(ActionStatus.FAILED, "${entry.label} didn't open.")
    }

    private fun maps(query: String, doneText: String): Executed {
        val q = Uri.encode(query)
        for (uri in listOf("om://search?query=$q", "geo:0,0?q=$q")) {
            if (start(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))) return Executed(ActionStatus.DONE, "$doneText.", launchedApp = true)
        }
        return Executed(ActionStatus.FAILED, "Organic Maps isn't installed.")
    }

    private fun torch(on: Boolean): Executed {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { id ->
            val c = cm.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return Executed(ActionStatus.FAILED, "This phone has no flashlight.")
        cm.setTorchMode(id, on)
        return Executed(ActionStatus.DONE, if (on) "Flashlight on." else "Flashlight off.")
    }

    // ------------------------------------------------------------------ people

    private fun needContacts() = Prepared.Refused(ActionStatus.NEEDS_PERMISSION, "Allow Robot to read your contacts in the Robot app.")

    private val region get() = settings.phoneRegion

    /** A number's target: its gateway address for Cheogram, the number itself for the SMS app. */
    private fun numberTarget(name: String?, e164: String, via: TextVia, appName: String): TextTarget? {
        val address = if (via == TextVia.CHEOGRAM) PhoneNumbers.jid(e164, settings.smsGateway) ?: return null else e164
        return TextTarget(name, address, Phones.display(e164, region), appName, via)
    }

    private fun prepareText(a: Action.SendText): Prepared {
        val cheogram = HandOffApps.cheogram(ctx)
        val via = when (settings.textAppFor(cheogram != null)) {
            TextApp.OFF -> return Prepared.Refused(ActionStatus.DISABLED, "Texts are turned off in Robot's settings.")
            TextApp.CHEOGRAM -> TextVia.CHEOGRAM
            TextApp.SMS -> TextVia.SMS
        }
        if (via == TextVia.CHEOGRAM && cheogram == null) return Prepared.Refused(ActionStatus.NO_APP, "Install Cheogram, or choose the SMS app in Robot's settings.")
        if (via == TextVia.SMS && !HandOffApps.resolves(ctx, HandOffApps.smsIntent())) return Prepared.Refused(ActionStatus.NO_APP, "No SMS app is installed.")
        val appName = if (via == TextVia.CHEOGRAM) "Cheogram" else HandOffApps.smsAppName(ctx) ?: "the SMS app"
        val to = a.to.trim()
        // An address or a number the model wrote: never checked against the contacts (it may have made up digits),
        // so the card always shows it, even when texts may just go.
        if (Addresses.isAddress(to)) {
            if (via == TextVia.SMS) return Prepared.Refused(ActionStatus.FAILED, "The SMS app can't text an XMPP address.")
            if (!Addresses.isJid(to)) return Prepared.Refused(ActionStatus.INVALID, "That XMPP address can't be used.")
            return Prepared.Ready(a.copy(target = TextTarget(null, to, to, appName, via)), mustAsk = true)
        }
        if (Addresses.isPhone(to)) {
            val t = Phones.e164(to, region)?.let { numberTarget(null, it, via, appName) }
                ?: return Prepared.Refused(ActionStatus.INVALID, "That phone number couldn't be read.")
            return Prepared.Ready(a.copy(target = t), mustAsk = true)
        }
        if (!Contacts.granted(ctx)) return needContacts()
        val all = ContactMatcher.best(to, Contacts.names(ctx), max = Int.MAX_VALUE)
        // Not in the phone's contacts: with Cheogram, the person may be in its own roster (the card offers its list).
        if (all.isEmpty()) return Recipients.notFound(a, "No contact matches “$to”.", via)
        val matches = all.take(Recipients.MAX_PEOPLE)
        val options = Recipients.forText(Contacts.cards(ctx, matches), xmppOk = via == TextVia.CHEOGRAM).mapNotNull { r ->
            val t = if (r.xmpp) TextTarget(r.name, r.address, r.address, appName, via)
            else Phones.e164(r.address, region)?.let { numberTarget(r.name, it, via, appName) }
            t?.let { Recipients.Option(r.name, r.label, it.display, a.copy(target = it)) }
        }.distinctBy { (it.action as Action.SendText).target?.address }
        // Fixed wording: the contacts' names are not sent to the model here.
        if (options.isEmpty()) return Recipients.notFound(a, "No matching contact has a phone number${if (via == TextVia.CHEOGRAM) " or XMPP address" else ""}.", via)
        return Recipients.prepare(a, matches, options, all.size - matches.size) { name -> a.copy(to = name) }
    }

    private fun prepareEmail(a: Action.DraftEmail): Prepared {
        if (!HandOffApps.resolves(ctx, HandOffApps.mailIntent())) return Prepared.Refused(ActionStatus.NO_APP, "No email app is installed.")
        val proton = HandOffApps.proton(ctx)
        val appName = if (settings.emailAppFor(proton) == EmailApp.PROTON && proton) "Proton Mail" else null
        val to = a.to.trim()
        // An address the model wrote: the card always shows it.
        if (Addresses.isAddress(to)) return Prepared.Ready(a.copy(target = MailTarget(null, to, appName)), mustAsk = true)
        if (!Contacts.granted(ctx)) return needContacts()
        val all = ContactMatcher.best(to, Contacts.names(ctx), max = Int.MAX_VALUE)
        if (all.isEmpty()) return Prepared.Refused(ActionStatus.NOT_FOUND, "No contact matches “$to”.")
        val matches = all.take(Recipients.MAX_PEOPLE)
        val options = Recipients.forEmail(Contacts.cards(ctx, matches)).map { r ->
            Recipients.Option(r.name, null, r.address, a.copy(target = MailTarget(r.name, r.address, appName)))
        }
        if (options.isEmpty()) return Prepared.Refused(ActionStatus.NOT_FOUND, "No matching contact has an email address.")
        return Recipients.prepare(a, matches, options, all.size - matches.size) { name -> a.copy(to = name) }
    }

    /**
     * Cheogram (or the SMS app) opens with the text in its compose box: nothing is sent until the owner presses Send.
     * With no contact to text, Cheogram's share screen (ShareWithActivity, exported for SEND text/plain) opens with the
     * message instead: the owner picks the person in Cheogram's list, and the text is in that chat's box.
     */
    private fun text(a: Action.SendText): Executed {
        val t = a.target ?: return Executed(ActionStatus.FAILED, "Robot didn't find who to text.")
        val i = when (t.via) {
            TextVia.CHEOGRAM -> {
                val pkg = HandOffApps.cheogram(ctx) ?: return Executed(ActionStatus.NO_APP, "Install Cheogram.")
                Intent(Intent.ACTION_VIEW, Uri.parse(XmppLink.message(t.address, a.message))).setPackage(pkg)
            }
            TextVia.CHEOGRAM_PICK -> {
                val pkg = HandOffApps.cheogram(ctx) ?: return Executed(ActionStatus.NO_APP, "Install Cheogram.")
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, a.message).setPackage(pkg)
            }
            TextVia.SMS -> Intent(Intent.ACTION_SENDTO, Uri.parse(SmsTo.uri(t.address))).putExtra("sms_body", a.message)
        }
        // A draft, not a sent text. Cheogram keeps a draft already in that chat instead of this one (it says "already
        // drafting"), and with the number in several accounts it shows a search first: the card says so.
        val detail = when (t.via) {
            TextVia.CHEOGRAM -> "Opened a draft in Cheogram: nothing is sent until Send is pressed there. " +
                "If that chat already had a draft, Cheogram keeps the old one."
            TextVia.CHEOGRAM_PICK -> "Opened Cheogram with the message: choose who it goes to there. Nothing is sent until Send is pressed."
            TextVia.SMS -> "Opened a draft in ${t.appName}: nothing is sent until Send is pressed there."
        }
        return if (start(i)) Executed(ActionStatus.DRAFT_OPENED, detail, launchedApp = true)
        else Executed(ActionStatus.NO_APP, if (t.via == TextVia.SMS) "No SMS app is installed." else "Install Cheogram.")
    }

    /** Proton Mail (or the app picked in the chooser) opens the draft: mailto: with the same values in the extras. */
    private fun email(a: Action.DraftEmail): Executed {
        val t = a.target ?: return Executed(ActionStatus.FAILED, "Robot didn't find who to email.")
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse(MailTo.uri(t.address, a.subject, a.text)))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(t.address))
            .putExtra(Intent.EXTRA_SUBJECT, a.subject)
            .putExtra(Intent.EXTRA_TEXT, a.text)
        val go = if (t.appName != null) i.setPackage(HandOffApps.PROTON) else Intent.createChooser(i, "Write the email with")
        return if (start(go)) Executed(ActionStatus.DRAFT_OPENED, "Opened a draft in ${t.appName ?: "the email app"}: nothing is sent until Send is pressed there.", launchedApp = true)
        else Executed(ActionStatus.NO_APP, "No email app is installed.")
    }

    private fun lookup(a: Action.LookupContact): Executed {
        if (!Contacts.granted(ctx)) return Executed(ActionStatus.NEEDS_PERMISSION, "Allow Robot to read your contacts in the Robot app.")
        val matches = ContactMatcher.best(a.name, Contacts.names(ctx))
        if (matches.isEmpty()) {
            // With Cheogram as the texting app the person may be in its own list: the model is told so (a fixed code),
            // and the card offers to open Cheogram.
            val cheogram = HandOffApps.cheogram(ctx)
            val f = LookupFallback.notFound(a.name, settings.textAppFor(cheogram != null), cheogram != null)
            return Executed(ActionStatus.NOT_FOUND, f.detail, results = emptyList(), forModel = f.forModel, handOff = if (f.openCheogram) cheogram else null)
        }
        val cards = Contacts.cards(ctx, matches)
        val results = Recipients.lookup(cards) { raw -> Phones.e164(raw, region)?.let { Phones.display(it, region) } ?: raw }
        val lines = results.joinToString("\n") { r ->
            listOfNotNull(r["name"] as String?, (r["numbers"] as List<*>?)?.joinToString(", "), (r["emails"] as List<*>?)?.joinToString(", "), r["xmpp"] as String?)
                .joinToString(" · ")
        }
        val detail = if (results.size == 1) "Found 1 contact." else "Found ${results.size} contacts."
        return Executed(ActionStatus.DONE, detail, results = results, body = lines, spoken = lines.replace("\n", ". "))
    }

    // ------------------------------------------------------------------ notes

    private fun addNote(a: Action.AddNote): Executed {
        val n = NotesFiles.store(ctx).add(a.text, a.title, System.currentTimeMillis())
            ?: return Executed(ActionStatus.FAILED, "The note couldn't be saved.")
        return Executed(ActionStatus.DONE, "Saved to Robot Notes.", undo = n.id)
    }

    private fun findNotes(a: Action.FindNotes): Executed {
        val found = NoteSearch.find(a.query, NotesFiles.store(ctx).list())
        if (found.isEmpty()) return Executed(ActionStatus.NOT_FOUND, "No note matches “${a.query}”.", results = emptyList())
        val results = NoteSearch.results(found)
        val titles = found.joinToString(", ") { it.displayTitle }
        return Executed(ActionStatus.DONE, if (found.size == 1) "Found 1 note." else "Found ${found.size} notes.", results = results,
            body = found.joinToString("\n") { it.displayTitle }, spoken = "Found: $titles.")
    }

    // ------------------------------------------------------------------ calendar

    private fun prepareEvent(a: Action.AddEvent): Prepared {
        val now = ZonedDateTime.now()
        val span = when (val r = Events.times(a, now, settings.eventMinutes)) {
            is Read.Unreadable -> return Prepared.Refused(ActionStatus.INVALID, r.detail)
            is Read.Ok -> r.value
        }
        // Robot adds it itself when it may write to a calendar; otherwise the calendar app's editor opens prefilled.
        val cal = if (Calendars.readGranted(ctx) && Calendars.writeGranted(ctx)) CalendarPick.choose(Calendars.writable(ctx), settings.eventCalendar) else null
        if (cal == null && !HandOffApps.resolves(ctx, Calendars.editorIntent())) {
            return if (!Calendars.writeGranted(ctx)) Prepared.Refused(ActionStatus.NEEDS_PERMISSION, "Allow Robot to use your calendar in the Robot app.")
            else Prepared.Refused(ActionStatus.NO_APP, "No calendar app is installed.")
        }
        return Prepared.Ready(a.copy(plan = EventPlan(span.start, span.end!!, span.allDay, cal?.id, cal?.name, now.year)))
    }

    private fun addEvent(a: Action.AddEvent): Executed {
        val p = a.plan ?: return Executed(ActionStatus.FAILED, "Robot couldn't read the date.")
        if (p.calendarId != null) {
            // Any provider error (the calendar was removed while the card waited...) falls back to the editor.
            val id = try { Calendars.insert(ctx, a.title, p) } catch (t: Exception) { RLog.w("calendar_insert", t); null }
            // Fixed wording: a shared calendar's name is not the model's business (the card shows it).
            if (id != null) return Executed(ActionStatus.DONE, "Added to the calendar.")
        }
        return if (start(Calendars.insertIntent(a.title, p))) Executed(ActionStatus.DRAFT_OPENED, "Opened the calendar app with the event: it is added only when saved there.", launchedApp = true)
        else Executed(ActionStatus.NO_APP, "No calendar app is installed.")
    }

    private fun prepareRead(a: Action.ReadCalendar): Prepared {
        if (!Calendars.readGranted(ctx)) return Prepared.Refused(ActionStatus.NEEDS_PERMISSION, "Allow Robot to read your calendar in the Robot app.")
        return when (val r = WhenParser.range(a.day, ZonedDateTime.now())) {
            is Read.Unreadable -> Prepared.Refused(ActionStatus.INVALID, r.detail)
            is Read.Ok -> Prepared.Ready(a.copy(range = r.value))
        }
    }

    private fun readCalendar(a: Action.ReadCalendar): Executed {
        val range = a.range ?: return Executed(ActionStatus.FAILED, "Robot couldn't read the day.")
        if (!Calendars.readGranted(ctx)) return Executed(ActionStatus.NEEDS_PERMISSION, "Allow Robot to read your calendar in the Robot app.")
        val events = CalendarFormat.inRange(Calendars.events(ctx, range), range)
        val results = CalendarFormat.results(events, range)
        val detail = CalendarFormat.detail(events.size, range)
        val lines = CalendarFormat.lines(results)
        return Executed(ActionStatus.DONE, detail, results = results, body = lines.ifEmpty { null },
            spoken = (detail + " " + lines.replace("\n", ". ").replace(" · ", " ")).trim())
    }

    private companion object {
        const val ICON_PX = 96
    }
}
