package io.legado.app.eink.contract

import kotlinx.coroutines.flow.Flow

/**
 * 书源管理条目的展示模型（宿主 `book_sources_part` 视图同构的轻量投影）。
 *
 * 字段只含墨水屏书源管理界面的展示与操作所需：标识（[url]）、名称、
 * 分组原始串、启用位与排序键。分组为宿主原样存储的逗号分隔多分组串，
 * 模块按分隔符拆分后用于筛选面板与行内展示（拆分口径见
 * [BookSourceEngine] KDoc 的实现义务）。
 */
data class BookSourceEntryUiModel(
    /** 书源地址（宿主书源体系的唯一标识，也是全部写操作的回传键）。 */
    val url: String,
    /** 书源名称。 */
    val name: String,
    /** 分组原始串（逗号分隔多分组；null/空白 = 未分组）。 */
    val group: String?,
    /** 是否启用（搜索/正文可用性）。 */
    val enabled: Boolean,
    /** 是否配置了登录地址（「登录」入口与「需要登录」筛选的判定键）。 */
    val hasLoginUrl: Boolean,
    /** 手动排序号（置顶/置底与手动排序的比较键）。 */
    val customOrder: Int,
    /** 智能排序权重。 */
    val weight: Int,
    /** 最后更新时间（毫秒 epoch，「更新时间」排序用）。 */
    val lastUpdateTime: Long,
    /** 响应时间（毫秒，「响应时间」排序用；宿主默认 180000）。 */
    val respondTime: Long,
)

/**
 * 书源管理端口——**可选**端口：未注册 = 宿主无书源管理能力，
 * 「我的 → 书源管理」入口不渲染，不参与 install 必填校验。
 *
 * 职责边界：模块书源管理屏保留浏览编排（搜索/筛选/排序/选择的纯函数
 * 组合与批量动作的分发）；宿主实现负责真实数据源（Room flow）、
 * 写操作（启停/排序/删除的级联语义）与登录界面承载：
 *
 * ```text
 * sourceEntries() ─► Room book_sources_part flow（模块 collect 全量，
 *                     自行筛选排序——列表口径单一，不把筛选下推 DAO）
 * setEnabled / moveToEdge / deleteSources ─► 宿主事务化写
 * loginStates() ─► 登录信息存在性快照（非 flow，模块在进入/回前台时拉取）
 * openSourceLogin(url) ─► 宿主登录界面（表单/WebView/验证码全由宿主承载）
 * ```
 *
 * 写操作与展示解耦：写完成后 Room flow 自动回流新列表，模块不做乐观
 * 更新（墨水屏按压反色已提供即时反馈，DB 回流的一轮刷新可接受）。
 */
interface BookSourceEngine {

    /**
     * 全部书源条目（含禁用），按宿主默认顺序（customOrder 升序）发射。
     *
     * 宿主实现义务：响应式 Flow（Room invalidation 驱动），写操作后
     * 自动重发新列表；条目不得过滤禁用源（筛选是模块侧职责）。
     */
    fun sourceEntries(): Flow<List<BookSourceEntryUiModel>>

    /**
     * 登录状态快照：key = 书源地址，value = 是否已保存登录信息
     * （宿主 CacheManager `userInfo_<key>` 的存在性口径）。
     *
     * 只需覆盖配置了登录地址的书源（其余条目模块按「不可登录」处理）。
     * 登录信息存储不是 Room 表、无失效通知——模块在进入页面与从登录
     * 界面返回（ON_RESUME）时重新拉取合并。
     */
    suspend fun loginStates(): Map<String, Boolean>

    /**
     * 批量启用/禁用书源（纯 DB 位更新，可逆）。
     *
     * 宿主实现义务：事务化批量更新；不存在的 url 静默跳过。
     */
    suspend fun setEnabled(urls: List<String>, enabled: Boolean)

    /**
     * 批量启用/禁用书源的「发现页」（enabledExplore 位，纯 DB 位更新，
     * 可逆；影响完整模式发现页的数据源集合）。
     *
     * 宿主实现义务：事务化批量更新；不存在的 url 静默跳过。
     */
    suspend fun setExploreEnabled(urls: List<String>, enabled: Boolean)

    /**
     * 批量置顶/置底：按当前 manual 序取边界偏移重排 customOrder
     * （对齐宿主「置顶所选/置底所选」语义：选中项作为整块移到
     * 全表最前/最后，块内保持原相对顺序）。
     *
     * 宿主实现义务：序号越界（±99999）或出现重复序号时按宿主既有
     * 逻辑全表重排；不存在的 url 静默跳过。
     */
    suspend fun moveToEdge(urls: List<String>, toTop: Boolean)

    /**
     * 批量删除书源。
     *
     * 宿主实现义务：对齐宿主删除语义——事务内级联删除源变量缓存与
     * 选中源配置，**不可恢复**；模块侧必须经用户确认后调用。
     */
    suspend fun deleteSources(urls: List<String>)

    /**
     * 打开宿主的书源登录界面（表单模式 / WebView 模式 / 验证码流程
     * 全部由宿主 UI 承载，模块不感知）。
     *
     * 登录完成后用户返回，模块经 ON_RESUME 重拉 [loginStates] 刷新
     * 「已登录」状态。返回 false = 宿主无登录界面能力（模块提示），
     * true = 已发起（宿主界面覆盖在本界面之上）。
     */
    fun openSourceLogin(sourceKey: String): Boolean
}
