package de.linuxundich.hausmusik.sonos

import okhttp3.HttpUrl

/**
 * A Sonos alarm. Sonos stores alarms in the household, not on the phone; any
 * player answers for all of them.
 */
data class Alarm(
    /** Null for an alarm that does not exist yet. */
    val id: String?,
    /** Minutes after midnight, local time of the speakers. */
    val startMinutes: Int,
    val durationMinutes: Int,
    val recurrence: Recurrence,
    val enabled: Boolean,
    val roomUuid: String,
    /** `x-rincon-buzzer:0` is the Sonos chime. */
    val programUri: String = BUZZER,
    val programMetadata: String = "",
    val playMode: PlayMode = PlayMode.Normal,
    val volume: Int = 20,
    /** Also play in the rooms grouped with [roomUuid]. */
    val includeLinkedZones: Boolean = false,
) {
    val usesChime: Boolean get() = programUri.startsWith("x-rincon-buzzer:")

    companion object {
        const val BUZZER = "x-rincon-buzzer:0"

        internal fun hms(minutes: Int) = "%02d:%02d:00".format(minutes / 60, minutes % 60)
        internal fun minutes(hms: String?): Int = hms?.split(':')?.let { (it.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (it.getOrNull(1)?.toIntOrNull() ?: 0) } ?: 0

        /** Parses the CurrentAlarmList value of ListAlarms. */
        fun parseList(xml: String?): List<Alarm> {
            if (xml.isNullOrBlank()) return emptyList()
            return Xml.parse(xml).findAll("Alarm").map { a ->
                Alarm(
                    id = a.attr("ID"),
                    startMinutes = minutes(a.attr("StartTime") ?: a.attr("StartLocalTime")),
                    durationMinutes = minutes(a.attr("Duration")),
                    recurrence = Recurrence.parse(a.attr("Recurrence")),
                    enabled = a.attr("Enabled") == "1",
                    roomUuid = a.attr("RoomUUID").orEmpty(),
                    programUri = a.attr("ProgramURI") ?: BUZZER,
                    programMetadata = a.attr("ProgramMetaData").orEmpty(),
                    playMode = PlayMode.parse(a.attr("PlayMode")),
                    volume = a.attr("Volume")?.toIntOrNull() ?: 20,
                    includeLinkedZones = a.attr("IncludeLinkedZones") == "1",
                )
            }.sortedBy { it.startMinutes }
        }
    }
}

/**
 * Days an alarm rings on. Sonos numbers days from Sunday = 0; the app shows
 * them from Monday, as people in Germany expect.
 */
data class Recurrence(val days: Set<Int>) {
    val sonosValue: String
        get() = when {
            days.isEmpty() -> "ONCE"
            days.size == 7 -> "DAILY"
            days == WEEKDAYS -> "WEEKDAYS"
            days == WEEKEND -> "WEEKENDS"
            else -> "ON_" + days.sorted().joinToString("")
        }

    companion object {
        val WEEKDAYS = setOf(1, 2, 3, 4, 5)
        val WEEKEND = setOf(0, 6)

        fun parse(value: String?): Recurrence = when {
            value == null || value == "ONCE" -> Recurrence(emptySet())
            value == "DAILY" -> Recurrence((0..6).toSet())
            value == "WEEKDAYS" -> Recurrence(WEEKDAYS)
            value == "WEEKENDS" -> Recurrence(WEEKEND)
            value.startsWith("ON_") -> Recurrence(value.removePrefix("ON_").mapNotNull { it.digitToIntOrNull() }.filter { it in 0..6 }.toSet())
            else -> Recurrence(emptySet())
        }
    }
}

/** AlarmClock service calls; they can go to any player of the household. */
class AlarmClient(private val baseUrl: HttpUrl, private val soap: SoapClient) {

    suspend fun list(): List<Alarm> =
        Alarm.parseList(soap.call(baseUrl, SonosService.AlarmClock, "ListAlarms")["CurrentAlarmList"])

    /** Creates the alarm and returns its new ID. */
    suspend fun create(a: Alarm): String =
        soap.call(baseUrl, SonosService.AlarmClock, "CreateAlarm", args(a))["AssignedID"].orEmpty()

    suspend fun update(a: Alarm) {
        soap.call(baseUrl, SonosService.AlarmClock, "UpdateAlarm", listOf("ID" to requireNotNull(a.id)) + args(a))
    }

    suspend fun delete(id: String) {
        soap.call(baseUrl, SonosService.AlarmClock, "DestroyAlarm", listOf("ID" to id))
    }

    private fun args(a: Alarm): List<Pair<String, Any>> = listOf(
        "StartLocalTime" to Alarm.hms(a.startMinutes),
        "Duration" to Alarm.hms(a.durationMinutes),
        "Recurrence" to a.recurrence.sonosValue,
        "Enabled" to if (a.enabled) 1 else 0,
        "RoomUUID" to a.roomUuid,
        "ProgramURI" to a.programUri,
        "ProgramMetaData" to a.programMetadata,
        "PlayMode" to a.playMode.sonosValue,
        "Volume" to a.volume,
        "IncludeLinkedZones" to if (a.includeLinkedZones) 1 else 0,
    )
}
