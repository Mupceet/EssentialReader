package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cookie实体，存储网站登录后的Cookie信息
 */
@Entity(tableName = "cookies", indices = [(Index(value = ["url"], unique = true))])
data class Cookie(
    // 网站URL
    @PrimaryKey
    var url: String = "",
    // Cookie内容
    var cookie: String = ""
)