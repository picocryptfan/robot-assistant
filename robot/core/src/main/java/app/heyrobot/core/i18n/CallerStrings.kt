// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// Caller name (docs/plans/caller-name-plan.md, idea 13): the notice while a number you don't have rings. Ids start with
// "caller."; the area is "people". Québec French (tu); render() adds its spaces. The switch's words are in
// values/strings_people.xml.

object CallerStrings : Strings("caller", area = "people") {
    /** {name}: the likely name only, never where it was found. */
    val probably = s("probably", "Probably {name}", "Probablement {name}")
    val calling = s("calling", "Calling from a number you don't have", "Appel d'un numéro que tu n'as pas")
    /** The lock screen's version: no name. */
    val unlock = s("unlock", "Unlock to see who's probably calling", "Déverrouille pour voir qui t'appelle probablement")
    val channel = s("channel", "Caller name", "Nom de l'appelant")
}
