package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import kotlin.coroutines.coroutineContext

/**
 * 字典规则实体，定义在线字典查询的URL和内容解析规则
 */
@Entity(tableName = "dictRules")
data class DictRule(
    // 字典名称
    @PrimaryKey
    var name: String = "",
    // 查询URL规则，{key}会被替换为查询关键词
    var urlRule: String = "",
    // 结果提取规则
    var showRule: String = "",
    // 是否启用
    @ColumnInfo(defaultValue = "1")
    var enabled: Boolean = true,
    // 排序号
    @ColumnInfo(defaultValue = "0")
    var sortNumber: Int = 0
) {

    override fun hashCode(): Int {
        return name.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other is DictRule) {
            return name == other.name
        }
        return false
    }

    /**
     * 搜索字典
     */
    suspend fun search(word: String): String {
        val analyzeUrl = AnalyzeUrl(urlRule, key = word, coroutineContext = coroutineContext)
        val body = analyzeUrl.getStrResponseAwait().body
        if (showRule.isBlank()) {
            return body!!
        }
        val analyzeRule = AnalyzeRule().setCoroutineContext(coroutineContext)
        return analyzeRule.getString(showRule, mContent = body)
    }

}