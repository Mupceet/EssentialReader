package io.legado.app.eink.contract

import kotlinx.coroutines.flow.StateFlow

/**
 * 应用更新端口 —— 端口总表中唯一的**可选**端口。
 *
 * ## 职责边界
 *
 * 更新的主体是**宿主应用**而非 E-Ink 模式/模块：检查哪个发布仓库、
 * 走什么渠道（正式/测试）、版本比较规则、下载与安装管线，全部是宿主
 * 独占知识。模块侧只做三件事：
 *
 * ```text
 * 「我的」页入口行（本端口已注册才渲染）
 *    └─ 点击 ─► checkUpdate()
 *         ├─ null            ─► toast「已是最新版本」
 *         ├─ AppUpdateInfo   ─► E-Ink 纯文本更新弹层
 *         │                      └─「立即更新」─► startDownload()
 *         │                           └─ collect downloadProgress ─► 弹层内进度条
 *         └─ 抛异常          ─► toast 异常 message（宿主提供面向用户的文案）
 *
 * E-Ink 启动（EInkApp 根层）
 *    └─ shouldAutoCheckOnStart() ─ true ─► checkUpdate()
 *         ├─ null / 抛异常   ─► 静默（对齐宿主自动检查口径，不打扰）
 *         └─ AppUpdateInfo   ─► 同一更新弹层（根层渲染，任意屏幕之上）
 * ```
 *
 * ## 为什么可选（而非注册空实现）
 *
 * companion 宿主（把本模块作为 AAR 嵌入的第三方应用）可能根本没有
 * app 级更新机制——此时更新入口的正确形态是**消失**，而不是一个点了
 * 报「暂不支持」的死入口。这与「预缓存泵端口注册显式空实现」不同：
 * 泵是可空化的行为近似（空转不损害语义），更新是能力的整体在场/缺席
 * （二值），缺席只能以入口不渲染明示。宿主未注册时
 * [EInkEngineRegistry.appUpdateEngine] 为 null，模块据此隐藏入口。
 *
 * ## 宿主实现义务
 *
 * - `checkUpdate` 在调用方协程上下文执行网络检查（IO 由实现方自理）；
 *   「无更高版本」是**正常结果**（返回 null），只有检查失败才抛异常。
 * - `startDownload` 启动宿主自有下载管线（通知/前台服务/系统安装器），
 *   fire-and-forget：进度与完成反馈由宿主通知承担，模块不展示进度 UI。
 */
interface AppUpdateEngine {

    /**
     * 本次进程是否应执行启动自动检查。
     *
     * 「是否自动检查」是宿主独占知识：用户设置（完整模式「其他设置」的
     * 启动检查开关）与进程级一次性频控（每次进程生命周期最多自动检查
     * 一次，与完整模式共享同一闸）都在宿主侧。调用即消耗本次进程的
     * 自动检查机会（consume 语义）：返回 true 后无论检查结果如何，
     * 本进程内后续调用一律返回 false。
     *
     * @return 设置开启且本进程尚未自动检查过时 true；否则 false，
     *   调用方不得再发起自动检查（手动检查不受影响）。
     */
    fun shouldAutoCheckOnStart(): Boolean

    /**
     * 检查应用更新。
     *
     * @return 有更高版本时返回更新快照；已是最新返回 null（正常结果，
     *   不是错误）。
     * @throws Exception 检查失败（网络/解析/限流等），message 面向
     *   最终用户，模块直接 toast。
     */
    suspend fun checkUpdate(): AppUpdateInfo?

    /**
     * 启动更新包下载。宿主以自有管线承接（本仓参照实现：
     * DownloadService 系统下载器 + 进度通知 + 完成后调起系统安装器）。
     * fire-and-forget：模块经 [downloadProgress] 观测进度做应用内补充
     * 展示——部分系统不给通知栏权限，通知进度不可见时模块侧进度是
     * 唯一可见反馈。
     *
     * @param update [checkUpdate] 返回的同一条更新快照。
     */
    fun startDownload(update: AppUpdateInfo)

    /**
     * 最近一次 [startDownload] 的更新包下载进度。
     *
     * - null：尚无发布（未开始/宿主不支持进度观测）；
     * - 非 null：进度快照按宿主页轮询节奏（约 1s）刷新，宿主保证
     *   [AppDownloadProgress.state] 达到终态（SUCCEEDED/FAILED）后
     *   停留不再回退。
     *
     * 宿主实现以最近一次 [startDownload] 的下载地址为关联键，从自有
     * 下载管线的进度中过滤本条更新；companion 宿主无进度观测能力时
     * 返回恒 null 的流即可（模块按「准备中」呈现，不构成错误）。
     */
    val downloadProgress: StateFlow<AppDownloadProgress?>
}

/**
 * 跨界更新信息快照：宿主把自有更新检查结果映射而来。
 *
 * 义务对齐其它 UiModel：全基元不可变、不携带宿主实体。
 * [note] 保留发布说明源文本（通常为 markdown），模块自行做纯文本化
 * 呈现（[io.legado.app.eink.feature.home.releaseNoteToPlainText]），
 * 不要求宿主预清洗。
 */
data class AppUpdateInfo(
    /** 新版本名（与发布 tag 一致，如 `3.26.17-beta.1`）。 */
    val versionName: String,
    /** 发布说明源文本（markdown），模块纯文本化展示。 */
    val note: String,
    /** 更新包下载直链（宿主下载管线消费，模块不解读）。 */
    val downloadUrl: String,
    /** 更新包文件名（含 ABI 与版本，下载提示用）。 */
    val fileName: String
)

/**
 * 更新包下载进度快照：宿主下载管线发布，模块渲染应用内进度。
 * 义务对齐其它 UiModel：全基元不可变、不携带宿主下载器类型。
 */
data class AppDownloadProgress(
    /** 下载状态机；SUCCEEDED/FAILED 为终态。 */
    val state: AppDownloadState,
    /** 已下载字节数。 */
    val bytesSoFar: Long,
    /** 总字节数；<= 0 表示总长未知（此时 [percent] 恒 -1）。 */
    val totalBytes: Long,
) {
    /** 整数百分比 0..100；总长未知时 -1（调用方按不定长呈现）。 */
    val percent: Int
        get() = if (totalBytes > 0) {
            ((bytesSoFar * 100) / totalBytes).coerceIn(0, 100).toInt()
        } else {
            -1
        }
}

/** 更新包下载状态机（宿主下载器状态的基元投影）。 */
enum class AppDownloadState {
    /** 已入队等待网络/调度。 */
    PENDING,

    /** 传输中。 */
    RUNNING,

    /** 暂停（等待网络/重试等）。 */
    PAUSED,

    /** 成功；宿主已/将调起系统安装器。 */
    SUCCEEDED,

    /** 失败；重新调用 [AppUpdateEngine.startDownload] 可重试。 */
    FAILED,
}
