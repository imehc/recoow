package imehc.recoow.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as DesignR
import imehc.recoow.core.domain.preferences.AppLanguage
import imehc.recoow.core.domain.preferences.Appearance

@Composable
fun SettingsScreen(state: SettingsUiState, onAppearance: (Appearance) -> Unit, onLanguage: (AppLanguage) -> Unit,
    onRetry: () -> Unit, onSync: () -> Unit = {}, onMedia: () -> Unit = {}, onData: () -> Unit = {},
    onPickedPhotos: (Boolean) -> Unit = {}, onCameraPhotos: (Boolean) -> Unit = {},
    onToolVisible: (String, Boolean) -> Unit = { _, _ -> }, onUpdate: () -> Unit = {}, onAccount: () -> Unit = {},
    onDisplay: () -> Unit = {}, onHomeTools: () -> Unit = {}) {
    RecoowPage(stringResource(R.string.settings_title)) {
        item("account") { SettingsSection(stringResource(R.string.settings_account_group)) {
            RecoowGroup { SettingsNavigationRow(R.drawable.ic_account_security, stringResource(R.string.account_security),
                "account_security", stringResource(R.string.settings_account_hint), onClick = onAccount) }
        } }
        item("general") { SettingsSection(stringResource(R.string.settings_general)) {
            RecoowGroup {
                SettingsNavigationRow(DesignR.drawable.ic_setting_appearance, stringResource(R.string.settings_display_language),
                    "display_settings", stringResource(R.string.settings_display_hint), onClick = onDisplay)
                RecoowDivider(RecoowLayout.dividerInset)
                SettingsNavigationRow(DesignR.drawable.ic_grid, stringResource(R.string.home_tools), "home_tools_settings",
                    stringResource(R.string.settings_tools_hint), onClick = onHomeTools)
            }
        } }
        item("data") { SettingsSection(stringResource(R.string.settings_data_group)) {
            RecoowGroup { SettingsNavigationRow(DesignR.drawable.ic_setting_data, stringResource(R.string.data_title),
                "data_settings", stringResource(R.string.settings_data_hint), onClick = onData) }
        } }
        item("app") { SettingsSection(stringResource(R.string.settings_application)) {
            RecoowGroup { SettingsNavigationRow(DesignR.drawable.ic_setting_update, stringResource(R.string.update_title),
                "app_update", stringResource(R.string.settings_update_hint), onClick = onUpdate) }
        } }
        if (state.readFailed) item("error") { SettingsBanner(stringResource(R.string.read_failed), error = true,
            actionLabel = stringResource(R.string.retry), onAction = onRetry) }
    }
}

@Composable internal fun SettingsIcon(icon: Int) {
    Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(RecoowLayout.iconSize))
}

@Composable internal fun SettingsNavigationRow(icon: Int, title: String, tag: String,
    subtitle: String? = null, value: String? = null, onClick: () -> Unit) {
    RecoowRow(title, Modifier.testTag(tag), subtitle = subtitle, trailingText = value,
        showChevron = true, onClick = onClick, icon = { SettingsIcon(icon) })
}
