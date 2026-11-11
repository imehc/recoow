package imehc.recoow.feature.decision

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.decision.Turntable
import imehc.recoow.core.scene.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun DecisionModelPreview(dark: Boolean, back: () -> Unit) {
    val context = LocalContext.current
    val motion = rememberDecisionTurntableMotion()
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<DecisionModelEntry>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(context, generation) {
        failed = false
        try { models = decisionModelCatalog(context).sortedBy { it.key == "default" } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    val entries = models.orEmpty()
    val titles = entries.map { decisionModelTitle(it.key) }
    val modelKeys = remember(entries) { entries.map { it.key } }
    val focused by remember(motion, entries.size) { derivedStateOf { Turntable.focused(motion.cursor.toDouble(), entries.size) ?: 0 } }
    RecordPage(stringResource(R.string.decision_models), back, tag = "decision_model_preview", compact = true,
        action = if (entries.isNotEmpty()) stringResource(R.string.decision_model_rotate) else null,
        actionIcon = CommonR.drawable.ic_play, actionEnabled = !motion.isAnimating,
        onAction = { scope.launch { motion.spin(motion.cursor + entries.size, true); motion.snap(focused, entries.size) } }) {
        if (failed) item { RecordListFooter(failed = true, onRetry = { generation++ }) }
        else if (models == null) item { RecordSkeleton(rows = 3) }
        else {
            item { DecisionTurntablePanel(titles, motion, dark, 280.dp, interactive = true, modelKeys = modelKeys) }
            item {
                RecoowGroup {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { motion.browse(focused - 1, entries.size) }, enabled = entries.size > 1) {
                            Icon(painterResource(CommonR.drawable.ic_record_back), stringResource(R.string.decision_previous))
                        }
                        Text(titles.getOrNull(focused).orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        IconButton(onClick = { motion.browse(focused + 1, entries.size) }, enabled = entries.size > 1) {
                            Icon(painterResource(CommonR.drawable.ic_chevron_right), stringResource(R.string.decision_next))
                        }
                    }
                }
            }
            item {
                Column {
                    RecoowSectionHeader(stringResource(R.string.decision_model_count, entries.size))
                    RecoowGroup {
                        FlowRow(Modifier.padding(RecoowLayout.rowHorizontal), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            titles.forEachIndexed { index, title ->
                                FilterChip(index == focused, { motion.browse(index, entries.size) }, label = { Text(title) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun decisionModelTitle(key: String): String = stringResource(when (key) {
    "burger" -> R.string.decision_model_burger
    "noodles" -> R.string.decision_model_noodles
    "salad" -> R.string.decision_model_salad
    "sushi" -> R.string.decision_model_sushi
    "sandwich" -> R.string.decision_model_sandwich
    "hotpot" -> R.string.decision_model_hotpot
    "skewers" -> R.string.decision_model_skewers
    "malatang" -> R.string.decision_model_malatang
    "barbecue" -> R.string.decision_model_barbecue
    "luosifen" -> R.string.decision_model_luosifen
    "drypotshrimp" -> R.string.decision_model_drypotshrimp
    else -> R.string.decision_model_default
})
