// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.i18n.LockStrings
import app.heyrobot.lock.PhoneLockNow
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
import app.heyrobot.R
import app.heyrobot.core.Abort
import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Addresses
import app.heyrobot.core.AskWhy
import app.heyrobot.core.AppEntry
import app.heyrobot.core.AppMatcher
import app.heyrobot.core.BrainModel
import app.heyrobot.core.CalendarFormat
import app.heyrobot.core.AppHandOff
import app.heyrobot.core.ClaudeAppLink
import app.heyrobot.core.helpers.HelperSettings
import app.heyrobot.core.CalendarPick
import app.heyrobot.core.CheogramRoute
import app.heyrobot.core.ContactIds
import app.heyrobot.core.EmailApp
import app.heyrobot.core.EventAlert
import app.heyrobot.core.EventPlan
import app.heyrobot.core.EventText
import app.heyrobot.core.LocationCode
import app.heyrobot.core.LookupFallback
import app.heyrobot.core.Events
import app.heyrobot.core.FreeUp
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
import app.heyrobot.core.SavedPlace
import app.heyrobot.core.car.CarNavigate
import app.heyrobot.core.car.NavDestination
import app.heyrobot.core.i18n.HandsFreeStrings
import app.heyrobot.sys.HandsFreeFiles
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
import app.heyrobot.core.i18n.InfoStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.ToolStrings
import app.heyrobot.core.i18n.TurnStrings
import app.heyrobot.core.i18n.UiLabel
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.web.QueryContext
import app.heyrobot.helpers.web.WebStep
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.Calendars
import app.heyrobot.sys.CheogramIntents
import app.heyrobot.sys.CoarseLocation
import app.heyrobot.sys.NearestAnswers
import app.heyrobot.sys.NominatimClient
import app.heyrobot.sys.OpenMeteoClient
import app.heyrobot.sys.PhoneInfo
import app.heyrobot.sys.SettingsIntents
import app.heyrobot.sys.HandOffIntents
import app.heyrobot.sys.Contacts
import app.heyrobot.sys.HandOffApps
import app.heyrobot.sys.ModelStore
import app.heyrobot.sys.NotesFiles
import app.heyrobot.sys.PeopleFiles
import app.heyrobot.sys.Phones
import java.time.ZoneId
import java.time.ZonedDateTime

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
    private val roomTokens: Int = Int.MAX_VALUE, private val said: String? = null,
    /** 0.9 web search: where the conversation's words came from (QueryCheck), and what a search is doing (the panel's status). */
    private val webContext: () -> QueryContext = { QueryContext(said = listOfNotNull(said)) },
    private val webStep: (WebStep, String) -> Unit = { _, _ -> },
    /** 1.1: the turn's reply language, whose date and time words add_event and read_calendar read first (core WhenParser). */
    private val lang: Lang = Lang.EN,
    private val launch: (Intent) -> Boolean) {
    private val web by lazy { WebTool(ctx, settings, abort, said, webContext, webStep) }

    /**
     * Before the gate and the card: what the action will really do. open_app becomes the installed app it will open
     * (its real label and launcher activity); send_text and draft_email the person, number or address it goes to
     * (or the choices, when several fit); add_event its dates and calendar; read_calendar its day or week. What
     * can't be done is refused here, before any card.
     */
    /**
     * 1.1, Phone lock (§6): during a lock, a call goes only to the People every active lock lets you call (Calls to People
     * you choose), matched by their numbers in People; emergency numbers always. Outside a lock, [p] as it is.
     */
    private fun lockCalls(p: Prepared): Prepared {
        if (!PhoneLockNow.on(ctx)) return p
        val numbers = PhoneLockNow.callKeys(ctx)
        fun ok(a: Action) = a is Action.Call && (a.emergency || a.number?.let { PhoneLockNow.callKey(it) in numbers } == true)
        val closed = Prepared.Refused(ActionStatus.NEEDS_UNLOCK, LockStrings.callClosed())
        if (p !is Prepared.Ready) return p
        if (p.choices.isEmpty()) return if (ok(p.action)) p else closed
        val kept = p.choices.filter { ok(it.action) }
        return when {
            kept.isEmpty() -> closed
            ok(p.action) -> p.copy(choices = kept)
            else -> p.copy(action = kept.first().action, choices = kept)
        }
    }

    fun prepare(action: Action): Prepared = try {
        when (action) {
            is Action.OpenApp -> {
                val m = AppMatcher.match(action.name, launcherApps())
                if (m == null) Prepared.Refused(ActionStatus.FAILED, ToolStrings.noAppCalled("name" to action.name))
                // 1.1, Phone lock (§6): only the apps the active locks leave open.
                else if (PhoneLockNow.on(ctx) && m.app.key.substringBefore('/') !in PhoneLockNow.apps(ctx))
                    Prepared.Refused(ActionStatus.NEEDS_UNLOCK, LockStrings.appClosed())
                else Prepared.Ready(Action.OpenApp(m.app.label, m.app.key), why = unsure(m.loose))
            }
            is Action.SendText -> prepareText(action)
            is Action.DraftEmail -> prepareEmail(action)
            is Action.LookupContact -> if (Contacts.granted(ctx) || PeopleFiles.store(ctx).list().isNotEmpty()) Prepared.Ready(action) else needContacts()
            // 0.8: who to call (People and the contacts, numbers only) and whether it is an emergency number.
            is Action.Call -> lockCalls(app.heyrobot.assistant.car.CallPlacer.prepare(ctx, action, settings))
            is Action.AddEvent -> prepareEvent(action)
            is Action.ReadCalendar -> prepareRead(action)
            is Action.Weather -> prepareWeather(action)
            is Action.FreeMemory -> prepareFreeMemory(action)
            is Action.FreeStorage -> prepareFreeStorage(action)
            // "The nearest …" in the model's words or your own ([said], never screen text), with the setting on (0.4.0).
            // Settings' "Find the nearest place online" (or the panel's newer answer): Ask the first time shows its card.
            is Action.MapsSearch -> Nearby.prepare(action, action.query, nearestWanted(action.query, action.nearest), nearestMode(), settings.nearbyServer)
            is Action.MapsNavigate -> SavedPlace.of(action.destination)?.takeIf { !action.nearest }?.let { prepareSaved(action, it) }
                ?: Nearby.prepare(action, action.destination, nearestWanted(action.destination, action.nearest), nearestMode(), settings.nearbyServer)
            // A helper's app (1.0.0, any helper with one): which app from your settings, installed or "Install …" before any
            // card, and the card says what goes and that you press Send there.
            is Action.AskHelper -> prepareHandOff(action)
            is Action.WebSearch -> web.prepare(action)
            // 1.0: a search in your browser: your own words open at once, the model's show their card (core web BrowserGate).
            is app.heyrobot.core.BrowserSearchAction -> BrowserSearchTool.prepare(ctx, action, said, webContext())
            // 1.4, Robot Files (F9): refused while its files can't be read (a phone lock, a locked phone, Robot Files off).
            is Action.FindFiles, is Action.ReadFile -> PanelFileTools.prepare(action, app.heyrobot.sys.FilesIntake.gate(ctx))
            else -> Prepared.Ready(action)
        }
    } catch (t: Throwable) {
        RLog.w("tool_prepare", t, "tool" to action.tool)
        Prepared.Refused(ActionStatus.FAILED, ToolStrings.somethingWrong())
    }

    fun run(action: Action): Executed {
        val r = try {
            when (action) {
                is Action.SetTimer -> timer(action.seconds)
                is Action.SetAlarm -> alarm(action.hour, action.minute)
                is Action.OpenApp -> openApp(action)
                is Action.MapsSearch -> {
                    keep(action.consent)
                    if (action.nearest) nearest(action.query, navigate = false) else maps(action.query, navigate = false)
                }
                // A search, not a route (om://route needs coordinates): say what to tap next. The nearest one: its route.
                is Action.MapsNavigate -> {
                    keep(action.consent)
                    val saved = if (action.nearest) null else SavedPlace.of(action.destination)
                    when {
                        saved != null -> savedRoute(saved)
                        action.nearest -> nearest(action.destination, navigate = true)
                        else -> maps(action.destination, navigate = true)
                    }
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
                is Action.AskHelper -> openHelperApp(action)
                is Action.Weather -> weather(action)
                is Action.PhoneCheck -> phoneCheck(action)
                is Action.FreeMemory -> freeMemory(action)
                is Action.FreeStorage -> freeStorage(action)
                // 0.7: memory's own (MemoryPanel), never here.
                is Action.Remember, is Action.Forget -> Executed(ActionStatus.FAILED, ToolStrings.somethingWrong())
                is Action.WebSearch -> web.run(action)
                // 1.0: the engine's own address in your browser (ACTION_VIEW); Robot sends nothing and reads nothing.
                is app.heyrobot.core.BrowserSearchAction -> BrowserSearchTool.open(ctx, action, launch)
                // 0.9: a Termux run waits for its result and Stop ends it: TermuxTool.run (PanelController), never here.
                is app.heyrobot.core.TermuxCommandAction, is app.heyrobot.core.TermuxScriptAction -> Executed(ActionStatus.FAILED, ToolStrings.somethingWrong())
                // 1.4, Robot Files (F9): reads of the sealed index behind its lock gate; read_file ends the turn (PanelFileAnswer).
                is Action.FindFiles -> PanelFileTools.find(action, { app.heyrobot.sys.FilesIntake.gate(ctx) }, { app.heyrobot.sys.RobotFiles.shown(ctx) })
                is Action.ReadFile -> PanelFileTools.read(action, { app.heyrobot.sys.FilesIntake.gate(ctx) }, { app.heyrobot.sys.RobotFiles.shown(ctx) })
            }
        } catch (t: Throwable) {
            RLog.w("tool_run", t, "tool" to action.tool)
            Executed(ActionStatus.FAILED, ToolStrings.somethingWrong())
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
        return if (start(i)) Executed(ActionStatus.DONE, ToolStrings.timerSet("duration" to app.heyrobot.core.i18n.Arg.Seconds(seconds)))
        else Executed(ActionStatus.FAILED, ToolStrings.noClockTimers())
    }

    private fun alarm(hour: Int, minute: Int): Executed {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Robot")
        return if (start(i)) Executed(ActionStatus.DONE, ToolStrings.alarmSet("time" to app.heyrobot.core.EventText.clock(hour, minute)))
        else Executed(ActionStatus.FAILED, ToolStrings.noClockAlarms())
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
            ?: return Executed(ActionStatus.FAILED, ToolStrings.noAppCalled("name" to a.name))
        val (pkg, cls) = entry.key.split('/', limit = 2)
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(pkg, cls)
            .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return if (start(i)) Executed(ActionStatus.DONE, ToolStrings.opened("appName" to entry.label), launchedApp = true)
        else Executed(ActionStatus.FAILED, ToolStrings.didntOpen("appName" to entry.label))
    }

    /** Organic Maps' search for [query]; [navigate]: the card says to tap Route for directions. */
    private fun maps(query: String, navigate: Boolean): Executed {
        val q = Uri.encode(query)
        for (uri in listOf("om://search?query=$q", "geo:0,0?q=$q")) {
            if (start(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))) {
                return Executed(ActionStatus.DONE, if (navigate) ToolStrings.mapsOpenedRoute() else ToolStrings.mapsOpened(), launchedApp = true)
            }
        }
        return Executed(ActionStatus.FAILED, ToolStrings.noOrganicMaps())
    }

    /**
     * 1.1 (Skills, D4): "the route home" is the route to your Home or Work saved in Settings › Car mode, as car mode opens
     * it (core car CarNavigate): none saved, nothing opens and the card says where to save it. During a phone lock, only
     * when every active lock leaves Maps open.
     */
    private fun prepareSaved(a: Action.MapsNavigate, dest: NavDestination): Prepared {
        val home = dest == NavDestination.Home
        val s = HandsFreeFiles.store(ctx).carSettings()
        if ((if (home) s.home else s.work) == null) return Prepared.Refused(ActionStatus.NOT_FOUND, CarNavigate.noPlaceText(home))
        if (PhoneLockNow.on(ctx) && !PhoneLockNow.maps(ctx)) return Prepared.Refused(ActionStatus.NEEDS_UNLOCK, LockStrings.appClosed())
        return Prepared.Ready(a)
    }

    /** Organic Maps' route from where the phone is (when Robot may know it) to the saved place, else the place itself. */
    private fun savedRoute(dest: NavDestination): Executed {
        val home = dest == NavDestination.Home
        val s = HandsFreeFiles.store(ctx).carSettings()
        val p = (if (home) s.home else s.work) ?: return Executed(ActionStatus.NOT_FOUND, CarNavigate.noPlaceText(home))
        val here = if (CoarseLocation.granted(ctx)) CoarseLocation.find(ctx, ROUTE_FIX_MS, abort).fix else null
        val uri = here?.let { OrganicMapsRoute.uri(it.lat, it.lon, ctx.getString(R.string.card_your_position), p.lat, p.lon, p.label) }
            ?: OrganicMapsRoute.geo(p.lat, p.lon, p.label)
        if (!start(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))) return Executed(ActionStatus.FAILED, ToolStrings.noOrganicMaps())
        return Executed(ActionStatus.DONE, HandsFreeStrings.routeReady(), launchedApp = true)
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
    private fun nearest(what: String, navigate: Boolean): Executed {
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
            if (abort.cancelled || !abort.claim()) return Executed(ActionStatus.DECLINED, TurnStrings.stopped())
            val m = maps(what, navigate)
            if (!m.status.succeeded) return m
            val instead = Nearby.insteadText(r.why, navigate = navigate, server = server)
            return m.copy(detail = instead.en(), text = instead)
        }
        val f = r as NearbyLookup.Found
        val place = f.place
        if (abort.cancelled || !abort.claim()) return Executed(ActionStatus.DECLINED, TurnStrings.stopped())
        val opened = start(Intent(Intent.ACTION_VIEW, Uri.parse(OrganicMapsRoute.uri(f.lat, f.lon, ctx.getString(R.string.card_your_position), place.lat, place.lon, place.name))))
            || start(Intent(Intent.ACTION_VIEW, Uri.parse(OrganicMapsRoute.geo(place.lat, place.lon, place.name))))
        if (!opened) return Executed(ActionStatus.FAILED, ToolStrings.noOrganicMaps())
        val far = Nearby.distanceText(f.metres, WeatherUnits.of(region))
        RLog.i("maps_nearest", "found" to true, "places" to f.places, "requests" to requests, "ms" to ms)
        val sent = ToolStrings.sentNear("server" to server.sentTo, "what" to what, "lat" to Nearby.coord(f.lat), "lon" to Nearby.coord(f.lon))
        val where = Phrase.Join(listOfNotNull(ToolStrings.placeFar("place" to place.name, "far" to far), place.address?.let(Phrase::plain)), "\n")
        return Executed(ActionStatus.DONE, ToolStrings.nearestOpened("far" to far),
            launchedApp = true, results = listOf(mapOf("place" to place.name, "distance" to far.en())),
            body = Phrase.Join(listOf(where, Phrase.Join(listOf(sent, Phrase.plain(Nearby.ATTRIBUTION)), "\n")), "\n\n"))
    }

    private fun torch(on: Boolean): Executed {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { id ->
            val c = cm.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return Executed(ActionStatus.FAILED, ToolStrings.noFlashlight())
        cm.setTorchMode(id, on)
        return Executed(ActionStatus.DONE, if (on) ToolStrings.flashlightIsOn() else ToolStrings.flashlightIsOff())
    }

    // ------------------------------------------------------------------ people

    private fun needContacts() = Prepared.Refused(ActionStatus.NEEDS_PERMISSION, ToolStrings.allowContacts())

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

    /** A choice's kind on the card ("Mobile"), and where a learned name came from; null: neither. */
    private fun kind(r: Reach): Phrase? = listOfNotNull(r.label?.let { app.heyrobot.core.People.labelText(it) }, if (r.learned) InfoStrings.seenInCheogram() else null)
        .takeIf { it.isNotEmpty() }?.let { Phrase.Join(it, " · ") }

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
            TextApp.OFF -> return Prepared.Refused(ActionStatus.DISABLED, ToolStrings.textsOff())
            TextApp.CHEOGRAM -> TextVia.CHEOGRAM
            TextApp.SMS -> TextVia.SMS
        }
        if (via == TextVia.CHEOGRAM && cheogram == null) return Prepared.Refused(ActionStatus.NO_APP, ToolStrings.installCheogramOrSms())
        if (via == TextVia.SMS && !HandOffApps.resolves(ctx, HandOffApps.smsIntent())) return Prepared.Refused(ActionStatus.NO_APP, ToolStrings.noSmsApp())
        val appName = if (via == TextVia.CHEOGRAM) "Cheogram" else HandOffApps.smsAppName(ctx) ?: ToolStrings.theSmsApp().en()
        val to = a.to.trim()
        // An address or a number the model wrote: never checked against the contacts (it may have made up digits),
        // so the card always shows it, even when texts may just go.
        if (Addresses.isAddress(to)) {
            if (via == TextVia.SMS) return Prepared.Refused(ActionStatus.FAILED, ToolStrings.smsNoXmpp())
            if (!Addresses.isJid(to)) return Prepared.Refused(ActionStatus.INVALID, ToolStrings.badXmpp())
            return Prepared.Ready(a.copy(target = TextTarget(null, to, to, appName, via)), why = setOf(AskWhy.WHO))
        }
        if (Addresses.isPhone(to)) {
            val t = Phones.e164(to, region)?.let { numberTarget(null, it, via, appName) }
                ?: return Prepared.Refused(ActionStatus.INVALID, ToolStrings.badNumber())
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
            val why = if (granted) InfoStrings.noContact("name" to to) else ToolStrings.noOneInPeople("people" to UiLabel(UiStrings.people), "name" to to)
            return Recipients.notFound(a, why.en(), why, via, pick = canPick(cheogram), allowContacts = !granted)
        }
        val matches = all.take(Recipients.MAX_PEOPLE)
        val options = Recipients.forText(book.cards(matches) { Contacts.cards(ctx, it) }, xmppOk = via == TextVia.CHEOGRAM).mapNotNull { r ->
            val t = if (r.xmpp) TextTarget(r.name, r.address, r.address, appName, via, from = r)
            else Phones.e164(r.address, region)?.let { numberTarget(r.name, it, via, appName, r) }
            t?.let {
                val k = kind(r)
                Recipients.Option(r.name, k?.en(), it.display, a.copy(target = it), learned = r.learned,
                    remember = if (settings.rememberPicks) a.copy(target = it.copy(remember = true)) else null, kindText = k)
            }
        }.distinctBy { (it.action as Action.SendText).target?.address }.let { Recipients.trusted(matches, it) }
        // Fixed wording: the contacts' names are not sent to the model here.
        if (options.isEmpty()) {
            val why = if (via == TextVia.CHEOGRAM) ToolStrings.noNumberOrXmpp() else ToolStrings.noNumber()
            return Recipients.notFound(a, why.en(), why, via, pick = canPick(cheogram), allowContacts = !Contacts.granted(ctx))
        }
        return Recipients.prepare(a, matches, options, all.size - matches.size, Recipients.rememberLabel(to), Recipients.rememberLabelText(to)) { name -> a.copy(to = name) }
    }

    private fun prepareEmail(a: Action.DraftEmail): Prepared {
        if (!HandOffApps.resolves(ctx, HandOffApps.mailIntent())) return Prepared.Refused(ActionStatus.NO_APP, ToolStrings.noEmailApp())
        val proton = HandOffApps.proton(ctx)
        val appName = if (settings.emailAppFor(proton) == EmailApp.PROTON && proton) "Proton Mail" else null
        val to = a.to.trim()
        // An address the model wrote: the card always shows it.
        if (Addresses.isAddress(to)) return Prepared.Ready(a.copy(target = MailTarget(null, to, appName)), why = setOf(AskWhy.WHO))
        val book = PeopleFiles.book(ctx)
        val all = book.best(to, max = Int.MAX_VALUE, reach = Reachable.email)
        if (all.isEmpty()) return if (Contacts.granted(ctx)) Prepared.Refused(ActionStatus.NOT_FOUND, InfoStrings.noContact("name" to to)) else needContacts()
        val matches = all.take(Recipients.MAX_PEOPLE)
        val options = Recipients.forEmail(book.cards(matches) { Contacts.cards(ctx, it) }).map { r ->
            val t = MailTarget(r.name, r.address, appName, from = r)
            val k = kind(r)
            Recipients.Option(r.name, k?.en(), r.address, a.copy(target = t), learned = r.learned,
                remember = if (settings.rememberPicks) a.copy(target = t.copy(remember = true)) else null, kindText = k)
        }.let { Recipients.trusted(matches, it) }
        if (options.isEmpty()) return Prepared.Refused(ActionStatus.NOT_FOUND, ToolStrings.noEmailAddress())
        return Recipients.prepare(a, matches, options, all.size - matches.size, Recipients.rememberLabel(to), Recipients.rememberLabelText(to)) { name -> a.copy(to = name) }
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
        val t = a.target ?: return Executed(ActionStatus.FAILED, ToolStrings.noOneToText())
        if (t.via == TextVia.SMS) {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse(SmsTo.uri(t.address))).putExtra("sms_body", a.message)
            return if (start(i)) Executed(ActionStatus.DRAFT_OPENED, ToolStrings.draftOpened("appName" to t.app), launchedApp = true)
            else Executed(ActionStatus.NO_APP, ToolStrings.noSmsApp())
        }
        val pkg = HandOffApps.cheogram(ctx) ?: return Executed(ActionStatus.NO_APP, ToolStrings.installCheogram())
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
        if (i < 0) return Executed(ActionStatus.NO_APP, ToolStrings.installCheogram())
        VoiceLog.add(VoiceEvent.CHEOGRAM_HANDOFF, routes[i].first, if (i > 0) 1 else 0)
        // A draft, not a sent text. A draft already in that chat: the account's chat keeps it instead of this one (Cheogram
        // says "already drafting"); the link's route adds this text after it (Cheogram's source). The card says which.
        val detail = when {
            t.via == TextVia.CHEOGRAM && routes[i].first == CheogramRoute.ACCOUNT -> ToolStrings.cheogramDraftKept()
            t.via == TextVia.CHEOGRAM -> ToolStrings.cheogramDraftAdded()
            t.via == TextVia.CHEOGRAM_LIST -> ToolStrings.cheogramList()
            else -> ToolStrings.cheogramShare()
        }
        return Executed(ActionStatus.DRAFT_OPENED, detail, launchedApp = true)
    }

    /** Proton Mail (or the app picked in the chooser) opens the draft: mailto: with the same values in the extras. */
    private fun email(a: Action.DraftEmail): Executed {
        val t = a.target ?: return Executed(ActionStatus.FAILED, ToolStrings.noOneToEmail())
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse(MailTo.uri(t.address, a.subject, a.text)))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(t.address))
            .putExtra(Intent.EXTRA_SUBJECT, a.subject)
            .putExtra(Intent.EXTRA_TEXT, a.text)
        val go = if (t.appName != null) i.setPackage(HandOffApps.PROTON) else Intent.createChooser(i, ctx.getString(R.string.card_write_email_with))
        return if (start(go)) Executed(ActionStatus.DRAFT_OPENED, ToolStrings.draftOpened("appName" to (t.appName ?: ToolStrings.theEmailApp())), launchedApp = true)
        else Executed(ActionStatus.NO_APP, ToolStrings.noEmailApp())
    }

    private fun lookup(a: Action.LookupContact): Executed {
        val book = PeopleFiles.book(ctx)
        val needed = Executed(ActionStatus.NEEDS_PERMISSION, ToolStrings.allowContacts())
        if (!Contacts.granted(ctx) && book.people.isEmpty()) return needed
        val matches = book.best(a.name)
        if (matches.isEmpty()) {
            if (!Contacts.granted(ctx)) return needed
            // With Cheogram as the texting app the person may be in its own list: the model is told so (a fixed code),
            // and the card offers to open Cheogram.
            val cheogram = HandOffApps.cheogram(ctx)
            val f = LookupFallback.notFound(a.name, settings.textAppFor(cheogram != null), cheogram != null)
            return Executed(ActionStatus.NOT_FOUND, f.text, results = emptyList(), forModel = f.forModel, handOff = if (f.openCheogram) cheogram else null)
        }
        val cards = book.cards(matches) { Contacts.cards(ctx, it) }
        val results = Recipients.lookup(cards) { raw -> Phones.e164(raw, region)?.let { Phones.display(it, region) } ?: raw }
        val lines = results.joinToString("\n") { r ->
            listOfNotNull(r["name"] as String?, (r["numbers"] as List<*>?)?.joinToString(", "), (r["emails"] as List<*>?)?.joinToString(", "), r["xmpp"] as String?)
                .joinToString(" · ")
        }
        return Executed(ActionStatus.DONE, ToolStrings.foundContacts(results.size), results = results, body = Phrase.plain(lines),
            spoken = Phrase.plain(lines.replace("\n", ". ")))
    }

    // ------------------------------------------------------------------ notes

    private fun addNote(a: Action.AddNote): Executed {
        val n = NotesFiles.store(ctx).add(a.text, a.title, System.currentTimeMillis())
            ?: return Executed(ActionStatus.FAILED, ToolStrings.noteNotSaved())
        return Executed(ActionStatus.DONE, ToolStrings.savedToNotes(), undo = n.id)
    }

    private fun findNotes(a: Action.FindNotes): Executed {
        val found = NoteSearch.find(a.query, NotesFiles.store(ctx).list())
        if (found.isEmpty()) return Executed(ActionStatus.NOT_FOUND, ToolStrings.noNoteMatches("query" to a.query), results = emptyList())
        val results = NoteSearch.results(found)
        val titles = found.joinToString(", ") { it.displayTitle }
        return Executed(ActionStatus.DONE, ToolStrings.foundNotes(found.size), results = results,
            body = Phrase.plain(found.joinToString("\n") { it.displayTitle }), spoken = ToolStrings.found("titles" to titles))
    }

    // ------------------------------------------------------------------ calendar

    private fun prepareEvent(a: Action.AddEvent): Prepared {
        val now = ZonedDateTime.now()
        val span = when (val r = Events.times(a, now, settings.eventMinutes, lang)) {
            is Read.Unreadable -> return Prepared.Refused(ActionStatus.INVALID, r.why)
            is Read.Ok -> r.value
        }
        // Robot adds it itself when it may write to a calendar; otherwise the calendar app's editor opens prefilled.
        val cal = if (Calendars.readGranted(ctx) && Calendars.writeGranted(ctx)) CalendarPick.choose(Calendars.writable(ctx), settings.eventCalendar) else null
        if (cal == null && !HandOffApps.resolves(ctx, Calendars.editorIntent())) {
            return if (!Calendars.writeGranted(ctx)) Prepared.Refused(ActionStatus.NEEDS_PERMISSION, ToolStrings.allowCalendarWrite())
            else Prepared.Refused(ActionStatus.NO_APP, ToolStrings.noCalendarApp())
        }
        // 1.1, a dated reminder: its alert (9:00 on an all-day one's day, else at its time; none once that has passed), and
        // always its card. Without a calendar Robot may write to, the editor can't take the alert: the card says to add it there.
        val alertAt = if (a.alert) EventAlert.at(span, now) else null
        val plan = EventPlan(span.start, span.end!!, span.allDay, cal?.id, cal?.name, now.year, alertAt)
        if (!a.alert) return Prepared.Ready(a.copy(plan = plan))
        return Prepared.Ready(a.copy(plan = plan), why = setOf(AskWhy.REMINDER), noteText = if (cal == null && alertAt != null) ToolStrings.addAlertInApp() else null,
            note = if (cal == null && alertAt != null) ToolStrings.addAlertInApp().en() else null)
    }

    private fun addEvent(a: Action.AddEvent): Executed {
        val p = a.plan ?: return Executed(ActionStatus.FAILED, ToolStrings.couldntReadDate())
        if (p.calendarId != null) {
            // Any provider error (the calendar was removed while the card waited...) falls back to the editor.
            val added = try { Calendars.insert(ctx, a.title, p) } catch (t: Exception) { RLog.w("calendar_insert", t); null }
            // Fixed wording: a shared calendar's name is not the model's business (the card shows it). 1.1: what Robot says
            // is the card's own words (EventText.added), with the alert only when its row went in.
            if (added != null) return Executed(ActionStatus.DONE, ToolStrings.addedToCalendar(), spoken = EventText.added(a.title, p, added.alert))
        }
        val opened = if (p.alertAt != null) ToolStrings.calendarAppOpenedAlert() else ToolStrings.calendarAppOpened()
        return if (start(Calendars.insertIntent(a.title, p))) Executed(ActionStatus.DRAFT_OPENED, opened, launchedApp = true)
        else Executed(ActionStatus.NO_APP, ToolStrings.noCalendarApp())
    }

    private fun prepareRead(a: Action.ReadCalendar): Prepared {
        if (!Calendars.readGranted(ctx)) return Prepared.Refused(ActionStatus.NEEDS_PERMISSION, ToolStrings.allowCalendarRead())
        return when (val r = WhenParser.range(a.day, ZonedDateTime.now(), lang)) {
            is Read.Unreadable -> Prepared.Refused(ActionStatus.INVALID, r.why)
            is Read.Ok -> Prepared.Ready(a.copy(range = r.value))
        }
    }

    private fun readCalendar(a: Action.ReadCalendar): Executed {
        val range = a.range ?: return Executed(ActionStatus.FAILED, ToolStrings.couldntReadDay())
        if (!Calendars.readGranted(ctx)) return Executed(ActionStatus.NEEDS_PERMISSION, ToolStrings.allowCalendarRead())
        val events = CalendarFormat.inRange(Calendars.events(ctx, range), range)
        val results = CalendarFormat.results(events, range)
        val detail = CalendarFormat.detailText(events.size, range)
        val body = if (results.isEmpty()) null else CalendarFormat.linesText(events, range)
        val spoken = if (results.isEmpty()) detail else Phrase.join(detail, CalendarFormat.linesText(events, range, spoken = true))
        return Executed(ActionStatus.DONE, detail, results = results, body = body, spoken = spoken)
    }

    // ------------------------------------------------------------------ weather

    /**
     * Before the card: the days (refused here if they can't be read or aren't in the forecast) and where. Nothing goes
     * online yet. "Here" without location access falls back on your Home for weather; with neither, the card asks you
     * to allow location (Allow opens the Robot app, which asks) or to set a Home.
     */
    private fun prepareWeather(a: Action.Weather): Prepared {
        val range = when (val r = WeatherAsk.days(a.day, ZonedDateTime.now())) {
            is Read.Unreadable -> return Prepared.Refused(ActionStatus.INVALID, r.why)
            is Read.Ok -> r.value
        }
        if (!Assistant.granted(ctx, Manifest.permission.INTERNET)) return Prepared.Refused(ActionStatus.FAILED, WeatherFail.NO_NETWORK_PERMISSION.phrase)
        val (where, named) = WeatherAsk.where(a.place)
        // A named place goes to Open-Meteo's geocoder only as a town or a place's name (Nearby's rule): never an address, a
        // number or a long run of words the model took from what it read.
        if (where == WeatherWhere.NAMED && !Nearby.weatherSendable(named ?: a.place.orEmpty())) {
            return Prepared.Refused(ActionStatus.INVALID, ToolStrings.weatherPlaceNotSent())
        }
        if (where == WeatherWhere.HOME && settings.weatherHome == null) return Prepared.Refused(ActionStatus.FAILED, WeatherFail.NO_HOME.phrase)
        if (where == WeatherWhere.HERE && !CoarseLocation.granted(ctx) && settings.weatherHome == null) {
            return Prepared.Refused(ActionStatus.NEEDS_PERMISSION, ToolStrings.weatherNeedsLocation())
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
        val range = a.range ?: (WeatherAsk.days(a.day, now) as? Read.Ok)?.value ?: return Executed(ActionStatus.INVALID, InfoStrings.sayDay())
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
                WeatherSource.FIX, WeatherSource.LAST_KNOWN -> ToolStrings.forecastHere() to HERE
                WeatherSource.HOME -> ToolStrings.forecastHome() to place.name
                WeatherSource.HOME_NO_FIX -> ToolStrings.forecastHomeNoFix() to place.name
                WeatherSource.NAMED -> ToolStrings.forecastNamed() to place.name
            }
            // Where the phone is has no name: the card says so in words; a place keeps its own name.
            val shownPlace = if (named == HERE) ToolStrings.whereYouAre() else Phrase.plain(named.replaceFirstChar { it.uppercase() })
            val coords = "${OpenMeteo.coord(place.lat)}, ${OpenMeteo.coord(place.lon)}"
            val sent = if (source == WeatherSource.NAMED) ToolStrings.sentToOpenMeteoNamed("place" to (WeatherAsk.where(a.place).second ?: a.place), "coords" to coords)
            else ToolStrings.sentToOpenMeteo("coords" to coords)
            Executed(ActionStatus.DONE, detail, results = listOf(mapOf("place" to named)) + report.results,
                body = Phrase.Join(listOf(Phrase.Join(listOf(shownPlace, report.linesText), "\n"), Phrase.Join(listOf(sent, Phrase.plain(OpenMeteo.ATTRIBUTION)), "\n")), "\n\n"),
                spoken = ToolStrings.weatherSpoken("place" to shownPlace, "days" to report.spokenText))
        } catch (e: OpenMeteoClient.Failure) {
            // Once the caller has given up (Stop, the deadline) it logs why; this result is dropped.
            if (!abort.cancelled) VoiceLog.add(VoiceEvent.WEATHER_FAILED, e.why, e.http)
            Executed(if (e.why == WeatherFail.NO_PLACE) ActionStatus.NOT_FOUND else ActionStatus.FAILED, e.detail?.let(Phrase::plain) ?: e.why.phrase)
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
        if (results.isEmpty()) return Executed(ActionStatus.FAILED, ToolStrings.couldntReadPhone())
        val units = WeatherUnits.of(region)
        val storage = a.part == PhonePart.STORAGE || a.part == PhonePart.ALL
        val page = if (!storage) null else if (f.usageAccess) SettingsPage.STORAGE else SettingsPage.USAGE_ACCESS
        return Executed(ActionStatus.DONE, ToolStrings.readOnPhone(), results = results, body = PhoneStatus.linesText(f, a.part, units),
            spoken = PhoneStatus.linesText(f, a.part, units, sep = ". "), handOff = page)
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
        val m = AppMatcher.match(name, launcherApps()) ?: return Prepared.Refused(ActionStatus.FAILED, ToolStrings.noAppCalled("name" to name))
        val pkg = m.app.key.substringBefore('/')
        if (pkg == ctx.packageName) return Prepared.Refused(ActionStatus.FAILED, ToolStrings.cantStopItself())
        return Prepared.Ready(Action.FreeMemory(m.app.label, pkg), why = unsure(m.loose))
    }

    /**
     * 0.7.0, free_memory with no app ("free up memory", "close apps"): a card to pick from (1.1: in the turn's languages).
     * Unloading Robot's model (about how much it holds), then the apps used lately that are likely still in memory (Usage
     * access; core FreeUp.likelyInMemory), each opening its App info where you tap Force stop; without Usage access, a
     * button to turn it on. Robot never stops an app itself (Android 14 and later let no app stop another).
     */
    private fun prepareUnload(a: Action.FreeMemory): Prepared {
        val now = System.currentTimeMillis()
        val brain = BrainKeeper.state.value
        val loaded = brain.readiness == BrainKeeper.Readiness.READY || brain.readiness == BrainKeeper.Readiness.LOADING
        val recent = PhoneInfo.recentInMemory(ctx)
        val choices = buildList {
            add(Choice(FreeUp.unloadChoiceText(if (loaded) modelBytes(brain.model) else null), Action.FreeMemory(null)))
            recent?.forEach { add(Choice(FreeUp.appChoiceText(it, now), Action.FreeMemory(it.label, it.pkg))) }
            if (recent == null) add(Choice(FreeUp.usageAccessChoiceText(), Action.FreeMemory(Action.FreeMemory.USAGE_ACCESS_LABEL, SettingsPage.USAGE_ACCESS)))
        }
        val note = FreeUp.pickNoteText()
        return Prepared.Ready(a, choices = choices, note = note.en(), noteText = note)
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
            return if (page(SettingsIntents.of(ctx, SettingsPage.USAGE_ACCESS))) Executed(ActionStatus.DONE, ToolStrings.usageAccessOpened(), launchedApp = true)
            else Executed(ActionStatus.FAILED, ToolStrings.usageAccessDidntOpen())
        }
        if (a.app == null || pkg == null) {
            ModelUnload.request(ModelUnload.available(ctx))
            val recent = PhoneInfo.recentApps(ctx)
            val results = listOf(mapOf(
                "memory" to PhoneStatus.memoryLine(PhoneInfo.memory(ctx), robotModel = true, modelBytes(BrainKeeper.state.value.model)),
                "recent_apps" to when {
                    recent.isNotEmpty() -> FreeUp.names(recent) + " (used in the last 2 hours)" // l10n: not text (model prompt)
                    PhoneInfo.usageAccess(ctx) -> "none, besides the one in use" // l10n: not text (model prompt)
                    else -> "unknown: Robot needs Usage access to see which apps were used lately" // l10n: not text (model prompt)
                },
            ))
            return Executed(ActionStatus.DONE, InfoStrings.unloading(), results = results, forModel = FreeUp.MEMORY_FOR_MODEL)
        }
        return if (page(listOf(SettingsIntents.appInfo(pkg)))) Executed(ActionStatus.DONE, ToolStrings.appInfoForceStop("appName" to a.app), launchedApp = true)
        else Executed(ActionStatus.FAILED, ToolStrings.appInfoDidntOpen("appName" to a.app))
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
            Prepared.Refused(ActionStatus.NEEDS_PERMISSION, ToolStrings.turnOnUsageAccessForApps("page" to UiLabel(UiStrings.assistantSetup)))
        } else {
            // Not Robot: its App info's "Clear storage" would delete its models.
            val top = PhoneStatus.top(PhoneInfo.apps(ctx).filter { it.pkg != ctx.packageName })
            if (top.isEmpty()) Prepared.Refused(ActionStatus.NOT_FOUND, ToolStrings.couldntReadAppSizes())
            else Prepared.Ready(a, why = setOf(AskWhy.PICK), choices = top.map { Choice(FreeUp.appChoiceText(it), a.copy(target = it.pkg, label = it.label)) })
        }
        StorageWay.FILES -> if (!PhoneInfo.mediaGranted(ctx)) {
            Prepared.Refused(ActionStatus.NEEDS_PERMISSION, ToolStrings.allowMedia())
        } else {
            val zone = ZoneId.systemDefault()
            val files = PhoneInfo.largeMedia(ctx)
            if (files.isEmpty()) Prepared.Refused(ActionStatus.NOT_FOUND, if (PhoneInfo.mediaPartial(ctx)) ToolStrings.mediaPartial() else ToolStrings.noMedia())
            else Prepared.Ready(a, why = setOf(AskWhy.DELETE), choices = files.map { f ->
                val summary = FreeUp.fileSummaryText(f, zone)
                Choice(FreeUp.fileChoiceText(f, zone), a.copy(target = f.uri, label = if (f.video) "video" else "photo", bytes = f.bytes, summary = summary.en(),
                    summaryText = summary))
            })
        }
    }

    private fun freeStorage(a: Action.FreeStorage): Executed = when (a.way) {
        StorageWay.ANDROID -> if (page(SettingsIntents.of(ctx, SettingsPage.FREE_UP))) Executed(ActionStatus.DONE, ToolStrings.freeUpOpened(), launchedApp = true)
        else Executed(ActionStatus.FAILED, ToolStrings.freeUpDidntOpen())
        StorageWay.ROBOT -> {
            val freed = PhoneInfo.clearRobotCache(ctx)
            Executed(ActionStatus.DONE, ToolStrings.cacheCleared("size" to PhoneStatus.sizeText(freed)),
                forModel = "Cleared ${PhoneStatus.size(freed)} of Robot's cache. Robot's big files are its models (the Model tab in the Robot app), which it needs to work.") // l10n: not text (model prompt)
        }
        StorageWay.APPS -> {
            val pkg = a.target
            if (pkg != null && page(listOf(SettingsIntents.appInfo(pkg)))) Executed(ActionStatus.DONE, ToolStrings.appInfoClearCache("appName" to a.label), launchedApp = true)
            else Executed(ActionStatus.FAILED, ToolStrings.appPageDidntOpen())
        }
        // Android's own confirmation decides; Robot never deletes a file itself.
        StorageWay.FILES -> {
            val uri = a.target
            // Like a draft: nothing is deleted until you allow it in Android's dialog; the card says so until it answers.
            if (uri != null && start(MediaDeleteActivity.intent(ctx, Uri.parse(uri)))) Executed(ActionStatus.DRAFT_OPENED, InfoStrings.deleteWaiting(), launchedApp = true)
            else Executed(ActionStatus.FAILED, ToolStrings.deleteDidntOpen())
        }
    }

    /** A settings page: the first of [intents] that opens (the Settings app may not be visible to Robot's queries). */
    private fun page(intents: List<Intent>): Boolean = intents.any { i -> try { launch(i) } catch (_: Exception) { false } }

    // ------------------------------------------------------------------ a helper's app

    /**
     * The app [a] opens, always from your settings (HelperSettings.appHelper): the helper it names when that one is on and
     * has an app, else the one Robot checks with. Never a package or a name from the call itself. Null: none.
     */
    private fun handOff(a: Action.AskHelper): Action.AskHelper? {
        val h = HelperSettings.appHelper(settings, a.helper) { HelperLink.keySaved(ctx, it) } ?: return null
        val pkg = h.presetOf.app ?: return null
        return a.copy(helper = h.id, app = pkg, appName = h.presetOf.defaultName)
    }

    private fun prepareHandOff(a: Action.AskHelper): Prepared {
        val r = handOff(a) ?: return Prepared.Refused(ActionStatus.NO_APP, AppHandOff.installText(null))
        if (!HandOffIntents.installed(ctx, r.app!!)) return Prepared.Refused(ActionStatus.NO_APP, AppHandOff.installText(r.appName))
        return Prepared.Ready(r, note = AppHandOff.note(r.appName!!, r.text), noteText = AppHandOff.noteText(r.appName!!, r.text))
    }

    /**
     * Opens a helper's app with the question by the first way that resolves on this phone (docs/ask-claude.md "The
     * Claude app route", 1.0.0 for every helper's app): the Claude app's claude:// or claude.ai link, a share to the app,
     * or just the app. The question goes on the clipboard every time, for an app that ignores the share. Robot only
     * prefills; you press Send there. The log has the way, never the words.
     */
    private fun openHelperApp(a: Action.AskHelper): Executed {
        val r = handOff(a) ?: return Executed(ActionStatus.NO_APP, AppHandOff.installText(null))
        val pkg = r.app!!
        val name = r.appName!!
        val way = HandOffIntents.choose(ctx, pkg) ?: return Executed(ActionStatus.NO_APP, AppHandOff.installText(name))
        val text = AppHandOff.cap(a.text)
        if (text.isNotEmpty()) HandOffIntents.copy(ctx, text, name)
        val i = HandOffIntents.intent(ctx, pkg, way, text) ?: return Executed(ActionStatus.NO_APP, AppHandOff.installText(name))
        RLog.i("hand_off_app", "way" to way, "claude" to (pkg == ClaudeAppLink.PACKAGE))
        if (!start(i)) return Executed(ActionStatus.FAILED, ToolStrings.appDidntOpen("appName" to name))
        return Executed(ActionStatus.DONE, AppHandOff.doneText(name, way, text), launchedApp = true)
    }

    private companion object {
        const val ICON_PX = 96
        /** The place name of a forecast for where the phone is, for the model (Open-Meteo has no reverse lookup, and none is wanted). */
        const val HERE = "where you are" // l10n: not text (model prompt)
        /** The weather's status, detail and place beside its days, about. */
        const val PLACE_TOKENS = 60
        /** How long the route to a saved place waits for where the phone is (car mode's 3 s). */
        const val ROUTE_FIX_MS = 3_000L
    }
}
