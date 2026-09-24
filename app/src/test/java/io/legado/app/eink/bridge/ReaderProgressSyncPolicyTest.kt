package io.legado.app.eink.bridge

import io.legado.app.domain.model.ReadingProgress
import io.legado.app.eink.bridge.ReaderProgressSyncPolicy.ProgressRelation.CloudAhead
import io.legado.app.eink.bridge.ReaderProgressSyncPolicy.ProgressRelation.CloudMissing
import io.legado.app.eink.bridge.ReaderProgressSyncPolicy.ProgressRelation.CloudPosAhead
import io.legado.app.eink.bridge.ReaderProgressSyncPolicy.ProgressRelation.Equal
import io.legado.app.eink.bridge.ReaderProgressSyncPolicy.ProgressRelation.LocalAhead
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端进度比较核心：方向语义源自宿主三处比较器（ReadBook.syncProgress /
 * ReadBookLoadDelegate.syncBookProgress / downloadAllBookProgress）共用的
 * 字典序，差异一处：同章云端位置超前单列 CloudPosAhead（调用侧忽略，
 * 防同章微差循环）；另覆盖章节有效性 gate 与弹框上界取小。
 */
class ReaderProgressSyncPolicyTest {

    private fun cloud(index: Int, pos: Int) =
        ReadingProgress("", "", index, pos, 0L, null)

    @Test
    fun `null cloud is CloudMissing`() {
        assertTrue(
            ReaderProgressSyncPolicy.relation(null, localChapterIndex = 3, localChapterPos = 5)
                == CloudMissing
        )
    }

    @Test
    fun `cloud chapter ahead is CloudAhead`() {
        assertTrue(
            ReaderProgressSyncPolicy.relation(cloud(4, 0), 3, 100) == CloudAhead
        )
    }

    @Test
    fun `same chapter cloud pos ahead is CloudPosAhead not prompt-worthy`() {
        assertTrue(
            ReaderProgressSyncPolicy.relation(cloud(3, 6), 3, 5) == CloudPosAhead
        )
    }

    @Test
    fun `same chapter cloud pos far ahead is still CloudPosAhead`() {
        // 哪怕同章 pos 差很大也不弹框：跨设备 pos 空间不可比，只有章节级超前才提示
        assertTrue(
            ReaderProgressSyncPolicy.relation(cloud(3, 99999), 3, 1) == CloudPosAhead
        )
    }

    @Test
    fun `exact match is Equal`() {
        assertTrue(
            ReaderProgressSyncPolicy.relation(cloud(3, 5), 3, 5) == Equal
        )
    }

    @Test
    fun `local chapter ahead is LocalAhead even with smaller pos`() {
        assertTrue(
            ReaderProgressSyncPolicy.relation(cloud(3, 100), 4, 0) == LocalAhead
        )
    }

    @Test
    fun `same chapter local pos ahead is LocalAhead`() {
        assertTrue(
            ReaderProgressSyncPolicy.relation(cloud(3, 4), 3, 5) == LocalAhead
        )
    }

    @Test
    fun `bounds gate matches host simulated chapter num semantics`() {
        assertTrue(ReaderProgressSyncPolicy.chapterIndexInBounds(0, 10))
        assertTrue(ReaderProgressSyncPolicy.chapterIndexInBounds(9, 10))
        assertFalse(ReaderProgressSyncPolicy.chapterIndexInBounds(10, 10))
        assertFalse(ReaderProgressSyncPolicy.chapterIndexInBounds(-1, 10))
        assertFalse(ReaderProgressSyncPolicy.chapterIndexInBounds(0, 0))
    }

    @Test
    fun `prompt bound takes the smaller of simulated total and live row count`() {
        // 书记录 totalChapterNum 滞后偏高（652）而实际章节行数只有 650
        assertTrue(ReaderProgressSyncPolicy.promptChapterBound(652, 650) == 650)
        assertTrue(ReaderProgressSyncPolicy.promptChapterBound(650, 652) == 650)
        assertTrue(ReaderProgressSyncPolicy.promptChapterBound(652, 652) == 652)
    }

    @Test
    fun `cloud chapter beyond live rows is gated out before prompting`() {
        val bound = ReaderProgressSyncPolicy.promptChapterBound(652, 650)
        // 行数内（第 650 章，下标 649）仍放行
        assertTrue(ReaderProgressSyncPolicy.chapterIndexInBounds(649, bound))
        // 行数外（第 651 章，下标 650）：不弹框，杜绝「弹了恢复框但应用被守卫吞掉」
        assertFalse(ReaderProgressSyncPolicy.chapterIndexInBounds(650, bound))
    }
}
