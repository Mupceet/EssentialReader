// AGP < 9 宿主（源码嵌入）专用构建变体——宿主 settings.gradle 经
// `project(':modules:eink').buildFileName = 'build-agp8.gradle.kts'` 选用
// 本文件（参照 contract/EINK-PORTING.md §2 步骤 3b 与子模块形态）。
//
// 与 build.gradle.kts（AGP 9 默认形态）逐段同步，差异仅两处：
//  1. plugins 增加 kotlin-android（AGP 9 内置 Kotlin 才可省略）；
//  2. kotlin { jvmToolchain } 从 android {} 内移到顶层（嵌套形态是
//     AGP 9 内置 Kotlin 专属 DSL）。
// 其余任何段（依赖、发布、版本注释）修改时两个文件一起改。

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    `maven-publish`
}

android {
    namespace = "io.legado.app.eink"
    // 35 ＝ 依赖集地板（模块 AAR 元数据 minCompileSdk 跟随本值）。
    // 版本约束、家族调研结论与升档协议的权威注释在模块
    // gradle/libs.versions.toml 头部——改 compileSdk 前先读它。
    compileSdk = 35

    defaultConfig {
        // minSdk 21：可被低 minSdk 宿主直接依赖，库 minSdk 高于宿主会导致 manifest merge 失败
        minSdk = 21
    }

    buildFeatures {
        compose = true
        // MineScreen 排版调试入口按变体裁剪（BuildConfig.DEBUG 编译期常量）
        buildConfig = true
    }

    compileOptions {
        // 同理取兼容下限：产出 Java 17 字节码（JDK 21 工具链 + target 17）
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            consumerProguardFiles("consumer-rules.pro")
        }
    }

    publishing {
        // 宿主经 Maven Local / 远程仓库消费预构建产物（发布 release 单变体）
        singleVariant("release") { withSourcesJar() }
    }
}

// AGP < 9 经 KGP 提供工具链（AGP 9 形态此块嵌在 android {} 内）
kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    // ── 依赖经模块自有版本目录 einkLibs 钉「保守档」 ──
    // （modules/eink/gradle/libs.versions.toml，随模块树复制；宿主
    // settings 加 4 行 versionCatalogs 挂载即可。）单一坐标服务新旧
    // 宿主：模块按最低公共依赖集编译（POM 随保守版发布，消费门槛 =
    // compileSdk ≥35 / Kotlin ≥2.3 / Java 17 / minSdk ≥21）；新栈宿主
    // 自动解析到自己的更高版本（Gradle 取 max，二进制兼容——本仓 app
    // 即此形态）。升档须整体重验四条消费门槛（EINK-PORTING §0）。

    // Compose — Foundation/UI/Runtime only, no Material3 (per E-Ink spec §3, §4)
    implementation(platform(einkLibs.compose.bom))
    implementation(einkLibs.compose.ui)
    implementation(einkLibs.compose.ui.graphics)
    implementation(einkLibs.compose.foundation)
    implementation(einkLibs.compose.runtime)

    // ViewModel + 协程（模块承载全部 E-Ink ViewModel）
    implementation(einkLibs.coroutines.core)
    implementation(einkLibs.coroutines.android)
    // 入口基类 EInkHostActivity 为 AppCompatActivity（宿主 DialogFragment
    // 弹层——段评半屏 WebView 等——的事务宿主，见其 KDoc）；模块自身
    // 仍不组合任何 AppCompat UI
    implementation(einkLibs.appcompat)
    implementation(einkLibs.lifecycle.viewmodel.compose)
    implementation(einkLibs.lifecycle.runtime.compose)
    implementation(einkLibs.activity.compose)

    // 图片加载（EInkAsyncImage / EInkBookCover 封面）— implementation：
    // 契约 CoverEngine 为纯 Kotlin 字节端口（fetchCoverBytes），签名不
    // 暴露任何图片框架类型，宿主编译期零 Coil 可见性、app 依赖清单无需
    // 添加（AAR 形态经 POM runtime 域自动传递）。模块自有 ImageLoader +
    // 封面 Fetcher，网络字节经 CoverEngine 端口回到宿主管线（防盗链/
    // 解密/持久缓存在宿主侧，见 contract/CoverEngine KDoc）
    implementation(einkLibs.coil.compose)
    implementation(einkLibs.coil.network.okhttp)

    // Tooling (debug only)
    debugImplementation(einkLibs.compose.ui.tooling)
    debugImplementation(einkLibs.compose.ui.tooling.preview)

    // Unit tests（纯函数 JVM 测试，无需 Robolectric）
    testImplementation(einkLibs.junit)
    // 并发单元测试（onEachParallel / CacheBookPump 的虚拟时间验证）
    testImplementation(einkLibs.coroutines.test)
}


afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "io.legado.app.eink"
                artifactId = "eink"
                // 版本沿革与历史坐标的注释见 build.gradle.kts（单一权威）
                version = "0.6.1"
            }
        }
    }
}
