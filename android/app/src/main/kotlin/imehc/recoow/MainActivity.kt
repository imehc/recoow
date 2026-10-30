package imehc.recoow

import android.os.Bundle
import android.os.Build
import android.content.Intent
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import imehc.recoow.core.designsystem.RecoowTheme
import imehc.recoow.core.domain.preferences.Appearance
import imehc.recoow.feature.settings.SettingsViewModel
import imehc.recoow.feature.settings.SyncViewModel
import imehc.recoow.feature.settings.SyncScreen
import imehc.recoow.navigation.ToolLinkViewModel

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val preferences: SettingsViewModel by viewModels()
    private val toolLinks: ToolLinkViewModel by viewModels()
    private val bills: imehc.recoow.feature.bills.BillsViewModel by viewModels()
    private val media: imehc.recoow.feature.media.MediaLibraryViewModel by viewModels()
    private val sync: SyncViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleToolLink(intent)
        enableEdgeToEdge()
        setContent {
            val state by preferences.uiState.collectAsStateWithLifecycle()
            val syncState by sync.state.collectAsStateWithLifecycle()
            val pendingRoute by toolLinks.pendingRoute.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = when (state.preferences?.appearance) {
                Appearance.LIGHT -> false
                Appearance.DARK -> true
                else -> systemDark
            }
            LaunchedEffect(state.preferences?.language) {
                if (Build.VERSION.SDK_INT >= 33) return@LaunchedEffect
                state.preferences?.let {
                    val locales = LocaleListCompat.forLanguageTags(it.language.tag)
                    if (AppCompatDelegate.getApplicationLocales() != locales) AppCompatDelegate.setApplicationLocales(locales)
                }
            }
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                )
            }
            RecoowTheme(darkTheme = dark) {
                RecoowApp(state, preferences::setAppearance, preferences::setLanguage, preferences::retry,
                    pendingRoute, toolLinks::consumed,
                    billsScreen = { onBack -> imehc.recoow.feature.bills.BillsScreen(bills, onBack) },
                    mediaScreen = { onBack -> imehc.recoow.feature.media.MediaLibraryScreen(media, onBack) }) { onBack ->
                    SyncScreen(syncState, onBack, sync::connect, sync::synchronize, sync::disconnect,
                        sync::acknowledgeRecoveryKey, sync::resolve, sync::generatePairingCode, sync::dismissPairingCode)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A link is a one-shot event. SavedStateHandle owns pending delivery across recreation.
        handleToolLink(intent)
    }

    private fun handleToolLink(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        intent.data?.let { toolLinks.receive(it.scheme, it.host, it.pathSegments) }
    }
}
