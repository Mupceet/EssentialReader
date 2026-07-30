package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 缓存实体，存储应用内的键值对缓存数据，支持过期时间
 */
@Entity(tableName = "caches", indices = [(Index(value = ["key"], unique = true))])
data class Cache(
    // 缓存键
    @PrimaryKey
    val key: String = "",
    // 缓存值
    var value: String? = null,
    // 过期时间戳
    var deadline: Long = 0L
)