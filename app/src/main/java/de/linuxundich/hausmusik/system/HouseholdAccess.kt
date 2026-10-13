package de.linuxundich.hausmusik.system

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
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
        val group = withTimeoutOrNull(8_000) { household.state.first { it.selected?.nowPlaying != null || it.selected?.positionReadAt != 0L && it.selected != null }.selected }
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
