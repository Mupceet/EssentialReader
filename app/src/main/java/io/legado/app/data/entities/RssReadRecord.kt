package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * RSS阅读记录实体，存储RSS文章的阅读状态
 */
@Entity(tableName = "rssReadRecords")
data class RssReadRecord(
    // 记录标识（文章链接MD5等）
    @PrimaryKey
    val record: String,
    // 文章标题
    val title: String? = null,
    // 阅读时间
    val readTime: Long? = null,
    // 是否已读
    val read: Boolean = true
)