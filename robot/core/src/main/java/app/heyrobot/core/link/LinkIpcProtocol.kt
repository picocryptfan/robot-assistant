// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §5.1; job L3): the hand-written Binder interface of :link's
// LinkService, as LlamaIpc is :llama's: a descriptor, a code per call, and each call's fields in a fixed order. Here, in
// core, so the processes that bind it (main's screens in L5, :voice's cards and sending in L6 and L7) name the service by
// this string and never by its class (ProcessIsolationTest's mainProcessCodeNeverReachesEngines). Only Robot's own
// processes may bind it: Robot's signature permission on the service, and each call's calling uid checked to be Robot's
// own (an isolated process, :linkparse's included, has another uid and is refused). Frames are strict: exactly the fields
// a call lists, each within its cap, nothing left over; a frame that breaks that is refused without a word.
//
// Every reply starts with an int: [REPLY_OK], or [REPLY_REFUSED] (not Robot's uid, a bad frame, Robot to Robot off or not
// set up) with nothing after it. Never Parcel.writeException/readException: what crosses is numbers, strings and bytes.

object LinkIpcProtocol {
    const val SERVICE_CLASS = "app.heyrobot.link.LinkService" // l10n: not text (a class name)
    const val DESCRIPTOR = "app.heyrobot.link.ILink" // l10n: not text (a Binder descriptor)

    const val REPLY_OK = 0
    const val REPLY_REFUSED = 1

    /** IBinder.FIRST_CALL_TRANSACTION. */
    private const val FIRST = 1

    /**
     * In: nothing. Out: the vault's state ([VAULT_OFF]…), Robot to Robot on (0/1), network allowed (0/1), then the node's:
     * pairings, hellos waiting, outbox, open codes, live connections, listening (0/1), Tor running (0/1), awake (0/1).
     */
    const val STATUS = FIRST
    /** In: nothing. Turning Robot to Robot on (link_settings.json says on): the keys and your identity. Out: 1 done, 0 not. */
    const val SET_UP = FIRST + 1
    /** In: nothing. Start over: everything unpaired, every key and record destroyed. Out: 1. */
    const val START_OVER = FIRST + 2
    /**
     * In: pairing id (16 bytes), the request's type code (int), its id (16 bytes), at (long, Unix seconds), ttl (long,
     * seconds), its padded bytes (at most [MAX_REQUEST]). Out: 0 queued, or the node's QueueProblem code.
     */
    const val SEND = FIRST + 3
    /**
     * In: mode code (int), in person (0/1). Out: a MakeCodeResult code; the code's id (16 bytes, or 0 bytes when none was
     * made); its text (an empty string when none).
     */
    const val MAKE_CODE = FIRST + 4
    /**
     * In: the code's text (at most [MAX_CODE_TEXT] characters), form (0 scanned, 1 pasted), your mode code. Out: a
     * UseCodeResult code; the pairing id (16 bytes, or 0 bytes when the result has none).
     */
    const val USE_CODE = FIRST + 5
    /** In: nothing. "Cancel this code": every open code's one-time routes deleted. Out: 1. */
    const val CANCEL_CODES = FIRST + 6
    /** In: pairing id (16 bytes). Out: 1 unpaired, 0 no such pairing. */
    const val UNPAIR = FIRST + 7
    /** In: a package from [PUSH_APPS] (empty: none). Your tap in the Push app row, never a default. Out: 1 saved, 0 not. */
    const val CHOOSE_PUSH = FIRST + 8
    /** One way. In: nothing. A client is using :link now (stay awake: listen, run Tor when needed). */
    const val WAKE = FIRST + 9

    // 1.5 L5, pairing and People (robot-to-robot-plan.md §2.3, §2.6, §2.7, §6.2, §6.3). Each changes one pairing by its id
    // (16 bytes) and answers 1 done, 0 not (no such pairing, or not now); reads answer what they read.

    /** In: a code's id (16 bytes). Out: a CodeState code; the pairing id (16 bytes, or 0 bytes). */
    const val CODE_STATE = FIRST + 10
    /** In: nothing. Out: every pairing as core PairingView.encodeAll writes them. */
    const val PAIRINGS = FIRST + 11
    /** In: pairing id. Out: its safety number, 60 digits (with the new key while it is MOVED), or an empty string. */
    const val SAFETY = FIRST + 12
    /** In: pairing id. "They match": the pairing is verified from now. */
    const val VERIFY = FIRST + 13
    /** In: pairing id, a People id (at most [MAX_PERSON] characters; empty: nobody). "Who is this". */
    const val SET_PERSON = FIRST + 14
    /** In: pairing id, a preset's code (core LinkLevels.presetCode): every level set to the preset's (D8). */
    const val SET_PRESET = FIRST + 15
    /** In: pairing id, a capability's code, a level's code (core LinkLevels): your tap on the permissions page. */
    const val SET_LEVEL = FIRST + 16
    /** In: pairing id. "Raise to Anonymous": at once, on both sides. */
    const val RAISE = FIRST + 17
    /** In: pairing id. "Ask to switch to Private", or, when they asked, "Switch to Private" (both agreed). */
    const val LOWER = FIRST + 18
    /** In: pairing id. "Keep Anonymous": their ask to switch to Private is declined. */
    const val KEEP_MODE = FIRST + 19
    /** In: pairing id, a label (the name you knew them by, at most [MAX_LABEL] characters). Unpair, and drop their hellos from now on. */
    const val BLOCK = FIRST + 20
    /** In: nothing. Out: the Robots you blocked, as core BlockedView.encodeAll writes them. */
    const val BLOCKED = FIRST + 21
    /** In: a block's id (16 bytes). Unblock. */
    const val UNBLOCK = FIRST + 22
    /** In: pairing id, accept (0/1). A moved hello: accepted after "They match" (its new key pinned, a new session), or the old phone kept. */
    const val ANSWER_MOVED = FIRST + 23
    /** In: a code's id (16 bytes). That code alone cancelled (leaving the in-person screen closes the code it showed). */
    const val CANCEL_CODE = FIRST + 24


    // 1.5 L6, cards, levels and briefings (robot-to-robot-plan.md §4.2, §4.4, §6.4): the desk (core LinkDesk). What shows a
    // request's contents needs the phone unlocked; notices carry kinds, pairings and counts only, and are read while locked.

    /** In: nothing. The waiting inbox opened. Out: the desk as core DeskView.encode writes it. Refused while locked. */
    const val DESK = FIRST + 25
    /** In: a card's id (16 bytes), a DeskChoice code (int). Your Yes or Decline. Out: 1 answered, 0 not. Refused while locked. */
    const val DESK_ANSWER = FIRST + 26
    /** In: an entry's id (16 bytes). Undo of what Robot did on its own. Out: 1 undone, 0 not. Refused while locked. */
    const val DESK_UNDO = FIRST + 27
    /** In: nothing. Out: the notices since the last call and the waiting count, as core DeskNotices.encode writes them. */
    const val DESK_NOTICES = FIRST + 28
    /** In: pairing id, a capability's code. "Not now" on Robot's proposal (Turn on is [SET_LEVEL]). Out: 1 done, 0 not. */
    const val DESK_NOT_NOW = FIRST + 29


    // 1.5 L7, calendar and sending (robot-to-robot-plan.md §4.1, §4.3, §4.4). Sending and plans need the phone unlocked.

    /**
     * In: pairing id, the plan's title (at most [MAX_TITLE] characters), place and note (empty: none; at most [MAX_PLACE] and
     * [MAX_NOTE]), the number of times (1 to 3) and each (long, Unix seconds), its length in minutes (long). Your plan
     * proposed (`ev.propose`), kept so its answer finds it. Out: the plan's id (16 bytes), or 0 bytes when it couldn't go.
     * Refused while locked.
     */
    const val PLAN_SEND = FIRST + 30
    /** In: nothing. Out: every plan kept, as core LinkPlan.encodeAll writes them. Refused while locked. */
    const val PLANS = FIRST + 31
    /** In: pairing id, plan id (16 bytes). Your cancel: off your calendar, their Robot told. Out: 1 done, 0 not. Refused while locked. */
    const val PLAN_CANCEL = FIRST + 32
    /** In: a reply line's id (16 bytes). :voice said it: not announced again. Out: 1 done, 0 not. */
    const val DESK_SEEN = FIRST + 33
    /** L10: in: a question card's id (16 bytes), your typed answer (at most [MAX_ANSWER] characters). Out: 1 sent, 0 not. Refused while locked. */
    const val DESK_REPLY = FIRST + 34
    /**
     * L8: a file for another Robot. In: its [TransferView] alone in TransferView.encodeAll (at most [MAX_TRANSFER] bytes), its
     * 32-byte transfer key. The copy is already sealed in the link folder's outgoing/. Out: 1 offered, 0 not (the copy gone).
     * Refused while locked.
     */
    const val FILE_SEND = FIRST + 35
    /** L8: out: every file sent and received, as TransferView.encodeAll. Refused while locked. */
    const val TRANSFERS = FIRST + 36
    /** L8: in: a received transfer's id (16 bytes), and whether Files took it. Its file in incoming/link/ goes. Out: 1 done, 0 not. Refused while locked. */
    const val TRANSFER_FILED = FIRST + 37

    /** L8: the card's question answered with a file you just offered. */
    const val DESK_FILE_ANSWER = FIRST + 38
    /**
     * L12: in: nothing. Out: core LinkBackupCodec's file (at most [LinkBackupCodec.MAX_BYTES], UTF-8), or 0 bytes when it can't
     * be read now. Holds no key. Only unlocked.
     */
    const val BACKUP_EXPORT = FIRST + 39
    /** L12: "Tell my Robots I moved". In: pairing id. Out: 1 on its way, 0 no way to reach it (no push address, or not now). Only unlocked. */
    const val SEND_MOVED = FIRST + 40
    // 1.5, shared lists. Far from the codes before them: other work adds there. Each refused while locked (LinkService).
    /** A part or a batch of a list from :voice as a DeskRequest (frame, plain); replies its request's id, or nothing. */
    /** L11: invite [pairing]'s Robot to a new shared folder named [name]: the folder's id. */
    const val FOLDER_INVITE = FIRST + 41
    /** L11: a file of yours in a folder (the `fold.op` goes before its `file.offer`). */
    const val FOLDER_ADD = FIRST + 42
    /** L11: a file you added comes out of a folder (your own file stays in Robot Files). */
    const val FOLDER_REMOVE = FIRST + 43
    /** L11: you leave a folder (their Robot is told, your copies stay). */
    const val FOLDER_LEAVE = FIRST + 44
    /** L11: every shared folder (core FolderRec). */
    const val FOLDERS = FIRST + 45
    /** L11: a group plan (core LinkGroup) starts: each person's Robot is asked when it's busy. */
    const val GROUP_START = FIRST + 46
    /** L11: a group plan is proposed to everyone, at one time. */
    const val GROUP_PROPOSE = FIRST + 47
    /** L11: you cancel a group plan you organize. */
    const val GROUP_CANCEL = FIRST + 48
    /** L11: every group plan (core GroupsView). */
    const val GROUPS = FIRST + 49
    const val LIST_SEND = FIRST + 60
    /** Stop sharing a list with a pairing: their Robot is told. */
    const val LIST_LEAVE = FIRST + 61
    /** Requests of lists ready to apply (ListShareItem.encodeAll). */
    const val LIST_PENDING = FIRST + 62
    /** Those requests were applied: they go from the sealed inbox. */
    const val LIST_APPLIED = FIRST + 63
    /** Where each list stands (ListShareView.encodeAll): ids and counts. */
    const val LIST_SHARES = FIRST + 64
    /** Everything on a list is owed again: the record's lost flag clears. */
    const val LIST_LOST_SEEN = FIRST + 65

    /** Out: the chosen package (empty: none), pairings waiting for an address, the count, then each app's package and name. */
    const val PUSH_APPS = FIRST + 66

    const val LAST = PUSH_APPS

    const val VAULT_OFF = 0
    const val VAULT_READY = 1
    const val VAULT_LOCKED = 2
    const val VAULT_GONE = 3
    const val VAULT_NEWER = 4

    const val ID_BYTES = 16
    /** A live request at most (core LinkRequests.LIVE). */
    const val MAX_REQUEST = 32 * 1024
    /** A pasted code is short (PairCode.MAX_TEXT is 1,578); a paste may hold other words around it. */
    const val MAX_CODE_TEXT = 8 * 1024
    /** A People id (a UUID) at most. */
    const val MAX_PERSON = 64
    /** Your typed answer to a question at most (core RequestCaps.ANSWER). */
    const val MAX_ANSWER = 2_000
    /** One offer's view: an offer is at most a few hundred bytes of fields. */
    const val MAX_TRANSFER = 4 * 1024
    /** A block's label at most (core BlockedView.BLOCK_LABEL), in characters. */
    const val MAX_LABEL = 200
    /** L7: a plan's words at most, in UTF-16 characters (twice each field's cap in code points). */
    const val MAX_TITLE = 2 * app.heyrobot.core.link.requests.RequestCaps.TITLE
    const val MAX_PLACE = 2 * app.heyrobot.core.link.requests.RequestCaps.PLACE
    const val MAX_NOTE = 2 * app.heyrobot.core.link.requests.RequestCaps.NOTE
}
