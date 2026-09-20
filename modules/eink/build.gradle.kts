plugins {
    alias(libs.plugins.android.library)
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
    // ── 依赖经模块自有版本目录 einkLibs 钉「保守档」 ──
    // （modules/eink/gradle/libs.versions.toml，随模块树复制；根 settings
    // 4 行 versionCatalogs 挂载。）单一坐标服务新旧宿主：保守版进 POM、
    // 新栈宿主解析自动取 max。**版本的语义、家族调研结论与升档协议的
    // 权威注释在该 toml 头部——升级任何依赖前先读它**（误升只会抬高
    // 旧宿主门槛，对新栈宿主零收益）。

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
                //         宿主子类须随 AAR 重编译）；
                // 0.5.0 = 0.4.0 设版后陆续并入的特性整体发布：云端进度同步
                //         触发链路（ReaderSyncTrigger / applyCloudProgress /
                //         onCloudProgressNewer + 恢复弹框「以本设备为准」）
                //         + 可选端口三件（ReaderSelectionEngine / MarksEngine /
                //         BookshelfGroupEngine）与配套 UI + AppUpdateEngine
                //         可选更新端口（启动自动检查 + gh-proxy 加速 + 弹框
                //         内嵌下载进度）+ 阅读字体选择两级交互浮层 +
                //         einkReaderTapZones / einkReaderPullDownBookmark 落
                //         宿主专属 prefs 文件 + 移除保持屏幕常亮（仅自动翻页
                //         场景常亮）。依赖集抬至 Compose UI 1.12 / Coil 3.6.2，
                //         宿主专属 prefs 文件 + 移除保持屏幕常亮（仅自动翻页
                //         场景常亮）。依赖集抬至 Compose UI 1.12 / Coil 3.6.2，
                //         AAR 消费方 compileSdk 须 ≥ 37；
                // 0.6.0 = 契约能力粒度四件套（宿主侧提案落地）：
                //         ReaderStyleParam 新增 Locked（仅默认值可用 → UI 置灰
                //         锁定态）；设置弹层按目录守卫（参数未声明整行隐藏，
                //         不再出现 0..0 死滑条）；ReaderSelectionEngine 增
                //         supportsMarkings/supportsPageBookmark、MarksEngine 增
                //         supportsBookmarks/supportsMarkings（默认均 true，旧
                //         宿主零改动）——目录页 Tab、下拉书签/顶栏书签钮/
                //         页角标/长按选择按能力显隐；GlobalSettings 增
                //         supportsReviewBubbles（false = 隐藏段评开关）；
    // 0.6.1 = ReaderStyleParam 新增 Presets（协议预设档集、无
    //         自定义滑条——字重行 allowCustom = false 时隐藏
    //         「自定义」按钮与滑条）；
    // 0.7.0 = 契约清理轮（待发布）：PendingJumpConfirm 迁出 contract 包
    //         （模块内部类型，宿主零引用）；移除 BookshelfEngine.
    //         setCacheWorkingState（模块不再调用，契约面不留废弃成员）；
    //         CoverEngine 改纯 Kotlin 字节端口 fetchCoverBytes——签名不再
    //         暴露 Coil 类型，Coil 降为 implementation（宿主零 Coil
    //         可见性，模块自有 ImageLoader + 封面 Fetcher）；另含
    //         TITLE_WEIGHT Presets 分支补齐与信息弹层逐行目录守卫；
    //         **依赖集永久钉保守档**（BOM 2025.11.00 / Foundation 1.9.4 /
    //         Coil 3.0.4 / lifecycle-compose 2.8.7 / activity 1.8.2 /
    //         appcompat 1.7.0，compileSdk 35＝依赖集 AAR 元数据地板，经
    //         模块自有版本目录 einkLibs 声明、脱离宿主根目录）——单一
    //         坐标服务新旧宿主，消费门槛 AGP ≥ 8.6.0 / K2.3 / Java 17 /
    //         minSdk 21；
    // 0.7.1 = ReaderSelectionEngine 契约 v2（待发布）：
    //         saveMarking 拆为 createMarking（仅新选区）+ updateMarkingNote
    //         (id, note)（唯一状态转换路径，锚点不变，结构性杜绝跨宿主把
    //         划线⇄想法转换实现成重复添加）；thought 字段删除（类型恒由
    //         note 派生）；togglePageBookmark 增 ReaderPageBookmarkContent
    //         载荷（书签显示字段由模块携带，宿主不自定显示语义）；
    //         ReaderPageLine 增 paragraphBreaksAfter（书签摘录按段落边界
    //         拼装，与宿主 page.text 口径同构——宿主映射器实现义务）；
    //         ReaderSelectionEngine 并入 MarksEngine（能力位按特性全有全无：
    //         supportsMarkings/supportsBookmarks 各管阅读内 + 目录页两表面，
    //         两同名不同义的能力位与 KDoc 免责声明消亡；注册表撤
    //         selectionEngine 槽）；
    // 孪生坐标 0.5.0-oldstack / 0.6.1-min21（已退役，保留为历史坐标）：
    // 统一前为分开服务旧栈与 minSdk 21 宿主的过渡产物，0.7.0 起主坐标
    // 即覆盖两类消费形态，不再发布孪生；
                version = "0.6.1"
            }
        }
    }
}
