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
    private val reminders: imehc.recoow.feature.reminders.RemindersViewModel by viewModels()
    private val anniversaries: imehc.recoow.feature.anniversaries.AnniversariesViewModel by viewModels()
    private val decisions: imehc.recoow.feature.decision.DecisionViewModel by viewModels()
    private val diary: imehc.recoow.feature.diary.DiaryViewModel by viewModels()
    private val food: imehc.recoow.feature.food.FoodViewModel by viewModels()
    private val items: imehc.recoow.feature.items.ItemsViewModel by viewModels()
    private val media: imehc.recoow.feature.media.MediaLibraryViewModel by viewModels()
    private val sync: SyncViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleToolLink(intent)
        enableEdgeToEdge()
        setContent {
            val state by preferences.uiState.collectAsStateWithLifecycle()
            val billState by bills.state.collectAsStateWithLifecycle()
            val reminderState by reminders.state.collectAsStateWithLifecycle()
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
                    bills = billState.bills, billsLoading = billState.loading, billsFailed = billState.readFailed, onBillsRetry = bills::retry, onBill = bills::show,
                    onToolVisible = preferences::setToolVisible, reminders = reminderState.records,
                    remindersScreen = { recordId, onBack -> imehc.recoow.feature.reminders.RemindersScreen(reminders, onBack, recordId) },
                    billsScreen = { onBack -> imehc.recoow.feature.bills.BillsScreen(bills, onBack) },
                    journalScreen = { route, id, onBack, onSource -> when (route) {
                        imehc.recoow.core.domain.navigation.ToolRoute.ANNIVERSARIES -> imehc.recoow.feature.anniversaries.AnniversariesScreen(anniversaries, onBack, id)
                        imehc.recoow.core.domain.navigation.ToolRoute.DECISION -> imehc.recoow.feature.decision.DecisionScreen(decisions, onBack, id)
                        imehc.recoow.core.domain.navigation.ToolRoute.DIARY -> imehc.recoow.feature.diary.DiaryScreen(diary, onBack, id, onSource)
                        imehc.recoow.core.domain.navigation.ToolRoute.FOOD -> imehc.recoow.feature.food.FoodScreen(food, onBack, id) { onSource(imehc.recoow.core.domain.navigation.ToolRoute.BILLS, it) }
                        imehc.recoow.core.domain.navigation.ToolRoute.ITEMS -> imehc.recoow.feature.items.ItemsScreen(items, onBack, id)
                        else -> Unit
                    } },
                    mediaScreen = { onBack -> imehc.recoow.feature.media.MediaLibraryScreen(media, onBack) }) { onBack ->
                    SyncScreen(syncState, onBack, sync::connect, sync::synchronize, sync::disconnect,
                        sync::acknowledgeRecoveryKey, sync::resolve, sync::generatePairingCode, sync::dismissPairingCode, sync::setEnabled, sync::refreshDevices, sync::revokeDevice)
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
