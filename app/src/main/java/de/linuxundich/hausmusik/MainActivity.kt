package de.linuxundich.hausmusik

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import de.linuxundich.hausmusik.data.HouseholdRepository
import de.linuxundich.hausmusik.ui.HausmusikRoot
import de.linuxundich.hausmusik.ui.setup.PermissionScreen
import de.linuxundich.hausmusik.ui.theme.HausmusikTheme
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var household: HouseholdRepository

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
                    if (ok) household.start()
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
        intent.getStringExtra("host")?.let { host -> lifecycleScope.launch { household.connectTo(host) } }
    }

    override fun onStart() {
        super.onStart()
        if (hasLocalNetwork()) household.start()
    }

    override fun onStop() {
        super.onStop()
        household.stop()
    }

    companion object {
        private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
