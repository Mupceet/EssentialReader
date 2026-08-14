package io.legado.app.eink.arch

import androidx.compose.runtime.Immutable

/**
 * E-Ink UI 架构约定。
 *
 * 参考 JBusDriver 的 UDF 最佳实践，适配 E-Ink 场景。
 *
 * ## UiState 设计规范
 *
 * 每个屏幕的 ViewModel 暴露一个单一的 `StateFlow<XxxUiState>`，
 * UiState 遵循以下规则:
 *
 * 1. **扁平布尔标志位**（而非 sealed class）—— 因为 E-Ink 上 UI 可能同时处于
 *    "有内容 + 正在后台刷新"，sealed class 的互斥状态无法表达。
 *
 * 2. **error 字段为 Int?（string resource id）**—— ViewModel 永不持有本地化字符串。
 *
 * 3. **派生属性用 `val ... get()`**—— 不在 state 里重复存储派生数据。
 *
 * 4. **UiModel 标注 @Immutable**—— 帮助 Compose 稳定推断，减少不必要的重组。
 *
 * 5. **lastUpdatedAtMillis**—— 记录缓存时间戳，驱动刷新决策。
 *
 * 示例:
 * ```
 * data class BookshelfUiState(
 *     val books: List<BookUiModel> = emptyList(),
 *     val isLoading: Boolean = false,
 *     val isRefreshing: Boolean = false,
 *     val error: Int? = null,
 *     val lastUpdatedAtMillis: Long? = null,
 * ) {
 *     val isEmpty: Boolean get() = books.isEmpty() && !isLoading
 * }
 * ```
 *
 * ## Route + Screen 双层分离
 *
 * 每个屏幕拆成两个 Composable:
 *
 * - **`XxxRoute`**: 感知 ViewModel，负责 `viewModel()`、`collectAsStateWithLifecycle()`、
 *   `LaunchedEffect` 调用意图方法。处理一次性事件（SharedFlow）。
 *
 * - **`XxxScreen`**: **纯函数**，只接收 `state: XxxUiState` + 回调 `onXxx: () -> Unit`，
 *   完全无 ViewModel 依赖，可 Preview、可测试。
 *
 * 示例:
 * ```
 * @Composable
 * fun HomeRoute(viewModel: BookshelfViewModel = viewModel()) {
 *     val uiState by viewModel.uiState.collectAsStateWithLifecycle()
 *     BookshelfScreen(
 *         state = uiState,
 *         onBookClick = { bookUrl -> viewModel.openBook(bookUrl) },
 *         onRefresh = viewModel::refresh,
 *     )
 * }
 *
 * @Composable
 * fun BookshelfScreen(state: BookshelfUiState, onBookClick: (String) -> Unit, onRefresh: () -> Unit) {
 *     // 纯渲染，无 ViewModel 依赖
 * }
 * ```
 *
 * ## 渲染优先级 when 分支
 *
 * E-Ink 屏上错误不应清空已加载内容:
 * ```
 * when {
 *     state.isLoading && state.books.isEmpty() -> EInkLoading()
 *     state.error != null && state.books.isEmpty() -> ErrorView(...)
 *     else -> BookList(state.books, onBookClick)
 * }
 * ```
 *
 * ## StateReducer 纯函数模式
 *
 * 状态转换逻辑抽成 `internal fun XxxUiState.applyXxx(...): XxxUiState` 纯函数，
 * 放在单独的 `XxxStateReducers.kt` 文件中，可单元测试:
 *
 * ```
 * internal fun BookshelfUiState.applyBooks(books: List<BookUiModel>): BookshelfUiState =
 *     copy(books = books, isLoading = false, error = null, lastUpdatedAtMillis = System.currentTimeMillis())
 * ```
 */

/**
 * 标注 UI 数据模型为 Immutable。
 * 帮助 Compose 编译器进行稳定推断，减少不必要的重组（E-Ink 上重组成本极高）。
 */
@Immutable
annotation class EinkImmutable
