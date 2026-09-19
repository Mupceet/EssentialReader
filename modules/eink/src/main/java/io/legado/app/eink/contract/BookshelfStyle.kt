package io.legado.app.eink.contract

import io.legado.app.eink.arch.EInkImmutable

/**
 * 书架显示样式快照：宿主书架设置中对 E-Ink 生效的策划子集投影。
 *
 * 判定全集（48 键逐键依据）见
 * `docs/superpowers/specs/2026-09-09-eink-bookshelf-settings-passing-design.md`；
 * 未投影的宿主键为主动忽略或功能面缺失，不进本快照。
 *
 * 映射纪律（宿主构造义务）：
 *  - 字段按模块语义命名，不照搬宿主键名；宿主键到字段的对应关系见各成员
 *    KDoc，宿主实现不得扩大或收窄语义；
 *  - [gridCoverWidth]/[titleMaxLines] 的非法值钳制在本层完成，模块收到的值恒可用；
 *  - 快照为实时档：宿主设置变化后经 Flow 发射新值，模块组合期订阅，
 *    立即重组（与 [GlobalSettings.useDefaultCover] 的快照状态档同语义）。
 *
 * 双宿主归宿：嵌入式宿主投影 `BookshelfSettingsGateway`；插件宿主无宿主
 * 书架设置可投影，发射本默认值的静态快照即合法实现（不是功能缺失，
 * 插件形态本就不承接宿主设置，见插件计划 §6.4 配置分叉）。
 */
@EInkImmutable
data class BookshelfStyle(
    /**
     * 是否显示未读章节数角标（宿主 `showUnread`）。
     *
     * false 时网格与列表条目均不渲染未读角标；刷新中的「…」角标是刷新态
     * 表达，不受本字段影响。
     */
    val showUnreadBadge: Boolean = true,

    /**
     * 本次目录刷新发现新章时角标是否反色高亮（宿主 `showUnreadNew`）。
     *
     * 模块侧组合规则与 View 版一致：高亮 = [showUnreadBadge] && 本字段 &&
     * hasNewChapter；未读角标整体隐藏时高亮随之无载体。
     */
    val highlightNewChapter: Boolean = true,

    /**
     * 列表条目是否显示最新章节行（宿主 `bookshelfShowLatestChapter`）。
     *
     * false 时列表条目隐藏该行，剩余信息行按既有 SpaceBetween 结构重排；
     * 网格条目本无该行，不受影响。
     */
    val showLatestChapter: Boolean = true,

    /**
     * 书架默认布局：true = 网格，false = 列表（宿主 `bookshelfLayoutModePortrait`，
     * 0 = 列表、非 0 = 网格）。
     *
     * 默认值随宿主（实时档）；E-Ink 首页顶栏可切换，经
     * [BookshelfEngine.setStyle] 反向写宿主竖屏键（横屏变体不投影，
     * E-Ink 按竖屏形态设计）。
     */
    val isGridLayout: Boolean = true,

    /**
     * 网格封面宽（dp）：格宽的最小值语义（宿主 `bookshelfGridCoverWidth`，
     * 宿主滑杆范围 40..150，默认 120）。
     *
     * 网格列数由模块按可用宽推导，使每格不小于该值、富余均摊（列数随
     * 屏宽/旋转自适应，封面保持 66:90 比例随格宽伸缩）。宿主映射义务：
     * 值 <= 0 时回落 120，不做其他钳制。仅 [isGridLayout] = true 时消费。
     */
    val gridCoverWidth: Int = 120,

    /**
     * 网格标题最大行数（宿主 `bookshelfTitleMaxLines`，合法域 1..5，默认 2）。
     *
     * 仅网格消费：标题最小高度与 maxLines 随之伸缩，行高变化需重建网格
     * 分页状态；E-Ink 列表标题保持单行。宿主映射义务：越界（<=0 或 >5）
     * 回落 2。
     */
    val titleMaxLines: Int = 2,
)
