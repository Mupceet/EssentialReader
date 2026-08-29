package io.legado.app.eink.designsystem.refresh

/**
 * 抽象刷新档位（规范 §43 Waveform Abstraction）。
 *
 * UI/策略层只使用这四档语义质量级；具体波形（A2/DU/GC16/GL16/
 * vendor mode）由 Device Refresh Adapter 映射：
 *
 * ```text
 * Interactive -> A2 / DU / vendor fast mode
 * Stable      -> normal grayscale mode
 * PageTurn    -> device default page-turn policy
 * FullRedraw  -> GC16 / GL16 / vendor full mode
 * ```
 */
enum class EInkRefreshTier {
    /** 快速低闪：连续交互、文本输入。 */
    Interactive,

    /** 常规灰阶质量：内容稳定、覆盖层、导航整屏替换。 */
    Stable,

    /** 翻页：设备默认翻页策略（快/全刷由设备档位决定）。 */
    PageTurn,

    /** 全刷：清残影、大面积重绘（昂贵，规范 §69 限制使用场景）。 */
    FullRedraw,
}

/**
 * 刷新策略（规范 §41）：根据意图与设备能力决定抽象档位。
 *
 * 策略不接触硬件、不含波形名；真机 Adapter 拿到档位后再做平台映射。
 * 设备不支持某档位时由实现自行降级（如无快速刷新时 Interactive →
 * Stable）。
 */
fun interface EInkRefreshPolicy {
    fun decide(intent: EInkRefreshIntent, profile: EInkDeviceProfile): EInkRefreshTier
}

/**
 * 默认策略：意图 → 档位的保守映射。
 *
 * 设备能力降级规则：
 *  - 不支持快速刷新时，Interactive 退化为 Stable（宁可慢不可闪）；
 *  - 不支持全刷时，FullRedraw 退化为 Stable（由系统自行处理残影）。
 */
object DefaultEInkRefreshPolicy : EInkRefreshPolicy {
    override fun decide(
        intent: EInkRefreshIntent,
        profile: EInkDeviceProfile,
    ): EInkRefreshTier = when (intent) {
        EInkRefreshIntent.ContentStable -> EInkRefreshTier.Stable
        EInkRefreshIntent.Interactive,
        EInkRefreshIntent.TextInput,
        -> if (profile.supportsFastRefresh) EInkRefreshTier.Interactive else EInkRefreshTier.Stable

        EInkRefreshIntent.PageTurn -> EInkRefreshTier.PageTurn
        EInkRefreshIntent.Navigation,
        EInkRefreshIntent.Overlay,
        -> EInkRefreshTier.Stable

        EInkRefreshIntent.FullRedraw,
        EInkRefreshIntent.ClearGhosting,
        -> if (profile.supportsFullRefresh) EInkRefreshTier.FullRedraw else EInkRefreshTier.Stable
    }
}
