package imehc.recoow.feature.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import imehc.recoow.core.designsystem.RecoowPage
import imehc.recoow.core.domain.preferences.AppLanguage
import imehc.recoow.core.domain.preferences.Appearance

@Composable
fun SettingsScreen(state: SettingsUiState, onAppearance: (Appearance) -> Unit, onLanguage: (AppLanguage) -> Unit, onRetry: () -> Unit) {
    var selection by rememberSaveable { mutableStateOf<String?>(null) }
    val appearanceLabels = listOf(stringResource(R.string.follow_system), stringResource(R.string.appearance_light), stringResource(R.string.appearance_dark))
    val languageLabels = listOf(stringResource(R.string.follow_system), stringResource(R.string.language_chinese), stringResource(R.string.language_english))
    RecoowPage(stringResource(R.string.settings_title)) {
        item { Text(stringResource(R.string.display_section), style = MaterialTheme.typography.titleSmall) }
        val preferences = state.preferences
        if (preferences != null) {
            item {
                Card {
                    ListItem(
                        onClick = { selection = "appearance" },
                        enabled = !state.saving,
                        supportingContent = { Text(appearanceLabels[preferences.appearance.ordinal]) },
                        modifier = Modifier.testTag("appearance"),
                    ) { Text(stringResource(R.string.appearance_title)) }
                    ListItem(
                        onClick = { selection = "language" },
                        enabled = !state.saving,
                        supportingContent = { Text(languageLabels[preferences.language.ordinal]) },
                        modifier = Modifier.testTag("language"),
                    ) { Text(stringResource(R.string.language_title)) }
                }
            }
        } else if (!state.readFailed) { item { CircularProgressIndicator() } }
        if (state.readFailed || state.saveFailed) {
            item {
                Text(stringResource(if (state.readFailed) R.string.read_failed else R.string.save_failed), color = MaterialTheme.colorScheme.error)
                if (state.readFailed) TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
    }
    if (selection != null && state.preferences != null) {
        val appearance = selection == "appearance"
        val labels = if (appearance) appearanceLabels else languageLabels
        val selected = if (appearance) state.preferences.appearance.ordinal else state.preferences.language.ordinal
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
