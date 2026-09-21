package io.legado.app.eink.contract

/**
 * 全局设置视图：模块全部设置项的唯一出入口。
 *
 * 读写路径：
 * ```text
 * 模块 UI（「我的」页开关 / 阅读菜单开关 / 字体设置页）
 *        │ 写（var setter，按各键档位语义）
 *        ▼
 * 宿主存储（设置网关 / 历史键 / 自有 DataStore——宿主决定）
 *        │ 读（VM init / 按键时 / 入口 attach 期 / 组合内快照）
 *        ▼
 * 模块消费方（预缓存泵门槛、音量键判定、入口 Context 包装、
 *            图片画笔抗锯齿、书架自动刷新触发、阅读页状态栏收起、
 *            阅读页点击分区分发…）
 * ```
 *
 * 收录 E-Ink VM 编排与「我的」页/阅读菜单真正读写的键——含转发宿主
 * 设置的键（与完整模式共享同一存储）与 E-InK 自有偏好（如
 * [readerTapZonesEncoding]、[pullDownBookmark]）。嵌入式宿主与完整模式共享
 * 存储（自有偏好键落宿主侧自有 prefs 文件），插件宿主可用自有 DataStore。
 *
 * 写入语义分档（宿主实现须遵守，模块 UI 按档位做乐观更新）：
 *  - fire-and-forget：异步落盘，写后立即读 getter **不保证**可见新值；
 *  - 快照状态：读取由宿主 Compose 快照状态背书，写入同步可见；
 *  - attach 期：写入后需 recreate 入口 Activity 才生效。
 * 各键档位见其 KDoc。
 */
interface GlobalSettings {
    /**
     * 线程数：目录刷新并发上限（书架页）与换源搜索并发信号量的共同
     * 上限。纯性能调优项，模块在 VM init 与操作时读取。
     */
    val threadCount: Int

    /**
     * 进入书架是否自动刷新一次书籍目录。
     *
     * 可写（「我的」页开关）：fire-and-forget 写入；生效时机为下次
     * 启动（书架 VM 初始化时读取）。
     */
    var autoRefreshBook: Boolean

    /**
     * 启动是否直达最近阅读（以 [书架, 阅读页] 初始栈进入）。
     *
     * 可写（「我的」页开关）：fire-and-forget 写入；生效时机为下次
     * 启动（入口模板 onCreate 读取）。
     */
    var defaultToRead: Boolean

    /**
     * 音量键翻页。
     *
     * 可写（阅读界面「其它设置」面板开关）：fire-and-forget 写入；实时
     * 生效——阅读页按键处理器每次按键时读取，无一致性窗口。
     */
    var volumeKeyPage: Boolean

    /**
     * 总是使用默认封面（不加载网络封面）。
     *
     * 可写（「我的」页开关）：**快照状态**档——读取由宿主 Compose 快照
     * 状态背书，组合内读取订阅变化，切换后开关行与书架/详情封面立即
     * 重组。宿主在入口装配时与设置存储对齐一次（防跨模式往返后的
     * 陈旧值）。
     */
    var useDefaultCover: Boolean

    /**
     * 阅读区竖直下拉添加书签（E-InK 自有界面偏好，完整模式无对应设置）。
     *
     * 可写（其它设置面板开关，默认关）：关闭时竖直下拉不认领书签
     * （手势仍被统一仲裁吞并以压掉点按，不产生动作）；读取在阅读 VM
     * 构造时一次，写入即时生效（UiState 同步）且同步落盘。嵌入式宿主
     * 实现以自有键 `einkReaderPullDownBookmark` 存宿主侧自有 prefs 文件。
     */
    var pullDownBookmark: Boolean

    /**
     * 阅读页隐藏系统状态栏（转发宿主阅读设置，与完整模式「隐藏状态栏」
     * 同键共享存储）。
     *
     * 语义对齐完整模式：开启后阅读页收起状态栏——沉浸条件为「开关开启
     * && 顶部操作条收起」，操作条展开期状态栏强制显示（操作条图标不可
     * 落入状态栏区域不可操作）；页眉（时间/电量）在状态栏收起时接管
     * 顶部信息——headerMode 默认档的页眉可见性即跟随本开关（见
     * ReaderEngine.headerFooterVisibility 的默认分支）。
     *
     * 可写（阅读菜单开关）：fire-and-forget 写入；实时生效——切换后
     * 模块立即重算页眉可见性并收起/恢复状态栏。若宿主写入为纯异步
     * 可见（写后读 getter 拿到旧值），页眉最迟随下一次翻页的
     * 内容刷新对齐。
     */
    var hideStatusBar: Boolean

    /**
     * 段评气泡参与排版（转发宿主阅读设置键 showReviewBubbles，与完整模式
     * 共享同一存储；默认 true）。
     *
     * false 时带 click 动作脚本的图片（段评气泡）不参与排版：不绘制、
     * 不可点、不解析图片尺寸。切换需重排——调用方写后显式触发重排。
     * 嵌入式宿主经设置网关 pending-overlay 内存同步可见（主线程写后
     * 重排即可读到新值）；若宿主写入为纯异步可见（写后读 getter 拿到
     * 旧值），排版最迟随下一次翻页的内容刷新对齐。
     *
     * 可写（阅读菜单开关）：fire-and-forget 写入 + 显式重排触发。
     *
     * 能力声明（0.6.0 起）：[supportsReviewBubbles] = false 的宿主上，
     * 阅读菜单的「显示段评气泡」开关整体隐藏（宿主无段评能力时不留
     * 死开关；本键的读写不再被触达，实现可为固定值）。
     */
    var showReviewBubbles: Boolean

    /** 段评气泡能力声明：false = 宿主不支持，模块隐藏相关开关与元素。 */
    val supportsReviewBubbles: Boolean get() = true

    /**
     * 阅读页点击分区的存储编码（E-Ink 自有偏好，完整模式无对应设置）。
     *
     * 端口面只承载透明字符串：9 位数字编码（行主序，每格 0/1/2，中心位
     * 恒 0），null = 未存储。九宫格几何、中心格固定菜单、脏数据回落等
     * 语义全部在模块侧 ReaderTapZoneGrid（feature/reader，非契约类型），
     * 宿主按整键原样存取即可，不解读、不校验内容。
     *
     * **不转发**完整模式 clickAction* 键：完整模式单格可配 15 种动作，
     * 本键值域只有三种，转发会让两侧配置互相覆盖（eink 蒙层三值写回会
     * 静默清掉完整模式的 下一章/书签 等配置）。嵌入式宿主以历史风格
     * 自有键 `einkReaderTapZones` 落专属 prefs 文件（默认 prefs 文件是
     * DataStore 迁移源，不可作存储位，见 EINK-PORTING.md）。
     *
     * 可写（点击区域蒙层退出时）：fire-and-forget 落盘 + 调用方同步更新
     * UiState 快照——蒙层退出即生效，纯手势语义不触发重排。
     *
     * 默认实现（旧宿主）getter 恒返回 null、写入丢弃：模块回落默认
     * 分区，行为不回退，新设置不可持久化。
     */
    var readerTapZonesEncoding: String?
        get() = null
        set(value) {}

    /**
     * 最近选中的文件字体（E-Ink 自有界面偏好，完整模式无对应设置）。
     *
     * 端口面只承载透明字符串：换行符分隔的 path 列表编码，当前语义仅
     * 保留最近一个（列表形态留扩展余地），空串 = 无历史。path 即字体
     * 枚举项的内容 URI，宿主按整键原样存取、不解读不校验；显示时模块
     * 与当前文件夹枚举求交集——换文件夹后旧历史项自然隐藏但保留，换回
     * 即恢复。嵌入式宿主以历史风格自有键 `einkRecentFontPaths` 落专属
     * prefs 文件（同 readerTapZones 的存储位约定）。
     *
     * 可写（字体配置弹层选中文件字体时）：fire-and-forget 落盘 + 调用方
     * 乐观更新本地状态——写后立即读 getter 不保证可见新值，反显展示
     * 以模块本地状态背书。纯界面偏好，不触发重排。
     *
     * 默认实现（旧宿主）getter 恒返回空串、写入丢弃：弹框不反显，行为
     * 不回退，历史不持久化。
     */
    var recentFontPathsEncoding: String
        get() = ""
        set(value) {}

    /**
     * 图片绘制抗锯齿（仅阅读页图片画笔消费；文字画笔恒抗锯齿不受
     * 影响）。与灰阶控制立场存在张力，默认关闭。
     */
    val useAntiAlias: Boolean

    /**
     * 预下载章节数：书架预缓存泵的启动门槛（0 = 关闭预缓存）。
     * 也是目录刷新后单本书预缓存入队的前看章数上限。
     */
    val preDownloadChapterCount: Int

    /**
     * 换源搜索结果是否校验作者一致（防同名异书误换）。
     * 透传给 [ChangeSourceEngine.searchSourceBook] 的 checkAuthor 参数。
     */
    val changeSourceCheckAuthor: Boolean

    /**
     * 应用内字体缩放原始设置值：÷10 为倍率（如 11 = 1.1 倍），有效
     * 区间 0.8~1.6，越界回落系统缩放。
     *
     * null = 未设置，跟随系统缩放。
     *
     * 可写（「我的」页入口 + 字体设置页）：**attach 期**档——缩放由
     * 入口模板在 attachBaseContext 一次性应用到 Context；模块写入后
     * 须 recreate 入口 Activity 才生效（消费方负责 recreate）。
     */
    var fontScaleSetting: Int?

    /**
     * 云端进度同步总开关（转发宿主「同步阅读进度」键，与完整模式
     * 「备份与恢复」页共享同一存储）。
     *
     * E-Ink 语义为「一键全开」：开关打开即完整双向同步——进书拉取
     * （云端超前弹确认框）、退出/息屏比较后上传、网络恢复同步；宿主的
     * 「同步增强」子键只作用于完整模式（有意分歧，见
     * [ReaderSyncTrigger] KDoc 与本仓 bridge/ReaderProgressSyncer）。
     *
     * 可写（「我的」页开关）：fire-and-forget 写入；消费方（同步编排）
     * 每次触发时实时读取，无一致性窗口。
     *
     * 默认实现 = 恒 false（宿主未实现同步能力时开关显示关闭，属诚实
     * 降级——不假装在同步）。
     */
    var syncReadingProgress: Boolean
        get() = false
        set(value) {}
}
