package io.legado.app.eink.contract

/**
 * 阅读排版参数快照（渲染 + 设置面板双用途）。
 *
 * 模块对「阅读页长什么样」的全部主张收敛在这一个值对象：阅读页
 * 设置面板编辑它，经 [ReaderEngine.applyStyle] 整体写入宿主排版引擎
 * （映射宿主阅读配置各字段并持久化 + 刷新画笔）；
 * [ReaderEngine.currentStyle] 从宿主配置读回同构快照。嵌入式宿主下
 * 这些键与完整模式的阅读排版设置共享存储——任一模式调参，另一模式
 * 同步生效（既定产品语义）。
 *
 * 字段单位与模块内编辑区间（设置面板滑条的边界，常量在模块
 * feature/reader 包内）：
 *  - [textSize] sp，8..40。标题字号无独立字段——模块语义为标题跟随
 *    正文，宿主实现把标题字号一并写入同值；
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
    /** 正文字号（sp），默认 20；标题字号随正文，无独立字段。 */
    val textSize: Int = 20,
    /** 字距（em），默认 0.1；宿主引擎按 [textSize] 换算像素。 */
    val letterSpacing: Float = 0.1f,
    /** 段首缩进字符数，默认 2；0 = 无缩进。 */
    val indentChars: Int = 2,
    /** 行距增量（0.1 倍行高），默认 12（即 1.2 倍行高）。 */
    val lineSpacing: Int = 12,
    /** 段距增量（0.1 倍行高），默认 2。 */
    val paragraphSpacing: Int = 2,
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
