package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * 书籍标签实体，用于书籍的标签分类管理
 */
@Parcelize
@Entity(tableName = "book_tags")
data class BookTag(
    // 标签ID（使用位掩码标识）
    @PrimaryKey
    val tagId: Long = 0b1,
    // 标签名称
    var name: String = "",
    // 排序顺序
    var order: Int = 0
) : Parcelable {

    override fun hashCode(): Int {
        return tagId.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other is BookTag) {
            return other.tagId == tagId
                    && other.name == name
                    && other.order == order
        }
        return false
    }
}
