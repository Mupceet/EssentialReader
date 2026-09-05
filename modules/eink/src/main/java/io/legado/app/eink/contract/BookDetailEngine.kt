package io.legado.app.eink.contract


/**
 * [BookDetailEngine.prefetchChapters] 的结果。
 *
 * 宿主实现义务：预取是后台增强行为，**静默失败**——任何无法/无需
 * 预取的情况都返回 [Skipped]，不得抛异常。
 */
sealed interface BookDetailPrefetchResult {
    /** 无需/无法预取（本地书、无书源、目录已存在、静默失败）。 */
    data object Skipped : BookDetailPrefetchResult

    /**
     * 预取完成且书籍记录可能被详情/重定向更新：返回新的句柄与展示
     * 模型，模块以此替换本地持有的旧值。
     */
    data class Updated(val handle: BookHandle, val model: BookDetailUiModel) :
        BookDetailPrefetchResult
}

/**
 * 书籍详情端口：详情页的数据与书架操作来源。
 *
 * 职责边界：模块详情页 VM 保留加载/书架状态机与消息提示；宿主实现
 * 负责书籍查找链与目录预取管线。书籍的引擎身份经 [BookHandle] 在
 * 模块与端口之间流转——**记录可能被预取就地更新或重定向替换**，
 * 模块回传句柄而非 bookUrl 即为兼容此点。
 */
interface BookDetailEngine {

    /**
     * 按导航参数查找书籍：书架记录优先（书名 + 作者匹配），其次搜索
     * 结果记录。返回「句柄 + 展示模型」序对，找不到返回 null。
     */
    suspend fun findBook(
        name: String,
        author: String,
        bookUrl: String
    ): Pair<BookHandle, BookDetailUiModel>?

    /** 读取书籍当前展示数据（存储最新值；书籍已不存在返回 null）。 */
    suspend fun loadBookDetail(bookUrl: String): BookDetailUiModel?

    /** 是否在书架（未加书架的隐藏行视为不在）。 */
    suspend fun isBookInBookshelf(bookUrl: String): Boolean

    /**
     * 后台预取目录入库：缺书籍详情先拉详情；发生目录重定向时替换
     * 书架记录并迁移缓存；未加书架则落隐藏行。静默失败（见
     * [BookDetailPrefetchResult]）。
     *
     * @param inShelf 调用时刻的书架状态（宿主据此决定落库形态）。
     */
    suspend fun prefetchChapters(handle: BookHandle, inShelf: Boolean): BookDetailPrefetchResult

    /**
     * 将书籍加入书架（从未加书架的隐藏行转正；序号排到书架最前；
     * 与书架内同名书合并阅读进度）。返回是否成功。
     */
    suspend fun addToBookshelf(handle: BookHandle): Boolean

    /** 将书籍移出书架（退回隐藏行，不物理删除）。返回是否成功。 */
    suspend fun removeFromBookshelf(handle: BookHandle): Boolean
}
