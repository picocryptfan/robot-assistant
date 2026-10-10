package app.heyrobot.sys

import android.Manifest
import android.content.ComponentName
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
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import app.heyrobot.core.ContactCard
import app.heyrobot.core.ContactMatch
import app.heyrobot.core.ContactName
import app.heyrobot.core.ContactPhone
import app.heyrobot.core.NoteStore
import app.heyrobot.core.PhoneKind
import app.heyrobot.core.PhoneNumbers
import java.io.File
import java.util.Locale

// Main and voice processes. The phone's side of people, notes and hand-offs (docs/phase-2.md): ContactsContract
// (READ_CONTACTS, asked for by the main app only), PhoneNumberUtils, the apps Robot hands drafts to, and the Robot
// Notes folder. The choices are core's (People.kt, Notes.kt); nothing here logs names, numbers or text.

object Contacts {
    fun granted(ctx: Context): Boolean = Assistant.granted(ctx, Manifest.permission.READ_CONTACTS)

    /** Every contact's display name and nicknames, to rank against a name. */
    fun names(ctx: Context): List<ContactName> {
        val cr = ctx.contentResolver
        val names = LinkedHashMap<Long, String>()
        cr.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)
                if (!name.isNullOrBlank()) names[c.getLong(0)] = name
            }
        }
        val nicknames = HashMap<Long, MutableList<String>>()
        cr.query(Data.CONTENT_URI, arrayOf(Data.CONTACT_ID, Nickname.NAME), "${Data.MIMETYPE} = ?", arrayOf(Nickname.CONTENT_ITEM_TYPE), null)?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(1)
                if (!n.isNullOrBlank()) nicknames.getOrPut(c.getLong(0)) { mutableListOf() } += n
            }
        }
        return names.map { (id, n) -> ContactName(id, n, nicknames[id].orEmpty()) }
    }

    /** The numbers, email addresses and XMPP (Jabber) addresses of the [matches], in their order. */
    @Suppress("DEPRECATION") // Im's protocol columns: deprecated, still how a contact's Jabber address is stored.
    fun cards(ctx: Context, matches: List<ContactMatch>): List<ContactCard> {
        if (matches.isEmpty()) return emptyList()
        val ids = matches.map { it.id }
        val phones = HashMap<Long, MutableList<ContactPhone>>()
        val emails = HashMap<Long, MutableList<String>>()
        val xmpp = HashMap<Long, MutableList<String>>()
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
                            xmpp.getOrPut(id) { mutableListOf() } += value.removePrefix("xmpp:")
                        }
                    }
                }
            }
        }
        return matches.map { m -> ContactCard(m.id, m.name, phones[m.id].orEmpty(), emails[m.id].orEmpty(), xmpp[m.id].orEmpty()) }
    }

    private fun kind(type: Int): PhoneKind = when (type) {
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

/** Pages of the main app another process (the panel) or the Robot Notes launcher entry can open. */
object AppPages {
    const val EXTRA = "robot_page"
    /** Assistant setup, at "People, notes and calendar": the permissions and their grant buttons. */
    const val PERMISSIONS = "permissions"
    const val NOTES = "notes"
    /** The Robot Notes launcher entry (an activity-alias of MainActivity). */
    const val NOTES_ALIAS = "app.heyrobot.ui.NotesLauncher"
}

object NotesFiles {
    fun dir(ctx: Context): File = File(ctx.filesDir, "notes").apply { mkdirs() }
    fun store(ctx: Context): NoteStore = NoteStore(dir(ctx))
}
