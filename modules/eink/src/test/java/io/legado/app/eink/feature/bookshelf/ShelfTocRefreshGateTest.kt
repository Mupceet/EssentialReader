package io.legado.app.eink.feature.bookshelf

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单本目录刷新超时闸门语义：病态书源（规则 JS 死循环等无界耗时）超时
 * 放弃不拖垮整轮刷新——整轮 isRefreshing 复位依赖全部逐本管线返回，
 * 无闸门时一本挂住即刷新按钮永久置灰（退出书架重建 ViewModel 才恢复）。
 *
 * 取消语义锚定：rhino fork 的 observeInstructionCount 已挂协程取消检查
 * （RhinoContext.ensureActive），withTimeout 的取消可真正杀死死循环 JS；
 * 闸门只吞超时（返回 "timeout"），外部取消（VM 销毁）与管线业务异常
 * 原样穿透。
 */
class ShelfTocRefreshGateTest {

    @Test
    fun `超时放弃返回 timeout 不拖垮整轮`() = runTest {
        // 可取消的挂起即病态管线模型：网络挂起与 rhino 死循环在取消下
        // 均可终止（后者经 observeInstructionCount）；虚拟时钟推进到闸门
        // 到期即放弃
        val result = refreshTocBounded(timeoutMs = 1_000) {
            awaitCancellation()
        }

        assertEquals("timeout", result)
    }

    @Test
    fun `正常结果直通`() = runTest {
        assertEquals("ok", refreshTocBounded(timeoutMs = 5_000) { "ok" })
        assertEquals("error", refreshTocBounded(timeoutMs = 5_000) { "error" })
    }

    @Test
    fun `管线业务异常原样穿透不吞`() = runTest {
        // 宿主 bridge 自捕业务异常转 ERROR 结果；闸门若吞掉异常会掩盖
        // 桥接层逃逸的编程错误，必须原样穿透
        val thrown = runCatching {
            refreshTocBounded(timeoutMs = 5_000) {
                throw IllegalStateException("管线内部异常")
            }
        }.exceptionOrNull()

        assertTrue(
            "业务异常应原样传播而非被吞成 timeout",
            thrown is IllegalStateException
        )
    }

    @Test
    fun `外部取消保持取消语义而非误报超时`() = runTest {
        val job = launch {
            refreshTocBounded(timeoutMs = 60_000) {
                awaitCancellation()
            }
            // 若闸门把外部取消吞成 "timeout"，本行可达、job 正常完成
            throw AssertionError("外部取消被吞成正常返回")
        }

        job.cancelAndJoin()

        // 取消必须以取消态收场：isCancelled = false 即闸门吞了取消
        assertTrue("外部取消应传播为 CancellationException", job.isCancelled)
    }
}
