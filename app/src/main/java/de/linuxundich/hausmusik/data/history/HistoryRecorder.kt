package de.linuxundich.hausmusik.data.history

import de.linuxundich.hausmusik.data.HouseholdRepository
import de.linuxundich.hausmusik.di.AppScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Sonos keeps no play history, so Hausmusik writes its own (as Gnomos does):
 * every track that plays while the app is connected, once per room and track.
 * It stays on the phone.
 */
@Singleton
class HistoryRecorder @Inject constructor(
    private val household: HouseholdRepository,
    private val dao: HistoryDao,
    @AppScope private val scope: CoroutineScope,
) {
    private val lastSeen = mutableMapOf<String, String>()

    fun start() {
        scope.launch {
            household.state.collect { h ->
                for (g in h.groups) {
                    val now = g.nowPlaying ?: continue
                    if (!g.isPlaying) continue
                    val key = listOf(now.title, now.artist, now.station).joinToString("|")
                    if (lastSeen[g.id] == key) continue
                    lastSeen[g.id] = key
                    dao.insert(
                        HistoryEntry(
                            playedAt = System.currentTimeMillis(),
                            room = g.group.displayName,
                            title = now.title,
                            artist = now.artist,
                            album = now.album,
                            station = now.station,
                            artUrl = g.artUrl,
                        ),
                    )
                    dao.trim()
                }
            }
        }
    }
}
