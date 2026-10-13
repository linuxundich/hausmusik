// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import de.linuxundich.hausmusik.TestData
import de.linuxundich.hausmusik.data.Connection
import de.linuxundich.hausmusik.ui.setup.ConnectScreen
import de.linuxundich.hausmusik.ui.stage.StageActions
import de.linuxundich.hausmusik.ui.stage.StageScreen
import de.linuxundich.hausmusik.ui.theme.CoverColors
import de.linuxundich.hausmusik.ui.theme.HausmusikTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pixel 10 held sideways: everything important has to be on screen without scrolling. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w914dp-h411dp-land")
class LandscapeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `stage keeps all controls and shows the timer text`() {
        val noop = {}
        compose.setContent {
            HausmusikTheme {
                StageScreen(
                    TestData.queueGroup(), CoverColors.Default,
                    StageActions(noop, noop, noop, noop, noop, noop, noop, {}, {}, noop, noop, noop, noop, noop, noop, timerLabel = "23 min"),
                )
            }
        }
        compose.onNodeWithText("Segfault Serenade").assertIsDisplayed()
        compose.onNodeWithContentDescription("Queue").assertIsDisplayed()
        compose.onNodeWithContentDescription("Library").assertIsDisplayed()
        compose.onNodeWithText("23 min").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.onNodeWithContentDescription("Volume").assertIsDisplayed()
    }

    @Test
    fun `not-found screen shows explanation and buttons side by side`() {
        compose.setContent { HausmusikTheme { ConnectScreen(Connection.NotFound("10.0.0.5"), onConnect = {}, onSearchAgain = {}) } }
        compose.onNodeWithText("No speakers found").assertIsDisplayed()
        compose.onNodeWithText("Connect").assertIsDisplayed()
        compose.onNodeWithText("Search again").assertIsDisplayed()
    }
}
