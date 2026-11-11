package imehc.recoow.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as DesignR
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.preferences.*

@Composable internal fun SettingsBack(onBack: () -> Unit, enabled: Boolean = true, tag: String = "settings_back") {
    IconButton(onClick = onBack, enabled = enabled, modifier = Modifier.testTag(tag)) { Icon(painterResource(DesignR.drawable.ic_record_back), stringResource(DesignR.string.record_back)) }
}

@Composable fun DisplaySettingsScreen(state: SettingsUiState, onBack: () -> Unit,
    onAppearance: (Appearance) -> Unit, onLanguage: (AppLanguage) -> Unit, onRetry: () -> Unit) {
    RecoowPage(stringResource(R.string.settings_display_language), navigationIcon = { SettingsBack(onBack) },
        contentSpacing = RecoowLayout.compactSectionGap) {
        state.preferences?.let { preferences -> item("display") { RecordForm {
            RecordChoice(stringResource(R.string.appearance_title), preferences.appearance,
                listOf(Appearance.SYSTEM to stringResource(R.string.follow_system), Appearance.LIGHT to stringResource(R.string.appearance_light),
                    Appearance.DARK to stringResource(R.string.appearance_dark)), onAppearance, enabled = !state.saving, bordered = false,
                modifier = Modifier.testTag("appearance"))
            RecordFormDivider()
            RecordChoice(stringResource(R.string.language_title), preferences.language,
                listOf(AppLanguage.SYSTEM to stringResource(R.string.follow_system), AppLanguage.CHINESE to stringResource(R.string.language_chinese),
                    AppLanguage.ENGLISH to stringResource(R.string.language_english)), onLanguage, enabled = !state.saving, bordered = false,
                modifier = Modifier.testTag("language"))
        } } }
        settingsFeedback(state, onRetry)
    }
}

@Composable fun HomeToolsSettingsScreen(state: SettingsUiState, onBack: () -> Unit,
    onToolVisible: (String, Boolean) -> Unit, onRetry: () -> Unit) {
    val names = LocalResources.current.getStringArray(R.array.home_tool_names)
    RecoowPage(stringResource(R.string.home_tools), navigationIcon = { SettingsBack(onBack) },
        contentSpacing = RecoowLayout.compactSectionGap) {
        item("hint") { SettingsFooter(stringResource(R.string.settings_tools_detail)) }
        state.preferences?.let { preferences -> item("tools") { RecoowGroup {
            ToolRoute.entries.forEachIndexed { index, tool ->
                RecoowSwitchRow(names[index], tool.id !in preferences.hiddenHomeTools,
                    { onToolVisible(tool.id, it) }, enabled = !state.saving, modifier = Modifier.testTag("setting_tool_${tool.id}"),
                    icon = { Icon(painterResource(RecoowToolIcons.drawable(tool)), null, tint = RecoowToolIcons.tint(tool), modifier = Modifier.size(RecoowLayout.iconSize)) })
                if (index < ToolRoute.entries.lastIndex) RecoowDivider(RecoowLayout.dividerInset)
            }
        } } }
        settingsFeedback(state, onRetry)
    }
}

@Composable fun PhotoSettingsScreen(state: SettingsUiState, onBack: () -> Unit,
    onPickedPhotos: (Boolean) -> Unit, onCameraPhotos: (Boolean) -> Unit, onRetry: () -> Unit) {
    RecoowPage(stringResource(R.string.photos_section), navigationIcon = { SettingsBack(onBack) },
        contentSpacing = RecoowLayout.compactSectionGap) {
        state.preferences?.let { preferences -> item("photos") { RecoowGroup {
            RecoowSwitchRow(stringResource(R.string.photos_picked), preferences.addsPickedPhotosToMediaLibrary,
                onPickedPhotos, enabled = !state.saving, modifier = Modifier.testTag("photos_picked"))
            RecoowDivider()
            RecoowSwitchRow(stringResource(R.string.photos_camera), preferences.savesCameraPhotosToLibrary,
                onCameraPhotos, enabled = !state.saving, modifier = Modifier.testTag("photos_camera"))
        } } }
        item("hint") { SettingsFooter(stringResource(R.string.settings_photos_detail)) }
        settingsFeedback(state, onRetry)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.settingsFeedback(state: SettingsUiState, onRetry: () -> Unit) {
    if (state.preferences == null && !state.readFailed) item("loading") { RecordSkeleton(rows = 2) }
    if (state.saving) item("saving") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    if (state.readFailed || state.saveFailed) item("error") {
        SettingsBanner(stringResource(if (state.readFailed) R.string.read_failed else R.string.save_failed), error = true,
            actionLabel = if (state.readFailed) stringResource(R.string.retry) else null, onAction = onRetry)
    }
}
