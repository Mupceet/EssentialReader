package io.legado.app.eink.contract

import io.legado.app.eink.arch.EInkImmutable
import kotlinx.coroutines.flow.Flow

/**
 * 语义值（2026-10-16 双模式修订）：
 * - [NONE]（0）= 无筛选——默认态，书架为文件夹视图（分组条目 + 未归组书）；
 * - [ALL]（-1，宿主 `BookGroup.IdAll`）= 「全部」筛选——平铺视图之一，
 *   与范围（scope）维度共用表示「不在组视图」。
 */
object BookshelfGroupIds {
    const val NONE: Long = 0L
    const val ALL: Long = -1L
}

/**
 * 筛选器/用户组展示快照（宿主构造义务：一次映射，模块零计算）。
 */
@EInkImmutable
data class BookshelfGroupUiModel(
    /** 组 id。筛选器为 NONE(0)/精选系统组负 ID；用户组为位值。模块不解释位运算。 */
    val groupId: Long,
    /** 展示名。筛选器为默认本地化名（不读宿主自定义名）；用户组为组名。 */
    val name: String,
    /** 上下文计数：筛选器为全书架口径；用户组为组大小。 */
    val bookCount: Int,
    /** 用户组显隐态（调整分组面板管理，隐藏组的条目不进顶层）；筛选器恒 true。 */
    val shown: Boolean = true,
)

/**
 * 封面引用：url（可空——无图时 UI 用书名+作者文字封面，同普通封面条目）+
 * 书名/作者 + 所属书源 origin（origin 参与加载解析）。
 */
@EInkImmutable
data class BookshelfCoverRefUiModel(
    val url: String?,
    val name: String,
    val author: String?,
    val origin: String?,
)

/**
 * 顶层视图混合内容条目：书籍 | 用户组聚合条目（spec D5-D9）。
 */
@EInkImmutable
sealed interface BookshelfShelfEntryUiModel {

    @EInkImmutable
    data class Book(val book: BookshelfItemUiModel) : BookshelfShelfEntryUiModel

    /** 用户组聚合条目：点击进组视图，长按出管理菜单。仅在无筛选（文件夹）视图出现。 */
    @EInkImmutable
    data class Group(
        val groupId: Long,
        val name: String,
        /** 组大小。 */
        val bookCount: Int,
        /** 拼贴封面引用（按 durChapterTime DESC 前 7，空位由 UI 补白）。 */
        val covers: List<BookshelfCoverRefUiModel>,
    ) : BookshelfShelfEntryUiModel
}

/**
 * 书架分组端口（2026-10-14 spec 重写；2026-10-16 双模式修订）：
 * 筛选（无筛选=文件夹视图 / 精选筛选=平铺视图）+ 范围（全部 | 用户组）+
 * 顶层聚合 + 分组管理（排序与显隐）。
 *
 * 语义规则：位掩码运算封闭在宿主实现内；管理方法对系统组（groupId < 0）
 * 抛 [IllegalArgumentException]（用户组 `show` 位除外——由调整分组面板
 * 经 [applyGroupAdjustments] 读写）；筛选入口仅在顶层。
 */
interface BookshelfGroupEngine {

    /** 可见筛选器流（无筛选[NONE] 恒在首位 + show=1 系统筛选器按 order；hideEmpty 过滤；无筛选计数=全书架口径）。 */
    fun observeFilters(): Flow<List<BookshelfGroupUiModel>>

    /** 管理筛选器面板数据：全量系统筛选器（含隐藏、不做空计数门控，按 order）。 */
    fun observeManageableFilters(): Flow<List<BookshelfGroupUiModel>>

    /** 管理筛选器面板退出时一次性整表提交系统筛选器新序与显隐集；集合不一致/含未知项 IAE。 */
    suspend fun applyFilterAdjustments(orderedFilterIds: List<Long>, shownFilterIds: Set<Long>)

    /** 当前筛选（持久化键 einkShelfFilter 投影；NONE=无筛选文件夹视图，非精选值收敛 NONE）。 */
    val selectedFilter: Flow<Long>

    /** 持久化筛选；仅接受 [BookshelfGroupIds.NONE] 或系统筛选器 id（IdRoot 除外）。 */
    suspend fun setSelectedFilter(filterId: Long)

    /**
     * 当前范围：ALL 或用户组 id（saveTabPosition 投影，负值收敛为 ALL；
     * 指向私密组时宿主应回落 ALL——模块侧无隐私解锁端口，不得明文列出
     * 私密组书籍）。
     */
    val selectedGroup: Flow<Long>

    /** 持久化范围（组视图进入/退出）；仅接受 ALL 或用户组 id。 */
    suspend fun setSelectedGroup(groupId: Long)

    /**
     * 用户组全量流（含隐藏组；范围状态的失联回退参考列表与调整分组面板数据源）。
     * 宿主必须排除私密组（isPrivate）——模块无隐私门控 UI，出现即等于绕过
     * 完整模式的隐私边界。
     */
    fun observeUserGroups(): Flow<List<BookshelfGroupUiModel>>

    /**
     * 无筛选（文件夹）视图：可见（show=1）非空组条目 + 未归组书（仅被可见组
     * 收纳的书才隐藏）。私密组不产出条目；其书籍由未归组掩码（含私密组位）
     * 排除在顶层之外，与完整模式平铺书架口径一致。
     */
    fun observeShelfWithGroups(): Flow<List<BookshelfShelfEntryUiModel>>

    /** 筛选（平铺）视图：全量匹配书单（含已归组书），无分组条目。 */
    fun observeFilteredShelf(filterId: Long): Flow<List<BookshelfShelfEntryUiModel>>

    /** 组视图内容（组位掩码全量；组视图只能自无筛选文件夹视图进入）。 */
    fun observeGroupBooks(groupId: Long): Flow<List<BookshelfItemUiModel>>

    /** 新建用户组并返回新组 id（重名/空名抛 IAE，满 64 组上限抛 ISE）；建组不归书，归组由调用方紧随调用。 */
    suspend fun createGroup(name: String): Long

    /** 重命名用户组（系统组/重名抛 IAE）。 */
    suspend fun renameGroup(groupId: Long, name: String)

    /**
     * 调整分组面板退出时一次性整表提交用户组新序与显隐集（与完整模式拖拽同写路径）；
     * 提交集合与当前用户组集合不一致、或 shown 含未知组时 IAE。
     */
    suspend fun applyGroupAdjustments(orderedGroupIds: List<Long>, shownGroupIds: Set<Long>)

    /** 解散用户组（书籍保留仅清位；系统组抛 IAE）。 */
    suspend fun dissolveGroup(groupId: Long)

    /** 归书（member=true 置位 / false 清位；系统组抛 IAE）。 */
    suspend fun setBookGroupMembership(bookUrl: String, groupId: Long, member: Boolean)

    /** 书籍当前归属的用户组 id 集合流（归组面板回显）。 */
    fun observeBookGroupMembership(bookUrl: String): Flow<Set<Long>>
}
