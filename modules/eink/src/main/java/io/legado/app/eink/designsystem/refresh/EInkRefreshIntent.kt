package io.legado.app.eink.designsystem.refresh

/**
 * 刷新意图（规范 §40）：组件只产生语义事件，不选择刷新方式。
 *
 * 组件/屏幕在语义状态变化时上报对应的 Intent：
 *
 * ```text
 * Page changed        -> PageTurn
 * Dialog shown        -> Overlay
 * Text typed          -> TextInput
 * Button pressed      -> Interactive
 * Content settled     -> ContentStable
 * Screen swapped      -> Navigation
 * Ghosting observed   -> ClearGhosting
 * ```
 *
 * 而不是直接要求 A2 / DU / GC16 等波形——那些属于 Device Adapter 层
 * （规范 §39/§43）。具体波形由 [EInkRefreshPolicy] 结合
 * [EInkDeviceProfile] 决定抽象档位，再由平台侧 Adapter 映射到硬件。
 */
enum class EInkRefreshIntent {
    /** 内容已稳定（列表加载完成、页面静止），常规灰阶质量即可。 */
    ContentStable,

    /** 连续交互中（拖动、滑条跟随），需要快速低闪的档位。 */
    Interactive,

    /** 翻页（阅读/列表整页跳转），设备默认翻页策略。 */
    PageTurn,

    /** 页面级切换（导航跳转），整屏内容替换。 */
    Navigation,

    /** 覆盖层出现/消失（对话框、菜单）。 */
    Overlay,

    /** 文本输入（光标移动、字符变更），适合局部刷新。 */
    TextInput,

    /** 全量重绘（主题切换、大面积损坏）。 */
    FullRedraw,

    /** 清残影（用户请求或策略周期性触发）。 */
    ClearGhosting,
}
