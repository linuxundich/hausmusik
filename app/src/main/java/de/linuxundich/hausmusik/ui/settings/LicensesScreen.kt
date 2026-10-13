// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.linuxundich.hausmusik.R

/** One entry of the licence list. Kept in code: the list is short and must not depend on Play Services. */
data class Component(val name: String, val authors: String, val license: String, val url: String)

val components = listOf(
    Component("noson (libnoson)", "Jean-Luc Barrière", "GPL-3.0-or-later", "github.com/janbar/noson"),
    Component("SoCo (reference)", "SoCo contributors", "MIT", "github.com/SoCo/SoCo"),
    Component("AndroidX (Compose, Material 3, Media3, Room, DataStore, Glance, Lifecycle, Palette …)", "The Android Open Source Project", "Apache-2.0", "developer.android.com/jetpack"),
    Component("Material Icons", "Google", "Apache-2.0", "github.com/google/material-design-icons"),
    Component("Dagger / Hilt", "The Dagger Authors", "Apache-2.0", "dagger.dev"),
    Component("Kotlin, kotlinx.coroutines, kotlinx.serialization", "JetBrains s.r.o. and contributors", "Apache-2.0", "kotlinlang.org"),
    Component("OkHttp, Okio", "Square, Inc.", "Apache-2.0", "square.github.io/okhttp"),
    Component("Coil", "Coil Contributors", "Apache-2.0", "coil-kt.github.io/coil"),
    Component("Guava", "The Guava Authors", "Apache-2.0", "github.com/google/guava"),
    Component("JSpecify", "The JSpecify Authors", "Apache-2.0", "jspecify.dev"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.licenses)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        SelectionContainer(Modifier.padding(padding)) {
            LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                item {
                    Text(stringResource(R.string.licenses_intro), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
                }
                items(components) { c ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text(c.name, style = MaterialTheme.typography.titleSmall)
                        Text("${c.authors} · ${c.license}", style = MaterialTheme.typography.bodySmall)
                        Text(c.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    HorizontalDivider()
                }
                item {
                    Text(stringResource(R.string.licenses_apache), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 24.dp))
                }
            }
        }
    }
}
