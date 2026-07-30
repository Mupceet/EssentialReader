package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.RssReadRecord

/**
 * RSS阅读记录数据访问对象，管理RSS文章已读记录的存储和查询。
 */
@Dao
interface RssReadRecordDao {

    /** 插入阅读记录（忽略重复） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertRecord(vararg rssReadRecord: RssReadRecord)

    @Query("select * from rssReadRecords order by readTime desc")
    fun getRecords(): List<RssReadRecord>

    @get:Query("select count(1) from rssReadRecords")
    val countRecords: Int

    @Query("delete from rssReadRecords")
    fun deleteAllRecord()

}