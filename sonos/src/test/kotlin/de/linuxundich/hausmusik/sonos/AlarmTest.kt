package de.linuxundich.hausmusik.sonos

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AlarmTest {
    @get:Rule val server = MockWebServerRule()

    private val list = """<Alarms>
        <Alarm ID="12" StartTime="06:45:00" Duration="01:00:00" Recurrence="WEEKDAYS" Enabled="1" RoomUUID="RINCON_1" ProgramURI="x-rincon-buzzer:0" ProgramMetaData="" PlayMode="SHUFFLE_NOREPEAT" Volume="18" IncludeLinkedZones="0"/>
        <Alarm ID="3" StartTime="09:00:00" Duration="00:30:00" Recurrence="ON_06" Enabled="0" RoomUUID="RINCON_2" ProgramURI="x-rincon-mp3radio://radio.example/a" ProgramMetaData="" PlayMode="NORMAL" Volume="25" IncludeLinkedZones="1"/>
    </Alarms>"""

    @Test
    fun `parses and sorts alarms`() {
        val alarms = Alarm.parseList(list)
        assertEquals(listOf("12", "3"), alarms.map { it.id })
        val first = alarms[0]
        assertEquals(6 * 60 + 45, first.startMinutes)
        assertEquals(60, first.durationMinutes)
        assertEquals(Recurrence.WEEKDAYS, first.recurrence.days)
        assertTrue(first.usesChime)
        assertEquals(PlayMode(true, RepeatMode.Off), first.playMode)
        assertEquals(setOf(0, 6), alarms[1].recurrence.days)
        assertEquals("WEEKENDS", alarms[1].recurrence.sonosValue)
    }

    @Test
    fun `recurrence values`() {
        assertEquals("ONCE", Recurrence(emptySet()).sonosValue)
        assertEquals("DAILY", Recurrence((0..6).toSet()).sonosValue)
        assertEquals("ON_135", Recurrence(setOf(5, 1, 3)).sonosValue)
        assertEquals(setOf(1, 3, 5), Recurrence.parse("ON_135").days)
    }

    @Test
    fun `create sends arguments in Sonos order`() = runTest {
        server.server.enqueue(MockResponse.Builder().body("""<s:Envelope xmlns:s="x"><s:Body><u:CreateAlarmResponse><AssignedID>42</AssignedID></u:CreateAlarmResponse></s:Body></s:Envelope>""").build())
        val id = AlarmClient(server.server.url("/"), SoapClient(OkHttpClient())).create(Alarm.parseList(list)[0].copy(id = null))
        assertEquals("42", id)
        val body = server.server.takeRequest().body!!.utf8()
        val order = listOf("StartLocalTime", "Duration", "Recurrence", "Enabled", "RoomUUID", "ProgramURI", "ProgramMetaData", "PlayMode", "Volume", "IncludeLinkedZones")
        assertEquals(order, order.sortedBy { body.indexOf("<$it>") })
        assertTrue(body.contains("<StartLocalTime>06:45:00</StartLocalTime>"))
        assertTrue(body.contains("<Recurrence>WEEKDAYS</Recurrence>"))
    }
}
