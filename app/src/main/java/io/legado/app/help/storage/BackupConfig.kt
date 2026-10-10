package io.legado.app.help.storage

import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.local.preferences.LocalPreferencesKeys
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import splitties.init.appCtx

internal val alwaysIgnoredPreferenceKeys = setOf(
    PreferKey.defaultCover,
    PreferKey.defaultCoverDark,
    PreferKey.backupPath,
    PreferKey.defaultBookTreeUri,
    PreferKey.webDavDeviceName,
    PreferKey.launcherIcon,
    LocalPreferencesKeys.PASSWORD.name,
    LocalPreferencesKeys.MIGRATED_TO_SETTINGS.name,
    // 私密内容解锁的派生凭据。salt + verifier 是"可离线爆破的产物"：密码往往很短，
    // PBKDF2 的迭代次数挡不住 GPU，进云备份等于把口令强度降了一个数量级。
    // 生物信封（含 iv）与开关同样排除：换了设备 Keystore 密钥并不通用，导出只会得到一个
    // "开关亮着、信封解不开"的假状态，不如让用户在新设备上重新开启一次。
    LocalPreferencesKeys.PRIVATE_PASSWORD_SALT.name,
    LocalPreferencesKeys.PRIVATE_PASSWORD_VERIFIER.name,
    LocalPreferencesKeys.PRIVATE_BIOMETRIC_ENABLED.name,
    LocalPreferencesKeys.PRIVATE_BIOMETRIC_ENVELOPE.name,
    LocalPreferencesKeys.PRIVATE_BIOMETRIC_IV.name,
)

/**
 * 设备相关键：值的正确性取决于设备（屏幕几何/硬件能力/性能档位/本机路径）而非用户品味。
 *
 * 与 [alwaysIgnoredPreferenceKeys] 的双向忽略不同，本集合**照常随备份导出**——
 * 备份是唯一的数据捕获点，丢弃了就再也无法恢复；只在**恢复**时按来源设备过滤：
 * 备份来源设备名（[backupOriginDeviceKey]）与本机 [PreferKey.webDavDeviceName] 一致
 * （重装/本机恢复）才应用，跨设备恢复跳过、保留本机值。标记缺失或双方任一为空时
 * 保守按跨设备处理——宁可少恢复，不可把手机备份的 eInkMode=false 灌进墨水屏设备。
 */
internal val deviceLocalPreferenceKeys = setOf(
    // 模式与入口：跨设备同步会互相翻转 UI 模式，eink 设备丢失重入口
    PreferKey.eInkMode,
    PreferKey.labEInkDisplay,
    // 书架几何：eink 书架样式契约直接消费，屏宽/密度差异下合理值天然不同
    PreferKey.bookshelfGridCoverWidth,
    PreferKey.bookshelfLayoutModePortrait,
    PreferKey.bookshelfListCoverWidth,
    // 显示与交互硬件：字体缩放（attach 期生效）、音量键翻页（实体键 vs 调音量）、
    // 前光/背光绝对值（设备档位不可比）
    PreferKey.fontScale,
    PreferKey.volumeKeyPage,
    PreferKey.brightness,
    PreferKey.nightBrightness,
    // 性能与资源：慢速 eink SoC 与旗舰手机的合理档位不同（自 always 迁入——
    // 迁入前重装同机恢复也拿不回来，属过度丢弃）
    PreferKey.threadCount,
    PreferKey.cacheBookThreadCount,
    PreferKey.bitmapCacheSize,
    PreferKey.webServiceWakeLock,
    PreferKey.readAloudWakeLock,
    PreferKey.audioPlayWakeLock,
    // 本机路径/URI/设备盘点：字体私有副本路径（字体文件不随备份走，必悬空）、
    // SAF 文件夹 URI、导入路径历史、系统字体盘点、物理翻页键码
    PreferKey.appFontPath,
    PreferKey.fontFolder,
    PreferKey.importBookPath,
    PreferKey.systemTypefaces,
    PreferKey.prevKeys,
    PreferKey.nextKeys,
)

/**
 * config.xml 内嵌的备份来源设备名（备份时为 [PreferKey.webDavDeviceName] 的快照）。
 * 仅用于恢复侧的同机判定，恢复时读出比对后剥离，**永不作为设置应用**；
 * 不进任何忽略集合（备份侧需要导出它）。旧版本 app 恢复新备份时会把本键当
 * 未知设置写入，无人消费、无害。
 */
internal const val backupOriginDeviceKey = "backupOriginDeviceName"

/**
 * 硬过滤（纯函数，便于 JVM 单测）：备份/恢复都要过的第一道闸。
 * - [alwaysIgnoredPreferenceKeys]：双向忽略，备份不导出、恢复不应用；
 * - [deviceLocalPreferenceKeys]：备份照常导出；仅跨设备恢复（[restoreFromSameDevice]
 *   = false）时跳过。
 */
internal fun isKeyAllowedByHardPolicy(
    key: String,
    isBackup: Boolean,
    restoreFromSameDevice: Boolean = true,
): Boolean {
    if (key in alwaysIgnoredPreferenceKeys) return false
    if (!isBackup && !restoreFromSameDevice && key in deviceLocalPreferenceKeys) return false
    return true
}

/**
 * 备份配置
 */
@Suppress("ConstPropertyName")
object BackupConfig {

    private val ignoreConfigPath = FileUtils.getPath(appCtx.filesDir, "restoreIgnore.json")
    val ignoreConfig: HashMap<String, Boolean> by lazy {
        val file = FileUtils.createFileIfNotExist(ignoreConfigPath)
        val json = file.readText()
        GSON.fromJsonObject<HashMap<String, Boolean>>(json).getOrNull() ?: hashMapOf()
    }

    private val backupIgnoreConfigPath = FileUtils.getPath(appCtx.filesDir, "backupIgnore.json")
    val backupIgnoreConfig: HashMap<String, Boolean> by lazy {
        val file = FileUtils.createFileIfNotExist(backupIgnoreConfigPath)
        val json = file.readText()
        GSON.fromJsonObject<HashMap<String, Boolean>>(json).getOrNull() ?: hashMapOf()
    }

    private val dbIgnoreConfigPath = FileUtils.getPath(appCtx.filesDir, "dbIgnore.json")
    val dbIgnoreConfig: HashMap<String, Boolean> by lazy {
        val file = FileUtils.createFileIfNotExist(dbIgnoreConfigPath)
        val json = file.readText()
        GSON.fromJsonObject<HashMap<String, Boolean>>(json).getOrNull() ?: hashMapOf()
    }

    private val backupDbIgnoreConfigPath = FileUtils.getPath(appCtx.filesDir, "backupDbIgnore.json")
    val backupDbIgnoreConfig: HashMap<String, Boolean> by lazy {
        val file = FileUtils.createFileIfNotExist(backupDbIgnoreConfigPath)
        val json = file.readText()
        GSON.fromJsonObject<HashMap<String, Boolean>>(json).getOrNull() ?: hashMapOf()
    }

    private const val readConfigKey = "readConfig"
    private const val themeConfigKey = "themeConfig"
    private const val coverConfigKey = "coverConfig"
    private const val localBookKey = "localBook"
    private const val mangaKey = "mangaConfig"

    //数据库忽略key
    private const val dbKeyBookmark = "bookmark"
    private const val dbKeyBookGroup = "bookGroup"
    private const val dbKeyBookSource = "bookSource"
    private const val dbKeyRssSource = "rssSource"
    private const val dbKeyRssStar = "rssStar"
    private const val dbKeyReplaceRule = "replaceRule"
    private const val dbKeyReadRecord = "readRecord"
    private const val dbKeySearchHistory = "searchHistory"
    private const val dbKeySourceSub = "sourceSub"
    private const val dbKeyTxtTocRule = "txtTocRule"
    private const val dbKeyHttpTts = "httpTTS"
    private const val dbKeyKeyboardAssists = "keyboardAssists"
    private const val dbKeyDictRule = "dictRule"
    private const val dbKeyHomepageModules = "homepageModules"
    private const val dbKeyHomepageCustomSets = "homepageCustomSets"
    private const val dbKeyHighlightRule = "highlightRule"
    private const val dbKeyHighlightTagRule = "highlightTagRule"
    private const val dbKeyTagGroupRule = "tagGroupRule"
    private const val dbKeyServer = "server"

    val dbIgnoreKeys = arrayOf(
        dbKeyBookmark, dbKeyBookGroup, dbKeyBookSource, dbKeyRssSource,
        dbKeyRssStar, dbKeyReplaceRule, dbKeyReadRecord, dbKeySearchHistory,
        dbKeySourceSub, dbKeyTxtTocRule, dbKeyHttpTts, dbKeyKeyboardAssists,
        dbKeyDictRule, dbKeyHomepageModules, dbKeyHomepageCustomSets,
        dbKeyHighlightRule, dbKeyHighlightTagRule, dbKeyTagGroupRule, dbKeyServer
    )

    val dbIgnoreTitle = arrayOf(
        appCtx.getString(R.string.bookmark),
        appCtx.getString(R.string.book_group),
        appCtx.getString(R.string.book_source),
        appCtx.getString(R.string.rss_source),
        appCtx.getString(R.string.rss_star),
        appCtx.getString(R.string.replace_rule),
        appCtx.getString(R.string.read_record),
        appCtx.getString(R.string.search_history),
        appCtx.getString(R.string.source_sub),
        appCtx.getString(R.string.txt_toc_rule),
        appCtx.getString(R.string.http_tts),
        appCtx.getString(R.string.keyboard_assists),
        appCtx.getString(R.string.dict_rule),
        appCtx.getString(R.string.homepage_modules),
        appCtx.getString(R.string.homepage_custom_sets),
        appCtx.getString(R.string.highlight_rule_config),
        appCtx.getString(R.string.highlight_tag_config),
        appCtx.getString(R.string.tag_group_rules),
        appCtx.getString(R.string.server_config)
    )

    val backupDbIgnoreKeys = dbIgnoreKeys
    val backupDbIgnoreTitle = dbIgnoreTitle

    fun dbIsNotIgnored(key: String, isBackup: Boolean = false): Boolean {
        val config = if (isBackup) backupDbIgnoreConfig else dbIgnoreConfig
        return config[key] != true
    }

    fun saveDbIgnoreConfig() {
        val json = GSON.toJson(dbIgnoreConfig)
        FileUtils.createFileIfNotExist(dbIgnoreConfigPath).writeText(json)
    }

    fun saveBackupDbIgnoreConfig() {
        val json = GSON.toJson(backupDbIgnoreConfig)
        FileUtils.createFileIfNotExist(backupDbIgnoreConfigPath).writeText(json)
    }

    //配置忽略key
    val ignoreKeys = arrayOf(
        readConfigKey,
        PreferKey.themeMode,
        themeConfigKey,
        coverConfigKey,
        PreferKey.bookshelfLayout,
        PreferKey.showRss,
        PreferKey.threadCount,
        localBookKey,
        mangaKey
    )

    //配置忽略标题
    val ignoreTitle = arrayOf(
        appCtx.getString(R.string.read_config),
        appCtx.getString(R.string.theme_mode),
        appCtx.getString(R.string.theme_config),
        appCtx.getString(R.string.cover_config),
        appCtx.getString(R.string.bookshelf_layout),
        appCtx.getString(R.string.show_rss),
        appCtx.getString(R.string.thread_count),
        appCtx.getString(R.string.local_book),
        appCtx.getString(R.string.manga_config)
    )

    //备份忽略key
    val backupIgnoreKeys = arrayOf(
        readConfigKey,
        PreferKey.themeMode,
        themeConfigKey,
        coverConfigKey,
        PreferKey.bookshelfLayout,
        PreferKey.showRss,
        PreferKey.threadCount,
        localBookKey,
        mangaKey
    )

    //备份忽略标题
    val backupIgnoreTitle = arrayOf(
        appCtx.getString(R.string.read_config),
        appCtx.getString(R.string.theme_mode),
        appCtx.getString(R.string.theme_config),
        appCtx.getString(R.string.cover_config),
        appCtx.getString(R.string.bookshelf_layout),
        appCtx.getString(R.string.show_rss),
        appCtx.getString(R.string.thread_count),
        appCtx.getString(R.string.local_book),
        appCtx.getString(R.string.manga_config)
    )

    //阅读配置
    private val readPrefKeys = arrayOf(
        PreferKey.readStyleSelect,
        PreferKey.comicStyleSelect,
        PreferKey.shareLayout,
        PreferKey.hideStatusBar,
        PreferKey.hideNavigationBar,
        PreferKey.autoReadSpeed,
        PreferKey.readBrightnessMode,
        PreferKey.readBrightnessControlPosition,
        PreferKey.clickActionTL,
        PreferKey.clickActionTC,
        PreferKey.clickActionTR,
        PreferKey.clickActionML,
        PreferKey.clickActionMC,
        PreferKey.clickActionMR,
        PreferKey.clickActionBL,
        PreferKey.clickActionBC,
        PreferKey.clickActionBR,
        // 阅读进度条
        PreferKey.readBarStyle,
        PreferKey.readBarStyleFollowPage,
        // 阅读菜单
        PreferKey.readMenuBgColor,
        PreferKey.readMenuAccentColor,
        PreferKey.readMenuContainerColor,
        PreferKey.readMenuBgColorNight,
        PreferKey.readMenuAccentColorNight,
        PreferKey.readMenuContainerColorNight,
        PreferKey.readMenuTextColor,
        PreferKey.readMenuTextColorNight,
        PreferKey.readMenuColorMode,
        PreferKey.readMenuIconShowText,
        PreferKey.readMenuIconStyle,
        PreferKey.readMenuIconItemsPerRow,
        PreferKey.readMenuIconRowCount,
        PreferKey.readMenuBottomCornerRadius,
        PreferKey.readMenuFloatingBottomBar,
        PreferKey.readMenuTopBarBlurMode,
        PreferKey.readMenuBottomBarBlurMode,
        PreferKey.readMenuTopBarLiquidGlassButtons,
        PreferKey.readMenuTopBarMergeButtons,
        PreferKey.readMenuTopBarTitleCapsule,
        PreferKey.readMenuBottomBarLiquidGlassButtons,
        PreferKey.readMenuFloatingIconLiquidGlass,
        PreferKey.readMenuTopBarBlurStyle,
        PreferKey.readMenuBottomBarBlurStyle,
        PreferKey.readMenuBlurRadius,
        PreferKey.readMenuBlurAlpha,
        PreferKey.readMenuBlurColor,
        PreferKey.readMenuBlurColorNight,
        PreferKey.readMenuPaletteStyle,
        PreferKey.readMenuLensRadius,
        PreferKey.readMenuBorderWidth,
        PreferKey.readMenuBorderColor,
        PreferKey.readMenuBorderColorNight,
        PreferKey.readMenuCustomIcons,
        // 标题栏
        PreferKey.titleBarIconStyle,
        PreferKey.titleBarCustomIcons,
        PreferKey.titleBarIconPosition,
        PreferKey.showTitleBarIcons,
        PreferKey.showMenuIcon,
        PreferKey.titleBarMode,
        PreferKey.shouldShowExpandButton,
    )

    private val themePrefKeys = arrayOf(
        PreferKey.cPrimary,
        PreferKey.cNPrimary,
        PreferKey.bgImage,
        PreferKey.bgImageBlurring,
        PreferKey.bgImageN,
        PreferKey.bgImageNBlurring,
        PreferKey.themeColor,
        PreferKey.secondaryThemeColor,
        PreferKey.themeColorNight,
        PreferKey.secondaryThemeColorNight,
        PreferKey.paletteStyle,
        PreferKey.materialVersion,
        PreferKey.composeEngine,
        PreferKey.customContrast,
        PreferKey.customMode,
        PreferKey.useMiuixMonet,
        PreferKey.containerOpacity,
        PreferKey.topBarOpacity,
        PreferKey.bottomBarOpacity,
        PreferKey.enableBlur,
        PreferKey.enableProgressiveBlur,
        PreferKey.topBarBlurRadius,
        PreferKey.bottomBarBlurRadius,
        PreferKey.topBarBlurAlpha,
        PreferKey.bottomBarBlurAlpha,
        PreferKey.bottomBarLensRadius,
        PreferKey.useFlexibleTopAppBar,
        PreferKey.topBarButtonStyle,
        PreferKey.mergeTopBarActions,
        PreferKey.bookInfoFollowCoverColor,
        PreferKey.bookInfoBackgroundBlur,
        PreferKey.bookInfoNetworkCoverBackground,
        PreferKey.bookInfoDefaultCoverBackground,
        PreferKey.cBackground,
        PreferKey.cBBackground,
        PreferKey.cNBackground,
        PreferKey.cNBBackground,
        PreferKey.enableDeepPersonalization,
        PreferKey.primaryTextColor,
        PreferKey.secondaryTextColor,
        PreferKey.themeBackgroundColor,
        PreferKey.labelContainerColor,
        PreferKey.primaryTextColorNight,
        PreferKey.secondaryTextColorNight,
        PreferKey.themeBackgroundColorNight,
        PreferKey.labelContainerColorNight,
        PreferKey.eyeProtectionEnabled,
        PreferKey.colorTemperature,
        PreferKey.eyeProtectionAutoNight,
        PreferKey.eyeProtectionSchedule,
        PreferKey.eyeProtectionStartTime,
        PreferKey.eyeProtectionEndTime,
    )

    private val bookshelfPrefKeys = arrayOf(
        PreferKey.bookshelfLayout,
        PreferKey.bookshelfLayoutModePortrait,
        PreferKey.bookshelfLayoutModeLandscape,
        PreferKey.bookshelfLayoutCompact,
        PreferKey.bookshelfListCoverCenter,
        PreferKey.bookshelfListIntroBelowContent,
        PreferKey.bookshelfShowDivider,
        PreferKey.bookshelfGridLayout,
        PreferKey.bookshelfSort,
        PreferKey.bookshelfSortOrder,
        PreferKey.bookshelfLayoutGridLandscape,
        PreferKey.bookshelfLayoutGridPortrait,
        PreferKey.bookshelfLayoutListLandscape,
        PreferKey.bookshelfLayoutListPortrait,
        PreferKey.bookshelfFolderLayoutModePortrait,
        PreferKey.bookshelfFolderLayoutModeLandscape,
        PreferKey.bookshelfFolderLayoutGridPortrait,
        PreferKey.bookshelfFolderLayoutGridLandscape,
        PreferKey.bookshelfFolderLayoutListPortrait,
        PreferKey.bookshelfFolderLayoutListLandscape,
        PreferKey.bookshelfTitleSmallFont,
        PreferKey.bookshelfTitleCenter,
        PreferKey.bookshelfTitleMaxLines,
        PreferKey.bookshelfCoverShadow,
        PreferKey.bookshelfSearchActionDirectToSearch,
        PreferKey.bookshelfCardColor,
        PreferKey.bookshelfCardColorDark,
        PreferKey.bookshelfGroupListStyle,
        PreferKey.bookshelfGroupCoverCount,
        PreferKey.bookshelfListCoverWidth,
        PreferKey.bookshelfGridCoverWidth,
        PreferKey.bookshelfRefreshingLimit,
        PreferKey.bookshelfShowIntro,
        PreferKey.bookshelfShowTag,
        PreferKey.bookshelfShowLatestChapter,
        PreferKey.bookshelfIntroMaxLines
    )

    private val mangaPrefKeys = arrayOf(
        PreferKey.showMangaUi,
        PreferKey.mangaScrollMode,
        PreferKey.webtoonSidePaddingDp,
        PreferKey.mangaPreDownloadNum,
        PreferKey.mangaChapterPrefetchCount,
        PreferKey.mangaAutoOfflineCache,
        PreferKey.mangaAutoPageSpeed,
        PreferKey.mangaFooterConfig,
        PreferKey.disableClickScroll,
        PreferKey.hideMangaTitle,
        PreferKey.mangaColorFilter,
        PreferKey.enableMangaEInk,
        PreferKey.mangaEInkThreshold,
        PreferKey.enableMangaGray,
        PreferKey.doublePageHorizontal,
        PreferKey.mouseWheelPage,
        PreferKey.disableMangaScale,
        PreferKey.disableMangaScrollAnimation,
        PreferKey.disableMangaCrossFade,
        PreferKey.mangaVolumeKeyPage,
        PreferKey.reverseVolumeKeyPage,
        PreferKey.mangaLongClick,
        PreferKey.mangaBackground,
        PreferKey.mangaClickActionTL,
        PreferKey.mangaClickActionTC,
        PreferKey.mangaClickActionTR,
        PreferKey.mangaClickActionML,
        PreferKey.mangaClickActionMC,
        PreferKey.mangaClickActionMR,
        PreferKey.mangaClickActionBL,
        PreferKey.mangaClickActionBC,
        PreferKey.mangaClickActionBR
    )

    private val coverPrefKeys = arrayOf(
        PreferKey.useDefaultCover,
        PreferKey.loadCoverOnlyWifi,
        PreferKey.coverShowName,
        PreferKey.coverShowAuthor,
        PreferKey.coverShowNameN,
        PreferKey.coverShowAuthorN,
        PreferKey.coverShowShadow,
        PreferKey.coverShowStroke,
        PreferKey.coverTextColor,
        PreferKey.coverTextColorN,
        PreferKey.coverShadowColor,
        PreferKey.coverShadowColorN,
        PreferKey.coverDefaultColor,
        PreferKey.coverInfoOrientation
    )

    /**
     * @param restoreFromSameDevice 备份来源设备名与本机一致（仅恢复侧有意义；默认 true
     *        保持既有调用语义——设备相关键照常应用，见 [deviceLocalPreferenceKeys]）
     */
    fun keyIsNotIgnore(
        key: String,
        isBackup: Boolean = false,
        restoreFromSameDevice: Boolean = true,
    ): Boolean {
        if (!isKeyAllowedByHardPolicy(key, isBackup, restoreFromSameDevice)) return false
        if (isBackup) {
            return when {
                backupIgnoreReadConfig && readPrefKeys.contains(key) -> false
                backupIgnoreThemeConfig && themePrefKeys.contains(key) -> false
                backupIgnoreCoverConfig && coverPrefKeys.contains(key) -> false
                backupIgnoreBookshelfLayout && bookshelfPrefKeys.contains(key) -> false
                backupIgnoreManga && mangaPrefKeys.contains(key) -> false
                PreferKey.themeMode == key && backupIgnoreThemeMode -> false
                PreferKey.showRss == key && backupIgnoreShowRss -> false
                PreferKey.threadCount == key && backupIgnoreThreadCount -> false
                else -> true
            }
        }
        return when {
            ignoreReadConfig && readPrefKeys.contains(key) -> false
            ignoreThemeConfig && themePrefKeys.contains(key) -> false
            ignoreCoverConfig && coverPrefKeys.contains(key) -> false
            ignoreBookshelfLayout && bookshelfPrefKeys.contains(key) -> false
            ignoreManga && mangaPrefKeys.contains(key) -> false
            PreferKey.themeMode == key && ignoreThemeMode -> false
            PreferKey.showRss == key && ignoreShowRss -> false
            PreferKey.threadCount == key && ignoreThreadCount -> false
            else -> true
        }
    }

    val ignoreReadConfig: Boolean
        get() = ignoreConfig[readConfigKey] == true
    val ignoreThemeMode: Boolean
        get() = ignoreConfig[PreferKey.themeMode] == true
    val ignoreThemeConfig: Boolean
        get() = ignoreConfig[themeConfigKey] == true
    val ignoreCoverConfig: Boolean
        get() = ignoreConfig[coverConfigKey] == true
    val ignoreBookshelfLayout: Boolean
        get() = ignoreConfig[PreferKey.bookshelfLayout] == true
    val ignoreShowRss: Boolean
        get() = ignoreConfig[PreferKey.showRss] == true
    val ignoreThreadCount: Boolean
        get() = ignoreConfig[PreferKey.threadCount] == true
    val ignoreLocalBook: Boolean
        get() = ignoreConfig[localBookKey] == true
    val ignoreManga: Boolean
        get() = ignoreConfig[mangaKey] == true

    val backupIgnoreReadConfig: Boolean
        get() = backupIgnoreConfig[readConfigKey] == true
    val backupIgnoreThemeMode: Boolean
        get() = backupIgnoreConfig[PreferKey.themeMode] == true
    val backupIgnoreThemeConfig: Boolean
        get() = backupIgnoreConfig[themeConfigKey] == true
    val backupIgnoreCoverConfig: Boolean
        get() = backupIgnoreConfig[coverConfigKey] == true
    val backupIgnoreBookshelfLayout: Boolean
        get() = backupIgnoreConfig[PreferKey.bookshelfLayout] == true
    val backupIgnoreShowRss: Boolean
        get() = backupIgnoreConfig[PreferKey.showRss] == true
    val backupIgnoreThreadCount: Boolean
        get() = backupIgnoreConfig[PreferKey.threadCount] == true
    val backupIgnoreLocalBook: Boolean
        get() = backupIgnoreConfig[localBookKey] == true
    val backupIgnoreManga: Boolean
        get() = backupIgnoreConfig[mangaKey] == true

    fun saveIgnoreConfig() {
        val json = GSON.toJson(ignoreConfig)
        FileUtils.createFileIfNotExist(ignoreConfigPath).writeText(json)
    }

    fun saveBackupIgnoreConfig() {
        val json = GSON.toJson(backupIgnoreConfig)
        FileUtils.createFileIfNotExist(backupIgnoreConfigPath).writeText(json)
    }

}
