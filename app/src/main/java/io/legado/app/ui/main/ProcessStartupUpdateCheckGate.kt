package io.legado.app.ui.main

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 启动自动检查更新的进程级一次性闸：完整模式 MainActivity 与
 * E-Ink 模式（经 AppUpdateEngineImpl）共用同一实例，语义为
 * 「每个进程生命周期最多自动检查一次」，跨模式往返不重复弹框。
 */
internal object ProcessStartupUpdateCheckGate {

    private val consumed = AtomicBoolean(false)

    fun consume(enabled: Boolean): Boolean = !consumed.getAndSet(true) && enabled
}
