package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 规则订阅实体，存储书源/RSS源等规则的远程订阅信息
 */
@Entity(tableName = "ruleSubs")
data class RuleSub(
    // 订阅ID
    @PrimaryKey
    val id: Long = System.currentTimeMillis(),
    // 订阅名称
    var name: String = "",
    // 订阅URL
    var url: String = "",
    // 订阅类型（0:书源, 1:RSS源等）
    var type: Int = 0,
    // 自定义排序
    var customOrder: Int = 0,
    // 是否自动更新
    var autoUpdate: Boolean = false,
    // 更新时间
    var update: Long = System.currentTimeMillis()
)
