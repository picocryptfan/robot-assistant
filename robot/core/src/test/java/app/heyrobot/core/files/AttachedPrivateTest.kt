// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F50: a file attached in the panel gets the private-file check read_file's do. */
class AttachedPrivateTest {
    @Test fun aCardNumberInTheTextIsPrivate() = assertTrue(FilesForHelper.attachedPrivate("notes.txt", "Visa 4111 1111 1111 1111 exp 04/29"))

    @Test fun aPasswordInTheNameIsPrivate() = assertTrue(FilesForHelper.attachedPrivate("password: hunter2-Secret!", "Nothing here."))

    @Test fun aDiagnosisIsPrivate() = assertTrue(FilesForHelper.attachedPrivate("visit.txt", "Patient: J. Doe. Diagnosis: asthma. Prescription: inhaler."))

    @Test fun anOrdinaryFileIsNot() = assertFalse(FilesForHelper.attachedPrivate("trip.txt", "We drive to Moncton on Friday and stay two nights."))
}
