package io.legado.app.eink.contract

/**
 * 阅读排版参数快照（渲染 + 设置面板双用途）。
 *
 * 调参数据流：
 * ```text
 * 阅读页排版面板（滑条编辑；模块内已钳制区间）
 *        │ applyStyle(style)     快照写入（可空扩展字段 null 时跳过对应键）
 *        ▼
 * 宿主排版配置（映射各字段 + 持久化 + 刷新画笔）
 *        │ relayout()            调参防抖合并后触发
 *        ▼
 * 引擎重排 ─► onContentUpdated ─► 新页快照（模块画布重绘）
 *
 * currentStyle() ◄── 读回同构快照（面板初值 / VM 构造）
 * ```
 *
 * 模块对「阅读页长什么样」的全部主张收敛在这一个值对象。嵌入式宿主下
 * 这些键与完整模式的阅读排版设置共享存储——任一模式调参，另一模式
 * 同步生效（既定产品语义）。
 *
 * 字段单位与模块内编辑区间（设置面板滑条的边界，常量在模块
 * feature/reader 包内）：
 *  - [textSize] sp，8..40。标题字号为独立可空字段（[titleSize]），null
 *    时不跨桥写；宿主实现不再钉平；
 *  - [letterSpacing] em（引擎侧乘以字号换算 px），0..0.5，面板按
 *    0.05 步进设置（避免浮点累加漂移）；
 *  - [indentChars] 段首缩进字符数，0..4（<=0 即无缩进）；宿主把它
 *    展开为缩进字符串写入（展开所用缩进字符是宿主引擎常量，不进
 *    快照）；
 *  - [lineSpacing] 行距增量，单位 0.1 倍行高（12 = 1.2 倍），0..30；
 *  - [paragraphSpacing] 段距增量，单位 0.1 倍行高，0..10；
 *  - [paddingLeft]/[paddingTop]/[paddingRight]/[paddingBottom] 正文
 *    四边距 dp，水平 0..64、竖直 0..48；
 *  - [headerPadding*]/[footerPadding*] 页眉/页脚内容四边距 dp，
 *    区间同正文边距。
 *
 * 区间是模块 UI 的编辑边界而非宿主校验义务：模块侧调参前已钳制，
 * 宿主实现按自身引擎容忍度原值透传即可。
 */
data class ReaderTextStyle(
    /** 正文字号（sp），默认 20。 */
    val textSize: Int = 20,

    /** 字距（em），默认 0.1；宿主引擎按 [textSize] 换算像素。 */
    val letterSpacing: Float = 0.1f,

    /** 段首缩进字符数，默认 2；0 = 无缩进。 */
    val indentChars: Int = 2,

    /** 行距增量（0.1 倍行高），默认 12（即 1.2 倍行高）。 */
    val lineSpacing: Int = 12,

    /** 段距增量（0.1 倍行高），默认 2。 */
    val paragraphSpacing: Int = 2,

    // ---- 协商扩展参数：null = 不跨桥写（保持宿主值）。宿主目录标记
    // 不可用或旧宿主回落目录中不存在时，UI 隐藏对应设置项。 ----

    /** 正文字体；null = 不管理。 */
    val bodyFont: ReaderFontSelection? = null,

    /** 正文字重：0 常规 / 1 粗体 / 2 细体 / 100..900 自定义可变字重
     *  （宿主 textBold 同构，预设档直传保留宿主下拉语义）；null = 不管理。 */
    val bodyWeight: Int? = null,

    /** 标题字体；null = 不管理，[ReaderFontSelection.FollowBody] = 跟随正文。 */
    val titleFont: ReaderFontSelection? = null,

    /** 标题字重：0 常规 / 1 粗体 / 2 细体 / 100..900 自定义可变字重
     *  （宿主 titleBold 同构，预设档直传保留宿主下拉语义）；null = 不管理。 */
    val titleWeight: Int? = null,

    /** 标题位置（0 左 / 1 中 / 2 隐藏，宿主语义）；null = 不管理。 */
    val titleMode: Int? = null,

    /** 标题字号（sp）；null = 不管理（宿主值独立保留，不再钉平跟随正文）。
     *  eink 侧「随正文一致」模式由 VM 保持与 [textSize] 相等（相等即随正文），
     *  自定义时独立调节。 */
    val titleSize: Int? = null,

    /** 标题上留白（dp）；null = 不管理。 */
    val titleTopSpacing: Int? = null,

    /** 标题下留白（dp）；null = 不管理。 */
    val titleBottomSpacing: Int? = null,

    /** 标题行距（0.1 倍档，12 = 1.2 倍）；null = 不管理。 */
    val titleLineSpacing: Int? = null,

    /** 页眉字体；null = 不管理，FollowBody = 跟随正文。 */
    val headerFont: ReaderFontSelection? = null,

    /** 页眉模式（0 随状态栏 / 1 显示 / 2 隐藏，宿主 HeaderMode 同构）；
     *  null = 不跨桥写——读回时宿主 0 档保持 null（不管理），用户显式
     *  选「随状态栏」时写 0。 */
    val headerMode: Int? = null,

    /** 页眉字号（sp，经 extent 影响正文分页预留）；null = 不管理。 */
    val headerSize: Int? = null,

    /** 页眉分割线；null = 不管理。 */
    val headerDivider: Boolean? = null,

    /** 页脚显隐（写宿主 FooterMode 0/1）；null = 不管理。 */
    val footerVisible: Boolean? = null,

    /** 页脚分割线；null = 不管理。 */
    val footerDivider: Boolean? = null,

    /** 正文左边距（dp）。 */
    val paddingLeft: Int = 16,

    /** 正文上边距（dp）。 */
    val paddingTop: Int = 6,

    /** 正文右边距（dp）。 */
    val paddingRight: Int = 16,

    /** 正文下边距（dp）。 */
    val paddingBottom: Int = 6,

    /** 页眉左边距（dp）。 */
    val headerPaddingLeft: Int = 16,

    /** 页眉上边距（dp）。 */
    val headerPaddingTop: Int = 0,

    /** 页眉右边距（dp）。 */
    val headerPaddingRight: Int = 16,

    /** 页眉下边距（dp）。 */
    val headerPaddingBottom: Int = 0,

    /** 页脚左边距（dp）。 */
    val footerPaddingLeft: Int = 16,

    /** 页脚上边距（dp）。 */
    val footerPaddingTop: Int = 6,

    /** 页脚右边距（dp）。 */
    val footerPaddingRight: Int = 16,

    /** 页脚下边距（dp）。 */
    val footerPaddingBottom: Int = 6,
)
