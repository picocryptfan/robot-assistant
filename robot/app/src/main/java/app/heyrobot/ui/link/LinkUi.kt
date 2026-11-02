// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.link

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.heyrobot.R
import app.heyrobot.core.Person
import app.heyrobot.core.link.LinkCapability
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.LinkLevel
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.PairingPhase
import app.heyrobot.core.link.PairingView
import app.heyrobot.core.link.Verified
import app.heyrobot.ui.AppActions

// MAIN PROCESS. 1.5, Robot to Robot (robot-to-robot-plan.md §6; job L5): the routes of Settings › Robot to Robot and its
// pages, and the words they share. Every one of these pages is kept out of screenshots and Recents (SecureWindow), as §6
// asks of every Robot to Robot screen.
//
//   link                       Settings › Robot to Robot
//   link/pair                  pairing (§6.2)
//   link/p/<id>                one pairing: its Robot link (§6.3), for a person or not linked to anyone yet
//   link/p/<id>/permissions    its permissions page
//   link/p/<id>/safety         its safety number
//   link/requests              Requests (L6, §6.4): waiting for you, handled by Robot, sent
//   link/folders, link/groups  L11: shared folders and group plans, with /new and one each by id

object LinkRoutes {
    const val ROOT = "link" // l10n: not text (a route)
    const val PAIR = "link/pair" // l10n: not text (a route)
    const val REQUESTS = "link/requests" // l10n: not text (a route)
    /** L11: shared folders and group plans. */
    const val FOLDERS = "link/folders" // l10n: not text (a route)
    const val FOLDER_NEW = "link/folders/new" // l10n: not text (a route)
    fun folder(pairing: String, fold: String) = "link/folders/$pairing/$fold" // l10n: not text (a route)
    const val GROUPS = "link/groups" // l10n: not text (a route)
    const val GROUP_NEW = "link/groups/new" // l10n: not text (a route)
    fun group(id: String) = "link/groups/$id" // l10n: not text (a route)
    /** L-bridges: Settings › Robot to Robot › Tor bridges. */
    const val BRIDGES = "link/bridges" // l10n: not text (a route)
    fun pairing(id: String) = "link/p/$id" // l10n: not text (a route)
    fun permissions(id: String) = "link/p/$id/permissions" // l10n: not text (a route)
    fun safety(id: String) = "link/p/$id/safety" // l10n: not text (a route)

    /** The pairing id in a pairing's route, or null. */
    fun idOf(route: String): String? = route.removePrefix("link/p/").substringBefore('/').takeIf { route.startsWith("link/p/") && app.heyrobot.core.link.LinkIds.isId(it) }
}

/** Settings › Robot to Robot and its pages, by route (SettingsNav). */
@Composable
fun LinkRoute(route: String, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val port = rememberLinkPort()
    val id = LinkRoutes.idOf(route)
    when {
        route == LinkRoutes.ROOT -> LinkSettingsScreen(port, a, snackbar, onBack)
        route == LinkRoutes.PAIR -> PairScreen(port, a, snackbar, onBack)
        route == LinkRoutes.REQUESTS -> RequestsScreen(port, a, snackbar, onBack)
        route == LinkRoutes.BRIDGES -> BridgesScreen(port, a, snackbar, onBack)
        route == LinkRoutes.FOLDERS -> FoldersScreen(port, a, snackbar, onBack)
        route == LinkRoutes.FOLDER_NEW -> FolderNewScreen(port, a, snackbar, onBack)
        route == LinkRoutes.GROUPS -> GroupsScreen(port, a, snackbar, onBack)
        route == LinkRoutes.GROUP_NEW -> GroupNewScreen(port, a, snackbar, onBack)
        route.startsWith(LinkRoutes.GROUPS + "/") && LinkIds.isId(route.substringAfterLast('/')) -> GroupScreen(port, route.substringAfterLast('/'), a, snackbar, onBack)
        route.startsWith(LinkRoutes.FOLDERS + "/") && route.split('/').size == 4 && route.split('/').takeLast(2).all(LinkIds::isId) ->
            route.split('/').let { (_, _, pairing, fold) -> FolderScreen(port, pairing, fold, a, snackbar, onBack) }
        id != null && route == LinkRoutes.permissions(id) -> PermissionsScreen(port, id, a, snackbar, onBack)
        id != null && route == LinkRoutes.safety(id) -> SafetyScreen(port, id, a, snackbar, onBack)
        id != null && route == LinkRoutes.pairing(id) -> PairingScreen(port, id, a, snackbar, onBack)
        else -> LaunchedEffect(route) { onBack() }
    }
}

/** One port for as long as the page shows, closed when it goes (:link sleeps again once nothing uses it). */
@Composable
fun rememberLinkPort(): LinkPort {
    val ctx = LocalContext.current
    val port = remember { LinkPorts.make(ctx) }
    DisposableEffect(port) { onDispose { port.close() } }
    return port
}

/** Where the pages get their port: the phone's, or a test's. */
object LinkPorts {
    @Volatile var factory: ((Context) -> LinkPort)? = null
    fun make(ctx: Context): LinkPort = factory?.invoke(ctx) ?: PhoneLinkPort(ctx)
}

@Composable
internal fun modeName(m: LinkMode): String = stringResource(if (m == LinkMode.PRIVATE) R.string.link_mode_private else R.string.link_mode_anonymous)

@Composable
internal fun modeDetail(m: LinkMode): String = stringResource(if (m == LinkMode.PRIVATE) R.string.link_mode_private_detail else R.string.link_mode_anonymous_detail)

/** A date for "Verified in person on …" (your phone's date format). */
internal fun dateText(ctx: Context, seconds: Long): String =
    DateUtils.formatDateTime(ctx, seconds * 1000, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

@Composable
internal fun verifiedLine(v: PairingView, since: Boolean): String {
    val ctx = LocalContext.current
    return when (v.verified) {
        Verified.NO -> stringResource(R.string.link_not_verified)
        Verified.IN_PERSON -> if (since) stringResource(R.string.link_verified_in_person_since, dateText(ctx, v.verifiedAt)) else stringResource(R.string.link_verified_in_person)
        Verified.COMPARED -> if (since) stringResource(R.string.link_verified_compared_since, dateText(ctx, v.verifiedAt)) else stringResource(R.string.link_verified_compared)
    }
}

/** What a pairing that isn't simply active is waiting for; null when active. */
@Composable
internal fun phaseLine(v: PairingView): String? = when (v.phase) {
    PairingPhase.ACTIVE -> null
    PairingPhase.SENT -> stringResource(R.string.link_phase_sent)
    PairingPhase.MOVING -> stringResource(R.string.link_phase_moving)
    PairingPhase.MOVED -> stringResource(R.string.link_phase_moved)
    PairingPhase.ENDED -> stringResource(R.string.link_phase_ended)
}

/** A pairing's one-line state: what it waits for, else its mode and how it was verified. */
@Composable
internal fun stateLine(v: PairingView): String = phaseLine(v) ?: stringResource(R.string.link_row_summary, modeName(v.mode), verifiedLine(v, since = false))

/** The name a pairing shows: your People name for it, never the name their Robot sent (that one only as "says they're …"). */
internal fun personOf(v: PairingView, people: List<Person>): Person? = v.person?.let { id -> people.firstOrNull { it.id == id } }

@Composable
internal fun displayName(v: PairingView, people: List<Person>): String = personOf(v, people)?.name ?: stringResource(R.string.link_someone)

@Composable
internal fun capabilityName(c: LinkCapability): String = stringResource(when (c) {
    LinkCapability.FREE_BUSY -> R.string.link_cap_free_busy
    LinkCapability.EVENTS -> R.string.link_cap_events
    LinkCapability.REMINDERS -> R.string.link_cap_reminders
    LinkCapability.ETA -> R.string.link_cap_eta
    LinkCapability.FILES -> R.string.link_cap_files
    LinkCapability.FILE_REQUESTS -> R.string.link_cap_file_requests
    LinkCapability.QUESTIONS -> R.string.link_cap_questions
    LinkCapability.CONTACT_CARDS -> R.string.link_cap_contact_cards
    LinkCapability.SPLITS -> R.string.link_cap_splits
    LinkCapability.LISTS -> R.string.link_cap_lists
    LinkCapability.FOLDERS -> R.string.link_cap_folders
    // Never a row (payments wait until after the full test): named only so the page can't miss a capability.
    LinkCapability.PAYMENTS -> R.string.link_cap_splits
})

@Composable
internal fun levelName(l: LinkLevel): String = stringResource(when (l) {
    LinkLevel.OFF -> R.string.link_level_off
    LinkLevel.ASK -> R.string.link_level_ask
    LinkLevel.AUTO -> R.string.link_level_auto
})

@Composable
internal fun levelDetail(l: LinkLevel): String = stringResource(when (l) {
    LinkLevel.OFF -> R.string.link_level_off_detail
    LinkLevel.ASK -> R.string.link_level_ask_detail
    LinkLevel.AUTO -> R.string.link_level_auto_detail
})

/** The limits that stay (§4.1), as one line under a capability's level. */
@Composable
internal fun limitLine(c: LinkCapability, hours: Pair<String, String>): String = when (c) {
    LinkCapability.FREE_BUSY -> stringResource(R.string.link_limit_free_busy)
    LinkCapability.EVENTS -> stringResource(R.string.link_limit_events, hours.first, hours.second)
    LinkCapability.REMINDERS -> stringResource(R.string.link_limit_reminders)
    LinkCapability.ETA -> stringResource(R.string.link_limit_eta)
    LinkCapability.FILES -> stringResource(R.string.link_limit_files)
    LinkCapability.FILE_REQUESTS, LinkCapability.QUESTIONS -> stringResource(R.string.link_limit_shared)
    LinkCapability.CONTACT_CARDS -> stringResource(R.string.link_limit_contact_cards)
    LinkCapability.SPLITS, LinkCapability.PAYMENTS -> stringResource(R.string.link_limit_splits)
    LinkCapability.LISTS, LinkCapability.FOLDERS -> stringResource(R.string.link_limit_join)
}
