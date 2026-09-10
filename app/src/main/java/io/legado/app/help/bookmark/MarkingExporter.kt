package io.legado.app.help.bookmark

import android.net.Uri
import io.legado.app.data.entities.BookMarking
import io.legado.app.domain.model.TextProcessAnchor
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/** 划线/想法笔记 Markdown 导出（版式对齐 [BookmarkExporter]：# 书名 / 按章分组 / > 摘录）。 */
object MarkingExporter {

    fun formatToMarkdown(bookName: String, author: String?, markings: List<BookMarking>): String {
        val sb = StringBuilder()
        sb.append("# ").append(bookName).append('\n')
        author?.takeIf { it.isNotBlank() }?.let { sb.append("\n作者：").append(it).append('\n') }
        markings
            .sortedWith(compareBy({ it.chapterIndex ?: Int.MAX_VALUE }, { it.createdAt }))
            .mapNotNull { m ->
                val text = GSON.fromJsonObject<TextProcessAnchor>(m.anchorJson).getOrNull()
                    ?.selectedText?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                m to text
            }
            .groupBy { (m, _) ->
                m.chapterName.ifBlank { "第 ${(m.chapterIndex ?: 0) + 1} 章" }
            }
            .forEach { (chapterTitle, items) ->
                sb.append("\n## ").append(chapterTitle).append('\n')
                items.forEach { (m, text) ->
                    sb.append('\n')
                    text.split('\n').forEach { line -> sb.append("> ").append(line).append('\n') }
                    if (m.note.isNotBlank()) sb.append("\n想法：").append(m.note).append('\n')
                }
            }
        return sb.toString()
    }

    suspend fun exportToUri(uri: Uri, content: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            appCtx.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            } ?: return@withContext false
            true
        }.getOrDefault(false)
    }
}
