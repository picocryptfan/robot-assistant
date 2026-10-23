// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.parse

// Pure. Which copy of a file leaves the phone (a share, an export, a helper's card: F7 and later), by Settings › Files'
// "Strip location from photos I send" (§6.6, on by default). On, an image :files decodes goes as its STRIP copy: re-encoded
// from its pixels with no metadata at all (location, camera, dates, comments), its orientation applied to the pixels. An
// image or video Robot can't re-encode may hold a location it can't remove: CANT_STRIP, for the caller to say so before
// anything goes. Off, or for documents and text, the file goes as you added it.

enum class OutgoingCopy { ORIGINAL, STRIPPED, CANT_STRIP }

object Outgoing {
    fun copy(stripLocation: Boolean, type: FileType): OutgoingCopy = when {
        !stripLocation -> OutgoingCopy.ORIGINAL
        type.group == FileGroup.IMAGE -> OutgoingCopy.STRIPPED
        type.group == FileGroup.IMAGE_OTHER || type.group == FileGroup.VIDEO -> OutgoingCopy.CANT_STRIP
        else -> OutgoingCopy.ORIGINAL
    }
}
