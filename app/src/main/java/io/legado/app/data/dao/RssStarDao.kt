package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.RssStar
import kotlinx.coroutines.flow.Flow

/**
 * RSS收藏数据访问对象，管理RSS文章收藏的增删改查。
 * 支持按分组、来源和链接进行收藏管理。
 */
@Dao
interface RssStarDao {

    /** 获取所有收藏，按收藏时间倒序 */
    @get:Query("select * from rssStars order by starTime desc")
    val all: List<RssStar>

    @Query("select `group` from rssStars group by `group` order by `group`")
    fun flowGroups(): Flow<List<String>>

    @Query("select * from rssStars where `group` = :group order by starTime desc")
    fun flowByGroup(group: String): Flow<List<RssStar>>

    @Query("select * from rssStars where origin = :origin and link = :link")
    fun get(origin: String, link: String): RssStar?

    @Query("select * from rssStars order by starTime desc")
    fun liveAll(): Flow<List<RssStar>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg rssStar: RssStar)

    @Update
    fun update(vararg rssStar: RssStar)

    @Query("update rssStars set origin = :origin where origin = :oldOrigin")
    fun updateOrigin(origin: String, oldOrigin: String)

    @Query("delete from rssStars where origin = :origin")
    fun delete(origin: String)

    @Query("delete from rssStars where origin = :origin and link = :link")
    fun delete(origin: String, link: String)

    @Query("delete from rssStars where `group` = :group")
    fun deleteByGroup(group: String)

    @Query("delete from rssStars")
    fun deleteAll()
}