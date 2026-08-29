package io.legado.app.eink.engine

import io.legado.app.eink.feature.changesource.ChangeSourceBookUiModel
import io.legado.app.eink.feature.changesource.ChangeSourceResultUiModel

/**
 * 换源端口。
 *
 * VM 保留并发搜索编排（信号量、超时、按到达顺序追加、去重）；单源搜索
 * 与换源迁移管线（拉详情/目录 → migrateTo 进度迁移 → 记录替换 → 重载
 * 阅读会话）下沉桥接层。
 */
interface ChangeSourceEngine {

    /** 当前阅读会话书籍优先，其次 DB（换源页入口）。 */
    suspend fun currentReadingBook(bookUrl: String): Pair<BookHandle, ChangeSourceBookUiModel>?

    /** 全部启用书源（已过滤空地址）。 */
    fun enabledSources(): List<SourceHandle>

    /**
     * 在指定书源搜索书籍：作者清洗（authorRegex）与上游 filter 签名差异
     * 由桥接层吸收；结果已 releaseHtmlData 并映射为 UiModel。
     */
    suspend fun searchSourceBook(
        source: SourceHandle,
        name: String,
        author: String,
        checkAuthor: Boolean,
    ): List<ChangeSourceResultUiModel>

    /**
     * 应用换源：获取目录 → 迁移进度 → 替换记录 → 重载引擎阅读会话。
     * 成功返回新书句柄；失败返回 [Result.failure]（VM 负责消息提示）。
     */
    suspend fun changeBookSource(
        bookHandle: BookHandle,
        result: ChangeSourceResultUiModel,
    ): Result<BookHandle>
}
