package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import kotlinx.parcelize.Parcelize


/**
 * 键盘辅助实体，存储阅读器底部快捷操作的键值配置
 */
@Parcelize
@Entity(tableName = "keyboardAssists", primaryKeys = ["type", "key"])
data class KeyboardAssist(
    // 辅助类型（0:替换, 1:快捷操作等）
    @ColumnInfo(defaultValue = "0")
    var type: Int = 0,
    // 快捷键标识
    @ColumnInfo(defaultValue = "")
    var key: String,
    // 对应值
    @ColumnInfo(defaultValue = "")
    var value: String,
    // 序号
    @ColumnInfo(defaultValue = "0")
    var serialNo: Int = 0
) : Parcelable