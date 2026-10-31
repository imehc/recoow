package imehc.recoow.core.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp

/** The existing clickable container owns the action; expose its value on the same node. */
fun Modifier.recordToggleState(checked: Boolean, controlRole: Role = Role.Checkbox): Modifier = semantics {
    role = controlRole
    toggleableState = ToggleableState(checked)
}

fun Modifier.recordControlDescription(label: String, value: String? = null): Modifier = semantics {
    contentDescription = label
    if (value != null) stateDescription = value
}

@Composable
fun RecordSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun RecordSwitchItem(label: String, checked: Boolean, onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    ListItem(onClick = { onChange(!checked) }, enabled = enabled,
        modifier = modifier.recordToggleState(checked, Role.Switch),
        trailingContent = { Switch(checked, onCheckedChange = null, enabled = enabled) }) { Text(label) }
}
