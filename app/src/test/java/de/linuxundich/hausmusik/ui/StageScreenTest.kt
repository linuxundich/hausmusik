// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.linuxundich.hausmusik.TestData
import de.linuxundich.hausmusik.data.GroupState
import de.linuxundich.hausmusik.ui.stage.StageActions
import de.linuxundich.hausmusik.ui.stage.StageScreen
import de.linuxundich.hausmusik.ui.theme.CoverColors
import de.linuxundich.hausmusik.ui.theme.HausmusikTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h914dp")
class StageScreenTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun actions(timer: String? = null) = StageActions(
        onRooms = { calls += "rooms" }, onLibrary = { calls += "library" }, onQueue = { calls += "queue" },
        onTimer = { calls += "timer" }, onPlayPause = { calls += "playPause" }, onNext = { calls += "next" },
        onPrevious = { calls += "previous" }, onSeek = { calls += "seek" }, onVolume = { calls += "volume" },
        onShuffle = { calls += "shuffle" }, onRepeat = { calls += "repeat" }, onAlarms = { calls += "alarms" },
        onSound = { calls += "sound" }, onSettings = { calls += "settings" }, onLyrics = { calls += "lyrics" }, timerLabel = timer,
    )

    private fun show(group: GroupState, timer: String? = null) = compose.setContent {
        HausmusikTheme { StageScreen(group, CoverColors.Default, actions(timer)) }
    }

    @Test
    fun `shows the track and the room`() {
        show(TestData.queueGroup())
        compose.onNodeWithText("Segfault Serenade").assertIsDisplayed()
        compose.onNodeWithText("Penny Guin · Kernel Panic Ballads").assertIsDisplayed()
        compose.onNodeWithText("Wohnzimmer + Küche", substring = true).assertIsDisplayed()
        compose.onNodeWithText("2:53").assertIsDisplayed() // duration
        compose.onNodeWithText("1:00").assertIsDisplayed() // position, no read time yet
    }

    @Test
    fun `play button pauses while playing`() {
        show(TestData.queueGroup(playing = true))
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithContentDescription("Next").assertIsEnabled().performClick()
        assertEquals(listOf("playPause", "next"), calls)
    }

    @Test
    fun `radio cannot skip`() {
        show(TestData.radioGroup())
        compose.onNodeWithText("Radio Tuxfunk · Penny Guin").assertIsDisplayed()
        // No skip buttons at all for radio (they gave way instead of standing there greyed out).
        compose.onNodeWithContentDescription("Next").assertDoesNotExist()
        compose.onNodeWithContentDescription("Previous").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
    }

    @Test
    fun `timer button shows the time left`() {
        show(TestData.queueGroup(), timer = "23 min")
        compose.onNodeWithText("23 min").assertIsDisplayed().performClick()
        assertEquals(listOf("timer"), calls)
    }

    @Test
    fun `room chip opens the rooms`() {
        show(TestData.queueGroup())
        compose.onNodeWithText("Wohnzimmer + Küche", substring = true).performClick()
        assertEquals(listOf("rooms"), calls)
    }
}
