package io.legado.app.eink.contract

/**
 * 全局设置视图：模块全部设置项的唯一出入口。
 *
 * 收录 E-Ink VM 编排与「我的」页/阅读菜单真正读写的键——含转发宿主
 * 设置的键（与完整模式共享同一存储）与 E-Ink 自有偏好（如
 * [keepScreenOn]）。存储后端由宿主决定：嵌入式宿主与完整模式共享
 * 存储（自有偏好键以历史键名落宿主默认 prefs 文件），插件宿主可用
 * 自有 DataStore。
 *
 * 写入语义分档（宿主实现须遵守，模块 UI 按档位做乐观更新）：
 *  - fire-and-forget：异步落盘，写后立即读 getter **不保证**可见新值；
 *  - 快照状态：读取由宿主 Compose 快照状态背书，写入同步可见；
 *  - attach 期：写入后需 recreate 入口 Activity 才生效。
 * 各键档位见其 KDoc。
 */
interface GlobalSettings {
    /** 线程数（目录刷新并发、换源并发上限用）。 */
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
     * 可写（「我的」页开关）：fire-and-forget 写入；实时生效——阅读页
     * 按键处理器每次按键时读取，无一致性窗口。
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
     * 阅读页保持屏幕常亮（E-Ink 自有界面偏好，完整模式无对应设置）。
     *
     * 可写（阅读菜单开关）：读取在阅读 VM 构造时一次，写入即时生效
     * 且同步落盘。嵌入式宿主实现以历史键 `einkReaderKeepScreenOn` 存
     * 默认 prefs 文件（存量设置无损继承）。
     */
    var keepScreenOn: Boolean

    /**
     * 图片绘制抗锯齿（仅阅读页图片画笔消费；文字画笔恒抗锯齿不受
     * 影响）。
     */
    val useAntiAlias: Boolean

    /** 预下载章节数（书架预缓存泵的启动门槛，0 = 关闭预缓存）。 */
    val preDownloadChapterCount: Int

    /** 换源搜索结果是否校验作者一致（防同名异书）。 */
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
}
