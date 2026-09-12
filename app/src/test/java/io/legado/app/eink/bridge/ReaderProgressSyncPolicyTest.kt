package io.legado.app.eink.bridge

import io.legado.app.domain.model.ReadingProgress
import io.legado.app.eink.bridge.ReaderProgressSyncPolicy.ProgressRelation.CloudAhead
import io.legado.app.eink.bridge.ReaderProgressSyncPolicy.ProgressRelation.CloudMissing
import io.legado.app.eink.bridge.ReaderProgressSyncPolicy.ProgressRelation.Equal
import io.legado.app.eink.bridge.ReaderProgressSyncPolicy.ProgressRelation.LocalAhead
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端进度比较核心：复刻宿主三处比较器（ReadBook.syncProgress /
 * ReadBookLoadDelegate.syncBookProgress / downloadAllBookProgress）共用的
 * 字典序语义与章节有效性 gate。
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
    fun `same chapter pos ahead is CloudAhead`() {
        assertTrue(
            ReaderProgressSyncPolicy.relation(cloud(3, 6), 3, 5) == CloudAhead
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
}
