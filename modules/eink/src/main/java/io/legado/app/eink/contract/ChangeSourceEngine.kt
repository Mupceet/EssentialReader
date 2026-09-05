package io.legado.app.eink.contract

import kotlinx.coroutines.flow.SharedFlow

/**
 * 换源端口：跨书源搜索与换源迁移。
 *
 * 职责边界：模块换源页 VM 保留并发搜索编排（信号量限流、超时、按
 * 到达顺序追加、按 [ChangeSourceResultUiModel.deduplicationKey] 去重）；
 * 宿主实现负责单源搜索、以及换源应用的完整管线（拉详情/目录 →
 * 迁移阅读进度 → 替换书籍记录 → 重载阅读会话）。
 */
interface ChangeSourceEngine {

    /**
     * 换源成功事件：发射**新书**的 bookUrl（此刻旧记录已删除、阅读
     * 会话已重载为新源）。
     *
     * 宿主实现义务：在 [changeBookSource] 成功、引擎会话重载完成后
     * 发射。仍展示该书且位于导航栈下方的界面（如详情页）订阅本流，
     * 按新 bookUrl 跟随刷新，返回时即见新源数据。
     */
    val bookChanged: SharedFlow<String>

    /**
     * 读取换源页的当前书籍：正在阅读的会话书优先，否则按 bookUrl
     * 从存储读取。返回「句柄 + 展示模型」序对，找不到返回 null。
     */
    suspend fun currentReadingBook(bookUrl: String): Pair<BookHandle, ChangeSourceBookUiModel>?

    /** 全部启用书源（已过滤无效地址；模块按并发上限逐源搜索）。 */
    fun enabledSources(): List<SourceHandle>

    /**
     * 在指定书源搜索书籍。
     *
     * 宿主实现义务：作者名按书源规则清洗后再参与匹配（书源的
     * authorRegex）；[checkAuthor] 为 true 时结果作者须与原书一致，
     * 不一致的书目直接过滤。返回结果已映射为展示模型。
     */
    suspend fun searchSourceBook(
        source: SourceHandle,
        name: String,
        author: String,
        checkAuthor: Boolean,
    ): List<ChangeSourceResultUiModel>

    /**
     * 应用换源：拉取新源详情与目录 → 迁移阅读进度 → 替换书籍记录
     * （删除旧记录）→ 重载引擎阅读会话 → 发射 [bookChanged]。
     *
     * @return 成功：新书记录的句柄；失败：[Result.failure]（模块负责
     *   错误提示，异常信息面向用户可读）。
     */
    suspend fun changeBookSource(
        bookHandle: BookHandle,
        result: ChangeSourceResultUiModel,
    ): Result<BookHandle>
}
