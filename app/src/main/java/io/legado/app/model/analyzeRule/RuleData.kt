package io.legado.app.model.analyzeRule

import io.legado.app.utils.GSON

/**
 * 规则数据容器，存储规则解析过程中的变量和Put/Get操作结果，实现RuleDataInterface接口。
 */
class RuleData : RuleDataInterface {

    override val variableMap by lazy {
        hashMapOf<String, String>()
    }

    override fun putBigVariable(key: String, value: String?) {
        if (value == null) {
            variableMap.remove(key)
        } else {
            variableMap[key] = value
        }
    }

    override fun getBigVariable(key: String): String? {
        return null
    }

    fun getVariable(): String? {
        if (variableMap.isEmpty()) {
            return null
        }
        return GSON.toJson(variableMap)
    }

}