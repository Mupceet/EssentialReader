package io.legado.app.eink.feature.reader

import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParam
import kotlin.math.roundToInt

/** 目录 → UI 呈现辅助：值域/默认档/可用性（步进粒度与标签由调用方定）。 */

internal fun ReaderStyleCatalog.available(id: String): Boolean =
    find(id)?.available ?: false

private fun ReaderStyleCatalog.stepped(id: String): ReaderStyleParam.Stepped? =
    find(id) as? ReaderStyleParam.Stepped

/** 整型值域（参数缺失时 0..0，仅作展示兜底，不用于钳制）。 */
internal fun ReaderStyleCatalog.intRange(id: String): IntRange {
    val p = stepped(id) ?: return 0..0
    return p.min.roundToInt()..p.max.roundToInt()
}

/** 整型钳制（参数缺失时不钳制，原值透传）。 */
internal fun ReaderStyleCatalog.clampInt(id: String, value: Int): Int {
    val p = stepped(id) ?: return value
    return value.coerceIn(p.min.roundToInt(), p.max.roundToInt())
}

/** 整型默认值。 */
internal fun ReaderStyleCatalog.defaultInt(id: String): Int =
    stepped(id)?.default?.roundToInt() ?: 0

/** 「默认」标识档位（在值域内才显示）。 */
internal fun ReaderStyleCatalog.defaultStep(id: String): Int? {
    val p = stepped(id) ?: return null
    val d = p.default.roundToInt()
    return d.takeIf { it in p.min.roundToInt()..p.max.roundToInt() }
}

/** 浮点参数按步进映射为整型档位域（如字距 -0.5..0.5、步进 0.05 → -10..10）。 */
internal fun ReaderStyleCatalog.floatStepIndexRange(id: String, step: Float): IntRange {
    val p = stepped(id) ?: return 0..0
    return (p.min / step).roundToInt()..(p.max / step).roundToInt()
}

/** 浮点参数默认档位。 */
internal fun ReaderStyleCatalog.floatDefaultStep(id: String, step: Float): Int? {
    val p = stepped(id) ?: return null
    val d = (p.default / step).roundToInt()
    return d.takeIf { it in floatStepIndexRange(id, step) }
}
