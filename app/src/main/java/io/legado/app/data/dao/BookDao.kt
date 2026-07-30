package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isNotShelf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
interface BookDao {

    fun flowByGroup(groupId: Long, sumGroupId: Long, hideNetNone: Boolean): Flow<List<Book>> {
        return when (groupId) {
            BookGroup.IdRoot -> flowRoot().map { list ->
                list.filter { book ->
                    book.type and BookType.text > 0
                            && book.type and BookType.local == 0
                            && (sumGroupId and book.group) == 0L
                            && hideNetNone
                            && !book.isNotShelf
                }
            }
            BookGroup.IdAll -> flowAll().map { list -> list.filterNot { it.isNotShelf } }
            BookGroup.IdLocal -> flowLocal().map { list -> list.filterNot { it.isNotShelf } }
            BookGroup.IdAudio -> flowAudio().map { list -> list.filterNot { it.isNotShelf } }
            BookGroup.IdNetNone -> {
                flowNetNoGroup().map { list ->
                    list.filter { book ->
                        (sumGroupId and book.group) == 0L && !book.isNotShelf
                    }
                }
            }
            BookGroup.IdLocalNone -> {
                flowLocalNoGroup().map { list ->
                    list.filter { book ->
                        (sumGroupId and book.group) == 0L && !book.isNotShelf
                    }
                }
            }
            BookGroup.IdError -> flowUpdateError().map { list -> list.filterNot { it.isNotShelf } }
            else -> flowByUserGroup(groupId).map { list -> list.filterNot { it.isNotShelf } }
        }
    }

    @Query(
        """select * from books where type & ${BookType.text} > 0 and type & ${BookType.local} = 0"""
    )
    fun flowRoot(): Flow<List<Book>>

    @Query("SELECT * FROM books order by durChapterTime desc")
    fun flowAll(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE type & ${BookType.audio} > 0")
    fun flowAudio(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE type & ${BookType.local} > 0")
    fun flowLocal(): Flow<List<Book>>

    @Query(
        """select * from books where type & ${BookType.audio} = 0 and type & ${BookType.local} = 0"""
    )
    fun flowNetNoGroup(): Flow<List<Book>>

    @Query(
        """select * from books where type & ${BookType.local} > 0"""
    )
    fun flowLocalNoGroup(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE (`group` & :group) > 0")
    fun flowByUserGroup(group: Long): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE name like '%'||:key||'%' or author like '%'||:key||'%'")
    fun flowSearch(key: String): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE tags & :tagId > 0")
    fun flowSearchByTag(tagId: Long): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE tags = 0")
    fun flowSearchByNoTag(): Flow<List<Book>>

    @Query("SELECT * FROM books where type & ${BookType.updateError} > 0 order by durChapterTime desc")
    fun flowUpdateError(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE (`group` & :group) > 0")
    fun getBooksByGroup(group: Long): List<Book>

    @Query("SELECT * FROM books WHERE `name` in (:names)")
    fun findByName(vararg names: String): List<Book>

    @Query("select * from books where originName = :fileName")
    fun getBookByFileName(fileName: String): Book?

    @Query("SELECT * FROM books WHERE bookUrl = :bookUrl")
    fun getBook(bookUrl: String): Book?

    @Query("SELECT * FROM books WHERE name = :name and author = :author")
    fun getBook(name: String, author: String): Book?

    @Query("""select distinct bs.* from books, book_sources bs 
        where origin == bookSourceUrl and origin not like '${BookType.localTag}%' 
        and origin not like '${BookType.webDavTag}%'""")
    fun getAllUseBookSource(): List<BookSource>

    @Query("SELECT * FROM books WHERE name = :name and origin = :origin")
    fun getBookByOrigin(name: String, origin: String): Book?

    @get:Query("select count(bookUrl) from books where (SELECT sum(groupId) FROM book_groups)")
    val noGroupSize: Int

    @get:Query("SELECT * FROM books where type & ${BookType.local} = 0")
    val webBooks: List<Book>

    @get:Query("SELECT * FROM books where type & ${BookType.local} = 0 and canUpdate = 1")
    val hasUpdateBooks: List<Book>

    @get:Query("SELECT * FROM books")
    val all: List<Book>

    @Query("SELECT * FROM books where type & :type > 0 and type & ${BookType.local} = 0")
    fun getByTypeOnLine(type: Int): List<Book>

    @get:Query("SELECT * FROM books where type & ${BookType.text} > 0 ORDER BY durChapterTime DESC limit 1")
    val lastReadBook: Book?

    @get:Query("SELECT bookUrl FROM books")
    val allBookUrls: List<String>

    @get:Query("SELECT COUNT(*) FROM books")
    val allBookCount: Int

    @get:Query("select min(`order`) from books")
    val minOrder: Int

    @get:Query("select max(`order`) from books")
    val maxOrder: Int

    @Query("select exists(select 1 from books where bookUrl = :bookUrl)")
    fun has(bookUrl: String): Boolean

    @Query("select exists(select 1 from books where name = :name and author = :author)")
    fun has(name: String, author: String): Boolean

    @Query(
        """select exists(select 1 from books where type & ${BookType.local} > 0 
        and (originName = :fileName or (origin != '${BookType.localTag}' and origin like '%' || :fileName)))"""
    )
    fun hasFile(fileName: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg book: Book)

    @Update
    fun update(vararg book: Book)

    @Delete
    fun delete(vararg book: Book)

    @Transaction
    fun replace(oldBook: Book, newBook: Book) {
        delete(oldBook)
        insert(newBook)
    }

    @Query("update books set durChapterPos = :pos where bookUrl = :bookUrl")
    fun upProgress(bookUrl: String, pos: Int)

    @Query("update books set `group` = :newGroupId where `group` = :oldGroupId")
    fun upGroup(oldGroupId: Long, newGroupId: Long)

    @Query("update books set `group` = `group` - :group where `group` & :group > 0")
    fun removeGroup(group: Long)

    @Query("delete from books where type & ${BookType.notShelf} > 0")
    fun deleteNotShelfBook()
}