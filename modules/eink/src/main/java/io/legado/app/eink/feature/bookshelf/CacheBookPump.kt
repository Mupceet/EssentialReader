package io.legado.app.eink.feature.bookshelf

import io.legado.app.eink.contract.BookshelfEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 预缓存泵的进程级宿主。
 *
 * 泵不能挂在书架 VM 的 viewModelScope：书架 VM 随目的地切换销毁（进入
 * 阅读页即销毁），泵随之取消后，刷新已入队的章节留在 CacheBook 队列里
 * 无人消费，直到阅读页懒加载才恢复下载。此处用模块自有进程级作用域承载
 * 泵：VM 销毁不再打断下载，队列清空（或全局暂停）后泵自终止，不常驻。
 *
 * 重复触发（新书架 VM 刷新完成）不重复起泵，只接管刷新态读取器——
 * 暂停/恢复判定始终以最新书架 VM 的刷新状态为准，避免沿用已销毁 VM
 * 的陈旧结论。
 */
internal object CacheBookPump {

    private val defaultScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 单元测试注入点：虚拟时间作用域替换进程级 IO 作用域，其余逻辑不变
    internal var scopeOverride: CoroutineScope? = null

    private val scope: CoroutineScope
        get() = scopeOverride ?: defaultScope

    private var job: Job? = null

    @Volatile
    private var refreshState: () -> Boolean = { false }

    /**
     * 触发泵（挂起至队列清空；调用方无需等待结果，返回值仅用于去重）。
     *
     * @param isRefreshing 目录刷新进行中判定；泵存续期间以 1s 轮询对齐
     *   工作态（目录优先，对齐 View 版 workingState 联动）。
     */
    @Synchronized
    fun start(engine: BookshelfEngine, isRefreshing: () -> Boolean): Job {
        updateRefreshState(isRefreshing)
        job?.takeIf { it.isActive }?.let { return it }
        return scope.launch {
            val pump = launch { engine.startCacheProcessJob() }
            // 不能以 engine.isCacheRunning 为轮询条件：泵启动前该查询恒
            // false，轮询会立即退出，刷新期间将无人暂停泵；改为跟随泵
            // 任务本身存续。
            launch {
                while (pump.isActive) {
                    engine.setCacheWorkingState(!refreshState())
                    delay(1_000)
                }
            }
            pump.join()
        }.also { job = it }
    }

    /**
     * 接管刷新态读取器（不起泵）。供新一轮目录刷新启动时调用：上一轮
     * 泵可能仍在消费旧队列，目录优先要求本轮刷新期间暂停它——即使泵
     * 是已销毁的上一轮书架 VM 启动的。
     */
    @Synchronized
    fun updateRefreshState(isRefreshing: () -> Boolean) {
        refreshState = isRefreshing
    }
}
