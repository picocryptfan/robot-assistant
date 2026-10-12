package app.heyrobot.assistant

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.SystemClock
import android.provider.AlarmClock
import androidx.core.graphics.drawable.toBitmap
import app.heyrobot.core.Abort
import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Addresses
import app.heyrobot.core.AskWhy
import app.heyrobot.core.AppEntry
import app.heyrobot.core.AppMatcher
import app.heyrobot.core.BrainModel
import app.heyrobot.core.CalendarFormat
import app.heyrobot.core.ClaudeAppLink
import app.heyrobot.core.CalendarPick
import app.heyrobot.core.CheogramRoute
import app.heyrobot.core.ContactIds
import app.heyrobot.core.Durations
import app.heyrobot.core.EmailApp
import app.heyrobot.core.EventPlan
import app.heyrobot.core.LocationCode
import app.heyrobot.core.LookupFallback
import app.heyrobot.core.Events
import app.heyrobot.core.FreeUp
import app.heyrobot.core.SpeechLang
import app.heyrobot.core.StorageWay
import app.heyrobot.core.Choice
import app.heyrobot.core.Executed
import app.heyrobot.core.MailTarget
import app.heyrobot.core.MailTo
import app.heyrobot.core.Nearby
import app.heyrobot.core.NearbyFail
import app.heyrobot.core.NearbyLookup
import app.heyrobot.core.NearestConsent
import app.heyrobot.core.NearestOnline
import app.heyrobot.core.NearestAsk
import app.heyrobot.core.OrganicMapsRoute
import app.heyrobot.core.NoteSearch
import app.heyrobot.core.OpenMeteo
import app.heyrobot.core.PeopleEdit
import app.heyrobot.core.PhonePart
import app.heyrobot.core.PhoneStatus
import app.heyrobot.core.SettingsPage
import app.heyrobot.core.PhoneNumbers
import app.heyrobot.core.Prepared
import app.heyrobot.core.RLog
import app.heyrobot.core.Reach
import app.heyrobot.core.Reachable
import app.heyrobot.core.Read
import app.heyrobot.core.Recipients
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SmsTo
import app.heyrobot.core.TextApp
import app.heyrobot.core.TextTarget
import app.heyrobot.core.TextVia
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.WeatherAsk
import app.heyrobot.core.WeatherFail
import app.heyrobot.core.WeatherPlace
import app.heyrobot.core.WeatherSource
import app.heyrobot.core.WeatherText
import app.heyrobot.core.WeatherUnits
import app.heyrobot.core.WeatherWhere
import app.heyrobot.core.WhenParser
import app.heyrobot.core.XmppLink
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.Calendars
import app.heyrobot.sys.CheogramIntents
import app.heyrobot.sys.CoarseLocation
import app.heyrobot.sys.NearestAnswers
import app.heyrobot.sys.NominatimClient
import app.heyrobot.sys.OpenMeteoClient
import app.heyrobot.sys.PhoneInfo
import app.heyrobot.sys.SettingsIntents
import app.heyrobot.sys.ClaudeAppIntents
import app.heyrobot.sys.Contacts
import app.heyrobot.sys.HandOffApps
import app.heyrobot.sys.ModelStore
import app.heyrobot.sys.NotesFiles
import app.heyrobot.sys.PeopleFiles
import app.heyrobot.sys.Phones
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

// VOICE PROCESS (the app side, never :brain). Runs an action that already passed the approval gate,
// with intents that need no new permissions: AlarmClock (GrapheneOS Clock handles SET_TIMER with
// EXTRA_SKIP_UI silently, verified in phase 0), the launcher's app list, Organic Maps' om:// URIs (geo:
// as a fallback) and CameraManager's torch. Phase 2: texts and emails are handed to Cheogram, the SMS app or
// Proton Mail as drafts (you press Send there), contacts and the calendar are read with the permissions the main app
// asked for, notes go to Robot Notes. 0.4.0: names are found in Robot's People too (docs/people.md), with or without
// the contacts permission, and the weather comes from Open-Meteo over the internet (this is the process that goes
// online for it). The log records the tool and the outcome, never arguments.

/**
 * [abort]: an online tool's (the weather's) Stop and deadline (OnlineRun); the others run to the end. [roomTokens]: about
 * what is left of the model's context for a tool's answer (PanelController's estimate): the weather gives fewer days.
 */
class ToolExecutor(private val ctx: Context, private val settings: RobotSettings, private val abort: Abort = Abort(),
    private val roomTokens: Int = Int.MAX_VALUE, private val said: String? = null, private val launch: (Intent) -> Boolean) {
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
                else Prepared.Ready(Action.OpenApp(m.app.label, m.app.key), why = unsure(m.loose))
            }
            is Action.SendText -> prepareText(action)
            is Action.DraftEmail -> prepareEmail(action)
            is Action.LookupContact -> if (Contacts.granted(ctx) || PeopleFiles.store(ctx).list().isNotEmpty()) Prepared.Ready(action) else needContacts()
            // 0.8: who to call (People and the contacts, numbers only) and whether it is an emergency number.
            is Action.Call -> app.heyrobot.assistant.car.CallPlacer.prepare(ctx, action, settings)
            is Action.AddEvent -> prepareEvent(action)
            is Action.ReadCalendar -> prepareRead(action)
            is Action.Weather -> prepareWeather(action)
            is Action.FreeMemory -> prepareFreeMemory(action)
            is Action.FreeStorage -> prepareFreeStorage(action)
            // "The nearest …" in the model's words or your own ([said], never screen text), with the setting on (0.4.0).
            // Settings' "Find the nearest place online" (or the panel's newer answer): Ask the first time shows its card.
            is Action.MapsSearch -> Nearby.prepare(action, action.query, nearestWanted(action.query, action.nearest), nearestMode(), settings.nearbyServer)
            is Action.MapsNavigate -> Nearby.prepare(action, action.destination, nearestWanted(action.destination, action.nearest), nearestMode(), settings.nearbyServer)
            // The Claude app: installed or "Install Claude" before any card.
            is Action.AskClaude -> if (ClaudeAppIntents.installed(ctx)) Prepared.Ready(action) else Prepared.Refused(ActionStatus.NO_APP, "Install Claude.")
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
                is Action.MapsSearch -> {
                    keep(action.consent)
                    if (action.nearest) nearest(action.query, SEARCH_DONE) else maps(action.query, SEARCH_DONE)
                }
                // A search, not a route (om://route needs coordinates): say what to tap next. The nearest one: its route.
                is Action.MapsNavigate -> {
                    keep(action.consent)
                    if (action.nearest) nearest(action.destination, NAVIGATE_DONE) else maps(action.destination, NAVIGATE_DONE)
                }
                is Action.Flashlight -> torch(action.on)
                is Action.SendText -> text(action).also { if (it.status.succeeded && action.target?.remember == true) remember(action.to, action.target?.from, email = false) }
                is Action.DraftEmail -> email(action).also { if (it.status.succeeded && action.target?.remember == true) remember(action.to, action.target?.from, email = true) }
                is Action.LookupContact -> lookup(action)
                is Action.Call -> app.heyrobot.assistant.car.CallPlacer.run(ctx, action, ::start)
                is Action.AddNote -> addNote(action)
                is Action.FindNotes -> findNotes(action)
                is Action.AddEvent -> addEvent(action)
                is Action.ReadCalendar -> readCalendar(action)
                is Action.AskClaude -> openClaude(action)
                is Action.Weather -> weather(action)
                is Action.PhoneCheck -> phoneCheck(action)
                is Action.FreeMemory -> freeMemory(action)
                is Action.FreeStorage -> freeStorage(action)
                // 0.7: memory's own (MemoryPanel), never here.
                is Action.Remember, is Action.Forget -> Executed(ActionStatus.FAILED, "Something went wrong.")
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

    /** "The nearest …" in the model's or your words, and only a kind of place or a name (Nearby.sendable): never an address. */
    private fun nearestWanted(what: String, modelSaid: Boolean): Boolean =
        (modelSaid || NearestAsk.inWords(said, what)) && Nearby.sendable(what)

    private fun nearestMode(): NearestOnline = Nearby.mode(settings, NearestAnswers.load(ctx))

    /** The first-use card's lasting answer ("Allow always", or "No" with "Don't ask again"), kept for next time. */
    private fun keep(consent: NearestConsent?) {
        when (consent) {
            NearestConsent.ALWAYS -> NearestAnswers.save(ctx, NearestOnline.ALWAYS)
            NearestConsent.NEVER -> NearestAnswers.save(ctx, NearestOnline.NEVER)
            else -> {}
        }
    }

    /**
     * "Directions to the nearest pharmacy" (0.4.0): where the phone is (coarse location), the Nominatim search server in
     * Settings (OpenStreetMap's unless you chose another) for that kind of place around it (two decimals, two searches at
     * most, a second apart), the closest one, and Organic Maps' route to it. The card's line (saved) names no place; its
     * body (shown, never saved) has the place, how far, and what was sent to which server. Anything in the way (no
     * location, no internet, nothing found): Organic Maps' search, as before, and the card says why. The log gets codes
     * and times, never the place.
     */
    private fun nearest(what: String, searchDone: String): Executed {
        val started = SystemClock.elapsedRealtime()
        var requests = 0
        val server = settings.nearbyServer
        val r = Nearby.lookup(CoarseLocation.granted(ctx), abort,
            locate = { CoarseLocation.find(ctx, Nearby.FIX_MS, abort).fix?.let { it.lat to it.lon } },
            search = { lat, lon -> NominatimClient.search(ctx, what, lat, lon, server, abort) { requests = it } })
        val ms = SystemClock.elapsedRealtime() - started
        if (r is NearbyLookup.Instead) {
            RLog.i("maps_nearest", "found" to false, "why" to r.why, "requests" to requests, "ms" to ms)
            // Stopped, or its time ran out and the panel opens the search itself: only one app ever opens ([Abort.claim]).
            if (abort.cancelled || !abort.claim()) return Executed(ActionStatus.DECLINED, NearbyFail.STOPPED.text + ".")
            val m = maps(what, searchDone)
            return if (m.status.succeeded) m.copy(detail = Nearby.instead(r.why, navigate = searchDone == NAVIGATE_DONE, server = server)) else m
        }
        val f = r as NearbyLookup.Found
        val place = f.place
        if (abort.cancelled || !abort.claim()) return Executed(ActionStatus.DECLINED, NearbyFail.STOPPED.text + ".")
        val opened = start(Intent(Intent.ACTION_VIEW, Uri.parse(OrganicMapsRoute.uri(f.lat, f.lon, START_NAME, place.lat, place.lon, place.name))))
            || start(Intent(Intent.ACTION_VIEW, Uri.parse(OrganicMapsRoute.geo(place.lat, place.lon, place.name))))
        if (!opened) return Executed(ActionStatus.FAILED, "Organic Maps isn't installed.")
        val far = Nearby.distance(f.metres, WeatherUnits.of(region))
        RLog.i("maps_nearest", "found" to true, "places" to f.places, "requests" to requests, "ms" to ms)
        val sent = "Sent to ${server.sentTo}: “$what” near ${Nearby.coord(f.lat)}, ${Nearby.coord(f.lon)}"
        return Executed(ActionStatus.DONE, "Opened Organic Maps' route to the nearest one OpenStreetMap knows, $far away in a straight line.",
            launchedApp = true, results = listOf(mapOf("place" to place.name, "distance" to far)),
            body = listOfNotNull("${place.name} · $far", place.address).joinToString("\n") + "\n\n" + sent + "\n" + Nearby.ATTRIBUTION)
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

    private val region by lazy { settings.regionFor(Phones.deviceRegion(ctx)) }

    /** A name that matched an installed app only loosely: not certain which app. */
    private fun unsure(loose: Boolean): Set<AskWhy> = if (loose) setOf(AskWhy.WHO) else emptySet()

    /** A number's target: its gateway address for Cheogram, the number itself for the SMS app. [from]: the contact's number. */
    private fun numberTarget(name: String?, e164: String, via: TextVia, appName: String, from: Reach? = null): TextTarget? {
        val address = if (via == TextVia.CHEOGRAM) PhoneNumbers.jid(e164, settings.smsGateway) ?: return null else e164
        return TextTarget(name, address, Phones.display(e164, region), appName, via, from = from)
    }

    /** Cheogram's contact list can be opened to pick someone (its ShortcutActivity is exported for a result). */
    private fun canPick(pkg: String?): Boolean = pkg != null && CheogramIntents.canPick(ctx, pkg)

    /** A choice's kind on the card ("Mobile"), and where a learned name came from. */
    private fun kind(r: Reach): String? = listOfNotNull(r.label, if (r.learned) "seen in Cheogram" else null).joinToString(" · ").ifEmpty { null }

    /**
     * After a text or an email opened from a place tapped with "Always use this for …": it is remembered for the name
     * asked ([asked]), which also confirms a person learned from a notification. Nothing else confirms them: opening a
     * draft (even after the card) doesn't, so one tap on a stranger's name never makes them someone you know.
     */
    private fun remember(asked: String, r: Reach?, email: Boolean) {
        r ?: return
        val key = ContactIds.systemId(r.contactId)?.let { Contacts.lookupKey(ctx, it) }
        PeopleFiles.store(ctx).update { PeopleEdit.rememberChoice(it, asked, r, email, key, System.currentTimeMillis()) } ?: RLog.w("people_save")
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
            return Prepared.Ready(a.copy(target = TextTarget(null, to, to, appName, via)), why = setOf(AskWhy.WHO))
        }
        if (Addresses.isPhone(to)) {
            val t = Phones.e164(to, region)?.let { numberTarget(null, it, via, appName) }
                ?: return Prepared.Refused(ActionStatus.INVALID, "That phone number couldn't be read.")
            return Prepared.Ready(a.copy(target = t), why = setOf(AskWhy.WHO))
        }
        // People, and the phone's contacts when Robot may read them.
        val book = PeopleFiles.book(ctx)
        val all = book.best(to, max = Int.MAX_VALUE, reach = Reachable.text(xmppOk = via == TextVia.CHEOGRAM))
        if (all.isEmpty()) {
            val granted = Contacts.granted(ctx)
            if (!granted && via != TextVia.CHEOGRAM) return needContacts()
            // Not in the contacts: with Cheogram, the person may be in its own roster (the card offers its list, which
            // needs no permission). Contacts Robot may not read: the card also offers to allow them.
            return Recipients.notFound(a, if (granted) "No contact matches “$to”." else "No one in People matches “$to”, and Robot can't read your contacts.",
                via, pick = canPick(cheogram), allowContacts = !granted)
        }
        val matches = all.take(Recipients.MAX_PEOPLE)
        val options = Recipients.forText(book.cards(matches) { Contacts.cards(ctx, it) }, xmppOk = via == TextVia.CHEOGRAM).mapNotNull { r ->
            val t = if (r.xmpp) TextTarget(r.name, r.address, r.address, appName, via, from = r)
            else Phones.e164(r.address, region)?.let { numberTarget(r.name, it, via, appName, r) }
            t?.let {
                Recipients.Option(r.name, kind(r), it.display, a.copy(target = it), learned = r.learned,
                    remember = if (settings.rememberPicks) a.copy(target = it.copy(remember = true)) else null)
            }
        }.distinctBy { (it.action as Action.SendText).target?.address }.let { Recipients.trusted(matches, it) }
        // Fixed wording: the contacts' names are not sent to the model here.
        if (options.isEmpty()) {
            return Recipients.notFound(a, "No matching contact has a phone number${if (via == TextVia.CHEOGRAM) " or XMPP address" else ""}.", via,
                pick = canPick(cheogram), allowContacts = !Contacts.granted(ctx))
        }
        return Recipients.prepare(a, matches, options, all.size - matches.size, Recipients.rememberLabel(to)) { name -> a.copy(to = name) }
    }

    private fun prepareEmail(a: Action.DraftEmail): Prepared {
        if (!HandOffApps.resolves(ctx, HandOffApps.mailIntent())) return Prepared.Refused(ActionStatus.NO_APP, "No email app is installed.")
        val proton = HandOffApps.proton(ctx)
        val appName = if (settings.emailAppFor(proton) == EmailApp.PROTON && proton) "Proton Mail" else null
        val to = a.to.trim()
        // An address the model wrote: the card always shows it.
        if (Addresses.isAddress(to)) return Prepared.Ready(a.copy(target = MailTarget(null, to, appName)), why = setOf(AskWhy.WHO))
        val book = PeopleFiles.book(ctx)
        val all = book.best(to, max = Int.MAX_VALUE, reach = Reachable.email)
        if (all.isEmpty()) return if (Contacts.granted(ctx)) Prepared.Refused(ActionStatus.NOT_FOUND, "No contact matches “$to”.") else needContacts()
        val matches = all.take(Recipients.MAX_PEOPLE)
        val options = Recipients.forEmail(book.cards(matches) { Contacts.cards(ctx, it) }).map { r ->
            val t = MailTarget(r.name, r.address, appName, from = r)
            Recipients.Option(r.name, kind(r), r.address, a.copy(target = t), learned = r.learned,
                remember = if (settings.rememberPicks) a.copy(target = t.copy(remember = true)) else null)
        }.let { Recipients.trusted(matches, it) }
        if (options.isEmpty()) return Prepared.Refused(ActionStatus.NOT_FOUND, "No matching contact has an email address.")
        return Recipients.prepare(a, matches, options, all.size - matches.size, Recipients.rememberLabel(to)) { name -> a.copy(to = name) }
    }

    /**
     * Cheogram (or the SMS app) opens with the text in its compose box: nothing is sent until you press Send. An address
     * picked in Cheogram's contact list, with its account, opens that account's chat directly (StartConversationActivity
     * with the "account" extra: it finds the chat among that account's contacts, so only a picked address goes this way).
     * Everything else, or a picked one that doesn't open, goes by the xmpp: link as before (Cheogram's own route for it,
     * ShareViaAccountActivity, opens the chat for any address, and asks which account when there are several). With no
     * contact to text: Cheogram's contact list to pick someone (CheogramPickActivity), or its share screen
     * (ShareWithActivity, exported for SEND text/plain), where you pick the person and the text is in their chat's box.
     * The voice log gets the way it went, never who.
     */
    private fun text(a: Action.SendText): Executed {
        val t = a.target ?: return Executed(ActionStatus.FAILED, "Robot didn't find who to text.")
        if (t.via == TextVia.SMS) {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse(SmsTo.uri(t.address))).putExtra("sms_body", a.message)
            return if (start(i)) Executed(ActionStatus.DRAFT_OPENED, "Opened a draft in ${t.appName}: nothing is sent until Send is pressed there.", launchedApp = true)
            else Executed(ActionStatus.NO_APP, "No SMS app is installed.")
        }
        val pkg = HandOffApps.cheogram(ctx) ?: return Executed(ActionStatus.NO_APP, "Install Cheogram.")
        // In order; the first that opens.
        val routes: List<Pair<CheogramRoute, Intent>> = when (t.via) {
            TextVia.CHEOGRAM -> listOfNotNull(
                t.account?.let { CheogramRoute.ACCOUNT to CheogramIntents.chat(pkg, t.address, it, a.message) },
                CheogramRoute.LINK to CheogramIntents.link(pkg, t.address, a.message),
            )
            TextVia.CHEOGRAM_LIST -> listOf(CheogramRoute.LIST to CheogramPickActivity.intent(ctx, pkg, a.to, a.message, settings.rememberPicks))
            // Its share screen by name, resolved first (start); a build without that screen: the share to the package.
            TextVia.CHEOGRAM_PICK -> listOf(CheogramRoute.SHARE to CheogramIntents.share(pkg, a.message), CheogramRoute.SHARE to CheogramIntents.shareAny(pkg, a.message))
            TextVia.SMS -> emptyList()
        }
        val i = routes.indexOfFirst { start(it.second) }
        if (i < 0) return Executed(ActionStatus.NO_APP, "Install Cheogram.")
        VoiceLog.add(VoiceEvent.CHEOGRAM_HANDOFF, routes[i].first, if (i > 0) 1 else 0)
        // A draft, not a sent text. A draft already in that chat: the account's chat keeps it instead of this one (Cheogram
        // says "already drafting"); the link's route adds this text after it (Cheogram's source). The card says which.
        val detail = when {
            t.via == TextVia.CHEOGRAM && routes[i].first == CheogramRoute.ACCOUNT -> "Opened a draft in Cheogram: nothing is sent until Send is " +
                "pressed there. If that chat already had a draft, Cheogram keeps the old one."
            t.via == TextVia.CHEOGRAM -> "Opened a draft in Cheogram: nothing is sent until Send is pressed there. With several Cheogram " +
                "accounts, choose the account first. If that chat already had a draft, the text is added after it."
            t.via == TextVia.CHEOGRAM_LIST -> "Opened Cheogram's contact list: pick who it goes to, and their chat opens with the message. " +
                "Nothing is sent until Send is pressed there."
            else -> "Opened Cheogram with the message: choose who it goes to there. Nothing is sent until Send is pressed."
        }
        return Executed(ActionStatus.DRAFT_OPENED, detail, launchedApp = true)
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
        val book = PeopleFiles.book(ctx)
        val needed = Executed(ActionStatus.NEEDS_PERMISSION, "Allow Robot to read your contacts in the Robot app.")
        if (!Contacts.granted(ctx) && book.people.isEmpty()) return needed
        val matches = book.best(a.name)
        if (matches.isEmpty()) {
            if (!Contacts.granted(ctx)) return needed
            // With Cheogram as the texting app the person may be in its own list: the model is told so (a fixed code),
            // and the card offers to open Cheogram.
            val cheogram = HandOffApps.cheogram(ctx)
            val f = LookupFallback.notFound(a.name, settings.textAppFor(cheogram != null), cheogram != null)
            return Executed(ActionStatus.NOT_FOUND, f.detail, results = emptyList(), forModel = f.forModel, handOff = if (f.openCheogram) cheogram else null)
        }
        val cards = book.cards(matches) { Contacts.cards(ctx, it) }
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

    // ------------------------------------------------------------------ weather

    /**
     * Before the card: the days (refused here if they can't be read or aren't in the forecast) and where. Nothing goes
     * online yet. "Here" without location access falls back on your Home for weather; with neither, the card asks you
     * to allow location (Allow opens the Robot app, which asks) or to set a Home.
     */
    private fun prepareWeather(a: Action.Weather): Prepared {
        val range = when (val r = WeatherAsk.days(a.day, ZonedDateTime.now())) {
            is Read.Unreadable -> return Prepared.Refused(ActionStatus.INVALID, r.detail)
            is Read.Ok -> r.value
        }
        if (!Assistant.granted(ctx, Manifest.permission.INTERNET)) return Prepared.Refused(ActionStatus.FAILED, WeatherFail.NO_NETWORK_PERMISSION.text)
        val where = WeatherAsk.where(a.place).first
        if (where == WeatherWhere.HOME && settings.weatherHome == null) return Prepared.Refused(ActionStatus.FAILED, WeatherFail.NO_HOME.text)
        if (where == WeatherWhere.HERE && !CoarseLocation.granted(ctx) && settings.weatherHome == null) {
            return Prepared.Refused(ActionStatus.NEEDS_PERMISSION, "To check the weather where you are, allow location for Robot in the Robot app, " +
                "or set a Home for weather in Robot's settings. You can also name a town.")
        }
        return Prepared.Ready(a.copy(where = where, range = range))
    }

    /**
     * Finds the place (Open-Meteo's geocoding for a named one, your Home, or the phone's coarse location), then asks
     * Open-Meteo for the days. The card shows what was sent ("45.51, -73.59") in its body, which isn't saved; the voice
     * log gets how the place was found and the times, never the place.
     */
    private fun weather(a: Action.Weather): Executed {
        val now = ZonedDateTime.now()
        val range = a.range ?: (WeatherAsk.days(a.day, now) as? Read.Ok)?.value ?: return Executed(ActionStatus.INVALID, WeatherAsk.SAY_DAY)
        val units = WeatherUnits.of(region)
        val home = settings.weatherHome
        val started = SystemClock.elapsedRealtime()
        return try {
            val (place, source) = when (a.where ?: WeatherAsk.where(a.place).first) {
                WeatherWhere.NAMED -> OpenMeteoClient.geocode(ctx, WeatherAsk.where(a.place).second ?: a.place.orEmpty(), abort) to WeatherSource.NAMED
                WeatherWhere.HOME -> (home ?: throw OpenMeteoClient.Failure(WeatherFail.NO_HOME)) to WeatherSource.HOME
                WeatherWhere.HERE -> {
                    // With a Home to fall back on, a fix gets only a few seconds.
                    val wait = if (home != null) WeatherAsk.FIX_WITH_HOME_MS else WeatherAsk.FIX_MS
                    val found = if (CoarseLocation.granted(ctx)) CoarseLocation.find(ctx, wait, abort) else null
                    found?.let { VoiceLog.add(VoiceEvent.WEATHER_LOCATION, it.code, it.importance, it.ms) }
                    if (abort.cancelled) throw OpenMeteoClient.Failure(if (abort.expired) WeatherFail.TIMEOUT else WeatherFail.STOPPED)
                    val fix = found?.fix
                    when {
                        fix != null -> WeatherPlace(HERE, fix.lat, fix.lon) to if (fix.fresh) WeatherSource.FIX else WeatherSource.LAST_KNOWN
                        home != null -> home to if (found != null) WeatherSource.HOME_NO_FIX else WeatherSource.HOME
                        found?.code == LocationCode.OFF -> throw OpenMeteoClient.Failure(WeatherFail.LOCATION_OFF)
                        found?.code == LocationCode.BACKGROUND -> throw OpenMeteoClient.Failure(WeatherFail.LOCATION_BACKGROUND)
                        else -> throw OpenMeteoClient.Failure(WeatherFail.NO_LOCATION)
                    }
                }
            }
            val found = SystemClock.elapsedRealtime()
            val f = OpenMeteoClient.forecast(ctx, place, WeatherAsk.fetchDays(range, now), units, abort)
            val report = WeatherText.reportFitting(f, range, WeatherAsk.relative(a.day), now, units, roomTokens - PLACE_TOKENS)
                ?: throw OpenMeteoClient.Failure(WeatherFail.NO_DAYS)
            VoiceLog.add(VoiceEvent.WEATHER, source, if (source == WeatherSource.HOME) 0 else (found - started).toInt(), (SystemClock.elapsedRealtime() - found).toInt())
            // The card's line is saved with the conversation, so it names no place; the place goes in the results (for
            // the model) and the body (shown, never saved), with what Open-Meteo was sent.
            val (detail, named) = when (source) {
                WeatherSource.FIX, WeatherSource.LAST_KNOWN -> "Forecast for where you are, from Open-Meteo." to HERE
                WeatherSource.HOME -> "Forecast for your Home for weather, from Open-Meteo." to place.name
                WeatherSource.HOME_NO_FIX -> "Robot couldn't tell where you are: forecast for your Home for weather, from Open-Meteo." to place.name
                WeatherSource.NAMED -> "Forecast from Open-Meteo." to place.name
            }
            val coords = "${OpenMeteo.coord(place.lat)}, ${OpenMeteo.coord(place.lon)}"
            val sent = if (source == WeatherSource.NAMED) "Sent to Open-Meteo: “${WeatherAsk.where(a.place).second ?: a.place}” (to find it), then $coords"
            else "Sent to Open-Meteo: $coords"
            Executed(ActionStatus.DONE, detail, results = listOf(mapOf("place" to named)) + report.results,
                body = named.replaceFirstChar { it.uppercase() } + "\n" + report.lines + "\n\n" + sent + "\n" + OpenMeteo.ATTRIBUTION,
                spoken = "${named.replaceFirstChar { it.uppercase() }}. ${report.spoken}")
        } catch (e: OpenMeteoClient.Failure) {
            // Once the caller has given up (Stop, the deadline) it logs why; this result is dropped.
            if (!abort.cancelled) VoiceLog.add(VoiceEvent.WEATHER_FAILED, e.why, e.http)
            Executed(if (e.why == WeatherFail.NO_PLACE) ActionStatus.NOT_FOUND else ActionStatus.FAILED, e.text)
        }
    }

    /**
     * 0.8, the morning brief (core BriefComposer): today's forecast where the phone is, else your Home for weather, as the
     * weather tool finds it, in the units of your region. The place's name only for your Home (where the phone is has no
     * name offline); the brief says it only unlocked. Null: no place, or Open-Meteo didn't answer (the brief says the
     * weather isn't available). Blocking; [abort]'s deadline ends it.
     */
    fun briefWeather(): app.heyrobot.core.BriefWeather? {
        val now = ZonedDateTime.now()
        val units = WeatherUnits.of(region)
        val home = settings.weatherHome
        return try {
            val found = if (CoarseLocation.granted(ctx)) CoarseLocation.find(ctx, if (home != null) WeatherAsk.FIX_WITH_HOME_MS else WeatherAsk.FIX_MS, abort) else null
            val fix = found?.fix
            val place = when {
                fix != null -> WeatherPlace(HERE, fix.lat, fix.lon)
                home != null -> home
                else -> return null
            }
            val f = OpenMeteoClient.forecast(ctx, place, 1, units, abort)
            val today = f.days.firstOrNull { it.date == now.withZoneSameInstant(f.zone ?: now.zone).toLocalDate() } ?: f.days.firstOrNull()
            VoiceLog.add(VoiceEvent.WEATHER, if (fix != null) WeatherSource.FIX else WeatherSource.HOME, 0, 0)
            app.heyrobot.core.BriefWeather(f.now, today, units, place = if (fix == null) home?.name else null)
        } catch (e: OpenMeteoClient.Failure) {
            if (!abort.cancelled) VoiceLog.add(VoiceEvent.WEATHER_FAILED, e.why, e.http)
            null
        } catch (t: Throwable) {
            RLog.w("brief_weather", t)
            null
        }
    }

    // ------------------------------------------------------------------ the phone itself

    /**
     * phone_status: read on the phone; nothing leaves it. Storage comes with the apps using the most space and the media
     * sizes when Usage access is on, and the card offers Android's storage settings; without it, to turn Usage access on.
     */
    private fun phoneCheck(a: Action.PhoneCheck): Executed {
        val brain = BrainKeeper.state.value
        val f = PhoneInfo.read(ctx, a.part, robotModelLoaded = brain.readiness == BrainKeeper.Readiness.READY, robotModelBytes = modelBytes(brain.model))
        val results = PhoneStatus.results(f, a.part, WeatherUnits.of(region))
        if (results.isEmpty()) return Executed(ActionStatus.FAILED, "Robot couldn't read that on this phone.")
        val lines = PhoneStatus.lines(results)
        val storage = a.part == PhonePart.STORAGE || a.part == PhonePart.ALL
        val page = if (!storage) null else if (f.usageAccess) SettingsPage.STORAGE else SettingsPage.USAGE_ACCESS
        return Executed(ActionStatus.DONE, "Read on this phone; nothing left it.", results = results, body = lines, spoken = lines.replace("\n", ". "), handOff = page)
    }

    /** What the model in memory holds, about (0.4.1: Fast 2 GB, an E4B 4 GB, your own file about its size). */
    private fun modelBytes(m: BrainModel?): Long = when {
        m == null -> PhoneStatus.ROBOT_MODEL_BYTES
        m.memoryBytes > 0 -> m.memoryBytes
        else -> ModelStore.file(ctx, m).length().takeIf { it > 0 } ?: PhoneStatus.ROBOT_MODEL_BYTES
    }

    /**
     * free_memory for an app you named: the installed app it means (a loose match shows the card even under "Just do it").
     * Robot itself is refused: its own memory is freed by unloading its model.
     */
    private fun prepareFreeMemory(a: Action.FreeMemory): Prepared {
        val name = a.app ?: return prepareUnload(a)
        val m = AppMatcher.match(name, launcherApps()) ?: return Prepared.Refused(ActionStatus.FAILED, "No app called “$name” is installed.")
        val pkg = m.app.key.substringBefore('/')
        if (pkg == ctx.packageName) return Prepared.Refused(ActionStatus.FAILED, "Robot can't stop itself. Say “free up memory” to unload its model.")
        return Prepared.Ready(Action.FreeMemory(m.app.label, pkg), why = unsure(m.loose))
    }

    /**
     * 0.7.0, free_memory with no app ("free up memory", "close apps"): a card to pick from, in the language you asked in.
     * Unloading Robot's model (about how much it holds), then the apps used lately that are likely still in memory (Usage
     * access; core FreeUp.likelyInMemory), each opening its App info where you tap Force stop; without Usage access, a
     * button to turn it on. Robot never stops an app itself (Android 14 and later let no app stop another).
     */
    private fun prepareUnload(a: Action.FreeMemory): Prepared {
        val fr = said?.let(SpeechLang::french) == true
        val now = System.currentTimeMillis()
        val brain = BrainKeeper.state.value
        val loaded = brain.readiness == BrainKeeper.Readiness.READY || brain.readiness == BrainKeeper.Readiness.LOADING
        val recent = PhoneInfo.recentInMemory(ctx)
        val choices = buildList {
            add(Choice(FreeUp.unloadChoice(if (loaded) modelBytes(brain.model) else null, fr), Action.FreeMemory(null)))
            recent?.forEach { add(Choice(FreeUp.appChoice(it, now, fr), Action.FreeMemory(it.label, it.pkg))) }
            if (recent == null) add(Choice(FreeUp.usageAccessChoice(fr), Action.FreeMemory(Action.FreeMemory.USAGE_ACCESS_LABEL, SettingsPage.USAGE_ACCESS)))
        }
        return Prepared.Ready(a, choices = choices, note = FreeUp.pickNote(fr))
    }

    /**
     * Robot's own model is unloaded once this answer ends (PanelController, ModelUnload): the model is told so, with what
     * Android does by itself and the apps used lately (Usage access), which you may name to stop. Android 14 and later
     * let no app stop another, so an app you name gets its info page, where you tap Force stop. 0.7.0: the card's "Turn on
     * Usage access" opens Android's page for it.
     */
    private fun freeMemory(a: Action.FreeMemory): Executed {
        val pkg = a.pkg
        if (pkg == SettingsPage.USAGE_ACCESS) {
            return if (page(SettingsIntents.of(ctx, SettingsPage.USAGE_ACCESS))) Executed(ActionStatus.DONE,
                "Opened Usage access: turn it on for Robot, then ask again to see the apps you used lately.", launchedApp = true)
            else Executed(ActionStatus.FAILED, "Android's Usage access page didn't open.")
        }
        if (a.app == null || pkg == null) {
            ModelUnload.request(ModelUnload.available(ctx))
            val recent = PhoneInfo.recentApps(ctx)
            val results = listOf(mapOf(
                "memory" to PhoneStatus.memoryLine(PhoneInfo.memory(ctx), robotModel = true, modelBytes(BrainKeeper.state.value.model)),
                "recent_apps" to when {
                    recent.isNotEmpty() -> FreeUp.names(recent) + " (used in the last 2 hours)"
                    PhoneInfo.usageAccess(ctx) -> "none, besides the one in use"
                    else -> "unknown: Robot needs Usage access to see which apps were used lately"
                },
            ))
            return Executed(ActionStatus.DONE, FreeUp.UNLOADING, results = results, forModel = FreeUp.MEMORY_FOR_MODEL)
        }
        return if (page(listOf(SettingsIntents.appInfo(pkg)))) Executed(ActionStatus.DONE, "Opened ${a.app}'s App info: tap Force stop there. It may start again later.", launchedApp = true)
        else Executed(ActionStatus.FAILED, "${a.app}'s App info didn't open.")
    }

    /**
     * free_storage, before the card: Robot's cache is measured (and always asks: it deletes); the apps using the most
     * space (Usage access) or the largest photos and videos (the media permission) become the card's choices, so you
     * pick one, whatever the level. A file's name is on its button only.
     */
    private fun prepareFreeStorage(a: Action.FreeStorage): Prepared = when (a.way) {
        StorageWay.ANDROID -> Prepared.Ready(a)
        StorageWay.ROBOT -> Prepared.Ready(a.copy(bytes = PhoneInfo.robotCache(ctx)), why = setOf(AskWhy.DELETE))
        StorageWay.APPS -> if (!PhoneInfo.usageAccess(ctx)) {
            Prepared.Refused(ActionStatus.NEEDS_PERMISSION, "Turn on Usage access for Robot (Assistant setup in the Robot app) so it can list the apps using the most space.")
        } else {
            // Not Robot: its App info's "Clear storage" would delete its models.
            val top = PhoneStatus.top(PhoneInfo.apps(ctx).filter { it.pkg != ctx.packageName })
            if (top.isEmpty()) Prepared.Refused(ActionStatus.NOT_FOUND, "Robot couldn't read the apps' sizes.")
            else Prepared.Ready(a, why = setOf(AskWhy.PICK), choices = top.map { Choice(FreeUp.appChoice(it), a.copy(target = it.pkg, label = it.label)) })
        }
        StorageWay.FILES -> if (!PhoneInfo.mediaGranted(ctx)) {
            Prepared.Refused(ActionStatus.NEEDS_PERMISSION, "Allow photos and videos for Robot in the Robot app, so it can find the largest ones.")
        } else {
            val zone = ZoneId.systemDefault()
            val files = PhoneInfo.largeMedia(ctx)
            if (files.isEmpty()) Prepared.Refused(ActionStatus.NOT_FOUND,
                if (PhoneInfo.mediaPartial(ctx)) "Robot sees only the photos and videos you picked, and none of them is there." else "No photos or videos found.")
            else Prepared.Ready(a, why = setOf(AskWhy.DELETE), choices = files.map { f ->
                Choice(FreeUp.fileChoice(f, zone), a.copy(target = f.uri, label = if (f.video) "video" else "photo", bytes = f.bytes, summary = FreeUp.fileSummary(f, zone)))
            })
        }
    }

    private fun freeStorage(a: Action.FreeStorage): Executed = when (a.way) {
        StorageWay.ANDROID -> if (page(SettingsIntents.of(ctx, SettingsPage.FREE_UP))) Executed(ActionStatus.DONE, "Opened Android's Free up space screen.", launchedApp = true)
        else Executed(ActionStatus.FAILED, "Android's Free up space screen didn't open.")
        StorageWay.ROBOT -> {
            val freed = PhoneInfo.clearRobotCache(ctx)
            Executed(ActionStatus.DONE, "Cleared ${PhoneStatus.size(freed)} of Robot's cache.",
                forModel = "Cleared ${PhoneStatus.size(freed)} of Robot's cache. Robot's big files are its models (the Model tab in the Robot app), which it needs to work.")
        }
        StorageWay.APPS -> {
            val pkg = a.target
            if (pkg != null && page(listOf(SettingsIntents.appInfo(pkg)))) Executed(ActionStatus.DONE, "Opened ${a.label}'s App info: tap Storage and cache, then Clear cache.", launchedApp = true)
            else Executed(ActionStatus.FAILED, "The app's info page didn't open.")
        }
        // Android's own confirmation decides; Robot never deletes a file itself.
        StorageWay.FILES -> {
            val uri = a.target
            // Like a draft: nothing is deleted until you allow it in Android's dialog; the card says so until it answers.
            if (uri != null && start(MediaDeleteActivity.intent(ctx, Uri.parse(uri)))) Executed(ActionStatus.DRAFT_OPENED, FreeUp.DELETE_WAITING, launchedApp = true)
            else Executed(ActionStatus.FAILED, "Android's delete confirmation didn't open.")
        }
    }

    /** A settings page: the first of [intents] that opens (the Settings app may not be visible to Robot's queries). */
    private fun page(intents: List<Intent>): Boolean = intents.any { i -> try { launch(i) } catch (_: Exception) { false } }

    // ------------------------------------------------------------------ the Claude app

    /**
     * Opens the Claude app with the question by the first way that resolves on this phone (docs/ask-claude.md "The
     * Claude app route"): its claude:// link, its claude.ai link, a share, or just the app with the question on the
     * clipboard. Robot only prefills; you press Send there. The log has the way, never the words.
     */
    private fun openClaude(a: Action.AskClaude): Executed {
        val way = ClaudeAppIntents.choose(ctx) ?: return Executed(ActionStatus.NO_APP, "Install Claude.")
        val text = ClaudeAppLink.cap(a.text)
        if (way == ClaudeAppLink.Way.LAUNCH && text.isNotEmpty()) ClaudeAppIntents.copy(ctx, text)
        val i = ClaudeAppIntents.intent(ctx, way, text) ?: return Executed(ActionStatus.NO_APP, "Install Claude.")
        RLog.i("claude_app", "way" to way)
        if (!start(i)) return Executed(ActionStatus.FAILED, "Claude didn't open.")
        val detail = when {
            text.isEmpty() -> "Opened Claude."
            way == ClaudeAppLink.Way.LAUNCH -> "Opened Claude. Paste your question in Claude: it's on the clipboard."
            else -> "Opened Claude with your question: press Send there if it isn't sent."
        }
        return Executed(ActionStatus.DONE, detail, launchedApp = true)
    }

    private companion object {
        const val ICON_PX = 96
        /** The place name of a forecast for where the phone is (Open-Meteo has no reverse lookup, and none is wanted). */
        const val HERE = "where you are"
        /** The weather's status, detail and place beside its days, about. */
        const val PLACE_TOKENS = 60
        const val SEARCH_DONE = "Opened Organic Maps search"
        const val NAVIGATE_DONE = "Opened an Organic Maps search for it: pick the place, then tap Route for directions"
        /** The route's start in Organic Maps: where Robot found the phone (approximate; Organic Maps' link has no "my position"). */
        const val START_NAME = "Your position (approximate)"
    }
}
