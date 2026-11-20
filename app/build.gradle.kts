import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// ── 签名 ───────────────────────────────────────────────────────────────
// 凭据在仓库根的 keystore.properties（已 gitignore），keystore 文件本身在仓库之外。
// 刻意让 debug 与 release 用**同一套签名**：不同签名不能互相覆盖安装，
// 切换时必须卸载 → 本地数据全丢。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val releaseStoreFile = keystoreProps.getProperty("storeFile")?.let { file(it) }
val hasReleaseSigning = releaseStoreFile?.exists() == true

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    // ⚠️ namespace 保持 com.zzupocket.app：它决定 R/BuildConfig 的包名，
    // 改它要同步改全部源码 import + AndroidManifest + 测试，风险高、无用户收益。
    // 对外安装标识用下面的 applicationId 区分两校版本，可同机共存。
    namespace = "com.zzupocket.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hpupocket.app"
        minSdk = 31
        targetSdk = 36
        // 河南理工版从 1 起算：与郑大版是不同应用，不共享版本序列
        // 2: 0.2.0-hpu —— 新增「周末延长」开关（课表可多出第 8 列「周八」）
        // 3: 0.2.1-hpu —— 宿舍用电（楼栋/楼层/房间三级选择、照明+空调同查、首页电量卡、负数电量）
        // 4: 0.2.2-hpu —— 空闲教室（按日期/节次查空教室，教务同源会话）
        // 5: 0.2.3-hpu —— 空闲教室全链路埋点 + WebView 登录承接页等待（诊断版）
        // 6: 0.2.4-hpu —— 空闲教室查询改 form.submit() 真导航（fetch 校外必 Failed to fetch）
        // 7: 0.2.5-hpu —— 空闲教室：填教务自己的表单、UA 去 ;wv、登录页静默续期、异常栈日志
        // 8: 0.2.6-hpu —— 空闲教室补 project.id=1（教务 project not selected）、页面结构探针
        // 9: 0.2.7-hpu —— project.id 改放 URL query（body 无效）+ 入口页报错时扒首页真实链接
        versionCode = 9
        // 版本号不带 -P6 那种阶段后缀（阶段划分是 docs 的事）；预发行用标准 rc 标记（如 0.33.0-rc1），
        // AppUpdate.compareVersions 对它有专门规则：rc < 同号正式版，正式版发布后已装 rc 的用户能看到红点。
        // ⚠️ 升级判断只认 versionCode（Android 的规则），versionName 是给人看的；
        // 所以改名号（哪怕是"变小"的数字）都不会影响覆盖安装，每次发版 versionCode 必须 +1。
        versionName = "0.2.7-hpu"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("hpupocket") {
                storeFile = releaseStoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("hpupocket")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("hpupocket")
            } else {
                logger.lifecycle("⚠️ 未找到 keystore.properties，release 包将使用 debug 签名")
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Room 导出 schema，便于日后写迁移。ksp{} 是项目级扩展，必须放在 android{} 之外。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    // 定时采样电量：学校不提供历史用电量，只能靠自己按点采样差分（每 4 小时一次）
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Material You 配色生成（由种子色算出整套 M3 浅/深配色，见 ui/theme/MaterialYouScheme.kt）
    implementation(libs.material.color.utilities)

    // 网络（不用 Retrofit：接口少，且 CAS 登录的 POST 走 query string，裸 OkHttp 更直接）
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // 本地存储
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // 真机自检（androidTest）：像"图书馆会话/记录接口"这类要看设备上的真实登录态与真实
    // 响应的验证，用 instrumentation 比 adb 盲点 UI 可靠得多 —— 依赖直接写死三个，
    // 不进 version catalog（仅此一处用）。
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
