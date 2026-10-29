package io.legado.app.eink.feature.common

/**
 * 文字占位封面（方案 A「题签」式）的文本解析结果。
 *
 * @param tag 书名前缀【…】整段提取出的标签（【五夏/R】→ "五夏/R"），
 *   多个连续前缀以空格连接；无前缀为 null。
 * @param title 书名主体：剥去前缀标签与尾部括号副标题（(…)/（…），如
 *   "曾用标题:…"、"连载中"、"pwp"）后的剩余部分。
 */
data class EInkCoverText(val tag: String?, val title: String)

/** 题签标签的括号对：前缀识别按开括号匹配对应闭括号，混用不成对则不拆。 */
private const val COVER_TAG_OPENERS = "【「『"
private const val COVER_TAG_CLOSERS = "】」』"

/** 尾部副标题的圆括号对（全角/半角），与标签括号区分对待：副标题直接丢弃。 */
private const val COVER_PAREN_OPENERS = "（("
private const val COVER_PAREN_CLOSERS = "）)"

/** 截断补「…」前清掉的句尾标点：题签竖排里“标点+省略号”连排很难看。 */
private const val COVER_TAIL_PUNCT = "，、。：；！？…·,.;:!?~—～"

/**
 * 解析占位封面文本：前缀【…】提取为标签列，尾部 (…)/（…）副标题直接
 * 丢弃，其余为书名主体。剥完为空时回退为去空白原名并置空标签——保证
 * 任何输入下 title 非空（除非 name 本身空白），绘制侧无需再兜底。
 *
 * 仅当括号出现在头部/尾部才处理：AO3 书名的标签与副标题位置固定，
 * 书名中间的括号（如「夜行山路，当心狐妖(pwp)」的正文部分）不受影响。
 */
fun parseEInkCoverText(name: String): EInkCoverText {
    var s = name.trim()
    val tags = mutableListOf<String>()

    // 前缀【…】：可连续多个（【A】【B】题 → 标签 "A B"），逐个剥掉
    while (s.isNotEmpty() && s.first() in COVER_TAG_OPENERS) {
        val closer = COVER_TAG_CLOSERS[COVER_TAG_OPENERS.indexOf(s.first())]
        val end = s.indexOf(closer, 1)
        if (end <= 0) break
        tags.add(s.substring(1, end).trim())
        s = s.substring(end + 1).trim()
    }

    // 尾部 (…)/（…）：可连续多个（"(pwp) (连载)"尾排），逐层剥掉；
    // 只有闭没有开（笑脸 ":)"）时停止，不误伤书名
    while (s.isNotEmpty() && s.last() in COVER_PAREN_CLOSERS) {
        val opener = COVER_PAREN_OPENERS[COVER_PAREN_CLOSERS.indexOf(s.last())]
        val start = s.lastIndexOf(opener)
        if (start < 0) break
        s = s.substring(0, start).trim()
    }

    if (s.isEmpty()) {
        return EInkCoverText(tag = null, title = name.trim())
    }
    val tag = tags.filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }
    return EInkCoverText(tag = tag, title = s)
}

/**
 * 截断到 [maxChars] 个展示位以内：超长时截取 maxChars-1 个字符、清掉
 * 截点残留的句尾标点后补「…」——「夜行山路，…」比「夜行山路…」多一个
 * 悬空逗号位。maxChars < 2 时不截（调用方按题签列容量传入，实际恒 ≥ 2）。
 */
fun truncateEInkCoverTitle(title: String, maxChars: Int): String {
    if (maxChars < 2 || title.length <= maxChars) return title
    val head = title.substring(0, maxChars - 1).trimEnd { it in COVER_TAIL_PUNCT }
    return head + "…"
}
