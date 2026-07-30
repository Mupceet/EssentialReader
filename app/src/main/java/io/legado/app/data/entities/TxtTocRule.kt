package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey


/**
 * TXT目录规则实体，定义TXT文本文件自动识别章节标题的正则规则
 */
@Entity(tableName = "txtTocRules")
data class TxtTocRule(
    // 规则ID
    @PrimaryKey
    var id: Long = System.currentTimeMillis(),
    // 规则名称
    var name: String = "",
    // 正则规则
    var rule: String = "",
    // 示例
    var example: String? = null,
    // 序号
    var serialNumber: Int = -1,
    // 是否启用
    var enable: Boolean = true
) {

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other is TxtTocRule) {
            return id == other.id
        }
        return false
    }

}