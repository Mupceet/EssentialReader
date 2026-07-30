package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.SearchKeyword
import kotlinx.coroutines.flow.Flow


/**
 * 搜索关键词数据访问对象，管理搜索历史关键词的增删改查。
 * 支持按使用频率和最近使用时间排序。
 */
@Dao
interface SearchKeywordDao {

    /** 获取所有搜索关键词 */
    @get:Query("SELECT * FROM search_keywords")
    val all: List<SearchKeyword>

    @Query("SELECT * FROM search_keywords ORDER BY usage DESC")
    fun flowByUsage(): Flow<List<SearchKeyword>>

    @Query("SELECT * FROM search_keywords ORDER BY lastUseTime DESC")
    fun flowByTime(): Flow<List<SearchKeyword>>

    @Query("SELECT * FROM search_keywords where word like '%'||:key||'%' ORDER BY usage DESC")
    fun flowSearch(key: String): Flow<List<SearchKeyword>>

    @Query("select * from search_keywords where word = :key")
    fun get(key: String): SearchKeyword?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg keywords: SearchKeyword)

    @Update
    fun update(vararg keywords: SearchKeyword)

    @Delete
    fun delete(vararg keywords: SearchKeyword)

    @Query("DELETE FROM search_keywords")
    fun deleteAll()

}