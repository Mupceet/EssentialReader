package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.TxtTocRule
import kotlinx.coroutines.flow.Flow

/**
 * TXT目录规则数据访问对象，管理TXT文件目录识别规则的增删改查。
 * 支持按启用状态筛选和排序号管理。
 */
@Dao
interface TxtTocRuleDao {

    /** 观察所有TXT目录规则的数据流，按序号排列 */
    @Query("select * from txtTocRules order by serialNumber")
    fun observeAll(): Flow<List<TxtTocRule>>

    @get:Query("select * from txtTocRules order by serialNumber")
    val all: List<TxtTocRule>

    @get:Query("select * from txtTocRules where enable = 1 order by serialNumber")
    val enabled: List<TxtTocRule>

    @get:Query("select * from txtTocRules where enable != 1 order by serialNumber")
    val disabled: List<TxtTocRule>

    @get:Query("select count(*) from txtTocRules")
    val count: Int

    @Query("select * from txtTocRules where id = :id")
    fun get(id: Long): TxtTocRule?

    @get:Query("select ifNull(min(serialNumber), 0) from txtTocRules")
    val minOrder: Int

    @get:Query("select ifNull(max(serialNumber), 0) from txtTocRules")
    val maxOrder: Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg rule: TxtTocRule)

    @Update(onConflict = OnConflictStrategy.REPLACE)
    fun update(vararg rule: TxtTocRule)

    @Delete
    fun delete(vararg rule: TxtTocRule)

    @Query("delete from txtTocRules where id < 0")
    fun deleteDefault()
}