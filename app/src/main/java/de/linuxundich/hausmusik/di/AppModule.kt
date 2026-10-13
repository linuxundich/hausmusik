// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.linuxundich.hausmusik.data.history.HistoryDao
import de.linuxundich.hausmusik.data.net.LocalNetwork
import de.linuxundich.hausmusik.data.history.HistoryDatabase
import de.linuxundich.hausmusik.sonos.SoapClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * For the speakers (SOAP, events, covers): bound to the local network.
     * Speakers answer in milliseconds; long timeouts would only hide a lost connection.
     */
    @Provides @Singleton
    fun okHttp(local: LocalNetwork): OkHttpClient = OkHttpClient.Builder()
        .socketFactory(local.socketFactory)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun soap(http: OkHttpClient): SoapClient = SoapClient(http)

    @Provides @Singleton
    fun settings(@ApplicationContext context: Context): DataStore<Preferences> = context.settingsStore

    @Provides @Singleton
    fun historyDb(@ApplicationContext context: Context): HistoryDatabase = HistoryDatabase.create(context)

    @Provides
    fun historyDao(db: HistoryDatabase): HistoryDao = db.history()

    @Provides @Singleton @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
