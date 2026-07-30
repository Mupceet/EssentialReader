package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * 阅读记录实体，用于WebDav等同步场景记录设备上的阅读时间
 */
@Entity(tableName = "readRecord", primaryKeys = ["deviceId", "bookName"])
data class ReadRecord(
    // 设备ID
    var deviceId: String = "",
    // 书名
    var bookName: String = "",
    // 阅读时长（秒）
    @ColumnInfo(defaultValue = "0")
    var readTime: Long = 0L,
    // 最后阅读时间
    @ColumnInfo(defaultValue = "0")
    var lastRead: Long = System.currentTimeMillis()
)