package io.legado.app.eink.engine

/**
 * 宿主级界面设置端口：E-Ink 版需要「写入后重新应用」的应用全局 UI 偏好。
 *
 * 与只读的 [GlobalSettings]（后台刷新等行为开关）相对，本端口承载的是
 * attach 时配置类设置：模块侧写入后 recreate 承载 Activity 才能生效
 * （入口 Activity 的 fontScale 在 attachBaseContext 一次性应用）。
 */
interface UiSettings {

    /**
     * 应用内字体缩放原始设置值：÷10 为倍率（如 11 = 1.1 倍），有效区间
     * 0.8~1.6（与宿主完整模式的界面字体缩放共享同一存储键，两端语义
     * 一致：越界/未设置时宿主回落系统缩放）。
     *
     * null = 未设置，跟随系统缩放。
     */
    var fontScaleSetting: Int?
}
