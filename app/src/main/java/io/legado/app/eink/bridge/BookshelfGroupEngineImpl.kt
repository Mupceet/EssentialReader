package io.legado.app.eink.bridge

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.repository.BookGroupRepository
import io.legado.app.domain.gateway.BookshelfSettingsGateway
import io.legado.app.eink.contract.BookshelfGroupEngine
import io.legado.app.eink.contract.BookshelfGroupUiModel
import io.legado.app.eink.contract.BookshelfItemUiModel
import io.legado.app.help.book.isNotShelf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import splitties.init.appCtx

/**
 * 书架分组端口实现：薄转发既有 Room 查询 + 快照映射 + 排序落库。
 * 位掩码运算全部封闭在本层（flowByGroup 查询内），不进契约。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object BookshelfGroupEngineImpl : BookshelfGroupEngine, KoinComponent {

    private val bookGroupRepository: BookGroupRepository by inject()
    private val bookshelfSettingsGateway: BookshelfSettingsGateway by inject()

    /**
     * 展示名解析：虚拟组表内 groupName 可能为空，回落宿主本地化后缀
     * （与完整模式 GroupManageSheet 的 getManageName 消费方式一致）。
     */
    private val nameOf: (BookGroup) -> String = { group ->
        group.getManageName(appCtx).let { it.groupName.ifBlank { it.suffix ?: "" } }
    }

    override fun observeGroups(): Flow<List<BookshelfGroupUiModel>> =
        bookGroupRepository.flowShow().flatMapLatest { groups ->
            val userGroups = groups.filter { it.groupId > 0 }
            val userCountsFlow = if (userGroups.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    userGroups.map { group ->
                        appDb.bookDao.flowUserGroupBookCount(group.groupId)
                            .map { group.groupId to it }
                    }
                ) { pairs -> pairs.toMap() }
            }
            combine(
                appDb.bookDao.flowSystemGroupCounts(),
                userCountsFlow,
                bookshelfSettingsGateway.settings
                    .map { it.hideEmptyGroups }
                    .distinctUntilChanged(),
            ) { systemCounts, userCounts, hideEmpty ->
                buildGroupUiModels(
                    groups,
                    systemCounts.associate { it.groupId to it.count },
                    userCounts,
                    hideEmpty,
                    nameOf,
                )
            }
        }.distinctUntilChanged()

    override fun observeGroupBooks(groupId: Long): Flow<List<BookshelfItemUiModel>> =
        combine(
            // 用户组走 flowUserGroupBooks：带私有组豁免（谓词同完整模式
            // flowBookShelfByUserGroup），与面板 flowUserGroupBookCount 计数
            // 口径一致；全部/根/虚拟组维持 flowByGroup 原查询
            if (groupId > 0) {
                appDb.bookDao.flowUserGroupBooks(groupId)
                    // 镜像 flowByGroup 包装层的下架书过滤，两分支行为对齐
                    .map { books -> books.filterNot { it.isNotShelf } }
            } else {
                appDb.bookDao.flowByGroup(groupId)
            },
            bookGroupRepository.flowShow().map { groups ->
                groups.firstOrNull { it.groupId == groupId }
            },
            bookshelfSettingsGateway.settings
                .map { BookshelfSortKey(it.bookshelfSort, it.bookshelfSortOrder) }
                .distinctUntilChanged(),
        ) { books, group, sortKey ->
            // 组 bookSort >= 0 覆盖全局排序（语义与完整模式 sortBooks 一致）
            val sort = group?.getRealBookSort(sortKey.sort) ?: sortKey.sort
            books.sortedForBookshelf(sort, sortKey.sortOrder)
        }.map { books -> books.map { it.toBookshelfItemUiModel() } }

    override val selectedGroup: Flow<Long> =
        bookshelfSettingsGateway.settings
            .map { it.saveTabPosition }
            .distinctUntilChanged()

    override suspend fun setSelectedGroup(groupId: Long) {
        bookshelfSettingsGateway.update { it.copy(saveTabPosition = groupId) }
    }

    override suspend fun moveGroup(groupId: Long, up: Boolean) {
        val groups = bookGroupRepository.flowShow().first()
        val reordered = reorderedGroupsForMove(groups, groupId, up) ?: return
        val changed = reordered.filter { new ->
            groups.firstOrNull { it.groupId == new.groupId }?.order != new.order
        }
        try {
            appDb.runInTransaction {
                changed.forEach { appDb.bookGroupDao.update(it) }
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // 排序落库失败不抛给模块：真值流不重发，模块乐观层由下一次
            // 分组表变化收敛；日志指名定位
            AppLog.put("分组排序失败 groupId=$groupId\n${e.localizedMessage}", e)
        }
    }
}

/**
 * 分组快照组装（纯函数，注入名称解析便于 JVM 单测）：
 * `hideEmpty` 开启时计数为 0 的组被过滤，「全部」永不隐藏
 * （镜像宿主 BookshelfViewModel.computeHiddenGroupIds 语义）。
 */
internal fun buildGroupUiModels(
    groups: List<BookGroup>,
    systemCounts: Map<Long, Int>,
    userCounts: Map<Long, Int>,
    hideEmpty: Boolean,
    nameOf: (BookGroup) -> String,
): List<BookshelfGroupUiModel> = groups.mapNotNull { group ->
    val count =
        if (group.groupId > 0) userCounts[group.groupId]
        else systemCounts[group.groupId]
    if (hideEmpty && group.groupId != BookGroup.IdAll && (count ?: 0) == 0) {
        return@mapNotNull null
    }
    BookshelfGroupUiModel(
        groupId = group.groupId,
        name = nameOf(group),
        bookCount = count ?: 0,
    )
}

/**
 * 排序重排（纯函数）：目标行与相邻行交换后整体重赋 `order = index`，
 * 保证 order 唯一有序（宿主可容忍历史碰撞值）。不可移动返回 null。
 */
internal fun reorderedGroupsForMove(
    groups: List<BookGroup>,
    groupId: Long,
    up: Boolean,
): List<BookGroup>? {
    val index = groups.indexOfFirst { it.groupId == groupId }
    if (index < 0) return null
    val target = index + if (up) -1 else 1
    if (target !in groups.indices) return null
    return groups.toMutableList()
        .apply { add(target, removeAt(index)) }
        .mapIndexed { i, g -> g.copy(order = i) }
}
