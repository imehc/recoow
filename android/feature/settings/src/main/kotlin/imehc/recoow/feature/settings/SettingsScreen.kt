package imehc.recoow.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import imehc.recoow.core.designsystem.RecoowDivider
import imehc.recoow.core.designsystem.RecoowGroup
import imehc.recoow.core.designsystem.RecoowLayout
import imehc.recoow.core.designsystem.RecoowPage
import imehc.recoow.core.designsystem.RecoowRow
import imehc.recoow.core.designsystem.RecoowSectionHeader
import imehc.recoow.core.designsystem.RecoowSwitchRow
import imehc.recoow.core.designsystem.RecoowToolIcons
import imehc.recoow.core.designsystem.R as DesignR
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.preferences.AppLanguage
import imehc.recoow.core.domain.preferences.Appearance

@Composable
fun SettingsScreen(state: SettingsUiState, onAppearance: (Appearance) -> Unit, onLanguage: (AppLanguage) -> Unit,
    onRetry: () -> Unit, onSync: () -> Unit = {}, onMedia: () -> Unit = {}, onData: () -> Unit = {},
    onPickedPhotos: (Boolean) -> Unit = {}, onCameraPhotos: (Boolean) -> Unit = {},
    onToolVisible: (String, Boolean) -> Unit = { _, _ -> }, onUpdate: () -> Unit = {}, onAccount: () -> Unit = {}) {
    var selection by rememberSaveable { mutableStateOf<String?>(null) }
    val appearanceLabels = listOf(stringResource(R.string.follow_system), stringResource(R.string.appearance_light), stringResource(R.string.appearance_dark))
    val languageLabels = listOf(stringResource(R.string.follow_system), stringResource(R.string.language_chinese), stringResource(R.string.language_english))
    val preferences = state.preferences

    RecoowPage(stringResource(R.string.settings_title), contentSpacing = RecoowLayout.sectionGap) {
        item(key = "data") {
            Column {
                RecoowSectionHeader(stringResource(R.string.data_section))
                RecoowGroup {
                    SettingRow(R.drawable.ic_account_security, stringResource(R.string.account_security), "account_security", onClick = onAccount)
                    RecoowDivider(RecoowLayout.dividerInset)
                    SettingRow(DesignR.drawable.ic_setting_media, stringResource(R.string.media_library), "media_settings", onClick = onMedia)
                    RecoowDivider(RecoowLayout.dividerInset)
                    SettingRow(DesignR.drawable.ic_setting_data, stringResource(R.string.data_title), "data_settings", onClick = onData)
                    RecoowDivider(RecoowLayout.dividerInset)
                    SettingRow(DesignR.drawable.ic_setting_update, stringResource(R.string.update_title), "app_update", onClick = onUpdate)
                }
            }
        }
        if (preferences != null) {
            item(key = "display") {
                Column {
                    RecoowSectionHeader(stringResource(R.string.display_section))
                    RecoowGroup {
                        SettingRow(
                            icon = DesignR.drawable.ic_setting_appearance,
                            title = stringResource(R.string.appearance_title),
                            tag = "appearance",
                            trailing = appearanceLabels[preferences.appearance.ordinal],
                            onClick = { if (!state.saving) selection = "appearance" },
                        )
                        RecoowDivider(RecoowLayout.dividerInset)
                        SettingRow(
                            icon = DesignR.drawable.ic_setting_language,
                            title = stringResource(R.string.language_title),
                            tag = "language",
                            trailing = languageLabels[preferences.language.ordinal],
                            onClick = { if (!state.saving) selection = "language" },
                        )
                        RecoowDivider(RecoowLayout.dividerInset)
                        RecoowSwitchRow(
                            title = stringResource(R.string.photos_picked),
                            checked = preferences.addsPickedPhotosToMediaLibrary,
                            onChange = onPickedPhotos,
                            enabled = !state.saving,
                            icon = { SettingIcon(DesignR.drawable.ic_setting_media) },
                        )
                        RecoowDivider(RecoowLayout.dividerInset)
                        RecoowSwitchRow(
                            title = stringResource(R.string.photos_camera),
                            checked = preferences.savesCameraPhotosToLibrary,
                            onChange = onCameraPhotos,
                            enabled = !state.saving,
                            icon = { SettingIcon(DesignR.drawable.ic_setting_media) },
                        )
                    }
                }
            }
            item(key = "tools") {
                val names = LocalResources.current.getStringArray(R.array.home_tool_names)
                Column {
                    RecoowSectionHeader(stringResource(R.string.home_tools))
                    RecoowGroup {
                        ToolRoute.entries.forEachIndexed { index, tool ->
                            RecoowSwitchRow(
                                title = names[index],
                                checked = tool.id !in preferences.hiddenHomeTools,
                                onChange = { onToolVisible(tool.id, it) },
                                enabled = !state.saving,
                                icon = {
                                    Icon(
                                        painterResource(RecoowToolIcons.drawable(tool)),
                                        contentDescription = null,
                                        tint = RecoowToolIcons.tint(tool),
                                        modifier = Modifier.size(RecoowLayout.iconSize),
                                    )
                                },
                            )
                            if (index < ToolRoute.entries.lastIndex) RecoowDivider(RecoowLayout.dividerInset)
                        }
                    }
                }
            }
        } else if (!state.readFailed) {
            item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        if (state.readFailed || state.saveFailed) {
            item(key = "error") {
                Column {
                    Text(
                        stringResource(if (state.readFailed) R.string.read_failed else R.string.save_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (state.readFailed) {
                        TextButton(onClick = onRetry, modifier = Modifier.testTag("settings_retry")) {
                            Text(stringResource(R.string.retry), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }

    if (selection != null && preferences != null) {
        val appearance = selection == "appearance"
        val labels = if (appearance) appearanceLabels else languageLabels
        val selected = if (appearance) preferences.appearance.ordinal else preferences.language.ordinal
        AlertDialog(
            onDismissRequest = { selection = null },
            title = { Text(stringResource(if (appearance) R.string.appearance_title else R.string.language_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    labels.forEachIndexed { index, label ->
                        Row(
                            modifier = Modifier.fillMaxWidth().testTag("preference_option_$index").selectable(
                                selected = index == selected,
                                role = Role.RadioButton,
                                onClick = {
                                    selection = null
                                    if (appearance) onAppearance(Appearance.entries[index]) else onLanguage(AppLanguage.entries[index])
                                },
                            ).padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = index == selected, onClick = null)
                            Text(label, Modifier.padding(start = 16.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selection = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SettingIcon(icon: Int) {
    Icon(
        painterResource(icon),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(RecoowLayout.iconSize),
    )
}

@Composable
private fun SettingRow(icon: Int, title: String, tag: String, trailing: String? = null, onClick: () -> Unit) {
    RecoowRow(
        title = title,
        trailingText = trailing,
        showChevron = true,
        onClick = onClick,
        modifier = Modifier.testTag(tag),
        icon = { SettingIcon(icon) },
    )
}
