package io.legado.app.help.storage

import io.legado.app.data.entities.readRecord.ReadRecordSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话导入判重（Restore.filterPendingReadRecordSessions）：现有行身份与
 * 导入批内身份共用一个已见集（重复导入幂等）；现有行原样参与比较，与旧
 * 逐条查库按本地 deviceId 精确匹配的语义一致——远端分区的同身份行不
 * 拦截导入。
 */
class ReadRecordSessionImportFilterTest {

    private fun session(
        deviceId: String = "",
        name: String = "书名",
        author: String = "作者",
        start: Long = 100L,
    ) = ReadRecordSession(
        deviceId = deviceId,
        bookName = name,
        bookAuthor = author,
        bookUrl = "https://example.com/book",
        startTime = start,
        endTime = start + 60L,
        words = 1000L,
    )

    @Test
    fun `existing local identity is skipped`() {
        val pending = Restore.filterPendingReadRecordSessions(
            existing = listOf(session()),
            incoming = listOf(session()),
        )
        assertTrue(pending.isEmpty())
    }

    @Test
    fun `duplicates within batch are imported once`() {
        val pending = Restore.filterPendingReadRecordSessions(
            existing = emptyList(),
            incoming = listOf(session(start = 1L), session(start = 1L), session(start = 2L)),
        )
        assertEquals(listOf(1L, 2L), pending.map { it.startTime })
    }

    @Test
    fun `remote partition row does not block import`() {
        val pending = Restore.filterPendingReadRecordSessions(
            existing = listOf(session(deviceId = "old-device")),
            incoming = listOf(session()),
        )
        assertEquals(1, pending.size)
    }

    @Test
    fun `incoming sessions are localized to local partition`() {
        val pending = Restore.filterPendingReadRecordSessions(
            existing = emptyList(),
            incoming = listOf(session(deviceId = "remote-device")),
        )
        assertEquals("", pending.single().deviceId)
    }

    @Test
    fun `identity normalization collapses whitespace before dedup`() {
        val pending = Restore.filterPendingReadRecordSessions(
            existing = listOf(session(name = "书 名")),
            incoming = listOf(session(name = "  书   名 ")),
        )
        assertTrue(pending.isEmpty())
    }
}
