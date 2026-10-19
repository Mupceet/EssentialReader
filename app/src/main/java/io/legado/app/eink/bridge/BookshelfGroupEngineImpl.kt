package io.legado.app.eink.bridge

import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.repository.BookGroupRepository
import io.legado.app.domain.gateway.BookGroupMutationGateway
import io.legado.app.domain.gateway.BookshelfSettingsGateway
import io.legado.app.domain.model.BookGroupUpdate
import io.legado.app.domain.model.NewBookGroup
import io.legado.app.domain.usecase.UpdateBooksGroupUseCase
import io.legado.app.eink.contract.BookshelfCoverRefUiModel
import io.legado.app.eink.contract.BookshelfGroupEngine
import io.legado.app.eink.contract.BookshelfGroupIds
import io.legado.app.eink.contract.BookshelfGroupUiModel
import io.legado.app.eink.contract.BookshelfItemUiModel
import io.legado.app.eink.contract.BookshelfShelfEntryUiModel
import io.legado.app.help.book.isNotShelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import splitties.init.appCtx

/**
 * 书架分组端口实现（2026-10-16 双模式修订）：无筛选=文件夹视图 /
 * 精选筛选=平铺视图 + 分组管理（排序与显隐）。位掩码运算全部封闭在
 * 本层，不进契约。
 *
 * 私密边界（2026-10-19 修复）：eink 侧无隐私解锁端口，所有组策展
 * （文件夹条目/范围下拉/选中组回放）一律排除 isPrivate 组——其书籍由
 * 未归组掩码（含私密组位）自然排除在顶层之外，与完整模式平铺书架的
 * PUBLIC_BOOK_FILTER 口径一致；宁可不显示，不绕过完整模式的隐私门控。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object BookshelfGroupEngineImpl : BookshelfGroupEngine, KoinComponent {

    /**
     * 系统筛选器默认命名（id → 本地化资源；不读宿主库内自定义名，spec D4）。
     * IdRoot(-100) 不是筛选器（根目录入口），排除。
     */
    private val SYSTEM_FILTER_NAMES: Map<Long, Int> = mapOf(
        BookGroup.IdAll to R.string.all,
        BookGroup.IdLocal to R.string.local,
        BookGroup.IdAudio to R.string.audio,
        BookGroup.IdNetNone to R.string.net_no_group,
        BookGroup.IdLocalNone to R.string.local_no_group,
        BookGroup.IdManga to R.string.manga,
        BookGroup.IdText to R.string.noval,
        BookGroup.IdError to R.string.update_book_fail,
        BookGroup.IdReading to R.string.is_reading,
        BookGroup.IdUnread to R.string.is_unread,
        BookGroup.IdReadFinished to R.string.is_read_finished,
        BookGroup.IdReadFinishedUpdate to R.string.is_read_finished_update,
        BookGroup.IdReadFinishedComplete to R.string.is_read_finished_complete,
    )

    private fun filterNameOf(id: Long): String =
        SYSTEM_FILTER_NAMES[id]?.let { appCtx.getString(it) } ?: ""

    private val bookGroupRepository: BookGroupRepository by inject()
    private val bookGroupMutationGateway: BookGroupMutationGateway by inject()
    private val updateBooksGroupUseCase: UpdateBooksGroupUseCase by inject()
    private val bookshelfSettingsGateway: BookshelfSettingsGateway by inject()

    private val nameOf: (BookGroup) -> String = { group ->
        group.getManageName(appCtx).let { it.groupName.ifBlank { it.suffix ?: "" } }
    }

    // —— 筛选 ——

    override fun observeFilters(): Flow<List<BookshelfGroupUiModel>> =
        combine(
            bookGroupRepository.flowShow(),
            appDb.bookDao.flowSystemGroupCounts(),
            bookshelfSettingsGateway.settings
                .map { it.hideEmptyGroups }
                .distinctUntilChanged(),
        ) { rows, counts, hideEmpty ->
            val system = rows
                .filter { it.groupId < 0 && it.groupId != BookGroup.IdRoot }
                .sortedBy { it.order }
            buildFilterUiModels(
                system, counts.associate { it.groupId to it.count }, hideEmpty,
                noneName = appCtx.getString(R.string.eink_shelf_filter_none),
                nameOf = ::filterNameOf,
            )
        }

    override fun observeManageableFilters(): Flow<List<BookshelfGroupUiModel>> =
        combine(
            bookGroupRepository.flowAll(),
            appDb.bookDao.flowSystemGroupCounts(),
        ) { rows, counts ->
            rows.filter { it.groupId < 0 && it.groupId != BookGroup.IdRoot }
                .sortedBy { it.order }
                .map { row ->
                    BookshelfGroupUiModel(
                        groupId = row.groupId,
                        name = filterNameOf(row.groupId),
                        bookCount = counts.firstOrNull { it.groupId == row.groupId }?.count ?: 0,
                        shown = row.show,
                    )
                }
        }

    override val selectedFilter: Flow<Long> =
        bookshelfSettingsGateway.settings
            .map { raw ->
                // 读路径收敛：NONE 与系统筛选器 id 直通，其余（策展调整后的
                // 存量值）回落 NONE（文件夹视图），不让 error 外溢成冷启动崩溃
                if (raw.einkShelfFilter == BookshelfGroupIds.NONE ||
                    raw.einkShelfFilter in SYSTEM_FILTER_NAMES
                ) raw.einkShelfFilter else BookshelfGroupIds.NONE
            }
            .distinctUntilChanged()

    override suspend fun setSelectedFilter(filterId: Long) {
        require(
            filterId == BookshelfGroupIds.NONE || filterId in SYSTEM_FILTER_NAMES
        ) { "非系统筛选器: $filterId" }
        bookshelfSettingsGateway.update { it.copy(einkShelfFilter = filterId) }
    }

    // —— 范围 ——

    // by lazy：object clinit 不强制解析 bookGroupRepository（守卫方法
    // require 先于任何网关访问；单测 Koin 可只定义 settings 网关）
    override val selectedGroup: Flow<Long> by lazy {
        combine(
            bookshelfSettingsGateway.settings
                .map { if (it.saveTabPosition > 0) it.saveTabPosition else BookshelfGroupIds.ALL }
                .distinctUntilChanged(),
            bookGroupRepository.flowAll(),
        ) { selected, groups ->
            // 完整模式可能把书架停在私密组（共享 saveTabPosition）；eink 无
            // 隐私解锁端口，回落全部而非明文列出私密组书籍
            val isPrivateSelection = selected > 0 &&
                groups.any { it.groupId == selected && it.groupId > 0 && it.isPrivate }
            if (isPrivateSelection) BookshelfGroupIds.ALL else selected
        }.distinctUntilChanged()
    }

    override suspend fun setSelectedGroup(groupId: Long) {
        require(groupId == BookshelfGroupIds.ALL || groupId > 0) { "范围仅接受全部或用户组: $groupId" }
        bookshelfSettingsGateway.update { it.copy(saveTabPosition = groupId) }
    }

    override fun observeUserGroups(): Flow<List<BookshelfGroupUiModel>> =
        bookGroupRepository.flowAll().flatMapLatest { groups ->
            val userGroups = rangeVisibleGroups(groups)
            if (userGroups.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    userGroups.map { group ->
                        appDb.bookDao.flowUserGroupBookCount(group.groupId)
                            .map { group.groupId to it }
                    }
                ) { pairs -> pairs.toMap() }.map { counts ->
                    userGroups.map { group ->
                        BookshelfGroupUiModel(
                            groupId = group.groupId,
                            name = nameOf(group),
                            bookCount = counts[group.groupId] ?: 0,
                            shown = group.show,
                        )
                    }
                }
            }
        }

    // —— 无筛选（文件夹）视图 ——

    override fun observeShelfWithGroups(): Flow<List<BookshelfShelfEntryUiModel>> =
        bookGroupRepository.flowAll().flatMapLatest { groups ->
            // 仅可见（show=1）且非私密的组产出条目与封面查询；隐藏组的书由
            // 未归组查询（掩码仅统计 show=1 组）平铺回顶层，私密组的书则被
            // 同一掩码排除（掩码含私密组位）——与完整模式隐私口径一致
            val visibleGroups = folderVisibleGroups(groups).sortedBy { it.order }
            val countsFlow = if (visibleGroups.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    visibleGroups.map { group ->
                        appDb.bookDao.flowUserGroupBookCount(group.groupId)
                            .map { group.groupId to it }
                    }
                ) { pairs -> pairs.toMap() }
            }
            // 拼贴封面引用（与 countsFlow 同形）：每组按 durChapterTime DESC
            // 前 7 本（无图封面不过滤——UI 用书名+作者文字封面兜底，
            // 空白象限仅用于书籍数量不足时）
            val coversFlow = if (visibleGroups.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    visibleGroups.map { group ->
                        appDb.bookDao.flowUserGroupCoverRefs(group.groupId, 7)
                            .map { refs ->
                                group.groupId to refs.map { ref ->
                                    BookshelfCoverRefUiModel(
                                        url = ref.coverUrl,
                                        name = ref.name,
                                        author = ref.author,
                                        origin = ref.origin,
                                    )
                                }
                            }
                    }
                ) { pairs -> pairs.toMap() }
            }
            combine(
                countsFlow,
                coversFlow,
                appDb.bookDao.flowUngroupedBooks(),
                bookshelfSettingsGateway.settings
                    .map { BookshelfSortKey(it.bookshelfSort, it.bookshelfSortOrder) }
                    .distinctUntilChanged(),
            ) { counts, covers, books, sortKey ->
                val ungrouped = books
                    .filterNot { it.isNotShelf }
                    .sortedForBookshelf(sortKey.sort, sortKey.sortOrder)
                buildShelfWithGroupsEntries(
                    visibleGroups, counts, covers, ungrouped,
                    nameOf = nameOf,
                    bookMapper = { it.toBookshelfItemUiModel() },
                )
            }
        }.distinctUntilChanged()

    // —— 筛选（平铺）视图 ——

    override fun observeFilteredShelf(filterId: Long): Flow<List<BookshelfShelfEntryUiModel>> {
        require(filterId != BookshelfGroupIds.NONE) { "无筛选态走文件夹视图: observeShelfWithGroups" }
        return combine(
            appDb.bookDao.flowByGroup(filterId),
            bookshelfSettingsGateway.settings
                .map { BookshelfSortKey(it.bookshelfSort, it.bookshelfSortOrder) }
                .distinctUntilChanged(),
        ) { books, sortKey ->
            books.sortedForBookshelf(sortKey.sort, sortKey.sortOrder)
                .map { BookshelfShelfEntryUiModel.Book(it.toBookshelfItemUiModel()) }
        }
    }

    // —— 组视图 ——

    override fun observeGroupBooks(groupId: Long): Flow<List<BookshelfItemUiModel>> =
        combine(
            appDb.bookDao.flowUserGroupBooks(groupId)
                .map { books -> books.filterNot { it.isNotShelf } },
            bookGroupRepository.flowAll().map { groups ->
                groups.firstOrNull { it.groupId == groupId }
            },
            bookshelfSettingsGateway.settings
                .map { BookshelfSortKey(it.bookshelfSort, it.bookshelfSortOrder) }
                .distinctUntilChanged(),
        ) { books, group, sortKey ->
            val sort = group?.getRealBookSort(sortKey.sort) ?: sortKey.sort
            books.sortedForBookshelf(sort, sortKey.sortOrder).map { it.toBookshelfItemUiModel() }
        }

    // —— 管理（仅用户组；show 位经 applyGroupAdjustments 读写）——

    override suspend fun createGroup(name: String): Long {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "分组名不能为空" }
        requireUserGroupNameFree(trimmed, excludeGroupId = null)
        val canAdd = withContext(Dispatchers.IO) { appDb.bookGroupDao.canAddGroup }
        check(canAdd) { "分组数量已达上限（64）" }
        bookGroupMutationGateway.addGroup(
            NewBookGroup(
                groupName = trimmed, bookSort = -1, enableRefresh = true,
                isPrivate = false, cover = null, pattern = null,
            )
        )
        return withContext(Dispatchers.IO) {
            appDb.bookGroupDao.getByName(trimmed)?.groupId
                ?: error("建组后未找到新组: $trimmed")
        }
    }

    override suspend fun renameGroup(groupId: Long, name: String) {
        require(groupId > 0) { "系统筛选器不支持重命名" }
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "分组名不能为空" }
        requireUserGroupNameFree(trimmed, excludeGroupId = groupId)
        val current = withContext(Dispatchers.IO) { appDb.bookGroupDao.getByID(groupId) }
            ?: error("分组不存在: $groupId")
        bookGroupMutationGateway.saveGroup(
            bookGroup = current.toUpdate().copy(groupName = trimmed),
            ruleToSave = null, ruleIdToDelete = null,
        )
    }

    override suspend fun applyGroupAdjustments(orderedGroupIds: List<Long>, shownGroupIds: Set<Long>) {
        val groups = withContext(Dispatchers.IO) { appDb.bookGroupDao.all }
        require(
            validateClassSubmission(groups, orderedGroupIds) { it.groupId > 0 }
        ) { "分组列表已变化，请重试" }
        require(shownGroupIds.all { it in orderedGroupIds }) { "显隐集含未知分组" }
        bookGroupRepository.update(
            *weaveClassAdjustmentUpdate(groups, orderedGroupIds, shownGroupIds) { it.groupId > 0 }
                .toTypedArray()
        )
    }

    override suspend fun applyFilterAdjustments(orderedFilterIds: List<Long>, shownFilterIds: Set<Long>) {
        val groups = withContext(Dispatchers.IO) { appDb.bookGroupDao.all }
        val isFilter = { g: BookGroup -> g.groupId < 0 && g.groupId != BookGroup.IdRoot }
        require(validateClassSubmission(groups, orderedFilterIds, isFilter)) {
            "筛选器列表已变化，请重试"
        }
        require(shownFilterIds.all { it in orderedFilterIds }) { "显隐集含未知筛选器" }
        bookGroupRepository.update(
            *weaveClassAdjustmentUpdate(groups, orderedFilterIds, shownFilterIds, isFilter)
                .toTypedArray()
        )
    }

    override suspend fun dissolveGroup(groupId: Long) {
        require(groupId > 0) { "系统筛选器不支持解散" }
        bookGroupMutationGateway.deleteGroup(groupId)
    }

    override suspend fun setBookGroupMembership(bookUrl: String, groupId: Long, member: Boolean) {
        require(groupId > 0) { "系统筛选器不支持归书" }
        updateBooksGroupUseCase.updateGroups(setOf(bookUrl)) { old ->
            if (member) old or groupId else old and groupId.inv()
        }
    }

    override fun observeBookGroupMembership(bookUrl: String): Flow<Set<Long>> =
        combine(
            appDb.bookDao.flowBookGroupMask(bookUrl),
            bookGroupRepository.flowAll().map { groups -> groups.filter { it.groupId > 0 } },
        ) { mask, groups ->
            mask?.let { m -> groups.filter { m and it.groupId > 0 }.map { it.groupId } }?.toSet()
                ?: emptySet()
        }

// —— 私有装配 ——

private suspend fun requireUserGroupNameFree(name: String, excludeGroupId: Long?) {
        val dup = withContext(Dispatchers.IO) { appDb.bookGroupDao.getByName(name) }
        require(dup == null || dup.groupId !in 1L..Long.MAX_VALUE || dup.groupId == excludeGroupId) {
            "已有同名分组: $name"
        }
    }

    private fun BookGroup.toUpdate() = BookGroupUpdate(
        groupId = groupId, groupName = groupName, cover = cover, order = order,
        enableRefresh = enableRefresh, show = show, bookSort = bookSort, isPrivate = isPrivate,
    )
}

/**
 * 文件夹视图可见组策展（纯函数，便于 JVM 测试）：用户组 + show=1 + 非私密。
 * 私密组不产出条目、不产出封面查询，其书籍靠未归组掩码（含私密组位）
 * 排除在顶层之外。
 */
internal fun folderVisibleGroups(groups: List<BookGroup>): List<BookGroup> =
    groups.filter { it.groupId > 0 && it.show && !it.isPrivate }

/** 范围下拉可见组（纯函数）：用户组 + 非私密；不看 show（范围选择≠显隐策展）。 */
internal fun rangeVisibleGroups(groups: List<BookGroup>): List<BookGroup> =
    groups.filter { it.groupId > 0 && !it.isPrivate }

/**
 * 筛选器菜单装配（纯函数，注入名称解析便于 JVM 测试）：
 * 「无筛选」恒在首位（计数取全书架口径 IdAll），系统筛选器按入参序、
 * hideEmpty 开启时空计数不展示（与宿主口径一致）。
 */
internal fun buildFilterUiModels(
    visibleSystemRows: List<BookGroup>,
    systemCounts: Map<Long, Int>,
    hideEmpty: Boolean,
    noneName: String,
    nameOf: (Long) -> String,
): List<BookshelfGroupUiModel> = buildList {
    add(
        BookshelfGroupUiModel(
            groupId = BookshelfGroupIds.NONE,
            name = noneName,
            bookCount = systemCounts[BookGroup.IdAll] ?: 0,
        )
    )
    visibleSystemRows.forEach { row ->
        val count = systemCounts[row.groupId] ?: 0
        if (hideEmpty && count == 0) return@forEach
        add(
            BookshelfGroupUiModel(
                groupId = row.groupId,
                name = nameOf(row.groupId),
                bookCount = count,
            )
        )
    }
}

/**
 * 无筛选（文件夹）视图混合条目装配（纯函数，注入便于 JVM 测试）：
 * 条目置顶（按入参序，入参应为可见组）+ 未归组书跟随；空组条目保留显示
 *（2026-10-17 真机反馈：空组以占位形态展示；hideEmpty 仅门控筛选器菜单）。
 */
internal fun buildShelfWithGroupsEntries(
    userGroups: List<BookGroup>,
    userCounts: Map<Long, Int>,
    groupCovers: Map<Long, List<BookshelfCoverRefUiModel>>,
    ungroupedBooks: List<Book>,
    nameOf: (BookGroup) -> String,
    bookMapper: (Book) -> BookshelfItemUiModel,
): List<BookshelfShelfEntryUiModel> {
    val entries = userGroups.map { group ->
        val count = userCounts[group.groupId] ?: 0
        BookshelfShelfEntryUiModel.Group(
            groupId = group.groupId,
            name = nameOf(group),
            bookCount = count,
            covers = (groupCovers[group.groupId] ?: emptyList()).take(7),
        )
    }
    return entries + ungroupedBooks.map { BookshelfShelfEntryUiModel.Book(bookMapper(it)) }
}

/** 提交集合与当前类别集合一致（同元素集；size 隐含）才允许落库。 */
internal fun validateClassSubmission(
    allGroups: List<BookGroup>,
    orderedIds: List<Long>,
    isTarget: (BookGroup) -> Boolean,
): Boolean {
    val current = allGroups.filter(isTarget).map { it.groupId }
    return orderedIds.size == current.size && orderedIds.toSet() == current.toSet()
}

/**
 * 织入重排 + 显隐落位（用户组/系统筛选器两用）：非目标行原位原序不动，
 * 目标行按提交序依次占据目标槽位、show 取显隐集；全表 order = index
 *（与完整模式 GroupManageSheet 拖拽落序同构）。
 */
internal fun weaveClassAdjustmentUpdate(
    allGroups: List<BookGroup>,
    orderedIds: List<Long>,
    shownIds: Set<Long>,
    isTarget: (BookGroup) -> Boolean,
): List<BookGroup> {
    val byId = allGroups.associateBy { it.groupId }
    val queue = ArrayDeque(orderedIds)
    return allGroups.map { group ->
        if (isTarget(group)) byId.getValue(queue.removeFirst()) else group
    }.mapIndexed { index, group ->
        val shown = if (isTarget(group)) group.groupId in shownIds else group.show
        group.copy(order = index, show = shown)
    }
}
