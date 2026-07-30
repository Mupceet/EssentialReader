package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordShow

/**
 * 阅读记录数据访问对象，管理阅读时长记录的增删改查。
 * 支持按书籍名称汇总阅读时长，以及按设备ID查询阅读时间。
 */
@Dao
interface ReadRecordDao {

    /** 获取所有阅读记录 */
    @get:Query("select * from readRecord")
    val all: List<ReadRecord>

    @get:Query(
        """
        select bookName, sum(readTime) as readTime, max(lastRead) as lastRead 
        from readRecord 
        group by bookName 
        order by bookName collate localized"""
    )
    val allShow: List<ReadRecordShow>

    @get:Query("select sum(readTime) from readRecord")
    val allTime: Long

    @Query(
        """
        select bookName, sum(readTime) as readTime, max(lastRead) as lastRead 
        from readRecord 
        where bookName like '%' || :searchKey || '%'
        group by bookName 
        order by bookName collate localized"""
    )
    fun search(searchKey: String): List<ReadRecordShow>

    @Query("select sum(readTime) from readRecord where bookName = :bookName")
    fun getReadTime(bookName: String): Long?

    @Query("select readTime from readRecord where deviceId = :androidId and bookName = :bookName")
    fun getReadTime(androidId: String, bookName: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg readRecord: ReadRecord)

    @Update
    fun update(vararg record: ReadRecord)

    @Delete
    fun delete(vararg record: ReadRecord)

    @Query("delete from readRecord")
    fun clear()

    @Query("delete from readRecord where bookName = :bookName")
    fun deleteByName(bookName: String)
}