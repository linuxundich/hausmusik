// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import de.linuxundich.hausmusik.R
import de.linuxundich.hausmusik.data.GroupState
import de.linuxundich.hausmusik.data.HouseholdRepository
import de.linuxundich.hausmusik.data.QueueMode
import de.linuxundich.hausmusik.sonos.DidlObject
import kotlinx.coroutines.launch

/**
 * Commands from outside the app: launcher shortcuts, Tasker, Home Assistant or
 * `adb shell am start`. Shows nothing; runs the command against the room named
 * in the extra `room` (or the selected one) and ends at once.
 *
 *     am start -a de.linuxundich.hausmusik.action.PLAY_FAVORITE --es favorite "Radio Tuxfunk" --es room Bad
 */
class CommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.action
        val room = intent.getStringExtra(EXTRA_ROOM)
        val favorite = intent.getStringExtra(EXTRA_FAVORITE)
        val app = applicationContext
        HouseholdAccess.entry(app).appScope().launch {
            runCatching { HouseholdAccess.withSelected(app) { household, selected -> run(household, target(household, selected, room), action, favorite) } }
                .onFailure { Log.w(TAG, "command $action failed", it) }
        }
        finish()
    }

    private fun target(household: HouseholdRepository, selected: GroupState, room: String?): GroupState =
        room?.let { name -> household.state.value.groups.firstOrNull { g -> g.group.members.any { it.roomName.equals(name, ignoreCase = true) } } }
            ?: selected

    private suspend fun run(household: HouseholdRepository, group: GroupState, action: String?, favorite: String?) {
        when (action) {
            ACTION_PLAY_PAUSE -> household.commandFor(group.id) { if (group.isPlaying) it.pause() else it.play() }
            ACTION_PLAY -> household.commandFor(group.id) { it.play() }
            ACTION_PAUSE -> household.commandFor(group.id) { it.pause() }
            ACTION_NEXT -> household.commandFor(group.id) { it.next() }
            ACTION_PAUSE_ALL -> household.state.value.groups.filter { it.isPlaying }.forEach { g -> household.commandFor(g.id) { it.pause() } }
            ACTION_VOLUME_UP -> household.requestGroupVolume(group.id, group.groupVolume + STEP)
            ACTION_VOLUME_DOWN -> household.requestGroupVolume(group.id, group.groupVolume - STEP)
            ACTION_EVERYWHERE -> { household.select(group.id); household.joinEverywhere() }
            ACTION_PLAY_FAVORITE -> {
                val fav = household.browseAll("FV:2").getOrNull()?.firstOrNull { it.title.equals(favorite?.trim(), ignoreCase = true) }
                    ?: run { Log.w(TAG, "no favorite called $favorite"); return }
                household.select(group.id)
                household.play(fav, QueueMode.ReplaceAndPlay)
            }
            else -> Log.w(TAG, "unknown command $action")
        }
    }

    companion object {
        private const val TAG = "Commands"
        private const val STEP = 3
        const val ACTION_PLAY_PAUSE = "de.linuxundich.hausmusik.action.PLAY_PAUSE"
        const val ACTION_PLAY = "de.linuxundich.hausmusik.action.PLAY"
        const val ACTION_PAUSE = "de.linuxundich.hausmusik.action.PAUSE"
        const val ACTION_NEXT = "de.linuxundich.hausmusik.action.NEXT"
        const val ACTION_PAUSE_ALL = "de.linuxundich.hausmusik.action.PAUSE_ALL"
        const val ACTION_VOLUME_UP = "de.linuxundich.hausmusik.action.VOLUME_UP"
        const val ACTION_VOLUME_DOWN = "de.linuxundich.hausmusik.action.VOLUME_DOWN"
        const val ACTION_EVERYWHERE = "de.linuxundich.hausmusik.action.EVERYWHERE"
        const val ACTION_PLAY_FAVORITE = "de.linuxundich.hausmusik.action.PLAY_FAVORITE"
        const val EXTRA_ROOM = "room"
        const val EXTRA_FAVORITE = "favorite"

        fun intent(context: Context, action: String, favorite: String? = null, room: String? = null): Intent =
            Intent(action).setClass(context, CommandActivity::class.java)
                .apply { favorite?.let { putExtra(EXTRA_FAVORITE, it) }; room?.let { putExtra(EXTRA_ROOM, it) } }

        /**
         * Long press on the app icon: pause everywhere, play/pause, and the first
         * favorites (radio first, they start at once). Refreshed when favorites load.
         */
        fun publishShortcuts(context: Context, favorites: List<DidlObject>) {
            val icon = IconCompat.createWithResource(context, R.drawable.ic_speaker)
            val fixed = listOf(
                ShortcutInfoCompat.Builder(context, "pause_all").setShortLabel(context.getString(R.string.shortcut_pause_all))
                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_pause)).setIntent(intent(context, ACTION_PAUSE_ALL)).build(),
                ShortcutInfoCompat.Builder(context, "play_pause").setShortLabel(context.getString(R.string.shortcut_play_pause))
                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_play)).setIntent(intent(context, ACTION_PLAY_PAUSE)).build(),
            )
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtMost(4)
            val favs = favorites.sortedByDescending { it.isRadio }.take((max - fixed.size).coerceAtLeast(0)).mapIndexed { i, f ->
                ShortcutInfoCompat.Builder(context, "fav_$i").setShortLabel(f.title.take(24)).setLongLabel(f.title)
                    .setIcon(icon).setIntent(intent(context, ACTION_PLAY_FAVORITE, favorite = f.title)).setRank(i + fixed.size).build()
            }
            runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, fixed + favs) }
                .onFailure { Log.w(TAG, "shortcuts not published", it) }
        }
    }
}
