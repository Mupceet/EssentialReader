package io.legado.app.eink.designsystem.pager

/**
 * 自定义翻页键码串编解码（纯函数）：存储格式沿用宿主完整模式
 * PreferKey.prevKeyCodes/nextKeyCodes——逗号分隔十进制键码（如
 * "92,93"），默认空串。按键枢纽判定与「翻页设置」页共用同一套解析，
 * 录入、反显、判定三者对同一存储值解释一致。
 *
 * 解析宽松（容错空段/空格/非数字段/重复键码），与宿主完整模式
 * `split(",").contains(keyCode.toString())` 的读取语义兼容：宿主侧
 * 精确匹配不受宽松解析影响，模块侧非法段直接丢弃。
 */
internal fun parseKeyCodes(encoded: String): Set<Int> =
    encoded.split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it > 0 }
        .toSet()

/** 键码集合 → 逗号分隔串（宿主同格式）；保持入参顺序，调用方负责去重。 */
internal fun formatKeyCodes(keyCodes: Collection<Int>): String =
    keyCodes.joinToString(separator = ",")
