package io.legado.app.eink.feature.home

/**
 * 发布说明 markdown → E-Ink 更新弹层纯文本。
 *
 * 更新弹层不引入 markdown 渲染（墨水屏上图片/表格渲染价值低），只做
 * 语法符号的最小剥离：标题 `#` 前缀、粗体 `**`、链接 `[文字](地址)` →
 * 文字、列表行 `* `-`- `-`+ ` 前缀、`----` 分隔线 → 空行；连续空行压成
 * 单个、首尾空白裁掉。不做更深的语法理解（代码块/引用/表格等保留原样）。
 */
internal fun releaseNoteToPlainText(note: String): String {
    return note.lines()
        .map(::plainNoteLine)
        .map(String::trim)
        .let(::squeezeBlankNoteLines)
}

private val noteHeading = Regex("^#{1,6}\\s+")
private val noteBullet = Regex("^[*_+\\-]\\s+")
private val noteDivider = Regex("^[-*_]{3,}$")
private val noteBold = Regex("\\*\\*(.+?)\\*\\*")
private val noteLink = Regex("\\[([^]]+)]\\([^)]*\\)")

private fun plainNoteLine(line: String): String {
    var text = line.trim()
    if (noteDivider.matches(text)) return ""
    text = text.replaceFirst(noteHeading, "")
    text = text.replaceFirst(noteBullet, "")
    text = noteBold.replace(text, "$1")
    text = noteLink.replace(text, "$1")
    return text
}

private fun squeezeBlankNoteLines(lines: List<String>): String {
    val result = mutableListOf<String>()
    var lastBlank = true // 首部空行一并压掉
    for (line in lines) {
        val blank = line.isEmpty()
        if (blank && lastBlank) continue
        result.add(line)
        lastBlank = blank
    }
    return result.joinToString("\n").trimEnd()
}
