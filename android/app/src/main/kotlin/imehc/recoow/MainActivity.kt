package imehc.recoow

import android.os.Bundle
import android.os.Build
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

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val preferences: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by preferences.uiState.collectAsStateWithLifecycle()
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
                RecoowApp(state, preferences::setAppearance, preferences::setLanguage, preferences::retry)
            }
        }
    }
}
