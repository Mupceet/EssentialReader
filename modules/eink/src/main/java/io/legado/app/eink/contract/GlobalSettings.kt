package io.legado.app.eink.contract

/**
 * 全局设置视图（模块全部设置项的唯一出入口）。
 *
 * 收录 E-Ink VM 编排与「我的」页/阅读菜单真正读写的键——含转发宿主
 * 设置的键（与完整模式共享同一存储）与 E-Ink 自有偏好（如
 * [keepScreenOn]）。存储后端由宿主决定：嵌入式宿主与完整模式共享
 * 存储（自有偏好键以历史键名落宿主默认 prefs 文件），插件宿主可用
 * 自有 DataStore。
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

    /**
     * 总是使用默认封面（与完整模式封面设置共享同一存储键）。
     *
     * 可写（「我的」页开关）：读取由宿主的 Compose 快照状态背书——组合内
     * 读取订阅变化，切换后开关行与书架/详情可见封面立即重组（与
     * [autoRefreshBook] 等的写后读不保证可见语义不同）；写入同步更新状态、
     * 异步落盘。宿主在入口 install 时与设置快照对齐（防跨模式往返后的
     * 陈旧值）。
     */
    var useDefaultCover: Boolean

    /**
     * 阅读页保持屏幕常亮（E-Ink 自有界面偏好，完整模式无对应设置）。
     *
     * 可写（阅读菜单开关）：读取在阅读 VM 构造时一次，写入即时生效且
     * 同步落盘。嵌入式宿主实现以历史键 `einkReaderKeepScreenOn` 存于
     * 默认 prefs 文件（键名/文件与 EInkSettings 时期逐字一致，存量
     * 设置无损继承）。
     */
    var keepScreenOn: Boolean

    /**
     * 图片绘制抗锯齿（OtherSettings.antiAlias，与完整模式共享同一开关）。
     * 阅读页图片画笔取用；引擎文字画笔恒抗锯齿，不受本项影响。
     */
    val useAntiAlias: Boolean

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
