package io.legado.app.eink.bridge

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.eink.contract.BookshelfGroupEngine
import io.legado.app.eink.contract.BookshelfGroupIds
import io.legado.app.eink.contract.BookshelfGroupUiModel
import io.legado.app.eink.contract.BookshelfItemUiModel
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * 书架分组端口实现：薄转发既有 Room 查询 + 快照映射。位掩码运算封闭在
 * BookDao.flowByGroup 查询内，不进契约。
 *
 * 本宿主差异：
 *  - 无 hideEmptyGroups 设置键——空组恒过滤（计数 0 的组不显示，
 *    「全部」恒在；对齐完整模式书架 Tab 的空虚拟组隐藏行为）；
 *  - 无按组计数 Flow 查询——计数经逐组 flowByGroup 取 size（分组数上限
 *    64，Room 复用同一失效追踪，开销可接受）；
 *  - 展示名用裸 groupName（完整模式书架 Tab 同款；getManageName 是
 *    分组管理界面的拼后缀名，虚拟组会得到「全部(全部)」式重复）；
 *  - 选中分组经宿主 saveTabPosition（完整模式书架 Tab 位置）换算 groupId：
 *    E-Ink 选择器与完整模式 Tab 共用「展示分组按 order 排序」的位序。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object BookshelfGroupEngineImpl : BookshelfGroupEngine {

    /** 展示分组流（book_groups 全行按 order 排序，过滤 show=0）。 */
    private val shownGroupsFlow: Flow<List<BookGroup>> =
        appDb.bookGroupDao.flowAll().map { groups -> groups.filter { it.show } }

    /**
     * 完整模式记忆的 Tab 位（Int，无 SP 流）：进程内状态流承接，切组时
     * 双写状态与宿主键；跨模式往返经 install 后首次读取对齐。
     */
    private val savedPosition = MutableStateFlow(AppConfig.saveTabPosition)

    override fun observeGroups(): Flow<List<BookshelfGroupUiModel>> =
        shownGroupsFlow.flatMapLatest { groups ->
            if (groups.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    groups.map { group ->
                        appDb.bookDao.flowByGroup(group.groupId).map { group to it.size }
                    }
                ) { pairs ->
                    pairs.mapNotNull { (group, count) ->
                        // 空组过滤（「全部」恒在）
                        if (group.groupId != BookshelfGroupIds.ALL && count == 0) {
                            return@mapNotNull null
                        }
                        BookshelfGroupUiModel(
                            groupId = group.groupId,
                            name = group.groupName,
                            bookCount = count,
                        )
                    }
                }
            }
        }.distinctUntilChanged()

    override fun observeGroupBooks(groupId: Long): Flow<List<BookshelfItemUiModel>> =
        appDb.bookDao.flowByGroup(groupId)
            .map { books -> books.map { it.toBookshelfItemUiModel() } }

    override val selectedGroup: Flow<Long> =
        combine(shownGroupsFlow, savedPosition) { groups, position ->
            groups.getOrNull(position)?.groupId ?: BookshelfGroupIds.ALL
        }.distinctUntilChanged()

    override suspend fun setSelectedGroup(groupId: Long) {
        // 位序换算按当前展示分组；组不在列表（刚删除等）回落首位「全部」
        val position = shownGroupsSnapshot().indexOfFirst { it.groupId == groupId }
            .takeIf { it >= 0 } ?: 0
        savedPosition.value = position
        AppConfig.saveTabPosition = position
    }

    private suspend fun shownGroupsSnapshot(): List<BookGroup> =
        appDb.bookGroupDao.flowAll().first().filter { it.show }
}
