// SPDX-License-Identifier: GPL-3.0-only
// Vendored from Readability4J 1.0.8 (https://github.com/dankito/Readability4J, commit 170d052e), a Kotlin port of Mozilla's
// Readability.js. Apache-2.0: see NOTICE and LICENSE in this folder. Changed for Robot (0.9 web search): the package, every
// slf4j logging call removed (no logging library may see page text), built against jsoup 1.23.2.
package app.heyrobot.helpers.web.readability.model


open class ReadabilityObject(var contentScore: Double)