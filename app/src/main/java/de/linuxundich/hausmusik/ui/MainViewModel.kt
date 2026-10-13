package de.linuxundich.hausmusik.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.data.HouseholdRepository
import de.linuxundich.hausmusik.data.QueueMode
import de.linuxundich.hausmusik.sonos.DidlObject
import de.linuxundich.hausmusik.sonos.PlayMode
import de.linuxundich.hausmusik.sonos.RepeatMode
import de.linuxundich.hausmusik.sonos.SonosPlayer
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repo: HouseholdRepository,
) : ViewModel() {

    val household = repo.state

    private val _messages = Channel<Int>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private fun run(@StringRes failure: Int = R.string.error_command, block: suspend () -> Result<Unit>) {
        viewModelScope.launch { block().onFailure { _messages.send(failure) } }
    }

    fun playPause() = run {
        val playing = household.value.selected?.isPlaying == true
        repo.command { if (playing) it.pause() else it.play() }
    }

    fun playPause(groupId: String) = run {
        val playing = household.value.groups.firstOrNull { it.id == groupId }?.isPlaying == true
        repo.commandFor(groupId) { if (playing) it.pause() else it.play() }
    }

    fun next() = run { repo.command { it.next() } }

    /** Like Gnomos: a few seconds into a track, "previous" restarts it. */
    fun previous() = run {
        val now = household.value.selected?.nowPlaying
        val restart = (now?.positionMs ?: 0) > 4000 || household.value.selected?.actions?.canPrevious == false
        repo.command { if (restart) it.seekTo(0) else it.previous() }
    }

    fun seek(ms: Long) = run { repo.command { it.seekTo(ms) } }

    fun setGroupVolume(groupId: String, v: Int) = repo.requestGroupVolume(groupId, v)

    fun setMemberVolume(groupId: String, player: SonosPlayer, v: Int) = repo.requestMemberVolume(groupId, player, v)

    fun select(groupId: String) = repo.select(groupId)

    fun setMembership(player: SonosPlayer, member: Boolean) {
        val groupId = household.value.selected?.id ?: return
        run { repo.setMembership(player, groupId, member) }
    }

    fun pauseAll() = run {
        runCatching {
            household.value.groups.filter { it.isPlaying }.forEach { g -> repo.commandFor(g.id) { it.pause() }.getOrThrow() }
        }
    }

    fun toggleShuffle() = run {
        val m = household.value.selected?.playMode ?: PlayMode.Normal
        repo.command { it.setPlayMode(m.copy(shuffle = !m.shuffle)) }
    }

    fun cycleRepeat() = run {
        val m = household.value.selected?.playMode ?: PlayMode.Normal
        val next = when (m.repeat) {
            RepeatMode.Off -> RepeatMode.All
            RepeatMode.All -> RepeatMode.One
            RepeatMode.One -> RepeatMode.Off
        }
        repo.command { it.setPlayMode(m.copy(repeat = next)) }
    }

    fun setSleepTimer(minutes: Int?) = run { repo.command { it.configureSleepTimer(minutes?.let { m -> m * 60_000L }) } }

    suspend fun sound(player: SonosPlayer) = repo.sound(player)
    fun setBass(player: SonosPlayer, v: Int) = run { repo.setBass(player, v) }
    fun setTreble(player: SonosPlayer, v: Int) = run { repo.setTreble(player, v) }
    fun setLoudness(player: SonosPlayer, on: Boolean) = run { repo.setLoudness(player, on) }

    fun play(item: DidlObject, mode: QueueMode) = viewModelScope.launch {
        repo.play(item, mode)
            .onSuccess {
                when (mode) {
                    QueueMode.PlayNext -> _messages.send(R.string.added_next)
                    QueueMode.Append -> _messages.send(R.string.added_to_queue)
                    QueueMode.ReplaceAndPlay -> Unit
                }
            }
            .onFailure { _messages.send(R.string.error_command) }
    }

    fun playQueueTrack(trackNumber: Int) = run { repo.playQueueTrack(trackNumber) }

    suspend fun loadQueue(): Result<List<DidlObject>> = repo.browseAll("Q:0", limit = 1000)

    suspend fun removeFromQueue(nr: Int) = repo.removeFromQueue(nr).also { r -> r.onFailure { _messages.send(R.string.error_command) } }
    suspend fun moveToNext(nr: Int) = repo.moveToNext(nr).also { r -> r.onFailure { _messages.send(R.string.error_command) } }
    suspend fun clearQueue() = repo.clearQueue().also { r -> r.onFailure { _messages.send(R.string.error_command) } }
    suspend fun saveQueue(title: String) = repo.saveQueue(title).also { r ->
        r.onSuccess { _messages.send(R.string.queue_saved) }.onFailure { _messages.send(R.string.error_command) }
    }

    fun connect(host: String) = viewModelScope.launch {
        repo.connectTo(host)
    }

    fun searchAgain() = repo.refreshNow()
}
