package de.linuxundich.hausmusik.sonos

/** Recorded (anonymised) responses of a first-generation Sonos household. */
object Fixtures {
    fun load(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

    fun result(action: String, name: String): Map<String, String> = SoapClient.parseResponse(action, load(name))
}
