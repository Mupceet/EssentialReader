package io.legado.app.eink.contract

/**
 * 字体取值：系统预设 / 字体文件 / 跟随正文。
 *
 * FollowBody 仅对标题/页眉合法：宿主实现把它展开为正文当前有效字体
 * （正文为系统预设时无路径可写，宿主回落系统默认字体）。
 */
sealed interface ReaderFontSelection {
    data object Sans : ReaderFontSelection
    data object Serif : ReaderFontSelection
    data object Mono : ReaderFontSelection
    data class File(val path: String) : ReaderFontSelection
    data object FollowBody : ReaderFontSelection
}

/** 可选字体文件（宿主字体文件夹枚举项；path 为文件 uri/路径字符串）。 */
data class ReaderFontOption(val name: String, val path: String)
