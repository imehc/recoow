package imehc.recoow.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.food.MealKind

/**
 * Recoow 的唯一色板。页面通过 MaterialTheme 读取，不在功能页复制十六进制值。
 * 浅色与深色是两套独立中性色阶，不是简单反相。
 */
object RecoowPalette {
    val background = Color(0xFFF4F5F7)
    val surface = Color(0xFFFFFFFF)
    val onSurface = Color(0xFF1C2026)
    val onSurfaceVariant = Color(0xFF696F79)
    val outline = Color(0xFFD8DCE2)
    val outlineVariant = Color(0xFFECEFF3)
    val primary = Color(0xFF3E70E3)
    val primaryContainer = Color(0xFFE8EFFD)
    val onPrimaryContainer = Color(0xFF1B3A8F)
    val success = Color(0xFF067E5B)
    val warning = Color(0xFF9B6109)
    val error = Color(0xFFCB3345)
    val toolBills = Color(0xFF067E5B)
    val toolReminders = Color(0xFF2868DA)
    val toolDiary = Color(0xFF8250CD)
    val toolFood = Color(0xFFB85E1A)
    val toolDecision = Color(0xFFC5366F)
    val toolItems = Color(0xFF087B92)
    val toolAnniversaries = Color(0xFF9B6109)
    val toolTracks = Color(0xFF677180)

    fun warningColor(dark: Boolean): Color = if (dark) Color(0xFFC2892A) else warning

    /** 与 iOS 餐别图标对应的语义色，供列表图标及推荐共用。 */
    fun foodMealColor(meal: MealKind, dark: Boolean): Color = when (meal) {
        MealKind.BREAKFAST -> warningColor(dark)
        MealKind.LUNCH -> if (dark) Color(0xFF16A87B) else success
        MealKind.DINNER, MealKind.LATE_NIGHT -> if (dark) Color(0xFFA77BEC) else toolDiary
        MealKind.SNACK -> if (dark) Color(0xFFE6689B) else toolDecision
        MealKind.DRINK -> if (dark) Color(0xFF159DB6) else toolItems
        MealKind.OTHER -> if (dark) Color(0xFF737E8F) else toolTracks
    }

    /** 功能图标只上色、不加底板；深色下逐个提亮以维持辨识度。 */
    fun toolColor(route: ToolRoute, dark: Boolean): Color = when (route) {
        ToolRoute.BILLS -> if (dark) Color(0xFF16A87B) else toolBills
        ToolRoute.REMINDERS -> if (dark) Color(0xFF4588EF) else toolReminders
        ToolRoute.DIARY -> if (dark) Color(0xFFA77BEC) else toolDiary
        ToolRoute.FOOD -> if (dark) Color(0xFFD57E33) else toolFood
        ToolRoute.DECISION -> if (dark) Color(0xFFE6689B) else toolDecision
        ToolRoute.ITEMS -> if (dark) Color(0xFF159DB6) else toolItems
        ToolRoute.ANNIVERSARIES -> if (dark) Color(0xFFC2892A) else toolAnniversaries
        ToolRoute.LOCATION -> if (dark) Color(0xFFA7ABB4) else toolTracks
    }

    fun lightScheme(): ColorScheme = lightColorScheme(
        primary = primary,
        onPrimary = Color.White,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = Color(0xFFB4C8FF),
        secondary = primary,
        onSecondary = Color.White,
        secondaryContainer = primaryContainer,
        onSecondaryContainer = onPrimaryContainer,
        tertiary = success,
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFDDF3EA),
        onTertiaryContainer = Color(0xFF043F2E),
        background = background,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = Color(0xFFF0F2F5),
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = primary,
        surfaceBright = surface,
        surfaceDim = Color(0xFFE2E5EA),
        surfaceContainerLowest = surface,
        surfaceContainerLow = Color(0xFFF8F9FB),
        surfaceContainer = background,
        surfaceContainerHigh = Color(0xFFECEFF3),
        surfaceContainerHighest = Color(0xFFE5E8ED),
        outline = outline,
        outlineVariant = outlineVariant,
        error = error,
        onError = Color.White,
        errorContainer = Color(0xFFFBE4E7),
        onErrorContainer = Color(0xFF5C1119),
        inverseSurface = Color(0xFF2B3037),
        inverseOnSurface = Color(0xFFF1F2F4),
        scrim = Color.Black.copy(alpha = 0.32f),
    )

    fun darkScheme(): ColorScheme = darkColorScheme(
        primary = Color(0xFF98B3FF),
        onPrimary = Color(0xFF141A2C),
        primaryContainer = Color(0xFF26324E),
        onPrimaryContainer = Color(0xFFC9D8FF),
        inversePrimary = primary,
        secondary = Color(0xFF98B3FF),
        onSecondary = Color(0xFF141A2C),
        secondaryContainer = Color(0xFF26324E),
        onSecondaryContainer = Color(0xFFC9D8FF),
        tertiary = Color(0xFF16A87B),
        onTertiary = Color(0xFF04140E),
        tertiaryContainer = Color(0xFF0E3A2C),
        onTertiaryContainer = Color(0xFFB9E8D5),
        background = Color(0xFF111214),
        onBackground = Color(0xFFF3F4F6),
        surface = Color(0xFF1F2024),
        onSurface = Color(0xFFF3F4F6),
        surfaceVariant = Color(0xFF282A2F),
        onSurfaceVariant = Color(0xFFA7ABB4),
        surfaceTint = Color(0xFF98B3FF),
        surfaceBright = Color(0xFF35373D),
        surfaceDim = Color(0xFF111214),
        surfaceContainerLowest = Color(0xFF111214),
        surfaceContainerLow = Color(0xFF191A1E),
        surfaceContainer = Color(0xFF1F2024),
        surfaceContainerHigh = Color(0xFF282A2F),
        surfaceContainerHighest = Color(0xFF303239),
        outline = Color(0xFF3A3D44),
        outlineVariant = Color(0xFF2A2D33),
        error = Color(0xFFF06672),
        onError = Color(0xFF2A0A0E),
        errorContainer = Color(0xFF4A151B),
        onErrorContainer = Color(0xFFFFD9DC),
        inverseSurface = Color(0xFFE9EAEC),
        inverseOnSurface = Color(0xFF26282C),
        scrim = Color.Black.copy(alpha = 0.5f),
    )
}

/**
 * 六级语义字阶：导航 20 / 分组 13 / 行标题 16 / 辅助 13 / 元信息 12 / 指标 20。
 * 页面只引用语义槽位，不写固定 fontSize，跟随系统字体缩放。
 */
private val Base = Typography()
private val RecoowTypography = Base.copy(
    titleLarge = Base.titleLarge.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.15).sp),
    titleMedium = Base.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleSmall = Base.titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = Base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = Base.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = Base.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = Base.labelLarge.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.26.sp),
    labelMedium = Base.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = Base.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

private val RecoowShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(RecoowLayout.cardRadius),
    extraLarge = RoundedCornerShape(24.dp),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RecoowTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = if (darkTheme) RecoowPalette.darkScheme() else RecoowPalette.lightScheme(),
        typography = RecoowTypography,
        shapes = RecoowShapes,
        content = content,
    )
}
