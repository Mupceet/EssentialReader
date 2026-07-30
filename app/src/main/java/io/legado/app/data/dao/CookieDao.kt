package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.Cookie

/**
 * Cookie数据访问对象，管理HTTP Cookie的存储和查询。
 * 支持按URL获取Cookie，以及OkHttp格式Cookie的批量操作。
 */
@Dao
interface CookieDao {

    /** 根据URL获取对应的Cookie */
    @Query("SELECT * FROM cookies Where url = :url")
    fun get(url: String): Cookie?

    @Query("select * from cookies where url like '%|%'")
    fun getOkHttpCookies(): List<Cookie>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg cookie: Cookie)

    @Update
    fun update(vararg cookie: Cookie)

    @Query("delete from cookies where url = :url")
    fun delete(url: String)

    @Query("delete from cookies where url like '%|%'")
    fun deleteOkHttp()
}