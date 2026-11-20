package com.zzupocket.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.zzupocket.app.ui.components.DescriptionText
import com.zzupocket.app.ui.components.GlassCard
import com.zzupocket.app.ui.components.HintText
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.R
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.core.LauncherIcon
import com.zzupocket.app.data.prefs.AppFontScale
import com.zzupocket.app.data.prefs.DarkContrast
import com.zzupocket.app.data.prefs.DarkMode
import com.zzupocket.app.data.prefs.ThemePreset
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.ui.theme.rememberAppColorScheme
import com.zzupocket.app.ui.theme.rememberEffectiveDark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 预设的中文名（放 UI 层，偏好存储里只存枚举） */
private fun ThemePreset.label(): String = when (this) {
    ThemePreset.DYNAMIC -> "跟随壁纸（动态取色）"
    ThemePreset.HPU_BLUE -> "理工蓝"
    ThemePreset.PURPLE -> "紫"
    ThemePreset.BLUE -> "蓝"
    ThemePreset.TEAL -> "青"
    ThemePreset.GREEN -> "绿"
    ThemePreset.AMBER -> "琥珀"
    ThemePreset.PINK -> "玫红"
    ThemePreset.GRAPHITE -> "石墨"
}

private fun DarkMode.label(): String = when (this) {
    DarkMode.SYSTEM -> "跟随系统"
    DarkMode.LIGHT -> "始终浅色"
    DarkMode.DARK -> "始终深色"
}

private fun DarkContrast.label(): String = when (this) {
    DarkContrast.STANDARD -> "标准"
    DarkContrast.MEDIUM -> "中"
    DarkContrast.HIGH -> "高"
}

private fun AppFontScale.label(): String = when (this) {
    AppFontScale.SYSTEM -> "跟随系统"
    AppFontScale.SMALL -> "小"
    AppFontScale.STANDARD -> "标准"
    AppFontScale.LARGE -> "大"
    AppFontScale.EXTRA_LARGE -> "特大"
}

class AppearanceViewModel : ViewModel() {

    private val store = AppGraph.uiPrefsStore

    private val _state = MutableStateFlow(UiPrefs())
    val state: StateFlow<UiPrefs> = _state.asStateFlow()

    init {
        viewModelScope.launch { store.flow.collect { _state.value = it } }
    }

    fun setPreset(preset: ThemePreset) {
        viewModelScope.launch { store.setThemePreset(preset) }
    }

    fun setDarkMode(mode: DarkMode) {
        viewModelScope.launch { store.setDarkMode(mode) }
    }

    fun setDarkContrast(v: DarkContrast) {
        viewModelScope.launch { store.setDarkContrast(v) }
    }

    fun setDarkTinted(v: Boolean) {
        viewModelScope.launch { store.setDarkTinted(v) }
    }

    fun setFontScale(v: AppFontScale) {
        viewModelScope.launch { store.setFontScale(v) }
    }

    fun setIconColor(color: LauncherIcon.IconColor) {
        viewModelScope.launch {
            store.setIconColor(color.name)
            // 切换 alias 必须放 IO：桌面图标切换有系统级开销，别卡主线程
            withContext(Dispatchers.IO) {
                AppGraph.appContextOrNull()?.let { LauncherIcon.apply(it, color) }
            }
        }
    }
}

/**
 * 「外观」页：切换整个 App 的配色与深浅色。
 *
 * 点一下**立刻全局生效**（主题在 MainActivity 上，改完整个 App 立刻重绘），
 * 所以每行左边那组色块本身就是预览色，不用再单独做一块预览区。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    vm: AppearanceViewModel = viewModel(),
) {
    val prefs by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("配色与深色模式") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "深浅色",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DarkMode.entries.forEach { mode ->
                    FilterChip(
                        selected = prefs.darkMode == mode,
                        onClick = { vm.setDarkMode(mode) },
                        label = { Text(mode.label()) },
                    )
                }
            }

            Text(
                text = "字体大小",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppFontScale.entries.forEach { scale ->
                    FilterChip(
                        selected = prefs.fontScale == scale,
                        onClick = { vm.setFontScale(scale) },
                        label = { Text(scale.label()) },
                    )
                }
            }
            HintText("「跟随系统」= 用系统设置里的字体大小；其余档位覆盖系统、只放大文字不改布局")

            Text(
                text = "深色对比度",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DarkContrast.entries.forEach { level ->
                    FilterChip(
                        selected = prefs.darkContrast == level,
                        onClick = { vm.setDarkContrast(level) },
                        label = { Text(level.label()) },
                    )
                }
            }

            Text(
                text = "深色保留颜色",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "深色底带主色色相、整体不再乌黑",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = prefs.darkTinted,
                    onCheckedChange = { vm.setDarkTinted(it) },
                )
            }

            Text("配色", style = MaterialTheme.typography.titleSmall)

            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    // 预览跟随「生效深浅」，对比度/保留颜色也随之联动，所见即所得
                    val previewDark = rememberEffectiveDark(prefs.darkMode)
                    ThemePreset.entries.forEach { preset ->
                        PresetRow(
                            preset = preset,
                            selected = prefs.themePreset == preset,
                            dark = previewDark,
                            darkContrast = prefs.darkContrast,
                            darkTinted = prefs.darkTinted,
                            onClick = { vm.setPreset(preset) },
                        )
                    }
                }
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("说明", style = MaterialTheme.typography.titleSmall)
                    // 用户要做决定的事只有一件：深浅色各管各的、两个深色选项只在深色下生效；
                    // 取色算法（种子色 / Material You / Expressive 规则）是实现细节，进描述层。
                    HintText("深浅色可以单独设；对比度与保留颜色只在深色下生效")
                    DescriptionText(
                        "「跟随壁纸」= 系统动态取色（Material You），跟壁纸同色；其余配色用 " +
                            "Material 3 种子色算法，与系统原生同规则。\n" +
                            "对比度：中/高档把文字、容器、边线整体推亮，「跟随壁纸」的选中/高档会以壁纸主色重算。\n" +
                            "保留颜色：深色表面带上配色/壁纸的主色色相、比默认深色亮一档，可与对比度叠加。"
                    )
                }
            }

            Text("桌面图标颜色", style = MaterialTheme.typography.titleSmall)
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LauncherIcon.IconColor.entries.forEach { color ->
                            IconColorChip(
                                color = color,
                                selected = prefs.iconColor == color.name,
                                onClick = { vm.setIconColor(color) },
                            )
                        }
                    }
                    DescriptionText(
                        "换色后回到桌面看效果；部分桌面需要一两秒才会刷新图标。只换桌面那枚图标，" +
                            "不影响应用内配色，也不影响登录和数据。",
                    )
                }
            }

            Spacer(Modifier.width(1.dp))
        }
    }
}

/** 图标配色的一个色块选项：圆点预览色 + 名字 + 选中打勾 */
@Composable
private fun IconColorChip(
    color: LauncherIcon.IconColor,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // 预览色直接取自图标资源的背景色：色值只有 colors.xml 一处真源，
    // 改色不会出现"设置页预览和桌面实际图标不一样"
    val swatch = colorResource(
        when (color) {
            LauncherIcon.IconColor.BLUE -> R.color.ic_launcher_bg_blue
            LauncherIcon.IconColor.PURPLE -> R.color.ic_launcher_bg_purple
            LauncherIcon.IconColor.TEAL -> R.color.ic_launcher_bg_teal
            LauncherIcon.IconColor.GREEN -> R.color.ic_launcher_bg_green
            LauncherIcon.IconColor.AMBER -> R.color.ic_launcher_bg_amber
            LauncherIcon.IconColor.PINK -> R.color.ic_launcher_bg_pink
        },
    )
    Row(
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(swatch)
                .border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    CircleShape,
                ),
        )
        Text(color.label, style = MaterialTheme.typography.bodyMedium)
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已选中",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 一行配色：左边三色块（主色 / 主色容器 / 第三色），右边名字，选中打勾 */
@Composable
private fun PresetRow(
    preset: ThemePreset,
    selected: Boolean,
    dark: Boolean,
    darkContrast: DarkContrast,
    darkTinted: Boolean,
    onClick: () -> Unit,
) {
    val swatches = rememberPresetSwatches(preset, dark, darkContrast, darkTinted)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(width = 56.dp, height = 28.dp)) {
            swatches.forEachIndexed { index, color ->
                Swatch(color = color, offsetIndex = index)
            }
        }
        Text(
            text = preset.label(),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已选中",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 三个重叠的圆点，用来看这套配色长什么样 */
@Composable
private fun Swatch(color: Color, offsetIndex: Int) {
    Box(
        Modifier
            .padding(start = (offsetIndex * 14).dp)
            .size(28.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

/** 某个预设（按当前生效深浅 + 对比度 + 保留颜色）的三个代表色 */
@Composable
private fun rememberPresetSwatches(
    preset: ThemePreset,
    dark: Boolean,
    darkContrast: DarkContrast,
    darkTinted: Boolean,
): List<Color> {
    // 与全局主题同一套生成逻辑（rememberAppColorScheme 内部已做 remember）
    val scheme = rememberAppColorScheme(
        preset = preset,
        dark = dark,
        darkContrast = darkContrast,
        darkTinted = darkTinted,
    )
    return listOf(scheme.primary, scheme.primaryContainer, scheme.tertiary)
}
