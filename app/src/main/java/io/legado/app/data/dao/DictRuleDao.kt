package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.DictRule
import kotlinx.coroutines.flow.Flow


/**
 * 字典规则数据访问对象，管理文本替换/字典规则的增删改查。
 * 支持按排序号排列和启用状态筛选。
 */
@Dao
interface DictRuleDao {

    /** 获取所有字典规则，按排序号排列 */
    @get:Query("select * from dictRules order by sortNumber")
    val all: List<DictRule>

    @get:Query("select * from dictRules where enabled = 1 order by sortNumber")
    val enabled: List<DictRule>

    @Query("select * from dictRules order by sortNumber")
    fun flowAll(): Flow<List<DictRule>>

    @Query("select * from dictRules where name = :name")
    fun getByName(name: String): DictRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg dictRule: DictRule)

    @Update
    fun update(vararg dictRule: DictRule)

    @Delete
    fun delete(vararg dictRule: DictRule)

}