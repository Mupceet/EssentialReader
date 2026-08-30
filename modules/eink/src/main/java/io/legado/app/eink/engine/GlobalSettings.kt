package io.legado.app.eink.engine

/**
 * 全局设置视图（转发宿主设置存储）。
 *
 * 只收录 E-Ink VM 编排与「我的」页真正读写的键；E-Ink 自有的界面偏好
 * （书架网格布局、阅读页常亮/自动翻页间隔）不在此列 ——
 * 它们由 [io.legado.app.eink.settings.EInkSettings] 自管，
 * 随模块走。
 */
interface GlobalSettings {
    /** 线程数（目录刷新并发、换源并发上限用）。 */
    val threadCount: Int

    /**
     * 进入首页是否自动刷新一次（对齐 View 版 MainActivity）。
     *
     * 可写（「我的」页开关）：写入为 fire-and-forget 异步落盘，写后立即
     * 读 getter 不保证可见新值，调用方应以本地状态做乐观更新；生效时机
     * 为下次启动（VM init 时读取）。
     */
    var autoRefreshBook: Boolean

    /**
     * 启动是否自动跳转最近阅读（对齐 View 版 defaultToRead）。
     *
     * 可写（「我的」页开关）：写入语义同 [autoRefreshBook]；生效时机
     * 为下次启动（入口 Activity onCreate 时读取）。
     */
    var defaultToRead: Boolean

    /**
     * 音量键翻页（与完整模式阅读设置共享同一存储键，默认开）。
     *
     * 可写（「我的」页开关）：写入语义同 [autoRefreshBook]；与上述两项
     * 启动期语义不同，本项实时生效 —— 阅读页按键处理器每次按键时读取。
     */
    var volumeKeyPage: Boolean

    /** 预下载章节数（0 = 关闭预缓存）。 */
    val preDownloadNum: Int

    /** 换源时是否校验作者。 */
    val changeSourceCheckAuthor: Boolean

    /**
     * 应用内字体缩放原始设置值：÷10 为倍率（如 11 = 1.1 倍），有效区间
     * 0.8~1.6（与宿主完整模式的界面字体缩放共享同一存储键，两端语义
     * 一致：越界/未设置时宿主回落系统缩放）。
     *
     * null = 未设置，跟随系统缩放。
     *
     * 可写（「我的」页开关）：attach 期配置 —— fontScale 由入口 Activity
     * 在 attachBaseContext 一次性应用，模块侧写入后须 recreate 入口
     * Activity 才能生效（消费方负责 recreate）。
     */
    var fontScaleSetting: Int?
}
