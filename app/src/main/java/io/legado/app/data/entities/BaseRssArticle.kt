package io.legado.app.data.entities

import io.legado.app.help.RuleBigDataHelp
import io.legado.app.model.analyzeRule.RuleDataInterface
import io.legado.app.utils.GSON

/**
 * RSS文章基础接口，定义RSS文章实体的公共属性和方法
 */
interface BaseRssArticle : RuleDataInterface {

    // RSS源URL
    var origin: String
    // 文章链接
    var link: String

    // 自定义变量
    var variable: String?

    override fun putVariable(key: String, value: String?): Boolean {
        if (super.putVariable(key, value)) {
            variable = GSON.toJson(variableMap)
        }
        return true
    }

    override fun putBigVariable(key: String, value: String?) {
        RuleBigDataHelp.putRssVariable(origin, link, key, value)
    }

    override fun getBigVariable(key: String): String? {
        return RuleBigDataHelp.getRssVariable(origin, link, key)
    }

}