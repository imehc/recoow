package imehc.recoow.widget

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import imehc.recoow.R
import imehc.recoow.core.designsystem.RecoowPage
import imehc.recoow.core.designsystem.RecoowTheme
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.preferences.Appearance
import imehc.recoow.feature.settings.SettingsViewModel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class QuickToolWidgetConfiguration : AppCompatActivity() {
    private val preferences: SettingsViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || info?.provider?.className != QuickToolWidget::class.java.name || info.provider.packageName != packageName) { finish(); return }
        enableEdgeToEdge()
        setContent {
            val state by preferences.uiState.collectAsStateWithLifecycle()
            val dark = when (state.preferences?.appearance) { Appearance.DARK -> true; Appearance.LIGHT -> false; else -> isSystemInDarkTheme() }
            val names = LocalResources.current.getStringArray(imehc.recoow.core.designsystem.R.array.record_tool_names)
            RecoowTheme(dark) {
                RecoowPage(stringResource(R.string.widget_choose), navigationIcon = {
                    TextButton(onClick = { finish() }) { Text(stringResource(R.string.widget_cancel)) }
                }) {
                    item { Text(stringResource(R.string.widget_hint), style = MaterialTheme.typography.bodyMedium) }
                    QuickToolWidget.tools.forEach { tool -> item {
                        Card { ListItem(modifier = Modifier.testTag("widget_tool_${tool.id}").semantics {
                            role = Role.RadioButton
                            selected = QuickToolWidget.selected(this@QuickToolWidgetConfiguration, id) == tool
                        }, onClick = {
                            check(QuickToolWidget.settings(this@QuickToolWidgetConfiguration).edit().putString(id.toString(), tool.id).commit())
                            lifecycleScope.launch {
                                QuickToolWidget.renderWithPreferences(this@QuickToolWidgetConfiguration, intArrayOf(id), state.preferences ?: imehc.recoow.core.domain.preferences.AppPreferences())
                                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)); finish()
                            }
                        }, trailingContent = { RadioButton(QuickToolWidget.selected(this@QuickToolWidgetConfiguration, id) == tool, onClick = null) }) { Text(names[ToolRoute.entries.indexOf(tool)]) } }
                    } }
                }
            }
        }
    }
}
