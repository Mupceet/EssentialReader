package io.legado.app.eink.designsystem.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * E-Ink 排版系统。
 *
 * 设计规则（BOOX/E-Ink UX 指南 + 2026-09 真机校准）：
 *  - **14sp 硬下限**：任何样式不得低于 14sp（更低字号在墨水屏上锐度不足，
 *    [EInkText][io.legado.app.eink.designsystem.content.EInkText] 另有同值
 *    运行时钳制）。下限是地板不是聚点——被下限压扁的档位整条上抬保持阶梯
 *    可辨：body 18/16/14（Material 16/14/12 的 +2sp 平移）、title 22/18/16、
 *    label 16/14/14。
 *  - **字重按角色定**：display/headline/title/label = Bold（结构短文本，
 *    靠字重提对比），body 三档统一 Normal（成段内容文字合成加粗易糊，且
 *    全粗即无层级）。跨家族同尺寸的字重差（bodySmall 14 Normal vs
 *    labelMedium 14 Bold）是角色语义，与 Material 的 body/label 分工同构。
 *    不用 Medium(500)：部分 ROM 的系统字体替换不覆盖 medium 字重档，会落
 *    回未替换的原生黑体（真机实证，见 [bodySmall] 注释）。
 *  - **行高即几何契约**：列表行高（bookshelfListRowHeight）消费
 *    titleMedium(24)/bodyMedium(24) 行高并计入行距与内边距；网格标题高
 *    （bookshelfGridTitleHeight）随 BookshelfScreen 的 titleStyle 行高
 *    （titleSmall 20）直读。调这些行高即调布局密度，须连几何测试一起核对。
 *
 * 15 个样式沿用 Material 角色命名（display/headline/title/body/label ×
 * large/medium/small），Material 使用者可无缝对照，但不依赖 Material3。
 */
object EInkTypography {

    /** Display styles for headers and prominent text. */
    val displayLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
    )

    val displayMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp,
    )

    val displaySmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp,
    )

    /** Headline styles for section headers. */
    val headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
    )

    val headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
    )

    val headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    )

    /** Title styles for component headers. */
    val titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    )

    val titleMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp, // 16→18：body 整体上抬后保持 title 对 body 的一档压制
        lineHeight = 24.sp, // 行高钉住：书架列表行高等几何以此为参数，勿随字号联动
        letterSpacing = 0.15.sp,
    )

    val titleSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp, // 14→16：随阶梯上抬，与 titleMedium 拉开一档
        lineHeight = 20.sp, // 网格标题高参数（BookshelfScreen titleStyle 直读）
        letterSpacing = 0.1.sp,
    )

    /**
     * Body styles：成段内容文字，三档统一 Normal——合成加粗在墨水屏上易糊，
     * 且全粗即无层级。阶梯 18/16/14 纯靠尺寸（14sp 下限把 Material 的
     * 16/14/12 整体 +2）。元数据如需视觉弱化，用灰阶 token
     * （secondaryContent/tertiaryContent）做减法，不用字重做加法。
     */
    val bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.5.sp,
    )

    val bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp, // 行高钉住：书架列表作者/进度/最新章节行几何参数
        letterSpacing = 0.25.sp,
    )

    val bodySmall = TextStyle(
        // 与 body 族统一 Normal：字重倒挂会让 small 压过 medium（真机反馈的
        // 层级问题）；也不用 Medium(500)——部分 ROM 的系统字体替换不覆盖
        // medium 字重档，会落回未替换的原生黑体（真机实证）
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    )

    /** Label styles for buttons and interactive elements. */
    val labelLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp, // 14→16：label 的 large 档随 body 上抬保持一档之差
        lineHeight = 20.sp, // 行高钉住：按钮/页码指示器高度不随抬档增长
        letterSpacing = 0.1.sp,
    )

    val labelMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp, // 贴 14sp 下限（Material 12 被钳制）：进度行/滑条标签等最小标签
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    )

    val labelSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp, // 贴 14sp 下限（Material 11 被钳制）：角标/刻度等
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    )
}
