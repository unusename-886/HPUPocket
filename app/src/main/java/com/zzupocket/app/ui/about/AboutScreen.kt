package com.zzupocket.app.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.zzupocket.app.ui.components.GlassCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zzupocket.app.BuildConfig

/**
 * ⚠️ 发布前把这里改成河南理工版的仓库地址。
 * 当前保留的是上游项目地址，用于标明代码来源与 MIT 许可义务，不是本版本的发布仓。
 */
private const val UPSTREAM_REPO_URL = "https://github.com/3beuml/ZZUPocket"
private const val UPSTREAM_AUTHOR_URL = "https://github.com/3beuml"

/**
 * 「关于」页：**这个 App 是什么、数据从哪来、怎么用你的数据、开源与致谢**。
 *
 * 之前这里顶着一块「非官方项目」的声明（按用户要求已删除）——
 * 同样的边界说明保留在仓库 README 与 [数据与隐私] 卡里，产品内不再重复。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 校徽：用户提供素材（已去白底），透明 PNG 自适应深浅主题
                    Image(
                        painter = painterResource(com.zzupocket.app.R.drawable.ic_school_badge),
                        contentDescription = com.zzupocket.app.core.SchoolConfig.SCHOOL_NAME_ZH + "校徽",
                        modifier = Modifier.size(96.dp),
                    )
                    Text(com.zzupocket.app.core.SchoolConfig.APP_NAME, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = com.zzupocket.app.core.SchoolConfig.SCHOOL_NAME_ZH + " · " +
                            com.zzupocket.app.core.SchoolConfig.SCHOOL_NAME_EN,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "把学校的课表系统整理成一个原生 Android App：登录走统一认证，" +
                            "课表按真实日期匹配，离线可看。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            AboutCard("数据与隐私") {
                Bullet("不保存你的密码。登录只走学校统一身份认证，本机仅保存加密后的会话 Cookie。")
                Bullet("课表、专注记录等数据只存在本机数据库，没有自建服务器，也不会上传。")
                Bullet("退出登录会清除本机会话；卸载 App 会一并清掉所有本地数据。")
                Bullet("只读：只抓取课表，不提交任何选课/修改操作。")
            }

            AboutCard("数据从哪来") {
                Bullet("课表：学校教务系统（EAMS），由已登录的 WebView 同源抓取，按真实日期匹配。")
                Bullet("作息时间：内置春/秋两套，可在「课程表设置」里切换或交给自动判断。")
            }

            AboutCard("开源与致谢") {
                Text(
                    text = "本版基于上游项目 ZZUPocket（MIT）适配河南理工大学。以下为上游项目" +
                        "参考过的社区协议实现，列此以履行署名义务，并非本版接口来源：",
                    style = MaterialTheme.typography.bodySmall,
                )
                LinkRow("上游：3beuml/ZZUPocket（MIT）", UPSTREAM_REPO_URL, uriHandler)
                LinkRow("Illustar0/ZZU.Py（MIT）", "https://github.com/Illustar0/ZZU.Py", uriHandler)
                LinkRow(
                    "Elykia093/ZZU-Electricity-Monitor（MIT）",
                    "https://github.com/Elykia093/ZZU-Electricity-Monitor",
                    uriHandler,
                )
                LinkRow(
                    "rimrose/ZZU-Dorm-Electricity-Balance-Base（MIT）",
                    "https://github.com/rimrose/ZZU-Dorm-Electricity-Balance-Base",
                    uriHandler,
                )
                TextButton(onClick = { uriHandler.openUri(UPSTREAM_AUTHOR_URL) }) { Text("上游作者主页") }
                TextButton(onClick = { uriHandler.openUri(UPSTREAM_REPO_URL) }) { Text("上游项目仓库") }
            }

            Text(
                text = "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun AboutCard(title: String, content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Text(
        text = "· $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LinkRow(label: String, url: String, uriHandler: androidx.compose.ui.platform.UriHandler) {
    TextButton(onClick = { uriHandler.openUri(url) }) { Text(label) }
}
