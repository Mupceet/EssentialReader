package io.legado.app.eink.designsystem.refresh

/**
 * 设备能力档案（规范 §42）。
 *
 * Design System 不假设「E-Ink = 单色」：能力矩阵由平台侧探测后注入，
 * 策略与 Adapter 据此降级。UI 层不得依赖具体波形，同样也不得依赖
 * 具体设备型号——都通过本档案表达。
 *
 * @param grayscaleLevels 实际可辨灰阶数（2 = 纯黑白）。
 * @param supportsPartialRefresh 支持局部刷新（脏矩形）。
 * @param supportsFastRefresh 支持快速刷新（A2/DU 类）。
 * @param supportsFullRefresh 支持全刷（清残影）。
 * @param supportsColor 彩色墨水屏。
 * @param physicalPageKeys 有实体翻页键。
 * @param touchInput 支持触摸。
 * @param keyboardInput 支持键盘/DPad。
 */
data class EInkDeviceProfile(
    val grayscaleLevels: Int,
    val supportsPartialRefresh: Boolean,
    val supportsFastRefresh: Boolean,
    val supportsFullRefresh: Boolean,
    val supportsColor: Boolean,
    val physicalPageKeys: Boolean,
    val touchInput: Boolean,
    val keyboardInput: Boolean,
) {
    companion object {
        /**
         * 保守默认档：设备能力未知时按「只支持全刷的黑白触屏设备」处理，
         * 所有非全刷意图都退化为随系统默认刷新，保证不出错。
         */
        val Conservative: EInkDeviceProfile = EInkDeviceProfile(
            grayscaleLevels = 16,
            supportsPartialRefresh = false,
            supportsFastRefresh = false,
            supportsFullRefresh = true,
            supportsColor = false,
            physicalPageKeys = false,
            touchInput = true,
            keyboardInput = false,
        )
    }
}
