// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.system

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.linuxundich.hausmusik.data.Connection
import de.linuxundich.hausmusik.data.GroupState
import de.linuxundich.hausmusik.data.HouseholdRepository
import de.linuxundich.hausmusik.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@EntryPoint
@InstallIn(SingletonComponent::class)
interface HouseholdEntryPoint {
    fun household(): HouseholdRepository

    @AppScope
    fun appScope(): CoroutineScope
}

/**
 * Lets the tile and the widget act while the app is closed: keeps the
 * household connected for a short while, waits for the selected room and runs
 * the block against it.
 */
object HouseholdAccess {
    private const val OWNER = "external"
    private var releaseJob: Job? = null

    fun entry(context: Context): HouseholdEntryPoint =
        EntryPointAccessors.fromApplication(context.applicationContext, HouseholdEntryPoint::class.java)

    suspend fun <T> withSelected(context: Context, block: suspend (HouseholdRepository, GroupState) -> T): T? {
        val e = entry(context)
        val household = e.household()
        synchronized(this) {
            releaseJob?.cancel()
            household.acquire(OWNER)
        }
        // Returns at once without a home network instead of waiting for a timeout.
        val reached = withTimeoutOrNull(8_000) {
            household.state.first {
                it.connection == Connection.NoLocalNetwork || it.connection is Connection.NotFound ||
                    (it.connection == Connection.Connected && it.selected != null && it.selected!!.positionReadAt != 0L)
            }
        }
        val group = reached?.takeIf { it.connection == Connection.Connected }?.selected
        val result = group?.let { block(household, it) }
        synchronized(this) {
            // Stay connected a little longer: a second tap usually follows.
            releaseJob = e.appScope().launch {
                delay(20_000)
                household.release(OWNER)
            }
        }
        return result
    }
}
