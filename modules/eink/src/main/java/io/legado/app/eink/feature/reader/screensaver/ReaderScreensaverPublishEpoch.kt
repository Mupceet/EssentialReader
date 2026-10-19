package io.legado.app.eink.feature.reader.screensaver

import java.util.concurrent.atomic.AtomicLong

/**
 * 进程级发布代数（2026-10-19 修复跨实例竞态）：每个阅读组合各持一个
 * [ReaderScreensaverPublisher]，各自的互斥与指纹门无法阻止「旧组合退出
 * 时的慢 flush（含网络大图重载）晚于新组合发布完成」——旧 flush 的
 * cleanupObsolete 会删掉新组合刚发布的壁纸、再广播旧书文件，锁屏持久
 * 停留在旧书。
 *
 * 语义：任何组合调度一次发布即 [schedule] 递增代数；在途发布周期持有
 * 调度时的 token，在产生副作用（写文件/广播/清理）前用 [isCurrent]
 * 复核——已被更新调度超越的周期整体放弃。最后一次调度者的内容最终
 * 落盘，代数不回退。
 */
internal class ReaderScreensaverPublishEpoch {
    private val counter = AtomicLong(0L)

    /** 调度一次发布，返回本次发布的代数 token。 */
    fun schedule(): Long = counter.incrementAndGet()

    /** token 仍为最新调度时 true；有更新调度发生则 false（本周期应放弃）。 */
    fun isCurrent(token: Long): Boolean = counter.get() == token
}
