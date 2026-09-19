package io.legado.app.eink.feature.bookshelf

import io.legado.app.eink.contract.BookshelfEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 预缓存泵的进程级宿主。
 *
 * 泵不能挂在书架 VM 的 viewModelScope：书架 VM 随目的地切换销毁（进入
 * 阅读页即销毁），泵随之取消后，刷新已入队的章节留在 CacheBook 队列里
 * 无人消费，直到阅读页懒加载才恢复下载。此处用模块自有进程级作用域承载
 * 泵：VM 销毁不再打断下载，队列清空（或全局暂停）后泵自终止，不常驻。
 *
 * 与目录刷新并行：不做「目录优先」整轮暂停（那会让多书刷新的几分钟里
 * 一章不下）。两侧并发额度天然分离——目录刷新按 threadCount、章节下载
 * 按 cacheBookThreadCount，且都是挂起式网络请求，线程不互斥；高峰期仅
 * 共享 OkHttp 全局 maxRequests 上界。泵随每本书入队触发（幂等复用），
 * 队列空时启动的空泵立即自终止。
 */
internal object CacheBookPump {

    private val defaultScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 单元测试注入点：虚拟时间作用域替换进程级 IO 作用域，其余逻辑不变
    internal var scopeOverride: CoroutineScope? = null

    private val scope: CoroutineScope
        get() = scopeOverride ?: defaultScope

    private var job: Job? = null

    /**
     * 触发泵（挂起至队列清空；调用方无需等待结果，返回值仅用于去重）。
     * 泵运行中重复触发直接复用。
     */
    @Synchronized
    fun start(engine: BookshelfEngine): Job {
        job?.takeIf { it.isActive }?.let { return it }
        return scope.launch {
            engine.startCacheProcessJob()
        }.also { job = it }
    }
}
