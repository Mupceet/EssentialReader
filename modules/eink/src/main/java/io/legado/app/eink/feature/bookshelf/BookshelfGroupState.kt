package io.legado.app.eink.feature.bookshelf

import io.legado.app.eink.contract.BookshelfGroupIds
import io.legado.app.eink.contract.BookshelfGroupUiModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch

/**
 * 书架分组状态宿主：分组列表流 + 选中分组（宿主快照 + 面板乐观覆盖），
 * 合并为对外唯一选中流。结构与语义镜像 [BookshelfStyleState]。
 *
 * - [submit] 同步置覆盖：切组渲染即时生效，不等待宿主落库
 *  （saveTabPosition 经 DataStore 原子提交）；
 * - 覆盖在宿主快照追平后清除：落库完成与流发射之间的空窗不回跳旧值；
 * - 选中组不在已加载列表中（被隐藏/删除，含加载后清空）时回退「全部」：
 *   避免 chip 与列表失联导致空书架假象；列表未加载（尚未出现过非空
 *   列表，空列表视为未加载）时保持快照值不误回退。
 *
 * 快照流与分组流均为纯输入，本类不修改它们（纯函数式合并 + 一处
 * 覆盖清除副作用，可在纯 JVM 测试中验证——AndroidViewModel 不可直测）。
 */
internal class BookshelfGroupState(
    savedSelected: Flow<Long>,
    groupsFlow: Flow<List<BookshelfGroupUiModel>>,
    scope: CoroutineScope,
) {
    private val _override = MutableStateFlow<Long?>(null)

    /** 分组列表直通（宿主一次映射快照，模块零计算）。 */
    val groups: Flow<List<BookshelfGroupUiModel>> = groupsFlow

    /**
     * 分组列表的加载投影：首个非空列表出现前为 null（未加载），此后
     * 直通当前列表（含空列表 = 全部隐藏）。scan 状态随收集器独立，
     * 不跨收集器共享。
     */
    private val loadedGroups: Flow<List<BookshelfGroupUiModel>?> =
        groupsFlow.scan(null as List<BookshelfGroupUiModel>?) { loaded, groups ->
            if (loaded != null || groups.isNotEmpty()) groups else loaded
        }

    /** 合并后的对外选中流：覆盖优先，追平回快照，失联回退「全部」。 */
    val selected: Flow<Long> =
        combine(savedSelected, _override, loadedGroups) { saved, override, groups ->
            val effective = override ?: saved
            if (groups != null && groups.none { it.groupId == effective }) {
                BookshelfGroupIds.ALL
            } else {
                effective
            }
        }

    init {
        scope.launch {
            savedSelected.collect { saved ->
                if (_override.value == saved) _override.value = null
            }
        }
    }

    /** 提交切组：同步置覆盖，宿主落库由调用方另行发起。 */
    fun submit(groupId: Long) {
        _override.value = groupId
    }
}
