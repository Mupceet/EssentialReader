package io.legado.app.eink.engine

import io.legado.app.eink.feature.bookdetail.BookDetailUiModel

/** 详情页目录预取结果。 */
sealed interface PrefetchResult {
    /** 无需/无法预取（本地书、无书源、已有目录、静默失败）。 */
    data object Skipped : PrefetchResult

    /** 预取完成：书籍可能被详情/重定向更新，返回新句柄与展示模型。 */
    data class Updated(val handle: BookHandle, val model: BookDetailUiModel) : PrefetchResult
}

/**
 * 书籍详情端口。
 *
 * 书籍查找链（书架 name/author → bookUrl → 搜索记录）与目录预取管线
 * 是引擎侧编排，下沉桥接层；VM 保留加载/书架状态机与消息。引擎身份经
 * [BookHandle] 在 VM 与端口间流转（实体可能被预取就地更新/重定向替换）。
 */
interface BookDetailEngine {

    /** 按导航参数查找书籍（书架优先，其次搜索记录），找不到返回 null。 */
    suspend fun findBook(name: String, author: String, bookUrl: String): Pair<BookHandle, BookDetailUiModel>?

    /** 读取书籍当前展示数据（DB 最新，null = 已不存在）。 */
    suspend fun loadBookDetail(bookUrl: String): BookDetailUiModel?

    /** 是否在书架（notShelf 隐藏行视为不在）。 */
    suspend fun isBookInBookshelf(bookUrl: String): Boolean

    /**
     * 后台预取目录入库（缺详情先拉详情；重定向替换书架记录并迁移缓存；
     * 未加书架落 notShelf 行）。静默失败。
     */
    suspend fun prefetchChapters(handle: BookHandle, inShelf: Boolean): PrefetchResult

    /** 加入书架（序号 minOrder-1、合并同名书进度），返回是否成功。 */
    suspend fun addToBookshelf(handle: BookHandle): Boolean

    /** 移出书架（标记 notShelf），返回是否成功。 */
    suspend fun removeFromBookshelf(handle: BookHandle): Boolean
}
