package io.legado.app.help.storage

import com.google.gson.JsonNull
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨设备恢复时备份 JSON 内嵌字体文件引用的清洗语义：
 * 五种引用字段置空（配置字段 → ""，规则字段 → null）、assets:// 内置字体与空值
 * 保留、其余字段（含未来版本未知字段）零丢失、无可清洗引用时返回 null 保持文件不变。
 */
class FontReferenceSanitizerTest {

    private fun sanitize(json: String) =
        FontReferenceSanitizer.sanitizeForCrossDeviceRestore(json)

    @Test
    fun `strips config font references and nested highlight rule fonts`() {
        val json = """
            [
              {
                "name": "默认",
                "textSize": 21,
                "textFont": "content://com.android.externalstorage.documents/tree/abc/document/primary%3Afonts%2Fkaiti.ttf",
                "titleFont": "file:///storage/emulated/0/Android/data/io.legato.kazusa.eink/files/font/hei.ttf",
                "headerFont": "/storage/emulated/0/Android/data/io.legato.kazusa.eink/files/font/song.otf",
                "footerFont": "content://providers/xxx/document/2",
                "highlightRules": [
                  {"pattern": "对话", "fontPath": "content://com.android.externalstorage.documents/tree/abc/document/primary%3Afonts%2Fkaiti.ttf"},
                  {"pattern": "书名", "fontPath": null}
                ]
              },
              {"name": "夜间", "textFont": ""}
            ]
        """.trimIndent()

        val result = JsonParser.parseString(sanitize(json)!!).asJsonArray

        val first = result[0].asJsonObject
        assertEquals("默认", first["name"].asString)
        assertEquals(21, first["textSize"].asInt)
        assertEquals("", first["textFont"].asString)
        assertEquals("", first["titleFont"].asString)
        assertEquals("", first["headerFont"].asString)
        assertEquals("", first["footerFont"].asString)
        val rules = first["highlightRules"].asJsonArray
        // 清洗值置 null：重序列化后成员可能被省略（GSON 默认不写 null），两种形态都是无引用
        listOf(rules[0], rules[1]).forEach { rule ->
            val fontPath = rule.asJsonObject.get("fontPath")
            assertTrue(fontPath == null || fontPath.isJsonNull)
        }

        // 空引用本就无字体可失，保持 "" 不动
        assertEquals("", result[1].asJsonObject["textFont"].asString)
    }

    @Test
    fun `strips share config single object`() {
        val json = """
            {"name": "分享", "textFont": "file:///storage/emulated/0/Android/data/io.legato.kazusa.eink/files/font/kai.ttf"}
        """.trimIndent()

        val result = JsonParser.parseString(sanitize(json)!!).asJsonObject

        assertEquals("分享", result["name"].asString)
        assertEquals("", result["textFont"].asString)
    }

    @Test
    fun `keeps assets and blank references without rewrite`() {
        val json = """
            [{"textFont": "", "fontPath": "assets://fonts/bundled.ttf", "textSize": 20}]
        """.trimIndent()

        // assets:// 指向内置字体（设备无关），空值本就无引用：整份无可清洗，返回 null
        assertNull(sanitize(json))
    }

    @Test
    fun `returns null when no font references at all`() {
        assertNull(sanitize("""[{"name": "默认", "textSize": 20, "bgStr": "#FFFFFF"}]"""))
        assertNull(sanitize("""not a json"""))
    }

    @Test
    fun `preserves unknown fields from newer backup versions`() {
        val json = """
            [{"textFont": "content://x/document/f.ttf", "futureField": {"a": [1, 2.5, true, null], "b": "值"}, "futureList": [ {"deep": "content://y/document/g.ttf"} ]}]
        """.trimIndent()

        val result = JsonParser.parseString(sanitize(json)!!).asJsonArray
        val config = result[0].asJsonObject

        val future = config["futureField"].asJsonObject
        assertEquals(4, future["a"].asJsonArray.size())
        assertEquals(1, future["a"].asJsonArray[0].asInt)
        assertEquals(2.5, future["a"].asJsonArray[1].asDouble, 0.0)
        assertEquals(true, future["a"].asJsonArray[2].asBoolean)
        assertEquals(JsonNull.INSTANCE, future["a"].asJsonArray[3])
        assertEquals("值", future["b"].asString)

        // 未知结构里的键名不等于字体引用键，不误伤其内容
        assertEquals(
            "content://y/document/g.ttf",
            config["futureList"].asJsonArray[0].asJsonObject["deep"].asString
        )
    }

    @Test
    fun `sanitized result is idempotent`() {
        val json = """
            {"textFont": "content://x/document/f.ttf", "highlightRules": [{"fontPath": "file:///storage/emulated/0/Android/data/io.legato.kazusa.eink/files/font/a.ttf"}]}
        """.trimIndent()

        val once = sanitize(json)!!
        assertNull(sanitize(once))
    }

    @Test
    fun `non string font fields are left untouched`() {
        val json = """{"textFont": 123, "fontPath": true}"""

        assertNull(sanitize(json))
    }

    // ---- 存在性谓词：目标机可读的引用保留，仅悬空引用置空 ----

    private val knownFonts = listOf(
        // 主题包导入字体：applicationId 相同 → 两台设备路径逐字符一致
        "/storage/emulated/0/Android/data/io.legato.kazusa.eink/files/font/theme.ttf",
        "file:///storage/emulated/0/Android/data/io.legato.kazusa.eink/files/font/hei.ttf",
    )

    private fun sanitizeWithExists(json: String) =
        FontReferenceSanitizer.sanitizeForCrossDeviceRestore(json) { it in knownFonts }

    @Test
    fun `readable references survive and dangling ones are stripped`() {
        val json = """
            {"textFont": "${knownFonts.first()}", "titleFont": "content://other/device/x.ttf"}
        """.trimIndent()

        val result = JsonParser.parseString(sanitizeWithExists(json)!!).asJsonObject

        // 目标机仍可读的主题包字体原样保留；异机 SAF URI（无持久化权限）置空
        assertEquals(knownFonts.first(), result["textFont"].asString)
        assertEquals("", result["titleFont"].asString)
    }

    @Test
    fun `all references readable keeps file byte identical`() {
        val json = """
            {"textFont": "${knownFonts[0]}", "highlightRules": [{"fontPath": "${knownFonts[1]}"}]}
        """.trimIndent()

        assertNull(sanitizeWithExists(json))
    }

    @Test
    fun `default predicate stays conservative`() {
        // 不注入谓词 = 恒不可读 = 全部置空（等价于存在性检查引入前的行为）
        val json = """{"textFont": "${knownFonts.first()}"}"""
        val result = JsonParser.parseString(sanitize(json)!!).asJsonObject
        assertEquals("", result["textFont"].asString)
    }
}
