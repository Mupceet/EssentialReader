package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.HttpTTS
import kotlinx.coroutines.flow.Flow

/**
 * HTTP TTS朗读引擎数据访问对象，管理自定义TTS引擎的增删改查。
 * 支持按名称排序和默认引擎的清理。
 */
@Dao
interface HttpTTSDao {

    /** 获取所有TTS引擎，按名称排序 */
    @get:Query("select * from httpTTS order by name")
    val all: List<HttpTTS>

    @Query("select * from httpTTS order by name")
    fun flowAll(): Flow<List<HttpTTS>>

    @get:Query("select count(*) from httpTTS")
    val count: Int

    @Query("select * from httpTTS where id = :id")
    fun get(id: Long): HttpTTS?

    @Query("select name from httpTTS where id = :id")
    fun getName(id: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg httpTTS: HttpTTS)

    @Delete
    fun delete(vararg httpTTS: HttpTTS)

    @Update
    fun update(vararg httpTTS: HttpTTS)

    @Query("delete from httpTTS where id < 0")
    fun deleteDefault()
}