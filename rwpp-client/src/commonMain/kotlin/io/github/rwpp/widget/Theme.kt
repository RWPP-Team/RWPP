/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget

import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.rwpp.config.Settings
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.ui.UI
import org.koin.compose.koinInject

val RWOutlinedTextColors
    @Composable get() =
        OutlinedTextFieldDefaults.colors(
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            cursorColor = MaterialTheme.colorScheme.secondary,
            selectionColors = TextSelectionColors(MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.primaryContainer.copy(.4f)),
            focusedBorderColor = MaterialTheme.colorScheme.secondaryContainer,
            unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurface,
            disabledLabelColor = MaterialTheme.colorScheme.onSurface
        )

val RWSliderColors
    @Composable get() =
        SliderDefaults.colors(
            thumbColor =  MaterialTheme.colorScheme.primary,
            activeTrackColor =  MaterialTheme.colorScheme.primary,
            activeTickColor = MaterialTheme.colorScheme.secondary,
            inactiveTrackColor = MaterialTheme.colorScheme.onSurface,
            inactiveTickColor = MaterialTheme.colorScheme.onSurface
        )

val RWSelectionColors
    @Composable get() = TextSelectionColors(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.primary
    )

val RWTextFieldColors
    @Composable get() = TextFieldDefaults.colors(
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = UI.backgroundTransparency + 0.1f.coerceAtMost(1f)),
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha =  UI.backgroundTransparency + 0.1f.coerceAtMost(1f)),
        cursorColor = MaterialTheme.colorScheme.primary,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = MaterialTheme.colorScheme.onSurface,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent
    )

val RWCheckBoxColors
    @Composable get() = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.secondaryContainer)


val defaultRWPPColorScheme = darkColorScheme(
        surface = Color(27, 18, 18),
        surfaceContainer = Color.DarkGray,
        onSurface = Color.White,
        primaryContainer = Color(151, 188, 98),
        secondary = Color(95, 190, 95),
        secondaryContainer = Color(151, 188, 98),
        onSecondaryContainer = Color(151, 188, 98),
        primary = Color(151, 188, 98),
        onSecondary = Color(0xFF000000),
        background = Color(53, 57, 53),
        inversePrimary = Color(44, 95, 45),
        onTertiaryContainer = Color.White,
        surfaceTint = Color.White,
        onPrimary = Color(0xFF000000),
    )

@Composable
fun RWPPTheme(default: Boolean = false, content: @Composable () -> Unit) {
    val jostFonts = JostFonts()
    val valoraxFont = ValoraxFont()
    val selectedColorScheme = remember(
        if (!default)
            UI.selectedColorSchemeName
        else Unit
    ) { themes[if (default) "RWPP" else UI.selectedColorSchemeName]!! }

    if (!default) {
        val settings = koinInject<Settings>()

        LaunchedEffect(UI.selectedColorSchemeName) {
            if (settings.changeGameTheme) {
                val color = themes[UI.selectedColorSchemeName]!!.primary
                GameEngine.getInstance().interfaceEngine.screenMessageTextPaint.color = color.toArgb()
            }
        }
    }

    val typography = Typography(
        displayLarge = TextStyle(
            color = selectedColorScheme.onSurface,
            fontFamily = valoraxFont,
            fontWeight = FontWeight.Normal,
            fontSize = 32.sp
        ),
        headlineLarge = TextStyle(
            color = selectedColorScheme.onSurface,
            fontFamily = jostFonts,
            fontWeight = FontWeight.Bold,
            fontSize = 21.sp
        ),
        headlineMedium = TextStyle(
            color = selectedColorScheme.onSurface,
            fontFamily = jostFonts,
            fontWeight = FontWeight.Normal,
            fontSize = 19.sp
        ),
        headlineSmall = TextStyle(
            color = selectedColorScheme.onSurface,
            fontFamily = jostFonts,
            fontWeight = FontWeight.Normal,
            fontSize = 17.sp
        ),
        bodyLarge = TextStyle(
            color = selectedColorScheme.onSurface,
            fontFamily = jostFonts,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        ),
        bodyMedium = TextStyle(
            color = selectedColorScheme.onSurface,
            fontFamily = jostFonts,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp
        )
    )

    MaterialTheme(
        typography = typography,
        colorScheme = selectedColorScheme,
        content = content
    )
}

val themes = mapOf(
    "RWPP" to defaultRWPPColorScheme,
    "Material Purple" to darkColorScheme(
        background = Color(48, 40, 56), // 偏紫的深灰色
        surfaceContainer = Color(68, 60, 76),
    ),

    "Material Default" to darkColorScheme(
        primary = Color(0xFFBB86FC),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF6200EE),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFF03DAC6),
        onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFF005047),
        onSecondaryContainer = Color(0xFFFFFFFF),
        tertiary = Color(0xFF03DAC6),
        onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFF003E3E),
        onTertiaryContainer = Color(0xFFFFFFFF),
        inversePrimary = Color(0xFF543F6A),
        background = Color(48, 40, 56), // 偏紫的深灰色
        surfaceContainer = Color(68, 60, 76),
    ),

    "Amber Blue" to darkColorScheme(
        primary = Color(0xFFFFB300),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFFC87200),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFF82B1FF),
        onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFF3770CF),
        onSecondaryContainer = Color(0xFFFFFFFF),
        tertiary = Color(0xFF448AFF),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFF0B429C),
        onTertiaryContainer = Color(0xFFFFFFFF),
        inversePrimary = Color(0xFF6A510A),
        background = Color(48, 44, 40), // 偏黄的深灰色
        surfaceContainer = Color(68, 64, 60),
    ),

    "Aqua Blue" to darkColorScheme(
        primary = Color(0xFF5DB3D5),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF297EA0),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFFA1E9DF),
        onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFF005049),
        onSecondaryContainer = Color(0xFFFFFFFF),
        tertiary = Color(0xFFA0E5E5),
        onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFF004F50),
        onTertiaryContainer = Color(0xFFFFFFFF),
        inversePrimary = Color(0xFF2F515F),
        background = Color(40, 50, 56), // 偏蓝的深灰色
        surfaceContainer = Color(60, 70, 76),
    ),

    "Bahama And Trinidad" to darkColorScheme(
        primary = Color(0xFF4585B5),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF095D9E),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFFE57C4A),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFDD520F),
        onSecondaryContainer = Color(0xFFFFFFFF),
        tertiary = Color(0xFF9CD5F9),
        onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFF3A7292),
        onTertiaryContainer = Color(0xFFFFFFFF),
        inversePrimary = Color(0xFF253F52),
        background = Color(40, 48, 56), // 蓝灰色
        surfaceContainer = Color(60, 68, 76),
    ),

    "Gold Sunset" to darkColorScheme(
        primary = Color(0xFFEDA85E),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFFB86914),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFFD28F60),
        onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFFB5642C),
        onSecondaryContainer = Color(0xFFFFFFFF),
        tertiary = Color(0xFFDDAB88),
        onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFFBF7D4E),
        onTertiaryContainer = Color(0xFFFFFFFF),
        inversePrimary = Color(0xFF684D2F),
        background = Color(56, 48, 40), // 暖灰色
        surfaceContainer = Color(76, 68, 60),
    ),

    "Flutter Dash" to darkColorScheme(
        primary = Color(0xFFB4E6FF),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF1E8FDB),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFF99CCF9),
        onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFF202B6D),
        onSecondaryContainer = Color(0xFFFFFFFF),
        tertiary = Color(0xFFBAA99D),
        onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFF514239),
        onTertiaryContainer = Color(0xFFFFFFFF),
        inversePrimary = Color(0xFF52666A),
        background = Color(40, 50, 56), // 偏蓝的深灰色
        surfaceContainer = Color(60, 70, 76),
    ),

    "Hippie Blue" to  darkColorScheme(
        primary = Color(0xFF669DB3),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF078282),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFFFC6E75),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFF92001A),
        onSecondaryContainer = Color(0xFFFFFFFF),
        tertiary = Color(0xFFF75F67),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFF580810),
        onTertiaryContainer = Color(0xFFFFFFFF),
        inversePrimary = Color(0xFF324851),
        background = Color(40, 50, 56), // 蓝灰色
        surfaceContainer = Color(60, 70, 76),
    ),

    "Pink Sakura" to darkColorScheme(
        primary = Color(0xFFEEC4D8),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFFCE5B78),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFFF5D6C6),
        onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFFEBA689),
        onSecondaryContainer = Color(0xFF000000),
        tertiary = Color(0xFFF7E0D4),
        onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFFEEBDA8),
        onTertiaryContainer = Color(0xFF000000),
        inversePrimary = Color(0xFF695860),
        background = Color(56, 48, 52), // 偏粉的深灰色
        surfaceContainer = Color(76, 68, 72),
    ),

    "Blumine" to darkColorScheme(
        primary = Color(0xFF82BACE),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF04666F),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFFFFD682),
        onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFF9E7910),
        onSecondaryContainer = Color(0xFFFFFFFF),
        tertiary = Color(0xFF243E4D),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFF426173),
        onTertiaryContainer = Color(0xFFFFFFFF),
        inversePrimary = Color(0xFF3E545C),
        background = Color(40, 50, 56), // 蓝灰色
        surfaceContainer = Color(60, 70, 76),
    ),

    "Green Money" to darkColorScheme(
        primary = Color(0xFF7AB893),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF224430),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFFD5D6A8),
        onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFF515402),
        onSecondaryContainer = Color(0xFFFFFFFF),
        tertiary = Color(0xFFBBBE74),
        onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFF404204),
        onTertiaryContainer = Color(0xFFFFFFFF),
        inversePrimary = Color(0xFF3A5344),
        background = Color(40, 50, 44), // 偏绿的深灰色
        surfaceContainer = Color(60, 70, 64),
    ),

    "Rosewood" to darkColorScheme(
        primary = Color(0xFF9C5A69),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF5F111E),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFFEDCE9B),
        onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFF805E23),
        onSecondaryContainer = Color(0xFFFFFFFF),
        tertiary = Color(0xFFF5DFB9),
        onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFF8E6E3C),
        onTertiaryContainer = Color(0xFFFFFFFF),
        inversePrimary = Color(0xFF482E34),
        background = Color(56, 44, 48), // 偏红的深灰色
        surfaceContainer = Color(76, 64, 68),
    ),

    "Verdun Lime" to darkColorScheme(
        primary = Color(0xFFBCD063),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF3F4C00),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFFFFE17B),
        onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFF3B2F00),
        onSecondaryContainer = Color(0xFFFFFFFF),
        tertiary = Color(0xFF78D3EC),
        onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFF224E43),
        onTertiaryContainer = Color(0xFFFFFFFF),
        inversePrimary = Color(0xFF555D31),
        background = Color(48, 50, 40), // 偏黄绿的深灰色
        surfaceContainer = Color(68, 70, 60),
    ),

    "Grey Law" to darkColorScheme(
        primary = Color(0xFF90A4AE),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF37474F),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFF815AA3),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFF421F62),
        onSecondaryContainer = Color(0xFFFFFFFF),
        tertiary = Color(0xFF373D5C),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFF1D2449),
        onTertiaryContainer = Color(0xFFFFFFFF),
        error = Color(0xFFCF6679),
        onError = Color(0xFF000000),
        errorContainer = Color(0xFFB1384E),
        onErrorContainer = Color(0xFFFFFFFF),
        inversePrimary = Color(0xFF434B4F),
        background = Color(48, 50, 52), // 中性深灰色
        surfaceContainer = Color(68, 70, 72),
    ),

    "Red Tornado" to darkColorScheme(
        primary = Color(0xFFEF9A9A),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFFB71C1C),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFFF8BBD0),
        onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFFAD1457),
        onSecondaryContainer = Color(0xFFFFFFFF),
        tertiary = Color(0xFFFCE4EC),
        onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFFC2185B),
        onTertiaryContainer = Color(0xFFFFFFFF),
        inversePrimary = Color(0xFF694747),
        background = Color(56, 44, 44), // 偏红的深灰色
        surfaceContainer = Color(76, 64, 64),
    )
)
