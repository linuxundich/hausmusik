// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import de.linuxundich.hausmusik.ui.theme.CoverColors

/*
 * Material 3 Expressive building blocks shared by every menu and dialog:
 * rounder menus with icons, and grouped choice lists instead of stacks of
 * text buttons.
 */

/** Menu colors: the theme's, or mixed from the cover on the stage and its sheets. */
data class MenuColors(val container: Color, val content: Color, val icon: Color)

@Composable
fun menuColors(cover: CoverColors? = null): MenuColors = if (cover != null) {
    MenuColors(lerp(cover.backgroundTop, cover.content, 0.12f), cover.content, cover.content.copy(alpha = 0.85f))
} else {
    MenuColors(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun AppMenu(expanded: Boolean, onDismiss: () -> Unit, colors: MenuColors = menuColors(), content: @Composable AppMenuScope.() -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = colors.container,
        offset = DpOffset(0.dp, 4.dp),
    ) { AppMenuScope(colors).content() }
}

class AppMenuScope(private val colors: MenuColors) {
    @Composable
    fun Item(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
        DropdownMenuItem(
            text = { Text(label, style = MaterialTheme.typography.bodyLarge) },
            leadingIcon = { Icon(icon, null) },
            onClick = onClick,
            enabled = enabled,
            colors = MenuDefaults.itemColors(
                textColor = colors.content, leadingIconColor = colors.icon,
                disabledTextColor = colors.content.copy(alpha = 0.38f), disabledLeadingIconColor = colors.icon.copy(alpha = 0.38f),
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            modifier = Modifier.widthIn(min = 200.dp),
        )
    }
}

/** One option of a [ChoiceGroup]. */
data class Choice(val icon: ImageVector?, val label: String, val supporting: String? = null, val onClick: () -> Unit)

/**
 * Options as one connected group: large outer corners, small inner ones and
 * 2 dp gaps, the list style of Material 3 Expressive.
 */
@Composable
fun ChoiceGroup(choices: List<Choice>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        choices.forEachIndexed { i, c ->
            val top = if (i == 0) 20.dp else 4.dp
            val bottom = if (i == choices.lastIndex) 20.dp else 4.dp
            Surface(
                onClick = c.onClick,
                shape = RoundedCornerShape(top, top, bottom, bottom),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    c.icon?.let { Icon(it, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary) }
                    Column(Modifier.padding(start = if (c.icon != null) 16.dp else 0.dp)) {
                        Text(c.label, style = MaterialTheme.typography.bodyLarge)
                        c.supporting?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
