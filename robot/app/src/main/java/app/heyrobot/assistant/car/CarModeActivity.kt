// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.car

import app.heyrobot.core.car.CarInfo
import app.heyrobot.core.car.CarBottomSaid
import app.heyrobot.core.car.CarHalvesPhrase
import app.heyrobot.R
import android.app.ActivityManager
import android.content.Intent
import android.content.IntentSender
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.heyrobot.assistant.MessagesLink
import app.heyrobot.assistant.PanelController
import app.heyrobot.assistant.handsfree.HandsFreeHub
import app.heyrobot.assistant.music.PanelMusic
import app.heyrobot.core.Abort
import app.heyrobot.core.car.AppPin
import app.heyrobot.core.car.CarArtHold
import app.heyrobot.core.car.CarBeside
import app.heyrobot.core.car.CarLineHold
import app.heyrobot.core.car.CarMusicChoice
import app.heyrobot.core.car.CarRecents
import app.heyrobot.core.car.CarWidget
import app.heyrobot.core.car.CarWidgetNow
import app.heyrobot.core.car.CarFourth
import app.heyrobot.core.car.CarLayout
import app.heyrobot.core.car.CarMessagesNow
import app.heyrobot.core.car.CarDirections
import app.heyrobot.core.car.CarOpen
import app.heyrobot.core.car.CarPhase
import app.heyrobot.core.car.CarScreen
import app.heyrobot.core.car.MediaNow
import app.heyrobot.core.everyday.MusicApp
import app.heyrobot.core.i18n.HandsFreeStrings
import app.heyrobot.core.i18n.LockStrings
import app.heyrobot.core.output.MusicAction
import app.heyrobot.core.output.spoken
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.CarWidgets
import app.heyrobot.sys.CoarseLocation
import app.heyrobot.sys.EverydaySettingsStore
import app.heyrobot.sys.MapsApps
import app.heyrobot.sys.MusicApps
import app.heyrobot.ui.car.CarAppRow
import app.heyrobot.ui.car.CarModeScreen
import app.heyrobot.ui.car.CarMusicUi
import app.heyrobot.ui.car.CarScreenActions
import app.heyrobot.ui.car.CarScreenState
import app.heyrobot.ui.car.CarSheet
import app.heyrobot.ui.car.CarTurnUi
import app.heyrobot.ui.common.ReplyOp
import app.heyrobot.ui.theme.RobotTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import android.appwidget.AppWidgetHostView
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import app.heyrobot.ui.common.CoveredTouches
import app.heyrobot.ui.common.filterObscuredTouches

// VOICE PROCESS (0.8). The car screen, not exported, in :voice where car mode is: big buttons, and what Robot has to say in
// the information panel. Its own card in Recents, "Car mode" with Robot's icon, gone when car mode ends; swiping the card only
// closes this screen, and car mode, its notice and "hey robot" carry on. Over the lock screen only during the trusted session (§4.6), and then only these buttons; it keeps the screen on
// only with "Keep the screen on". It closes when car mode ends. The music tile and the map beside it:
// docs/pm/handoff-car-media-maps.md. What it opens follows the locks as "navigate home" does (CarOpen). The layout, the
// fourth button and the messages it may show (RAM, shown only, never sent from here): docs/plans/car-layout-design.md.

class CarModeActivity : ComponentActivity() {
    private val ui = mutableStateOf(CarScreenState())
    /** What Robot has to say, for a few seconds. */
    private var said = ""
        set(v) {
            field = v
            saidGoes?.cancel()
            if (v.isNotEmpty()) saidGoes = lifecycleScope.launch { delay(SAID_MS); said = ""; refresh() }
        }
    private var saidGoes: Job? = null
    /** M1: a touch another app's window hid part of this screen for, and the line a refused Yes leaves on its own card. */
    private val covered = CoveredTouches()
    private val refusal = CarCoveredLine(SAID_MS)
    /** Your podcast app, while it's still yours. */
    private var podcast: MusicApp? = null
    @Volatile private var mapsPkg: String? = null
    private var sheet: CarSheet? = null
    /** What "Choose your music app" listed, for its pick to pin. */
    private var foundMusic: List<MusicApps.Found> = emptyList()
    /** The song's picture, while this screen is up and the song is the same (never logged, saved or sent). */
    private val artHold = CarArtHold<Bitmap>()
    private val icons = HashMap<String, Bitmap?>()
    private var fourthIcon: Bitmap? = null
    private val turnHold = CarLineHold<CarTurnUi>(TURN_LINE_MS)
    private var turnNow: CarTurnNow? = null
    private var turnGoes: Job? = null
    private var music = CarMusicUi()
    private var pinned: MusicApp? = null
    /** The app the tile's name opens, and whether the tile shows a session Android told us of. */
    private var opens: String? = null
    private var fromSessions = false
    /** The app whose session the tile shows, playing or paused: the Maps button's swap opens it when both your apps are set. */
    private var onTile: String? = null
    private var secureNow = false
    /** The bottom screen's widget, and whether its swap shows the media player instead. */
    private lateinit var widgets: CarWidgetHost
    private var swapped = false
    private var messages = CarMessagesNow()
    private var link: MessagesLink? = null
    private val watcher: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Recents can reopen this screen in a fresh :voice, before anything else has started the hub.
        app.heyrobot.assistant.VoiceHub.init(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Its Yes or Send acts at once: a tap through another app's window is refused (review-panel-covered).
        window.filterObscuredTouches()
        CarModeController.watch(watcher)
        widgets = CarWidgetHost(this) { s -> layout().widget?.let { openApp(it.app, s) } }
        lifecycleScope.launch(Dispatchers.IO) { CarWidgets.sweep(this@CarModeActivity, setOfNotNull(layout().widget?.id)) }
        setTaskDescription(ActivityManager.TaskDescription.Builder().setLabel(HandsFreeStrings.carMode().render(HandsFreeHub.screen)).setIcon(R.mipmap.ic_launcher).build())
        refresh()
        // The panel's state: with no panel open, this screen shows the turn.
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { PanelController.state.collect { refresh() } } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(SESSIONS_MS)
                    showMusic(pinnedToo = fromSessions)
                    showMessages()
                }
            }
        }
        setContent {
            RobotTheme {
                CarModeScreen(ui.value, CarScreenActions(
                    // Talk again in a turn with no panel ends the turn and Robot's words, as closing the panel would; a reply in
                    // its 3 seconds or on its way still goes first (fix-car-stay-f).
                    talk = { if (CarModeController.quietTurn) PanelController.hideAfterAnnouncement(words = false) else CarModeController.talk() },
                    settings = { openSettings(AppPages.CAR_SETTINGS) },
                    messageSettings = { openSettings(AppPages.MESSAGES_SETTINGS) },
                    fourth = { fourth() },
                    maps = { maps() },
                    playPause = { key(if (ui.value.music.playing) MusicAction.PAUSE else MusicAction.PLAY) },
                    next = { key(MusicAction.NEXT) },
                    previous = { key(MusicAction.PREVIOUS) },
                    openMusic = { openMusic() },
                    chooseMusic = { chooseMusic() },
                    apps = { showApps() },
                    yes = { answer(true) },
                    cancel = { answer(false) },
                    closeSheet = { sheet = null; refresh() },
                    pick = { from, row -> picked(from, row) },
                    addApps = { sheet = null; openSettings(AppPages.CAR_SETTINGS) },
                    swapBottom = { swapBottom() },
                    widget = { m, shown -> WidgetSlot(layout().widget?.let { widgets.view(it) }, m, shown) },
                ))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        CarModeController.screenUp = true
        widgets.start()
        CarModeController.bottomScreen = { p -> bottomVoice(p) }
        CarModeController.besideOpener = { i -> CarSplitOpen.start(this, i).also { if (it) openedBeside(i) } }
    }

    /** An app opened by voice is recorded as the buttons record theirs (review-car-maps-swap, Medium). */
    private fun openedBeside(i: Intent) {
        CarModeController.besideOpened(CarBeside.opened(i.`package` ?: i.component?.packageName, mapsPkg))
        SystemClock.elapsedRealtime().let { t -> runOnUiThread { besideAt = t } }
    }

    /** When this screen last opened an app beside itself (fix-car-stay: if it goes out of sight right after, Android didn't split). */
    /** fix-car-stay-b: written and read on the main thread only (a route by voice opens from Dispatchers.IO), and read once. */
    @Volatile private var besideAt = Long.MIN_VALUE / 2

    override fun onResume() {
        super.onResume()
        // L2: Robot's own messages show as the car screen's line: a toast over it would refuse its Yes.
        app.heyrobot.ui.common.RobotToast.up(this) { said = it; refresh() }
        if (CarModeController.takeRefusedSplit()) said = getString(R.string.car_split_refused)
        refresh()
        loadMusic()
        lifecycleScope.launch { showMessages() }
    }

    override fun onPause() {
        // fix-covered-partial-b L4: paused under another window (the panel, a dialog-style screen), Robot's messages are toasts again.
        app.heyrobot.ui.common.RobotToast.down(this)
        super.onPause()
    }

    override fun onStop() {
        app.heyrobot.ui.common.RobotToast.down(this)
        CarModeController.screenUp = false
        widgets.stop()
        CarModeController.bottomScreen = null
        CarModeController.besideOpener = null
        val since = SystemClock.elapsedRealtime() - besideAt
        besideAt = Long.MIN_VALUE / 2
        if (app.heyrobot.core.car.CarSplit.covered(isInMultiWindowMode, isFinishing, since)) CarModeController.splitRefused()
        sheet = null
        messages = CarMessagesNow()
        forgetPictures()
        refresh()
        super.onStop()
    }

    /** Split screen came or went: the other app is the large half, or the panel is back. */
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        refresh()
        lifecycleScope.launch { showMessages() }
    }

    override fun onDestroy() {
        forgetPictures()
        link?.close()
        link = null
        CarModeController.unwatch(watcher)
        super.onDestroy()
    }

    /** The song's picture and the icons go when the screen does; they come back with it. */
    private fun forgetPictures() {
        artHold.drop()
        icons.clear()
        fourthIcon = null
        music = music.copy(art = null, icon = null)
    }

    /** One app: open it. Both: ask on this screen. None: the list right here. */
    private fun follow(c: CarMusicChoice) {
        when (c) {
            is CarMusicChoice.Open -> openApp(c.app)
            CarMusicChoice.ChooseOne -> chooseMusic()
            is CarMusicChoice.Ask -> lifecycleScope.launch {
                val (m, p) = withContext(Dispatchers.IO) { row(c.music) to row(c.podcast) }
                sheet = CarSheet.MusicOrPodcasts(m, p)
                refresh()
            }
        }
    }

    private fun row(app: MusicApp) = CarAppRow(app.pkg, app.label, CarArtReader.icon(this, app.pkg))

    /** The music apps Robot can ask, leaving out what the locks close; never over the lock screen, where choosing is a setting. */
    private fun chooseMusic() {
        val no = closed(CarOpen.settings(HandsFreeHub.phone().locked))
        if (no != null) { said = no; return refresh() }
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { MusicApps.find(this@CarModeActivity) }.filter { PanelMusic.carMayShow(it.pkg) }
            foundMusic = found
            val rows = withContext(Dispatchers.IO) { found.map { CarAppRow(it.pkg, it.label, CarArtReader.icon(this@CarModeActivity, it.pkg)) } }
            sheet = CarSheet.ChooseMusic(rows)
            refresh()
        }
    }

    /** The Apps button: the apps you kept for the car, and only those, leaving out what the locks close. */
    private fun showApps() {
        if (sheet is CarSheet.Apps) { sheet = null; return refresh() }
        lifecycleScope.launch {
            val kept = layout().apps.filter { PanelMusic.carMayShow(it.pkg) }
            sheet = CarSheet.Apps(withContext(Dispatchers.IO) { kept.map { row(it) } })
            refresh()
        }
    }

    private fun picked(from: CarSheet, row: CarAppRow) {
        sheet = null
        when (from) {
            is CarSheet.Apps -> layout().apps.firstOrNull { it.pkg == row.pkg }?.let { openApp(it) }
            is CarSheet.MusicOrPodcasts -> listOfNotNull(pinned, podcast).firstOrNull { it.pkg == row.pkg }?.let { openApp(it) }
            // The same pin as Settings › Actions › Music, then the app beside this screen.
            is CarSheet.ChooseMusic -> foundMusic.firstOrNull { it.pkg == row.pkg }?.let { f ->
                // A pin is a setting: looked at again in case the phone locked with the list up.
                val no = closed(CarOpen.settings(HandsFreeHub.phone().locked))
                if (no != null) { said = no; return refresh() }
                lifecycleScope.launch {
                    val pin = withContext(Dispatchers.IO) {
                        MusicApps.pin(this@CarModeActivity, f)?.also { p -> EverydaySettingsStore.update(this@CarModeActivity) { it.copy(musicApp = p) } }
                    }
                    if (pin != null) openApp(pin)
                    loadMusic()
                }
            }
        }
        refresh()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        covered.see(ev.actionMasked, ev.flags)
        return super.dispatchTouchEvent(ev)
    }

    /** The big Yes or Cancel of a card in a turn with no panel. Yes refuses a tap another app's window hid part of (M1). */
    private fun answer(yes: Boolean) {
        val n = turnNow ?: return
        if (yes && covered.now) {
            // M2: said aloud too, and the card takes a spoken answer where it can (a map in picture-in-picture over the car screen).
            val line = PanelController.coveredYes(n.actionId, n.replyId)
            refusal.refused(n.actionId ?: n.replyId, line.res, SystemClock.elapsedRealtime())
            lifecycleScope.launch { delay(SAID_MS); refresh() }
            return refresh()
        }
        refusal.answered()
        n.actionId?.let { PanelController.confirm(it, yes) }
        n.replyId?.let { PanelController.reply(it, if (yes) ReplyOp.Send else ReplyOp.Cancel) }
    }

    /** The bottom screen's swap: the widget you picked, or the media player in its place. */
    fun swapBottom() {
        swapped = !swapped
        refresh()
    }

    /** "swap the bottom", "put Spotify's widget on the bottom": the widget Robot has, never a new one (only a tap binds one). */
    private fun bottomVoice(p: CarHalvesPhrase): CarBottomSaid {
        val l = layout()
        val w = l.widget?.takeIf { l.info == CarInfo.WIDGET }
        val named = p !is CarHalvesPhrase.WidgetOnBottom || (w != null && p.means(w.app.label))
        return when {
            w == null || !named || widgetGone -> CarBottomSaid.SET_UP
            Assistant.keyguard(this) -> CarBottomSaid.LOCKED
            closes(w) -> CarBottomSaid.CLOSED
            else -> {
                runOnUiThread { swapped = if (p is CarHalvesPhrase.WidgetOnBottom) false else !swapped; refresh() }
                CarBottomSaid.DONE
            }
        }
    }

    /** The widget's id no longer holds what you picked (its app reinstalled or re-signed); set by [widgetOff]. */
    @Volatile private var widgetGone = false

    private fun closes(w: CarWidget): Boolean = PhoneLockNow.on(this).let { on -> CarWidgetNow.closes(on, if (on) PhoneLockNow.apps(this) else emptySet(), w.app.pkg) }

    /** Over the lock screen, while Phone lock closes its app, or when its id is gone, the widget gives way to the media player. */
    private fun widgetOff(): Boolean {
        val w = layout().widget ?: return false.also { widgetGone = false }
        val locked = Assistant.keyguard(this)
        val closes = !locked && closes(w)
        // The view is made only once the lock and Phone lock allow it.
        if (!locked && !closes) widgetGone = widgets.view(w) == null
        return CarWidgetNow.off(locked, closes, made = locked || closes || !widgetGone)
    }

    /** The fourth button, as you chose it in Settings › Car mode. */
    private fun fourth() {
        when (val t = CarBeside.tap(layout().fourth, ui.value.back, ui.value.swap, CarMusicChoice.of(pinned, podcast))) {
            CarBeside.Tap.Maps -> maps()
            CarBeside.Tap.Home -> navigateHome()
            is CarBeside.Tap.Follow -> follow(t.choice)
        }
    }

    /** The app you pinned on the fourth button, beside this screen: through the locks, and only while signed as when you pinned it. */
    /** [screen]: a widget tap's own screen, opened beside instead of the launcher entry when CarWidgetScreen allows. */
    private fun openApp(app: MusicApp, screen: IntentSender? = null) {
        val lockOn = PhoneLockNow.on(this)
        val no = closed(CarOpen.app(app.pkg, Assistant.keyguard(this), CarModeController.trusted, lockOn, if (lockOn) PhoneLockNow.apps(this) else emptySet()))
        val i = if (no == null && MusicApps.check(this, app) == MusicApps.PinNow.OK) packageManager.getLaunchIntentForPackage(app.pkg) else null
        val opened = i != null && (screen != null && CarWidgetScreen.send(this, screen, app.pkg) || CarSplitOpen.start(this, i))
        said = no ?: if (opened) "" else getString(R.string.car_fourth_gone, app.label)
        // review-car-widget-taps M1: a tap's open arms the covered check as a voice open does (openedBeside), so one
        // that lands over the car screen instead of beside it brings the car screen back (onStop). On the main thread.
        if (opened) besideAt = SystemClock.elapsedRealtime()
        CarBeside.appOpen(app.pkg, opened, no != null, CarModeController.lastBeside)?.let { CarModeController.besideOpened(it) }
        refresh()
    }

    /** A Settings page: never over the lock screen (CarOpen.settings); Phone lock's own gate covers the main app. */
    private fun openSettings(page: String) {
        val no = closed(CarOpen.settings(HandsFreeHub.phone().locked))
        if (no == null) try {
            startActivity(Intent().setClassName(packageName, "app.heyrobot.ui.MainActivity").putExtra(AppPages.EXTRA, page).putExtra(AppPages.EXTRA_FROM_CAR, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {}
        said = no ?: ""
        refresh()
    }

    /**
     * Messages for the panels, only while a panel shows them: from the main process's bridge, which applies Settings › Messages
     * and the lock; threads of an app a phone lock closes are left out here. Kept in RAM for the screen, never logged or sent.
     */
    private suspend fun showMessages() {
        if (!layout().showsMessages(isInMultiWindowMode)) {
            if (link != null) { link?.close(); link = null; messages = CarMessagesNow() }
            return
        }
        val l = link ?: MessagesLink(this).also { link = it }
        val listed = withTimeoutOrNull(MESSAGES_MS) { l.list() }
        // The screen went while the list was on its way: it would show over the lock screen at the next wake.
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        messages = listed?.let { (status, threads) -> CarMessagesNow.of(status, threads.filter { t -> t.app.packages.any(PanelMusic::carMayShow) }) } ?: CarMessagesNow()
        refresh()
    }

    private fun layout(): CarLayout = HandsFreeHub.car.settings.layout

    /** The position first (3 s at most), then the route to home in your maps app, beside this screen. */
    private fun navigateHome() {
        lifecycleScope.launch {
            val here = withContext(Dispatchers.IO) {
                try { CoarseLocation.find(this@CarModeActivity, 3_000L, Abort(4_000L)).fix?.let { it.lat to it.lon } } catch (_: Throwable) { null }
            }
            said = withContext(Dispatchers.IO) { CarModeController.navigateHome(this@CarModeActivity, here) }
            refresh()
        }
    }

    /** Phone lock first, then the lock screen's trusted session; the line said when either closes it, else null. */
    private fun closed(gate: CarOpen.Gate): String? = when (gate) {
        CarOpen.Gate.OPEN -> null
        CarOpen.Gate.PHONE_LOCK -> spoken(LockStrings.appClosed(), HandsFreeHub.lang)
        CarOpen.Gate.UNLOCK -> spoken(HandsFreeStrings.unlockOnce(), HandsFreeHub.lang)
    }

    /** The Maps tile ("Back to directions" after a route): your maps app forward as it was, beside this screen. */
    private fun maps() {
        val lockOn = PhoneLockNow.on(this)
        val no = closed(CarOpen.maps(Assistant.keyguard(this), CarModeController.trusted, lockOn, lockOn && PhoneLockNow.mapsInCar(this)))
        val back = no == null && MapsApps.bringForward(this, adjacent = false) { CarSplitOpen.start(this, it) }
        said = no ?: if (back) "" else spoken(HandsFreeStrings.noOrganicMaps(), HandsFreeHub.lang)
        if (back) CarModeController.besideOpened(CarBeside.Last.Map)
        refresh()
    }

    /** The tile's app name: the app it names, beside this screen, when the locks leave it open. */
    private fun openMusic() {
        CarMusicChoice.forTile(ui.value.music.playing, opens, pinned, podcast)?.let { return follow(it) }
        val pkg = opens ?: return
        val app = pinned?.takeIf { it.pkg == pkg }
        val lockOn = PhoneLockNow.on(this)
        val no = closed(CarOpen.app(pkg, Assistant.keyguard(this), CarModeController.trusted, lockOn, if (lockOn) PhoneLockNow.apps(this) else emptySet()))
        val i = if (no == null && (app == null || MusicApps.check(this, app) == MusicApps.PinNow.OK)) packageManager.getLaunchIntentForPackage(pkg) else null
        if (i != null && CarSplitOpen.start(this, i)) CarModeController.besideOpened(CarBeside.Last.App(pkg))
        said = no ?: ""
        refresh()
    }

    private fun key(a: MusicAction) {
        lifecycleScope.launch {
            if (!PanelMusic.carKey(a)) said = spoken(app.heyrobot.core.i18n.EverydayStrings.didntAnswer(), HandsFreeHub.lang)
            refresh()
            delay(SONG_AFTER_KEY_MS)
            loadMusic()
        }
    }

    /** The tile, on opening and after a key. */
    private fun loadMusic() {
        lifecycleScope.launch { showMusic(pinnedToo = true) }
    }

    /**
     * What Android says plays, in any app the locks leave open (CarSessions: shown here only). A paused session counts only for
     * your music app or an app seen playing this drive. With none that counts and [pinnedToo], your pinned music app and its song,
     * asked of its own media service; else the name or the choosing line. Without [pinnedToo] no session changes nothing, so the
     * watch below never connects to your music app.
     */
    private suspend fun showMusic(pinnedToo: Boolean) {
        val sessions = withContext(Dispatchers.IO) { CarSessions.read(this@CarModeActivity) }.filter { PanelMusic.carMayShow(it.pkg) }
        val seen = CarModeController.playedThisDrive
        sessions.filter { it.playing }.forEach { seen.add(it.pkg) }
        val settings = withContext(Dispatchers.IO) { EverydaySettingsStore.current(this@CarModeActivity) }
        val app = settings.musicApp
        val pin = app?.let { pinNow(it) }
        val counted = sessions.filter { it.playing || it.pkg in seen || it.pkg == app?.pkg }
        if (counted.isEmpty() && !pinnedToo) return refresh()
        podcast = settings.podcastApp?.takeIf { pinNow(it) == AppPin.OK }
        mapsPkg = withContext(Dispatchers.IO) { MapsApps.app(this@CarModeActivity) }
        val song = if (counted.isEmpty() && pin == AppPin.OK) PanelMusic.carSong() else null
        val asked = counted.isEmpty() && pin == AppPin.OK && PanelMusic.carReported()
        val all = if (app != null && song != null) listOf(MediaNow(app.pkg, asked, song.title, song.artist)) else sessions
        val shown = CarSessions.shown(all, app, pin, packageName, seen) { CarSessions.label(this@CarModeActivity, it) }
        pinned = app.takeIf { pin == AppPin.OK }
        opens = shown.opens
        fromSessions = counted.isNotEmpty()
        val session = shown.session
        val songKey = session?.let { Triple(it, shown.ui.song, shown.ui.artist) }
        val art = withContext(Dispatchers.IO) { artHold.next(songKey) { song?.art ?: session?.let { CarSessions.art(this@CarModeActivity, it) } } }
        val iconOf = session ?: shown.opens
        val icon = iconOf?.let { iconFor(it) }
        fourthIcon = (layout().fourth as? CarFourth.App)?.let { iconFor(it.app.pkg) }
        onTile = session
        listOfNotNull(pinned, podcast).forEach { iconFor(it.pkg) }
        music = shown.ui.copy(playing = shown.ui.playing || asked, art = art, icon = icon)
        refresh()
    }

    private suspend fun pinNow(a: MusicApp): AppPin = withContext(Dispatchers.IO) {
        when (MusicApps.check(this@CarModeActivity, a)) {
            MusicApps.PinNow.OK -> AppPin.OK
            MusicApps.PinNow.GONE -> AppPin.GONE
            MusicApps.PinNow.REPLACED -> AppPin.REPLACED
        }
    }

    private suspend fun iconFor(pkg: String): Bitmap? {
        if (pkg !in icons) icons[pkg] = withContext(Dispatchers.IO) { CarArtReader.icon(this@CarModeActivity, pkg) }
        return icons[pkg]
    }

    /** The turn with no panel, and the reply a few seconds after it; a refresh comes when those seconds are up. */
    private fun turnLines(): CarTurnUi? {
        val now = if (CarModeController.quietTurn) CarTurnLines.of(PanelController.state.value) else null
        turnNow = now
        val shown = turnHold.next(now?.ui?.let(::refusedShown), CarTurnLines.kept(now), SystemClock.elapsedRealtime())
        turnGoes?.cancel()
        if (now == null && shown != null) turnGoes = lifecycleScope.launch { delay(TURN_LINE_MS); refresh() }
        return shown
    }

    /** M1: for a few seconds after a refused Yes, that card's words say why (the screen's own place for them); no other card's. */
    private fun refusedShown(t: CarTurnUi): CarTurnUi {
        val ask = t.ask ?: return t
        val res = refusal.line(turnNow?.let { it.actionId ?: it.replyId }, SystemClock.elapsedRealtime()) ?: return t
        return t.copy(ask = ask.copy(text = getString(res)))
    }

    private fun refresh() {
        val c = CarModeController
        if (c.state.phase == CarPhase.OFF) return finishAndRemoveTask()
        // The car screen is one of Robot's screens: the menus' language; what Robot said ([said]) is in its own.
        val l = HandsFreeHub.screen
        val trusted = c.trusted
        setShowWhenLocked(trusted)
        if (HandsFreeHub.car.settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val turn = turnLines()
        secureNow = CarRecents.secure(c.privateTurn, turn != null, secureNow)
        if (secureNow) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val off = widgetOff()
        val widget = layout().slotNow(isInMultiWindowMode, off, phoneLockCloses = false, swapped = swapped) == CarInfo.WIDGET
        setRecentsScreenshotEnabled(CarRecents.picture(messages.lines.isNotEmpty(), turn != null, trusted, widget))
        val directions = CarDirections.tile(c.routeThisDrive, ongoing = false) == CarDirections.Tile.BACK_TO_DIRECTIONS
        val back = if (mapsPkg == null) CarBeside.Back.NONE else CarBeside.back(c.lastBeside, mapsPkg, layout().fourth, layout().large, isInMultiWindowMode)
        val swap = CarBeside.swap(c.lastBeside, mapsPkg, layout().fourth, isInMultiWindowMode, CarBeside.pick(CarMusicChoice.of(pinned, podcast), onTile, c.besideThisDrive))
        ui.value = CarScreenState(CarScreen.titleText(trusted).render(l), trusted, said,
            music.copy(playing = PanelMusic.carPlaying(music.playing)), directions, layout(), isInMultiWindowMode, messages,
            turn = turn, sheet = sheet, back = back, fourthIcon = fourthIcon, widgetOff = off, swapped = swapped, swap = swap, swapIcon = (swap as? CarMusicChoice.Open)?.let { icons[it.app.pkg] })
    }

    private companion object {
        /** The song is asked again this long after a key, once the app has moved on. EST. */
        const val SONG_AFTER_KEY_MS = 1_500L
        /** How often the tile looks at what plays while this screen is up. EST. */
        const val SESSIONS_MS = 5_000L
        /** How long the bridge may take to list your messages. EST. */
        const val MESSAGES_MS = 3_000L
        /** How long what Robot said (a lock closed an app, an app is gone) and the reply after a turn stay. EST. */
        const val SAID_MS = 5_000L
        const val TURN_LINE_MS = 6_000L
    }
}

/** The widget's view in the bottom screen: hidden while a turn takes the slot, never dropped, so it doesn't reload. */
@Composable
private fun WidgetSlot(view: AppWidgetHostView?, modifier: Modifier, shown: Boolean) {
    if (view == null) {
        if (shown) Text(stringResource(R.string.car_widget_setup), modifier, color = MaterialTheme.colorScheme.onSurface)
        return
    }
    AndroidView({ (view.parent as? ViewGroup)?.removeView(view); view }, modifier, update = { it.visibility = if (shown) View.VISIBLE else View.INVISIBLE })
}
