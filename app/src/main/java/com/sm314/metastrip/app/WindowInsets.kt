/*
 * MetaStrip - removes all metadata from image files.
 * Copyright (C) 2026  sm314.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.sm314.metastrip.app

import android.app.Activity
import android.view.View
import com.sm314.metastrip.R
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Keeps content clear of the status bar, the navigation bar and any display
 * cutout.
 *
 * Apps targeting SDK 35 and above are drawn edge to edge on Android 15 and
 * newer, whether they ask for it or not, so a button pinned to the bottom of
 * the layout ends up underneath the navigation bar. Padding the root view by
 * the system bar insets puts it back where it belongs.
 *
 * On Android 14 and below the window does not extend behind the bars, so the
 * reported insets are zero and this changes nothing. The root keeps its own
 * background, so the bars still show the app's surface colour rather than a
 * gap.
 *
 * A little extra space is added below the status bar so the toolbar title does
 * not sit flush against the clock and status icons.
 */
fun Activity.applyWindowInsets(root: View) {
    val extraTop = root.resources.getDimensionPixelSize(R.dimen.content_top_spacing)
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.updatePadding(
            left = bars.left,
            top = bars.top + extraTop,
            right = bars.right,
            bottom = bars.bottom
        )
        // Insets are handled here, so nothing further down the tree re-applies
        // them and double-pads the content.
        WindowInsetsCompat.CONSUMED
    }
}
