package io.legado.app.data.entities

import io.legado.app.help.RuleBigDataHelp
import io.legado.app.model.analyzeRule.RuleDataInterface
import io.legado.app.utils.GSON
import io.legado.app.utils.splitNotBlank

/**
 * 书籍基础接口，定义书籍实体的公共属性和方法
 */
interface BaseBook : RuleDataInterface {
    // 书名
    var name: String
    // 作者
    var author: String
    // 书籍URL
    var bookUrl: String
    // 分类
    var kind: String?
    // 字数
    var wordCount: String?
    // 自定义变量（JSON格式）
    var variable: String?

    // 详情页HTML
    var infoHtml: String?
    // 目录页HTML
    var tocHtml: String?

    override fun putVariable(key: String, value: String?): Boolean {
        if (super.putVariable(key, value)) {
            variable = GSON.toJson(variableMap)
        }
        return true
    }

    fun putCustomVariable(value: String?) {
        putVariable("custom", value)
    }

    fun getCustomVariable(): String {
        return getVariable("custom")
    }

    override fun putBigVariable(key: String, value: String?) {
        RuleBigDataHelp.putBookVariable(bookUrl, key, value)
    }

    override fun getBigVariable(key: String): String? {
        return RuleBigDataHelp.getBookVariable(bookUrl, key)
    }

    fun getKindList(): List<String> {
        val kindList = arrayListOf<String>()
        wordCount?.let {
            if (it.isNotBlank()) kindList.add(it)
        }
        kind?.let {
            val kinds = it.splitNotBlank(",", "\n")
            kindList.addAll(kinds)
        }
        return kindList
    }
}