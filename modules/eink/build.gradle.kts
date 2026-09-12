plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    `maven-publish`
}

android {
    namespace = "io.legado.app.eink"
    // 跟随宿主 compileSdk 37。曾刻意压 36 作兼容下限，2026-09 随宿主
    // 依赖集升级（Compose UI 1.12 / Coil 3.6.2 的 AAR 元数据要求
    // minCompileSdk=37）下限被打穿，回到与宿主同轨。
    compileSdk = 37

    defaultConfig {
        // minSdk 21：可被低 minSdk 宿主直接依赖，库 minSdk 高于宿主会导致 manifest merge 失败
        minSdk = 21
    }

    kotlin {
        jvmToolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
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

dependencies {
    // Compose — Foundation/UI/Runtime only, no Material3 (per E-Ink spec §3, §4)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation("androidx.compose.ui:ui-graphics")
    implementation(libs.androidx.compose.foundation)
    implementation("androidx.compose.runtime:runtime")

    // ViewModel + 协程（模块承载全部 E-Ink ViewModel）
    implementation(libs.bundles.coroutines)
    // 入口基类 EInkHostActivity 为 AppCompatActivity（宿主 DialogFragment
    // 弹层——段评半屏 WebView 等——的事务宿主，见其 KDoc）；模块自身
    // 仍不组合任何 AppCompat UI
    implementation(libs.appcompat.appcompat)
    // 与宿主同轨（catalog 2.11.0）。曾钉 2.9.4 以保 compileSdk 36 兼容
    // 下限，下限回到 37 后钉版失去意义
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.activity.compose)

    // 图片加载（EInkAsyncImage / EInkBookCover 封面）— api 传递：
    // 契约 CoverEngine 签名暴露 Coil 类型，宿主 bridge 需编译期可见；
    // coil-network-okhttp 使 AAR 消费方开箱具备网络封面能力
    // （Coil 3 的网络抓取器经 ServiceLoader 自动注册，缺它则 http 封面
    // 全部失败——曾致 develop 宿主封面不显示）。防盗链/书源请求头仍由
    // 宿主经 CoverEngine 注入（见 contract/CoverEngine KDoc）
    api(libs.coil.compose)
    api(libs.coil.network.okhttp)

    // Tooling (debug only)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)

    // Unit tests（纯函数 JVM 测试，无需 Robolectric）
    testImplementation(libs.junit)
    // 并发单元测试（onEachParallel / CacheBookPump 的虚拟时间验证）
    testImplementation(libs.kotlinx.coroutines.test)
}


afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "io.legado.app.eink"
                artifactId = "eink"
                // 0.1.0 = 旧栈（AGP8.13/K2.3）构建、develop 宿主在用；
                // 0.2.0 = 本仓主栈（AGP9/K2.4/Java21）构建，跨栈消费核对 §0；
                // 0.3.0 = 契约破坏性变更（移除 setTextBold/textBold 端口）；
                // 0.4.0 = 新增可选宿主 UI 字体钩子（EInkTheme.fontFamily /
                //         EInkHostActivity.uiFontFamily）+ 系统深浅色改
                //         onConfigurationChanged 推进 State（修复使用中切换
                //         不生效）+ 排版阶梯重排与界面样式对位（body 18/16/14
                //         统一 Normal，title/label 抬档；列表行高计入行距
                //         内边距并随阶梯自适应）+ 段评查看：图片槽位带
                //         source/action 交互元数据、ReaderEngine 新增
                //         dispatchImageAction（默认无操作，旧宿主点按无响应）
                //         且入口基类改 AppCompatActivity（源码级基类变更，
                //         宿主子类须随 AAR 重编译）
                version = "0.4.0"
            }
        }
    }
}
