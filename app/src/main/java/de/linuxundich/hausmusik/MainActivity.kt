// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later

package de.linuxundich.hausmusik

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import de.linuxundich.hausmusik.data.HouseholdRepository
import de.linuxundich.hausmusik.data.Settings
import de.linuxundich.hausmusik.playback.PlaybackService
import de.linuxundich.hausmusik.ui.HausmusikRoot
import de.linuxundich.hausmusik.ui.setup.PermissionScreen
import de.linuxundich.hausmusik.ui.theme.HausmusikTheme
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var household: HouseholdRepository
    @Inject lateinit var settings: Settings

    /** Android 17 guards the home network behind a runtime permission. */
    private val needsLocalNetworkPermission get() = Build.VERSION.SDK_INT >= 37

    private fun hasLocalNetwork() = !needsLocalNetworkPermission ||
        ContextCompat.checkSelfPermission(this, LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Debug builds accept a speaker address from adb, e.g. for the fake household:
        // adb shell am start -n de.linuxundich.hausmusik.debug/de.linuxundich.hausmusik.MainActivity -e host 10.0.2.2:1401
        applyDebugHost(intent)
        setContent {
            HausmusikTheme {
                var granted by remember { mutableStateOf(hasLocalNetwork()) }
                var denied by remember { mutableStateOf(false) }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
                    granted = ok
                    denied = !ok
                    if (ok) startHousehold()
                }
                if (granted) HausmusikRoot() else PermissionScreen(denied) { launcher.launch(LOCAL_NETWORK) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyDebugHost(intent)
    }

    private fun applyDebugHost(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val callback = intent.getStringExtra("callback")
        val host = intent.getStringExtra("host")
        if (callback == null && host == null) return
        lifecycleScope.launch {
            callback?.let { settings.setDebugCallback(it.ifBlank { null }) }
            household.connectTo(host ?: settings.manualHost())
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasLocalNetwork()) startHousehold()
    }

    override fun onStop() {
        super.onStop()
        household.release(OWNER)
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
    }

    private var controller: ListenableFuture<MediaController>? = null

    /**
     * Polls/subscribes while visible and binds the playback service, so the
     * media notification and the lock screen controls exist as soon as music plays.
     */
    @OptIn(UnstableApi::class)
    private fun startHousehold() {
        household.acquire(OWNER)
        if (controller == null) {
            controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        }
    }

    /** In the foreground the volume buttons always steer the selected room. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (household.state.value.selected != null) when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { household.adjustSelectedVolume(+VOLUME_STEP); return true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { household.adjustSelectedVolume(-VOLUME_STEP); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val OWNER = "activity"
        const val VOLUME_STEP = 2
        private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
