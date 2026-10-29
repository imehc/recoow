package imehc.recoow.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/** 采用 Expressive 默认外观；业务页面不定义局部主题色。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RecoowTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialExpressiveTheme(colorScheme = if (darkTheme) darkColorScheme() else null, content = content)
}
