// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.linuxundich.hausmusik.ui.common.MessageHost
import de.linuxundich.hausmusik.ui.theme.HausmusikTheme
import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MessagePillTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `the pill shows the message, its action answers, and it ends by itself`() {
        val state = SnackbarHostState()
        lateinit var scope: CoroutineScope
        compose.setContent {
            scope = rememberCoroutineScope()
            HausmusikTheme { MessageHost(state) }
        }
        var result: SnackbarResult? = null
        compose.runOnIdle {
            scope.launch { result = state.showSnackbar("Bad spielt jetzt allein.", actionLabel = "Rückgängig", duration = SnackbarDuration.Long) }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Bad spielt jetzt allein.").assertIsDisplayed()
        compose.onNodeWithText("Rückgängig").performClick()
        compose.waitUntil(2_000) { result != null }
        assertEquals(SnackbarResult.ActionPerformed, result)

        // Without a tap it goes away after its duration, so the next message can follow.
        var second: SnackbarResult? = null
        compose.runOnIdle { scope.launch { second = state.showSnackbar("Gespeichert") } }
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitUntil(2_000) { second != null }
        assertEquals(SnackbarResult.Dismissed, second)
    }
}
