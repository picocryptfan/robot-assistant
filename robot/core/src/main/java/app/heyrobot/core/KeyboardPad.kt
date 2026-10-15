// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

/**
 * Pure. How much room the panel leaves at its bottom (0.4.2), in pixels: enough to clear the navigation bar, or the
 * keyboard, whichever is higher, less any distance Android already slid the panel's window up for the keyboard.
 *
 * The panel's window asks Android to report the keyboard ("resize") and pads itself for it. On the Pixel the
 * assistant's window still slid up ("pan") as well, so 0.4.0 showed the keyboard's height twice: a gap between the
 * text box and the keyboard. Subtracting the slide (the window's top above the screen's, which a View's position on
 * screen includes) keeps the box on the keyboard whichever Android does.
 */
object KeyboardPad {
    fun bottom(navPx: Int, imePx: Int, slidPx: Int): Int = maxOf(navPx, imePx - slidPx.coerceAtLeast(0), 0)
}
