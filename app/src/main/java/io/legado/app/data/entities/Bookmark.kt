package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * 书签实体，存储书籍阅读位置的书签信息
 */
@Parcelize
@Entity(
    tableName = "bookmarks",
    indices = [(Index(value = ["bookName", "bookAuthor"], unique = false))]
)
data class Bookmark(
    // 书签创建时间
    @PrimaryKey
    val time: Long = System.currentTimeMillis(),
    // 书名
    val bookName: String = "",
    // 作者
    val bookAuthor: String = "",
    // 章节索引
    var chapterIndex: Int = 0,
    // 章节内位置
    var chapterPos: Int = 0,
    // 章节名称
    var chapterName: String = "",
    // 选中文本
    var bookText: String = "",
    // 书签备注
    var content: String = ""
) : Parcelable