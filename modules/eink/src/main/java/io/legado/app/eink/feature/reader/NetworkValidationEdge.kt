package io.legado.app.eink.feature.reader

/**
 * NET_CAPABILITY_VALIDATED 边沿状态机（纯状态类，可单测）。
 *
 * 墨水屏 Wi-Fi 重连存在「capability 已上报但探测未过」的窗口，
 * onAvailable 早于实际可通；本类把 onCapabilitiesChanged 的
 * VALIDATED 上报收敛为未验证→已验证的单次边沿，作为网络真正恢复
 * 的补试时机。onLost 重置——不重置会吞掉下一次连接周期的边沿。
 *
 * 初始即已验证（重注册 callback 的首次上报）算一次边沿：触发动作
 * 幂等（进度同步为拉取+比较，自愈有目标防抖），可接受。
 */
internal class NetworkValidationEdge {

    private var validated = false

    /** 处理一次 VALIDATED 上报；返回 true 表示发生未验证→已验证边沿。 */
    fun onCapabilities(isValidated: Boolean): Boolean {
        val edge = isValidated && !validated
        validated = isValidated
        return edge
    }

    /** 网络丢失：重置边沿状态，允许下一次连接周期重新触发。 */
    fun onLost() {
        validated = false
    }
}
