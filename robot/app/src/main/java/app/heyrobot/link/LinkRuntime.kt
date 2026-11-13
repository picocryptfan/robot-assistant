// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.link

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Process
import android.os.UserManager
import app.heyrobot.core.link.LinkEvent
import app.heyrobot.core.link.LinkInbox
import app.heyrobot.core.link.LinkIpcProtocol
import app.heyrobot.core.link.LinkLog
import app.heyrobot.core.link.XferPaths
import app.heyrobot.core.link.LinkLogCodec
import app.heyrobot.core.link.LinkStateException
import app.heyrobot.core.link.LinkSettings
import app.heyrobot.core.link.TorPlan
import app.heyrobot.core.link.Waiting
import app.heyrobot.core.online.TorHours
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import app.heyrobot.r2r.ListsShareHandler
import app.heyrobot.r2r.AndroidUnixSockets
import app.heyrobot.r2r.ConnectorPush
import app.heyrobot.r2r.HttpPushPoster
import app.heyrobot.r2r.LinkEnv
import app.heyrobot.r2r.LinkNode
import app.heyrobot.r2r.LinkTransfers
import app.heyrobot.r2r.TransferDirs
import app.heyrobot.r2r.DeskBox
import app.heyrobot.r2r.DeskEnv
import app.heyrobot.r2r.CardsHandler
import app.heyrobot.r2r.EtaHandler
import app.heyrobot.r2r.QuestionsHandler
import app.heyrobot.r2r.RemindersHandler
import app.heyrobot.r2r.EventsHandler
import app.heyrobot.r2r.FreeBusyHandler
import app.heyrobot.r2r.GroupPlans
import app.heyrobot.r2r.SharedFolders
import app.heyrobot.r2r.LinkDesk
import app.heyrobot.r2r.LinkStateStore
import app.heyrobot.r2r.LinkStores
import app.heyrobot.r2r.NetView
import app.heyrobot.r2r.SocketDirectNet
import app.heyrobot.r2r.TorPaths
import app.heyrobot.r2r.TorRuntime
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.sys.Cmd
import app.heyrobot.sys.LinkSettingsStore
import app.heyrobot.core.link.LinkBackupCodec
import app.heyrobot.core.link.LinkBackupRead
import java.io.File

// THE :link PROCESS. 1.5, Robot to Robot (robot-to-robot-plan.md §5.1; job L3): the one LinkNode of this process, opened
// from the vault (L2) when something needs it: a client binding LinkService, or the push job. Off by default and fail-closed
// (§6.1): nothing opens, listens, starts Tor, registers with a distributor or sends unless link_settings.json says Robot to
// Robot is on, the phone has been unlocked since it started (before that, credential-encrypted storage looks empty and the
// vault would read as Off: never trusted), and the vault is Ready. Robot's network access (GrapheneOS's Network toggle) is
// checked before every send, listen and Tor start (LinkNode, through [AndroidLinkEnv]).
//
// What arrives is read by :linkparse, the isolated reader (L4, LinkParseLink): :link sends it each plaintext and keeps the
// request only when it rebuilds that plaintext exactly; :linkparse runs while :link is awake and ends when it sleeps or
// starts over. Requests that pass every check go to the desk (L6, the link module's LinkDesk): decided by your levels, the
// lock and Q3; cards wait sealed in the waiting inbox (robot_inbox_v1) as the plaintext they came as, read by :linkparse again
// after the unlock.

object LinkRuntime {
    /** L5-fix: how long Start over waits for the unpairs to go. */
    private const val UNPAIR_WAIT_S = 20L
    private val lock = Any()
    private var node: LinkNode? = null
    private var state: LinkStateStore? = null
    /** F59: why the last open couldn't read the state; null when it could. Asked by [vaultCode], so a Settings page tells. */
    @Volatile private var unopened: VaultState? = null
    private var vault: LinkVault? = null
    private var parse: LinkParseLink? = null
    private var holders = 0

    fun vault(ctx: Context): LinkVault = synchronized(lock) {
        vault ?: LinkVault(LinkVault.dir(ctx.filesDir)).also { vault = it }
    }

    /** The phone has been unlocked since it started: credential-encrypted storage is readable (L2's "confirm the first unlock"). */
    fun unlockedOnce(ctx: Context): Boolean = ctx.getSystemService(UserManager::class.java)?.isUserUnlocked == true

    fun on(ctx: Context): Boolean = LinkSettingsStore.current(ctx).on

    /** The vault's state as the IPC's code. Off whenever the phone hasn't been unlocked since it started. */
    fun vaultCode(ctx: Context): Int {
        if (!unlockedOnce(ctx)) return LinkIpcProtocol.VAULT_LOCKED
        return vaultCode(vault(ctx).state(), unopened)
    }

    /** F59: state on storage says Ready without decrypting anything, so what the last open found comes first. */
    internal fun vaultCode(onDisk: VaultState, unopened: VaultState?): Int {
        return when (val s = if (onDisk == VaultState.Ready) unopened ?: onDisk else onDisk) {
            VaultState.Off -> LinkIpcProtocol.VAULT_OFF
            VaultState.Ready -> LinkIpcProtocol.VAULT_READY
            VaultState.Locked -> LinkIpcProtocol.VAULT_LOCKED
            is VaultState.Gone -> LinkIpcProtocol.VAULT_GONE
            VaultState.Newer -> LinkIpcProtocol.VAULT_NEWER
        }
    }

    /**
     * L5-fix, tests only: opens the node in place of the vault (Robolectric has no Keystore and the app's unit tests load no
     * libsignal). The first-unlock and on checks still come first.
     */
    @androidx.annotation.VisibleForTesting
    internal var opener: ((Context) -> LinkNode?)? = null

    /**
     * The node, opened once (and started) when Robot to Robot is on, set up, and readable now; else null. L5-fix: whether
     * it is on is read again on every call, before a node already open is returned: turned off, that node is put to sleep
     * (no listener, no Tor, no connection) and closed, :linkparse ends, and nothing of it runs until it is turned on again.
     */
    fun node(ctx: Context): LinkNode? = synchronized(lock) {
        val app = ctx.applicationContext
        if (!unlockedOnce(app) || !on(app)) { shut("off"); return null } // l10n: not text (a code)
        node?.let { return it }
        val o = opener
        val n = (if (o != null) o(app) else open(app)) ?: return null
        n.start()
        node = n
        restored(app, n)
        // Something still holds :link (a bound client, the push job) from before it was turned off: awake again at once.
        if (holders > 0) { n.wake(); watchNetwork(app, n) }
        n
    }

    /** The node over the vault's state, or null when it isn't Ready (or the state doesn't read: a code in the link log). */
    private fun open(app: Context): LinkNode? {
        val v = vault(app)
        unopened = null
        if (v.state() != VaultState.Ready) return null
        if (!v.inboxReady()) { unopened = VaultState.Locked; return null }
        val log = LinkLog(File(v.dir, LinkLogCodec.FILE))
        val n = try {
            val all = v.records.readAll()
            val stores = LinkStores.open(v.records, all = all)
            val st = LinkStateStore(v.records).also { it.load(all) }
            state = st
            build(app, v, stores, st, log)
        } catch (x: LinkStateException) {
            unopened = v.problem(x)
            log.add(LinkEvent.START, x.problem.id, 0)
            return null
        }
        log.add(LinkEvent.START, LinkIpcProtocol.VAULT_READY, state?.pairings?.size ?: 0)
        return n
    }

    /** L5-fix: the open node, if any, closed (it sleeps first), :linkparse ended and the network no longer watched. */
    private fun shut(why: String) {
        val n = node ?: return
        node = null
        n.close()
        parse?.end(why); parse = null
        unwatchNetwork()
    }

    private fun build(app: Context, v: LinkVault, stores: LinkStores, st: LinkStateStore, log: LinkLog): LinkNode {
        // L-bridges: lyrebird beside Tor, and Settings › Robot to Robot › Tor bridges read at each start of Tor.
        val tor = TorRuntime(File(app.applicationInfo.nativeLibraryDir, "libtor.so"), TorPaths(File(v.dir, "tor")), AndroidUnixSockets(), Process.myPid(), // l10n: not text (file names)
            lyrebird = File(app.applicationInfo.nativeLibraryDir, "liblyrebird.so"), plan = { TorPlan.of(LinkSettingsStore.current(app)) }, // l10n: not text (a file name)
            onReady = { r -> log.add(LinkEvent.TOR, 2, r.code) }, onFailed = { p -> log.add(LinkEvent.TOR, 5, p.code) },
            hours = File(v.dir, "tor_hours.json").let { f -> TorHours({ readOrNull(f) }, { writeAtomic(f, it) }) }) // l10n: not text (a file name)
        val poster = HttpPushPoster.platform { tor }
        val decoder = LinkParseLink(app).also { parse = it }
        // L6: the desk is the node's sink: cards sealed in the waiting inbox, answers on their own by their handlers.
        // L7: free/busy and plans over your calendar (LinkCalendar: busy times only, and only the events Robot added).
        val cal = LinkCalendar(app)
        // L8: files between Robots: chunks under transfer keys in files/link/, what passed sealed for later in Files' incoming/link/.
        val transfers = LinkTransfers(st, TransferDirs(v.dir, XferPaths.filesDir(app.filesDir)), v.inboxWrap, log)
        val freeBusy = FreeBusyHandler(st, cal)
        val events = EventsHandler(st, cal) { LinkSettingsStore.current(app) }
        val folders = SharedFolders(st, transfers) // L11: shared folders and group plans.
        val desk = LinkDesk(st, InboxBox(v.inbox), AndroidDeskEnv(app), decoder,
            listOf(transfers, EtaHandler, freeBusy, events, GroupPlans(st, cal, events, freeBusy), folders,
                RemindersHandler(st, LinkDeskReminders(app, st)), CardsHandler(LinkDeskPeople(app)), QuestionsHandler(st),
                ListsShareHandler(st, InboxBox(v.listInbox), decoder, AndroidDeskEnv(app))))
        return LinkNode(stores, st, AndroidLinkEnv(app), decoder, desk, SocketDirectNet({ androidNetworks(app) }), tor, ConnectorPush(app), poster, log,
            { LinkSettingsStore.current(app).name }).also { desk.attach(it.deskOut); it.attachTransfers(transfers, desk::notice); folders.notice = desk::notice }
    }

    /**
     * Turning Robot to Robot on (link_settings.json already says on): robot_inbox_v1, then robot_link_v1 with your new
     * identity (L2's LinkStores.create). Only from Off, never over anything sealed.
     */
    fun setUp(ctx: Context): Boolean = synchronized(lock) {
        val app = ctx.applicationContext
        if (!unlockedOnce(app) || !on(app)) return false
        val v = vault(app)
        if (v.state() == VaultState.Ready) return node(app) != null
        if (!v.setUp()) return false
        try {
            LinkStores.create(v.records, System.currentTimeMillis())
        } catch (_: LinkStateException) {
            return false
        }
        node(app) != null
    }

    /**
     * Start over (your tap, after its dialog): the node stopped, every push registration and key gone, Tor's files gone.
     * L5-fix: first, while Robot to Robot is on and set up, every paired Robot is told (`unpair`, best effort, awake so
     * every carrier may take it, for at most [UNPAIR_WAIT_S] seconds), so their pairings end too.
     */
    /**
     * L12: a restore left pairings for :link (RestoreApply). They wait for a node, and so for Robot to Robot on, and go in once:
     * the file goes when they are written, or when it can't be read. Nothing is sent from here.
     */
    internal fun restored(app: Context, n: LinkNode) {
        val f = File(app.filesDir, LinkBackupCodec.PENDING)
        if (!f.isFile) return
        val read = try { LinkBackupCodec.decode(f.readText(Charsets.UTF_8)) } catch (_: Exception) { LinkBackupRead.Damaged }
        if (read !is LinkBackupRead.Ok) { if (read.dropsFile) f.delete(); return }
        n.restoreBackup(read.file).thenAccept { ok -> if (ok) f.delete() }
    }

    /** R7: false: a key couldn't be deleted, so it isn't "started over". */
    fun startOver(ctx: Context): Boolean = synchronized(lock) {
        val app = ctx.applicationContext
        node(app)?.let { n ->
            n.wake()
            try { n.unpairAll().get(UNPAIR_WAIT_S, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        }
        node?.close(); node = null
        parse?.end("start_over"); parse = null // l10n: not text (a code)
        val push = ConnectorPush(app)
        push.forgetAll(state?.let { s -> s.pairings.values.mapNotNull { it.myInstance } + s.codes.values.mapNotNull { it.myInstance } }.orEmpty())
        state = null
        unopened = null
        val v = vault(app)
        // R7: first, so a cut part way can't bring pairings you asked to forget back from a restore that left them waiting.
        File(app.filesDir, LinkBackupCodec.PENDING).delete()
        val gone = v.startOver()
        File(v.dir, "tor").deleteRecursively() // l10n: not text (a folder name)
        TransferDirs(v.dir, XferPaths.filesDir(app.filesDir)).wipe()
        File(v.dir, LinkLogCodec.FILE).delete()
        gone
    }

    /** Something uses :link (a bound client, the push job when [job]): awake until the last one lets go. */
    fun hold(ctx: Context, job: Boolean = false): LinkNode? = synchronized(lock) {
        val n = node(ctx) ?: return null
        if (holders++ == 0) {
            n.wake(quiet = job)
            watchNetwork(ctx.applicationContext, n)
        } else if (!job) {
            n.wake() // a client joined what may be only the push job: what it held back goes now
        }
        n
    }

    fun release() = synchronized(lock) {
        if (holders > 0 && --holders == 0) {
            node?.sleep()
            // :linkparse goes with it: a fresh one reads what comes next.
            parse?.end("sleep") // l10n: not text (a code)
            unwatchNetwork()
        }
    }

    private var netApp: Context? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /** While awake: a new default network (ACCESS_NETWORK_STATE) tells Private pairings your new address on it. */
    private fun watchNetwork(app: Context, n: LinkNode) {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = n.networkChanged()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = n.networkChanged()
        }
        try { cm.registerDefaultNetworkCallback(cb); netCallback = cb; netApp = app } catch (_: Exception) {}
    }

    private fun unwatchNetwork() {
        val cb = netCallback ?: return
        try { netApp?.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        netCallback = null; netApp = null
    }
}

/**
 * L5-fix: the networks the phone is on, for the direct carrier (ACCESS_NETWORK_STATE, which :link already uses for its
 * network callback): each one's transports and addresses. The carrier listens only on Wi-Fi, Ethernet or a VPN (r2r
 * DirectPlaces), never on the mobile network. Nothing of it is kept or logged.
 */
internal fun androidNetworks(app: Context): List<NetView> {
    val cm = app.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
    return try {
        @Suppress("DEPRECATION") // getAllNetworks: still the one call that lists every network an app may see
        cm.allNetworks.mapNotNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            val lp = cm.getLinkProperties(n) ?: return@mapNotNull null
            NetView(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN), caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                lp.linkAddresses.map { it.address.address })
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/** The phone: the wall clock, and Robot's INTERNET permission (GrapheneOS's Network toggle revokes it, Tor's child included). */
class AndroidLinkEnv(private val ctx: Context) : LinkEnv {
    override fun now(): Long = System.currentTimeMillis()
    override fun networkAllowed(): Boolean = ctx.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED
}

/** L6: the waiting inbox (robot_inbox_v1) as the desk keeps its cards there. */
private class InboxBox(private val inbox: LinkInbox) : DeskBox {
    override fun put(pairing: String, at: Long, request: ByteArray): String = inbox.put(pairing, at, request)
    override fun ids(): List<String> = inbox.ids()
    override fun count(): Int = inbox.count()
    override fun open(id: String): Waiting? = inbox.open(id)
    override fun delete(id: String) = inbox.delete(id)
}

/**
 * L6: the phone as the desk sees it. Locked: KeyguardManager (unknown counts as locked). A phone lock: PhoneLockNow. Q3:
 * link_settings.json, read each time. A ping: Robot's own broadcast to :voice (Cmd.LINK_DESK, under its signature
 * permission), with nothing in it: :voice asks LinkService what changed.
 */
internal class AndroidDeskEnv(private val app: Context) : DeskEnv {
    override fun locked(): Boolean = try { app.getSystemService(KeyguardManager::class.java)?.isDeviceLocked ?: true } catch (_: Exception) { true }
    override fun phoneLock(): Boolean = try { PhoneLockNow.on(app) } catch (_: Exception) { true }
    override fun settings(): LinkSettings = LinkSettingsStore.current(app)
    override fun ping() = Cmd.send(app, Cmd.LINK_DESK)
}
