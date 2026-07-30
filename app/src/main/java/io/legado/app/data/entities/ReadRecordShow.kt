package io.legado.app.data.entities

/**
 * 阅读记录展示数据类，用于UI层展示设备的阅读时间和最后阅读时间
 */
data class ReadRecordShow(
    // 书名
    var bookName: String,
    // 阅读时长
    var readTime: Long,
    // 最后阅读时间
    var lastRead: Long
)