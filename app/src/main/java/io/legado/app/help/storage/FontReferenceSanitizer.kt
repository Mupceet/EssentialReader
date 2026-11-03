package io.legado.app.help.storage

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.legado.app.utils.GSON

/**
 * 备份 JSON 内嵌字体文件引用的「设备本地忽略」清洗（谓词注入，保持纯函数便于 JVM 单测）。
 *
 * 字体选择的值是设备本地资源指针——SAF 文档 URI（content://）、应用私有字体目录
 * 路径（file:///绝对路径）或裸绝对路径，字体文件本身不随备份走，跨设备恢复后
 * 通常悬空。偏好键层面的同类指针（[io.legado.app.constant.PreferKey] 的
 * fontFolder/appFontPath 与 eink 最近字体键）已由 deviceLocalPreferenceKeys 与
 * EinkLegacyPrefsStore 的 crossDeviceLocalKeys 按「备份照常导出、仅跨设备恢复
 * 跳过」处理；本对象把同一语义补到 readConfig.json / shareReadConfig.json /
 * highlightRule.json 的内嵌字段——跨设备恢复时置空这些字段（阅读器回落系统预设
 * 字体），assets:// 内置字体引用、空值与其余所有字段（含未来版本未知字段）
 * 原样保留。
 *
 * 置空前先问 [referenceExists]（目标设备可读性谓词）：两台设备同路径同字体、
 * 主题包导入字体（applicationId 相同 → 私有目录路径逐字符一致）等可移植引用
 * 原样保留，只清洗目标机确认不可读的悬空引用。默认谓词恒 false（一律视为
 * 悬空，等价于不检查的保守行为），调用方（Restore）注入真实检查。
 */
internal object FontReferenceSanitizer {

    /** 排版配置（Config/ShareConfig）中的字体文件引用字段；字段非空类型，清洗值置 ""。 */
    private val configFontKeys = setOf("textFont", "titleFont", "headerFont", "footerFont")

    /** 高亮规则中的字体覆盖字段；可空类型，清洗值置 null。 */
    private const val ruleFontKey = "fontPath"

    /**
     * 递归把 [json] 中目标设备不可读的字体文件引用字段置空。
     *
     * @param referenceExists 引用可读性谓词（目标设备视角；恢复期由调用方注入，
     *        见 Restore.fontReferenceReadable——与 ReaderAndroidPaintFactory.loadTypeface
     *        的解析形态一一对应）
     * @return 有字段被清洗时返回改写后的 JSON 文本；没有可清洗引用时返回 null
     *         （调用方据此保持文件字节不变，同机恢复零扰动）。
     */
    fun sanitizeForCrossDeviceRestore(
        json: String,
        referenceExists: (String) -> Boolean = { false },
    ): String? {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return null
        var stripped = 0
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> element.entrySet().forEach { (key, value) ->
                    val replacement = replacementFor(key, value, referenceExists)
                    if (replacement != null) {
                        element.add(key, replacement)
                        stripped++
                    } else {
                        walk(value)
                    }
                }

                is JsonArray -> element.forEach(::walk)

                else -> Unit
            }
        }
        walk(root)
        if (stripped == 0) return null
        return GSON.toJson(root)
    }

    /**
     * 命中字体引用键且值是字符串形式的外置文件指针、且目标设备不可读时返回清洗值；
     * assets:// 内置字体、空值、非字符串值（其他变体可能有同名异型键）、未命中键与
     * 目标设备仍可读的引用返回 null（调用方继续向值内部递归，原样保留）。
     */
    private fun replacementFor(
        key: String,
        value: JsonElement,
        referenceExists: (String) -> Boolean,
    ): JsonElement? {
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
        val reference = value.asString
        if (reference.isBlank() || reference.startsWith("assets://")) return null
        return when (key) {
            in configFontKeys, ruleFontKey ->
                if (referenceExists(reference)) null
                else if (key == ruleFontKey) JsonNull.INSTANCE
                else JsonPrimitive("")
            else -> null
        }
    }
}
