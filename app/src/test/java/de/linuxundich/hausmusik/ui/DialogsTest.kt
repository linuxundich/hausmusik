package de.linuxundich.hausmusik.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.linuxundich.hausmusik.data.QueueMode
import de.linuxundich.hausmusik.sonos.Recurrence
import de.linuxundich.hausmusik.ui.alarms.recurrenceLabel
import de.linuxundich.hausmusik.ui.common.PlayDialog
import de.linuxundich.hausmusik.ui.theme.HausmusikTheme
import androidx.compose.material3.Text
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DialogsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "en")
    fun `play dialog names the queue it would replace and offers three ways`() {
        var chosen: QueueMode? = null
        compose.setContent {
            HausmusikTheme { PlayDialog("Pinguinzoo", "Wohnzimmer", 5, onChoose = { chosen = it }, onDismiss = {}) }
        }
        compose.onNodeWithText("The queue in Wohnzimmer has 5 tracks. Playing now replaces them.").assertIsDisplayed()
        compose.onNodeWithText("Add to queue").assertIsDisplayed()
        compose.onNodeWithText("Play now").assertIsDisplayed()
        compose.onNodeWithText("Play next").performClick()
        assertEquals(QueueMode.PlayNext, chosen)
    }

    @Test
    @Config(qualifiers = "de")
    fun `play dialog in German with one track`() {
        compose.setContent { HausmusikTheme { PlayDialog("Stable", "Bad", 1, onChoose = {}, onDismiss = {}) } }
        compose.onNodeWithText("In der Warteschlange von Bad liegt 1 Titel. „Jetzt abspielen“ ersetzt ihn.").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "de")
    fun `alarm days in German, Monday first`() {
        compose.setContent {
            HausmusikTheme {
                androidx.compose.foundation.layout.Column {
                    Text(recurrenceLabel(Recurrence(Recurrence.WEEKDAYS)))
                    Text(recurrenceLabel(Recurrence(setOf(0, 6))))
                    Text(recurrenceLabel(Recurrence(setOf(0, 1, 3))))
                    Text(recurrenceLabel(Recurrence(emptySet())))
                    Text(recurrenceLabel(Recurrence((0..6).toSet())))
                }
            }
        }
        compose.onNodeWithText("Mo–Fr").assertIsDisplayed()
        compose.onNodeWithText("Sa, So").assertIsDisplayed()
        compose.onNodeWithText("Mo, Mi, So").assertIsDisplayed()
        compose.onNodeWithText("Einmalig").assertIsDisplayed()
        compose.onNodeWithText("Täglich").assertIsDisplayed()
    }
}
