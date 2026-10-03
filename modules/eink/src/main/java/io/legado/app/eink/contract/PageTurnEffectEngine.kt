package io.legado.app.eink.contract

/**
 * 硬件翻页波纹效果端口（**可选**）——掌阅 EPDC PAGE_H 水波纹类设备能力。
 *
 * 未注册 = 宿主无此类设备能力，阅读「其它设置」面板的水波纹翻页档位行
 * 不渲染（不留死开关），翻页不触发任何效果，不参与 install 必填校验。
 * 设备能力探测（[supported]，含品牌与固件接口双重判定）由宿主实现承担；
 * 声明 false 与未注册等价降级，实现不得以「成功的空操作」伪造支持。
 *
 * 调用契约（帧序敏感）：
 *  - [preparePageTurn] 必须在**触发翻页的 UI 状态提交之前**、主线程
 *    调用——强制波形只作用于下一个提交帧，早于它的无关帧会消费掉效果；
 *  - 前进/后退以波纹扫入方向区分（forward = true 下一页），屏幕旋转
 *    由实现自取并折算方向编码；
 *  - 速度档语义由 [PageTurnRippleMode] 表达，具体波形速度位由实现映射；
 *    [PageTurnRippleMode.OFF] 由调用方拦截，不传入（传入时实现按
 *    no-op 防御）；
 *  - 实现对不支持/固件接口变化/调用失败一律静默 no-op，不抛出——
 *    效果是纯增益，任何失败都不阻断翻页。
 */
interface PageTurnEffectEngine {

    /** 设备是否支持（宿主探测；false 时调用方跳过效果、档位行隐藏）。 */
    val supported: Boolean

    /**
     * 为下一次翻页准备波纹（帧序契约见接口 KDoc）。
     *
     * @param forward true = 前进（下一页），false = 后退（上一页）。
     * @param mode 波纹速度档（[PageTurnRippleMode.OFF] 之外的档位）。
     */
    fun preparePageTurn(forward: Boolean, mode: PageTurnRippleMode)
}

/**
 * 水波纹翻页档位（含关闭，共四档；界面文案 关闭/慢速/标准/快速，
 * 对齐掌阅官方「慢/标准/快」语义并统一两字宽）。
 *
 * 语义档位，不含任何厂商编码：速度位（波形时长/残影表现）由宿主
 * Device Refresh 实现映射——同一档位在不同固件上的具体表现允许差异。
 */
enum class PageTurnRippleMode {
    /** 关闭：不触发任何波纹效果（默认）。 */
    OFF,

    /** 慢速：波形时长最长、过渡最平缓。 */
    SLOW,

    /** 标准：时长与平缓的均衡档。 */
    STANDARD,

    /** 快速：波形时长最短。 */
    FAST,
}
