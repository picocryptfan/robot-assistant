package app.heyrobot.sys

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Data
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import app.heyrobot.core.AddressBook
import app.heyrobot.core.CheogramLinks
import app.heyrobot.core.CheogramPick
import app.heyrobot.core.ContactCard
import app.heyrobot.core.ContactIds
import app.heyrobot.core.ContactMatch
import app.heyrobot.core.ContactName
import app.heyrobot.core.ContactPhone
import app.heyrobot.core.NoteStore
import app.heyrobot.core.People
import app.heyrobot.core.PeopleStore
import app.heyrobot.core.Person
import app.heyrobot.core.PersonSource
import app.heyrobot.core.PhoneKind
import app.heyrobot.core.PhoneNumbers
import app.heyrobot.core.XmppAddress
import app.heyrobot.core.XmppLink
import java.io.File
import java.util.Locale

// Main and voice processes. The phone's side of people, notes and hand-offs (docs/phase-2.md, docs/people.md):
// ContactsContract (READ_CONTACTS, asked for by the main app only), PhoneNumberUtils, the apps Robot hands drafts to,
// the Robot Notes folder and Robot's People. The choices are core's (People.kt, PeopleStore.kt, Notes.kt); nothing here
// logs names, numbers or text.

object Contacts {
    fun granted(ctx: Context): Boolean = Assistant.granted(ctx, Manifest.permission.READ_CONTACTS)

    /** Every contact's display name, nicknames and lookup key, to rank against a name. */
    fun names(ctx: Context): List<ContactName> {
        val cr = ctx.contentResolver
        val names = LinkedHashMap<Long, String>()
        val keys = HashMap<Long, String>()
        cr.query(ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.LOOKUP_KEY),
            null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)
                if (!name.isNullOrBlank()) names[c.getLong(0)] = name
                c.getString(2)?.takeIf { it.isNotBlank() }?.let { keys[c.getLong(0)] = it }
            }
        }
        val nicknames = HashMap<Long, MutableList<String>>()
        cr.query(Data.CONTENT_URI, arrayOf(Data.CONTACT_ID, Nickname.NAME), "${Data.MIMETYPE} = ?", arrayOf(Nickname.CONTENT_ITEM_TYPE), null)?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(1)
                if (!n.isNullOrBlank()) nicknames.getOrPut(c.getLong(0)) { mutableListOf() } += n
            }
        }
        return names.map { (id, n) -> ContactName(ContactIds.system(id), n, nicknames[id].orEmpty(), lookupKey = keys[id]) }
    }

    /** The numbers, email addresses and XMPP (Jabber) addresses of the [matches], in their order. */
    @Suppress("DEPRECATION") // Im's protocol columns: deprecated, still how a contact's Jabber address is stored.
    fun cards(ctx: Context, matches: List<ContactMatch>): List<ContactCard> {
        val ids = matches.mapNotNull { ContactIds.systemId(it.id) }
        if (ids.isEmpty()) return emptyList()
        val phones = HashMap<Long, MutableList<ContactPhone>>()
        val emails = HashMap<Long, MutableList<String>>()
        val xmpp = HashMap<Long, MutableList<XmppAddress>>()
        val sel = "${Data.CONTACT_ID} IN (${ids.joinToString(",") { "?" }}) AND ${Data.MIMETYPE} IN (?, ?, ?)"
        val args = ids.map { it.toString() } + listOf(Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE, Im.CONTENT_ITEM_TYPE)
        val projection = arrayOf(Data.CONTACT_ID, Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3, Im.PROTOCOL, Im.CUSTOM_PROTOCOL)
        ctx.contentResolver.query(Data.CONTENT_URI, projection, sel, args.toTypedArray(), "${Data.IS_SUPER_PRIMARY} DESC, ${Data._ID}")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val value = c.getString(2)?.trim()
                if (value.isNullOrEmpty()) continue
                when (c.getString(1)) {
                    Phone.CONTENT_ITEM_TYPE -> {
                        val type = if (c.isNull(3)) Phone.TYPE_OTHER else c.getInt(3)
                        val label = Phone.getTypeLabel(ctx.resources, type, c.getString(4)).toString()
                        phones.getOrPut(id) { mutableListOf() } += ContactPhone(value, kind(type), label)
                    }
                    Email.CONTENT_ITEM_TYPE -> emails.getOrPut(id) { mutableListOf() } += value
                    Im.CONTENT_ITEM_TYPE -> {
                        val protocol = if (c.isNull(5)) null else c.getInt(5)
                        val custom = c.getString(6)?.lowercase(Locale.ROOT)
                        if (protocol == Im.PROTOCOL_JABBER || (protocol == Im.PROTOCOL_CUSTOM && (custom == "xmpp" || custom == "jabber"))) {
                            xmpp.getOrPut(id) { mutableListOf() } += XmppAddress(value.removePrefix("xmpp:"))
                        }
                    }
                }
            }
        }
        return matches.mapNotNull { m ->
            val id = ContactIds.systemId(m.id) ?: return@mapNotNull null
            ContactCard(m.id, m.name, phones[id].orEmpty(), emails[id].orEmpty(), xmpp[id].orEmpty())
        }
    }

    /** A contact's lookup key (what a remembered choice for it is kept under), or null. */
    fun lookupKey(ctx: Context, id: Long): String? = try {
        ctx.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.LOOKUP_KEY),
            "${ContactsContract.Contacts._ID} = ?", arrayOf(id.toString()), null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) { null }

    internal fun kind(type: Int): PhoneKind = when (type) {
        Phone.TYPE_MOBILE, Phone.TYPE_WORK_MOBILE -> PhoneKind.MOBILE
        Phone.TYPE_HOME -> PhoneKind.HOME
        Phone.TYPE_WORK, Phone.TYPE_COMPANY_MAIN -> PhoneKind.WORK
        else -> PhoneKind.OTHER
    }
}

object Phones {
    /** E.164 through PhoneNumberUtils.formatNumberToE164 with the region from settings (core checks its answer). */
    fun e164(raw: String, region: String): String? = PhoneNumbers.e164(raw, region) { n, r ->
        try { PhoneNumberUtils.formatNumberToE164(n, r) } catch (_: Exception) { null }
    }

    /** "+1 514-555-0101" for the card (PhoneNumberUtils keeps the international form of an E.164 number). */
    fun display(e164: String, region: String): String =
        try { PhoneNumberUtils.formatNumber(e164, PhoneNumbers.region(region) ?: "") } catch (_: Exception) { null } ?: e164

    /** The phone's own region (SIM, mobile network, then the language setting), for when none is set. */
    fun deviceRegion(ctx: Context): String? {
        val tm = try { ctx.getSystemService(TelephonyManager::class.java) } catch (_: Throwable) { null }
        return PhoneNumbers.deviceRegion(
            try { tm?.simCountryIso } catch (_: Throwable) { null },
            try { tm?.networkCountryIso } catch (_: Throwable) { null },
            java.util.Locale.getDefault().country,
        )
    }
}

/** The apps Robot hands drafts to. Package visibility comes from the manifest's <queries> (no QUERY_ALL_PACKAGES). */
object HandOffApps {
    const val CHEOGRAM = "com.cheogram.android"
    const val CHEOGRAM_PLAY = "com.cheogram.android.playstore"
    const val PROTON = "ch.protonmail.android"

    fun installed(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) { false }

    /** Cheogram's package, or null. With both builds installed the F-Droid one is always used (README, "Texts"). */
    fun cheogram(ctx: Context): String? = listOf(CHEOGRAM, CHEOGRAM_PLAY).firstOrNull { installed(ctx, it) }

    fun proton(ctx: Context): Boolean = installed(ctx, PROTON)

    fun resolves(ctx: Context, i: Intent): Boolean = i.resolveActivity(ctx.packageManager) != null

    /** The default SMS app's name ("Messages"), for the card; null if there is none. */
    fun smsAppName(ctx: Context): String? = try {
        Telephony.Sms.getDefaultSmsPackage(ctx)?.let { pkg ->
            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
        }
    } catch (_: Exception) { null }

    fun smsIntent(): Intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))

    fun mailIntent(): Intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))

    /** Robot's own main app on a page (the permission screen, Notes): the voice process names it as a string. */
    fun robotPage(ctx: Context, page: String): Intent =
        Intent().setComponent(ComponentName(ctx.packageName, "app.heyrobot.ui.MainActivity")).putExtra(AppPages.EXTRA, page)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * Cheogram's own screens (docs/people.md "Ways in" 1 and 2). Both builds keep Conversations' class names for them, and
 * Robot names them explicitly, so they open whatever their intent filters say.
 */
object CheogramIntents {
    /** Cheogram's searchable contact list, started for a result (its "Create shortcut" screen; nothing goes on the home screen). */
    fun pick(pkg: String): Intent = Intent(Intent.ACTION_CREATE_SHORTCUT).setClassName(pkg, CheogramLinks.SHORTCUT_ACTIVITY)

    /** The installed Cheogram exports its contact list for a result. */
    fun canPick(ctx: Context, pkg: String): Boolean = HandOffApps.resolves(ctx, pick(pkg))

    /**
     * Each hand-off starts in a task of its own (0.4.0), so it always runs: started into Cheogram's existing task, an intent
     * equal to that task's first one (Android ignores the extras, so the share screen's text) only brought the task to the
     * front, on Cheogram's last screen. Cheogram's screens finish once they open the chat (ConversationsActivity, in
     * Cheogram's own task), and Android then drops the extra task from Recents. Cheogram's own back stack is left alone.
     */
    const val FRESH = Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK

    /**
     * The chat with [jid] in your [account], with [body] in its box: StartConversationActivity reads the xmpp: link (body
     * encoded twice, as XmppLink writes it) and the "account" extra, so a contact in several accounts needs no search.
     */
    fun chat(pkg: String, jid: String, account: String, body: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(XmppLink.message(jid, body))).setClassName(pkg, CheogramLinks.START_CONVERSATION)
            .putExtra(CheogramLinks.EXTRA_ACCOUNT, account).addFlags(FRESH)

    /** The xmpp: link alone (Cheogram picks the account; with the contact in several, it shows a search first). */
    fun link(pkg: String, jid: String, body: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(XmppLink.message(jid, body))).setPackage(pkg).addFlags(FRESH)

    /** Cheogram's share screen with [body], by name and fresh ([CheogramLinks.SHARE_ACTIVITY]): you choose who it goes to there. */
    fun share(pkg: String, body: String): Intent = Intent(Intent.ACTION_SEND).setClassName(pkg, CheogramLinks.SHARE_ACTIVITY).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, body).addFlags(FRESH)

    /**
     * The same share as before 0.4.0, to the package rather than by name: for a Cheogram build whose share screen isn't
     * [CheogramLinks.SHARE_ACTIVITY] ([share] doesn't resolve). Still in a task of its own.
     */
    fun shareAny(pkg: String, body: String): Intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body).setPackage(pkg)
        .addFlags(FRESH)

    /**
     * Who was picked, from the list's result: EXTRA_SHORTCUT_NAME, and EXTRA_SHORTCUT_INTENT's xmpp: link and "account"
     * extra. The icon is never read, and nothing is pinned. Null when the result holds no usable address.
     */
    @Suppress("DEPRECATION") // The legacy shortcut extras: what ACTION_CREATE_SHORTCUT still answers with.
    fun picked(data: Intent?): CheogramPick? = read(data).first

    /** [picked], and whether the answer could be read at all (false: its extras didn't unparcel). */
    fun read(data: Intent?): Pair<CheogramPick?, Boolean> = try {
        val target = data?.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT, Intent::class.java)
        CheogramLinks.pick(data?.getStringExtra(Intent.EXTRA_SHORTCUT_NAME), target?.dataString, target?.getStringExtra(CheogramLinks.EXTRA_ACCOUNT)) to true
    } catch (_: Exception) {
        null to false
    }
}

/**
 * The phone's contacts without the contacts permission (docs/people.md "Add from phone contacts" and "Ways out"): the
 * system contact picker, which hands back only what you pick, and the Contacts app's own "new contact" screen, where you
 * choose the account and save. Robot never writes to the address book itself (no WRITE_CONTACTS).
 */
object PhoneContacts {
    /**
     * Android 17's contact picker (Intent.ACTION_PICK_CONTACTS and its extras, API 37; Robot builds against 36, so by
     * name). It returns a session URI to read the picked contact's numbers and emails once.
     */
    const val ACTION_PICK_CONTACTS = "android.intent.action.PICK_CONTACTS"
    private const val EXTRA_FIELDS = "android.intent.extra.PICK_CONTACTS_REQUESTED_DATA_FIELDS"
    private const val EXTRA_LIMIT = "android.intent.extra.PICK_CONTACTS_SELECTION_LIMIT"
    private const val EXTRA_MATCH_ALL = "android.intent.extra.PICK_CONTACTS_MATCH_ALL_DATA_FIELDS"

    /** One contact, its numbers and emails. */
    fun picker(): Intent = Intent(ACTION_PICK_CONTACTS)
        .putStringArrayListExtra(EXTRA_FIELDS, arrayListOf(Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE))
        .putExtra(EXTRA_LIMIT, 1)
        .putExtra(EXTRA_MATCH_ALL, false)

    /** The older picker: one number ([email] false) or one email address, with the contact's name (Android 17 upgrades it). */
    fun legacy(email: Boolean): Intent = Intent(Intent.ACTION_PICK).setType(if (email) Email.CONTENT_TYPE else Phone.CONTENT_TYPE)

    /**
     * The person the picker's [uri] holds (Android 17's session URI, or the older picker's data row), or null. Read once,
     * right away: the access is temporary.
     */
    fun read(ctx: Context, uri: Uri, now: Long): Person? {
        val rows = ArrayList<PickedRow>()
        val cr = ctx.contentResolver
        // The session URI takes a projection only (no selection); the older picker's row has the same columns. The
        // narrow one names no contact (a data row's _ID isn't one): its rows are all the picked contact's.
        val wide = arrayOf(Data.CONTACT_ID, Data.DISPLAY_NAME_PRIMARY, Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3)
        val narrow = arrayOf(Data.DISPLAY_NAME_PRIMARY, Data.MIMETYPE, Data.DATA1)
        for (projection in listOf(wide, narrow)) {
            try {
                cr.query(uri, projection, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        rows += if (projection === wide) PickedRow(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5))
                        else PickedRow(null, c.getString(0), c.getString(1), c.getString(2), null, null)
                    }
                }
                break
            } catch (_: Exception) {
                rows.clear()
            }
        }
        return person(rows, now, PeopleStore.newId())
    }

    /** One row the picker's URI gave: its contact (null: the row doesn't say), name, mimetype and DATA1 to DATA3. */
    internal data class PickedRow(val contact: String?, val name: String?, val mime: String?, val data1: String?, val data2: String?, val data3: String?)

    /** The picked contact in [rows]: every row of the first row's contact (every row, when they name none). */
    internal fun person(rows: List<PickedRow>, now: Long, id: String): Person? {
        val first = rows.firstOrNull() ?: return null
        val mine = if (first.contact == null) rows else rows.filter { it.contact == first.contact }
        val name = mine.firstNotNullOfOrNull { it.name?.takeIf { n -> n.isNotBlank() } } ?: return null
        val phones = mine.filter { it.mime == Phone.CONTENT_ITEM_TYPE && !it.data1.isNullOrBlank() }.map { r ->
            val type = r.data2?.toIntOrNull() ?: Phone.TYPE_OTHER
            val k = Contacts.kind(type)
            ContactPhone(r.data1!!.trim(), k, if (type == Phone.TYPE_CUSTOM && !r.data3.isNullOrBlank()) r.data3 else People.label(k))
        }
        val emails = mine.filter { it.mime == Email.CONTENT_ITEM_TYPE && !it.data1.isNullOrBlank() }.map { it.data1!!.trim() }
        return Person(id, name, phones = phones, emails = emails, source = PersonSource.PHONE_PICKER, confirmed = true, updatedAt = now)
    }

    private fun type(k: PhoneKind): Int = when (k) {
        PhoneKind.MOBILE -> Phone.TYPE_MOBILE
        PhoneKind.HOME -> Phone.TYPE_HOME
        PhoneKind.WORK -> Phone.TYPE_WORK
        PhoneKind.OTHER -> Phone.TYPE_OTHER
    }

    /**
     * "Save to phone's contacts": the Contacts app's new-contact screen filled in with the name, what you call them
     * (nicknames), numbers, emails and XMPP addresses (IM, Jabber, which Cheogram reads). You choose the account and save
     * there; then Cheogram, Proton Mail and the dialer know the name too.
     */
    @Suppress("DEPRECATION") // Im's protocol columns: deprecated, still how a Jabber address is stored and read.
    fun insert(p: Person): Intent {
        val rows = ArrayList<ContentValues>()
        p.aliases.forEach { rows += ContentValues().apply { put(Data.MIMETYPE, Nickname.CONTENT_ITEM_TYPE); put(Nickname.NAME, it) } }
        p.phones.forEach { rows += ContentValues().apply {
            put(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE); put(Phone.NUMBER, it.number); put(Phone.TYPE, type(it.kind))
        } }
        p.emails.forEach { rows += ContentValues().apply { put(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE); put(Email.ADDRESS, it); put(Email.TYPE, Email.TYPE_OTHER) } }
        p.xmpp.forEach { rows += ContentValues().apply {
            put(Data.MIMETYPE, Im.CONTENT_ITEM_TYPE); put(Im.DATA, it.jid); put(Im.TYPE, Im.TYPE_OTHER); put(Im.PROTOCOL, Im.PROTOCOL_JABBER)
        } }
        return Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
            .putExtra(ContactsContract.Intents.Insert.NAME, p.name)
            .putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA, rows)
    }
}

/**
 * Robot's notification access, for "Learn names from Cheogram notifications" (the listener runs in the main process;
 * its class is named here as a string). Apps installed from a file need "Allow restricted settings" (App info › ⋮)
 * before Android lets you turn the access on.
 */
object NotificationAccess {
    const val LISTENER = "app.heyrobot.people.CheogramListener"

    fun component(ctx: Context): ComponentName = ComponentName(ctx.packageName, LISTENER)

    fun granted(ctx: Context): Boolean = try {
        ctx.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(ctx))
    } catch (_: Throwable) { false }

    /** Android's page for Robot's notification access, else the list of apps that have it. */
    fun settings(ctx: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(ctx).flattenToString()),
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
    )

    /** Robot's App info, where ⋮ › Allow restricted settings is. */
    fun appInfo(ctx: Context): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))

    /** The switch was turned on: Android binds the listener again (it unbinds itself while the switch is off). */
    fun rebind(ctx: Context) {
        try { NotificationListenerService.requestRebind(component(ctx)) } catch (_: Throwable) {}
    }
}

/** Pages of the main app another process (the panel) or the Robot Notes launcher entry can open. */
object AppPages {
    const val EXTRA = "robot_page"
    /** Assistant setup, at "People, notes and calendar": the permissions and their grant buttons. */
    const val PERMISSIONS = "permissions"
    const val NOTES = "notes"
    /** 0.5.0: the Transcripts library, or one transcript ([EXTRA_TRANSCRIPT]); Settings › Conversation (the tile's long press). */
    const val TRANSCRIPTS = "transcripts"
    const val CONVERSATION_SETTINGS = "conversation_settings"
    /** 0.6.0: Settings › Helpers (the summary screen's link when no stronger AI is set up). */
    const val HELPERS = "helpers"
    const val EXTRA_TRANSCRIPT = "robot_transcript"
    /** 0.5.0, phase 5.6: the line a summary's [14:03] link points at, in [EXTRA_TRANSCRIPT]. */
    const val EXTRA_LINE = "robot_transcript_line"
    /** Settings › Model, the Model tab (0.4.3: from a chat-only model's answer to an action). */
    const val MODEL = "model"
    /** Settings › Updates (the updater's notifications). */
    const val UPDATES = "updates"
    /** 0.7: Settings › Memory (a memory card's "Open Memory" in the panel). */
    const val MEMORY = "memory"
    /** 0.7: Settings › About › Send feedback, from a feedback phrase in the panel, with its kind ([EXTRA_FEEDBACK_KIND]). */
    const val FEEDBACK = "feedback"
    const val EXTRA_FEEDBACK_KIND = "robot_feedback_kind"
    /** The Robot Notes launcher entry (an activity-alias of MainActivity). */
    const val NOTES_ALIAS = "app.heyrobot.ui.NotesLauncher"
}

object NotesFiles {
    fun dir(ctx: Context): File = File(ctx.filesDir, "notes").apply { mkdirs() }
    fun store(ctx: Context): NoteStore = NoteStore(dir(ctx))
}

/** Robot's People (docs/people.md): files/people/people.json, changed under a lock both app processes take. */
object PeopleFiles {
    fun dir(ctx: Context): File = File(ctx.filesDir, "people").apply { mkdirs() }
    fun store(ctx: Context): PeopleStore = PeopleStore(dir(ctx))

    /** Turning learning on trusts the Cheogram installed then: its signer is kept again at its next notification. */
    fun forgetCheogramSigner(ctx: Context) {
        File(dir(ctx), app.heyrobot.core.CheogramSigners.FILE).delete()
    }

    /**
     * People and, when Robot may read them, the phone's contacts, as one address book for a name. Contacts that can't be
     * read (not allowed) leave People alone.
     */
    fun book(ctx: Context): AddressBook = AddressBook(if (Contacts.granted(ctx)) Contacts.names(ctx) else emptyList(), store(ctx).list())
}
