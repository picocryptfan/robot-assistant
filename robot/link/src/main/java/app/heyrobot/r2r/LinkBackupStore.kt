// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.backup.RestoreSafety
import app.heyrobot.core.link.LinkBackupCodec
import app.heyrobot.core.link.LinkBackupFile
import app.heyrobot.core.link.LinkBlockBackup
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.LinkLimits
import app.heyrobot.core.link.LinkPairingBackup

/**
 * 1.5, Robot to Robot (§2.7, D14; job L12): between :link's sealed state and a backup's links.json (core LinkBackupCodec, which
 * has no field for a key). Called on the link thread. [export] reads only [LinkStateStore] fields named here and the public
 * identity pins; no private key or session store is reached from this file.
 */
internal object LinkBackupStore {
    /**
     * Who you're paired with (live, or restored and not yet accepted: both can still move), who you blocked, and what passed
     * between Robots with them. Being paired up, ended and moved-in records are left behind: nothing about them can be moved.
     */
    fun export(st: LinkStateStore, stores: NodeKeys): LinkBackupFile {
        val pairings = st.pairings.values.filter { it.state == PairingState.ACTIVE || it.state == PairingState.MOVING }.mapNotNull { p ->
            val identity = stores.identityOf(p.id) ?: return@mapNotNull null
            LinkPairingBackup(p.id, p.mode, p.created, p.name, p.caps, identity, p.peerPush, p.person, p.preset, p.levels, p.movedOut)
        }
        val ids = pairings.map { it.id }.toSet()
        return LinkBackupCodec.fit(LinkBackupFile(
            pairings,
            st.blocked.values.map { LinkBlockBackup(it.identity, it.label, it.at) },
            st.desk.values.filter { it.pairing in ids }.map(LinkBackupCodec::line),
            st.plans.values.filter { it.pairing in ids }.mapNotNull(LinkBackupCodec::plan),
            st.transfers.values.filter { it.pairing in ids }.mapNotNull { LinkBackupCodec.transfer(it.view()) },
            st.folders.values.filter { it.pairing in ids }.mapNotNull(LinkBackupCodec::folder),
            st.groups.values.filter { g -> g.members.any { it.pairing in ids } }.mapNotNull(LinkBackupCodec::group),
        ))
    }

    /**
     * A restored backup, added to what is here. Each pairing arrives MOVING: paused, no session, Verified no, its levels down
     * to Ask me first ([RestoreSafety.linkLevels]), and nothing goes out until you tap "Tell my Robots I moved"
     * (LinkNode.sendMoved) and they accept. Never replaces: a pairing or person already here, someone blocked here and this
     * Robot's own key are skipped, so running it twice changes nothing. Throws when a record can't be written: the caller
     * keeps the file and tries again.
     */
    fun restore(st: LinkStateStore, stores: NodeKeys, f: LinkBackupFile) {
        val me = stores.identityKey
        for (b in f.blocked) {
            if (b.identity.contentEquals(me) || st.blocked.values.any { it.identity.contentEquals(b.identity) }) continue
            st.put(BlockRecord(LinkIds.newId(), b.identity, b.label, b.at))
        }
        val here = st.pairings.keys.mapNotNull(stores::identityOf).toMutableList()
        for (b in f.pairings) {
            if (b.id in st.pairings || st.pairings.size >= LinkLimits.PAIRINGS || b.identity.contentEquals(me)) continue
            if (here.any { it.contentEquals(b.identity) } || st.blocked.values.any { it.identity.contentEquals(b.identity) }) continue
            val p = PairingRecord(b.id, PairingState.MOVING, b.mode, b.created, b.name, b.caps)
            p.peerPush = b.push
            p.movedOut = b.movedOut
            p.person = b.person
            p.preset = b.preset
            p.levels = RestoreSafety.linkLevels(b.levels)
            stores.pin(p.id, b.identity)
            st.put(p)
            here += b.identity
        }
        // History only for pairings now here; a line already here stays as it is.
        val moved = f.pairings.map { it.id }.filter { it in st.pairings }.toSet()
        for (e in f.desk) if (e.pairing in moved && e.id !in st.desk) st.put(LinkBackupCodec.line(e))
        for (p in f.plans) if (p.pairing in moved && st.plans.values.none { it.id == p.id && it.pairing == p.pairing }) LinkBackupCodec.plan(p)?.let { st.put(it) }
        for (t in f.transfers) if (t.pairing in moved && t.id !in st.transfers) LinkBackupCodec.transfer(t)?.let {
            st.put(TransferRecord(it.id, it.pairing, it.out, it.state, it.offer, it.mode, it.at, it.done, it.done, it.alone, null))
        }
        for (x in f.folders) if (x.pairing in moved && st.folders.size < app.heyrobot.core.link.FolderRules.MAX_FOLDERS && st.folders[app.heyrobot.core.link.FolderRec.key(x.pairing, x.fold)] == null) st.put(x)
        for (g in f.groups) if (g.id !in st.groups && g.members.any { it.pairing in moved }) st.put(g)
    }
}
