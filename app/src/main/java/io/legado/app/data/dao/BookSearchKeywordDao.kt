package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.BookSearchKeyword
import kotlinx.coroutines.flow.Flow

@Dao
interface BookSearchKeywordDao {

    @get:Query("SELECT * FROM book_search_keywords")
    val all: List<BookSearchKeyword>

    @Query("SELECT * FROM book_search_keywords ORDER BY usage DESC")
    fun flowByUsage(): Flow<List<BookSearchKeyword>>

    @Query("SELECT * FROM book_search_keywords ORDER BY lastUseTime DESC")
    fun flowByTime(): Flow<List<BookSearchKeyword>>

    @Query("SELECT * FROM book_search_keywords where word like '%'||:key||'%' ORDER BY usage DESC")
    fun flowSearch(key: String): Flow<List<BookSearchKeyword>>

    @Query("select * from book_search_keywords where word = :key")
    fun get(key: String): BookSearchKeyword?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg keywords: BookSearchKeyword)

    @Update
    fun update(vararg keywords: BookSearchKeyword)

    @Delete
    fun delete(vararg keywords: BookSearchKeyword)

    @Query("DELETE FROM book_search_keywords")
    fun deleteAll()
}