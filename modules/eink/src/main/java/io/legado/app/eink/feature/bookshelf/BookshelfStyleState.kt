package io.legado.app.eink.feature.bookshelf

import io.legado.app.eink.contract.BookshelfStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 书架样式状态宿主：宿主快照流 + 面板乐观覆盖层，合并为对外唯一样式流。
 *
 * - [submit] 同步置覆盖：面板改动的渲染即时生效，不等待宿主落库；
 * - 覆盖在宿主快照追平（与覆盖同值）后清除：落库完成与流发射之间的
 *   空窗不回跳旧值；第二笔面板改动直接替换覆盖，中间快照不会误清。
 *
 * 快照流与覆盖流的合并是纯函数式（combine），快照本身不被本类修改。
 * 抽为独立类是为了在纯 JVM 测试中验证覆盖优先/追平清除语义
 * （AndroidViewModel 不可直测）。
 */
internal class BookshelfStyleState(
    snapshot: Flow<BookshelfStyle>,
    scope: CoroutineScope,
) {
    private val _override = MutableStateFlow<BookshelfStyle?>(null)

    /** 合并后的对外样式流：覆盖优先，追平后回到快照。 */
    val style: Flow<BookshelfStyle> = combine(snapshot, _override) { snap, override ->
        override ?: snap
    }

    init {
        scope.launch {
            snapshot.collect { snap ->
                if (_override.value == snap) _override.value = null
            }
        }
    }

    /** 提交面板改动：同步置覆盖，宿主落库由调用方另行发起。 */
    fun submit(style: BookshelfStyle) {
        _override.value = style
    }
}
