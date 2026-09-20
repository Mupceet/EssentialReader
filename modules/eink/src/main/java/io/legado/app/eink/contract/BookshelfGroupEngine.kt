package io.legado.app.eink.contract

import io.legado.app.eink.arch.EInkImmutable
import kotlinx.coroutines.flow.Flow

/**
 * 「全部」虚拟分组的 groupId（宿主 `BookGroup.IdAll` = -1）。
 *
 * 模块以本常量判断「不分组过滤」：选中值为 [ALL] 时书架走
 * [BookshelfEngine.observeShelf] 现状平铺路径；其余值走
 * [BookshelfGroupEngine.observeGroupBooks]。
 */
object BookshelfGroupIds {
    const val ALL: Long = -1L
}

/**
 * 书架分组展示快照（宿主构造义务：一次映射，模块零计算，
 * 同 [BookshelfItemUiModel] 纪律）。
 */
@EInkImmutable
data class BookshelfGroupUiModel(
    /**
     * 分组 id。虚拟组沿用宿主表行语义（全部 -1、未分组 -100、本地 -2 等，
     * 建库时种入 book_groups 表）；用户组为位值。模块不解释位运算。
     */
    val groupId: Long,

    /** 展示名（宿主经 getManageName 解析，虚拟组空名回落本地化后缀）。 */
    val name: String,

    /** 组内书数。宿主已按 hideEmptyGroups 语义处理：可见组才出现在列表。 */
    val bookCount: Int,
)

/**
 * 书架分组端口——**可选**端口（同 [MarksEngine]）：
 * 未注册 = 宿主无分组浏览能力，书架选择器整体不渲染（书架维持全量平铺），
 * 不参与 install 必填校验。
 *
 * 职责边界：分组浏览/切换/选中记忆由本端口承载；分组排序与分组管理
 * （建组、删组、书归组、AI 分组、标签规则）不在端口面内，仍归完整模式
 * （排序归 GroupManageSheet）。
 *
 * 宿主数据交互（位掩码模型，模块不感知）：
 * ```text
 * observeGroups()  ──► book_groups 表 show>0 行（按 order 排）
 *                      + BookDao 计数查询（系统组/用户组）
 *                      + hideEmptyGroups 过滤（「全部」永不隐藏）
 * observeGroupBooks(groupId) ──► BookDao.flowByGroup(groupId)
 *                      + 组 bookSort>=0 时覆盖全局排序（sortBooks）
 * selectedGroup / setSelectedGroup ──► 宿主书架设置 saveTabPosition
 * ```
 */
interface BookshelfGroupEngine {

    /**
     * 分组选择模型流（实时档）：`book_groups` 中 show>0 的行按 `order`
     * 排序，附组内书数。`hideEmptyGroups` 开启时计数为 0 的组被过滤，
     * 「全部」（[BookshelfGroupIds.ALL]）恒在列表内。
     */
    fun observeGroups(): Flow<List<BookshelfGroupUiModel>>

    /**
     * 组内书籍流：映射语义与 [BookshelfEngine.observeShelf] 完全一致
     * （作者清洗/封面挑选/未读数预计算一次完成）。模块永不以
     * [BookshelfGroupIds.ALL] 调用本方法（选中「全部」时调用方走
     * [BookshelfEngine.observeShelf]），实现遇 ALL 返回空流即可；
     * 其余值含虚拟组（未分组 -100 等）与用户组。组 `bookSort >= 0`
     * 时覆盖全局排序。
     */
    fun observeGroupBooks(groupId: Long): Flow<List<BookshelfItemUiModel>>

    /**
     * 当前选中分组（宿主 `saveTabPosition` 投影，实时档）：与完整模式
     * 共享「记住当前分组」，跨模式一致。初始值「全部」。
     */
    val selectedGroup: Flow<Long>

    /**
     * 持久化选中分组（切组时调用）：宿主经设置网关一次原子 update 写
     * `saveTabPosition`。模块侧应乐观更新 UI，不等待落库。
     */
    suspend fun setSelectedGroup(groupId: Long)
}
