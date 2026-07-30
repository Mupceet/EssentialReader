package io.legado.app.data.entities

/**
 * 书籍阅读进度数据类，用于备份/恢复阅读进度信息
 */
data class BookProgress(
    // 书名
    val name: String,
    // 作者
    val author: String,
    // 当前章节索引
    val durChapterIndex: Int,
    // 当前章节内阅读位置
    val durChapterPos: Int,
    // 最近阅读时间
    val durChapterTime: Long,
    // 当前章节标题
    val durChapterTitle: String?
) {

    constructor(book: Book) : this(
        name = book.name,
        author = book.author,
        durChapterIndex = book.durChapterIndex,
        durChapterPos = book.durChapterPos,
        durChapterTime = book.durChapterTime,
        durChapterTitle = book.durChapterTitle
    )

}