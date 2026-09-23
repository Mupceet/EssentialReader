package io.legado.app.eink.app

import io.legado.app.eink.contract.BackupSyncEngine
import io.legado.app.eink.contract.CloudBackupNewer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 云端备份启动检查 VM 状态机回归：检查静默口径、确认恢复忙态、
 * 失败可重试、恢复中收起的抑制位（终态不回显弹层只走 toast）。
 *
 * VM 只经方法参数消费端口（不触注册表），fake 引擎用
 * CompletableDeferred 手动放行驱动全状态转移；viewModelScope 的
 * Main 依赖由 setMain(Unconfined) 满足（ChangeSourceViewModelTest 同款，
 * runBlocking + 真实时间等待）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EInkBackupSyncViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `检查命中弹层无新备份静默`() = runBlocking {
        val engine = FakeBackupSyncEngine()
        val viewModel = EInkBackupSyncViewModel()

        viewModel.checkOnStart(engine)
        assertTrue("检查挂起中无弹层、标记检查中", viewModel.checking)
        assertNull(viewModel.prompt)
        engine.checkResult.complete(null)
        awaitUntil { !viewModel.checking }
        assertNull("无新备份静默", viewModel.prompt)
        assertNull(viewModel.oneShotNotice)

        // 第二次检查命中（宿主 recreate 重跑效应同位：prompt 空才重查）
        engine.checkResult = CompletableDeferred()
        viewModel.checkOnStart(engine)
        engine.checkResult.complete(info)
        awaitUntil { viewModel.prompt != null }
        assertEquals(BackupSyncPromptState.Newer(info), viewModel.prompt)
    }

    @Test
    fun `检查异常静默不弹层`() = runBlocking {
        val engine = FakeBackupSyncEngine()
        engine.checkResult.completeExceptionally(IllegalStateException("网络不可用"))
        val viewModel = EInkBackupSyncViewModel()

        viewModel.checkOnStart(engine)
        awaitUntil { !viewModel.checking }
        assertNull(viewModel.prompt)
        assertNull(viewModel.oneShotNotice)
    }

    @Test
    fun `确认恢复成功收层并提示完成`() = runBlocking {
        val engine = FakeBackupSyncEngine()
        engine.checkResult.complete(info)
        val viewModel = EInkBackupSyncViewModel()
        viewModel.checkOnStart(engine)
        awaitUntil { viewModel.prompt != null }

        viewModel.confirmRestore(engine)
        assertEquals(BackupSyncPromptState.Restoring(info), viewModel.prompt)
        engine.restoreResult.complete(Result.success(Unit))
        awaitUntil { viewModel.oneShotNotice != null }
        assertNull("成功后弹层收起", viewModel.prompt)
        assertEquals("恢复完成", viewModel.oneShotNotice)
        assertEquals(info.fileName, engine.restoreCalledWith)

        viewModel.clearNotice()
        assertNull(viewModel.oneShotNotice)
    }

    @Test
    fun `恢复失败保留弹层可重试`() = runBlocking {
        val engine = FakeBackupSyncEngine()
        engine.checkResult.complete(info)
        val viewModel = EInkBackupSyncViewModel()
        viewModel.checkOnStart(engine)
        awaitUntil { viewModel.prompt != null }

        viewModel.confirmRestore(engine)
        engine.restoreResult.complete(Result.failure(IllegalStateException("网络中断")))
        awaitUntil { viewModel.prompt is BackupSyncPromptState.Failed }
        val failed = viewModel.prompt as BackupSyncPromptState.Failed
        assertEquals("网络中断", failed.reason)
        assertEquals(info, failed.info)

        // 重试成功
        engine.restoreResult = CompletableDeferred()
        viewModel.confirmRestore(engine)
        engine.restoreResult.complete(Result.success(Unit))
        awaitUntil { viewModel.oneShotNotice != null }
        assertNull(viewModel.prompt)
        assertEquals("恢复完成", viewModel.oneShotNotice)
    }

    @Test
    fun `恢复中收起后失败不回显弹层只提示`() = runBlocking {
        val engine = FakeBackupSyncEngine()
        engine.checkResult.complete(info)
        val viewModel = EInkBackupSyncViewModel()
        viewModel.checkOnStart(engine)
        awaitUntil { viewModel.prompt != null }

        viewModel.confirmRestore(engine)
        viewModel.dismiss() // 恢复中收起：协程不取消
        assertNull(viewModel.prompt)
        engine.restoreResult.complete(Result.failure(IllegalStateException("超时")))
        awaitUntil { viewModel.oneShotNotice != null }
        assertNull("已收起不回显弹层", viewModel.prompt)
        assertEquals("恢复失败：超时", viewModel.oneShotNotice)
    }

    @Test
    fun `未确认前收起即放弃不产生任何提示`() = runBlocking {
        val engine = FakeBackupSyncEngine()
        engine.checkResult.complete(info)
        val viewModel = EInkBackupSyncViewModel()
        viewModel.checkOnStart(engine)
        awaitUntil { viewModel.prompt != null }

        viewModel.dismiss()
        assertNull(viewModel.prompt)
        assertNull(viewModel.oneShotNotice)
    }

    private val info = CloudBackupNewer(
        fileName = "backup2026-09-23-ReaderA.zip",
        deviceName = "ReaderA",
        dateText = "2026-09-23",
    )

    /** 轮询等待状态转移（Unconfined 下 deferred 完成多内联恢复，轮询为兜底）。 */
    private suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) { while (!condition()) delay(10) }
    }

    private class FakeBackupSyncEngine : BackupSyncEngine {
        var checkResult = CompletableDeferred<CloudBackupNewer?>()
        var restoreResult = CompletableDeferred<Result<Unit>>()
        var restoreCalledWith: String? = null

        override suspend fun checkNewBackupOnStart(): CloudBackupNewer? = checkResult.await()

        override suspend fun restoreBackup(fileName: String) {
            restoreCalledWith = fileName
            restoreResult.await().getOrThrow()
        }
    }
}
