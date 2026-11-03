// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.car

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import app.heyrobot.R
import app.heyrobot.core.car.CarAction
import app.heyrobot.core.car.CarBeside
import app.heyrobot.core.car.CarFourth
import app.heyrobot.core.car.CarInfo
import app.heyrobot.core.car.CarLarge
import app.heyrobot.core.car.CarLayout
import app.heyrobot.core.car.CarMessageLine
import app.heyrobot.core.car.CarMessagesNow
import app.heyrobot.core.car.CarText
import app.heyrobot.core.i18n.HandsFreeStrings
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.theme.RobotTokens

// The car screen (docs/plans/car-layout-design.md, docs/pm/handoff-car-mode-drive-fixes.md). The large panel (your map, the
// media player or messages), the information panel, and one row of buttons that are always there, each just its icon and
// spoken name: Talk (wider, the one filled tile, the one you reach for without looking), the one you choose, Apps and Settings.
// In split screen the other app is the large half. No top strip: what Robot has to say shows in the information panel for a
// few seconds. No scrolling but in the Apps and music lists: a panel shows what fits, and a long text stops at a whole word.
// Used by CarModeActivity in :voice; the drawing is here so the tests can take its picture.

/**
 * The music tile: [app] the name (null: none chosen yet), its [song] and [artist], whether something [playing]; [art] the
 * song's picture, the whole card's background, or else [icon] the app's, small. Held in memory only while shown
 * (CarArtHold), never logged, saved or sent.
 */
data class CarMusicUi(val app: String? = null, val song: String? = null, val playing: Boolean = false, val artist: String? = null, val art: Bitmap? = null, val icon: Bitmap? = null)

/** A question in the information panel: [text], Yes when [canYes] (a list of choices is answered aloud) and Cancel. */
data class CarAskUi(val text: String, val canYes: Boolean = true, val yes: String? = null, val no: String? = null)

/** Robot at work with no panel: [listening], and the live words or the reply in [words]; [ask] when a card waits. */
data class CarTurnUi(val listening: Boolean = false, val words: String? = null, val ask: CarAskUi? = null)

/** An app in a list on the car screen. */
data class CarAppRow(val pkg: String, val label: String, val icon: Bitmap? = null)

/** What takes the place of the panels until you pick or close it: never a dialog over the map. */
sealed class CarSheet {
    data class Apps(val apps: List<CarAppRow>) : CarSheet()
    data class MusicOrPodcasts(val music: CarAppRow, val podcast: CarAppRow) : CarSheet()
    data class ChooseMusic(val apps: List<CarAppRow>) : CarSheet()
}

/** What the car screen shows. [title]: "Car mode", or "Car mode · unlocked features" in the trusted session, with the key on Talk. */
data class CarScreenState(
    val title: String = HandsFreeStrings.carMode().en(),
    val trusted: Boolean = false,
    /** What Robot has to say for a few seconds ("That app is closed"), in the information panel. Empty: nothing. */
    val said: String = "",
    val music: CarMusicUi = CarMusicUi(),
    /** "Back to directions" in place of "Maps": Robot opened a route this drive. */
    val directions: Boolean = false,
    val layout: CarLayout = CarLayout(),
    /** The window is in split screen: the other app is the large half. */
    val beside: Boolean = false,
    /** Shown only; a panel asks for them only when it shows them. */
    val messages: CarMessagesNow = CarMessagesNow(),
    /** A line of directions when Robot can read one (CarDirectionText); null: the panel's plain fallback. */
    val directionText: String? = null,
    /** Robot at work with no panel. */
    val turn: CarTurnUi? = null,
    val sheet: CarSheet? = null,
    /** Where the way back to your map shows, while another app is beside this screen. */
    val back: CarBeside.Back = CarBeside.Back.NONE,
    /** The fourth button's app's own icon, when it's set to an app. */
    val fourthIcon: Bitmap? = null,
)

class CarScreenActions(
    val talk: () -> Unit = {},
    val settings: () -> Unit = {},
    /** Settings › Messages, from the panel that says messages are off. */
    val messageSettings: () -> Unit = {},
    val fourth: () -> Unit = {},
    val maps: () -> Unit = {},
    val playPause: () -> Unit = {},
    val next: () -> Unit = {},
    val previous: () -> Unit = {},
    val openMusic: () -> Unit = {},
    val apps: () -> Unit = {},
    val yes: () -> Unit = {},
    val cancel: () -> Unit = {},
    val closeSheet: () -> Unit = {},
    /** A row of the sheet showing: the app it names. */
    val pick: (CarSheet, CarAppRow) -> Unit = { _, _ -> },
    /** Settings › Car mode, from the Apps list with none kept. */
    val addApps: () -> Unit = {},
)

/** The page's edge, the gap between areas and a panel's own padding; the corner is Settings' group corner. */
private val Edge = RobotTokens.pagePadding
private val Gap = 12.dp
private val Inner = 16.dp
private val Corner = RoundedCornerShape(RobotTokens.groupCorner)

/** The smallest button in the car: taller than Android's minimum, as car mode's have always been. */
private val Target = 72.dp

/** A panel with less room than this inside its padding puts things side by side. */
private val PanelStacks = 136.dp

/** An icon in the row: well over Android's own, read at a glance. */
private val TileIcon = 40.dp

/** Talk's share of the row, the others' being one. */
private const val TalkWeight = 1.6f

/** The app's icon in the small panel, and the least room left for its words under the big one. */
private val ArtSmall = 64.dp
private val ArtWords = 88.dp

/** Over a song's picture: dark enough that white words have 4.5:1 over a white picture, whatever the phone's theme. */
internal val ArtScrim = Color.Black.copy(alpha = 0.6f)
internal val ArtSoft = Color.White.copy(alpha = 0.9f)

/** The song's name in a short panel gets this small, on the screen, at any font size, before any of it is cut. */
private val SongFloor = 14.dp

/** The padding of a media panel at the split's smallest stops, where every dp goes to the song's name. */
private val InnerTight = 8.dp

/** Below this the large panel's media buttons give up some height, so the title has room in landscape. */
private val MediaRoomy = 280.dp

private val HeadSize = 28.sp
private val BodySize = 20.sp
private val SmallSize = 18.sp
private val BodyLine = 26.sp
private val SmallLine = 24.sp

@Composable
fun CarModeScreen(s: CarScreenState, a: CarScreenActions, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(Edge)
        .semantics { paneTitle = s.title }) {
        val wide = maxWidth > maxHeight
        val large = s.layout.largeNow(s.beside)
        // A card's Yes and Cancel and a refusal's words come first; the list comes back after them.
        val sheet = s.sheet?.takeIf { s.turn?.ask == null && s.said.isEmpty() }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Gap)) {
            when {
                sheet != null -> {
                    SheetPanel(sheet, a, Modifier.weight(1f).fillMaxWidth())
                    FixedRow(s, a)
                }
                large == null -> {
                    InfoPanel(s, a, Modifier.weight(1f).fillMaxWidth())
                    FixedRow(s, a)
                }
                wide -> Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
                    LargePanel(large, s, a, Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Gap)) {
                        InfoPanel(s, a, Modifier.weight(1f).fillMaxWidth())
                        FixedRow(s, a)
                    }
                }
                else -> {
                    LargePanel(large, s, a, Modifier.weight(3f).fillMaxWidth())
                    InfoPanel(s, a, Modifier.weight(1f).fillMaxWidth())
                    FixedRow(s, a)
                }
            }
        }
    }
}

/** A panel: the same corner, colour and padding wherever it sits. */
@Composable
private fun Panel(modifier: Modifier, art: Bitmap? = null, inner: Dp = Inner, content: @Composable () -> Unit) {
    Box(modifier.clip(Corner).background(MaterialTheme.colorScheme.surfaceContainer)) {
        if (art != null) {
            Image(art.asImageBitmap(), stringResource(R.string.car_art), Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            Box(Modifier.matchParentSize().background(ArtScrim))
        }
        Box(Modifier.fillMaxSize().padding(inner)) { content() }
    }
}

@Composable
private fun LargePanel(large: CarLarge, s: CarScreenState, a: CarScreenActions, modifier: Modifier) {
    when (large) {
        CarLarge.MAP -> MapPanel(s, a, modifier)
        CarLarge.MEDIA -> Panel(modifier, s.music.art) { MediaBig(s.music, a) }
        CarLarge.MESSAGES -> Panel(modifier) { MessagesList(s.messages, a) }
    }
}

@Composable
private fun InfoPanel(s: CarScreenState, a: CarScreenActions, modifier: Modifier) {
    val info = s.layout.infoNow(s.beside)
    val media = info == CarInfo.MEDIA && s.turn == null && s.said.isEmpty()
    BoxWithConstraints(modifier) {
    Panel(Modifier.fillMaxSize(), s.music.art.takeIf { media }, if (media && s.beside && maxHeight < PanelStacks + Inner * 2) InnerTight else Inner) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Gap), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                val turn = s.turn
                when {
                    turn != null -> TurnLines(turn, a)
                    s.said.isNotEmpty() -> WholeWords(s.said, MaterialTheme.colorScheme.onSurface, BodySize, BodyLine, 2, weight = FontWeight.Medium)
                    info == CarInfo.MEDIA -> MediaSmall(s.music, a, s.beside)
                    info == CarInfo.DIRECTIONS -> DirectionsInfo(s, a)
                    else -> LatestMessage(s.messages, a)
                }
            }
            // The way back to your map joins this panel when the fourth button isn't the one that can become it.
            if (s.back == CarBeside.Back.INFO && (info != CarInfo.DIRECTIONS || s.turn != null || s.said.isNotEmpty())) Tile(mapLabel(s), RIcons.Maps, a.maps, Modifier.width(Target))
        }
    }
    }
}

/** Your words as Robot hears them, or its reply: two lines, while it speaks and a few seconds after; the whole answer is in History. */
@Composable
private fun TurnLines(t: CarTurnUi, a: CarScreenActions) {
    val c = MaterialTheme.colorScheme
    val ask = t.ask
    if (ask == null) {
        val words = t.words ?: stringResource(R.string.car_listening)
        return WholeWords(words, c.onSurface, BodySize, BodyLine, 2, Modifier.fillMaxWidth(), FontWeight.Medium)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val yes = ask.yes ?: stringResource(R.string.car_yes)
        val no = ask.no ?: stringResource(R.string.car_cancel)
        if (maxHeight >= PanelStacks) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WholeWords(ask.text, c.onSurface, BodySize, BodyLine, 2, Modifier.weight(1f).fillMaxWidth(), FontWeight.Medium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
                if (ask.canYes) PanelButton(yes, a.yes, Modifier.weight(1f).height(Target))
                PanelButton(no, a.cancel, Modifier.weight(1f).height(Target))
            }
        } else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Gap), verticalAlignment = Alignment.CenterVertically) {
            WholeWords(ask.text, c.onSurface, BodySize, BodyLine, 2, Modifier.weight(1f), FontWeight.Medium)
            if (ask.canYes) PanelButton(yes, a.yes, Modifier.width(96.dp).height(Target))
            PanelButton(no, a.cancel, Modifier.width(112.dp).height(Target))
        }
    }
}

/** A list or a question where the panels were; the row stays under it, and the map beside it stays clear. */
@Composable
private fun SheetPanel(sheet: CarSheet, a: CarScreenActions, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val title = stringResource(when (sheet) {
        is CarSheet.Apps -> R.string.car_apps
        is CarSheet.MusicOrPodcasts -> R.string.car_music_or_podcasts
        is CarSheet.ChooseMusic -> R.string.car_music_choose
    })
    Panel(modifier) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Gap)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = c.onSurface, fontSize = HeadSize, lineHeight = 34.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Tile(stringResource(R.string.car_close), RIcons.Close, a.closeSheet, Modifier.width(Target))
            }
            when (sheet) {
                is CarSheet.Apps -> if (sheet.apps.isEmpty()) {
                    Text(stringResource(R.string.car_apps_none), color = c.onSurface, fontSize = BodySize, lineHeight = BodyLine, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    PanelButton(stringResource(R.string.car_apps_add), a.addApps, Modifier.fillMaxWidth().height(Target), RIcons.Add)
                } else AppRows(sheet, sheet.apps, a)
                is CarSheet.ChooseMusic -> if (sheet.apps.isEmpty()) {
                    Text(stringResource(R.string.everyday_music_app_none_found), color = c.onSurface, fontSize = BodySize, lineHeight = BodyLine, maxLines = 3, overflow = TextOverflow.Ellipsis)
                } else AppRows(sheet, sheet.apps, a)
                is CarSheet.MusicOrPodcasts -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    if (maxWidth > maxHeight) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Gap)) { TwoApps(sheet, a, Modifier.weight(1f).fillMaxHeight()) }
                    else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Gap)) { TwoApps(sheet, a, Modifier.weight(1f).fillMaxWidth()) }
                }
            }
        }
    }
}

@Composable
private fun TwoApps(sheet: CarSheet.MusicOrPodcasts, a: CarScreenActions, each: Modifier) {
    AppButton(sheet.music, { a.pick(sheet, sheet.music) }, each)
    AppButton(sheet.podcast, { a.pick(sheet, sheet.podcast) }, each)
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.AppRows(sheet: CarSheet, apps: List<CarAppRow>, a: CarScreenActions) {
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (app in apps) AppButton(app, { a.pick(sheet, app) }, Modifier.fillMaxWidth().height(Target))
    }
}

/** An app's own icon and its name. */
@Composable
private fun AppButton(app: CarAppRow, onClick: () -> Unit, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    Button(onClick = onClick, modifier = modifier.heightIn(min = Target).semantics { contentDescription = app.label }, shape = Corner, contentPadding = PaddingValues(horizontal = Inner),
        colors = ButtonDefaults.buttonColors(containerColor = c.surfaceContainerHighest, contentColor = c.onSurface)) {
        if (app.icon != null) {
            Image(app.icon.asImageBitmap(), null, Modifier.size(TileIcon))
            Spacer(Modifier.width(Gap))
        }
        Text(app.label, fontSize = BodySize, lineHeight = BodyLine, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    }
}

/** Split screen isn't on: the whole panel is one target that opens your map beside this screen, filled as the other tiles are. */
@Composable
private fun MapPanel(s: CarScreenState, a: CarScreenActions, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val text = stringResource(if (s.directions) R.string.car_directions else R.string.car_map_open)
    Button(onClick = a.maps, modifier = modifier.semantics { contentDescription = text }, shape = Corner, contentPadding = PaddingValues(Inner),
        colors = ButtonDefaults.buttonColors(containerColor = c.surfaceContainerHigh, contentColor = c.onSurface)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Gap)) {
            Icon(RIcons.Maps, null, tint = c.primary, modifier = Modifier.size(64.dp))
            Text(text, fontSize = HeadSize, lineHeight = 34.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.car_map_hint), color = c.onSurfaceVariant, fontSize = SmallSize, lineHeight = SmallLine, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** [text] on at most [lines] lines, as many as its space holds, stopping at a whole word: Android's own ellipsis stops mid-word. */
@Composable
private fun WholeWords(text: String, color: Color, size: TextUnit, line: TextUnit, lines: Int, modifier: Modifier = Modifier, weight: FontWeight? = null) {
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.merge(TextStyle(fontSize = size, lineHeight = line, fontWeight = weight))
    BoxWithConstraints(modifier) {
        val (shown, fit) = remember(text, style, constraints.maxWidth, constraints.maxHeight, lines) { cutToFit(measurer, text, style, constraints, lines) }
        Text(shown, color = color, style = style, maxLines = fit, overflow = TextOverflow.Ellipsis)
    }
}

/** What to draw and on how many lines: as many as the height really holds (at least one), measured, since sizes round. */
private fun cutToFit(measurer: TextMeasurer, text: String, style: TextStyle, space: Constraints, lines: Int): Pair<String, Int> {
    fun measure(t: String, n: Int) = measurer.measure(t, style, TextOverflow.Clip, maxLines = n, constraints = Constraints(maxWidth = space.maxWidth))
    val fit = (lines downTo 2).firstOrNull { measure(text, it).multiParagraph.height <= space.maxHeight } ?: 1
    val whole = measure(text, fit)
    if (!whole.hasVisualOverflow) return text to fit
    var end = whole.getLineEnd(whole.lineCount - 1, visibleEnd = true)
    repeat(4) {
        val cut = CarText.cutAtWord(text, end)
        if (!measure(cut, fit).hasVisualOverflow) return cut to fit
        end = cut.length - 2
    }
    return text to fit
}

/**
 * The song is what you came for, so it's the big line, then the artist on its own line, then the app's name above them; what
 * the height doesn't hold goes, in the other order. The name opens the app when there is one.
 */
@Composable
private fun NowPlaying(m: CarMusicUi, a: CarScreenActions, modifier: Modifier, songSize: TextUnit, songLines: Int, fit: Boolean = false) {
    val c = MaterialTheme.colorScheme
    val ink = if (m.art != null) Color.White else c.onSurface
    val soft = if (m.art != null) ArtSoft else c.onSurfaceVariant
    val open = m.app?.let { stringResource(R.string.car_music_open, it) } ?: stringResource(R.string.car_music_choose)
    val tap = Modifier.clickable(onClickLabel = open, onClick = a.openMusic)
    val songLine = songSize * 1.25f
    BoxWithConstraints(modifier.heightIn(min = RobotTokens.minTouch).then(tap).semantics(mergeDescendants = true) { contentDescription = open },
        contentAlignment = Alignment.CenterStart) {
        val (small, big) = with(LocalDensity.current) { SmallLine.toDp() to songLine.toDp() }
        when {
            fit -> FittedSong(m, ink, soft, songSize, constraints)
            m.app == null -> Text(stringResource(R.string.car_music_choose), color = ink, fontSize = songSize, lineHeight = songLine, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            m.song == null -> Text(m.app, color = ink, fontSize = songSize, lineHeight = songLine, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            else -> {
                val artist = m.artist?.takeIf { maxHeight >= big + small }
                val app = m.app.takeIf { maxHeight >= big + small * (if (artist != null) 2 else 1) }
                Column(Modifier.fillMaxWidth()) {
                    if (app != null) Text(app, color = soft, fontSize = SmallSize, lineHeight = SmallLine, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    WholeWords(m.song, ink, songSize, songLine, songLines, Modifier.weight(1f, fill = false), FontWeight.Medium)
                    if (artist != null) Text(artist, color = soft, fontSize = SmallSize, lineHeight = SmallLine, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * The short panels, where the height is what's short: the whole name, on as many lines as the panel holds, at the largest
 * size that does it, with the artist and the app's name only where they leave it readable. A name too long even at
 * [SongFloor] stops at a word, as everywhere else.
 */
@Composable
private fun FittedSong(m: CarMusicUi, ink: Color, soft: Color, start: TextUnit, space: Constraints) {
    val name = m.song ?: m.app ?: stringResource(R.string.car_music_choose)
    val artist = m.artist.takeIf { m.song != null }
    val app = m.app.takeIf { m.song != null }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.merge(TextStyle(fontWeight = FontWeight.Medium))
    val (extras, size) = remember(name, artist, app, style, space.maxWidth, space.maxHeight, density) {
        with(density) {
            val sizes = (generateSequence(start.toPx()) { it * 0.9f }.takeWhile { it > SongFloor.toPx() } + SongFloor.toPx()).map { it.toSp() }.toList()
            val line = SmallLine.toPx()
            fun fits(s: TextUnit, lines: Int) = measurer.measure(name, style.copy(fontSize = s, lineHeight = s * 1.25f), TextOverflow.Clip,
                constraints = Constraints(maxWidth = space.maxWidth)).multiParagraph.height <= space.maxHeight - lines * line
            when {
                artist != null && app != null && fits(sizes.first(), 2) -> 2 to sizes.first()
                artist != null || app != null -> sizes.firstOrNull { it >= SmallSize && fits(it, 1) }?.let { 1 to it }
                else -> null
            } ?: (0 to (sizes.firstOrNull { fits(it, 0) } ?: sizes.last()))
        }
    }
    Column(Modifier.fillMaxWidth()) {
        if (app != null && (extras == 2 || (extras == 1 && artist == null))) Text(app, color = soft, fontSize = SmallSize, lineHeight = SmallLine, maxLines = 1, overflow = TextOverflow.Ellipsis)
        WholeWords(name, ink, size, size * 1.25f, 10, Modifier.weight(1f, fill = false), FontWeight.Medium)
        if (artist != null && extras >= 1) Text(artist, color = soft, fontSize = SmallSize, lineHeight = SmallLine, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The app's icon when the song has no picture of its own (the picture is the card's background, [Panel]). */
@Composable
private fun Picture(m: CarMusicUi, modifier: Modifier) {
    val icon = m.icon?.takeIf { m.art == null } ?: return
    Image(icon.asImageBitmap(), null, modifier.clip(Corner), contentScale = ContentScale.Fit)
}

@Composable
private fun MediaBig(m: CarMusicUi, a: CarScreenActions) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val roomy = maxHeight >= MediaRoomy
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Gap)) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val w = maxWidth
                val h = maxHeight
                val tall = h > w * 1.2f
                when {
                    m.art != null || m.icon == null -> NowPlaying(m, a, Modifier.fillMaxSize(), HeadSize, 4)
                    tall -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Gap)) {
                        Picture(m, Modifier.size(min(w, h - ArtWords - Gap).coerceAtLeast(ArtSmall)))
                        NowPlaying(m, a, Modifier.weight(1f).fillMaxWidth(), HeadSize, 3)
                    }
                    else -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Gap), verticalAlignment = Alignment.CenterVertically) {
                        Picture(m, Modifier.size(min(h, w * 0.45f)))
                        NowPlaying(m, a, Modifier.weight(1f).fillMaxHeight(), HeadSize, 4)
                    }
                }
            }
            Controls(m, a, Modifier.fillMaxWidth(), if (roomy) 112.dp else 88.dp, if (roomy) 56.dp else 48.dp)
        }
    }
}

/** The app's icon small, beside the song, when the song has no picture. */
@Composable
private fun SongBeside(m: CarMusicUi, a: CarScreenActions, modifier: Modifier, lines: Int, fit: Boolean) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Gap), verticalAlignment = Alignment.CenterVertically) {
        Picture(m, Modifier.size(ArtSmall))
        NowPlaying(m, a, Modifier.weight(1f), BodySize, lines, fit)
    }
}

@Composable
private fun MediaSmall(m: CarMusicUi, a: CarScreenActions, inSplit: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxHeight >= PanelStacks) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SongBeside(m, a, Modifier.weight(1f).fillMaxWidth(), 1, inSplit)
                Controls(m, a, Modifier.fillMaxWidth(), Target, 40.dp)
            }
        } else {
            // The smallest split: the name has only this much width, so even the app's icon gives it up.
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Gap), verticalAlignment = Alignment.CenterVertically) {
                NowPlaying(m, a, Modifier.weight(1f), BodySize, 2, inSplit)
                Controls(m, a, Modifier.weight(1.4f), Target, 36.dp)
            }
        }
    }
}

@Composable
private fun Controls(m: CarMusicUi, a: CarScreenActions, modifier: Modifier, height: Dp, icon: Dp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Control(R.string.car_music_previous, RIcons.SkipPrevious, a.previous, Modifier.weight(1f), height, icon)
        if (m.playing) Control(R.string.car_music_pause, RIcons.Pause, a.playPause, Modifier.weight(1f), height, icon)
        else Control(R.string.car_music_play, RIcons.Play, a.playPause, Modifier.weight(1f), height, icon)
        Control(R.string.car_music_next, RIcons.SkipNext, a.next, Modifier.weight(1f), height, icon)
    }
}

@Composable
private fun Control(res: Int, icon: ImageVector, onClick: () -> Unit, modifier: Modifier, height: Dp, iconSize: Dp) {
    val c = MaterialTheme.colorScheme
    val text = stringResource(res)
    Button(onClick = onClick, modifier = modifier.height(height).semantics { contentDescription = text }, shape = Corner,
        colors = ButtonDefaults.buttonColors(containerColor = c.surfaceContainerHighest, contentColor = c.onSurface), contentPadding = PaddingValues(4.dp)) {
        Icon(icon, null, modifier = Modifier.size(iconSize))
    }
}

/** A button inside a panel: the media buttons' fill, a name that may take two lines. */
@Composable
private fun PanelButton(text: String, onClick: () -> Unit, modifier: Modifier, icon: ImageVector? = null) {
    val c = MaterialTheme.colorScheme
    Button(onClick = onClick, modifier = modifier.heightIn(min = Target).semantics { contentDescription = text }, shape = Corner, contentPadding = PaddingValues(horizontal = Gap, vertical = 4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c.surfaceContainerHighest, contentColor = c.onSurface)) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(Gap))
        }
        Text(text, fontSize = BodySize, lineHeight = BodyLine, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The text when Robot can read one, and a big way to your map. Today there's no text, so the plain line says where the
 * directions are, and a short panel (landscape, split screen) leaves it out and is all button.
 */
@Composable
private fun DirectionsInfo(s: CarScreenState, a: CarScreenActions) {
    val c = MaterialTheme.colorScheme
    val button = stringResource(if (s.directions) R.string.car_directions else R.string.car_maps)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val line = s.directionText
        when {
            maxHeight >= PanelStacks -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (line != null) WholeWords(line, c.onSurface, BodySize, BodyLine, 3, weight = FontWeight.Medium)
                    else Text(stringResource(R.string.car_directions_fallback), color = c.onSurface, fontSize = BodySize, lineHeight = BodyLine, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                PanelButton(button, a.maps, Modifier.fillMaxWidth().height(Target), RIcons.Maps)
            }
            line == null -> PanelButton(button, a.maps, Modifier.fillMaxSize(), RIcons.Maps)
            else -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Gap), verticalAlignment = Alignment.CenterVertically) {
                WholeWords(line, c.onSurface, BodySize, BodyLine, 3, Modifier.weight(1f), FontWeight.Medium)
                PanelButton(button, a.maps, Modifier.weight(1f).height(Target), RIcons.Maps)
            }
        }
    }
}

/** No message: a line that says why. */
@Composable
private fun messagesLine(n: CarMessagesNow): String = stringResource(when (n.state) {
    CarMessagesNow.State.OFF -> R.string.car_messages_off
    CarMessagesNow.State.LOCKED -> R.string.car_messages_locked
    else -> R.string.car_messages_none
})

@Composable
private fun OpenMessageSettings(a: CarScreenActions, modifier: Modifier) {
    PanelButton(stringResource(R.string.car_messages_open), a.messageSettings, modifier)
}

@Composable
private fun MessageLine(l: CarMessageLine, locked: Boolean, lines: Int) {
    val c = MaterialTheme.colorScheme
    Text(l.who, color = c.onSurface, fontSize = BodySize, lineHeight = BodyLine, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    val text = l.text
    if (text != null) WholeWords(text, c.onSurface, BodySize, BodyLine, lines)
    else Text(stringResource(if (locked) R.string.car_message_locked else R.string.car_message_no_text), color = c.onSurfaceVariant, fontSize = SmallSize, lineHeight = BodyLine, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** The information panel's latest message. It shows a message: there is no way to send from here. */
@Composable
private fun LatestMessage(n: CarMessagesNow, a: CarScreenActions) {
    val l = n.lines.firstOrNull()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val off = n.state == CarMessagesNow.State.OFF
        val room = with(LocalDensity.current) { ((maxHeight - BodyLine.toDp()) / BodyLine.toDp()).toInt() }
        when {
            l != null -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) { MessageLine(l, n.locked, room.coerceIn(1, 3)) }
            off && maxHeight >= PanelStacks -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) { NoticeLine(messagesLine(n)) }
                OpenMessageSettings(a, Modifier.fillMaxWidth().height(Target))
            }
            off -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Gap), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { NoticeLine(messagesLine(n)) }
                OpenMessageSettings(a, Modifier.weight(1.3f).height(Target))
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { NoticeLine(messagesLine(n)) }
        }
    }
}

@Composable
private fun NoticeLine(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = BodySize, lineHeight = BodyLine, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
}

/** The large panel with no message: what's going on in large type, as the map panel has it, and the way to fix it when there's one. */
@Composable
private fun MessagesNotice(n: CarMessagesNow, a: CarScreenActions) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Gap, Alignment.CenterVertically)) {
        Icon(if (n.state == CarMessagesNow.State.LOCKED) RIcons.Lock else RIcons.Message, null, tint = c.onSurfaceVariant, modifier = Modifier.size(64.dp))
        Text(messagesLine(n), color = c.onSurface, fontSize = HeadSize, lineHeight = 34.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (n.state == CarMessagesNow.State.OFF) OpenMessageSettings(a, Modifier.fillMaxWidth().height(Target))
    }
}

/** The large panel's newest conversations, as many as fit. To reply you tap Talk, and the usual read-back and yes applies. */
@Composable
private fun MessagesList(n: CarMessagesNow, a: CarScreenActions) {
    val c = MaterialTheme.colorScheme
    if (n.lines.isEmpty()) return MessagesNotice(n, a)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val line = with(LocalDensity.current) { BodyLine.toDp() }
        val hint = with(LocalDensity.current) { SmallLine.toDp() } * 2 + Gap
        fun height(threads: Int, lines: Int) = line * ((1 + lines) * threads) + (Gap * 2 + 1.dp) * (threads - 1)
        val lines = if (height(n.lines.size, 2) <= maxHeight) 2 else 1
        val shown = (n.lines.size downTo 1).firstOrNull { height(it, lines) <= maxHeight } ?: 1
        val hinted = !n.locked && height(shown, lines) + hint <= maxHeight
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Gap)) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Gap)) {
                n.lines.take(shown).forEachIndexed { i, l ->
                    if (i > 0) HorizontalDivider(color = c.outlineVariant)
                    Column(Modifier.fillMaxWidth()) { MessageLine(l, n.locked, lines) }
                }
            }
            if (hinted) Text(stringResource(R.string.car_messages_reply_hint), color = c.onSurfaceVariant, fontSize = SmallSize, lineHeight = SmallLine, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Always there, always this height: Talk first and wider, the one you chose, Apps and Settings. Icons only; each says its name to a screen reader. */
@Composable
private fun FixedRow(s: CarScreenState, a: CarScreenActions) {
    val c = MaterialTheme.colorScheme
    val (fourthText, fourthIcon) = if (s.back == CarBeside.Back.FOURTH) mapLabel(s) to RIcons.Maps else fourthLook(s.layout.fourth, s.directions)
    val talk = stringResource(if (s.turn?.listening == true) R.string.car_listening else R.string.car_talk)
    Row(Modifier.fillMaxWidth().height(Target), horizontalArrangement = Arrangement.spacedBy(Gap)) {
        Tile(talk, RIcons.Mic, a.talk, Modifier.weight(TalkWeight), accent = true) {
            if (s.trusted) Icon(RIcons.Key, stringResource(R.string.car_unlocked_on), tint = c.onPrimary, modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp).size(20.dp))
        }
        Tile(fourthText, fourthIcon, a.fourth, Modifier.weight(1f), picture = s.fourthIcon.takeIf { s.back != CarBeside.Back.FOURTH && s.layout.fourth is CarFourth.App })
        Tile(stringResource(R.string.car_apps), RIcons.Apps, a.apps, Modifier.weight(1f))
        Tile(stringResource(R.string.car_settings), RIcons.Settings, a.settings, Modifier.weight(1f))
    }
}

/** The way back to your map: "Back to directions" when Robot opened the route. */
@Composable
private fun mapLabel(s: CarScreenState): String = stringResource(if (s.directions) R.string.car_directions else R.string.car_map)

@Composable
private fun fourthLook(f: CarFourth, directions: Boolean): Pair<String, ImageVector> = when (f) {
    is CarFourth.App -> f.app.label to RIcons.Apps
    is CarFourth.Action -> when (f.action) {
        CarAction.MUSIC_APP -> stringResource(R.string.car_fourth_music_short) to RIcons.Play
        CarAction.MAPS -> stringResource(if (directions) R.string.car_directions else R.string.car_maps) to RIcons.Maps
        CarAction.NAVIGATE_HOME -> stringResource(R.string.car_navigate_home) to RIcons.Navigate
    }
}

/** A button that's only an icon, or an app's own [picture]; [text] is what a screen reader says. */
@Composable
private fun Tile(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier, accent: Boolean = false, picture: Bitmap? = null, more: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}) {
    val c = MaterialTheme.colorScheme
    val fill: Color = if (accent) c.primary else c.surfaceContainerHigh
    val ink: Color = if (accent) c.onPrimary else c.onSurface
    Button(onClick = onClick, modifier = modifier.height(Target).semantics { contentDescription = text }, shape = Corner,
        colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = ink), contentPadding = PaddingValues(horizontal = 4.dp)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (picture != null) Image(picture.asImageBitmap(), null, Modifier.size(TileIcon))
            else Icon(icon, null, modifier = Modifier.size(TileIcon))
            more()
        }
    }
}
