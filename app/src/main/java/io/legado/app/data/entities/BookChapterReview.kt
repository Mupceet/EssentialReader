package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.ColumnInfo
import kotlinx.parcelize.Parcelize

/**
 * 书籍章节段评实体，存储章节的段评（章节评论）信息
 */
@Parcelize
class BookChapterReview(
    // 书籍ID
    @ColumnInfo(defaultValue = "0")
    var bookId: Long = 0,
    // 章节ID
    var chapterId: Long = 0,
    // 段评汇总URL
    var summaryUrl: String = "",
): Parcelable {

}
