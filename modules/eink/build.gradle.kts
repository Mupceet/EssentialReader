plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    `maven-publish`
}

android {
    namespace = "io.legado.app.eink"
    // 库取兼容下限：37 宿主向上消费无碍，36 宿主（AGP8.x）也可直接依赖
    //（compileSdk 37 构建的 AAR 元数据 minCompileSdk=37 会被 AGP 拒绝）
    compileSdk = 36

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
    // lifecycle 钉 2.9.4：2.11 的 AAR 元数据 minCompileSdk=37，会把模块
    // 的兼容下限抬到 37（AGP8/compileSdk36 宿主被拒）。宿主 app 自身
    // 用更高版本时 Gradle 解析取高，二进制兼容
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
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
}


afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "io.legado.app.eink"
                artifactId = "eink"
                // 0.1.0 = 旧栈（AGP8.13/K2.3）构建、develop 宿主在用；
                // 0.2.0 = 本仓主栈（AGP9/K2.4/Java21）构建，跨栈消费核对 §0
                version = "0.2.0"
            }
        }
    }
}
