package io.legado.app.eink.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * E-Ink 导航控制器。
 *
 * 管理屏幕栈，页面切换通过直接替换状态完成
 * （规范 §12, §44: 页面 transition 统一为 NONE，无动画过渡）。
 *
 * 每个栈条目持有独立的 [ViewModelStore] 与唯一 id：
 *  - push 新屏幕 → 新建 store（再次进入某界面即"首次进入"，状态全新）；
 *  - pop 返回 → 复用原 store（返回时保留界面状态，如搜索结果）；
 *  - 条目出栈 → clear store（触发 ViewModel.onCleared）。
 *
 * 配合 [EInkApp] 中按 entryId 提供 ViewModelStoreOwner 与 SaveableStateProvider，
 * 实现"退出界面再进入 == 首次进入"（如搜索界面）。
 */
class EInkNavController internal constructor(
    initialStack: List<EInkScreen>,
) {
    private data class Entry(
        val id: Long,
        val screen: EInkScreen,
        val viewModelStore: ViewModelStore,
    )

    init {
        require(initialStack.isNotEmpty()) { "initialStack must not be empty" }
    }

    private var backStack: MutableList<Entry> = initialStack.mapIndexed { index, screen ->
        Entry(index.toLong(), screen, ViewModelStore())
    }.toMutableList()
    private var nextEntryId = backStack.size.toLong()
    private var current by mutableStateOf(backStack.last())

    /** 当前屏幕。 */
    val screen: EInkScreen
        get() = current.screen

    /** 当前栈条目的唯一 id（用于按条目保留 saveable 状态）。 */
    val currentEntryId: Long
        get() = current.id

    /** 当前栈条目的 ViewModelStore（UI 层按条目作用域解析 ViewModel）。 */
    val currentViewModelStore: ViewModelStore
        get() = current.viewModelStore

    val canPop: Boolean
        get() = backStack.size > 1

    fun navigate(screen: EInkScreen) {
        val entry = Entry(nextEntryId++, screen, ViewModelStore())
        backStack.add(entry)
        current = entry
    }

    fun navigateAndClear(screen: EInkScreen) {
        backStack.forEach { it.viewModelStore.clear() }
        backStack.clear()
        val entry = Entry(nextEntryId++, screen, ViewModelStore())
        backStack.add(entry)
        current = entry
    }

    fun pop(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex).viewModelStore.clear()
        current = backStack.last()
        return true
    }

    /**
     * 用 [screen] 替换栈顶：当前屏幕出栈、新屏幕入栈。
     *
     * 用于"目录页选章进入阅读页"等中间页场景：离开中间页的同时
     * 从返回堆栈移除自身（返回时回到中间页的上一级）。
     */
    fun replaceTop(screen: EInkScreen) {
        if (backStack.size <= 1) {
            navigate(screen)
            return
        }
        backStack.removeAt(backStack.lastIndex).viewModelStore.clear()
        val entry = Entry(nextEntryId++, screen, ViewModelStore())
        backStack.add(entry)
        current = entry
    }

    /** 清空全部条目（释放各条目 ViewModelStore）：持有方 ViewModel.onCleared 时调用。 */
    internal fun clearAll() {
        backStack.forEach { it.viewModelStore.clear() }
        backStack.clear()
    }

    companion object {
        /**
         * 创建并记住一个 [EInkNavController]。
         *
         * 控制器由 Activity 级 [EInkNavViewModel] 持有：栈与各条目的
         * ViewModelStore 跨 Activity recreate 存活（字体缩放等 attach 时
         * 设置的应用走 recreate，用户停留在当前界面而非被弹回首页）；
         * 进程死亡仍回初始栈。[initialStack] 仅首次创建时生效。
         *
         * @param initialStack 初始屏幕栈（至少一项）；多于一项时用于
         * 冷启动直达（如自动跳转最近阅读：书架之上叠阅读页）
         */
        @Composable
        fun remember(initialStack: List<EInkScreen> = listOf(EInkScreen.Home)): EInkNavController {
            // 求值处位于 EInkApp 的每条目 LocalViewModelStoreOwner 覆盖之外，
            // viewModel() 解析到 Activity 级 store，即 recreate 存活的作用域
            val holder: EInkNavViewModel = viewModel()
            return remember { holder.getOrCreate(initialStack) }
        }
    }
}

/** [EInkNavController] 的 Activity 级持有者（见 [EInkNavController.Companion.remember]）。 */
internal class EInkNavViewModel : ViewModel() {
    var controller: EInkNavController? = null
        private set

    fun getOrCreate(initialStack: List<EInkScreen>): EInkNavController =
        controller ?: EInkNavController(initialStack).also { controller = it }

    override fun onCleared() {
        controller?.clearAll()
        controller = null
    }
}