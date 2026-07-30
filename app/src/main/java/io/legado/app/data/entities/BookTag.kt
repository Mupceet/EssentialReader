package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(tableName = "book_tags")
data class BookTag(
    @PrimaryKey
    val tagId: Long = 0b1,
    var name: String = "",
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
