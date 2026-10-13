// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.common

import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Landscape on a phone: wider than high. Height, not width, is the scarce resource then. */
@Composable
fun isWide(): Boolean {
    val c = LocalConfiguration.current
    return c.screenWidthDp > c.screenHeightDp
}

@OptIn(ExperimentalMaterial3Api::class)
/** Sheets get wider in landscape so they can use two columns. */
@Composable
fun sheetMaxWidth(): Dp = if (isWide()) 920.dp else BottomSheetDefaults.SheetMaxWidth

@OptIn(ExperimentalMaterial3Api::class)
/** The drag handle costs about 40 dp; in landscape that is a tenth of the screen. */
@Composable
fun sheetDragHandle(): (@Composable () -> Unit)? = if (isWide()) null else ({ BottomSheetDefaults.DragHandle() })
