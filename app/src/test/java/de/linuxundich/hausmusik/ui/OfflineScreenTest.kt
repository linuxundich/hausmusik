// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import de.linuxundich.hausmusik.ui.setup.OfflineScreen
import de.linuxundich.hausmusik.ui.setup.ReconnectingBanner
import de.linuxundich.hausmusik.ui.theme.HausmusikTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de-w411dp-h914dp")
class OfflineScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `mobile data mask names the problem, the way out and the last room`() {
        compose.setContent { HausmusikTheme { OfflineScreen(lastRoom = "Wohnzimmer + Küche", hasManualHost = false) } }
        compose.onNodeWithText("Nicht im Heimnetz").assertIsDisplayed()
        compose.onNodeWithText("WLAN-Einstellungen").assertIsDisplayed()
        compose.onNodeWithText("Zuletzt: Wohnzimmer + Küche", substring = true).assertIsDisplayed()
    }

    @Test
    fun `fixed address that failed is mentioned`() {
        compose.setContent { HausmusikTheme { OfflineScreen(lastRoom = null, hasManualHost = true) } }
        compose.onNodeWithText("Auch die feste Lautsprecher-Adresse hat nicht geantwortet.").assertIsDisplayed()
        compose.onNodeWithText("Hausmusik verbindet sich von selbst, sobald du zurück bist.").assertIsDisplayed()
    }

    @Test
    fun `reconnecting band`() {
        compose.setContent { HausmusikTheme { ReconnectingBanner() } }
        compose.onNodeWithText("Lautsprecher kurz weg, verbinde neu …").assertIsDisplayed()
    }
}
