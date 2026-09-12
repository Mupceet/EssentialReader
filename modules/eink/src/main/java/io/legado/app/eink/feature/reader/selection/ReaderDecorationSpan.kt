package io.legado.app.eink.feature.reader.selection

import io.legado.app.eink.contract.ReaderDecorationRun
import io.legado.app.eink.contract.ReaderPageLine

/**
 * 装饰 run → 行内 x 跨度（绘制用）。
 *
 * run 为行内拼接文本的 UTF-16 半开区间 [start, end)，x 换算与选区高亮带
 * 同一把尺（[charX] 逐字符前缀宽累加，段首从 [ReaderPageLine.x] 起算）。
 *
 * 越界（快照行文本变化后残留 run、或宿主映射异常）返回 null，绘制侧跳过，
 * 不按行尾钳制——残留 run 的长度不可信，宁缺勿错。
 *
 * 段首缩进（行首空白）经 [inkRange] 从句头剔掉：缩进是排版真实字符，
 * 标记区间含它时线/高亮会盖到那段空白上（真机反馈「段首的空白也有灰色
 * 背景及画线」）；区间余下全为空白时返回 null（不落墨迹）。
 */
fun decorationSpanX(
    line: ReaderPageLine,
    run: ReaderDecorationRun,
    measure: (String) -> Float,
): Pair<Float, Float>? {
    val text = lineText(line)
    if (run.start < 0 || run.end > text.length || run.start >= run.end) return null
    val (from, to) = inkRange(text, run.start, run.end) ?: return null
    return charX(line, from, measure) to charX(line, to, measure)
}
