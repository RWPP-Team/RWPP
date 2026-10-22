/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget.v2

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.api.config.ConfigIO
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.ui.UI
import io.github.rwpp.widget.LargeDropdownMenu
import io.github.rwpp.widget.RWSingleOutlinedTextField
import io.github.rwpp.widget.RWSliderColors

import io.github.rwpp.widget.WindowManager
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Composable
fun ExpandedCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.background.copy((UI.backgroundTransparency + 0.2f).coerceAtMost(1f)),
    content: @Composable ColumnScope.() -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Card(
            shape = RectangleShape,
            elevation =  CardDefaults.cardElevation(defaultElevation = 10.dp),
            colors = CardDefaults.cardColors(containerColor = backgroundColor),
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(if (LocalWindowManager.current == WindowManager.Small) 0.95f else 0.75f)
                .then(modifier),
            content = content
        )
    }
}

@Composable
fun RWIconButton(
    vector: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 50.dp,
    tint: Color = MaterialTheme.colorScheme.surfaceTint,
    onClick: () -> Unit,
) {
    Card(
        border = BorderStroke(3.dp, MaterialTheme.colorScheme.surfaceContainer),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(5.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp),
        modifier = Modifier.then(modifier).bounceClick(onClick = onClick),
    ) {
        Icon(
            vector,
            null,
            tint = tint,
            modifier = Modifier.size(size).align(Alignment.CenterHorizontally).padding(10.dp)
        )
    }
}

@Composable
fun RWIconButton(
    painter: Painter,
    modifier: Modifier = Modifier,
    size: Dp = 50.dp,
    tint: Color = MaterialTheme.colorScheme.surfaceTint,
    onClick: () -> Unit,
) {
    Card(
        border = BorderStroke(3.dp, MaterialTheme.colorScheme.surfaceContainer),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(5.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp),
        modifier = Modifier.then(modifier).bounceClick(onClick = onClick),
    ) {
        Icon(
            painter,
            null,
            tint = tint,
            modifier = Modifier.size(size).align(Alignment.CenterHorizontally).padding(10.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LongPressFloatingActionButton(
    vector: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 50.dp,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = androidx.compose.foundation.shape.CircleShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp),
        modifier = Modifier.then(modifier).combinedClickable(onLongClick = onLongClick, onClick = onClick),
    ) {
        Icon(
            vector,
            null,
            tint = MaterialTheme.colorScheme.surfaceTint,
            modifier = Modifier.size(size).align(Alignment.CenterHorizontally).padding(10.dp)
        )
    }
}

@Composable
fun SettingsSwitchComp(
    name: String,
    labelName: String = name,
    defaultValue: Boolean? = null,
    customConfigSettingAction: ((Boolean) -> Unit)? = null
) {
    val configIO = koinInject<ConfigIO>()

    var state by remember { mutableStateOf(defaultValue ?: configIO.getGameConfig(name)) }
    val onClick = customConfigSettingAction?.let{
        {
            state = !state
            customConfigSettingAction(state)
        }
    } ?: {
        state = !state
        configIO.setGameConfig(name, state)
    }
    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth(),
        onClick = onClick,
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if(customConfigSettingAction != null) labelName else readI18n("menus.settings.option.$labelName"),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Start,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Switch(
                    checked = state,
                    modifier = Modifier.padding(end = 15.dp),
                    onCheckedChange = { onClick() },
                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary, checkedThumbColor = MaterialTheme.colorScheme.onSurface),
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
fun SettingsTextField(
    label: String,
    value: String,
    trailingIcon: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    lengthLimitCount: Int = Int.MAX_VALUE,
    typeInOnlyInteger: Boolean = false,
    typeInNumberOnly: Boolean = false,
    enabled: Boolean = true,
    appendedContent: @Composable (() -> Unit)? = null,
    onValueChange: (String) -> Unit
) {
    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = label,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Start,
                    )
                }

                Spacer(modifier = Modifier.weight(1f))
                RWSingleOutlinedTextField(
                    "",
                    value,
                    Modifier.width(300.dp).padding(end = 15.dp),
                    trailingIcon,
                    leadingIcon,
                    lengthLimitCount,
                    typeInOnlyInteger,
                    typeInNumberOnly,
                    enabled,
                    appendedContent,
                    onValueChange = onValueChange
                )

            }
            HorizontalDivider()
        }
    }
}

@Composable
fun <T> SettingsDropDown(
    name: String,
    items: List<T>,
    selectedIndex: Int = 0,
    selectedItemColor: @Composable (T?, Int) -> Color = { _, _ -> MaterialTheme.colorScheme.onSurface },
    onSelectedItem: (Int, T) -> Unit,
) {
    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = readI18n("rwpp.settings.$name"),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Start,
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                LargeDropdownMenu(
                    modifier = Modifier.width(300.dp).padding(end = 15.dp),
                    items = items,
                    label = "",
                    selectedIndex = selectedIndex,
                    onItemSelected = onSelectedItem,
                    selectedItemColor = selectedItemColor
                )
            }

            HorizontalDivider()
        }
    }
}

@Composable
fun SettingsSlider(
    name: String,
    defaultValue: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    valueFormat: (Float) -> String = { (it * 100).roundToInt().toString() + "%" }
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(16.dp)
                )
            }

            var value by remember { mutableStateOf(defaultValue) }

            LaunchedEffect(value) {
                onValueChange(value)
            }

            Spacer(modifier = Modifier.weight(1f))

            Slider(
                value = value,
                valueRange = valueRange,
                modifier = Modifier.width(250.dp).padding(end = 5.dp),
                onValueChange = { value = it },
                colors = RWSliderColors
            )

            Text(valueFormat(value), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(50.dp).padding(top = 6.dp, end = 5.dp))
        }

        HorizontalDivider()
    }

}