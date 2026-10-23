// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.guide

// Tests only (see core/EnglishView.kt): the setup guide's summary choice in English.

val SummaryLine.value: String? get() = valueText?.en()
