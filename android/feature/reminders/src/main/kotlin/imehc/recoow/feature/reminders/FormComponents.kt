package imehc.recoow.feature.reminders

import imehc.recoow.core.designsystem.recordControlDescription
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable internal fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.reminder_back)) }
}

@Composable internal fun dateTime(value: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(value, zone, locale) { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(value).atZone(zone)) }
}
