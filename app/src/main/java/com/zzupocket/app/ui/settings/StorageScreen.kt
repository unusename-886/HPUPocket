package com.zzupocket.app.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zzupocket.app.core.CacheManager
import com.zzupocket.app.ui.components.DescriptionText
import com.zzupocket.app.ui.components.GlassCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「更多 → 存储空间」：缓存占用统计 + 一键清理。
 *
 * 只清可再生临时文件（cacheDir + 运行/崩溃日志），不碰登录态、课表、壁纸、偏好。
 * 进入本页时实时统计一次；清除后重新统计并给 Snackbar 反馈。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var info by remember { mutableStateOf(CacheManager.cacheInfo(context)) }
    var clearing by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            info = withContext(Dispatchers.IO) { CacheManager.cacheInfo(context) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("存储空间") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("缓存占用", style = MaterialTheme.typography.titleMedium)
                    ListItem(
                        headlineContent = { Text("临时文件") },
                        supportingContent = { Text(Formatter.formatFileSize(context, info.tempBytes)) },
                    )
                    ListItem(
                        headlineContent = { Text("运行与崩溃日志") },
                        supportingContent = { Text(Formatter.formatFileSize(context, info.logBytes)) },
                    )
                    ListItem(
                        headlineContent = { Text("合计", style = MaterialTheme.typography.titleSmall) },
                        supportingContent = {
                            Text(
                                Formatter.formatFileSize(context, info.totalBytes),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        },
                    )
                }
            }

            Button(
                onClick = {
                    clearing = true
                    scope.launch {
                        val freed = withContext(Dispatchers.IO) { CacheManager.clearCache(context) }
                        info = withContext(Dispatchers.IO) { CacheManager.cacheInfo(context) }
                        clearing = false
                        snackbar.showSnackbar("已清理 ${Formatter.formatFileSize(context, freed)}")
                    }
                },
                enabled = !clearing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (clearing) "清理中…" else "清除缓存")
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("说明", style = MaterialTheme.typography.titleSmall)
                    DescriptionText(
                        "只清除临时文件与日志。登录状态、课表、专注记录、背景壁纸和所有设置都不会被删除，" +
                            "清理后可直接使用。清空缓存只影响「诊断」页可导出的历史日志。"
                    )
                }
            }
        }
    }
}
