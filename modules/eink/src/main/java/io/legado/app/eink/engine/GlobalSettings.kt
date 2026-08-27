package io.legado.app.eink.engine

/**
 * 全局设置的只读视图（转发宿主 AppConfig）。
 *
 * 只收录 E-Ink VM 编排真正读取的键；E-Ink 自有的界面偏好
 * （书架网格布局、阅读页常亮/自动翻页间隔）不在此列 ——
 * 它们由 [io.legado.app.eink.settings.EinkSettings] 自管，
 * 随模块走。
 */
interface GlobalSettings {
    /** 线程数（目录刷新并发、换源并发上限用）。 */
    val threadCount: Int

    /** 进入首页是否自动刷新一次（对齐 View 版 MainActivity）。 */
    val autoRefreshBook: Boolean

    /** 预下载章节数（0 = 关闭预缓存）。 */
    val preDownloadNum: Int

    /** 换源时是否校验作者。 */
    val changeSourceCheckAuthor: Boolean
}
